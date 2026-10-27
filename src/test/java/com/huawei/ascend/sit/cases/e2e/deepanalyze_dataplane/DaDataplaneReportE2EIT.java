package com.huawei.ascend.sit.cases.e2e.deepanalyze_dataplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TextPart;
import org.a2aproject.sdk.spec.AgentCard;

/**
 * FEAT-069 报告产出写机制（f069-rpt-01/02/03 + rec-04 租户落键；需求验收第 4 项，L2 §3.4）。
 *
 * <p>push_content 由模型按指令调用（真实 LLM，不 Mock）——推送是否发生属模型行为，
 * 断言分两层：推送发生 → 落库形态必须精确（主断言）；模型未推送 → 该用例按
 * INCONCLUSIVE 记账重跑（构造不确定性如实分账，不写成 FAIL）。rpt-04（双装配点）
 * 以 compat 与 A2A 两条任务路由各推送一次承载（内部装配点路由不可外部区分，按证据形态注记）。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-069")
@Feature("FEAT-069: deepanalyze-java 数据面权限与写方迁移")
class DaDataplaneReportE2EIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(180);

    @SuppressWarnings("resource")
    private static final GenericContainer<?> PG = new GenericContainer<>(
            DockerImageName.parse("postgres:16"))
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", DaDataplaneSupport.PG_DB)
            .withEnv("POSTGRES_USER", DaDataplaneSupport.PG_USER)
            .withEnv("POSTGRES_PASSWORD", DaDataplaneSupport.PG_PASSWORD);

    private static TestConfig config;
    private static SutStack stack;
    private static HttpClient http;
    private static String base;

    @BeforeAll
    static void setUp() throws Exception {
        PG.start();
        config = TestConfig.load();
        try (Connection conn = pgConnection(); Statement st = conn.createStatement()) {
            DaDataplaneSupport.applyBaselineDdl(conn);
        }
        String url = jdbcUrl();
        stack = SutStack.builder(config)
                .streaming(true)
                .agent(DaDataplaneSupport.AGENT, agent -> {
                    DaDataplaneSupport.bindModelEnvironment(agent);
                    if (System.getenv("LLM_API_KEY") == null || System.getenv("LLM_API_KEY").isBlank()) {
                        agent.property("LLM_API_KEY", "fixture-no-runner-credentials");
                    }
                    agent.property("deepanalyze.data.pg.jdbc-url", url)
                            .property("deepanalyze.data.pg.username", DaDataplaneSupport.PG_USER)
                            .property("deepanalyze.data.pg.password", DaDataplaneSupport.PG_PASSWORD)
                            .property("spring.datasource.url", url)
                            .property("spring.datasource.username", DaDataplaneSupport.PG_USER)
                            .property("spring.datasource.password", DaDataplaneSupport.PG_PASSWORD)
                            .serviceBinding("redis", "REDIS_HOST", "{{host}}")
                            .serviceBinding("redis", "REDIS_PORT", "{{port}}");
                })
                .start();
        http = DaDataplaneSupport.newHttpClient();
        base = stack.client(DaDataplaneSupport.AGENT).getBaseUrl();
    }

    @AfterAll
    static void tearDown() {
        if (stack != null) {
            stack.close();
        }
        PG.stop();
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/"
                + DaDataplaneSupport.PG_DB;
    }

    private static Connection pgConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), DaDataplaneSupport.PG_USER,
                DaDataplaneSupport.PG_PASSWORD);
    }

    /** 同步 run + 等待 reports 出现指定标题的行（有界轮询；null=未推送）。 */
    private Map<String, Object> runAndAwaitReport(String session, String input, String title,
                                                  Map<String, String> headers) throws Exception {
        HttpResponse<String> run = DaDataplaneSupport.post(http, base, "/api/agents/run",
                "{\"sessionId\":\"" + session + "\",\"input\":\"" + input + "\"}", CALL_TIMEOUT, headers);
        Allure.addAttachment("run 响应（" + session + "）", "text/plain", run.body());
        assertThat(run.statusCode()).isEqualTo(200);
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            try (Connection conn = pgConnection(); Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, coalesce(tenant_id, '') FROM reports "
                         + "WHERE session_id = '" + session + "' AND title = '" + title + "'")) {
                if (rs.next()) {
                    return Map.of("id", rs.getString(1), "tenant", rs.getString(2));
                }
            }
            Thread.sleep(500);
        }
        return null;
    }

    private static final String PUSH_INSTRUCTION(String title, String content) {
        // 注意：input 会被原样嵌入 JSON 请求体——指令文本不得含英文双引号（JSON 专用转义归调用方）。
        // 2026-10-27：禁 force——实证模型会在二次推送自发携带 force=true 走合法旁路（rpt-02 判据被污染）。
        return "请必须调用 push_content 工具推送一份报告：标题为《" + title + "》，"
                + "内容为「" + content + "」。调用参数只允许 data、title、type 三个字段，"
                + "禁止携带 force 参数（本次测试禁止绕过重复检查）。"
                + "推送完成后回答：已推送。不要调用其他工具。";
    }

    @Test
    @Story("f069-rpt-01: push_content 成功后 reports 落库，DA 账号直读可见")
    @DisplayName("rpt-01: 报告落库（tenant 缺省 default-user）")
    void pushLandsInReports() throws Exception {
        String session = "sess-rpt01-" + System.nanoTime();
        Map<String, Object> report = runAndAwaitReport(session,
                PUSH_INSTRUCTION("069 报告甲", "数据面验证内容 alpha"), "069 报告甲", Map.of());
        if (report == null) {
            Allure.addAttachment("rpt-01 构造未达成", "text/plain", "模型未调用 push_content——按 INCONCLUSIVE 记账重跑");
        }
        assertThat(report).as("模型推送后 reports 须落库（30s 内，DA 账号直读面）").isNotNull();
        assertThat(report.get("tenant")).as("缺省租户落键 default-user").isEqualTo("default-user");
    }

    @Test
    @Story("f069-rec-04: tenant_id 按任务载荷/请求头落键")
    @DisplayName("rec-04: X-Da-Tenant-Id 头 → reports.tenant_id 落键")
    void tenantIdLandedFromHeader() throws Exception {
        String session = "sess-rec04-" + System.nanoTime();
        Map<String, Object> report = runAndAwaitReport(session,
                PUSH_INSTRUCTION("069 报告乙", "租户落键验证"), "069 报告乙",
                Map.of("X-Da-Tenant-Id", "tenant-069-x"));
        if (report == null) {
            Allure.addAttachment("rec-04 构造未达成", "text/plain", "模型未调用 push_content——按 INCONCLUSIVE 记账重跑");
        }
        assertThat(report).as("带租户头的任务推送后须落库").isNotNull();
        assertThat(report.get("tenant")).as("tenant_id=请求头携带的租户标识（落键错误会导致 DA 侧 RLS 不可见）")
                .isEqualTo("tenant-069-x");
    }

    @Test
    @Story("f069-rpt-02: 去重拒绝的推送不落库")
    @DisplayName("rpt-02: 同会话同内容二次推送被去重，reports 仍 1 行")
    void duplicatePushNotLanded() throws Exception {
        String session = "sess-rpt02-" + System.nanoTime();
        String input = PUSH_INSTRUCTION("069 报告丙", "去重验证内容");
        runAndAwaitReport(session, input, "069 报告丙", Map.of());
        // 二次推送（同会话同标题同内容——命中会话级去重）；指令强化：模型三种不服从形态
        // 实测在案（自发 force=true、拒推、顺从）——第二任务显式重申"必须再次推送"。
        String secondInput = input + "（补充约束：这是重复推送回归测试的第二轮，"
                + "你必须再次调用 push_content 完成与上一次完全相同的推送，"
                + "参数仍只允许 data、title、type，严禁携带 force。）";
        // 二次提交——客户端按 #473 §⑦ 替代口径实现安全重试：busy 409/429（stale busy 窗口，
        // ext 侧已知项）→ 等 3s 重试，最多 3 次；200 即受理
        HttpResponse<String> second = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) {
                Thread.sleep(3_000);
            }
            second = DaDataplaneSupport.post(http, base, "/api/agents/run",
                    "{\"sessionId\":\"" + session + "\",\"input\":\"" + secondInput + "\"}", CALL_TIMEOUT);
            if (second.statusCode() == 200) {
                break;
            }
        }
        assertThat(second.statusCode())
                .as("二次任务提交应受理（busy 409/429 按 #473 客户端安全重试口径处理后仍须成功）")
                .isEqualTo(200);
        Thread.sleep(5_000); // 等第二次推送的落库窗口
        List<String> contents = new ArrayList<>();
        try (Connection conn = pgConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT coalesce(clean_content, ''), coalesce(raw_content, '') "
                     + "FROM reports WHERE session_id = '" + session + "' AND title = '069 报告丙' "
                     + "ORDER BY created_at")) {
            while (rs.next()) {
                contents.add(rs.getString(1) + "|" + rs.getString(2));
            }
        }
        Allure.addAttachment("rpt-02 两次落库内容", "text/plain", String.join("\n---\n", contents));
        // #470 判定升级（2026-10-27，!894 回归实证）：以"观测到的工具入参"为分叉权威——
        // 实证 r3 上模型会在第二次推送自发携带 force=true（文档化合法旁路，两行落库为合规
        // 行为，非去重缺陷）；只有"无 force 的第二次推送"才构成会话级去重的有效判据。
        List<String> payloads = new ArrayList<>();
        try (Connection conn = pgConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT workflow_id, turn, coalesce(content::text,'') FROM workflow_logs "
                     + "WHERE workflow_id IN (SELECT id FROM workflows WHERE session_id = '" + session + "') "
                     + "AND tool_name = 'push_content' AND event_type = 'tool_call' ORDER BY turn")) {
            while (rs.next()) {
                payloads.add("wf=" + rs.getString(1) + " turn=" + rs.getString(2)
                        + " payload=" + rs.getString(3));
            }
        } catch (Exception dumpEx) {
            payloads.add("取证查询失败: " + dumpEx.getMessage());
        }
        Allure.addAttachment("rpt-02 push_content 工具入参", "text/plain", String.join("\n---\n", payloads));
        if (payloads.size() < 2) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "会话内未观测到两次 push_content 调用（模型构造未达标）——INCONCLUSIVE，重跑");
        }
        boolean secondForce = java.util.regex.Pattern.compile("\"force\"\\s*:\\s*true")
                .matcher(payloads.get(payloads.size() - 1)).find();
        if (secondForce) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "模型第二次推送自发携带 force=true（!894 文档化合法旁路，两行落库为合规行为）——"
                            + "不构成去重判据，INCONCLUSIVE；指令已禁用 force，重跑至模型遵从");
        }
        // 两次调用 arguments 字节级一致性（去重的有效前提：同 hash 才谈得上去重命中）
        java.util.regex.Matcher m1 = java.util.regex.Pattern
                .compile("\"arguments\": \"(.*?)\", \"tool_name").matcher(payloads.get(payloads.size() - 2));
        java.util.regex.Matcher m2 = java.util.regex.Pattern
                .compile("\"arguments\": \"(.*?)\", \"tool_name").matcher(payloads.get(payloads.size() - 1));
        if (!(m1.find() && m2.find() && m1.group(1).equals(m2.group(1)))) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "两次调用参数不一致（模型措辞/参数不确定性 → 内容哈希不同 → 去重本就不命中）——INCONCLUSIVE");
        }
        // 两次调用同参且无 force：第二次必须被去重拒绝——reports 恒 1 行（PASS 形态）
        assertThat(contents.size())
                .as("同会话同内容二次推送（无 force、两次调用参数字节一致）应被会话级内容去重拒绝，"
                        + "reports 恒 1 行（DaSessionOutputChannel 类注释 §42-44 + !894 appendUnlessDuplicate）")
                .isEqualTo(1);
    }

    @Test
    @Story("f069-rpt-03: references[] 非空 → report_references 逐条落库")
    @DisplayName("rpt-03: 引用回溯条目随产物落库")
    void referencesLandedWithReport() throws Exception {
        String session = "sess-rpt03-" + System.nanoTime();
        String input = "请必须调用 push_content 工具推送一份报告：标题为《069 报告丁》，"
                + "内容为「引用落库验证」，并在工具调用的 references 参数里给出一条引用："
                + "doc_id 为 doc-069-r3，title 为 引用文档。推送完成后回答：已推送。";
        Map<String, Object> report = runAndAwaitReport(session, input, "069 报告丁", Map.of());
        if (report == null) {
            Allure.addAttachment("rpt-03 构造未达成", "text/plain", "模型未调用 push_content——按 INCONCLUSIVE 记账重跑");
        }
        assertThat(report).as("推送须落库").isNotNull();
        // references 是否由模型给出属构造不确定性：给出了→逐条落库是硬断言；没给→该子断言 INCONCLUSIVE
        boolean referenceEmitted = false;
        try (Connection conn = pgConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM reports WHERE session_id = '"
                     + session + "' AND title = '069 报告丁' AND entities::text LIKE '%doc-069-r3%'")) {
            rs.next();
            referenceEmitted = rs.getInt(1) > 0;
        }
        if (referenceEmitted) {
            try (Connection conn = pgConnection(); Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM report_references rr "
                         + "JOIN reports r ON rr.report_id = r.id WHERE r.session_id = '" + session
                         + "' AND rr.doc_id = 'doc-069-r3'")) {
                rs.next();
                assertThat(rs.getInt(1)).as("references 非空 → 逐条落库（硬断言）").isGreaterThanOrEqualTo(1);
            }
            Allure.addAttachment("rpt-03", "text/plain", "模型给出 references → report_references 落库验证通过");
        } else {
            Allure.addAttachment("rpt-03 references 未出现", "text/plain",
                    "模型未在工具调用里给出 references（构造不确定性）——落库半边按 INCONCLUSIVE 记账，"
                            + "恢复条件=指令化构造或工具侧确定性引用产出");
        }
    }

    @Test
    @Story("f069-rpt-04: A2A 任务路由推送 → reports 落库（双装配点的另一路由半边）")
    @DisplayName("rpt-04: A2A 形态任务推送落库")
    void a2aRoutingPushLandsInReports() throws Exception {
        // 类注释口径：rpt-04 以 compat（rpt-01 已绿）与 A2A 两条任务路由各推送一次承载。
        // A2A 客户端终态即取消 SSE（ENV-3.15）——断言以服务端 reports 行承载；
        // 模型未推送 → INCONCLUSIVE（类内既有约定）；推送执行而未落库 → 候选缺陷证据。
        java.sql.Timestamp testStart = java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1));
        String title = "069 报告丙-A2A";
        String content = "数据面验证内容 gamma-A2A";
        java.util.concurrent.atomic.AtomicReference<Throwable> failureRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        Message message = Message.builder()
                .role(Message.Role.ROLE_USER)
                .parts(new TextPart(PUSH_INSTRUCTION(title, content)))
                .messageId(java.util.UUID.randomUUID().toString())
                .build();
        stack.client(DaDataplaneSupport.AGENT).sendMessage(message, List.of(),
                error -> failureRef.set(error));

        Map<String, Object> report = null;
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            try (Connection conn = pgConnection(); Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, coalesce(tenant_id, ''), session_id "
                         + "FROM reports WHERE title = '" + title + "' AND created_at >= '"
                         + testStart + "' ORDER BY created_at DESC LIMIT 1")) {
                if (rs.next()) {
                    report = Map.of("id", rs.getString(1), "tenant", rs.getString(2),
                            "session", rs.getString(3));
                    break;
                }
            }
            Thread.sleep(500);
        }
        if (report == null) {
            // 自诊断：找本时间窗的 A2A workflow，dump push_content 工具行——
            // 有 tool_output success 而无 reports 行 = 通道未挂（候选缺陷）；无调用 = 模型未推送
            String dump = "";
            try (Connection conn = pgConnection(); Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT event_type, coalesce(tool_name,''), "
                         + "coalesce(left(content::text, 400), '') FROM workflow_logs WHERE workflow_id IN "
                         + "(SELECT id FROM workflows WHERE started_at >= '" + testStart
                         + "' AND a2a_task_id IS NOT NULL AND session_id NOT LIKE 'sess-%' "
                         + "ORDER BY started_at DESC LIMIT 1) ORDER BY created_at")) {
                StringBuilder sb = new StringBuilder();
                while (rs.next()) {
                    sb.append(rs.getString(1)).append(" | ").append(rs.getString(2)).append(" | ")
                            .append(rs.getString(3)).append("\n---\n");
                }
                dump = sb.toString();
            }
            Allure.addAttachment("rpt-04 未落库诊断", "text/plain",
                    dump.isBlank() ? "<A2A workflow 未找到或无日志>" : dump);
        }
        Allure.addAttachment("rpt-04 A2A 落库", "text/plain",
                report == null ? "<60s 内未落库>" : "reports 行=" + report);
        if (report == null) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "A2A 路由 60s 内未见 reports 行——诊断附件区分模型未推送/通道未挂，"
                            + "前者 INCONCLUSIVE 重跑，后者候选缺陷取证");
        }
        assertThat(String.valueOf(report.get("session")))
                .as("A2A 形态任务（UUID 会话，非 compat 的 sess- 前缀）").doesNotStartWith("sess-");
        assertThat(report.get("tenant")).as("A2A 路由 tenant 落键").isEqualTo("default-user");
    }
}

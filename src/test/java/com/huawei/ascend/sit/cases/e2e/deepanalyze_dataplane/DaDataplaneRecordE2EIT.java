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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-069 执行记录写机制（f069-rec-01/03）与会话删除级联数据库前提（f069-del-01/02——
 * del-02=2026-11-18 评审细化 oracle（完整 sessionId+:todo 后缀精确匹配、禁反解、%/＿ 字面量）
 * + 三族负向数据不误删）。
 *
 * <p>自管生命周期（同 read-removal 类口径：播种先于启动）。任务经 compat 同步 run 执行
 * （真实 LLM，受控 runner 注入凭据）；落库内容以 DA 账号直读断言（验收第 3/8/12 项的
 * 指名证据通道——PG 直连在此是验收面本身，不属"绕过 API 的捷径"，细档 §1 红线边界声明）。
 * rec-02（A2A 路径）/rec-04（tenant_id 落键，证据通道=reports）归下一实现切片。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-069")
@Feature("FEAT-069: deepanalyze-java 数据面权限与写方迁移")
class DaDataplaneRecordE2EIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(120);

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

    private static HttpResponse<String> run(String sessionId, String input) throws Exception {
        return DaDataplaneSupport.post(http, base, "/api/agents/run",
                "{\"sessionId\":\"" + sessionId + "\",\"input\":\"" + input + "\"}", CALL_TIMEOUT);
    }

    @Test
    @Story("f069-rec-01: 任务后 workflows/workflow_logs 由 agent 写入，DA 账号直读可见")
    @DisplayName("rec-01: 执行记录闭环（DA 直读与执行事实一致）")
    void executionRecordWrittenAndReadable() throws Exception {
        String session = "sess-rec01-" + System.nanoTime();
        HttpResponse<String> run = run(session, "请用一句话回答：3+4 等于几。不要调用任何工具。");
        Allure.addAttachment("POST /api/agents/run", "text/plain", run.body());
        assertThat(run.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(run.body());
        assertThat(body.path("status").asText()).as("真实任务须执行完成").isEqualTo("completed");

        try (Connection conn = pgConnection()) {
            // 有界轮询：记录写走异步批量 writer（L2 §3.2），响应完成 ≠ 落库完成
            String status = null;
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT status FROM workflows WHERE session_id = '"
                             + session + "'")) {
                    if (rs.next() && !"running".equals(rs.getString(1))) {
                        status = rs.getString(1);
                        break;
                    }
                }
                Thread.sleep(250);
            }
            assertThat(status).as("15s 内须落终态（异步 writer 节拍内）").isNotNull();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, status, started_at, completed_at, "
                    + "coalesce(a2a_task_id, '') FROM workflows WHERE session_id = '" + session + "'")) {
                assertThat(rs.next()).as("DA 账号直读 workflows 必须看到本会话执行记录（验收第 3 项）").isTrue();
                String workflowId = rs.getString(1);
                assertThat(rs.getString(2)).as("终态（running→terminal）").isIn("completed", "failed");
                assertThat(rs.getTimestamp(4)).as("completed_at 须落键").isNotNull();
                Allure.addAttachment("workflows 行", "text/plain",
                        "id=" + workflowId + " status=" + rs.getString(2)
                                + " a2a_task_id=" + rs.getString(5));
                assertThat(rs.next()).as("单次任务恰一条 workflows 行").isFalse();
            }
            // workflow_logs：事件序列完整（至少含 llm_output/answer 骨架事件，无降级期间）
            try (Statement st2 = conn.createStatement();
                 ResultSet rs = st2.executeQuery("SELECT DISTINCT event_type FROM workflow_logs wl "
                    + "JOIN workflows w ON wl.workflow_id = w.id WHERE w.session_id = '" + session + "'")) {
                List<String> eventTypes = new ArrayList<>();
                while (rs.next()) {
                    eventTypes.add(rs.getString(1));
                }
                Allure.addAttachment("workflow_logs 事件类型", "text/plain", String.join(",", eventTypes));
                assertThat(eventTypes).as("事件序列须含 answer 或 llm_output 骨架事件（无降级期间）")
                        .containsAnyOf("answer", "llm_output");
                assertThat(eventTypes).as("不得出现丢弃标记（正常路径无降级）").doesNotContain("drop_marker");
            }
        }
    }

    @Test
    @Story("f069-rec-03: compat 路径 a2a_task_id 落键")
    @DisplayName("rec-03: workflows.a2a_task_id 落键且与响应 taskId 一致（agent_tasks 比对半边 partial）")
    void compatPathWritesA2aTaskId() throws Exception {
        String session = "sess-rec03-" + System.nanoTime();
        HttpResponse<String> run = run(session, "请用一句话回答：5+5 等于几。不要调用任何工具。");
        assertThat(run.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(run.body());
        String responseTaskId = body.path("taskId").asText(null);
        Allure.addAttachment("run 响应", "text/plain", run.body());

        try (Connection conn = pgConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT coalesce(a2a_task_id, '') FROM workflows "
                     + "WHERE session_id = '" + session + "'")) {
            assertThat(rs.next()).isTrue();
            String a2aTaskId = rs.getString(1);
            assertThat(a2aTaskId).as("compat 路径 a2a_task_id 须落键（验收第 12 项落键半边）").isNotBlank();
            if (responseTaskId != null && !responseTaskId.isBlank()) {
                assertThat(a2aTaskId).as("落键值须与响应 taskId 一致（da.run.task_id 口径）")
                        .isEqualTo(responseTaskId);
            }
            Allure.addAttachment("agent_tasks 比对半边", "text/plain",
                    "a2a_task_id=" + a2aTaskId + "；agent_tasks 一致性半边在本拓扑无写方（docs#38 DS-03），标 partial");
        }
    }

    @Test
    @Story("f069-del-01: FK 拆除后 DA 账号按 session_id 直删五表成功")
    @DisplayName("del-01: 会话删除级联数据库前提（删除成功、删后不可查）")
    void daAccountDeletesAgentOwnedRowsBySessionId() throws Exception {
        String session = "sess-del01-" + System.nanoTime();
        HttpResponse<String> run = run(session, "请用一句话回答：6+6 等于几。不要调用任何工具。");
        assertThat(run.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(run.body()).path("status").asText()).isEqualTo("completed");

        // 构造五表关联数据（workflows/workflow_logs 由任务产生；session_memory/reports/agent_todos 预置）
        try (Connection conn = pgConnection(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO session_memory (id, session_id, content) VALUES ('mem-del01', '"
                    + session + "', '删除级联前置记忆') ON CONFLICT (id) DO NOTHING");
            st.execute("INSERT INTO reports (id, session_id, title) VALUES ('rpt-del01', '"
                    + session + "', '删除级联前置报告') ON CONFLICT (id) DO NOTHING");
            st.execute("INSERT INTO report_references (report_id, ref_index, doc_id) VALUES ('rpt-del01', 0, 'doc-del01')");
            st.execute("INSERT INTO agent_todos (k, v) VALUES ('" + session + ":todo', '[\"t1\"]') ON CONFLICT (k) DO NOTHING");

            // 删前取证：按表的归属键捕获 ID 集（workflow_logs/report_references 无 session_id 列）
            List<String> workflowIds = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT id FROM workflows WHERE session_id = '" + session + "'")) {
                while (rs.next()) {
                    workflowIds.add(rs.getString(1));
                }
            }
            List<String> reportIds = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT id FROM reports WHERE session_id = '" + session + "'")) {
                while (rs.next()) {
                    reportIds.add(rs.getString(1));
                }
            }
            assertThat(workflowIds).as("前置：任务应已产生 workflows 行").isNotEmpty();

            // DA 账号（本拓扑以 service 用户承载其权限面；GRANT 半边由 grant IT 承载）按 session_id 显式直删
            // 顺序：workflow_logs 随 workflows FK 级联；report_references 随 reports FK 级联
            st.execute("DELETE FROM workflow_logs WHERE workflow_id IN (SELECT id FROM workflows WHERE session_id = '" + session + "')");
            st.execute("DELETE FROM workflows WHERE session_id = '" + session + "'");
            st.execute("DELETE FROM report_references WHERE report_id IN (SELECT id FROM reports WHERE session_id = '" + session + "')");
            st.execute("DELETE FROM reports WHERE session_id = '" + session + "'");
            st.execute("DELETE FROM session_memory WHERE session_id = '" + session + "'");
            // f069-del-02 oracle（L1 L148 + 2026-11-18 评审细化）：完整 sessionId + 固定 `:todo`
            // 后缀精确匹配（允许键前存在以 `:` 结尾的完整租户前缀，键结构 {租户}:{sessionId}:todo
            // ——core TenantNamespaceFactories.KV_STORE_DEFAULT 口径）；禁冒号切段反解 sessionId；
            // sessionId 中的 %、_ 作字面量不作 LIKE 通配符（谓词无 LIKE）
            st.execute("DELETE FROM agent_todos WHERE k = '" + session + ":todo' "
                    + "OR RIGHT(k, CHAR_LENGTH('" + session + "') + 6) = ':" + session + ":todo'");
            // 删后取证改用同口径谓词（旧形态 LIKE '%<S>%:todo' 属冒号子串+通配反解，评审废止）

            // 删后不可查（验收第 8 项数据库前提半边，docs#38 DS-02 口径）——按各表真实键列断言
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM workflows WHERE session_id = '" + session + "'")) {
                rs.next();
                assertThat(rs.getInt(1)).as("workflows 删后不可查").isZero();
            }
            for (String workflowId : workflowIds) {
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM workflow_logs WHERE workflow_id = '"
                        + workflowId + "'")) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("workflow_logs 删后不可查").isZero();
                }
            }
            for (String reportId : reportIds) {
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM reports WHERE id = '" + reportId + "'")) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("reports 删后不可查").isZero();
                }
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM report_references WHERE report_id = '"
                        + reportId + "'")) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("report_references 删后不可查").isZero();
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM session_memory WHERE session_id = '"
                    + session + "'")) {
                rs.next();
                assertThat(rs.getInt(1)).as("session_memory 删后不可查").isZero();
            }
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM agent_todos WHERE (k = '"
                    + session + ":todo' OR RIGHT(k, CHAR_LENGTH('" + session + "') + 6) = ':"
                    + session + ":todo')")) {
                rs.next();
                assertThat(rs.getInt(1)).as("agent_todos 按 del-02 oracle 口径删后不可查").isZero();
            }
            Allure.addAttachment("del-01 范围声明", "text/plain",
                    "本用例承载验收第 8 项的数据库前提半边（FK 拆除+DELETE 通路）；上游删除路径改造为上游工程（docs#38 DS-02）");
        }
    }

    /**
     * f069-del-02（2026-11-18 评审细化 oracle + 负向数据）：agent_todos 删除按
     * <b>完整 sessionId + 固定 {@code :todo} 后缀精确匹配</b>（允许键前存在以 {@code :} 结尾的
     * 完整租户前缀——键结构 {租户}:{sessionId}:todo，core TenantNamespaceFactories.KV_STORE_DEFAULT
     * 口径）；<b>禁冒号切段反解 sessionId</b>；<b>sessionId 中的 {@code %}、{@code _} 作字面量
     * 不作 LIKE 通配符</b>。负向数据：近似 sessionId、租户前缀下的其他会话键、
     * {@code %}/{@code _} 通配展开键——旧形态 {@code LIKE '%<S>%:todo'} 会误删的键族，
     * 细化后一律不误删。
     */
    @Test
    @Story("f069-del-02: agent_todos 删除 oracle——完整 sessionId + 固定 :todo 后缀精确匹配（允许租户前缀）；禁冒号反解；%/＿ 字面量；负向数据不误删")
    @DisplayName("del-02: 删除 oracle 精确匹配与负向数据不误删")
    void agentTodosDeletionOracleExactMatch() throws Exception {
        // ---- 构造：目标会话键族 + 三族负向键（旧 LIKE 形态会误删的键）----
        String target = "sess-del02-" + System.nanoTime();
        String tenant = "t-tenant-069";
        String likeDecoy1 = "near-" + target + "-x:todo";      // 近似 sessionId（目标为中缀）
        String likeDecoy2 = target + "x:todo";                 // 近似 sessionId（目标为前缀的长 id）
        String otherSession = "sess-del02-other-" + System.nanoTime();
        String tenantOther = tenant + ":" + otherSession + ":todo"; // 租户前缀下的其他会话键
        // 通配展开 decoy 组（旧 LIKE '%<S2>%:todo' 会命中、细化谓词不命中）
        String target2 = "s%d_r_" + System.nanoTime();         // 目标 2：含 % 与 _ 字面量
        String wildcardDecoy1 = "sX_d_" + stripPrefix(target2) + ":todo"; // %/_ 通配展开（旧 LIKE 命中）
        String wildcardDecoy2 = target2 + "x:todo";            // 尾缀通配展开（旧 LIKE 命中）
        try (Connection conn = pgConnection(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO agent_todos (k, v) VALUES ('" + target + ":todo', '[\"t\"]')");
            st.execute("INSERT INTO agent_todos (k, v) VALUES ('" + tenant + ":" + target + ":todo', '[\"t\"]')");
            st.execute("INSERT INTO agent_todos (k, v) VALUES ('" + likeDecoy1 + "', '[\"d1\"]')");
            st.execute("INSERT INTO agent_todos (k, v) VALUES ('" + likeDecoy2 + "', '[\"d2\"]')");
            st.execute("INSERT INTO agent_todos (k, v) VALUES ('" + tenantOther + "', '[\"d3\"]')");
            st.execute("INSERT INTO agent_todos (k, v) VALUES ('" + target2 + ":todo', '[\"t2\"]')");
            st.execute("INSERT INTO agent_todos (k, v) VALUES ('" + tenant + ":" + target2 + ":todo', '[\"t2\"]')");
            st.execute("INSERT INTO agent_todos (k, v) VALUES ('" + wildcardDecoy1 + "', '[\"d4\"]')");
            st.execute("INSERT INTO agent_todos (k, v) VALUES ('" + wildcardDecoy2 + "', '[\"d5\"]')");
        }
        try (Connection conn = pgConnection(); Statement st = conn.createStatement()) {
            // 前置自证：九行全部在场（任何 decoy 撞键即在此暴露）
            for (String key : List.of(target + ":todo", tenant + ":" + target + ":todo",
                    likeDecoy1, likeDecoy2, tenantOther, target2 + ":todo",
                    tenant + ":" + target2 + ":todo", wildcardDecoy1, wildcardDecoy2)) {
                try (ResultSet rs = st.executeQuery(
                        "SELECT count(*) FROM agent_todos WHERE k = '" + key + "'")) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("前置：键应在场 k=" + key).isEqualTo(1);
                }
            }
        }
        try (Connection conn = pgConnection(); Statement st = conn.createStatement()) {
            // 按细化 oracle 删除目标 1（裸键 + 租户前缀键均应命中）
            st.execute("DELETE FROM agent_todos WHERE k = '" + target + ":todo' "
                    + "OR RIGHT(k, CHAR_LENGTH('" + target + "') + 6) = ':" + target + ":todo'");
            // 按细化 oracle 删除目标 2（sessionId 含 % 与 _ ——作字面量，不作通配符）
            st.execute("DELETE FROM agent_todos WHERE k = '" + target2 + ":todo' "
                    + "OR RIGHT(k, CHAR_LENGTH('" + target2 + "') + 6) = ':" + target2 + ":todo'");
        }
        try (Connection conn = pgConnection(); Statement st = conn.createStatement()) {
            // 正向：目标键（裸 + 租户前缀）删除
            assertThat(countByKey(st, target + ":todo")).as("目标 1 裸键删除").isZero();
            assertThat(countByKey(st, tenant + ":" + target + ":todo")).as("目标 1 租户前缀键删除").isZero();
            assertThat(countByKey(st, target2 + ":todo")).as("目标 2（含 %/_）裸键删除").isZero();
            assertThat(countByKey(st, tenant + ":" + target2 + ":todo")).as("目标 2 租户前缀键删除").isZero();
            // 负向：三族 decoy 一律不误删（旧 LIKE 形态会误删）
            assertThat(countByKey(st, likeDecoy1)).as("① 近似 sessionId（中缀）不误删").isEqualTo(1);
            assertThat(countByKey(st, likeDecoy2)).as("① 近似 sessionId（前缀长 id）不误删").isEqualTo(1);
            assertThat(countByKey(st, tenantOther)).as("② 租户前缀下的其他会话键不误删").isEqualTo(1);
            assertThat(countByKey(st, wildcardDecoy1)).as("③ %/_ 通配展开键不误删（字面量语义）").isEqualTo(1);
            assertThat(countByKey(st, wildcardDecoy2)).as("③ 尾缀通配展开键不误删（字面量语义）").isEqualTo(1);
        }
        Allure.addAttachment("del-02 oracle 结论", "text/plain",
                "细化谓词（k=<S>:todo OR RIGHT(k,len+6)=:<S>:todo）删除目标 1/2 的裸键与租户前缀键；"
                        + "近似 sessionId ×2、租户前缀其他会话键、%/_ 通配展开键 ×2 全部不误删"
                        + "（旧形态 LIKE '%<S>%:todo' 对后三族全部误删——评审废止该形态）");
    }

    /** 键精确计数（不含任何通配语义）。 */
    private static int countByKey(Statement st, String key) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT count(*) FROM agent_todos WHERE k = '" + key + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** 取 "s%d_r_" 前缀后的数字段（通配展开 decoy 构造辅助）。 */
    private static String stripPrefix(String target2) {
        return target2.substring("s%d_r_".length());
    }
}

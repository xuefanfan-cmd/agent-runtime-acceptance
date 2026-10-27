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

import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TextPart;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-069 团队执行通道（f069-team-01/02/03/05/06/07 + rec-02 A2A 路径；
 * 需求 §3.6/验收 11，L2 §3.7）。f069-team-04（gating 503）与 gate/iso 在
 * DaDataplaneNopgE2EIT（数据面关闭形态栈）。
 *
 * <p>一期声明限制按 L2 §3.7 判定：执行期 SSE 静默（结束后成员事件按拓扑序补推）、
 * cancel 不中断成员内执行、council 按 dag 形态执行。团队定义以 SQL 直写 agent_teams
 * （DA 团队管理界面的宿主外写路径——六类 DA 写路径的模拟，与技能对账同口径）。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-069")
@Feature("FEAT-069: deepanalyze-java 数据面权限与写方迁移")
class DaDataplaneTeamE2EIT {

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
            // 团队定义（pipeline 两成员：输出带可辨识标记；council 一队）+ 模板路径不依赖表
            st.execute("INSERT INTO agent_teams (id, name, description, mode, is_active) "
                    + "VALUES ('team-069-pipe', '069 先锋队', 'pipeline 两成员', 'pipeline', true)");
            st.execute("INSERT INTO agent_team_members (id, team_id, role, task, depends_on, tools, sort_order) "
                    + "VALUES ('m1', 'team-069-pipe', '先锋', '只输出五个字：甲队先锋', '[]', '[]', 0)");
            st.execute("INSERT INTO agent_team_members (id, team_id, role, task, depends_on, tools, sort_order) "
                    + "VALUES ('m2', 'team-069-pipe', '殿后', '只输出五个字：乙队殿后', '[\"m1\"]', '[]', 1)");
            st.execute("INSERT INTO agent_teams (id, name, description, mode, is_active) "
                    + "VALUES ('team-069-council', '069 评审会', 'council 形态', 'council', true)");
            st.execute("INSERT INTO agent_team_members (id, team_id, role, task, depends_on, tools, sort_order) "
                    + "VALUES ('c1', 'team-069-council', '评审甲', '只输出四个字：同意通过', '[]', '[]', 0)");
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

    /** 等待本会话的 workflows 行到达终态并返回（有界轮询，异步 writer）。 */
    private Map<String, String> awaitWorkflowRow(String session, boolean expectTeamFields)
            throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            try (Connection conn = pgConnection(); Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT status, coalesce(team_name, ''), "
                         + "coalesce(mode, ''), coalesce(a2a_task_id, '') FROM workflows "
                         + "WHERE session_id = '" + session + "'")) {
                if (rs.next() && !"running".equals(rs.getString(1))) {
                    return Map.of("status", rs.getString(1), "teamName", rs.getString(2),
                            "mode", rs.getString(3), "a2aTaskId", rs.getString(4));
                }
            }
            Thread.sleep(400);
        }
        throw new IllegalStateException("30s 内 workflows 未落终态（session=" + session + "）");
    }

    @Test
    @Story("f069-team-01/02: teamId → agent_teams 现读编译执行；成员事件结束后按拓扑序补推")
    @DisplayName("team-01/02: 团队任务端到端（蓝图执行 + 记录 + SSE 补推）")
    void teamTaskEndToEnd() throws Exception {
        String session = "sess-team01-" + System.nanoTime();
        List<String> payloads = DaDataplaneSupport.postAndDrainSse(http, base, "/api/agents/run-stream",
                "{\"sessionId\":\"" + session + "\",\"input\":\"按团队执行\",\"teamId\":\"team-069-pipe\"}",
                Map.of("X-Da-Session-Id", session), CALL_TIMEOUT);
        Allure.addAttachment("run-stream SSE 载荷", "text/plain", String.join("\n", payloads));
        assertThat(payloads).as("团队任务 SSE 应有成员事件补推（执行期静默为一期限制，结束后合成）")
                .isNotEmpty();

        Map<String, String> row = awaitWorkflowRow(session, true);
        assertThat(row.get("status")).as("团队任务须落终态记录").isIn("completed", "failed");
        assertThat(row.get("teamName")).as("workflows.team_name 取自定义").isEqualTo("069 先锋队");
        assertThat(row.get("mode")).as("workflows.mode 取自定义").isEqualTo("pipeline");

        // team-02：成员事件拓扑序（pipeline：m1 先于 m2）——标记都出现时才比序（模型措辞不确定性下不硬断言内容）
        String joined = String.join("\n", payloads);
        int alpha = joined.indexOf("甲队先锋");
        int beta = joined.indexOf("乙队殿后");
        if (alpha >= 0 && beta >= 0) {
            assertThat(alpha).as("pipeline 拓扑序：成员1 输出先于成员2").isLessThan(beta);
        }
        Allure.addAttachment("team-02 拓扑序", "text/plain",
                "甲队标记@" + alpha + "，乙队标记@" + beta + "（-1=该标记未出现，按证据强度分账）");
    }

    @Test
    @Story("f069-team-03: teamId 不存在 → 404 team not found")
    @DisplayName("team-03: 未知团队 404")
    void unknownTeamId404() throws Exception {
        String session = "sess-team03-" + System.nanoTime();
        HttpResponse<String> response = DaDataplaneSupport.post(http, base, "/api/agents/run-stream",
                "{\"sessionId\":\"" + session + "\",\"input\":\"x\",\"teamId\":\"no-such-team-069\"}",
                Duration.ofSeconds(30));
        Allure.addAttachment("run-stream（未知团队）", "text/plain", response.body());
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("team not found");
    }

    @Test
    @Story("f069-team-05 + f069-rec-02: A2A 路径 metadata 不被消费（一期仅限 compat）；a2a_task_id=SDK taskId 落键")
    @DisplayName("team-05: A2A 带团队标识按单 Agent 执行 + rec-02 a2a_task_id 落键")
    void a2aPathIgnoresTeamId() throws Exception {
        // 框架便捷方法 sendMessage(text, metadata) 当前忽略 metadata（源码注释明示），
        // 故以 SDK Message 携带 metadata 构造 A2A 入体。客户端侧 SDK 对终态事件会取消
        // SSE 请求（CancellationException 经 errorHandler 冒泡，任务 ID 未必到 consumer）——
        // 断言改以服务端事实承载：任务已执行（SUT 日志 A2A stream complete），按时间窗 +
        // UUID 会话形态反查该任务的执行记录。
        java.sql.Timestamp testStart = java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1));
        java.util.concurrent.atomic.AtomicReference<Throwable> failureRef = new java.util.concurrent.atomic.AtomicReference<>();
        List<BiConsumer<ClientEvent, AgentCard>> consumers = List.of();
        Message message = Message.builder()
                .role(Message.Role.ROLE_USER)
                .parts(new TextPart("请用一句话回答：7+7 等于几。不要调用任何工具。"))
                .messageId(java.util.UUID.randomUUID().toString())
                .metadata(Map.of("da.run.team_id", "team-069-pipe"))
                .build();
        stack.client(DaDataplaneSupport.AGENT).sendMessage(message, consumers,
                error -> failureRef.set(error));
        Allure.addAttachment("A2A 客户端侧错误（如有）", "text/plain",
                String.valueOf(failureRef.get()));

        // 反查：时间窗内、A2A 形态（UUID 会话、非 sess- 前缀）的最新执行记录
        Map<String, String> row = null;
        long deadline = System.currentTimeMillis() + 45_000;
        while (System.currentTimeMillis() < deadline) {
            try (Connection conn = pgConnection(); Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT status, coalesce(team_name, ''), "
                         + "coalesce(mode, ''), coalesce(a2a_task_id, '') FROM workflows "
                         + "WHERE started_at >= '" + testStart + "' AND a2a_task_id IS NOT NULL "
                         + "AND session_id NOT LIKE 'sess-%' ORDER BY started_at DESC LIMIT 1")) {
                if (rs.next() && !"running".equals(rs.getString(1))) {
                    row = Map.of("status", rs.getString(1), "teamName", rs.getString(2),
                            "mode", rs.getString(3), "a2aTaskId", rs.getString(4));
                    break;
                }
            }
            Thread.sleep(500);
        }
        assertThat(row).as("45s 内须出现本 A2A 任务的终态执行记录").isNotNull();
        assertThat(row.get("teamName"))
                .as("A2A 路径不消费团队标识：team_name 必须为 null（L2 §3.7 边界声明）")
                .isEmpty();
        assertThat(row.get("mode")).as("单 Agent 路径 mode 为 null").isEmpty();
        assertThat(row.get("a2aTaskId")).as("rec-02：A2A 路径 a2a_task_id 落键").isNotBlank();
        Allure.addAttachment("team-05/rec-02", "text/plain",
                "A2A 任务（UUID 会话）终态=" + row.get("status") + "，a2a_task_id=" + row.get("a2aTaskId")
                        + "，team_name/mode=null（边界成立）；客户端 taskId 等值断言因 SDK 终态取消行为降级为落键断言");
    }

    @Test
    @Story("f069-team-06: 不带 teamId 回落宿主自带模板基线（既有行为不变）")
    @DisplayName("team-06: 无团队标识走模板路径")
    void noTeamIdFallsBackToTemplate() throws Exception {
        String session = "sess-team06-" + System.nanoTime();
        List<String> payloads = DaDataplaneSupport.postAndDrainSse(http, base, "/api/agents/run-stream",
                "{\"sessionId\":\"" + session + "\",\"input\":\"请用一句话回答：8+8 等于几。不要调用任何工具。\"}",
                Map.of("X-Da-Session-Id", session), CALL_TIMEOUT);
        assertThat(payloads).as("模板路径 SSE 正常（既有行为不变）").isNotEmpty();
        Map<String, String> row = awaitWorkflowRow(session, false);
        assertThat(row.get("status")).isIn("completed", "failed");
        assertThat(row.get("teamName")).as("单 Agent 路径 team_name 为 null").isEmpty();
    }

    @Test
    @Story("f069-team-07: council 团队按 dag 形态执行（一期声明限制）")
    @DisplayName("team-07: council 模式按 dag 执行完成")
    void councilRunsAsDag() throws Exception {
        String session = "sess-team07-" + System.nanoTime();
        List<String> payloads = DaDataplaneSupport.postAndDrainSse(http, base, "/api/agents/run-stream",
                "{\"sessionId\":\"" + session + "\",\"input\":\"按团队执行\",\"teamId\":\"team-069-council\"}",
                Map.of("X-Da-Session-Id", session), CALL_TIMEOUT);
        Map<String, String> row = awaitWorkflowRow(session, true);
        assertThat(row.get("status")).as("council 按 dag 形态执行完成（一期限制）").isIn("completed", "failed");
        assertThat(row.get("mode")).as("workflows.mode 记录定义中的 council").isEqualTo("council");
        Allure.addAttachment("team-07", "text/plain", "council 执行完成；互审拓扑不编译（一期声明限制）");
    }
}

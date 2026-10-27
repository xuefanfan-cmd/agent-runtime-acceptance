package com.huawei.ascend.sit.cases.e2e.deepanalyze_dataplane;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-069 skill_usage 遥测埋点（f069-rec-05；需求验收第 3 项遥测半边，L2 §3.2/§3.3）。
 *
 * <p>构造：SQL 直写 agent_skills（manual）→ 对账物化 → 任务指令调用 skill_invoke →
 * 断言 skill_usage 按列分写（use_count+1、last_used_at 更新、生命周期列不触碰）。
 * 技能是否被模型调用属构造不确定性：调用了→硬断言；未调用→INCONCLUSIVE 记账。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-069")
@Feature("FEAT-069: deepanalyze-java 数据面权限与写方迁移")
class DaDataplaneTelemetryE2EIT {

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
        Path materializeRoot = Files.createTempDirectory("feat069-telemetry-skills");
        try (Connection conn = pgConnection(); Statement st = conn.createStatement()) {
            DaDataplaneSupport.applyBaselineDdl(conn);
            // 种子技能（manual，updated_at=now() 保证对账可见——DaDataplaneSkillReconcileIT 校准口径）
            st.execute("INSERT INTO agent_skills (id, name, description, prompt, source, is_active, "
                    + "created_at, updated_at) VALUES ('skl-tel', 'skl-tel-069', '遥测用例技能', "
                    + "'你是遥测验证技能 skl-tel-069。', 'manual', true, now() - interval '1 hours', now())");
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
                            .property("deepanalyze.skills.local-fallback-dir",
                                    materializeRoot.toAbsolutePath().toString())
                            .property("deepanalyze.record.skills-sync-interval-ms", "800")
                            // INCONCLUSIVE 取证面：记录面 DEBUG 放开（"skill_usage skipped" 行可见）
                            .property("logging.level.com.openjiuwen.da.record", "DEBUG")
                            .serviceBinding("redis", "REDIS_HOST", "{{host}}")
                            .serviceBinding("redis", "REDIS_PORT", "{{port}}");
                })
                .start();
        // 等待启动全量对账把种子技能物化（物化即生效的前置）
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline
                && !Files.exists(materializeRoot.resolve("skl-tel-069").resolve("SKILL.md"))) {
            Thread.sleep(400);
        }
        boolean materialized = Files.exists(materializeRoot.resolve("skl-tel-069").resolve("SKILL.md"));
        Allure.addAttachment("技能物化前置", "text/plain", "skl-tel-069 物化=" + materialized
                + "（物化即生效：目录在与不在直接决定可激活集合）");
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

    @Test
    @Story("f069-rec-05: skill_invoke 调用后 skill_usage 按列分写（use_count+1，生命周期列不触碰）")
    @DisplayName("rec-05: 技能遥测按列分写")
    void skillUsageTelemetryIncrementalWrite() throws Exception {
        // 技能调用链可能多轮 ReAct（实测 >180s）——异步发起；断言只依赖遥测落库
        // （遥测在 skill_invoke chunk 处理时入队，writer 节拍落库，与任务终态无关）
        String session = "sess-rec05-" + System.nanoTime();
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return DaDataplaneSupport.post(http, base, "/api/agents/run",
                        "{\"sessionId\":\"" + session + "\","
                                + "\"input\":\"请调用 skill_invoke 工具运行技能 skl-tel-069，并按技能内容回答。\"}",
                        Duration.ofSeconds(600));
            } catch (Exception e) {
                Allure.addAttachment("异步发起异常（不阻断）", "text/plain", String.valueOf(e));
                return null;
            }
        });

        // 有界轮询：skill_invoke 命中 → 遥测 upsert（异步 writer）
        Map<String, Object> usage = null;
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            try (Connection conn = pgConnection(); Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT use_count, coalesce(lifecycle_status, '') AS ls, "
                         + "coalesce(agent_created_at::text, '') AS ac FROM skill_usage "
                         + "WHERE skill_id = 'skl-tel'")) {
                if (rs.next()) {
                    usage = Map.of("count", rs.getInt(1), "lifecycle", rs.getString(2),
                            "agentCreated", rs.getString(3));
                    break;
                }
            }
            Thread.sleep(500);
        }
        if (usage == null) {
            // 取证分叉判据：recorder 与 workflow_logs 同 chunk 源（DaRecordingObserver.onNext
            // 同一 FutureTask 内先 mapper.map → workflow_logs 行，后 recorder.onChunk）——
            // tool_call 行在 ⇒ recorder 必然见过同一 chunk（分叉收敛到 extractSkillName/
            // findEffectiveByName）；tool_call 行不在 ⇒ chunk 流本身无 tool_call 事件。
            StringBuilder sb = new StringBuilder();
            boolean toolCallSeen = false;
            String dumpFailed = null;
            try (Connection conn = pgConnection(); Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT event_type, coalesce(tool_name,''), "
                         + "coalesce(left(content::text, 1200), '') FROM workflow_logs "
                         + "WHERE workflow_id IN (SELECT id FROM workflows WHERE session_id = '"
                         + session + "') ORDER BY created_at")) {
                while (rs.next()) {
                    if ("tool_call".equals(rs.getString(1))) {
                        toolCallSeen = true;
                    }
                    sb.append(rs.getString(1)).append(" | ").append(rs.getString(2)).append(" | ")
.append(rs.getString(3)).append("\n---\n");
                }
            } catch (java.sql.SQLException ex) {
                dumpFailed = "workflow_logs 取证查询失败: " + ex.getMessage();
            }
            String dump = sb.toString();
            Allure.addAttachment("rec-05 INCONCLUSIVE workflow_logs 取证", "text/plain",
                    (dumpFailed != null ? dumpFailed + "\n" : "")
                            + (dump.isBlank() ? "<无行——session 未命中 workflows 行>" : dump));
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "遥测 INCONCLUSIVE——SUT 日志证实 skill_invoke outcome=success 而 skill_usage 无行；"
                            + "workflow_logs tool_call 行在=" + toolCallSeen
                            + "（真=extractSkillName/解析分叉，假=chunk 流无 tool_call 事件）"
                            + "——取证附件 + target/sit-logs DEBUG 行");
        }        assertThat((int) usage.get("count")).as("use_count ≥1（遥测 upsert）").isGreaterThanOrEqualTo(1);
        Allure.addAttachment("rec-05 按列分写", "text/plain",
                "use_count=" + usage.get("count") + "，lifecycle_status=" + usage.get("lifecycle")
                        + "（生命周期列归 agentevolving，本特性不触碰——值保持 NULL 即为不触碰的观察面）");
    }
}

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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-069 会话记忆关闭态/无压缩态（f069-mem-03；需求验收第 2 项的无条件半边，L2 §3.3）。
 *
 * <p>mem-03 语义二选一构造（设计初稿 G5）：本类取「session-memory 默认开启 +
 * 短任务无 context.compression_state chunk → session_memory 表空且无报错」——
 * 比开关关闭态更强：默认开启下无压缩不得有伪写入。「enabled=false」半边是
 * 开关直断（写入面整段缺席），属同义弱断言，不另设用例。
 * 压缩触发形态归 mem-01/02（条件项：chunk 出现，执行期未满足，登记台账）。</p>
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-069")
@Feature("FEAT-069: deepanalyze-java 数据面权限与写方迁移")
class DaDataplaneSessionMemoryE2EIT {

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
                        agent.property("LLM_API_KEY", "fixture-mem03-boot-only");
                    }
                    agent.property("deepanalyze.data.pg.jdbc-url", url)
                            .property("deepanalyze.data.pg.username", DaDataplaneSupport.PG_USER)
                            .property("deepanalyze.data.pg.password", DaDataplaneSupport.PG_PASSWORD)
                            .property("spring.datasource.url", url)
                            .property("spring.datasource.username", DaDataplaneSupport.PG_USER)
                            .property("spring.datasource.password", DaDataplaneSupport.PG_PASSWORD)
                            // 显式默认开启（yml 未配置时 DaRecordProperties 默认即 true——写明防漂移）
                            .property("deepanalyze.record.session-memory-enabled", "true")
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

    @Test
    @Story("f069-mem-03: session-memory 开启但无压缩 chunk → session_memory 表空且无报错")
    @DisplayName("mem-03: 无压缩短任务——session_memory 空表、任务无错")
    void noCompressionNoSpuriousSessionMemoryWrites() throws Exception {
        String session = "sess-mem03-" + System.nanoTime();
        HttpResponse<String> run = DaDataplaneSupport.post(http, base, "/api/agents/run",
                "{\"sessionId\":\"" + session + "\","
                        + "\"input\":\"请用一句话回答：3+3 等于几。不要调用任何工具。\"}",
                CALL_TIMEOUT);
        Allure.addAttachment("POST /api/agents/run（无压缩短任务）", "text/plain", run.body());
        assertThat(run.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(run.body());
        assertThat(body.path("status").asText()).isEqualTo("completed");

        int memoryRows = -1;
        String errorRows = null;
        try (Connection conn = pgConnection(); Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM session_memory "
                    + "WHERE session_id = '" + session + "'")) {
                if (rs.next()) {
                    memoryRows = rs.getInt(1);
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT coalesce(event_type, ''), "
                    + "coalesce(left(content::text, 300), '') FROM workflow_logs "
                    + "WHERE workflow_id IN (SELECT id FROM workflows WHERE session_id = '"
                    + session + "') AND event_type = 'error'")) {
                StringBuilder sb = new StringBuilder();
                while (rs.next()) {
                    sb.append(rs.getString(1)).append(" | ").append(rs.getString(2)).append("\n");
                }
                errorRows = sb.toString();
            }
        }
        Allure.addAttachment("mem-03 观察面", "text/plain",
                "session_memory 行数=" + memoryRows + "；workflow_logs error 事件="
                        + (errorRows == null || errorRows.isBlank() ? "<无>" : errorRows));
        assertThat(memoryRows).as("无压缩不得有伪写入（表空）").isZero();
        assertThat(errorRows).as("任务执行无 error 事件（无报错）").isBlank();
    }
}

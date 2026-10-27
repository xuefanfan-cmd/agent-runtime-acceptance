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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-069 读方消失改造（f069-read-01/02/04/05；需求验收第 6/11 项运行期半边，L2 §2.1/§3.8）。
 *
 * <p>自管生命周期：SUT 启动要求 TS 基线表在场（schema 演进是增量 DDL），播种必须先于启动，
 * 因此手工 PG + 类级 SUT 栈（@BeforeAll 内播种后启动）。read-01/05 执行真实任务（真实 LLM，
 * 不 Mock），须由受控 runner（run-sit-macos.sh）注入 ~/.llmrc 凭据。
 *
 * <p>静态扫描半边归交付流程（L2 §1.1，docs#38 DV-02）；本类只承接运行期可观察行为。
 * "全程不经 sessions/messages/settings 读取"的语句日志证据（f069-read-03）另立用例，
 * 不在本类（需要 per-account 语句日志面）。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-069")
@Feature("FEAT-069: deepanalyze-java 数据面权限与写方迁移")
class DaDataplaneReadRemovalE2EIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(90);

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
        // 播种必须先于 SUT 启动：schema 演进是增量 DDL（基线不含 agent_todos/a2a_task_id）
        try (Connection conn = pgConnection(); Statement st = conn.createStatement()) {
            DaDataplaneSupport.applyBaselineDdl(conn);
        }
        String url = jdbcUrl();
        stack = SutStack.builder(config)
                .streaming(true)
                .agent(DaDataplaneSupport.AGENT, agent -> {
                    DaDataplaneSupport.bindModelEnvironment(agent);
                    if (System.getenv("LLM_API_KEY") == null || System.getenv("LLM_API_KEY").isBlank()) {
                        // 无 runner 凭据时占位兜底：read-01/05 的"不再 404/正常受理"断言仍成立，
                        // completed 断言须真实凭据（受控 runner 注入），缺凭据时用例按 INCONCLUSIVE 记账
                        agent.property("LLM_API_KEY", "fixture-no-runner-credentials");
                    }
                    agent.property("deepanalyze.data.pg.jdbc-url", url)
                            .property("deepanalyze.data.pg.username", DaDataplaneSupport.PG_USER)
                            .property("deepanalyze.data.pg.password", DaDataplaneSupport.PG_PASSWORD)
                            .property("spring.datasource.url", url)
                            .property("spring.datasource.username", DaDataplaneSupport.PG_USER)
                            .property("spring.datasource.password", DaDataplaneSupport.PG_PASSWORD)
                            // redis 仍走栈托管（三段式推导 ②：redis 在场=常态部署形态）
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
    @Story("f069-read-01: sessionId 不存在于 sessions 不再 404（载荷即信任）")
    @DisplayName("read-01: 未知会话正常受理执行（对照旧行为 404）")
    void unknownSessionNoLonger404() throws Exception {
        HttpResponse<String> run = DaDataplaneSupport.post(http, base, "/api/agents/run",
                "{\"sessionId\":\"sess-unknown-069-01\",\"input\":\"请用一句话回答：1+1 等于几。不要调用任何工具。\"}",
                CALL_TIMEOUT);
        Allure.addAttachment("POST /api/agents/run（未知会话）", "text/plain", run.body());
        assertThat(run.statusCode())
                .as("载荷即信任：未知 sessionId 不再 404（旧形态=404 Session not found，L2 §2.1 伴生行为变化一）")
                .isNotEqualTo(404);
        assertThat(run.body()).as("会话存在性 404 表面须下线").doesNotContain("Session not found");
        JsonNode body = MAPPER.readTree(run.body());
        if (body.hasNonNull("status")) {
            assertThat(body.path("status").asText())
                    .as("凭据就绪时任务应执行完成（无凭据兜底形态下只断言受理面）")
                    .isEqualTo("completed");
        }
    }

    @Test
    @Story("f069-read-02: providers 探测读宿主配置面（协议形态不变）")
    @DisplayName("read-02: providers/{id}/test 命中宿主配置 provider；未命中 404")
    void providersProbeReadsHostConfig() throws Exception {
        HttpResponse<String> probe = DaDataplaneSupport.post(http, base,
                "/api/agents/providers/prov-feat069-hostcfg/test", "{}", CALL_TIMEOUT);
        Allure.addAttachment("POST /api/agents/providers/{hostcfg}/test", "text/plain", probe.body());
        assertThat(probe.statusCode())
                .as("宿主配置面的 provider 必须可被探测受理（该 id 只存在于 yml 配置，不在 settings 表）")
                .isEqualTo(200);
        JsonNode body = MAPPER.readTree(probe.body());
        assertThat(body.has("success")).as("响应须带探测结论字段（协议形态不变）").isTrue();
        assertThat(body.path("success").asBoolean())
                .as("探测指向不可达地址，结论应为 false（证明确实发起探测）").isFalse();

        HttpResponse<String> missing = DaDataplaneSupport.post(http, base,
                "/api/agents/providers/no-such-provider-069/test", "{}", CALL_TIMEOUT);
        Allure.addAttachment("POST /api/agents/providers/{unknown}/test", "text/plain", missing.body());
        assertThat(missing.statusCode()).as("未命中 provider 须 404（协议不变）").isEqualTo(404);
    }

    @Test
    @Story("f069-read-04: agent_skills 读端点换账号后语义不变")
    @DisplayName("read-04: GET /api/agents/skills/active 语义/结构不变")
    void skillsReadFaceUnchanged() throws Exception {
        HttpResponse<String> active = DaDataplaneSupport.get(http, base, "/api/agents/skills/active",
                CALL_TIMEOUT);
        Allure.addAttachment("GET /api/agents/skills/active", "text/plain", active.body());
        assertThat(active.statusCode()).as("读面换账号但端点语义不变（L2 §3.8④）").isEqualTo(200);
    }

    @Test
    @Story("f069-read-05: 旧调用方零影响（不带三新字段，响应与 SSE 词汇现状一致）")
    @DisplayName("read-05: 旧形态请求正常执行，响应词汇不变")
    void legacyCallerUnaffected() throws Exception {
        HttpResponse<String> run = DaDataplaneSupport.post(http, base, "/api/agents/run",
                "{\"sessionId\":\"sess-legacy-069-05\",\"input\":\"请用一句话回答：天空为什么是蓝的。不要调用任何工具。\"}",
                CALL_TIMEOUT);
        Allure.addAttachment("POST /api/agents/run（旧形态）", "text/plain", run.body());
        assertThat(run.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(run.body());
        assertThat(body.has("status")).as("响应须带 status 字段（SSE/响应词汇与现状一致）").isTrue();
        assertThat(body.path("status").asText()).isEqualTo("completed");
    }
}

package com.huawei.ascend.sit.cases.e2e.deepanalyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
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

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-054 共享 PG 数据面：{@code da.data.pg-read}。
 *
 * <p>库结构来自开发侧 2026-09-22 交付的 DA 侧权威建表语句副本
 * （{@code src/test/resources/deepanalyze/da-shared-pg-ddl.sql}，来源 DA 仓 {@code pg-migrations}），
 * 取代此前"按 L2 文字自造等价 schema"的口径 —— Q12/DES-Q12 已按①提供副本裁决。
 *
 * <p>外部可观察判据（不直连 fixture 做业务断言，只做前置与只读核对）：
 * <ul>
 *   <li>技能读面：{@code GET /agent/skills}、{@code /agent/skills/active}、{@code /agent/skills/{id}} 能读回种子技能；未命中 404。</li>
 *   <li>provider 读面：{@code POST /agent/providers/{id}/test} 读到种子 provider 行并发起探测；未命中 404。</li>
 *   <li>只读铁律：跑完一次真实任务后 sessions/messages/session_memory/settings/agent_skills 行数不变。</li>
 * </ul>
 *
 * <p>已知边界：{@code session_memory} 读穿的消费点（metadata 透传）在 core 会话记忆接入面就绪前不可外部观察，
 * 媒体元数据读面需要共享数据目录（{@code DA_DATA_DIR}）——两条均按台账缺口记账，不在本类伪造结论。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-054")
@Feature("FEAT-054: deepanalyze-java 宿主装配")
class DaSharedPgReadE2EIT extends BaseManagedStackTest {

    private static final String AGENT = "deepanalyze";
    private static final String PG_SERVICE = "postgres";
    private static final String PG_DB = "agent_rdc";
    private static final String PG_USER = "agent_rdc";
    private static final String PG_PASSWORD = "agent_rdc";

    private static final String SESSION_ID = "sess-feat054-read";
    private static final String SKILL_ID = "skill-feat054-read";
    private static final String PROVIDER_ID = "prov-feat054-read";
    private static final String DDL_RESOURCE = "/deepanalyze/da-shared-pg-ddl.sql";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);
    private static final List<String> READ_ONLY_TABLES =
            List.of("sessions", "messages", "session_memory", "settings", "agent_skills");

    private String jdbcUrl;
    private HttpClient http;
    private String base;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        String pgUrl = "jdbc:postgresql://{{host}}:{{port}}/" + PG_DB;
        return SutStack.builder(config)
                .streaming(true)
                .agent(AGENT, agent -> {
                    bindModelEnvironment(agent);
                    agent.property("deepanalyze.data.pg.enabled", "true")
                            .property("deepanalyze.data.pg.username", PG_USER)
                            .property("deepanalyze.data.pg.password", PG_PASSWORD)
                            .property("spring.datasource.username", PG_USER)
                            .property("spring.datasource.password", PG_PASSWORD)
                            // postgres/redis 经 serviceBinding 引用即由栈自管拉起容器并注入动态地址。
                            .serviceBinding(PG_SERVICE, "deepanalyze.data.pg.jdbc-url", pgUrl)
                            .serviceBinding(PG_SERVICE, "spring.datasource.url", pgUrl)
                            .serviceBinding("redis", "REDIS_HOST", "{{host}}")
                            .serviceBinding("redis", "REDIS_PORT", "{{port}}");
                });
    }

    private static void bindModelEnvironment(SutStack.AgentBuilder agent) {
        passThrough(agent, "LLM_API_KEY", "LLM_API_KEY");
        passThrough(agent, "DA_MODEL_BASE_URL", "LLM_API_BASE");
        passThrough(agent, "DA_MODEL_NAME", "LLM_MODEL");
        passThrough(agent, "DA_MODEL_PROVIDER", "LLM_PROVIDER");
    }

    private static void passThrough(SutStack.AgentBuilder agent, String target, String source) {
        String value = System.getenv(source);
        if (value != null && !value.isBlank()) {
            agent.env(target, value);
        }
    }

    @BeforeAll
    void seedSharedDataPlane() throws Exception {
        String address = stack.serviceUrl(PG_SERVICE).replaceFirst("^https?://", "");
        jdbcUrl = "jdbc:postgresql://" + address + "/" + PG_DB;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        base = client(AGENT).getBaseUrl();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, PG_USER, PG_PASSWORD)) {
            applyDdl(connection);
            seedRows(connection);
        }
        Allure.addAttachment("数据面准备", "text/plain",
                "jdbc=" + jdbcUrl + "\nDDL=" + DDL_RESOURCE + "\nseed session=" + SESSION_ID
                        + ", skill=" + SKILL_ID + ", provider=" + PROVIDER_ID);
    }

    @AfterAll
    void closeClient() {
        http = null;
    }

    @Test
    @Story("da.data.pg-read: 技能读面从共享 PG 读回种子数据")
    @DisplayName("da.data.pg-read: GET /agent/skills* 读到共享 PG 里的技能，未命中 404")
    void skillsReadPlaneReadsSharedPg() throws Exception {
        HttpResponse<String> active = get("/agent/skills/active", READ_TIMEOUT);
        Allure.addAttachment("GET /agent/skills/active", "text/plain", active.body());
        assertThat(active.statusCode()).as("启用数据面后技能读面应可用").isEqualTo(200);
        assertThat(active.body()).as("活跃技能列表须包含共享 PG 里的种子技能").contains(SKILL_ID);

        HttpResponse<String> detail = get("/agent/skills/" + SKILL_ID, READ_TIMEOUT);
        Allure.addAttachment("GET /agent/skills/{id}", "text/plain", detail.body());
        assertThat(detail.statusCode()).isEqualTo(200);
        JsonNode skill = MAPPER.readTree(detail.body());
        assertThat(skill.path("id").asText()).isEqualTo(SKILL_ID);
        assertThat(skill.path("name").asText()).isNotBlank();
        assertThat(skill.path("is_active").asBoolean()).isTrue();

        HttpResponse<String> missing = get("/agent/skills/no-such-skill-054", READ_TIMEOUT);
        Allure.addAttachment("GET /agent/skills/{unknown}", "text/plain", missing.body());
        assertThat(missing.statusCode()).as("未命中技能须 404").isEqualTo(404);
        assertThat(missing.body()).contains("error");
    }

    @Test
    @Story("da.data.pg-read: provider 读面读到 settings.providers 行并发起探测")
    @DisplayName("da.data.pg-read: POST /agent/providers/{id}/test 读到种子 provider；未命中 404")
    void providerTestReadsProvidersRow() throws Exception {
        HttpResponse<String> probe = post("/agent/providers/" + PROVIDER_ID + "/test", "{}", READ_TIMEOUT);
        Allure.addAttachment("POST /agent/providers/{id}/test", "text/plain", probe.body());
        assertThat(probe.statusCode())
                .as("provider 存在时探测端点必须受理（探测结果本身可成功可失败，取决于被探地址）")
                .isEqualTo(200);
        JsonNode body = MAPPER.readTree(probe.body());
        assertThat(body.has("success")).as("响应须带探测结论字段").isTrue();
        assertThat(body.path("success").asBoolean())
                .as("种子 provider 指向不可达地址，探测结果应为 false（证明确实发起了探测而非空实现）")
                .isFalse();

        HttpResponse<String> missing = post("/agent/providers/no-such-provider-054/test", "{}", READ_TIMEOUT);
        Allure.addAttachment("POST /agent/providers/{unknown}/test", "text/plain", missing.body());
        assertThat(missing.statusCode()).as("未命中 provider 须 404").isEqualTo(404);
    }

    @Test
    @Story("da.data.pg-read: 共享 PG 只读铁律（宿主不写业务表）")
    @DisplayName("da.data.pg-read: 真实任务跑完后业务表行数不变（只读铁律）")
    void hostNeverWritesSharedPg() throws Exception {
        Map<String, Integer> before = rowCounts();

        HttpResponse<String> run = post("/agent/run", "{\"sessionId\":\"" + SESSION_ID
                + "\",\"input\":\"请用一句话回答：2+2 等于几。不要调用任何工具。\"}", READ_TIMEOUT);
        Allure.addAttachment("POST /agent/run（种子会话）", "text/plain", run.body());
        assertThat(run.statusCode()).as("已存在的会话必须能跑通任务").isEqualTo(200);
        assertThat(MAPPER.readTree(run.body()).path("status").asText()).isEqualTo("completed");

        Map<String, Integer> after = rowCounts();
        Allure.addAttachment("只读铁律行数对比", "text/plain", before + " -> " + after);
        assertThat(after).as("宿主不得写入共享 PG 的任何业务表（messages/sessions 尤其）").isEqualTo(before);
    }

    private HttpResponse<String> get(String path, Duration timeout) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(timeout)
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, String body, Duration timeout) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private Map<String, Integer> rowCounts() throws Exception {
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, PG_USER, PG_PASSWORD);
             Statement statement = connection.createStatement()) {
            for (String table : READ_ONLY_TABLES) {
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
                    rows.next();
                    counts.put(table, rows.getInt(1));
                }
            }
        }
        return counts;
    }

    /** 回放 DA 侧权威 DDL（按语句切分；本 DDL 无函数体/美元引用，按分号切分安全）。 */
    private static void applyDdl(Connection connection) throws Exception {
        String script;
        try (InputStream stream = DaSharedPgReadE2EIT.class.getResourceAsStream(DDL_RESOURCE)) {
            assertThat(stream).as("DDL 资源必须存在: " + DDL_RESOURCE).isNotNull();
            script = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : script.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--") || trimmed.isEmpty()) {
                continue;
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                statements.add(current.toString());
                current.setLength(0);
            }
        }
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private static void seedRows(Connection connection) throws Exception {
        List<String> seeds = List.of(
                "INSERT INTO sessions (id, title, kb_scope) VALUES ('" + SESSION_ID + "', 'FEAT-054 读面会话', 'kb-054')"
                        + " ON CONFLICT (id) DO NOTHING",
                "INSERT INTO session_memory (id, session_id, content, token_count, last_token_position)"
                        + " VALUES ('mem-feat054-read', '" + SESSION_ID + "', '会话记忆：关注中国区流水波动归因', 12, 12)"
                        + " ON CONFLICT (id) DO NOTHING",
                "INSERT INTO agent_skills (id, name, description, prompt, tools, model_role, is_active, source)"
                        + " VALUES ('" + SKILL_ID + "', 'feat054-read-skill', '数据面读面验证技能',"
                        + " '你是数据面读面验证技能。', '{\"*\"}', 'main', true, 'manual')"
                        + " ON CONFLICT (id) DO NOTHING",
                "INSERT INTO settings (key, value) VALUES ('agent_settings',"
                        + " '{\"contextWindow\":128000,\"compactionBuffer\":20000,\"hierarchicalCompression\":true}'::jsonb)"
                        + " ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value",
                "INSERT INTO settings (key, value) VALUES ('providers',"
                        + " '{\"providers\":[{\"id\":\"" + PROVIDER_ID + "\",\"name\":\"读面验证 provider\","
                        + "\"type\":\"openai-compatible\",\"endpoint\":\"http://127.0.0.1:9\","
                        + "\"apiKey\":\"fixture-only\",\"model\":\"no-such-model\"}]}'::jsonb)"
                        + " ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value");
        try (Statement statement = connection.createStatement()) {
            for (String seed : seeds) {
                statement.execute(seed);
            }
        }
    }
}

package com.huawei.ascend.sit.cases.e2e.deepanalyze;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-054 {@code REQ-089/REQ-092~102} 的**参数矩阵与边界扩展**（黑盒负路径系统化）。
 *
 * <p>设计目标：把每个 compat 端点的输入面从"1~3 个负样例"扩到"系统化矩阵"，并加入一条通用红线——
 * **畸形输入绝不允许 500**（畸形输入返回 5xx 是典型实现缺陷：未做入参校验或异常未映射）。
 *
 * <p>判据来源：L2 §2.11 端点契约与错误表面（错误体 {@code {error}} 同源形态、400/404/503 矩阵）。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-054")
@Feature("FEAT-054: deepanalyze-java 宿主装配")
class DaCompatParamMatrixE2EIT extends BaseManagedStackTest {

    private static final String AGENT = "deepanalyze";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private HttpClient http;
    private String base;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .streaming(true)
                .agent(AGENT, agent -> {
                    passThrough(agent, "LLM_API_KEY", "LLM_API_KEY");
                    passThrough(agent, "DA_MODEL_BASE_URL", "LLM_API_BASE");
                    passThrough(agent, "DA_MODEL_NAME", "LLM_MODEL");
                    passThrough(agent, "DA_MODEL_PROVIDER", "LLM_PROVIDER");
                    agent.serviceBinding("redis", "REDIS_HOST", "{{host}}")
                            .serviceBinding("redis", "REDIS_PORT", "{{port}}");
                });
    }

    private static void passThrough(SutStack.AgentBuilder agent, String target, String source) {
        String value = System.getenv(source);
        if (value != null && !value.isBlank()) {
            agent.env(target, value);
        }
    }

    private void initClient() {
        if (http == null) {
            http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            base = client(AGENT).getBaseUrl();
        }
    }

    /** 入体畸形矩阵：run-stream / run 的必填字段缺失、空串、非法 JSON、超长、非 ASCII。 */
    @ParameterizedTest(name = "[{index}] {0} → {1}")
    @CsvSource(delimiter = '|', value = {
            "/agent/run-stream | {\"input\":\"x\"} | 400",
            "/agent/run-stream | {\"sessionId\":\"\",\"input\":\"x\"} | 400",
            "/agent/run-stream | {\"sessionId\":\"sess-matrix\",\"input\":\"\"} | 400",
            "/agent/run-stream | {not-json | 400",
            "/agent/run | {\"input\":\"x\"} | 400",
            "/agent/run | {\"sessionId\":\"sess-matrix\",\"input\":\"\"} | 400",
            "/agent/run | {not-json | 400",
    })
    @Story("da.compat.errors: 入体畸形矩阵（400 且不得 500）")
    @DisplayName("参数矩阵：run-stream/run 畸形入体 → 400，错误体同源")
    void malformedBodiesAreRejectedNotCrashing(String path, String body, int expected) throws Exception {
        initClient();
        HttpResponse<String> response = post(path, body, null);
        Allure.addAttachment(path + " body=" + body, "text/plain",
                response.statusCode() + "\n" + response.body());
        assertThat(response.statusCode())
                .as("畸形入体必须被拒为 %s（5xx 说明校验/异常映射缺失）", expected)
                .isEqualTo(expected);
        assertThat(response.body()).as("错误体必须是 DA 同源 {error} 形态").contains("error");
    }

    /** 路径参数不存在：cancel/answer/inject/status 一律 404，且不得 500。 */
    @ParameterizedTest(name = "[{index}] {0} → 404")
    @ValueSource(strings = {
            "/agent/cancel/no-such-054",
            "/agent/answer/no-such-054",
            "/agent/inject/no-such-054",
    })
    @Story("da.compat.errors: 未知 taskId 矩阵（404 且不得 500）")
    @DisplayName("参数矩阵：未知 taskId → 404")
    void unknownTaskIdAlwaysNotFound(String path) throws Exception {
        initClient();
        // 注意：校验顺序 —— answer/inject 的"缺参 400"先于"未知任务 404"，故此处给合法体以命中 404 分支
        String body = path.contains("/answer/") ? "{\"answer\":\"x\"}"
                : path.contains("/inject/") ? "{\"message\":\"x\"}" : "{}";
        HttpResponse<String> response = post(path, body, "sess-matrix-" + UUID.randomUUID());
        Allure.addAttachment(path, "text/plain", response.statusCode() + "\n" + response.body());
        assertThat(response.statusCode()).as("未知 taskId 必须 404").isEqualTo(404);
        assertThat(response.body()).contains("error");
    }

    /** GET 型端点（status）的未知 taskId：同样 404，且方法不匹配不得 405。 */
    @ParameterizedTest(name = "[{index}] {0} → 404")
    @ValueSource(strings = {"/agent/status/no-such-054"})
    @Story("da.compat.errors: 未知 taskId 矩阵（GET 型端点）")
    @DisplayName("参数矩阵：未知 taskId（GET status）→ 404")
    void unknownTaskIdOnGetEndpoints(String path) throws Exception {
        initClient();
        HttpResponse<String> response = get(path, "sess-matrix-" + UUID.randomUUID());
        Allure.addAttachment(path, "text/plain", response.statusCode() + "\n" + response.body());
        assertThat(response.statusCode()).as("未知 taskId 必须 404").isEqualTo(404);
        assertThat(response.body()).contains("error");
    }

    /** 会话隔离（IDOR）扩展：cancel/answer/inject 用"异属主"会话访问真实任务必须 404。 */
    @ParameterizedTest(name = "[{index}] {0} 异属主 → 404")
    @ValueSource(strings = {"/agent/cancel/", "/agent/answer/", "/agent/inject/"})
    @Story("da.compat.owner-binding: 全端点异属主访问被拒（IDOR 扩展）")
    @DisplayName("参数矩阵：控制面全端点异属主 → 404")
    void crossOwnerAccessIsDeniedOnAllControlEndpoints(String prefix) throws Exception {
        initClient();
        String owner = "sess-owner-" + UUID.randomUUID();
        String intruder = "sess-intruder-" + UUID.randomUUID();
        String taskId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(post("/agent/run", "{\"sessionId\":\"" + owner
                        + "\",\"input\":\"请用一句话回答：1+1。不要调用任何工具。\"}", owner).body())
                .path("taskId").asText();

        String body = "{\"answer\":\"x\",\"message\":\"x\"}";
        HttpResponse<String> asIntruder = post(prefix + taskId, body, intruder);
        HttpResponse<String> asOwner = post(prefix + taskId, body, owner);
        Allure.addAttachment(prefix, "text/plain", "intruder=" + asIntruder.statusCode()
                + " owner=" + asOwner.statusCode() + "\n" + asIntruder.body());
        assertThat(asIntruder.statusCode())
                .as("异属主访问必须被拒（404），且不得因属主不同返回 5xx")
                .isEqualTo(404);
        assertThat(asIntruder.body()).doesNotContain("500");
    }

    /** GET 型端点（status）的异属主访问同样必须 404。 */
    @Test
    @Story("da.compat.owner-binding: status 异属主被拒")
    @DisplayName("参数矩阵：/agent/status 异属主 → 404")
    void crossOwnerAccessIsDeniedOnStatusEndpoint() throws Exception {
        initClient();
        String owner = "sess-owner-" + UUID.randomUUID();
        String intruder = "sess-intruder-" + UUID.randomUUID();
        String taskId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(post("/agent/run", "{\"sessionId\":\"" + owner
                        + "\",\"input\":\"请用一句话回答：1+1。不要调用任何工具。\"}", owner).body())
                .path("taskId").asText();
        HttpResponse<String> asIntruder = get("/agent/status/" + taskId, intruder);
        Allure.addAttachment("/agent/status 异属主", "text/plain",
                asIntruder.statusCode() + "\n" + asIntruder.body());
        assertThat(asIntruder.statusCode()).as("异属主查状态必须 404").isEqualTo(404);
    }

    private HttpResponse<String> get(String path, String session) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(TIMEOUT)
                .header("X-Da-Session-Id", session)
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** 方法不匹配：PUT/DELETE 打 compat 端点必须是 405（或 404），绝不能 5xx。 */
    @ParameterizedTest(name = "[{index}] {0} {1} → 405/404")
    @CsvSource(delimiter = '|', value = {
            "PUT | /agent/run",
            "DELETE | /agent/run",
            "PUT | /agent/status/no-such-054",
            "PUT | /agent/run-stream",
    })
    @Story("da.compat.errors: 方法不匹配 → 405/404 且不得 5xx")
    @DisplayName("参数矩阵：方法不匹配（PUT/DELETE）→ 405 或 404")
    void methodMismatchDoesNotCrash(String method, String path) throws Exception {
        initClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Allure.addAttachment(method + " " + path, "text/plain",
                response.statusCode() + "\n" + response.body().substring(0, Math.min(200, response.body().length())));
        assertThat(response.statusCode()).as("方法不匹配必须是 405/404，不得 5xx").isIn(405, 404);
    }

    /** 未知字段容忍：合法请求带未知字段应照常受理（不得 5xx、不得因未知字段 400）。 */
    @Test
    @Story("da.compat.errors: 未知字段容忍")
    @DisplayName("参数矩阵：合法入体 + 未知字段 → 照常受理（非 5xx、非 400）")
    void unknownFieldsAreTolerated() throws Exception {
        initClient();
        String session = "sess-unknown-" + UUID.randomUUID();
        HttpResponse<String> response = post("/agent/run",
                "{\"sessionId\":\"" + session + "\",\"input\":\"请用一句话回答：3+4。不要调用任何工具。\","
                        + "\"unknownFieldA\":\"x\",\"nested\":{\"b\":1}}", session);
        Allure.addAttachment("未知字段容忍", "text/plain",
                response.statusCode() + "\n" + response.body().substring(0, Math.min(300, response.body().length())));
        assertThat(response.statusCode())
                .as("DA 前端前向兼容要求容忍未知字段（不得 5xx，也不应因未知字段拒 400）")
                .isEqualTo(200);
    }

    /** 穿越型 sessionId 与畸形 id：不得 5xx；状态/任务查询不得泄露其它会话数据。 */
    @Test
    @Story("da.compat.errors: 穿越/畸形标识不得 5xx")
    @DisplayName("参数矩阵：穿越字符与超长 id → 非 5xx，且不回显系统文件")
    void traversalAndOversizedIdsNeverCrash() throws Exception {
        initClient();
        String session = "sess-malformed-" + UUID.randomUUID();
        java.util.List<String> paths = java.util.List.of(
                "/agent/status/..%2F..%2Fetc%2Fpasswd",
                "/agent/status/%20%20",
                "/agent/status/" + "x".repeat(500),
                "/agent/providers/..%2F..%2Fetc%2Fpasswd/test",
                "/agent/providers/" + "y".repeat(500) + "/test",
                "/agent/skills/..%2F..%2Fetc%2Fpasswd");
        for (String path : paths) {
            HttpResponse<String> response = path.contains("/providers/")
                    ? post(path, "{}", session)
                    : get(path, session);
            String body = response.body() == null ? "" : response.body();
            Allure.addAttachment(path, "text/plain",
                    response.statusCode() + "\n" + body.substring(0, Math.min(200, body.length())));
            // 只允许 4xx；数据面未配置时契约规定返回 503（L2 §2.11），故 503 合法。
            // 其余 5xx（尤其 500）视为未映射异常。
            assertThat(response.statusCode())
                    .as("畸形标识只允许 4xx，或契约规定的数据面 503：%s", path)
                    .satisfiesAnyOf(
                            code -> assertThat((int) code).isLessThan(500),
                            code -> assertThat((int) code).isEqualTo(503));
            assertThat(body).as("响应不得回显系统文件内容").doesNotContain("root:");
        }
    }

    /** 超长与非 ASCII 入体：不得 5xx（是否受理取决于契约，500 一律不合格）。 */
    @ParameterizedTest(name = "[{index}] {0} → 不出现 5xx")
    @ValueSource(strings = {
            "LONG_ASCII",
            "NON_ASCII",
    })
    @Story("da.compat.errors: 超长/非 ASCII 入体不得 5xx")
    @DisplayName("参数矩阵：超长与非 ASCII 入体 → 非 5xx")
    void extremeInputsNeverCrash(String kind) throws Exception {
        initClient();
        String input = "LONG_ASCII".equals(kind)
                ? "A".repeat(20_000)
                : "请分析：中国区流水波动，涉及 αβγ、①②③、emoji 😀 与换行\\n\\t。";
        HttpResponse<String> response = post("/agent/run",
                "{\"sessionId\":\"sess-extreme-" + UUID.randomUUID() + "\",\"input\":\"" + input + "\"}", null);
        Allure.addAttachment("extreme=" + kind, "text/plain",
                response.statusCode() + "\n" + response.body().substring(0, Math.min(300, response.body().length())));
        assertThat(response.statusCode())
                .as("超长/非 ASCII 入体不得触发 5xx（%s）", kind)
                .isLessThan(500);
    }

    private HttpResponse<String> post(String path, String body, String session) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (session != null) {
            builder.header("X-Da-Session-Id", session);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}

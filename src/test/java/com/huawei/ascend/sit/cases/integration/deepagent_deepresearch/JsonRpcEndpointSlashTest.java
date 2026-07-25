package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-001.jsonrpc-endpoint-slash — {@code POST /a2a} 与 {@code POST /a2a/} 尾斜杠等价.
 *
 * <p>FEAT-001 §2「A2A JSON-RPC 统一入口」+ §3「路径 {@code POST /a2a} 或 {@code POST /a2a/}」。
 * 断言两个 URL 都走同一 JSON-RPC 分发路径 —— 不返 404 / 不 301/308 重定向、且响应 shape 等价。
 *
 * <p><b>Payload 选择</b>：使用 A2A 标准 method {@code GetTask}（PascalCase — 见 SDK 1.0.0.Final
 * {@code GetTaskRequest.METHOD}；REST binding 用的 {@code tasks/get} 是 REST 路径命名，不是 JSON-RPC
 * method 名）+ 随机 UUID 作 taskId。走完整 A2A JSON-RPC dispatch 但避免真实 LLM 调用；
 * 服务端返 A2A 特有的 {@code -32001 TaskNotFound} —— 两个 URL 都会返同一错误，
 * 尾斜杠等价性验证不受影响。
 * 两次请求 body 完全相同，唯一变量是 URL 尾斜杠。
 *
 * <p><b>HTTP status 期望</b>：按 A2A JSON-RPC transport 契约，
 * JSON-RPC error 必须在 HTTP 2xx body 里表达 —— HTTP 4xx/5xx 是 transport 崩坏语义，
 * A2A SDK 客户端遇到非 2xx 直接抛 {@code A2AClientHTTPError}，不会去读 body 里的 error code。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.jsonrpc-endpoint-slash: POST /a2a 与 POST /a2a/ 尾斜杠等价")
class JsonRpcEndpointSlashTest extends BaseManagedStackTest {

    private static final String DEEP_RESEARCH = "deep-research";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config).agent(DEEP_RESEARCH);
    }

    @Test
    @DisplayName("FEAT-001.jsonrpc-endpoint-slash: /a2a 与 /a2a/ 同 body 应走同一分发路径")
    void trailingSlashIsEquivalent() throws Exception {
        // A2A JSON-RPC method = "GetTask" (PascalCase, per SDK 1.0.0.Final GetTaskRequest.METHOD);
        // random UUID as taskId — server will return -32001 TaskNotFound in body, same on both URLs.
        String body = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"slash-%s\",\"method\":\"GetTask\","
                        + "\"params\":{\"id\":\"%s\"}}",
                UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID());

        HttpResponse<String> noSlash = post("/a2a", body);
        HttpResponse<String> withSlash = post("/a2a/", body);

        assertThat(noSlash.statusCode())
                .as("POST /a2a should return 200 (no 404 / no redirect)\nbody: %s", noSlash.body())
                .isEqualTo(200);
        assertThat(withSlash.statusCode())
                .as("POST /a2a/ should return 200 (no 404 / no redirect)\nbody: %s", withSlash.body())
                .isEqualTo(200);

        JsonNode a = mapper.readTree(noSlash.body());
        JsonNode b = mapper.readTree(withSlash.body());

        assertThat(a.path("jsonrpc").asText())
                .as("/a2a response should be a JSON-RPC 2.0 envelope").isEqualTo("2.0");
        assertThat(b.path("jsonrpc").asText())
                .as("/a2a/ response should be a JSON-RPC 2.0 envelope").isEqualTo("2.0");

        // Both requests carried the same id — dispatcher must echo it back on both variants.
        assertThat(a.path("id").asText())
                .as("/a2a response id should echo request id").isEqualTo(b.path("id").asText());

        // shape equivalence — both should land on the SAME dispatch outcome (both result or both error);
        // if error, same code; if result, both non-null.
        boolean aIsError = a.has("error");
        boolean bIsError = b.has("error");
        assertThat(aIsError)
                .as("Both endpoints should agree on outcome shape (result vs error)\n"
                        + "  /a2a  body: %s\n  /a2a/ body: %s", noSlash.body(), withSlash.body())
                .isEqualTo(bIsError);

        if (aIsError) {
            assertThat(a.path("error").path("code").asInt())
                    .as("Both endpoints should produce the same error code").isEqualTo(
                            b.path("error").path("code").asInt());
        } else {
            assertThat(a.hasNonNull("result")).as("/a2a should have a result node").isTrue();
            assertThat(b.hasNonNull("result")).as("/a2a/ should have a result node").isTrue();
        }
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(stack.baseUrl(DEEP_RESEARCH) + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
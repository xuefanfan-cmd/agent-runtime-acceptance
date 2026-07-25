package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * jsonrpc-invalid-request + jsonrpc-id-preserved —
 * 合法 JSON 但不符 JSON-RPC 请求 shape → {@code -32600}, 且 id 回显不丢.
 *
 * <p>version-scope FEAT-001 §5.1.2/§5.1.8 只承诺 "invalid-request 语义" + "错误 response 尽量
 * 保留原 request id"，不再固定 {@code -32600} 这个码值。本用例的具体码断言按 L2 §5.3
 * <b>当前实现事实</b> 表钉住 —— SUT 换表达形式时用例 FAIL 是"L2 实现现状变化"，需回 L2 对齐。
 *
 * <p>POST {@code {"jsonrpc":"2.0","id":"1"}}（缺 method）到 {@code /a2a}，SUT 必须返：
 * <ul>
 *   <li>HTTP 200</li>
 *   <li>合法 JSON-RPC error response</li>
 *   <li>{@code error.code == -32600}（invalid request）</li>
 *   <li>{@code id == "1"}（错误响应必须回显请求的 id）</li>
 * </ul>
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Feature("FEAT-001: 标准化智能体服务入口")
@Stories({
        @Story("da.jsonrpc-invalid-request: 合法 JSON 但缺 method → -32600 invalid-request"),
        @Story("da.jsonrpc-id-preserved: 错误响应须回显请求 id")
})
class JsonRpcInvalidRequestTest extends BaseManagedStackTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final int JSON_RPC_INVALID_REQUEST = -32600;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config).agent(DEEP_RESEARCH);
    }

    @Test
    @DisplayName("FEAT-001.jsonrpc-invalid-request: 缺 method → HTTP 200 + code=-32600 + id 回显 '1'")
    void missingMethodReturnsInvalidRequestWithHttpOk() throws Exception {
        String bodyText = "{\"jsonrpc\":\"2.0\",\"id\":\"1\"}";
        HttpResponse<String> response = post("/a2a", bodyText);

        assertThat(response.statusCode())
                .as("HTTP status should be 200\nbody: %s", response.body())
                .isEqualTo(200);

        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("jsonrpc").asText())
                .as("body should be a JSON-RPC 2.0 envelope\nbody: %s", response.body())
                .isEqualTo("2.0");
        assertThat(body.has("error"))
                .as("body should carry an error object\nbody: %s", response.body())
                .isTrue();
        assertThat(body.path("error").path("code").asInt())
                .as("error.code should be -32600 (invalid request)\nbody: %s", response.body())
                .isEqualTo(JSON_RPC_INVALID_REQUEST);

        // FEAT-001.jsonrpc-id-preserved: error response must echo the request id.
        assertThat(body.path("id").asText())
                .as("id should be preserved as '1' (FEAT-001.jsonrpc-id-preserved)\nbody: %s", response.body())
                .isEqualTo("1");
    }

    @Test
    @DisplayName("FEAT-001.jsonrpc-invalid-request: 顶层为 JSON 数组 [] → HTTP 200 + code=-32600")
    void nonObjectPayloadReturnsInvalidRequest() throws Exception {
        // JSON-RPC 2.0 §4.2 / §6: empty array (or any non-object top-level payload) is not a
        // well-formed Request/Batch, dispatcher must return -32600 invalid-request in a 200 body.
        // Top-level is not an object so there is no request id to echo → error id must be null.
        HttpResponse<String> response = post("/a2a", "[]");

        assertThat(response.statusCode())
                .as("HTTP status should be 200 (invalid-request lives in body)\nbody: %s", response.body())
                .isEqualTo(200);

        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("jsonrpc").asText())
                .as("body should be a JSON-RPC 2.0 envelope\nbody: %s", response.body())
                .isEqualTo("2.0");
        assertThat(body.has("error"))
                .as("body should carry an error object\nbody: %s", response.body())
                .isTrue();
        assertThat(body.path("error").path("code").asInt())
                .as("error.code should be -32600 (invalid request)\nbody: %s", response.body())
                .isEqualTo(JSON_RPC_INVALID_REQUEST);
        assertThat(body.hasNonNull("id"))
                .as("id should be null (no request object to echo id from)\nbody: %s", response.body())
                .isFalse();
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
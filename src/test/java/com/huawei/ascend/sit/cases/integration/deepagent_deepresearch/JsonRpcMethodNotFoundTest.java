package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
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
 * jsonrpc-method-not-found + jsonrpc-id-preserved —
 * 未知 method → {@code -32601}, 且 id 回显不丢.
 *
 * <p>version-scope FEAT-001 §5.1.2/§5.1.8 只承诺 "method-not-found 语义" + "错误 response 尽量
 * 保留原 request id"，不再固定 {@code -32601} 这个码值。本用例的具体码断言按 L2 §5.3
 * <b>当前实现事实</b> 表钉住 —— SUT 换表达形式时用例 FAIL 是"L2 实现现状变化"，需回 L2 对齐。
 *
 * <p>POST 合法 shape 但 method 名不存在到 {@code /a2a}，SUT 必须返：
 * <ul>
 *   <li>HTTP 200</li>
 *   <li>合法 JSON-RPC error response</li>
 *   <li>{@code error.code == -32601}（method not found）</li>
 *   <li>{@code id == "7"}（错误响应必须回显请求的 id）</li>
 * </ul>
 */
@Tag("integration")
@Tag("deepagent")
class JsonRpcMethodNotFoundTest extends BaseManagedStackTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final int JSON_RPC_METHOD_NOT_FOUND = -32601;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config).agent(DEEP_RESEARCH);
    }

    @Test
    @DisplayName("FEAT-001.jsonrpc-method-not-found: 未知 method → HTTP 200 + code=-32601 + id 回显 '7'")
    void unknownMethodProducesMethodNotFoundAndPreservesId() throws Exception {
        String bodyText = "{\"jsonrpc\":\"2.0\",\"id\":\"7\","
                + "\"method\":\"NoSuchMethodEver\",\"params\":{}}";
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
                .as("error.code should be -32601 (method not found)\nbody: %s", response.body())
                .isEqualTo(JSON_RPC_METHOD_NOT_FOUND);

        // FEAT-001.jsonrpc-id-preserved: error response must echo the request id.
        assertThat(body.path("id").asText())
                .as("id should be preserved as '7' (FEAT-001.jsonrpc-id-preserved)\nbody: %s", response.body())
                .isEqualTo("7");
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
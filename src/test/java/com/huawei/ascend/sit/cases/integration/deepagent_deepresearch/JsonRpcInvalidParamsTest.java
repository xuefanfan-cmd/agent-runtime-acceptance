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
 * jsonrpc-invalid-params + jsonrpc-id-preserved —
 * 合法 shape + 已知 method,但 {@code params} 非对象 → {@code -32602} invalid-params,
 * 且 id 回显不丢.
 *
 * <p>version-scope FEAT-001 §5.1.2/§5.1.8 只承诺 "invalid-params 语义" + "错误 response 尽量
 * 保留原 request id",不再固定 {@code -32602} 这个码值。本用例的具体码断言按 L2 §5.3
 * <b>当前实现事实</b> 表钉住 —— SUT 换表达形式时用例 FAIL 是"L2 实现现状变化",需回 L2 对齐。
 *
 * <p>POST {@code {"jsonrpc":"2.0","id":"9","method":"SendMessage","params":[]}}
 * 到 {@code /a2a},SUT 必须返:
 * <ul>
 *   <li>HTTP 200</li>
 *   <li>合法 JSON-RPC error response</li>
 *   <li>{@code error.code == -32602}(invalid params)</li>
 *   <li>{@code id == "9"}(错误响应必须回显请求的 id)</li>
 * </ul>
 *
 * <p><b>payload 挑选注意</b>:JSON-RPC 2.0 §4.2 规定 {@code params} MUST be structured
 * (Object 或 Array),字符串 / 数字 等标量属于 <i>shape</i> 违规,应由 dispatcher 在方法分派
 * <b>之前</b> reject 为 -32600 invalid-request,而不是 -32602 invalid-params。
 * 因此本用例用 {@code params:[]} —— shape 合法(空 Array,允许作 positional),但目标
 * method 期望 {@code SendMessageRequest} 对象,解码失败 → -32602。若把 {@code params}
 * 换成字符串,SUT 会返 -32600,那是 spec 分层正确行为,不是 bug。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Feature("FEAT-001: 标准化智能体服务入口")
@Stories({
        @Story("da.jsonrpc-invalid-params: 已知 method 但 params 非对象 → -32602 invalid-params"),
        @Story("da.jsonrpc-id-preserved: 错误响应须回显请求 id")
})
class JsonRpcInvalidParamsTest extends BaseManagedStackTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final int JSON_RPC_INVALID_PARAMS = -32602;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config).agent(DEEP_RESEARCH);
    }

    @Test
    @DisplayName("FEAT-001.jsonrpc-invalid-params: 已知 method + params=[] → HTTP 200 + code=-32602 + id 回显 '9'")
    void malformedMethodParamsReturnInvalidParams() throws Exception {
        // 已知 method (SendMessage,与 curl 手工验证同款) + params 是空 Array (shape 合法但
        // 无法解码到 SendMessageRequest 对象) → 分派已过 method 校验,应落 -32602。
        String bodyText = "{\"jsonrpc\":\"2.0\",\"id\":\"9\","
                + "\"method\":\"SendMessage\",\"params\":[]}";
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
                .as("error.code should be -32602 (invalid params)\nbody: %s", response.body())
                .isEqualTo(JSON_RPC_INVALID_PARAMS);

        // FEAT-001.jsonrpc-id-preserved: error response must echo the request id.
        assertThat(body.path("id").asText())
                .as("id should be preserved as '9' (FEAT-001.jsonrpc-id-preserved)\nbody: %s", response.body())
                .isEqualTo("9");
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

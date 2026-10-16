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
        // SUT jar 0.1.0 声明了 remote-agents[search-agent, verify-agent]，startup 会校验二者 URL 非空；
        // 本用例不打真实 sub-agent 链路，占位 URL 让 Spring bind 通过即可。
        return SutStack.builder(config)
                .agent(DEEP_RESEARCH, a -> a
                        .env("SEARCH_AGENT_URL", "http://127.0.0.1:1")
                        .env("VERIFY_AGENT_URL", "http://127.0.0.1:1"));
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

    /**
     * L2 §1.3 明确列为「当前实现缺口」:params 是<b>结构性合法的对象</b>,但内部字段缺项/类型
     * 不匹配时,当前实现仍会走 -32603 internal error 兜底,预期应为 -32602 invalid params。
     *
     * <p>本组 3 个断言分别覆盖 3 类子场景:
     * <ol>
     *   <li>params={} —— 缺必填 {@code message};</li>
     *   <li>params.message.parts 类型错(应为数组给字符串);</li>
     *   <li>params.message.role 枚举错(给未定义的 {@code "OWNER"})。</li>
     * </ol>
     *
     * <p>SUT 补齐 params validation 前此断言 red 属 spec-gap 而非 bug —— 若返 -32603
     * 输出 stderr 诊断行,便于回 L2 §1.3 追踪补齐进度。
     */
    @Test
    @DisplayName("FEAT-001.jsonrpc-invalid-params: 结构合法但字段缺项/类型错(L2 §1.3 gap red-first)")
    void structuredParamsWithBadFieldsReturnInvalidParams() throws Exception {
        assertInvalidParams(
                "{\"jsonrpc\":\"2.0\",\"id\":\"ip-a\","
                        + "\"method\":\"SendMessage\",\"params\":{}}",
                "params={} 缺 message");

        assertInvalidParams(
                "{\"jsonrpc\":\"2.0\",\"id\":\"ip-b\","
                        + "\"method\":\"SendMessage\",\"params\":{"
                        + "\"message\":{"
                        + "\"role\":\"ROLE_USER\","
                        + "\"messageId\":\"m1\","
                        + "\"contextId\":\"c1\","
                        + "\"parts\":\"not-an-array\""
                        + "}}}",
                "params.message.parts 非数组");

        assertInvalidParams(
                "{\"jsonrpc\":\"2.0\",\"id\":\"ip-c\","
                        + "\"method\":\"SendMessage\",\"params\":{"
                        + "\"message\":{"
                        + "\"role\":\"OWNER\","
                        + "\"messageId\":\"m1\","
                        + "\"contextId\":\"c1\","
                        + "\"parts\":[{\"text\":\"hi\"}]"
                        + "}}}",
                "params.message.role 非法枚举");
    }

    private void assertInvalidParams(String bodyText, String label) throws Exception {
        HttpResponse<String> response = post("/a2a", bodyText);
        assertThat(response.statusCode())
                .as("[%s] HTTP status 应为 200\nbody=%s", label, response.body())
                .isEqualTo(200);

        JsonNode node = mapper.readTree(response.body());
        assertThat(node.has("error"))
                .as("[%s] body 应含 error\nbody=%s", label, response.body())
                .isTrue();

        int code = node.path("error").path("code").asInt();
        // -32603 = L2 §1.3 明确记录的实现现状缺口。仍严格断 -32602(补齐后自然通过)。
        if (code == -32603) {
            System.err.println("[FEAT-001.jsonrpc-invalid-params][" + label
                    + "] SUT 返 -32603 internal error → L2 §1.3 记录的实现缺口(params validation 未细分),"
                    + "预期 -32602 invalid params。resp=" + response.body());
        }
        assertThat(code)
                .as("[%s] error.code 应为 -32602 invalid params(spec:L2 §2.3.1;"
                                + "若为 -32603,系 L2 §1.3 记录的实现缺口 red-first)\nbody=%s",
                        label, response.body())
                .isEqualTo(JSON_RPC_INVALID_PARAMS);
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

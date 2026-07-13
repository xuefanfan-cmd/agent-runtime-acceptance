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
 * FEAT-001.jsonrpc-parse-error — 非法 JSON body → 标准 JSON-RPC parse error {@code -32700}.
 *
 * <p>FEAT-001 §5.1.2 + §5.1.8。POST 非法 JSON 到 {@code /a2a}，SUT 必须返：
 * <ul>
 *   <li>HTTP 200（错误在 JSON-RPC 层面表达，不是 HTTP 层面）</li>
 *   <li>合法 JSON-RPC error response（含 {@code jsonrpc:"2.0"} + {@code error} 对象）</li>
 *   <li>{@code error.code == -32700}（parse error）</li>
 *   <li>{@code id == null}（JSON-RPC 规范：解析失败无法得知原 id）</li>
 * </ul>
 *
 * <p>用底层 {@code HttpClient} 直接发 —— A2A SDK 的 {@code unmarshalResponse} 会把 A2AError 包成
 * 通用异常，需要 raw HTTP 才能断言 protocol-level code。
 */
@Tag("integration")
@Tag("deepagent")
class JsonRpcParseErrorTest extends BaseManagedStackTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final int JSON_RPC_PARSE_ERROR = -32700;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config).agent(DEEP_RESEARCH);
    }

    @Test
    @DisplayName("FEAT-001.jsonrpc-parse-error: 非法 JSON → HTTP 200 + error.code=-32700 + id=null")
    void malformedJsonProducesParseError() throws Exception {
        String malformed = "{not-json";
        HttpResponse<String> response = post("/a2a", malformed);

        assertThat(response.statusCode())
                .as("HTTP status should be 200 (JSON-RPC errors are body-level, not HTTP-level)\n"
                        + "body: %s", response.body())
                .isEqualTo(200);

        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("jsonrpc").asText())
                .as("body should be a JSON-RPC 2.0 envelope\nbody: %s", response.body())
                .isEqualTo("2.0");
        assertThat(body.has("error"))
                .as("body should carry an error object\nbody: %s", response.body())
                .isTrue();
        assertThat(body.path("error").path("code").asInt())
                .as("error.code should be -32700 (parse error)\nbody: %s", response.body())
                .isEqualTo(JSON_RPC_PARSE_ERROR);

        // JSON-RPC 2.0 §5.1: when detecting parse error, id MUST be null (unparseable).
        assertThat(body.has("id"))
                .as("response must include id field (even if null)\nbody: %s", response.body())
                .isTrue();
        assertThat(body.path("id").isNull())
                .as("id should be null for parse error (per JSON-RPC 2.0 §5.1)\nbody: %s", response.body())
                .isTrue();
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
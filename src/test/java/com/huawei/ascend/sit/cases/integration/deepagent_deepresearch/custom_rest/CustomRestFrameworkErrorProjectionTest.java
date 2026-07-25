package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch.custom_rest;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2 §7 · framework 错误投影 —— 故意发坏请求，观察 framework 是否投射为对应 HTTP status 和
 * 错误信封。
 *
 * <p>覆盖场景:
 * <ul>
 *   <li>415 unsupported_media_type: 非 JSON Content-Type</li>
 *   <li>400 invalid_json: body 不是合法 JSON</li>
 *   <li>400 invalid_custom_request: body 不是 object（如 JSON array）</li>
 *   <li>406 stream_not_acceptable: stream=true 但 Accept 不接受 SSE</li>
 *   <li>409 conversation_busy: 并发同 conversation（详见 CustomRestConversationBusyTest）</li>
 * </ul>
 */
@Tag("integration")
@Tag("feat-022")
@Tag("custom-rest")
@Feature("FEAT-022: 自定义 REST API 智能体服务入口")
@Story("da.framework-error-projection: framework 错误投影为 adapter 定义的自定义信封")
class CustomRestFrameworkErrorProjectionTest extends BaseCustomRestTest {

    @Test
    @DisplayName("FEAT-022.framework-error [415]: 非 JSON Content-Type 应返回 415 unsupported_media_type")
    void nonJsonContentTypeShouldReturn415() throws Exception {
        String conversationId = "conv-feat022-err-415-" + UUID.randomUUID().toString().substring(0, 8);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(customRestUrl(conversationId)))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("plain text body"))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode())
                .as("FEAT-022.framework-error [415]: 非 JSON Content-Type 应返回 415\n"
                        + "  conversationId=%s\n  body=%s", conversationId, response.body())
                .isEqualTo(415);

        // §3.3 T: 错误信封应经过 adapter.fromError 投影，携带 unsupported_media_type 标记
        assertThat(response.body() == null ? "" : response.body().toLowerCase())
                .as("FEAT-022.framework-error [415]: 错误信封应含 unsupported_media_type 标记\n  body=%s",
                        response.body())
                .contains("unsupported_media_type");
    }

    @Test
    @DisplayName("FEAT-022.framework-error [400 invalid_json]: 非法 JSON body 应返回 400")
    void malformedJsonShouldReturn400InvalidJson() throws Exception {
        String conversationId = "conv-feat022-err-json-" + UUID.randomUUID().toString().substring(0, 8);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(customRestUrl(conversationId)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{not valid json"))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode())
                .as("FEAT-022.framework-error [400 invalid_json]: 非法 JSON body 应返回 400\n"
                        + "  conversationId=%s\n  body=%s", conversationId, response.body())
                .isEqualTo(400);
        assertThat(response.body() == null ? "" : response.body().toLowerCase())
                .as("FEAT-022.framework-error [400 invalid_json]: 错误信封应含 invalid_json 标记\n  body=%s",
                        response.body())
                .contains("invalid_json");
    }

    @Test
    @DisplayName("FEAT-022.framework-error [400 invalid_custom_request]: JSON 非 object 应返回 400 invalid_custom_request")
    void nonObjectJsonShouldReturn400InvalidCustomRequest() throws Exception {
        String conversationId = "conv-feat022-err-shape-" + UUID.randomUUID().toString().substring(0, 8);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(customRestUrl(conversationId)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("[\"array\",\"not\",\"object\"]"))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode())
                .as("FEAT-022.framework-error [400 invalid_custom_request]: JSON 非 object 应返回 400\n"
                        + "  conversationId=%s\n  body=%s", conversationId, response.body())
                .isEqualTo(400);
        assertThat(response.body() == null ? "" : response.body().toLowerCase())
                .as("FEAT-022.framework-error [400 invalid_custom_request]: 错误信封应含 invalid_custom_request 标记\n  body=%s",
                        response.body())
                .contains("invalid_custom_request");
    }

    @Test
    @DisplayName("FEAT-022.framework-error [406]: stream=true 但 Accept 不含 text/event-stream 应返回 406")
    void streamRequestWithoutSseAcceptShouldReturn406() throws Exception {
        String conversationId = "conv-feat022-err-406-" + UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"input\":\"流式请求\",\"stream\":true}";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(customRestUrl(conversationId)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode())
                .as("FEAT-022.framework-error [406]: stream=true 但 Accept 不含 SSE 应返回 406\n"
                        + "  conversationId=%s\n  body=%s", conversationId, response.body())
                .isEqualTo(406);
        assertThat(response.body() == null ? "" : response.body().toLowerCase())
                .as("FEAT-022.framework-error [406]: 错误信封应含 stream_not_acceptable 标记\n  body=%s",
                        response.body())
                .contains("stream_not_acceptable");
    }

    @Test
    @DisplayName("FEAT-022.framework-error [409]: 同 conversation 并发请求应有一个返回 409 conversation_busy")
    void concurrentRequestShouldReturn409ConversationBusy() throws Exception {
        String conversationId = "conv-feat022-err-mutex-" + UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"input\":\"触发并发\",\"stream\":false}";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(customRestUrl(conversationId)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(120))
                .build();

        CompletableFuture<HttpResponse<String>> f1 = http.sendAsync(req, HttpResponse.BodyHandlers.ofString());
        CompletableFuture<HttpResponse<String>> f2 = http.sendAsync(req, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> r1 = f1.get();
        HttpResponse<String> r2 = f2.get();

        boolean has409 = r1.statusCode() == 409 || r2.statusCode() == 409;
        assertThat(has409)
                .as("FEAT-022.framework-error [409]: 并发请求应有一个返回 409\n"
                        + "  conversationId=%s  status1=%d  status2=%d\n  body1=%s\n  body2=%s",
                        conversationId, r1.statusCode(), r2.statusCode(), r1.body(), r2.body())
                .isTrue();

        String errBody = r1.statusCode() == 409 ? r1.body() : r2.body();
        assertThat(errBody == null ? "" : errBody.toLowerCase())
                .as("FEAT-022.framework-error [409]: 409 错误信封应含 conversation_busy 标记\n  body=%s", errBody)
                .contains("conversation_busy");
    }
}

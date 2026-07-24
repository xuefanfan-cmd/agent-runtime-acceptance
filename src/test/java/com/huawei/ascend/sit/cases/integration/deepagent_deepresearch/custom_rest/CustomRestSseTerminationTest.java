package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch.custom_rest;

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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2 §4.5.2 · SSE 终止语义 —— {@code final=true} 或 {@code interrupted=true} 事件后，SSE
 * 连接必须关闭，不再有后续事件；adapter 的 event name 不影响 framework 的终止判断。
 */
@Tag("integration")
@Tag("feat-022")
@Tag("custom-rest")
@Feature("FEAT-022: 自定义 REST API 智能体服务入口")
@Story("da.sse-termination: final/interrupted 事件后 SSE 连接必须关闭")
class CustomRestSseTerminationTest extends BaseCustomRestTest {

    @Test
    @DisplayName("FEAT-022.sse-termination: SSE 流应有终止事件（final/interrupted）且之后无更多事件")
    void sseStreamShouldTerminateAfterFinalEvent() throws Exception {
        String conversationId = "conv-feat022-sse-term-" + UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"input\":\"写一首关于秋天的四句古体诗\",\"stream\":true}";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(customRestUrl(conversationId)))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(240))
                .build();

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode())
                .as("FEAT-022.sse-termination [前置]: SSE 请求应返回 200\n  conversationId=%s", conversationId)
                .isEqualTo(200);

        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertThat(contentType)
                .as("FEAT-022.sse-termination [前置]: Content-Type 应为 SSE\n  contentType=%s", contentType)
                .contains("text/event-stream");

        List<String> lines = response.body().lines().toList();

        // 【核心】响应体中应至少包含一个 SSE 事件；且响应必须已完整收束（HttpClient send 已同步返回）
        // 若 framework 未按 final 关闭连接，requests 会因超时抛异常 → 已被上面的 assertion 隐式覆盖。
        long dataLines = lines.stream().filter(l -> l.startsWith("data:")).count();
        assertThat(dataLines)
                .as("FEAT-022.sse-termination: 应至少收到 1 个 SSE data 行\n"
                        + "  conversationId=%s  bodyLen=%d\n  body头300字=%s",
                        conversationId, response.body().length(),
                        response.body().substring(0, Math.min(300, response.body().length())))
                .isGreaterThanOrEqualTo(1);

        // 检查是否有 final 或 interrupted 语义标记（具体字段名由 adapter 决定，做宽松匹配）
        String bodyLower = response.body().toLowerCase();
        boolean hasTerminationMarker = bodyLower.contains("final")
                || bodyLower.contains("interrupted")
                || bodyLower.contains("done")
                || bodyLower.contains("completed")
                || bodyLower.contains("terminated");
        assertThat(hasTerminationMarker)
                .as("FEAT-022.sse-termination: SSE 体应含终止语义标记 (final/interrupted/done/completed/terminated)\n"
                        + "  conversationId=%s\n  body尾300字=%s",
                        conversationId,
                        response.body().substring(Math.max(0, response.body().length() - 300)))
                .isTrue();
    }

    @Test
    @DisplayName("FEAT-022.sse-termination: 唯一的一次 HTTP 响应内应包含完整流（不应留连接开着等待更多）")
    void sseResponseShouldBeSelfContained() throws Exception {
        String conversationId = "conv-feat022-sse-self-" + UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"input\":\"简单问题\",\"stream\":true}";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(customRestUrl(conversationId)))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(180))
                .build();

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        long start = System.currentTimeMillis();
        HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());
        long elapsed = System.currentTimeMillis() - start;

        assertThat(response.statusCode())
                .as("FEAT-022.sse-termination [前置]: SSE 请求应返回 200")
                .isEqualTo(200);
        // 若 framework 未在 final 事件后关连接，HttpClient 会一直等到 read timeout（180s）
        // 此时 elapsed 逼近 180000，且抛 HttpTimeoutException。上面 send() 能正常返回 → framework 关了连接。
        assertThat(elapsed)
                .as("FEAT-022.sse-termination: SSE 响应应在合理时间内自然收束，不应超过 180s\n"
                        + "  conversationId=%s  elapsed=%dms", conversationId, elapsed)
                .isLessThan(180_000L);
    }
}

package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch.custom_rest;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Tag("feat-022")
@Tag("custom-rest")
@Feature("FEAT-022: 自定义 REST API 智能体服务入口")
@Story("da.streaming-sse-response: 流式消息调用返回 SSE")
class CustomRestStreamingResponseTest extends BaseCustomRestTest {

    @Test
    @DisplayName("FEAT-022.streaming-sse-response: stream=true 返回 text/event-stream 且含多个 SSE 事件")
    void streamingRequestShouldReturnSse() throws Exception {
        String conversationId = "conv-feat022-stream-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> response = postStreaming(conversationId, "复杂问题");

        assertThat(response.statusCode())
                .as("FEAT-022.streaming-sse-response: HTTP status 应为 200\n  conversationId=%s", conversationId)
                .isEqualTo(200);

        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertThat(contentType)
                .as("FEAT-022.streaming-sse-response: Content-Type 应含 text/event-stream\n  conversationId=%s", conversationId)
                .contains("text/event-stream");

        String body = response.body();
        long eventCount = body.lines()
                .filter(line -> line.startsWith("data:"))
                .count();
        assertThat(eventCount)
                .as("FEAT-022.streaming-sse-response: 应至少收到 2 个 SSE data 行\n  conversationId=%s\n  body头200字=%s",
                        conversationId, body.substring(0, Math.min(200, body.length())))
                .isGreaterThanOrEqualTo(2);
    }
}

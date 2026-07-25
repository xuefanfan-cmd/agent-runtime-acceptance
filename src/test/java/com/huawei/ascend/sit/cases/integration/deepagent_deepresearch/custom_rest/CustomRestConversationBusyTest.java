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
 * L2 §4.3.4 · 状态决策 —— {@code SUBMITTED / WORKING} 时同 conversationId 后续请求应返回
 * 409 {@code conversation_busy}。
 *
 * <p>通过并发发两个请求：第一个进入 SUBMITTED/WORKING，第二个应被 framework 拒。
 */
@Tag("integration")
@Tag("feat-022")
@Tag("custom-rest")
@Feature("FEAT-022: 自定义 REST API 智能体服务入口")
@Story("da.conversation-busy: SUBMITTED/WORKING 期间续请求应返回 409 conversation_busy")
class CustomRestConversationBusyTest extends BaseCustomRestTest {

    @Test
    @DisplayName("FEAT-022.conversation-busy: 上一轮 Task 未完成时同 conversationId 请求应返回 409")
    void requestWhileTaskWorkingShouldReturn409ConversationBusy() throws Exception {
        String conversationId = "conv-feat022-busy-" + UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"input\":\"复杂问题，让 planner 进入 WORKING 状态\",\"stream\":false}";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(customRestUrl(conversationId)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(120))
                .build();

        // 异步发第一个（长任务，会占用 conversation）
        CompletableFuture<HttpResponse<String>> f1 = http.sendAsync(req, HttpResponse.BodyHandlers.ofString());

        // 极短延迟后发第二个（此时第一个应还在 SUBMITTED/WORKING）
        Thread.sleep(300);
        HttpResponse<String> r2 = http.send(req, HttpResponse.BodyHandlers.ofString());

        // 等第一个完成，避免用例污染下一个 test
        f1.get();

        assertThat(r2.statusCode())
                .as("FEAT-022.conversation-busy: 上一轮进行中时 (SUBMITTED/WORKING) 同 conversationId 请求应返回 409\n"
                        + "  conversationId=%s\n  实际 status=%d\n  body=%s",
                        conversationId, r2.statusCode(), truncate(r2.body(), 500))
                .isEqualTo(409);

        // 错误信封应携带 conversation_busy 语义（adapter 映射后的字段名可能是 error_code / code）
        String errBody = r2.body() == null ? "" : r2.body();
        assertThat(errBody.toLowerCase())
                .as("FEAT-022.conversation-busy: 错误信封应包含 conversation_busy 语义标记\n"
                        + "  body=%s", errBody)
                .contains("conversation_busy");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}

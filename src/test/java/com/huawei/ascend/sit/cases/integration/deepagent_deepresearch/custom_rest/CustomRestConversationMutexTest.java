package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch.custom_rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@Tag("integration")
@Tag("feat-022")
@Tag("custom-rest")
@Feature("FEAT-022: 自定义 REST API 智能体服务入口")
@Story("da.conversation-mutex: 单 JVM conversation 互斥")
class CustomRestConversationMutexTest extends BaseCustomRestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("FEAT-022.conversation-mutex: 同 conversationId 并发请求，至少一个应返回 409 + 只创建一个 Task")
    void concurrentRequestsToSameConversationShouldReturn409() throws Exception {
        String conversationId = "conv-feat022-mutex-" + UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"input\":\"并发请求\",\"stream\":false}";

        HttpRequest req1 = buildRequest(conversationId, body);
        HttpRequest req2 = buildRequest(conversationId, body);

        CompletableFuture<HttpResponse<String>> f1 = http.sendAsync(req1, HttpResponse.BodyHandlers.ofString());
        CompletableFuture<HttpResponse<String>> f2 = http.sendAsync(req2, HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> r1 = f1.get();
        HttpResponse<String> r2 = f2.get();

        int s1 = r1.statusCode();
        int s2 = r2.statusCode();

        assertThat(s1 == 409 || s2 == 409)
                .as("FEAT-022.conversation-mutex [核心]: 同 conversationId 并发请求，至少一个应返回 409\n"
                        + "  conversationId=%s\n  status1=%d\n  status2=%d", conversationId, s1, s2)
                .isTrue();

        // §3.2 T 层 2: 错误信封应携带 conversation_busy 语义标记
        String loserBody = s1 == 409 ? r1.body() : r2.body();
        assertThat(loserBody == null ? "" : loserBody.toLowerCase())
                .as("FEAT-022.conversation-mutex: 409 错误信封应含 conversation_busy 标记\n  body=%s", loserBody)
                .contains("conversation_busy");

        // §3.2 T 层 2: 只应有一个 Task 被创建 —— 从 200 那侧提取 taskId; 若两侧都非 200 视为 framework 决策异常
        String winnerBody = s1 == 200 ? r1.body() : (s2 == 200 ? r2.body() : null);
        if (winnerBody != null) {
            String winnerTaskId = MAPPER.readTree(winnerBody).path("custom_rsp_data").path("id").asText("");
            assertThat(winnerTaskId)
                    .as("FEAT-022.conversation-mutex: 200 那侧响应应含唯一 Task 的 id\n  winnerBody=%s", winnerBody)
                    .isNotBlank();
            // 409 那侧 body 不应包含该 taskId (证明没有为它单独建 Task)
            assertThat(loserBody)
                    .as("FEAT-022.conversation-mutex [核心]: 409 响应 body 不应包含赢家 taskId,"
                                    + "证明 framework 在 reservation 阶段就拒绝,未走到 executor 创建 Task\n"
                                    + "  winnerTaskId=%s\n  loserBody=%s", winnerTaskId, loserBody)
                    .doesNotContain(winnerTaskId);
        }
    }

    private HttpRequest buildRequest(String conversationId, String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(customRestUrl(conversationId)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(120))
                .build();
    }
}

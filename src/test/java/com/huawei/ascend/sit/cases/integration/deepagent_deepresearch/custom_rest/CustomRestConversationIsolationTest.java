package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch.custom_rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.client.A2aServiceClient;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.Task;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B: 不同 conversationId 隔离 — 并发两个不同 conversationId，两个都应 200 且创建不同 Task。
 */
@Tag("integration")
@Tag("feat-022")
@Tag("custom-rest")
@Feature("FEAT-022: 自定义 REST API 智能体服务入口")
@Story("da.conversation-isolation: 不同 conversationId 创建不同 Task，互不干扰")
class CustomRestConversationIsolationTest extends BaseCustomRestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long POLL_TIMEOUT_MS = 120_000;
    private static final long POLL_INTERVAL_MS = 2_000;

    @Test
    @DisplayName("FEAT-022.conversation-isolation: 不同 conversationId 并发请求均 200，创建不同 Task")
    void differentConversationIdsShouldCreateIsolatedTasks() throws Exception {
        String conv1 = "conv-feat022-iso-a-" + UUID.randomUUID().toString().substring(0, 8);
        String conv2 = "conv-feat022-iso-b-" + UUID.randomUUID().toString().substring(0, 8);

        CompletableFuture<HttpResponse<String>> f1 = CompletableFuture.supplyAsync(() -> {
            try { return postSync(conv1, "问题一"); } catch (Exception e) { throw new RuntimeException(e); }
        });
        CompletableFuture<HttpResponse<String>> f2 = CompletableFuture.supplyAsync(() -> {
            try { return postSync(conv2, "问题二"); } catch (Exception e) { throw new RuntimeException(e); }
        });

        HttpResponse<String> r1 = f1.get();
        HttpResponse<String> r2 = f2.get();

        assertThat(r1.statusCode())
                .as("FEAT-022.conversation-isolation: conv1 请求应成功\n  conv1=%s\n  body=%s", conv1, r1.body())
                .isEqualTo(200);
        assertThat(r2.statusCode())
                .as("FEAT-022.conversation-isolation: conv2 请求应成功\n  conv2=%s\n  body=%s", conv2, r2.body())
                .isEqualTo(200);

        String taskId1 = MAPPER.readTree(r1.body()).path("custom_rsp_data").path("id").asText("");
        String taskId2 = MAPPER.readTree(r2.body()).path("custom_rsp_data").path("id").asText("");

        assertThat(taskId1).as("FEAT-022.conversation-isolation: conv1 响应 custom_rsp_data.id 应非空").isNotBlank();
        assertThat(taskId2).as("FEAT-022.conversation-isolation: conv2 响应 custom_rsp_data.id 应非空").isNotBlank();

        assertThat(taskId1)
                .as("FEAT-022.conversation-isolation [核心]: 不同 conversationId 应创建不同 Task\n"
                        + "  conv1=%s taskId1=%s\n  conv2=%s taskId2=%s", conv1, taskId1, conv2, taskId2)
                .isNotEqualTo(taskId2);

        // 通过 A2A GetTask 验证两个 Task 独立存在
        A2aServiceClient a2a = client(DEEP_RESEARCH);
        Task t1 = Awaitility.await("task1 reachable")
                .atMost(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .pollInterval(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .until(() -> a2a.getTask(taskId1), t -> t != null && t.status() != null);
        Task t2 = Awaitility.await("task2 reachable")
                .atMost(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .pollInterval(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .until(() -> a2a.getTask(taskId2), t -> t != null && t.status() != null);

        assertThat(t1.id()).isEqualTo(taskId1);
        assertThat(t2.id()).isEqualTo(taskId2);
    }
}

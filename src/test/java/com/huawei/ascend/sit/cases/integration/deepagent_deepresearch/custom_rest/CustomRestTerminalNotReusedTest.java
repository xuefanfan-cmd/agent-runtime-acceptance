package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch.custom_rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.client.A2aServiceClient;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2 §4.3.4 · 状态决策 —— 上一轮 Task 已到 {@code COMPLETED/FAILED} 等终态后，同 conversationId
 * 再次请求应创建新 Task，不复用终态 Task。
 */
@Tag("integration")
@Tag("feat-022")
@Tag("custom-rest")
@Feature("FEAT-022: 自定义 REST API 智能体服务入口")
@Story("da.terminal-not-reused: 终态 Task 不复用，同 conversationId 后续请求应创建新 Task")
class CustomRestTerminalNotReusedTest extends BaseCustomRestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long POLL_TIMEOUT_MS = 180_000;
    private static final long POLL_INTERVAL_MS = 2_000;

    @Test
    @DisplayName("FEAT-022.terminal-not-reused: 终态后同 conversationId 再来请求应创建新 Task，不复用旧 Task")
    void requestAfterTerminalShouldCreateNewTask() throws Exception {
        String conversationId = "conv-feat022-terminal-" + UUID.randomUUID().toString().substring(0, 8);
        A2aServiceClient a2a = client(DEEP_RESEARCH);

        // 第一轮：等到终态
        HttpResponse<String> r1 = postSync(conversationId, "简单问题一");
        assertThat(r1.statusCode())
                .as("FEAT-022.terminal-not-reused [前置]: 首轮请求应成功\n  conversationId=%s", conversationId)
                .isEqualTo(200);

        JsonNode body1 = MAPPER.readTree(r1.body());
        String firstTaskId = body1.path("custom_rsp_data").path("id").asText("");
        assertThat(firstTaskId).as("FEAT-022.terminal-not-reused [前置]: 首轮响应 custom_rsp_data.id 应非空").isNotBlank();

        // 等首轮 Task 进入终态
        Task firstTerminal = Awaitility.await("first task reaches terminal state")
                .atMost(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .pollInterval(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .until(() -> a2a.getTask(firstTaskId),
                        t -> t != null && t.status() != null && t.status().state().isFinal());

        assertThat(firstTerminal.status().state())
                .as("FEAT-022.terminal-not-reused [前置]: 首轮 Task 应到达终态")
                .matches(TaskState::isFinal);

        // 第二轮：终态后再来，应创建新 Task
        HttpResponse<String> r2 = postSync(conversationId, "简单问题二");
        assertThat(r2.statusCode())
                .as("FEAT-022.terminal-not-reused: 终态后同 conversationId 请求应成功\n  conversationId=%s", conversationId)
                .isEqualTo(200);

        JsonNode body2 = MAPPER.readTree(r2.body());
        String secondTaskId = body2.path("custom_rsp_data").path("id").asText("");

        assertThat(secondTaskId)
                .as("FEAT-022.terminal-not-reused [核心]: 终态 Task 不应被复用，第二轮应创建新 task_id\n"
                        + "  firstTaskId=%s\n  secondTaskId=%s", firstTaskId, secondTaskId)
                .isNotBlank()
                .isNotEqualTo(firstTaskId);
    }
}

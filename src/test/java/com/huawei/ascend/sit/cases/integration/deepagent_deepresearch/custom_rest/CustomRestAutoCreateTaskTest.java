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

@Tag("integration")
@Tag("feat-022")
@Tag("custom-rest")
@Feature("FEAT-022: 自定义 REST API 智能体服务入口")
@Story("da.auto-create-task: 自动创建首轮 Task")
class CustomRestAutoCreateTaskTest extends BaseCustomRestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long POLL_TIMEOUT_MS = 120_000;
    private static final long POLL_INTERVAL_MS = 2_000;

    @Test
    @DisplayName("FEAT-022.auto-create-task: 首轮请求应自动创建新 Task，可通过标准 A2A GetTask 查询")
    void firstRequestShouldAutoCreateTask() throws Exception {
        String conversationId = "conv-feat022-create-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> response = postSync(conversationId, "第一轮问题");

        assertThat(response.statusCode())
                .as("FEAT-022.auto-create-task: 首轮请求应成功\n  conversationId=%s", conversationId)
                .isEqualTo(200);

        // SUT example adapter 把 Task 完整嵌在 custom_rsp_data 里(不丢信息),task.id 在 custom_rsp_data.id
        JsonNode body = MAPPER.readTree(response.body());
        String taskId = body.path("custom_rsp_data").path("id").asText("");
        assertThat(taskId)
                .as("FEAT-022.auto-create-task: 响应 custom_rsp_data.id 应非空(task_id)\n  body=%s", response.body())
                .isNotBlank();

        // 通过标准 A2A 入口验证 Task 存在
        A2aServiceClient a2a = client(DEEP_RESEARCH);
        Task task = Awaitility.await("task reachable via A2A GetTask")
                .atMost(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .pollInterval(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .until(() -> a2a.getTask(taskId), t -> t != null && t.status() != null);

        assertThat(task.id())
                .as("FEAT-022.auto-create-task: GetTask 返回的 task.id 应与响应中一致")
                .isEqualTo(taskId);

        // L2 §4.3.2: framework 内部 contextId 形如 custom-rest:v1:<projectId>:<agentId>:<sha256(conversationId)>
        assertThat(task.contextId())
                .as("FEAT-022.auto-create-task: task.contextId 应为 framework 内部拼装形式(custom-rest:v1: 前缀)\n"
                        + "  实际 contextId=%s", task.contextId())
                .startsWith("custom-rest:v1:");

        // §3.2 T: Task 状态应为终态或 INPUT_REQUIRED (证明 framework 走完首轮决策 CREATE_NEW → 交给 executor)
        TaskState state = task.status().state();
        assertThat(state.isFinal() || state == TaskState.TASK_STATE_INPUT_REQUIRED)
                .as("FEAT-022.auto-create-task: 首轮 Task 应到达终态或 INPUT_REQUIRED\n"
                        + "  实际 state=%s", state)
                .isTrue();
    }
}

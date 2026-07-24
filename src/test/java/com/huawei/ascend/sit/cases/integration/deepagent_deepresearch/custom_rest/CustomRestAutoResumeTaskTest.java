package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch.custom_rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.client.A2aServiceClient;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Assumptions;
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
@Story("da.auto-resume-task: 自动续轮 INPUT_REQUIRED Task")
class CustomRestAutoResumeTaskTest extends BaseCustomRestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long POLL_TIMEOUT_MS = 120_000;
    private static final long POLL_INTERVAL_MS = 2_000;

    @Test
    @DisplayName("FEAT-022.auto-resume-task: 相同 conversationId 第二轮请求应续写 INPUT_REQUIRED Task，不创建新 Task")
    void secondRequestWithSameConversationShouldResumeTask() throws Exception {
        String conversationId = "conv-feat022-resume-" + UUID.randomUUID().toString().substring(0, 8);
        A2aServiceClient a2a = client(DEEP_RESEARCH);

        // 第一轮
        HttpResponse<String> first = postSync(conversationId, "第一轮问题");
        assertThat(first.statusCode())
                .as("FEAT-022.auto-resume-task [前置]: 首轮请求应成功\n  conversationId=%s", conversationId)
                .isEqualTo(200);

        JsonNode firstBody = MAPPER.readTree(first.body());
        String firstTaskId = firstBody.path("custom_rsp_data").path("id").asText("");
        assertThat(firstTaskId).as("FEAT-022.auto-resume-task [前置]: 首轮响应 custom_rsp_data.id 应非空").isNotBlank();

        // 等首轮 Task 进入终态或 INPUT_REQUIRED
        Task firstTask = Awaitility.await("first task reaches stable state")
                .atMost(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .pollInterval(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .until(() -> a2a.getTask(firstTaskId),
                        t -> t != null && t.status() != null
                                && (t.status().state().isFinal()
                                    || t.status().state() == TaskState.TASK_STATE_INPUT_REQUIRED));

        // 首轮若直接进终态,续轮不可测 -> 显式 INCONCLUSIVE(避免静默 skip 掩盖假绿)
        Assumptions.assumeTrue(
                firstTask.status().state() == TaskState.TASK_STATE_INPUT_REQUIRED,
                "FEAT-022.auto-resume-task [INCONCLUSIVE]: 首轮直接进入终态 " + firstTask.status().state()
                        + ", 无法验证 INPUT_REQUIRED 续轮。deep-research 业务流程决定该轮是否进 INPUT_REQUIRED。");

        // 第二轮（相同 conversationId）
        HttpResponse<String> second = postSync(conversationId, "第二轮回答");
        assertThat(second.statusCode())
                .as("FEAT-022.auto-resume-task: 续轮请求应成功\n  conversationId=%s", conversationId)
                .isEqualTo(200);

        JsonNode secondBody = MAPPER.readTree(second.body());
        String secondTaskId = secondBody.path("custom_rsp_data").path("id").asText("");

        assertThat(secondTaskId)
                .as("FEAT-022.auto-resume-task: 续轮应复用原 Task，task_id 应与首轮相同\n"
                        + "  firstTaskId=%s\n  secondTaskId=%s", firstTaskId, secondTaskId)
                .isEqualTo(firstTaskId);

        // 验证 Task 状态已推进
        Task resumedTask = Awaitility.await("resumed task reaches terminal state")
                .atMost(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .pollInterval(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .until(() -> a2a.getTask(firstTaskId),
                        t -> t != null && t.status() != null && t.status().state().isFinal());

        assertThat(resumedTask.status().state())
                .as("FEAT-022.auto-resume-task: 续轮后 Task 应推进到终态")
                .isNotEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);
    }
}

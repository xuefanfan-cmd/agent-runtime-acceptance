package com.huawei.ascend.sit.cases.integration.service_2_agent;

import com.huawei.ascend.sit.base.BaseIntegrationTest;
import com.huawei.ascend.sit.utils.WaitUtils;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration-level tests for the sub-chain: A2A interface → Weather Agent.
 *
 * <p>Validates the composite sub-chain where the A2A client sends a
 * message that is dispatched to the Weather Agent for execution.</p>
 *
 * <p>Corresponds to SIT-SC-02 (编排执行子链路) in the integration test design.</p>
 */
@Tag("integration")
@Tag("smoke")
@Disabled("示例用例，待联调验证后逐个放开")
class A2aToWeatherAgentTest extends BaseIntegrationTest {

    @Test
    @DisplayName("A2A → Weather Agent: full dispatch chain returns completed task")
    void a2aToWeatherAgent_fullChain_shouldReturnCompletedTask() {
        // given — submit via A2A protocol
        String taskId = agentClient.triggerWeatherAgent("北京明天天气如何，适合出门吗");

        // when — poll until terminal
        Task result = WaitUtils.pollUntilTerminal(
                () -> a2aClient.getTask(taskId),
                config.getPollTimeoutSeconds(),
                config.getPollIntervalMs());

        // then — verify end-to-end chain completed
        assertThat(result.status().state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertThat(result.id()).isEqualTo(taskId);
    }

    @Test
    @DisplayName("A2A → Weather Agent: task state transitions are valid")
    void a2aToWeatherAgent_stateTransitions_shouldBeValid() {
        // given
        String taskId = agentClient.triggerWeatherAgent("武汉天气查询");

        // when — observe initial state
        Task initialTask = a2aClient.getTask(taskId);

        // then — initial state should be non-terminal
        assertThat(initialTask.status().state()).isIn(
                TaskState.TASK_STATE_SUBMITTED, TaskState.TASK_STATE_WORKING, TaskState.TASK_STATE_INPUT_REQUIRED);

        // when — wait for terminal state
        Task finalTask = WaitUtils.pollUntilTerminal(
                () -> a2aClient.getTask(taskId),
                config.getPollTimeoutSeconds(),
                config.getPollIntervalMs());

        // then — DFA transition: non-terminal → terminal
        assertThat(WaitUtils.isTerminal(finalTask)).isTrue();
    }

    @Test
    @DisplayName("A2A → Weather Agent: cancellation interrupts execution mid-chain")
    void a2aToWeatherAgent_cancelMidChain_shouldTransitionToCanceled() {
        // given — trigger a weather query
        String taskId = agentClient.triggerWeatherAgent("重庆天气");

        // when — cancel immediately after creation
        Task cancelled = a2aClient.cancelTask(taskId);

        // then — should be in CANCELED state
        assertThat(cancelled.status().state()).isEqualTo(TaskState.TASK_STATE_CANCELED);
    }
}

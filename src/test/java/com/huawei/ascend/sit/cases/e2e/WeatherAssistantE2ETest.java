package com.huawei.ascend.sit.cases.e2e;

import com.huawei.ascend.sit.base.BaseE2ETest;
import com.huawei.ascend.sit.utils.WaitUtils;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end acceptance test for the Weather Assistant full chain.
 *
 * <p>Simulates a real user journey via the A2A protocol. No mocks are used.</p>
 *
 * <p>Corresponds to SIT-E2E-01 (标准 Agent 生命周期) in the integration test design.</p>
 */
@Tag("e2e")
@Tag("smoke")
@Disabled("示例用例，待联调验证后逐个放开")
class WeatherAssistantE2ETest extends BaseE2ETest {

    @Test
    @DisplayName("E2E: User sends weather query and receives a complete response")
    void e2e_weatherQuery_fullLifecycle() {
        // when — user sends a natural language weather query via A2A
        String taskId = a2aClient.sendMessage("北京今天天气怎么样，适合户外活动吗？");

        // then — task ID is returned
        assertThat(taskId).isNotBlank();

        // when — poll until the full chain completes
        Task result = WaitUtils.pollUntilTerminal(
                () -> a2aClient.getTask(taskId),
                config.getPollTimeoutSeconds(),
                config.getPollIntervalMs());

        // then — full chain succeeds
        assertThat(result.status().state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertThat(result.artifacts()).isNotEmpty();
    }

    @Test
    @DisplayName("E2E: Multi-turn weather conversation maintains context")
    void e2e_multiTurn_maintainsContext() {
        // Turn 1: Ask about Beijing weather
        String taskId1 = a2aClient.sendMessage("北京今天天气");
        Task result1 = WaitUtils.pollUntilTerminal(
                () -> a2aClient.getTask(taskId1),
                config.getPollTimeoutSeconds(),
                config.getPollIntervalMs());
        assertThat(result1.status().state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);

        // Turn 2: Follow-up question referencing previous context
        String taskId2 = a2aClient.sendMessage("那明天呢？");
        Task result2 = WaitUtils.pollUntilTerminal(
                () -> a2aClient.getTask(taskId2),
                config.getPollTimeoutSeconds(),
                config.getPollIntervalMs());
        assertThat(result2.status().state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
    }

    @Test
    @DisplayName("E2E: Agent card remains resolvable during task execution")
    void e2e_agentCardRemainsResolvableDuringExecution() {
        // given — submit a task
        a2aClient.sendMessage("上海天气");

        // when — resolve agent card concurrently
        AgentCard card = a2aClient.getAgentCard();

        // then — agent card should still be reachable
        assertThat(card).isNotNull();
        assertThat(card.name()).isNotBlank();
    }
}

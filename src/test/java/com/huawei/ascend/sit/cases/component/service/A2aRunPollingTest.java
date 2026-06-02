package com.huawei.ascend.sit.cases.component.service;

import com.huawei.ascend.sit.base.BaseComponentTest;
import com.huawei.ascend.sit.utils.WaitUtils;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Component-level tests for A2A task polling (getTask).
 *
 * <p>Verifies task state retrieval, state transitions, and
 * the A2A protocol's task lifecycle.</p>
 */
@Tag("component")
@Disabled("示例用例，待联调验证后逐个放开")
class A2aRunPollingTest extends BaseComponentTest {

    @Test
    @DisplayName("getTask returns current task state")
    void getTask_shouldReturnCurrentState() {
        // given — create a task
        String taskId = a2aClient.sendMessage("上海天气");

        // when
        Task task = a2aClient.getTask(taskId);

        // then
        assertThat(task).isNotNull();
        assertThat(task.id()).isEqualTo(taskId);
        assertThat(task.status().state()).isNotNull();
    }

    @Test
    @DisplayName("Task reaches a terminal state within timeout")
    void task_shouldReachTerminalState() {
        // given
        String taskId = a2aClient.sendMessage("深圳今天天气");

        // when — poll until terminal
        Task finalState = WaitUtils.pollUntilTerminal(
                () -> a2aClient.getTask(taskId),
                config.getPollTimeoutSeconds(),
                config.getPollIntervalMs());

        // then
        assertThat(WaitUtils.isTerminal(finalState)).isTrue();
        assertThat(finalState.status().state()).isIn(
                TaskState.TASK_STATE_COMPLETED, TaskState.TASK_STATE_FAILED, TaskState.TASK_STATE_CANCELED);
    }
}

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
 * Component-level tests for A2A task cancellation (cancelTask).
 *
 * <p>Verifies that the cancel path works and that a cancelled
 * task transitions to the CANCELED state.</p>
 */
@Tag("component")
@Disabled("示例用例，待联调验证后逐个放开")
class A2aRunCancellationTest extends BaseComponentTest {

    @Test
    @DisplayName("cancelTask returns a task with CANCELED state")
    void cancelTask_shouldReturnCanceledTask() {
        // given — create a task to cancel
        String taskId = a2aClient.sendMessage("广州天气");

        // when
        Task cancelled = a2aClient.cancelTask(taskId);

        // then
        assertThat(cancelled).isNotNull();
        assertThat(cancelled.status().state()).isEqualTo(TaskState.TASK_STATE_CANCELED);
    }

    @Test
    @DisplayName("Cancelled task observed via getTask is in CANCELED state")
    void cancelledTask_shouldBeQueryableAsCanceled() {
        // given
        String taskId = a2aClient.sendMessage("成都天气");

        // when — cancel and then poll
        a2aClient.cancelTask(taskId);

        Task finalState = WaitUtils.pollUntilTerminal(
                () -> a2aClient.getTask(taskId),
                config.getPollTimeoutSeconds(),
                config.getPollIntervalMs());

        // then
        assertThat(finalState.status().state()).isEqualTo(TaskState.TASK_STATE_CANCELED);
    }
}

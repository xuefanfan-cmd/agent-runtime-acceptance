package com.huawei.ascend.sit.cases.component.protocol;

import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.utils.WaitUtils;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared cancel + get assertions for A-06.
 */
final class TaskCancelVerifiers {

    private TaskCancelVerifiers() {
    }

    static void assertCancelAndGet(
            A2aServiceClient a2a,
            String taskId,
            long cancelPollMs,
            TaskState expectedState) {
        Task canceled = a2a.cancelTask(taskId);
        assertThat(canceled).as("cancelTask result").isNotNull();
        assertThat(canceled.id()).as("cancel taskId").isEqualTo(taskId);
        assertThat(canceled.status().state())
                .as("cancel state")
                .isEqualTo(expectedState);

        int pollSeconds = Math.max(1, (int) ((cancelPollMs + 999) / 1000));
        Task queried = WaitUtils.pollUntilTerminal(
                () -> a2a.getTask(taskId), pollSeconds, 500);
        assertThat(queried.id()).as("get taskId").isEqualTo(taskId);
        assertThat(queried.status().state())
                .as("get state")
                .isEqualTo(expectedState);
    }
}

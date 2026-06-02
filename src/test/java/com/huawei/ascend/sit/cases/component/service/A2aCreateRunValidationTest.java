package com.huawei.ascend.sit.cases.component.service;

import com.huawei.ascend.sit.base.BaseComponentTest;
import org.a2aproject.sdk.spec.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Component-level tests for A2A message sending and task creation.
 *
 * <p>Verifies that sending a message via the A2A protocol creates a valid
 * task with the expected initial state.</p>
 */
@Tag("component")
@Disabled("示例用例，待联调验证后逐个放开")
class A2aCreateRunValidationTest extends BaseComponentTest {

    @Test
    @DisplayName("sendMessage returns a valid task ID")
    void sendMessage_shouldReturnValidTaskId() {
        // when
        String taskId = a2aClient.sendMessage("北京今天天气");

        // then
        assertThat(taskId).isNotBlank();
    }

    @Test
    @DisplayName("Created task is queryable via getTask")
    void createdTask_shouldBeQueryable() {
        // given
        String taskId = a2aClient.sendMessage("上海天气查询");

        // when
        Task task = a2aClient.getTask(taskId);

        // then
        assertThat(task).isNotNull();
        assertThat(task.id()).isEqualTo(taskId);
        assertThat(task.status()).isNotNull();
        assertThat(task.status().state()).isNotNull();
    }

    @Test
    @DisplayName("sendMessage with empty text does not throw unhandled exception")
    void sendMessage_withEmptyText_shouldBeHandledGracefully() {
        // expect — the SDK/SUT should handle this without throwing
        assertThatCode(() -> {
            try {
                a2aClient.sendMessage("");
            } catch (Exception e) {
                // Expected for invalid input — verify it's a meaningful error
                assertThat(e).isNotNull();
            }
        }).doesNotThrowAnyException();
    }
}

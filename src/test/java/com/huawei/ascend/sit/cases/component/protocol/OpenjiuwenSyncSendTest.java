package com.huawei.ascend.sit.cases.component.protocol;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.cases.support.openjiuwen.OpenjiuwenStackSupport;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.TaskTextExtractor;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.model.openjiuwen.OpenjiuwenSyncSendScenarioData;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * OJ-01 — openjiuwen A2A synchronous SendMessage minimal loop.
 *
 * <p>See {@code docs/cases/OJ-01-openjiuwen-sync-send-minimal.md}.</p>
 */
@Tag("component")
@Tag("openjiuwen")
@Tag("smoke")
class OpenjiuwenSyncSendTest extends BaseManagedStackTest {

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return OpenjiuwenStackSupport.mainplanSync(config);
    }

    @Test
    @DisplayName("OJ-01: 同步 message/send — SUBMITTED→COMPLETED，回复非空")
    void oj01_syncSend_reachesCompletedWithNonBlankText() {
        OpenjiuwenSyncSendScenarioData scenario = OpenjiuwenSyncSendScenarioData.loadDefault();
        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> sendError = new AtomicReference<>();

        client(OpenjiuwenStackSupport.MAINPLAN).sendMessage(
                A2A.toUserMessage(scenario.inputText()),
                List.of(collector.createConsumer()),
                error -> sendError.set(error));

        if (sendError.get() != null) {
            fail("message/send failed", sendError.get());
        }

        TaskState terminalState = collector.awaitTerminalState(scenario.sendTimeoutMs());
        assertThat(terminalState)
                .as("OJ-01.A send terminal state")
                .isEqualTo(scenario.resolvedExpectedTerminalState());

        String taskId = collector.findFirstTaskId();
        assertThat(taskId).as("send taskId").isNotBlank();

        Task terminalTask = collector.findTerminalEvent()
                .flatMap(OpenjiuwenSyncSendTest::taskFrom)
                .orElseGet(() -> client(OpenjiuwenStackSupport.MAINPLAN).getTask(taskId));

        String responseText = TaskTextExtractor.textOf(terminalTask);
        assertThat(responseText.length())
                .as("OJ-01.B response text length")
                .isGreaterThanOrEqualTo(scenario.minResponseLength());
    }

    private static Optional<Task> taskFrom(ClientEvent event) {
        if (event instanceof TaskEvent taskEvent) {
            return Optional.of(taskEvent.getTask());
        }
        if (event instanceof TaskUpdateEvent updateEvent) {
            return Optional.of(updateEvent.getTask());
        }
        return Optional.empty();
    }
}

package com.huawei.ascend.sit.cases.component.protocol;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.A2aStreamErrors;
import com.huawei.ascend.sit.client.CancelWindow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.model.protocol.TaskCancelScenarioData;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * A-06-S — tasks/cancel on streaming {@code message/stream} (特性 4-5).
 *
 * <p>LLM credentials are not checked in this class — configure {@code LLM_*} (or equivalent)
 * before launch so the managed mainplan process can reach the model. See
 * {@code docs/cases/A-06-task-cancel.md}.</p>
 */
@Tag("component")
@Tag("smoke")
class AgentTaskCancelStreamTest extends BaseManagedStackTest {

    private static final Logger LOG = Logger.getLogger(AgentTaskCancelStreamTest.class.getName());

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return mainplanStack(config, true);
    }

    @Test
    @DisplayName("A-06-S: 流式 message/stream 过程中 cancel → CANCELED")
    void a06_streamCancel_reachesCanceledState() throws InterruptedException {
        TaskCancelScenarioData scenario = TaskCancelScenarioData.loadDefault();
        A2aServiceClient a2a = client("mainplan");

        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> streamError = new AtomicReference<>();

        Thread sendThread = Thread.ofVirtual().name("a06-stream-send").start(() ->
                a2a.sendMessage(
                        A2A.toUserMessage(scenario.inputText()),
                        List.of(collector.createConsumer()),
                        error -> streamError.set(error)));

        CancelWindow.Result window = CancelWindow.await(
                collector, scenario.taskIdWaitMs(), scenario.cancelWaitMs());
        LOG.info("A-06.E cancel_at_state=" + window.cancelAtState());

        TaskCancelVerifiers.assertCancelAndGet(
                a2a, window.taskId(), scenario.cancelPollMs(), scenario.expectedCanceledState());

        TaskState streamTerminal = collector.awaitTerminalState(scenario.streamTimeoutMs());
        assertThat(streamTerminal)
                .as("stream terminal state")
                .isEqualTo(scenario.expectedCanceledState());
        assertThat(streamTerminal)
                .as("stream must not complete successfully")
                .isNotEqualTo(TaskState.TASK_STATE_COMPLETED);

        sendThread.join(scenario.streamTimeoutMs());
        Throwable streamFailure = streamError.get();
        if (streamFailure != null && !A2aStreamErrors.isBenignShutdown(streamFailure)) {
            fail("message/stream failed after cancel", streamFailure);
        }
    }

    static SutStack.Builder mainplanStack(TestConfig config, boolean streaming) {
        return SutStack.builder(config)
                .streaming(streaming)
                .agent("mainplan");
    }
}

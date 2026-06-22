package com.huawei.ascend.sit.cases.component.protocol;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.A2aStreamErrors;
import com.huawei.ascend.sit.client.CancelWindow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.model.protocol.A06ScenarioData;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * A-06-S — tasks/cancel on streaming {@code message/stream} (特性 4-5).
 *
 * <p>See {@code docs/cases/A-06-task-cancel.md}.</p>
 */
@Tag("component")
@Tag("smoke")
@EnabledIf("com.huawei.ascend.sit.cases.component.protocol.A06LlmGate#isExecutable")
class AgentTaskCancelStreamTest extends BaseManagedStackTest {

    private static final Logger LOG = Logger.getLogger(AgentTaskCancelStreamTest.class.getName());

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return mainplanStack(config, true);
    }

    @Test
    @DisplayName("A-06-S: 流式 message/stream 过程中 cancel → CANCELED")
    void a06_streamCancel_reachesCanceledState() throws InterruptedException {
        requireLlmKey();

        A06ScenarioData scenario = A06ScenarioData.loadDefault();
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

        A06CancelVerifiers.assertCancelAndGet(
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
                .agent("mainplan", a -> {
                    String apiKey = System.getenv(A06LlmGate.LLM_KEY_ENV);
                    if (apiKey != null && !apiKey.isBlank()) {
                        a.property("main-plan-agent.api-key", apiKey);
                    }
                });
    }

    private static void requireLlmKey() {
        String apiKey = System.getenv(A06LlmGate.LLM_KEY_ENV);
        if (apiKey == null || apiKey.isBlank()) {
            fail("SIT_LLM_API_KEY must be set for A-06");
        }
    }
}

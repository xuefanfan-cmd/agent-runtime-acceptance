package com.huawei.ascend.sit.cases.component.boundary;

import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.A2aStreamErrors;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.fail;

/**
 * C-06 empty message + health probe flow (shared by sync and stream tests).
 *
 * <p>Scenario constants live here (no main ScenarioData). Matches
 * {@code testdata/component/boundary/c06-empty-message.json}.</p>
 */
final class EmptyMessageFlow {

    static final String INPUT_TEXT = "";
    static final String HEALTH_PROBE_TEXT = "你好";
    static final long EMPTY_MESSAGE_TIMEOUT_MS = 30_000L;
    static final long HEALTH_PROBE_TIMEOUT_MS = 60_000L;
    static final TaskState EXPECTED_TERMINAL_STATE = TaskState.TASK_STATE_FAILED;
    static final String EXPECTED_ERROR_CODE = "INVALID_INPUT";
    static final List<String> EXPECTED_ERROR_DETAIL_FRAGMENTS = List.of("no text");
    static final TaskState HEALTH_PROBE_EXPECTED_TERMINAL_STATE = TaskState.TASK_STATE_COMPLETED;

    private EmptyMessageFlow() {
    }

    static void run(A2aServiceClient a2a, String label, boolean streamSendOnBackground)
            throws InterruptedException {
        // ---- empty message ----
        A2aEventCollector emptyCollector = new A2aEventCollector();
        AtomicReference<Throwable> emptyError = new AtomicReference<>();

        Runnable emptySend = () -> a2a.sendMessage(
                A2A.toUserMessage(INPUT_TEXT),
                List.of(emptyCollector.createConsumer()),
                error -> emptyError.set(error));

        if (streamSendOnBackground) {
            Thread sendThread = Thread.ofVirtual().name(label + "-empty").start(emptySend);
            EmptyMessageAssertions.assertEmptyMessageFailed(emptyCollector, label);
            sendThread.join(EMPTY_MESSAGE_TIMEOUT_MS);
        } else {
            emptySend.run();
            EmptyMessageAssertions.assertEmptyMessageFailed(emptyCollector, label);
        }
        assertStreamHealthy(emptyError, label + " empty send");

        // ---- health probe ----
        A2aEventCollector probeCollector = new A2aEventCollector();
        AtomicReference<Throwable> probeError = new AtomicReference<>();

        Runnable probeSend = () -> a2a.sendMessage(
                A2A.toUserMessage(HEALTH_PROBE_TEXT),
                List.of(probeCollector.createConsumer()),
                error -> probeError.set(error));

        if (streamSendOnBackground) {
            Thread probeThread = Thread.ofVirtual().name(label + "-probe").start(probeSend);
            probeCollector.awaitTerminalState(HEALTH_PROBE_TIMEOUT_MS);
            probeThread.join(HEALTH_PROBE_TIMEOUT_MS);
        } else {
            probeSend.run();
            probeCollector.awaitTerminalState(HEALTH_PROBE_TIMEOUT_MS);
        }
        assertStreamHealthy(probeError, label + " health probe");

        String probeTaskId = probeCollector.findFirstTaskId();
        Task probeTask = a2a.getTask(probeTaskId);
        EmptyMessageAssertions.assertHealthProbeCompleted(probeTask, label);
    }

    private static void assertStreamHealthy(AtomicReference<Throwable> streamError, String label) {
        Throwable failure = streamError.get();
        if (failure != null && !A2aStreamErrors.isBenignShutdown(failure)) {
            fail(label + " transport failed", failure);
        }
    }
}

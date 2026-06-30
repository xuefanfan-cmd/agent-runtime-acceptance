package com.huawei.ascend.sit.cases.component.boundary;

import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.A2aStreamErrors;
import com.huawei.ascend.sit.model.component.boundary.EmptyMessageScenarioData;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.spec.Task;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.fail;

/**
 * C-06 empty message + health probe flow (shared by sync and stream tests).
 */
final class EmptyMessageFlow {

    private EmptyMessageFlow() {
    }

    static void run(A2aServiceClient a2a, EmptyMessageScenarioData scenario, String label, boolean streamSendOnBackground)
            throws InterruptedException {
        // ---- empty message ----
        A2aEventCollector emptyCollector = new A2aEventCollector();
        AtomicReference<Throwable> emptyError = new AtomicReference<>();

        Runnable emptySend = () -> a2a.sendMessage(
                A2A.toUserMessage(scenario.inputText()),
                List.of(emptyCollector.createConsumer()),
                error -> emptyError.set(error));

        if (streamSendOnBackground) {
            Thread sendThread = Thread.ofVirtual().name(label + "-empty").start(emptySend);
            EmptyMessageAssertions.assertEmptyMessageFailed(emptyCollector, scenario, label);
            sendThread.join(scenario.emptyMessageTimeoutMs());
        } else {
            emptySend.run();
            EmptyMessageAssertions.assertEmptyMessageFailed(emptyCollector, scenario, label);
        }
        assertStreamHealthy(emptyError, label + " empty send");

        // ---- health probe ----
        A2aEventCollector probeCollector = new A2aEventCollector();
        AtomicReference<Throwable> probeError = new AtomicReference<>();

        Runnable probeSend = () -> a2a.sendMessage(
                A2A.toUserMessage(scenario.healthProbeText()),
                List.of(probeCollector.createConsumer()),
                error -> probeError.set(error));

        if (streamSendOnBackground) {
            Thread probeThread = Thread.ofVirtual().name(label + "-probe").start(probeSend);
            probeCollector.awaitTerminalState(scenario.healthProbeTimeoutMs());
            probeThread.join(scenario.healthProbeTimeoutMs());
        } else {
            probeSend.run();
            probeCollector.awaitTerminalState(scenario.healthProbeTimeoutMs());
        }
        assertStreamHealthy(probeError, label + " health probe");

        String probeTaskId = probeCollector.findFirstTaskId();
        Task probeTask = a2a.getTask(probeTaskId);
        EmptyMessageAssertions.assertHealthProbeCompleted(probeTask, scenario, label);
    }

    private static void assertStreamHealthy(AtomicReference<Throwable> streamError, String label) {
        Throwable failure = streamError.get();
        if (failure != null && !A2aStreamErrors.isBenignShutdown(failure)) {
            fail(label + " transport failed", failure);
        }
    }
}

package com.huawei.ascend.sit.cases.component.boundary;

import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.A2aStreamErrors;
import com.huawei.ascend.sit.model.component.boundary.LongTravelMessageScenarioData;
import com.huawei.ascend.sit.model.component.boundary.LongMessageInputBuilder;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.spec.Task;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * C-07 long message + health probe flow (shared by sync and stream tests).
 */
final class LongTravelMessageFlow {

    private static final Logger LOG = Logger.getLogger(LongTravelMessageFlow.class.getName());

    private LongTravelMessageFlow() {
    }

    static void run(A2aServiceClient a2a, LongTravelMessageScenarioData scenario, String label, boolean streaming)
            throws InterruptedException {
        String longInput = LongMessageInputBuilder.build(scenario);
        assertThat(longInput.length())
                .as(label + " input length")
                .isGreaterThanOrEqualTo(scenario.minInputChars());
        LOG.info(label + " long_input_chars=" + longInput.length());

        // ---- long message ----
        A2aEventCollector longCollector = new A2aEventCollector();
        AtomicReference<Throwable> longError = new AtomicReference<>();

        Runnable longSend = () -> a2a.sendMessage(
                A2A.toUserMessage(longInput),
                List.of(longCollector.createConsumer()),
                error -> longError.set(error));

        if (streaming) {
            // Stream: events arrive incrementally — await terminal while send runs on a worker thread.
            Thread sendThread = Thread.ofVirtual().name(label + "-long").start(longSend);
            LongTravelMessageAssertions.assertLongMessageReachedTerminal(longCollector, scenario, label);
            sendThread.join(scenario.longMessageTimeoutMs());
        } else {
            // Sync message/send blocks until the server returns the terminal task; the collector
            // is populated only when the HTTP call completes. Wait for send first, then assert.
            Thread sendThread = Thread.ofVirtual().name(label + "-long").start(longSend);
            sendThread.join(scenario.longMessageTimeoutMs());
            LongTravelMessageAssertions.assertLongMessageReachedTerminal(
                    longCollector, scenario, label, 5_000);
        }
        assertStreamHealthy(longError, label + " long send");

        // ---- health probe ----
        A2aEventCollector probeCollector = new A2aEventCollector();
        AtomicReference<Throwable> probeError = new AtomicReference<>();

        Runnable probeSend = () -> a2a.sendMessage(
                A2A.toUserMessage(scenario.healthProbeText()),
                List.of(probeCollector.createConsumer()),
                error -> probeError.set(error));

        if (streaming) {
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
        LongTravelMessageAssertions.assertHealthProbeCompleted(probeTask, scenario, label);
    }

    private static void assertStreamHealthy(AtomicReference<Throwable> streamError, String label) {
        Throwable failure = streamError.get();
        if (failure != null && !A2aStreamErrors.isBenignShutdown(failure)) {
            fail(label + " transport failed", failure);
        }
    }
}

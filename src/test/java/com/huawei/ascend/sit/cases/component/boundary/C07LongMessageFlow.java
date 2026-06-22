package com.huawei.ascend.sit.cases.component.boundary;

import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.A2aStreamErrors;
import com.huawei.ascend.sit.model.component.boundary.C07ScenarioData;
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
final class C07LongMessageFlow {

    private static final Logger LOG = Logger.getLogger(C07LongMessageFlow.class.getName());

    private C07LongMessageFlow() {
    }

    static void run(A2aServiceClient a2a, C07ScenarioData scenario, String label, boolean streamSendOnBackground)
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

        // Long sync message/send can block for minutes; run on a background thread so the
        // collector can observe terminal events while the HTTP call is in flight.
        Thread sendThread = Thread.ofVirtual().name(label + "-long").start(longSend);
        C07LongMessageAssertions.assertLongMessageReachedTerminal(longCollector, scenario, label);
        sendThread.join(scenario.longMessageTimeoutMs());
        assertStreamHealthy(longError, label + " long send");

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
        C07LongMessageAssertions.assertHealthProbeCompleted(probeTask, scenario, label);
    }

    private static void assertStreamHealthy(AtomicReference<Throwable> streamError, String label) {
        Throwable failure = streamError.get();
        if (failure != null && !A2aStreamErrors.isBenignShutdown(failure)) {
            fail(label + " transport failed", failure);
        }
    }
}

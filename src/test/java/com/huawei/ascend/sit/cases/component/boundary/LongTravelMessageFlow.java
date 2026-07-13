package com.huawei.ascend.sit.cases.component.boundary;

import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.A2aStreamErrors;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * C-07 long message + health probe flow (shared by sync and stream tests).
 *
 * <p>Scenario constants live here (no main ScenarioData). Matches
 * {@code testdata/component/boundary/c07-long-travel-message.json}.</p>
 */
final class LongTravelMessageFlow {

    private static final Logger LOG = Logger.getLogger(LongTravelMessageFlow.class.getName());

    static final int MIN_INPUT_CHARS = 5000;
    static final String TRAVEL_TEMPLATE_PREFIX =
            "我要安排一次出差：从上海到北京，出发日期下周三，停留5天4晚。请根据以下详细要求给出规划建议。";
    static final String PADDING_SENTENCE =
            "此外还需要考虑市内交通、会议间隙用餐、发票与报销凭证、以及与同事拼车往返机场等细节。";
    static final String HEALTH_PROBE_TEXT = "你好";
    static final long LONG_MESSAGE_TIMEOUT_MS = 300_000L;
    static final long HEALTH_PROBE_TIMEOUT_MS = 60_000L;
    static final TaskState HEALTH_PROBE_EXPECTED_TERMINAL_STATE = TaskState.TASK_STATE_COMPLETED;

    private LongTravelMessageFlow() {
    }

    static void run(A2aServiceClient a2a, String label, boolean streaming)
            throws InterruptedException {
        String longInput = LongMessageInputBuilder.build(
                TRAVEL_TEMPLATE_PREFIX, PADDING_SENTENCE, MIN_INPUT_CHARS);
        assertThat(longInput.length())
                .as(label + " input length")
                .isGreaterThanOrEqualTo(MIN_INPUT_CHARS);
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
            LongTravelMessageAssertions.assertLongMessageReachedTerminal(longCollector, label);
            sendThread.join(LONG_MESSAGE_TIMEOUT_MS);
        } else {
            // Sync message/send blocks until the server returns the terminal task; the collector
            // is populated only when the HTTP call completes. Wait for send first, then assert.
            Thread sendThread = Thread.ofVirtual().name(label + "-long").start(longSend);
            sendThread.join(LONG_MESSAGE_TIMEOUT_MS);
            LongTravelMessageAssertions.assertLongMessageReachedTerminal(
                    longCollector, label, 5_000);
        }
        assertStreamHealthy(longError, label + " long send");

        // ---- health probe ----
        A2aEventCollector probeCollector = new A2aEventCollector();
        AtomicReference<Throwable> probeError = new AtomicReference<>();

        Runnable probeSend = () -> a2a.sendMessage(
                A2A.toUserMessage(HEALTH_PROBE_TEXT),
                List.of(probeCollector.createConsumer()),
                error -> probeError.set(error));

        if (streaming) {
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
        LongTravelMessageAssertions.assertHealthProbeCompleted(probeTask, label);
    }

    private static void assertStreamHealthy(AtomicReference<Throwable> streamError, String label) {
        Throwable failure = streamError.get();
        if (failure != null && !A2aStreamErrors.isBenignShutdown(failure)) {
            fail(label + " transport failed", failure);
        }
    }
}

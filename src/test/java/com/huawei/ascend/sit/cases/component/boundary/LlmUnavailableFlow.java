package com.huawei.ascend.sit.cases.component.boundary;

import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.A2aStreamErrors;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.spec.TaskState;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.fail;

/**
 * C-09 LLM-unavailable flow (shared by sync and stream tests).
 *
 * <p>Scenario constants live here (no main ScenarioData). Matches
 * {@code testdata/component/boundary/c09-llm-unavailable.json}.</p>
 */
final class LlmUnavailableFlow {

    static final String INPUT_TEXT = "你好";
    static final long LLM_FAILURE_TIMEOUT_MS = 120_000L;
    static final Set<TaskState> DISALLOWED_TERMINAL_STATES = Set.of(TaskState.TASK_STATE_COMPLETED);

    private LlmUnavailableFlow() {
    }

    static void run(A2aServiceClient a2a, String label, boolean streaming)
            throws InterruptedException {
        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> sendError = new AtomicReference<>();

        Runnable send = () -> a2a.sendMessage(
                A2A.toUserMessage(INPUT_TEXT),
                List.of(collector.createConsumer()),
                error -> sendError.set(error));

        if (streaming) {
            Thread sendThread = Thread.ofVirtual().name(label + "-send").start(send);
            LlmUnavailableAssertions.assertLlmUnavailableNonSuccessTerminal(collector, label);
            sendThread.join(LLM_FAILURE_TIMEOUT_MS);
        } else {
            Thread sendThread = Thread.ofVirtual().name(label + "-send").start(send);
            sendThread.join(LLM_FAILURE_TIMEOUT_MS);
            LlmUnavailableAssertions.assertLlmUnavailableNonSuccessTerminal(
                    collector, label, 5_000);
        }

        Throwable failure = sendError.get();
        if (failure != null && !A2aStreamErrors.isBenignShutdown(failure)) {
            fail(label + " transport failed", failure);
        }
    }
}

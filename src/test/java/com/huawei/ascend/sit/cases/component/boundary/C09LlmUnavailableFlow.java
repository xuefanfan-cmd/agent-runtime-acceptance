package com.huawei.ascend.sit.cases.component.boundary;

import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.A2aStreamErrors;
import com.huawei.ascend.sit.model.component.boundary.C09ScenarioData;
import org.a2aproject.sdk.A2A;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.fail;

/**
 * C-09 LLM-unavailable flow (shared by sync and stream tests).
 */
final class C09LlmUnavailableFlow {

    private C09LlmUnavailableFlow() {
    }

    static void run(A2aServiceClient a2a, C09ScenarioData scenario, String label) throws InterruptedException {
        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> sendError = new AtomicReference<>();

        Runnable send = () -> a2a.sendMessage(
                A2A.toUserMessage(scenario.inputText()),
                List.of(collector.createConsumer()),
                error -> sendError.set(error));

        Thread sendThread = Thread.ofVirtual().name(label + "-send").start(send);
        C09LlmUnavailableAssertions.assertLlmUnavailableNonSuccessTerminal(collector, scenario, label);
        sendThread.join(scenario.llmFailureTimeoutMs());

        Throwable failure = sendError.get();
        if (failure != null && !A2aStreamErrors.isBenignShutdown(failure)) {
            fail(label + " transport failed", failure);
        }
    }
}

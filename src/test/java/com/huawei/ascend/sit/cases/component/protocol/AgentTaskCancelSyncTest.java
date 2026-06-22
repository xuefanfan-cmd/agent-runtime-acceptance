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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.fail;

/**
 * A-06-Y — tasks/cancel on synchronous {@code message/send} (特性 4-5).
 *
 * <p>Blocking send runs on a background thread; the main thread issues cancel during the
 * in-flight window.</p>
 *
 * <p>See {@code docs/cases/A-06-task-cancel.md}.</p>
 */
@Tag("component")
@Tag("smoke")
@EnabledIf("com.huawei.ascend.sit.cases.component.protocol.A06LlmGate#isExecutable")
class AgentTaskCancelSyncTest extends BaseManagedStackTest {

    private static final Logger LOG = Logger.getLogger(AgentTaskCancelSyncTest.class.getName());

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return AgentTaskCancelStreamTest.mainplanStack(config, false);
    }

    @Test
    @DisplayName("A-06-Y: 同步 message/send 过程中 cancel → CANCELED")
    void a06_syncCancel_reachesCanceledState() throws InterruptedException {
        requireLlmKey();

        A06ScenarioData scenario = A06ScenarioData.loadDefault();
        A2aServiceClient a2a = client("mainplan");

        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> sendError = new AtomicReference<>();

        Thread sendThread = Thread.ofVirtual().name("a06-sync-send").start(() -> {
            try {
                a2a.sendMessage(
                        A2A.toUserMessage(scenario.inputText()),
                        List.of(collector.createConsumer()),
                        error -> sendError.set(error));
            } catch (Exception e) {
                sendError.set(e);
            }
        });

        CancelWindow.Result window = CancelWindow.await(
                collector, scenario.taskIdWaitMs(), scenario.cancelWaitMs());
        LOG.info("A-06.E cancel_at_state=" + window.cancelAtState());

        A06CancelVerifiers.assertCancelAndGet(
                a2a, window.taskId(), scenario.cancelPollMs(), scenario.expectedCanceledState());

        sendThread.join(scenario.streamTimeoutMs());
        Throwable sendFailure = sendError.get();
        if (sendFailure != null && !A2aStreamErrors.isBenignShutdown(sendFailure)) {
            fail("message/send failed after cancel", sendFailure);
        }
    }

    private static void requireLlmKey() {
        String apiKey = System.getenv(A06LlmGate.LLM_KEY_ENV);
        if (apiKey == null || apiKey.isBlank()) {
            fail("SIT_LLM_API_KEY must be set for A-06");
        }
    }
}

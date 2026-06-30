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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
 * <p>LLM credentials are not checked in this class — configure {@code LLM_*} (or equivalent)
 * before launch so the managed mainplan process can reach the model. See
 * {@code docs/cases/A-06-task-cancel.md}.</p>
 */
@Tag("component")
@Tag("smoke")
class AgentTaskCancelSyncTest extends BaseManagedStackTest {

    private static final Logger LOG = Logger.getLogger(AgentTaskCancelSyncTest.class.getName());

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return AgentTaskCancelStreamTest.mainplanStack(config, false);
    }

    @Test
    @DisplayName("A-06-Y: 同步 message/send 过程中 cancel → CANCELED")
    void a06_syncCancel_reachesCanceledState() throws InterruptedException {
        TaskCancelScenarioData scenario = TaskCancelScenarioData.loadDefault();
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

        TaskCancelVerifiers.assertCancelAndGet(
                a2a, window.taskId(), scenario.cancelPollMs(), scenario.expectedCanceledState());

        sendThread.join(scenario.streamTimeoutMs());
        Throwable sendFailure = sendError.get();
        if (sendFailure != null && !A2aStreamErrors.isBenignShutdown(sendFailure)) {
            fail("message/send failed after cancel", sendFailure);
        }
    }
}

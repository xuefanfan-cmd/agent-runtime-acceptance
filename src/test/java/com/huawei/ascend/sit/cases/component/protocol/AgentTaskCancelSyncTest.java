package com.huawei.ascend.sit.cases.component.protocol;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.A2aStreamErrors;
import com.huawei.ascend.sit.client.CancelWindow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.a2aproject.sdk.A2A;
import org.junit.jupiter.api.Disabled;
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
 * in-flight window. Shares constants with {@link AgentTaskCancelStreamTest}.</p>
 *
 * <p>See {@code docs/cases/reactagent/A-06-task-cancel.md}.</p>
 *
 * <p><b>当前状态：</b>{@code @Disabled} —— 框架暂不支持同步 {@code message/send} 路径下的 cancel 观测。</p>
 */
@Tag("component")
@Tag("smoke")
@Disabled("框架暂不支持 A-06-Y：同步 message/send 过程中 cancel 的 taskId 观测与 cancel 窗口。")
class AgentTaskCancelSyncTest extends BaseManagedStackTest {

    private static final Logger LOG = Logger.getLogger(AgentTaskCancelSyncTest.class.getName());

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return AgentTaskCancelStreamTest.mainplanStack(config, false);
    }

    @Test
    @DisplayName("A-06-Y: 同步 message/send 过程中 cancel → CANCELED")
    void a06_syncCancel_reachesCanceledState() throws InterruptedException {
        A2aServiceClient a2a = client("mainplan");

        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> sendError = new AtomicReference<>();

        Thread sendThread = Thread.ofVirtual().name("a06-sync-send").start(() -> {
            try {
                a2a.sendMessage(
                        A2A.toUserMessage(AgentTaskCancelStreamTest.INPUT_TEXT),
                        List.of(collector.createConsumer()),
                        error -> sendError.set(error));
            } catch (Exception e) {
                sendError.set(e);
            }
        });

        CancelWindow.Result window = CancelWindow.await(
                collector,
                AgentTaskCancelStreamTest.TASK_ID_WAIT_MS,
                AgentTaskCancelStreamTest.CANCEL_WAIT_MS);
        LOG.info("A-06.E cancel_at_state=" + window.cancelAtState());

        TaskCancelVerifiers.assertCancelAndGet(
                a2a,
                window.taskId(),
                AgentTaskCancelStreamTest.CANCEL_POLL_MS,
                AgentTaskCancelStreamTest.EXPECTED_CANCELED);

        sendThread.join(AgentTaskCancelStreamTest.STREAM_TIMEOUT_MS);
        Throwable sendFailure = sendError.get();
        if (sendFailure != null && !A2aStreamErrors.isBenignShutdown(sendFailure)) {
            fail("message/send failed after cancel", sendFailure);
        }
    }
}

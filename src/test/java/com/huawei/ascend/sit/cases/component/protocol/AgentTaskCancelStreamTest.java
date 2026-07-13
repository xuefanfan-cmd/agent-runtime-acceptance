package com.huawei.ascend.sit.cases.component.protocol;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.A2aStreamErrors;
import com.huawei.ascend.sit.client.CancelWindow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * A-06-S — tasks/cancel on streaming {@code message/stream} (特性 4-5).
 *
 * <p>Scenario constants live in this class (no main ScenarioData); shared with
 * {@link AgentTaskCancelSyncTest}. See {@code docs/cases/reactagent/A-06-task-cancel.md}.</p>
 */
@Tag("component")
@Tag("smoke")
class AgentTaskCancelStreamTest extends BaseManagedStackTest {

    private static final Logger LOG = Logger.getLogger(AgentTaskCancelStreamTest.class.getName());

    /** Matches {@code testdata/component/protocol/a06-cancel-long-prompt.json}. */
    static final String INPUT_TEXT =
            "请为我规划一条详细的出差方案：下周三从上海虹桥到北京，出差5天4晚。请分章节详细阐述交通方式对比（高铁与航班）、每日会议日程建议、酒店区域选择、每日餐饮与市内交通预算，以及注意事项。内容尽可能完整、篇幅尽量长。";
    static final long TASK_ID_WAIT_MS = 15_000L;
    static final long CANCEL_WAIT_MS = 30_000L;
    static final long CANCEL_POLL_MS = 10_000L;
    static final long STREAM_TIMEOUT_MS = 60_000L;
    static final TaskState EXPECTED_CANCELED = TaskState.TASK_STATE_CANCELED;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return mainplanStack(config, true);
    }

    @Test
    @DisplayName("A-06-S: 流式 message/stream 过程中 cancel → CANCELED")
    void a06_streamCancel_reachesCanceledState() throws InterruptedException {
        A2aServiceClient a2a = client("mainplan");

        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> streamError = new AtomicReference<>();

        Thread sendThread = Thread.ofVirtual().name("a06-stream-send").start(() ->
                a2a.sendMessage(
                        A2A.toUserMessage(INPUT_TEXT),
                        List.of(collector.createConsumer()),
                        error -> streamError.set(error)));

        CancelWindow.Result window = CancelWindow.await(collector, TASK_ID_WAIT_MS, CANCEL_WAIT_MS);
        LOG.info("A-06.E cancel_at_state=" + window.cancelAtState());

        TaskCancelVerifiers.assertCancelAndGet(a2a, window.taskId(), CANCEL_POLL_MS, EXPECTED_CANCELED);

        TaskState streamTerminal = collector.awaitTerminalState(STREAM_TIMEOUT_MS);
        assertThat(streamTerminal)
                .as("stream terminal state")
                .isEqualTo(EXPECTED_CANCELED);
        assertThat(streamTerminal)
                .as("stream must not complete successfully")
                .isNotEqualTo(TaskState.TASK_STATE_COMPLETED);

        sendThread.join(STREAM_TIMEOUT_MS);
        Throwable streamFailure = streamError.get();
        if (streamFailure != null && !A2aStreamErrors.isBenignShutdown(streamFailure)) {
            fail("message/stream failed after cancel", streamFailure);
        }
    }

    static SutStack.Builder mainplanStack(TestConfig config, boolean streaming) {
        return SutStack.builder(config)
                .streaming(streaming)
                .agent("mainplan");
    }
}

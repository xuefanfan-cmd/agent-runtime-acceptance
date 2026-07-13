package com.huawei.ascend.sit.cases.component.protocol;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.client.TaskTextExtractor;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-05 — tasks/get 查询已完成任务 (特性 4-4).
 *
 * <p>Sync {@code message/send} ({@code streaming(false)}) to {@code COMPLETED} via
 * {@link InteractionFlow}, then {@code getTask(taskId)} and assert id/state/text match.
 * Scenario constants live in this class (no main ScenarioData).</p>
 *
 * <p>See {@code docs/cases/reactagent/A-05-task-get-completed.md}.</p>
 */
@Tag("component")
@Tag("smoke")
class AgentTaskGetTest extends BaseManagedStackTest {

    private static final Logger LOG = Logger.getLogger(AgentTaskGetTest.class.getName());

    /** Matches {@code testdata/component/protocol/a05-get-completed-hello.json}. */
    private static final String INPUT_TEXT = "你好";
    private static final long SEND_TIMEOUT_MS = 60_000L;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .streaming(false)
                .agent("mainplan");
    }

    @Test
    @DisplayName("A-05: tasks/get 查询已完成任务 — send 快照与 get 一致")
    void a05_getTask_matchesSendSnapshotAfterCompleted() {
        A2aServiceClient a2a = client("mainplan");
        AtomicReference<String> sendTextRef = new AtomicReference<>();

        InteractionFlow.FlowResult flow = InteractionFlow.of(a2a)
                .withTimeoutMs(SEND_TIMEOUT_MS)
                .send(INPUT_TEXT)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertTask(task -> {
                        String sendText = TaskTextExtractor.textOf(task);
                        assertThat(sendText).as("send response text").isNotBlank();
                        sendTextRef.set(sendText);
                    })
                .execute();

        String taskId = flow.round(0).taskId();
        assertThat(taskId).as("send taskId").isNotBlank();
        LOG.info("A-05.E send_event_count=" + flow.round(0).eventCount());

        Task queried = a2a.getTask(taskId);
        assertThat(queried).as("getTask result").isNotNull();
        assertThat(queried.id()).as("get taskId").isEqualTo(taskId);
        assertThat(queried.status().state())
                .as("get task state")
                .isEqualTo(TaskState.TASK_STATE_COMPLETED);

        String getText = TaskTextExtractor.textOf(queried);
        assertThat(getText).as("get response text").isNotBlank();
        assertThat(getText).as("send vs get text").isEqualTo(sendTextRef.get());
    }
}

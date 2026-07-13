package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * DA-03 — deep-research 流式 SendStreamingMessage / SSE (场景 3).
 *
 * <p>参考 §3 curl：{@code method=SendStreamingMessage} 走 SSE，事件序列
 * SUBMITTED → WORKING → artifactUpdate → COMPLETED。栈走默认 streaming
 * （{@code streaming(true)}）由 SDK 建立 SSE 通道。
 *
 * <p>断言维度：
 * <ul>
 *   <li>终态 COMPLETED；</li>
 *   <li>状态轨迹中至少出现过 SUBMITTED 和 WORKING（时序不严格，但两者都要被观测到）；</li>
 *   <li>至少收到 1 个 artifactUpdate；</li>
 *   <li>合并后的 artifact 文本非空，且不包含已知 bug 标志串。</li>
 * </ul>
 *
 * <p><b>Bug 断言</b>：与 {@link SyncSendMessageTest} 相同——artifact 中若包含
 * "deep_agent_task_1 already exists" 或 "controller task parameter error"，判 FAIL。
 */
@Tag("integration")
@Tag("deepagent")
class StreamingSendMessageTest extends BaseManagedStackTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final long SEND_TIMEOUT_MS = 240_000;

    private static final String BUG_MARKER_TASK_EXISTS = "deep_agent_task_1 already exists";
    private static final String BUG_MARKER_CONTROLLER_ERR = "controller task parameter error";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // 默认 streaming(true) — 无需显式设置。
        return SutStack.builder(config).agent(DEEP_RESEARCH);
    }

    @Test
    @DisplayName("DA-03: 流式 SendMessage → SUBMITTED→WORKING→artifact→COMPLETED 且无 bug")
    void streamingSendMessageProducesFullEventSequence() {
        A2aServiceClient a2a = client(DEEP_RESEARCH);

        String runSuffix = "-" + UUID.randomUUID().toString().substring(0, 8);
        String contextId = "ctx-da03-stream" + runSuffix;
        String userInput = "写一首关于秋天的四句古体诗";

        Message message = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .contextId(contextId)
                .parts(List.of(new TextPart(userInput)))
                .build();

        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> streamError = new AtomicReference<>();
        List<BiConsumer<ClientEvent, AgentCard>> consumers = List.of(collector.createConsumer());
        Consumer<Throwable> errorHandler = streamError::set;

        a2a.sendMessage(message, consumers, errorHandler);

        TaskState terminalState;
        try {
            terminalState = collector.awaitTerminalState(SEND_TIMEOUT_MS);
        } catch (ConditionTimeoutException timeout) {
            Throwable err = streamError.get();
            if (err != null) {
                fail("DA-03: awaitTerminalState 超时且 stream 期间发生异常", err);
            }
            fail("DA-03: awaitTerminalState 纯超时（无 stream 异常）", timeout);
            return;
        }

        assertThat(terminalState).as("DA-03: 终态应为 COMPLETED")
                .isEqualTo(TaskState.TASK_STATE_COMPLETED);

        // 收集状态轨迹（TaskEvent + status-update TaskUpdateEvent 只会进 taskBearingEvents，
        // 无需再过滤 artifact 噪声）。
        List<TaskState> stateTrajectory = extractStateTrajectory(collector.snapshotAllEvents());
        assertThat(stateTrajectory)
                .as("DA-03: SSE 应观测到 SUBMITTED 状态\ntrajectory=%s", stateTrajectory)
                .contains(TaskState.TASK_STATE_SUBMITTED);
        assertThat(stateTrajectory)
                .as("DA-03: SSE 应观测到 WORKING 状态\ntrajectory=%s", stateTrajectory)
                .contains(TaskState.TASK_STATE_WORKING);
        assertThat(stateTrajectory)
                .as("DA-03: SSE 应观测到 COMPLETED 状态\ntrajectory=%s", stateTrajectory)
                .contains(TaskState.TASK_STATE_COMPLETED);

        // FEAT-001.task-lifecycle 扩展：严格顺序 —— SUBMITTED 必须先于 WORKING 先于 COMPLETED。
        int firstSubmitted = stateTrajectory.indexOf(TaskState.TASK_STATE_SUBMITTED);
        int firstWorking = stateTrajectory.indexOf(TaskState.TASK_STATE_WORKING);
        int firstCompleted = stateTrajectory.indexOf(TaskState.TASK_STATE_COMPLETED);
        assertThat(firstSubmitted)
                .as("FEAT-001.task-lifecycle: SUBMITTED 首现 index 应先于 WORKING 首现 index\ntrajectory=%s", stateTrajectory)
                .isLessThan(firstWorking);
        assertThat(firstWorking)
                .as("FEAT-001.task-lifecycle: WORKING 首现 index 应先于 COMPLETED 首现 index\ntrajectory=%s", stateTrajectory)
                .isLessThan(firstCompleted);

        // FEAT-001.task-lifecycle 扩展：无回退 —— 到达 COMPLETED 后不应再出现 SUBMITTED / WORKING。
        List<TaskState> afterCompleted = stateTrajectory.subList(firstCompleted, stateTrajectory.size());
        assertThat(afterCompleted)
                .as("FEAT-001.task-lifecycle: COMPLETED 之后不应回退到 SUBMITTED / WORKING\ntrajectory=%s", stateTrajectory)
                .doesNotContain(TaskState.TASK_STATE_SUBMITTED)
                .doesNotContain(TaskState.TASK_STATE_WORKING);

        // 至少有 1 个 artifact chunk
        assertThat(collector.findFirstArtifactUpdate())
                .as("DA-03: 至少收到 1 个 artifactUpdate 事件").isPresent();

        // artifact 文本非空 + bug 标志串缺席
        String merged = collector.collectArtifactText();
        assertThat(merged).as("DA-03: 合并后的 artifact 文本").isNotBlank();
        assertThat(merged)
                .as("DA-03: 合并 artifact 不应包含已知 bug 标志 '%s'\nartifact 头 300 字: %s",
                        BUG_MARKER_TASK_EXISTS, truncate(merged, 300))
                .doesNotContain(BUG_MARKER_TASK_EXISTS)
                .doesNotContain(BUG_MARKER_CONTROLLER_ERR);
    }

    /**
     * 从事件列表里提取 TaskState 序列。只看 TaskEvent + status-update TaskUpdateEvent；
     * artifact-update TaskUpdateEvent 的 task().status() 是当时快照，可能重复回显同一状态，
     * 为避免噪声不纳入轨迹。
     */
    private static List<TaskState> extractStateTrajectory(List<ClientEvent> events) {
        List<TaskState> trajectory = new ArrayList<>();
        for (ClientEvent e : events) {
            Task t = null;
            if (e instanceof TaskEvent te) {
                t = te.getTask();
            } else if (e instanceof TaskUpdateEvent ue
                    && ue.getUpdateEvent() instanceof TaskStatusUpdateEvent) {
                t = ue.getTask();
            }
            if (t != null && t.status() != null && t.status().state() != null) {
                trajectory.add(t.status().state());
            }
        }
        return trajectory;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.TaskTextExtractor;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * DA-05-1 — In-memory checkpointer 两轮同 contextId 记忆召回 (场景 5.1).
 *
 * <p>参考 §5.1 手工脚本：同一 contextId 内两轮对话——turn1 "我叫张三,请记住"，
 * turn2 "我叫什么名字?"。deep-research 的 checkpointer（默认 in-memory 模式）应让 turn2
 * 的 LLM 输出复述 "张三"。走同步 {@code streaming(false)} 简化断言。
 *
 * <p><b>observation</b>：turn2 的 artifact 里，用户查询本身 "我叫什么名字?" 不含 "张三"；
 * artifact 的 {@code inputs.messages} 只回显当轮消息、{@code query} 只回显当轮问句，
 * 均不含 "张三"。因此 turn2 artifact 中出现 "张三" 是"LLM 真的通过 checkpointer 拿到了
 * turn1 记忆"的干净信号——不是任何机械回显。
 *
 * <p><b>Bug 断言</b>：与 DA-02/03/04 相同——任一轮 artifact 含 bug 标志串即 FAIL。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-003")
@Feature("FEAT-003: 智能体任务状态缓存")
@Story("da.checkpointer-inmemory-recall: In-memory checkpointer 同 contextId 跨轮召回")
class InMemoryCheckpointerRecallTest extends BaseManagedStackTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final long ROUND_TIMEOUT_MS = 240_000;

    private static final String TURN1_INPUT = "我叫张三,请记住";
    private static final String TURN2_INPUT = "我叫什么名字?";
    private static final String RECALL_TOKEN = "张三";

    private static final String BUG_MARKER_TASK_EXISTS = "deep_agent_task_1 already exists";
    private static final String BUG_MARKER_CONTROLLER_ERR = "controller task parameter error";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .streaming(false)
                .agent(DEEP_RESEARCH);
    }

    @Test
    @DisplayName("DA-05-1: 同 contextId 两轮 — turn2 应从 in-memory checkpoint 召回 '张三'")
    void inMemoryCheckpointerRecallsTurn1IdentityIntoTurn2() {
        A2aServiceClient a2a = client(DEEP_RESEARCH);

        // 每次跑用独立 contextId，避免 SUT 内驻留的 checkpointer 与前次跑串扰。
        String runSuffix = "-" + UUID.randomUUID().toString().substring(0, 8);
        String contextId = "ctx-da05-1-inmem" + runSuffix;

        TurnOutcome turn1 = sendOneTurn(a2a, contextId, TURN1_INPUT);
        assertThat(turn1.state).as("DA-05-1: turn1 终态应为 COMPLETED")
                .isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertThat(turn1.artifactText)
                .as("DA-05-1: turn1 artifact 非空").isNotBlank();
        assertThat(turn1.artifactText)
                .as("DA-05-1: turn1 artifact 不应含 bug 标志\nhead: %s",
                        truncate(turn1.artifactText, 300))
                .doesNotContain(BUG_MARKER_TASK_EXISTS)
                .doesNotContain(BUG_MARKER_CONTROLLER_ERR);

        TurnOutcome turn2 = sendOneTurn(a2a, contextId, TURN2_INPUT);
        assertThat(turn2.state).as("DA-05-1: turn2 终态应为 COMPLETED")
                .isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertThat(turn2.artifactText)
                .as("DA-05-1: turn2 artifact 非空").isNotBlank();
        assertThat(turn2.artifactText)
                .as("DA-05-1: turn2 artifact 不应含 bug 标志\nhead: %s",
                        truncate(turn2.artifactText, 300))
                .doesNotContain(BUG_MARKER_TASK_EXISTS)
                .doesNotContain(BUG_MARKER_CONTROLLER_ERR);

        // 关键断言 —— turn2 通过 checkpointer 召回了 turn1 传入的姓名。
        assertThat(turn2.artifactText)
                .as("DA-05-1: turn2 artifact 应包含 turn1 存入的姓名 '%s'\n"
                        + "（in-memory checkpointer 未生效或 LLM 未复述）\nturn2 artifact 头 400 字: %s",
                        RECALL_TOKEN, truncate(turn2.artifactText, 400))
                .contains(RECALL_TOKEN);
    }

    private TurnOutcome sendOneTurn(A2aServiceClient a2a, String contextId, String userInput) {
        Message message = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .contextId(contextId)
                .parts(List.of(new TextPart(userInput)))
                .build();

        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> sendError = new AtomicReference<>();
        List<BiConsumer<ClientEvent, AgentCard>> consumers = List.of(collector.createConsumer());
        Consumer<Throwable> errorHandler = sendError::set;

        a2a.sendMessage(message, consumers, errorHandler);

        TaskState terminalState;
        try {
            terminalState = collector.awaitTerminalState(ROUND_TIMEOUT_MS);
        } catch (ConditionTimeoutException timeout) {
            Throwable err = sendError.get();
            if (err != null) {
                fail("DA-05-1: awaitTerminalState 超时且 send 期间发生异常 — contextId=" + contextId, err);
            }
            fail("DA-05-1: awaitTerminalState 纯超时（无 send 异常）— contextId=" + contextId, timeout);
            return null;
        }

        Task task = collector.findTerminalEvent()
                .flatMap(InMemoryCheckpointerRecallTest::taskFrom)
                .orElseThrow(() -> new AssertionError(
                        "DA-05-1: contextId=" + contextId + " 未产生终态 task 快照"));

        return new TurnOutcome(terminalState, TaskTextExtractor.textOf(task));
    }

    private static Optional<Task> taskFrom(ClientEvent event) {
        if (event instanceof TaskEvent te) {
            return Optional.of(te.getTask());
        }
        if (event instanceof TaskUpdateEvent ue) {
            return Optional.of(ue.getTask());
        }
        return Optional.empty();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private record TurnOutcome(TaskState state, String artifactText) {}
}
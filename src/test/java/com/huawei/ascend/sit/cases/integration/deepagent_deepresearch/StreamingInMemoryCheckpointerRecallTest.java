package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * DA-05-3 — In-memory checkpointer 跨轮召回（流式变体）(场景 5.3).
 *
 * <p>与 {@link InMemoryCheckpointerRecallTest} 同题：两轮同 contextId，turn1 存 "张三"、
 * turn2 问姓名，期望 turn2 artifact 复述 "张三"。区别在协议路径——本档走 SSE
 * {@code SendStreamingMessage}（默认 {@code streaming(true)}）而非同步 send。目的是让
 * checkpointer 契约同时覆盖 sync / stream 两条 A2A 路径，防止某一路径下 checkpoint 生命周期
 * 与 A2A 事件时序不同步时出现单侧回归。
 *
 * <p><b>召回断言</b>：turn2 的 SSE 合并 artifact ({@link A2aEventCollector#collectArtifactText()})
 * 应包含 "张三"。用户查询 "我叫什么名字?" 本身不含姓名，命中 = 通过 checkpointer 拿到 turn1 记忆。
 *
 * <p><b>Bug 断言</b>：任一轮合并 artifact 命中 {@code deep_agent_task_1 already exists}
 * / {@code controller task parameter error} 判 FAIL——与 DA-02/03/04/05-1 口径一致。
 */
@Tag("integration")
@Tag("deepagent")
class StreamingInMemoryCheckpointerRecallTest extends BaseManagedStackTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final long ROUND_TIMEOUT_MS = 240_000;

    private static final String TURN1_INPUT = "我叫张三,请记住";
    private static final String TURN2_INPUT = "我叫什么名字?";
    private static final String RECALL_TOKEN = "张三";

    private static final String BUG_MARKER_TASK_EXISTS = "deep_agent_task_1 already exists";
    private static final String BUG_MARKER_CONTROLLER_ERR = "controller task parameter error";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // 默认 streaming(true) —— SSE 路径，与 DA-05-1 的 streaming(false) 形成镜像。
        return SutStack.builder(config).agent(DEEP_RESEARCH);
    }

    @Test
    @DisplayName("DA-05-3: 流式同 contextId 两轮 — turn2 应从 in-memory checkpoint 召回 '张三'")
    void streamingInMemoryCheckpointerRecallsTurn1IdentityIntoTurn2() {
        A2aServiceClient a2a = client(DEEP_RESEARCH);

        String runSuffix = "-" + UUID.randomUUID().toString().substring(0, 8);
        String contextId = "ctx-da05-3-stream-inmem" + runSuffix;

        TurnOutcome turn1 = sendStreamingTurn(a2a, contextId, TURN1_INPUT);
        assertThat(turn1.state).as("DA-05-3: turn1 终态应为 COMPLETED")
                .isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertThat(turn1.artifactText)
                .as("DA-05-3: turn1 合并 artifact 非空").isNotBlank();
        assertThat(turn1.artifactText)
                .as("DA-05-3: turn1 artifact 不应含 bug 标志\nhead: %s",
                        truncate(turn1.artifactText, 300))
                .doesNotContain(BUG_MARKER_TASK_EXISTS)
                .doesNotContain(BUG_MARKER_CONTROLLER_ERR);

        TurnOutcome turn2 = sendStreamingTurn(a2a, contextId, TURN2_INPUT);
        assertThat(turn2.state).as("DA-05-3: turn2 终态应为 COMPLETED")
                .isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertThat(turn2.artifactText)
                .as("DA-05-3: turn2 合并 artifact 非空").isNotBlank();
        assertThat(turn2.artifactText)
                .as("DA-05-3: turn2 artifact 不应含 bug 标志\nhead: %s",
                        truncate(turn2.artifactText, 300))
                .doesNotContain(BUG_MARKER_TASK_EXISTS)
                .doesNotContain(BUG_MARKER_CONTROLLER_ERR);

        // 核心断言：SSE 路径下 checkpointer 应让 turn2 复述 turn1 存入的姓名。
        assertThat(turn2.artifactText)
                .as("DA-05-3: turn2 合并 artifact 应包含 turn1 存入的姓名 '%s'\n"
                        + "（streaming 路径下 in-memory checkpointer 未生效 / LLM 未复述）\n"
                        + "turn2 artifact 头 400 字: %s",
                        RECALL_TOKEN, truncate(turn2.artifactText, 400))
                .contains(RECALL_TOKEN);
    }

    private TurnOutcome sendStreamingTurn(A2aServiceClient a2a, String contextId, String userInput) {
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
            terminalState = collector.awaitTerminalState(ROUND_TIMEOUT_MS);
        } catch (ConditionTimeoutException timeout) {
            Throwable err = streamError.get();
            if (err != null) {
                fail("DA-05-3: awaitTerminalState 超时且 stream 期间发生异常 — contextId=" + contextId, err);
            }
            fail("DA-05-3: awaitTerminalState 纯超时（无 stream 异常）— contextId=" + contextId, timeout);
            return null;
        }

        return new TurnOutcome(terminalState, collector.collectArtifactText());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private record TurnOutcome(TaskState state, String artifactText) {}
}
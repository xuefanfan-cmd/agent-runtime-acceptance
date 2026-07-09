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
import org.junit.jupiter.api.Assumptions;
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
 * DA-06 — 长期记忆跨轮召回（流式）(场景 6).
 *
 * <p>参考 §6 手工脚本：两轮同 {@code contextId} 的 SendStreamingMessage。
 * turn1 询问 DeepSeek 官方 API 输入 token 定价（需搜索）；turn2 用同 contextId 问
 * "我上次问了什么？答案要点是什么？请直接复述，不要再搜索。"—— agent 应从记忆里
 * 复述 turn1 的主题（"DeepSeek" + "定价 / token / 价格"）。
 *
 * <p><b>Precondition (§6 注释)</b>：本场景当前存在已知 bug——测试前需要算子先手工重启
 * deep-research agent。因此本类 {@code @Tag("manual")} 让 CI 默认不跑；同时用
 * {@link Assumptions#assumeTrue} 做 agent 探活兜底：若 remote agent 不可达，用例被跳过
 * 而非 FAIL。若 bug 未被算子重启修复，turn1/turn2 会命中 {@code deep_agent_task_1 already exists}
 * 标志串，用例 FAIL（与 DA-02/03/04 口径一致）。
 *
 * <p><b>召回断言</b>：turn2 artifact 应同时包含专有名 "DeepSeek" 与至少一个话题词
 * （定价 / token / 价格），代表 agent 复述了 turn1 主题；纯回显 turn2 自身问句是判定不了的
 * ——问句里没有 "DeepSeek"。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("manual")
class LongTermMemoryRecallTest extends BaseManagedStackTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final long ROUND_TIMEOUT_MS = 300_000;

    private static final String TURN1_INPUT =
            "DeepSeek 官方 API 的输入 token 定价目前是多少？请给出官方页面链接。";
    private static final String TURN2_INPUT =
            "我上次问了你什么问题？你上次给我的答案的要点是什么？请直接复述，不要再搜索。";
    private static final String RECALL_SUBJECT_TOKEN = "DeepSeek";
    private static final List<String> RECALL_TOPIC_TOKENS = List.of("定价", "token", "价格");

    private static final String BUG_MARKER_TASK_EXISTS = "deep_agent_task_1 already exists";
    private static final String BUG_MARKER_CONTROLLER_ERR = "controller task parameter error";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // 默认 streaming(true) —— §6 手工脚本走 SendStreamingMessage。
        return SutStack.builder(config).agent(DEEP_RESEARCH);
    }

    @Test
    @DisplayName("DA-06: 同 contextId 两轮流式 — turn2 应复述 turn1 主题（DeepSeek + 定价 / token / 价格）")
    void longTermMemoryRecallsPreviousTurnTopic() {
        A2aServiceClient a2a = client(DEEP_RESEARCH);
        assumeAgentReachable(a2a);

        // 每次跑用独立 contextId，避免 SUT 记忆缓存串扰上次跑。
        String runSuffix = "-" + UUID.randomUUID().toString().substring(0, 8);
        String contextId = "ctx-da06-longmem" + runSuffix;

        // Turn 1 —— 提问，触发搜索 + agent search 下游。
        TurnOutcome turn1 = sendStreamingTurn(a2a, contextId, TURN1_INPUT);
        assertThat(turn1.state).as("DA-06 turn1 终态").isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertThat(turn1.artifactText)
                .as("DA-06 turn1 artifact 非空").isNotBlank();
        assertThat(turn1.artifactText)
                .as("DA-06 turn1 artifact 不应含 bug 标志（是否忘了按 §6 说明手工重启 agent？）\nhead: %s",
                        truncate(turn1.artifactText, 300))
                .doesNotContain(BUG_MARKER_TASK_EXISTS)
                .doesNotContain(BUG_MARKER_CONTROLLER_ERR);

        // Turn 2 —— 用同 contextId 让 agent 复述 turn1 主题。
        TurnOutcome turn2 = sendStreamingTurn(a2a, contextId, TURN2_INPUT);
        assertThat(turn2.state).as("DA-06 turn2 终态").isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertThat(turn2.artifactText)
                .as("DA-06 turn2 artifact 非空").isNotBlank();
        assertThat(turn2.artifactText)
                .as("DA-06 turn2 artifact 不应含 bug 标志\nhead: %s", truncate(turn2.artifactText, 300))
                .doesNotContain(BUG_MARKER_TASK_EXISTS)
                .doesNotContain(BUG_MARKER_CONTROLLER_ERR);

        // 关键断言：turn2 应同时命中 turn1 的专有名与至少一个话题词。
        assertThat(turn2.artifactText)
                .as("DA-06: turn2 artifact 应复述 turn1 主体 '%s'\nhead: %s",
                        RECALL_SUBJECT_TOKEN, truncate(turn2.artifactText, 400))
                .contains(RECALL_SUBJECT_TOKEN);

        boolean hitAnyTopic = RECALL_TOPIC_TOKENS.stream()
                .anyMatch(turn2.artifactText::contains);
        assertThat(hitAnyTopic)
                .as("DA-06: turn2 artifact 应命中至少一个话题词 %s\nhead: %s",
                        RECALL_TOPIC_TOKENS, truncate(turn2.artifactText, 400))
                .isTrue();
    }

    private void assumeAgentReachable(A2aServiceClient a2a) {
        try {
            Assumptions.assumeTrue(a2a.getAgentCard() != null,
                    "deep-research 不可达（getAgentCard() 返 null）— 场景 6 需要算子先手工重启 agent。");
        } catch (RuntimeException e) {
            Assumptions.abort(
                    "deep-research 不可达: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
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
                fail("DA-06: awaitTerminalState 超时且 stream 期间发生异常 — contextId=" + contextId, err);
            }
            fail("DA-06: awaitTerminalState 纯超时（无 stream 异常）— contextId=" + contextId, timeout);
            return null;
        }

        String artifactText = collector.collectArtifactText();
        return new TurnOutcome(terminalState, artifactText);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private record TurnOutcome(TaskState state, String artifactText) {}
}
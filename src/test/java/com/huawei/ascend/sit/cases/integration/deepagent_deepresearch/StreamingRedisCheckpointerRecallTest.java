package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * DA-05-4 — Redis checkpointer 跨进程记忆持久化（流式变体）(场景 5.4).
 *
 * <p>与 {@link RedisCheckpointerRecallTest} 同题、镜像 SSE 路径。整个工作流仍是手工两步——
 * Step1 存姓名 → 算子 kill + 用 {@code --spring.profiles.active=redis-checkpointer} 重启 →
 * Step2 验证召回；区别在协议路径：本档两步都走 SSE {@code SendStreamingMessage}
 * （默认 {@code streaming(true)}）而非同步 send，用于证明 Redis checkpoint 在流式路径下同样
 * 跨 JVM 存活。
 *
 * <p><b>共享 contextId</b>：由 {@code -Dda054.contextId=<共享id>} 传入；缺失时
 * {@link Assumptions#assumeTrue} 跳过用例。CI 默认不跑本类（{@code @Tag("manual")}）。
 *
 * <p><b>Bug 断言</b>：任一 Step 的合并 artifact 命中 {@code deep_agent_task_1 already exists}
 * / {@code controller task parameter error} 判 FAIL——与 DA-02/03/04/05-* 口径一致。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-003")
@Tag("manual")
@Feature("FEAT-003: 智能体任务状态缓存")
@Story("da.checkpointer-redis-recall-streaming: Redis checkpointer 流式路径跨进程持久化召回")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StreamingRedisCheckpointerRecallTest extends BaseManagedStackTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final long ROUND_TIMEOUT_MS = 300_000;
    private static final String CONTEXT_ID_PROP = "da054.contextId";

    private static final String TURN1_INPUT = "我叫薛凡凡，请记住";
    private static final String TURN2_INPUT = "我叫什么名字？";
    private static final String RECALL_TOKEN = "薛凡凡";

    private static final String BUG_MARKER_TASK_EXISTS = "deep_agent_task_1 already exists";
    private static final String BUG_MARKER_CONTROLLER_ERR = "controller task parameter error";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // 默认 streaming(true) —— SSE 路径，与 DA-05-2 的 streaming(false) 形成镜像。
        return SutStack.builder(config).agent(DEEP_RESEARCH);
    }

    @Test
    @Order(1)
    @DisplayName("DA-05-4 Step1: 流式存姓名到 Redis checkpointer（要求算子已用 redis-checkpointer profile 启动 deep-research）")
    void redisStreamingStep1Store() {
        String contextId = requireContextIdProperty();
        A2aServiceClient a2a = client(DEEP_RESEARCH);
        assumeAgentReachable(a2a);

        TurnOutcome step1 = sendStreamingTurn(a2a, contextId, TURN1_INPUT);
        assertThat(step1.state).as("Step1 终态").isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertThat(step1.artifactText)
                .as("Step1 合并 artifact 不应含 bug 标志\nhead: %s", truncate(step1.artifactText, 300))
                .doesNotContain(BUG_MARKER_TASK_EXISTS)
                .doesNotContain(BUG_MARKER_CONTROLLER_ERR);

        System.out.println("==== DA-05-4 Step1 完成 ====");
        System.out.println("contextId = " + contextId);
        System.out.println("下一步：kill deep-research → 用同一 redis-checkpointer profile 重启 → 再跑 redisStreamingStep2Recall（-D 相同 contextId）");
    }

    @Test
    @Order(2)
    @DisplayName("DA-05-4 Step2: 流式路径 — 重启后用同 contextId 应从 Redis 召回姓名")
    void redisStreamingStep2Recall() {
        String contextId = requireContextIdProperty();
        A2aServiceClient a2a = client(DEEP_RESEARCH);
        assumeAgentReachable(a2a);

        TurnOutcome step2 = sendStreamingTurn(a2a, contextId, TURN2_INPUT);
        assertThat(step2.state).as("Step2 终态").isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertThat(step2.artifactText)
                .as("Step2 合并 artifact 不应含 bug 标志\nhead: %s", truncate(step2.artifactText, 300))
                .doesNotContain(BUG_MARKER_TASK_EXISTS)
                .doesNotContain(BUG_MARKER_CONTROLLER_ERR);
        assertThat(step2.artifactText)
                .as("DA-05-4: SSE 路径下跨进程 Redis 召回应命中 '%s'\n"
                        + "（若失败说明 checkpoint 未跨 JVM 持久化，或算子未按流程重启 jar，"
                        + "或流式 A2A 事件时序破坏了 checkpoint 生命周期）\nartifact 头 400 字: %s",
                        RECALL_TOKEN, truncate(step2.artifactText, 400))
                .contains(RECALL_TOKEN);
    }

    private String requireContextIdProperty() {
        String contextId = System.getProperty(CONTEXT_ID_PROP, "").trim();
        Assumptions.assumeTrue(!contextId.isEmpty(),
                "DA-05-4 需要 -D" + CONTEXT_ID_PROP + "=<共享 contextId>（同一个 id 贯穿 Step1/Step2）;"
                        + " 缺失时用例被 Assumptions 跳过。");
        return contextId;
    }

    private void assumeAgentReachable(A2aServiceClient a2a) {
        try {
            Assumptions.assumeTrue(a2a.getAgentCard() != null,
                    "deep-research 不可达（getAgentCard() 返 null）— 请先启动 redis-checkpointer profile 的 jar。");
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
                fail("DA-05-4: awaitTerminalState 超时且 stream 期间发生异常 — contextId=" + contextId, err);
            }
            fail("DA-05-4: awaitTerminalState 纯超时（无 stream 异常）— contextId=" + contextId, timeout);
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
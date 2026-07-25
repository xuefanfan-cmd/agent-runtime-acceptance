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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * DA-05-2 — Redis checkpointer 跨进程记忆持久化 (场景 5.2).
 *
 * <p>参考 §5.2 手工脚本：需要 deep-research 以 {@code --spring.profiles.active=redis-checkpointer}
 * 启动、且中间要"杀进程 → 重启"。这两步框架无法为远端 agent 自动完成（{@link SutStack} 明示
 * remote agent 不支持 stop/start），因此本类走"人工分两次调用"模式：
 * <ol>
 *   <li>算子准备 Redis + 用 redis-checkpointer profile 启动 deep-research；</li>
 *   <li>{@code mvn test -Dtest.env=SIT -Dda052.contextId=ctx-... -Dtest=RedisCheckpointerRecallTest#redisStep1Store} 存入姓名；</li>
 *   <li>算子 {@code kill $(cat deep-research.pid)}，然后同 profile 重启 jar；</li>
 *   <li>{@code mvn test -Dtest.env=SIT -Dda052.contextId=ctx-... -Dtest=RedisCheckpointerRecallTest#redisStep2Recall} 验证召回。</li>
 * </ol>
 *
 * <p>两步共享同一 {@code contextId}（由 {@code -Dda052.contextId} 传入）；缺失该 -D 时
 * {@link Assumptions#assumeTrue} 直接跳过——保证 CI 全量跑到本类时不会误判失败。
 * {@code @Tag("manual")} 让本类默认不在 {@code -P integration} 里被扫上。
 *
 * <p><b>Bug 断言</b>：Step 2 artifact 中若命中 bug 标志串（{@code deep_agent_task_1 already exists}
 * / {@code controller task parameter error}）判 FAIL，与 DA-02/03/04/05-1 口径一致。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-003")
@Tag("manual")
@Feature("FEAT-003: 智能体任务状态缓存")
@Story("da.checkpointer-redis-recall: Redis checkpointer 跨进程持久化召回")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisCheckpointerRecallTest extends BaseManagedStackTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final long ROUND_TIMEOUT_MS = 240_000;
    private static final String CONTEXT_ID_PROP = "da052.contextId";

    private static final String TURN1_INPUT = "我叫薛凡凡，请记住";
    private static final String TURN2_INPUT = "我叫什么名字？";
    private static final String RECALL_TOKEN = "薛凡凡";

    private static final String BUG_MARKER_TASK_EXISTS = "deep_agent_task_1 already exists";
    private static final String BUG_MARKER_CONTROLLER_ERR = "controller task parameter error";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .streaming(false)
                .agent(DEEP_RESEARCH);
    }

    @Test
    @Order(1)
    @DisplayName("DA-05-2 Step1: 存姓名到 Redis checkpointer（要求算子已用 redis-checkpointer profile 启动 deep-research）")
    void redisStep1Store() {
        String contextId = requireContextIdProperty();
        A2aServiceClient a2a = client(DEEP_RESEARCH);

        // 探活兜底 —— agent 不可达时用 Assumptions 跳过，让本类在 SUT 未就绪时不误报 FAIL。
        assumeAgentReachable(a2a);

        TurnOutcome step1 = sendOneTurn(a2a, contextId, TURN1_INPUT);
        assertThat(step1.state).as("Step1 终态").isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertThat(step1.artifactText)
                .as("Step1 artifact 不应含 bug 标志\nhead: %s", truncate(step1.artifactText, 300))
                .doesNotContain(BUG_MARKER_TASK_EXISTS)
                .doesNotContain(BUG_MARKER_CONTROLLER_ERR);

        System.out.println("==== DA-05-2 Step1 完成 ====");
        System.out.println("contextId = " + contextId);
        System.out.println("下一步：kill deep-research → 用同一 redis-checkpointer profile 重启 → 再跑 redisStep2Recall（-D 相同 contextId）");
    }

    @Test
    @Order(2)
    @DisplayName("DA-05-2 Step2: 重启后用同 contextId 应从 Redis 召回姓名")
    void redisStep2Recall() {
        String contextId = requireContextIdProperty();
        A2aServiceClient a2a = client(DEEP_RESEARCH);
        assumeAgentReachable(a2a);

        TurnOutcome step2 = sendOneTurn(a2a, contextId, TURN2_INPUT);
        assertThat(step2.state).as("Step2 终态").isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertThat(step2.artifactText)
                .as("Step2 artifact 不应含 bug 标志\nhead: %s", truncate(step2.artifactText, 300))
                .doesNotContain(BUG_MARKER_TASK_EXISTS)
                .doesNotContain(BUG_MARKER_CONTROLLER_ERR);
        assertThat(step2.artifactText)
                .as("DA-05-2: 跨进程 Redis 召回应命中 '%s'\n"
                        + "（若失败说明 checkpoint 未跨 JVM 持久化，或算子未按流程重启 jar）\n"
                        + "artifact 头 400 字: %s",
                        RECALL_TOKEN, truncate(step2.artifactText, 400))
                .contains(RECALL_TOKEN);
    }

    private String requireContextIdProperty() {
        String contextId = System.getProperty(CONTEXT_ID_PROP, "").trim();
        Assumptions.assumeTrue(!contextId.isEmpty(),
                "DA-05-2 需要 -D" + CONTEXT_ID_PROP + "=<共享 contextId>（同一个 id 贯穿 Step1/Step2）;"
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
                fail("DA-05-2: awaitTerminalState 超时且 send 期间发生异常 — contextId=" + contextId, err);
            }
            fail("DA-05-2: awaitTerminalState 纯超时（无 send 异常）— contextId=" + contextId, timeout);
            return null;
        }

        Task task = collector.findTerminalEvent()
                .flatMap(RedisCheckpointerRecallTest::taskFrom)
                .orElseThrow(() -> new AssertionError(
                        "DA-05-2: contextId=" + contextId + " 未产生终态 task 快照"));

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
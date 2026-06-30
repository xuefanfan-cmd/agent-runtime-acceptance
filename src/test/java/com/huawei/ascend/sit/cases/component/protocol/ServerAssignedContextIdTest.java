package com.huawei.ascend.sit.cases.component.protocol;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-11-2 — 服务端自动分配 contextId (特性 4 / A2A 协议层 contextId 契约).
 *
 * <p>客户端串行两次 {@code message/send}：Turn 1 不带 {@code contextId}，Turn 2 显式带回
 * Turn 1 服务端分配的 {@code contextId}。断言：
 * <ul>
 *   <li><b>A-11-2.A</b>: Turn 1 不传 contextId 时服务端必须分配（{@code Task.contextId} 非空）；</li>
 *   <li><b>A-11-2.B</b>: Turn 2 带回 Turn 1 contextId 时服务端不得篡改（两轮 contextId 相等）；</li>
 *   <li><b>A-11-2.C</b>: 两轮 {@code task.id} 互异（任务 vs 会话分层）。</li>
 * </ul>
 *
 * <p>栈走 {@link BaseManagedStackTest} 形态——仅声明 mainplan 一节点（A-11-2 协议契约不需要
 * trip/hotel 链路），{@code .streaming(false)} 显式走同步 {@code message/send}。SUT 地址按
 * 当前 {@code test.env} 解析：{@code application-sit.yml} 配为 7.209.189.82:13003 指向预部署
 * mainplan（{@code -Dtest.env=SIT}）；{@code LOCAL} 时则从 ~/.m2 起本地进程。本用例不依赖
 * SUT 业务输出——只要任一终态（COMPLETED / INPUT_REQUIRED 等 {@link TaskState#isFinal()}=true）
 * 即可观测协议契约，所以无论 mainplan + LLM 是否走到追问分支，断言都成立。
 *
 * <p>本用例不走 {@link com.huawei.ascend.sit.client.InteractionFlow} —— DSL 把首轮自动留空、
 * 后续轮自动续 contextId 的逻辑封装进 {@code executeRound}，对本用例反而屏蔽了我们要观测的
 * 协议面（首轮**显式无** contextId、第二轮**显式有** contextId）。
 *
 * <p>详见 {@code docs/cases/A-11-2-server-assigned-context-id.md}。
 */
@Tag("component")
@Tag("smoke")
class ServerAssignedContextIdTest extends BaseManagedStackTest {

    // 文本内容与协议契约判定无关；用最短的非空字符串避免 LLM 走长链路。
    private static final String INPUT_TEXT = "hi";
    private static final long ROUND_TIMEOUT_MS = 60_000;
    private static final String MAINPLAN = "mainplan";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // 同步 message/send 走 streaming(false) — 与 doc/§3 描述一致；A-11-2 只验 contextId
        // 协议契约，不依赖 trip/hotel 全链，stack 单节点即可。
        return SutStack.builder(config)
                .streaming(false)
                .agent(MAINPLAN);
    }

    @Test
    @DisplayName("A-11-2: Turn 1 不带 contextId → 服务端分配；Turn 2 带回 → 服务端透传，taskId 互异")
    void serverAssignsContextIdAndPreservesItAcrossTurns() {
        A2aServiceClient a2a = client(MAINPLAN);

        // Turn 1 — explicitly omit contextId. The a2a-sdk server SimpleRequestContextBuilder
        // must allocate one and surface it as Task.contextId.
        Message turn1 = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .parts(List.of(new TextPart(INPUT_TEXT)))
                .build();
        TurnObservation t1 = sendOneTurn(a2a, turn1);

        assertNoStreamError(t1, "Turn 1");
        assertThat(t1.finalState())
                .as("Turn 1 应到达终态（非 SUBMITTED/WORKING），否则后续断言无观测面")
                .satisfies(state -> assertThat(state.isFinal()).isTrue());
        assertThat(t1.taskId()).as("Turn 1 taskId").isNotBlank();

        // ---- A-11-2.A ----
        assertThat(t1.contextId())
                .as("A-11-2.A: Turn 1 未传 contextId 时服务端必须分配（Task.contextId 非空）")
                .isNotBlank();

        // Turn 2 — carry Turn 1 contextId verbatim. Server must not mutate it.
        Message turn2 = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .contextId(t1.contextId())
                .parts(List.of(new TextPart(INPUT_TEXT)))
                .build();
        TurnObservation t2 = sendOneTurn(a2a, turn2);

        assertNoStreamError(t2, "Turn 2");
        assertThat(t2.finalState())
                .as("Turn 2 应到达终态")
                .satisfies(state -> assertThat(state.isFinal()).isTrue());
        assertThat(t2.taskId()).as("Turn 2 taskId").isNotBlank();

        // ---- A-11-2.B ----
        assertThat(t2.contextId())
                .as("A-11-2.B: Turn 2 带回 Turn 1 contextId 时服务端不得篡改")
                .isEqualTo(t1.contextId());

        // ---- A-11-2.C ----
        assertThat(t2.taskId())
                .as("A-11-2.C: 两轮 taskId 必须互异（每次 send 都是新 task）")
                .isNotEqualTo(t1.taskId());
    }

    // ---- helpers ----

    private TurnObservation sendOneTurn(A2aServiceClient a2a, Message message) {
        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> streamError = new AtomicReference<>();

        List<BiConsumer<ClientEvent, AgentCard>> consumers = List.of(collector.createConsumer());
        Consumer<Throwable> errorHandler = streamError::set;

        // metadata=null —— A-11-2 只关注 message.contextId 这一个观测面，metadata 不在范围。
        a2a.sendMessage(message, null, consumers, errorHandler);
        TaskState finalState = collector.awaitTerminalState(ROUND_TIMEOUT_MS);

        return new TurnObservation(
                collector.findFirstTaskId(),
                collector.findFirstContextId(),
                finalState,
                streamError.get());
    }

    private static void assertNoStreamError(TurnObservation obs, String label) {
        assertThat(obs.streamError())
                .as("%s 不应在流中产生异常", label)
                .isNull();
    }

    // ---- inner types ----

    private record TurnObservation(
            String taskId,
            String contextId,
            TaskState finalState,
            Throwable streamError) {}
}
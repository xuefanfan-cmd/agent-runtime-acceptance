package com.huawei.ascend.sit.cases.integration.travel_assistant;

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
import org.a2aproject.sdk.spec.TextPart;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-10: 流式中途客户端 TCP-RST 断连 → 续传同一 task/context 的补充输入可走到 COMPLETED。
 *
 * <p>两轮对话复用 {@code StreamingTravelPlanningTest} 的信息收集语义：第 1 轮发不完整请求
 * （{@link #INCOMPLETE_TURN_1}），第 2 轮发补齐输入（{@link #INCOMPLETE_TURN_2}），合起来走到
 * COMPLETED。区别于 A-08 的是：两轮之间注入了一次在途断链——第 1 轮流进入 WORKING 后
 * {@code stack.faultLink(MAINPLAN).resetPeer()} 切断在途 SSE；恢复链路后第 2 轮**继承第 1 轮的
 * taskId 与 contextId**续传同一任务，验证断连未使该任务卡死、对话可续并完成。
 *
 * <p>故障注入经框架级 {@code cardEndpointRedirect}：mainplan 对外发布的 agent-card endpoint 被重定向
 * 到一条 toxiproxy 故障链路，故 {@code stack.client(MAINPLAN)}（经 card 发现）自动经 toxiproxy 路由，
 * {@code resetPeer()} 即可在途断链。详见
 * {@code docs/superpowers/specs/2026-06-21-agent-card-endpoint-fault-redirect-design.md}。
 */
@Tag("e2e")
@Tag("chaos")
class StreamInterruptRecoveryTest extends BaseManagedStackTest {

    private static final String MAINPLAN = "mainplan";
    private static final String HOTEL = "hotel";
    private static final String TRIP = "trip";

    /** 两轮共用的会话 contextId（第 2 轮继承）。 */
    private static final String CONTEXT_ID = "c10-stream-ctx";

    /** 第 1 轮 — 信息不全（仅目的地 + 天数，缺出发日期 / 出发地）。 */
    private static final String INCOMPLETE_TURN_1 = "到北京出差3天";

    /** 第 2 轮 — 补齐出发日期 / 出发地 / 差标 / 偏好，使请求完整 → 应走到 COMPLETED。 */
    private static final String INCOMPLETE_TURN_2 =
            "明天从上海出发。差标：每晚不超过 800 元、最低 4 星、协议品牌 全季/亚朵/希尔顿欢朋。"
            + "偏好：国贸附近，需要会议室。";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // 全链路 mainplan → trip → hotel（端到端发送完整指令）；仅 mainplan 声明 cardEndpointRedirect，
        // 故只有 mainplan 的 agent-card endpoint 经 toxiproxy 重定向。框架按需自管 toxiproxy 容器。
        return SutStack.builder(config)
                .agent(HOTEL)
                .agent(TRIP, a -> a.downstream(HOTEL))
                .agent(MAINPLAN, a -> a.downstream(TRIP)
                        .cardEndpointRedirect("main-plan-agent.agent-card-endpoint"));
    }

    @Test
    @DisplayName("C-10: 第1轮流中段RST断连 → 第2轮续传(继承taskId+contextId)走到COMPLETED")
    void streamInterrupt_thenContinueReachesCompleted() throws Exception {
        // client(MAINPLAN) 经被重定向的 card 自动走 toxiproxy；faultLink 即那条链路。
        A2aServiceClient client = stack.client(MAINPLAN);

        // ---------------- 第 1 轮：发不完整请求，WORKING 后切断在途流 ----------------
        A2aEventCollector turn1 = new A2aEventCollector();
        CountDownLatch working = new CountDownLatch(1);
        AtomicReference<String> taskId = new AtomicReference<>();
        AtomicReference<Throwable> streamErr = new AtomicReference<>();

        Message turn1Msg = userMessage(CONTEXT_ID, INCOMPLETE_TURN_1);
        List<BiConsumer<ClientEvent, AgentCard>> turn1Consumers = List.of(
                turn1.createConsumer(),
                (e, card) -> {
                    if (isWorking(e)) {
                        working.countDown();
                    }
                    String id = taskIdOf(e);
                    if (id != null) {
                        taskId.compareAndSet(null, id);
                    }
                });
        client.sendMessage(turn1Msg, meta(), turn1Consumers, streamErr::set);

        // 等待流进入中段（首个 WORKING）—— SSE 持续下行，resetPeer 后下一块必触发 DOWNSTREAM RST
        assertThat(working.await(30, TimeUnit.SECONDS))
                .as("第 1 轮应进入 WORKING（流已中段），否则 mainplan 未进入处理态")
                .isTrue();
        String interruptedTask = taskId.get();
        assertThat(interruptedTask).as("应已捕获第 1 轮 taskId").isNotNull();
        // contextId 由客户端设置；优先取服务端在事件里回显的，回退到本地设置的值。
        String sharedContext = turn1.findFirstContextId();
        if (sharedContext == null || sharedContext.isBlank()) {
            sharedContext = CONTEXT_ID;
        }

        // 切断在途流（双向 TCP RST）
        stack.faultLink(MAINPLAN).resetPeer();

        // 客户端侧：在途流应在有限时间内报错（证明断链生效，非空操作）
        Awaitility.await("resetPeer 后在途流必须报错")
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> streamErr.get() != null);

        // 恢复链路（reset_peer 持续影响新连接，续传前必须先 restore）
        stack.faultLink(MAINPLAN).restore();

        // ---------------- 第 2 轮：续传同一 task + context 的补齐输入 → COMPLETED ----------------
        // 继承第 1 轮的 taskId 与 contextId：message 同时携带二者，由服务端在原任务上继续
        // （而非另起一个不相关的新任务）。这是"恢复被中断的任务"而非"开新会话"的关键。
        Message turn2Msg = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .contextId(sharedContext)
                .taskId(interruptedTask)
                .parts(List.of(new TextPart(INCOMPLETE_TURN_2)))
                .build();

        AtomicReference<TaskState> finalState = new AtomicReference<>();
        AtomicReference<Throwable> turn2Err = new AtomicReference<>();
        A2aEventCollector turn2 = new A2aEventCollector();
        client.sendMessage(turn2Msg, meta(),
                List.of(turn2.createConsumer(),
                        (e, card) -> {
                            TaskState s = stateOf(e);
                            if (s != null) {
                                finalState.set(s);
                            }
                        }),
                turn2Err::set);

        // 续传应到达终态；断言其恰为 COMPLETED —— 若 mainplan 无法续传被断连的任务（卡 WORKING / FAILED），
        // 此处会超时或断言失败，正是本用例要暴露的待修行为。
        Awaitility.await("第 2 轮续传到达终态")
                .atMost(120, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> {
                    TaskState s = finalState.get();
                    return s != null && s.isFinal();
                });
        assertThat(finalState.get())
                .as("第 2 轮续传（继承 taskId=%s, contextId=%s）应到达 COMPLETED，实际 %s",
                        interruptedTask, sharedContext, finalState.get())
                .isEqualTo(TaskState.TASK_STATE_COMPLETED);
    }

    // ---- helpers ----

    private static Message userMessage(String contextId, String text) {
        return Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .contextId(contextId)
                .parts(List.of(new TextPart(text)))
                .build();
    }

    private static Map<String, Object> meta() {
        return Map.of("userId", "c10-user", "agentId", "main-plan-agent");
    }

    private static Task taskOf(ClientEvent e) {
        if (e instanceof TaskEvent te) {
            return te.getTask();
        }
        if (e instanceof TaskUpdateEvent tue) {
            return tue.getTask();
        }
        return null;
    }

    private static TaskState stateOf(ClientEvent e) {
        Task t = taskOf(e);
        return t == null || t.status() == null ? null : t.status().state();
    }

    private static boolean isWorking(ClientEvent e) {
        return stateOf(e) == TaskState.TASK_STATE_WORKING;
    }

    private static String taskIdOf(ClientEvent e) {
        Task t = taskOf(e);
        return t == null ? null : t.id();
    }
}

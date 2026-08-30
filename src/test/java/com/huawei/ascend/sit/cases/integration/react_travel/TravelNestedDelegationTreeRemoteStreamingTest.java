package com.huawei.ascend.sit.cases.integration.react_travel;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.config.TestEnvironment;
import com.huawei.ascend.sit.conversation.ConversationInteractionAdapter;
import com.huawei.ascend.sit.conversation.RemoteInvocationProbe;
import com.huawei.ascend.sit.conversation.SseEvent;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.transport.InboundEvent;
import com.huawei.ascend.sit.transport.MessageProtocol;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-027 {@code ra.nested-delegation-passthrough} —— travel 二跳链 {@code mainplan → trip → hotel}
 * <b>全流式</b>（A→B→C 两条调用边均注入 streaming）时 delegation 生成/透传与深层标签不失真
 * （openjiuwen 限定）：
 * <ul>
 *   <li><b>一跳</b>（mainplan→trip）delegation 的 source=根任务，target=trip 任务；</li>
 *   <li><b>二跳</b>（trip→hotel）delegation 由 trip 的 runtime 生成（§5.1 delegation 由调用方
 *       Runtime 生成、source=父），hotel 必须挂 <b>trip 节点下</b>（source.taskId = trip 的 taskId），不挂根；
 *       树深 3（root→trip→hotel），三层节点 taskId 互异；</li>
 *   <li><b>深层标签不失真</b>：hotel 的 output 事件经 trip、mainplan 两跳转发到客户端流后，生产者
 *       标签仍是 hotel 自身的 {@code agentId+taskId}（§5.2/§5.4 透传义务）。</li>
 * </ul>
 *
 * <p><b>语义模型（边模式口径）</b>：agentEvent 树/标签只在<b>全部调用边流式</b>时对客户端可见——
 * 本类注入两跳流式验证正向面（run-20260817-115508 已实证 866 agentEvent、嵌套树完整可见）；
 * 非流式边对该边子树上游不透明：全 sync 默认栈反向见证 =
 * {@link StreamingTravelPlanningTest#defaultRemoteEdgesProjectNoAgentEvents}；混合拓扑
 * （A—stream—B—sync—C）判别 = {@code MixedTopologySyncEdgeOpacityTest}（均启用-预期红，
 * 缺陷档 {@code docs/a2a-sync-call-agent-event-projection-defect.cn.md}：SUT 投影门禁
 * {@code shouldProjectEvents} 错挂 serve 模式而非调用边模式）。
 *
 * <p><b>提取链</b>：走 {@link InteractionFlow}（与 {@code StreamingTravelPlanningTest} 同驱动面）——
 * 终态判定、事件流出口（{@code RoundResult.events()} 的 {@link InboundEvent#raw()} 即 SDK 帧）与
 * <b>整轮 wire 日志</b>（FileWireLogger r 文件，含 {@code raw:} 帧、失败轮也记）一步到位；再以与
 * adapter 同源的公开投影 {@link ConversationInteractionAdapter#agentEventOf(Object)} 转 {@link SseEvent}
 * 交 {@link RemoteInvocationProbe} 建树/分流 —— 线格式只有一处权威。良性关流（SDK 收 final 后
 * 自动 cancel SSE）由 InteractionFlow 的 await 路径内部吸收，无需用例处理。
 *
 * <p><b>注入键名必须是 {@code streaming}</b> 而非 {@code is-streaming}：Spring Boot JavaBean 绑定从
 * 访问器 {@code isStreaming()}/{@code setStreaming()} 派生属性名 "streaming"，{@code is-streaming}
 * 被静默忽略（2026-08-17 已用 jar 内类 + Spring Binder 实测两种键名验证）。注入点：mainplan 的
 * {@code remote-agents[0]} = travel-trip、trip 的 = travel-hotel（各上游单下游，均落下标 0）；
 * hotel 无远程调用，不需注入。
 */
@Tag("integration")
@Feature("FEAT-027: 标准流式响应数据协议")
class TravelNestedDelegationTreeRemoteStreamingTest extends BaseManagedStackTest {

    /** 远程代理条目级流式开关（agent-runtime-java A2AProperties.RemoteAgentProperties.isStreaming，缺省 false）。 */
    private static final String REMOTE_IS_STREAMING = "openjiuwen.service.a2a.remote-agents[0].streaming";

    /** 与 {@link StreamingTravelPlanningTest#COMPLETE_REQUEST} 同文：完整指定、一次成行（固定 LLM 必走 trip→hotel 派发）。 */
    static final String COMPLETE_REQUEST =
            "明天从上海到北京出差3天，住宿2晚。差标：每晚不超过 800 元、最低 4 星、协议品牌 全季/亚朵/希尔顿欢朋。偏好：国贸附近，需要会议室。";

    /** travel 链单轮成行 + 两跳派发的宽时限（真机实测分钟级）。 */
    static final long SEND_TIMEOUT_MS = 600_000L;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // 第一行 gate：非 openjiuwen 在基类 .start() 之前 abort，不拉容器。
        Assumptions.assumeTrue(TestEnvironment.current() == TestEnvironment.OPENJIUWEN,
                "openjiuwen profile only — re-run with -Dtest.env=openjiuwen (需 LLM_API_KEY 等)");
        // leaf-first 声明；差异仅在 .property 注入远程调用流式（--key=value 命令行参数，随栈销毁——
        // 不污染共享 application-openjiuwen.yml，同一套 yml 服务流式/默认两面）。
        return SutStack.builder(config)
                .agent("hotel")
                .agent("trip", a -> a.downstream("hotel").property(REMOTE_IS_STREAMING, "true"))
                .agent("mainplan", a -> a.downstream("trip").property(REMOTE_IS_STREAMING, "true"));
    }

    @Test
    @DisplayName("Feat-027 全流式二跳 delegation 挂载父节点（trip 下）且深层 hotel 标签不失真")
    @Tag("story-feat-027-nested-delegation-passthrough")
    @Story("ra.nested-delegation-passthrough: 二跳 delegation 生成/透传与深层标签不失真")
    void feat027NestedDelegationAttachesToParentNode() {
        InteractionFlow.FlowResult flow = InteractionFlow.of(client("mainplan"))
                .protocol(MessageProtocol.A2A_STREAM)
                .withMetadata(Map.of("userId", "manual-user", "agentId", "main-plan-agent"))
                .withTimeoutMs(SEND_TIMEOUT_MS)
                .send(COMPLETE_REQUEST)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                .execute();

        assertThat(flow.roundCount()).as("完整请求单轮成行").isEqualTo(1);
        InteractionFlow.RoundResult round = flow.round(0);
        assertThat(round.taskState()).as("链路真实走通（COMPLETED）")
                .isEqualTo(TaskState.TASK_STATE_COMPLETED);

        // InboundEvent.raw()（SDK TaskUpdateEvent/MessageEvent）→ agentEvent 投影 → probe（线格式唯一权威）。
        List<SseEvent> sse = new ArrayList<>();
        for (InboundEvent e : round.events()) {
            Map<String, Object> agentEvent = ConversationInteractionAdapter.agentEventOf(e.raw());
            if (agentEvent != null) {
                sse.add(new SseEvent("content", Map.of("agentEvent", agentEvent)));
            }
        }
        assertThat(sse).as("流式注入面载体前置：客户端流出现 agentEvent（缺失即注入失效或投影门禁未开）")
                .isNotEmpty();

        String rootTaskId = round.taskId();
        List<RemoteInvocationProbe.Delegation> tree = RemoteInvocationProbe.delegations(sse);
        assertThat(tree).as("链上至少出现 delegation 事件（一跳 mainplan→trip）").isNotEmpty();

        // 一跳：source=根任务（mainplan 委托）。
        Set<String> hop1Targets = tree.stream()
                .filter(d -> rootTaskId.equals(d.source().taskId()))
                .map(d -> d.target().taskId()).collect(Collectors.toSet());
        assertThat(hop1Targets).as("一跳 delegation（source=根任务）存在").isNotEmpty();

        // 二跳：source 是一跳 target（trip 委托 hotel）→ hotel 挂 trip 下，不挂根。
        List<RemoteInvocationProbe.Delegation> hop2 = tree.stream()
                .filter(d -> hop1Targets.contains(d.source().taskId())).toList();
        assertThat(hop2).as("二跳 delegation（trip→hotel，source=trip 任务）存在 —— 全流式注入下缺失"
                + "即透传/投影链独立缺陷（排除配置默认值因素），不降 oracle").isNotEmpty();
        for (RemoteInvocationProbe.Delegation d : hop2) {
            assertThat(d.source().taskId()).as("二跳挂父节点（trip）下，不挂根").isNotEqualTo(rootTaskId);
        }

        // 树深 3：三层节点 taskId 互异（root / trip / hotel）。
        String hotelTaskId = hop2.get(0).target().taskId();
        assertThat(hotelTaskId).as("hotel 节点不同于根与 trip（树深 3）")
                .isNotIn(rootTaskId, hop1Targets.toArray(new String[0]));

        // 深层标签不失真：hotel 的 output 事件经两跳转发后仍携带 hotel 自身生产者标签。
        List<RemoteInvocationProbe.AgentRef> producers = RemoteInvocationProbe.outputProducers(sse);
        assertThat(producers).extracting(RemoteInvocationProbe.AgentRef::taskId)
                .as("hotel 输出的生产者标签 = hotel 自身 taskId（两跳透传不失真）")
                .contains(hotelTaskId);

        // 诊断（不作断言）：整树与生产者全景，供首跑校准比对。
        System.out.println("[feat-027-nested] rootTaskId=" + rootTaskId
                + " tree=" + tree.stream().map(d -> d.source().taskId() + "->" + d.target().taskId())
                        .collect(Collectors.toList())
                + " producers=" + producers.stream().map(RemoteInvocationProbe.AgentRef::taskId)
                        .collect(Collectors.toList()));
    }
}

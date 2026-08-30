package com.huawei.ascend.sit.cases.integration.workflow_call;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.config.TestEnvironment;
import com.huawei.ascend.sit.conversation.Conversation;
import com.huawei.ascend.sit.conversation.ConversationIdentity;
import com.huawei.ascend.sit.conversation.ConversationInteractionAdapter;
import com.huawei.ascend.sit.conversation.DriveMode;
import com.huawei.ascend.sit.conversation.ParallelTurnResult;
import com.huawei.ascend.sit.conversation.RemoteInvocationProbe;
import com.huawei.ascend.sit.conversation.SseEvent;
import com.huawei.ascend.sit.conversation.Step;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.transport.MessageProtocol;
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.huawei.ascend.sit.cases.integration.workflow_call.BalanceTransferFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并行转账（{@code parallel-transfer} profile）的<b>独立</b>验收用例（openjiuwen 限定）：仅 A2A_STREAM，
 * 仅 edpa-adapter + edpa-plan-agent，不继承 {@link AbstractBalanceThenTransfersTest}（其 runFlow 锁死且单 cid 线性）。
 *
 * <p>{@code parallel-transfer} Spring profile 切换 plan-agent 系统提示为并行分解：余额串行查完后，李四/王五两笔转账
 * 在<b>同一轮批量派发</b>，runtime 扇出 2 个并发子会话（共享 parentContextId）。本用例用
 * {@link DriveMode#parallelStepUi(Map)} + {@link com.huawei.ascend.sit.conversation.Turn#runParallel()}
 * 驱动这两个子会话。FEAT-027（v0815）刷新后的线上已不再携带 {@code _remote_invocation.{batchId,toolCallId}} 投影，
 * 扇出判定与归属全部改走新契约（提取核心 {@link RemoteInvocationProbe}）：
 * <ul>
 *   <li><b>扇出判定</b> —— 扇出轮终态 statusUpdate 的 {@code status.message.metadata._interrupt.items[]} 列出 ≥2 个
 *       待输入成员的 toolCallId（"Multiple remote agents require input"）；单成员 wait 是串行远端步，驱动器继续串行；</li>
 *   <li><b>调用树</b> —— delegation 事件（{@code agentEvent.type=delegation}，携带 source/target 的 agentId+taskId）
 *       按 target.taskId 去重构成调用树，本场景 = 余额查询 1 + 转账扇出 2；</li>
 *   <li><b>子会话发现</b> —— 运行时把子 mid cid 推导为 {@code parentCid_<batchId>_<toolCallId>} 且不再上线，
 *       驱动器经中台 {@code GET /admin/conversations} 枚举后按 {@code parentCid_} 前缀 + {@code _<toolCallId>}
 *       后缀精确配对（余额腿单成员批的 cid 就是裸 parentCid，被前缀天然排除）；</li>
 *   <li><b>续传路由</b> —— 请求侧契约未变：每轮一次多 part POST，每个 part 的 {@code metadata.toolCallId}
 *       路由到对应子成员（body.conversation_id 保持 parentCid）；</li>
 *   <li><b>交织分流</b> —— 批量回复是单条交织 SSE 流，事件按生产者标签 {@code agentEvent.source.{agentId,taskId}}
 *       归属到调用树节点（FEAT-027 §5.2：无标签事件属调用树根，收集不丢弃）。</li>
 * </ul>
 *
 * <p><b>硬断言</b>：核心语义不泄露 + ≥2 个子会话被驱动 + 未被 maxInteractions 熔断 + 命中转账完成态标记其一 +
 * FEAT-027 契约（扇出轮 delegation 树 ≥2 个不同 target.taskId；交织回复分流出 ≥2 个带 output 事件的生产者）。
 * 选择按 step_id 键控（非位置序），故两腿<b>非对称</b>也能各自拿到正确 kv。
 * 确切人工步序已在真机钉死：完整序 {@code on_payee_input→on_paycard_input→on_confirm_remit}，其中一腿（收款人预解析）跳过 {@code on_payee_input}；运行时的 {@code [parallel-transfer][child …] labels} 行会逐腿复核该序列。
 *
 * @see PlanAgentDirectStreamingTest 串行直连变体（继承 AbstractBalanceThenTransfersTest）
 */
@Tag("integration")
@Feature("FEAT-019: 智能体生成并行的下游智能体调用委托")
@Feature("FEAT-004: 任务驱动远程智能体调用")
@Feature("FEAT-027: 多跳智能体调用的流式数据解析")
@Stories({
        @Story("ra.parallel-transfer: 同轮批量中断聚合与 toolCallId 定向续接回灌"),
        @Story("wf.parallel-transfer: 并行转账批量派发与并发驱动")
})
class PlanAgentParallelTransferStreamingTest extends BaseManagedStackTest {

    /**
     * 每腿转账的人工步选择，按 step_id 键控（非位置序）。两腿非对称时（一腿多一个 on_payee_input 收款人步），
     * 每条腿按自己当前的 step_id 取 kv，不会被位置序错配。值取自串行转账用例的权威步序
     * （{@link AbstractBalanceThenTransfersTest} / {@link PlanAgentReactiveQueryTest} 等，三处一致）。
     */
    private static final Map<String, Map<String, String>> SELECTIONS_BY_STEP = Map.of(
            "on_payee_input", Map.of("recSerialNum", "SN20240001"),
            "on_paycard_input", Map.of("accIndex", "0"),
            "on_confirm_remit", Map.of("_text", "确定"));

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // 第一行 gate：非 openjiuwen 在基类 .start() 之前 abort，不拉容器。
        Assumptions.assumeTrue(TestEnvironment.current() == TestEnvironment.OPENJIUWEN,
                "openjiuwen profile only — re-run with -Dtest.env=openjiuwen (需 LLM_API_KEY 等)");
        // 精简栈：不起 edpa-gateway。envexplorer 由 edpa-adapter 的 service-bindings 自动拉起。
        // parallel-transfer profile 切换 plan-agent 提示为并行分解（余额串行 + 转账同轮批量派发）。
        return SutStack.builder(config)
                .agent("edpa-adapter")
                .agent(PLAN_AGENT, a -> a.profile("parallel-transfer").downstream("edpa-adapter"));
    }

    @Test
    @DisplayName("FEAT-027 调用树由 delegation 事件增量构建并按 target.taskId 去重；交织输出按生产者标签分流且不串腿")
    @Tag("story-FEAT-027-tree-incremental-build")
    @Tag("story-FEAT-027-stream-interleaved-demux")
    @Story("FEAT-027.tree.incremental-build: 调用树增量构建与事件区分")
    @Story("FEAT-027.stream.interleaved-demux: 并发交织流式输出分流")
    void parallelTransfersA2aStream() {
        try (Conversation conv = Conversation
                .at(stack.baseUrl(PLAN_AGENT), stack.serviceUrl("envexplorer"))
                .identity(ConversationIdentity.loadDefault())
                .transport(new ConversationInteractionAdapter(
                        MessageProtocol.A2A_STREAM, client(PLAN_AGENT), ROUND_TIMEOUT_MS))
                .timeout(Duration.ofSeconds(600))
                .open()) {

            ParallelTurnResult r = conv.turn(SENTENCE)
                    .intent("")
                    .driveMode(DriveMode.parallelStepUi(SELECTIONS_BY_STEP))
                    .runParallel();

            String blob = concat(r.allEvents());
            List<String> hit = TRANSFER_DONE.stream().filter(blob::contains).toList();

            // Diagnostic (folded in from the Phase 0 capture probe): per-child step-label sequence —
            // the quickest way to see which leg hit which manual step when a future regression changes
            // the parallel-transfer sequence. Printed BEFORE the assertions so a hard-assert failure
            // still leaves the per-leg picture in stdout for triage.
            System.out.println("[parallel-transfer][children=" + r.childCount() + "]");
            for (ParallelTurnResult.ChildResult c : r.children()) {
                List<String> labels = new ArrayList<>();
                for (Step s : c.steps()) {
                    labels.add(s.stepLabel() != null ? s.stepLabel() : "(auto)");
                }
                System.out.println("[parallel-transfer][child " + c.toolCallId()
                        + "] steps=" + c.steps().size() + " capped=" + c.capped() + " labels=" + labels);
            }
            System.out.println("[parallel-transfer][completion markers hit] " + hit);

            // 并发驱动硬断言：核心语义不泄露 + ≥2 子会话 + 未被 maxInteractions 熔断 + 命中转账完成态标记其一。
            assertCoreSemantics(blob);
            assertThat(r.childCount()).as("≥2 个并行子会话被驱动").isGreaterThanOrEqualTo(2);
            assertThat(r.capped()).as("未被 maxInteractions 熔断").isFalse();
            assertThat(hit).as("转账完成态标记命中其一（候选: " + TRANSFER_DONE + "）").isNotEmpty();

            // FEAT-027 调用树：扇出轮（最后一个串行步）的 delegation 事件按 target.taskId 去重后 ≥2
            // —— 证明树确从 delegation 事件构建，而非旧的 _remote_invocation 投影（已随 v0815 下线）。
            Step fanOut = r.serialSteps().get(r.serialSteps().size() - 1);
            List<RemoteInvocationProbe.Delegation> roundTree = RemoteInvocationProbe.delegations(fanOut.events());
            assertThat(roundTree).as("FEAT-027 调用树: 扇出轮 ≥2 个不同 target.taskId 的 delegation")
                    .hasSizeGreaterThanOrEqualTo(2);
            assertThat(roundTree).as("delegation target.taskId 去重（distinct 子任务）")
                    .extracting(d -> d.target().taskId()).doesNotHaveDuplicates();

            // FEAT-027 流式输出分流：批量续传的交织回复按生产者标签（agentEvent.source）分流出
            // ≥2 个各自携带 output 事件的生产者 —— 证明回复确为多源交织，而非单子流复读或根输出。
            List<SseEvent> parallelEvents = r.parallelEvents();
            List<RemoteInvocationProbe.AgentRef> producers =
                    RemoteInvocationProbe.outputProducers(parallelEvents);
            assertThat(producers).as("交织回复分流出 ≥2 个 output 生产者（agentEvent.source）")
                    .hasSizeGreaterThanOrEqualTo(2);

            // ==================== FEAT-027.tree.incremental-build ====================
            // 跨轮增量 + 重发去重 + 根归属 + 无孤儿标签（§5.1/§5.2/§5.5）。
            Step kickOff = r.serialSteps().get(0);
            List<RemoteInvocationProbe.Delegation> kickTree =
                    RemoteInvocationProbe.delegations(kickOff.events());
            assertThat(kickTree).as("kickoff 轮含余额腿的 delegation（串行远端步建首条树边）")
                    .isNotEmpty();

            List<RemoteInvocationProbe.Delegation> liveTree =
                    RemoteInvocationProbe.delegations(r.allEvents());
            assertThat(liveTree).as("整轮调用树 ≥3 子（余额 + 两笔转账扇出）")
                    .hasSizeGreaterThanOrEqualTo(3);
            assertThat(liveTree).extracting(d -> d.target().taskId())
                    .as("delegation 按 target.taskId 去重（distinct 子任务）").doesNotHaveDuplicates();

            // 重发去重：已完成成员（余额）的 delegation 在扇出轮被运行时重发（携带累积结果），
            // union 树仍只计一个节点 —— 首见为准（真机 run-20260816-112040 r3 已实证该重发）。
            Set<String> fanOutTargets = roundTree.stream()
                    .map(d -> d.target().taskId()).collect(Collectors.toSet());
            for (RemoteInvocationProbe.Delegation d : kickTree) {
                assertThat(fanOutTargets)
                        .as("已完成成员 %s 的 delegation 在扇出轮重发（重发观测）", d.target().taskId())
                        .contains(d.target().taskId());
                assertThat(liveTree.stream()
                        .filter(x -> x.target().taskId().equals(d.target().taskId())).count())
                        .as("重发的 delegation 按 target.taskId 去重，union 只计一个节点").isEqualTo(1);
            }

            // 增量性：逐轮前缀树的 target 集合单调包含（只增不改不覆盖），末轮并入并行回复后仍单调。
            List<Set<String>> prefixes = new ArrayList<>();
            Set<String> acc = new LinkedHashSet<>();
            for (Step s : r.serialSteps()) {
                RemoteInvocationProbe.delegations(s.events())
                        .forEach(d -> acc.add(d.target().taskId()));
                prefixes.add(new LinkedHashSet<>(acc));
            }
            for (int i = 1; i < prefixes.size(); i++) {
                assertThat(prefixes.get(i)).as("树(前 %d 轮) ⊇ 树(前 %d 轮)，只增不减", i + 1, i)
                        .containsAll(prefixes.get(i - 1));
            }
            Set<String> finalTree = liveTree.stream()
                    .map(d -> d.target().taskId()).collect(Collectors.toSet());
            assertThat(finalTree).as("并行续传阶段只增新节点，不回撤串行阶段已见节点")
                    .containsAll(prefixes.get(prefixes.size() - 1));

            // 根归属：所有 delegation 的 source 是同一个根任务（发起委托的 plan-agent 任务）——
            // "全同根"（单值），不是互异；树边全挂同一根下。
            assertThat(liveTree.stream().map(d -> d.source().taskId()).collect(Collectors.toSet()))
                    .as("delegation source 单一根任务（委托方=根）").hasSize(1);
            String rootTaskId = liveTree.get(0).source().taskId();

            // 无孤儿标签（§5.2 归属完备）：交织流里每个带标签事件的 producer 必是调用树已知节点
            // （根自身或某 delegation target）——不存在归属悬空的输出。
            Set<String> knownNodes = liveTree.stream()
                    .map(d -> d.target().taskId()).collect(Collectors.toSet());
            knownNodes.add(rootTaskId);
            Map<String, List<SseEvent>> demux = RemoteInvocationProbe.streamsByProducer(parallelEvents);
            for (String producer : demux.keySet()) {
                assertThat(producer == null || knownNodes.contains(producer))
                        .as("生产者 %s 是调用树已知节点（根或子），无悬空归属", producer).isTrue();
            }

            // ==================== FEAT-027.stream.interleaved-demux ====================
            // 防串腿：两腿各自的收款人语义只出现在自己的生产者流里（李四/王五 不共流）。
            Set<String> liProducers = producersContaining(demux, "李四");
            Set<String> wangProducers = producersContaining(demux, "王五");
            assertThat(liProducers).as("李四腿语义出现在某生产者流中").isNotEmpty();
            assertThat(wangProducers).as("王五腿语义出现在某生产者流中").isNotEmpty();
            assertThat(liProducers.stream().noneMatch(wangProducers::contains))
                    .as("两腿收款人语义落不同生产者节点（分流不串腿）").isTrue();

            // per-producer 保序：每个生产者桶是其到达序的子序列（按事件恒等）——分流只归类，不重排不丢。
            for (Map.Entry<String, List<SseEvent>> bucket : demux.entrySet()) {
                assertThat(isSubsequenceByIdentity(bucket.getValue(), parallelEvents))
                        .as("生产者 %s 的分流保持到达顺序", bucket.getKey()).isTrue();
            }
        }
    }

    /** 生产者 → 其流中至少一个事件文本含 marker 的生产者集合（腿语义归属）。 */
    private static Set<String> producersContaining(Map<String, List<SseEvent>> demux, String marker) {
        return demux.entrySet().stream()
                .filter(en -> en.getValue().stream().anyMatch(e -> e.data() != null
                        && String.valueOf(e.data().get("text")).contains(marker)))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /** part 是否为 whole 的子序列（按事件恒等 —— 分流桶持有的是同一批实例，恒等即精确）。 */
    private static boolean isSubsequenceByIdentity(List<SseEvent> part, List<SseEvent> whole) {
        int i = 0;
        for (SseEvent e : whole) {
            if (i < part.size() && part.get(i) == e) {
                i++;
            }
        }
        return i == part.size();
    }
}

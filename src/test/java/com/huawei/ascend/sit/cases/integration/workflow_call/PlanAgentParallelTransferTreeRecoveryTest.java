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
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.transport.MessageProtocol;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.Task;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.huawei.ascend.sit.cases.integration.workflow_call.BalanceTransferFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-027 §5.4 断点重连后调用树恢复（openjiuwen 限定）：客户端流式构建的调用树（live，delegation
 * 事件按 target.taskId 去重）必须能从 {@code GetTask} 快照（{@link Task} 的
 * {@code artifacts[].metadata.agentEvent} + {@code history[].metadata.agentEvent}，§5.7：A2A Task
 * schema 无独立"调用树状态"字段）**等值重建** —— 重连客户端不依赖任何流内私有状态即可恢复同一拓扑。
 *
 * <p><b>Phase 1（本类，稳态等值）</b>：完整驱动 parallel-transfer 流程到终态后丢弃内存流态
 * （= §4 场景 3 的本质：部分 delegation 事件已丢失），仅凭 rootTaskId 走 {@code GetTask} 重建，
 * 断言重建树与 live 树的 source/target 四元组集合等值。恢复后续建合并（D_new 不覆盖已有节点）由
 * {@link PlanAgentParallelTransferStreamingTest} 的增量/去重断言覆盖（同一运行时重发语义）。
 * <b>Phase 2（后续，真 FaultLink 断流）</b>：用 {@code sit.fault.FaultLink} 在扇出轮
 * INPUT_REQUIRED 驻留点注入 SSE 中断后再取快照 —— 见单 TC 设计文档的风险与备注。
 *
 * <p><b>TC4 双路径等值（长期 env-gated，已实证无承载者）</b>：FEAT-027 §5.4 要求 {@code EndpointType}
 * （GATEWAY/RUNTIME）两路径的快照语义一致 —— oracle 是"同一任务从两个接入门查询，delegation 记录
 * 集合等值"（防恢复结果随接入路径漂移）。当前栈里这个第二路径<b>不存在</b>：edpa-gateway 是纯格式
 * 转换实体（非 A2A 服务，无 agent card，2026-08-16 真机 404 实证）；plan-agent 的 cust-rest
 * （REST_GATEWAY 线，{@code v1/{project}/agents/{agent}/conversations/{cid}}）是纯 send 面（POST+SSE
 * 同流回复），无任务快照查询 API；SDK 唯一的快照查询就是 A2A {@code tasks/get}。故本类<b>不起</b>
 * edpa-gateway（省一个容器），TC4 直接按门禁 abort 并留此依据 —— 待 SUT 交付第二路径查询承载者
 * （真 A2A 网关的 tasks/get 转发 / cust-rest 查询 API / 产品 SDK EndpointType=GATEWAY）后复活。
 *
 * <p>驱动一次、两个 @Test 断言各自侧面：基类 PER_CLASS，@BeforeAll 完整跑一遍 parallel-transfer
 * （分钟级），两个用例共享同一 live 树 —— 避免双份 LLM 驱动成本。
 *
 * @see PlanAgentParallelTransferStreamingTest 驱动机制与 FEAT-027 线契约总述
 */
@Tag("integration")
@Feature("FEAT-027: 多跳智能体调用的流式数据解析")
class PlanAgentParallelTransferTreeRecoveryTest extends BaseManagedStackTest {

    private static final Map<String, Map<String, String>> SELECTIONS_BY_STEP = Map.of(
            "on_payee_input", Map.of("recSerialNum", "SN20240001"),
            "on_paycard_input", Map.of("accIndex", "0"),
            "on_confirm_remit", Map.of("_text", "确定"));

    /** live 树（流内构建）与根任务 id，@BeforeAll 驱动一次后共享。 */
    private List<RemoteInvocationProbe.Delegation> liveTree;
    private String rootTaskId;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // 第一行 gate：非 openjiuwen 在基类 .start() 之前 abort，不拉容器。
        Assumptions.assumeTrue(TestEnvironment.current() == TestEnvironment.OPENJIUWEN,
                "openjiuwen profile only — re-run with -Dtest.env=openjiuwen (需 LLM_API_KEY 等)");
        // 与并行验收用例同栈（不起 edpa-gateway）：它是纯格式转换实体，对 GetTask 双路径比对无贡献
        //（TC4 的第二路径承载者未交付，见类 javadoc）。
        return SutStack.builder(config)
                .agent("edpa-adapter")
                .agent(PLAN_AGENT, a -> a.profile("parallel-transfer").downstream("edpa-adapter"));
    }

    /** 完整驱动一遍 parallel-transfer 到终态，钉住 live 调用树与根任务 id（分钟级，只跑一次）。 */
    @BeforeAll
    void driveParallelTransfersOnce() {
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

            liveTree = RemoteInvocationProbe.delegations(r.allEvents());
            // 所有 delegation 的 source 是同一个根任务（委托方=根）——注意是"全同根"（单值），
            // 不是"互异"；真机 run-20260816-112040 已实证。3 条 delegation = 余额 + 两笔转账扇出。
            assertThat(liveTree).as("live 树 ≥3 子（余额 + 两笔转账扇出）")
                    .hasSizeGreaterThanOrEqualTo(3);
            assertThat(liveTree.stream().map(d -> d.source().taskId()).collect(Collectors.toSet()))
                    .as("delegation source 单一根任务（委托方=根）").hasSize(1);
            rootTaskId = liveTree.get(0).source().taskId();
            System.out.println("[FEAT-027-recovery] rootTaskId=" + rootTaskId
                    + " liveTargets=" + liveTree.stream()
                            .map(d -> d.target().taskId()).collect(Collectors.toList()));
        }
    }

    /**
     * TC3：丢弃内存流态后，仅凭 rootTaskId 从 {@code GetTask} 快照重建的调用树与 live 树等值
     * （source/target 四元组集合一致）——快照内 delegation 记录足以挂载建树（§5.4），重连客户端
     * 无需任何流内私有状态。
     */
    @Test
    @DisplayName("FEAT-027 断流后经 GetTask 快照重建调用树（与流内构建等值）")
    @Tag("story-FEAT-027-recovery-gettask-rebuild")
    @Story("FEAT-027.recovery.gettask-rebuild: 断点重连后调用树恢复")
    void feat026CallTreeRebuildsFromGetTaskSnapshot() {
        Task snapshot = client(PLAN_AGENT).getTask(rootTaskId);
        assertThat(snapshot).as("GetTask(rootTaskId) 返回快照").isNotNull();
        assertThat(snapshot.id()).as("快照 id 即根任务").isEqualTo(rootTaskId);

        List<RemoteInvocationProbe.Delegation> rebuilt = RemoteInvocationProbe.delegationsOfTask(snapshot);
        assertThat(rebuilt).as("快照内 delegation 记录非空（§5.4 事实要求）").isNotEmpty();

        // 等值：四元组集合双向一致 —— D_snap 覆盖 D_live 且无幻影节点（拓扑一致）。
        Set<String> liveTuples = tuplesOf(liveTree);
        Set<String> rebuiltTuples = tuplesOf(rebuilt);
        assertThat(rebuiltTuples).as("快照重建覆盖 live 树全部 delegation 四元组")
                .containsAll(liveTuples);
        assertThat(liveTuples).as("快照无 live 树之外的幻影 delegation 节点")
                .containsAll(rebuiltTuples);

        // 挂载语义：重建树同样全部挂同一根下（source.taskId 一致）。
        assertThat(rebuilt).extracting(d -> d.source().taskId())
                .as("重建树 delegation source 同一根").containsOnly(rootTaskId);

        // 观测项（不作断言，Phase 2 校准输入）：快照 history 是否保留 _interrupt 待输入成员清单。
        System.out.println("[FEAT-027-recovery] snapshot rebuilt=" + rebuilt.size()
                + " tuples, artifacts=" + (snapshot.artifacts() == null ? 0 : snapshot.artifacts().size())
                + " history=" + (snapshot.history() == null ? 0 : snapshot.history().size()));
    }

    /**
     * TC4（长期 env-gated，无承载者）：同一 rootTaskId 经第二接入门与直连（RUNTIME）两路径
     * {@code GetTask}，快照的 delegation 记录集合一致（差异只允许在传输信封）。当前栈无第二查询
     * 路径可调（edpa-gateway 非 A2A 服务、cust-rest 纯 send 无查询 API）——直接按门禁 abort 留依据，
     * 不拉 edpa-gateway、不发误导性的 404。承载者交付后恢复为真实比对（保留本方法骨架）。
     */
    @Test
    @DisplayName("FEAT-027 网关与直连路径的调用树恢复快照语义一致（env-gated：第二查询路径承载者未交付）")
    @Tag("story-FEAT-027-recovery-gateway-equivalence")
    @Disabled("non A2A contract")
    @Story("FEAT-027.recovery.gateway-equivalence: 恢复查询双路径等值")
    void feat026GatewayPathSnapshotMatchesDirect() {
        // 直连侧快照随时可得（TC3 已证）；第二路径无可调用对象：
        //  - edpa-gateway：纯格式转换实体，非 A2A 服务（无 agent card，2026-08-16 真机 404 实证）；
        //  - plan-agent cust-rest（REST_GATEWAY 线）：纯 send 面（POST+SSE 同流），无快照查询 API；
        //  - SDK 唯一快照查询 = A2A tasks/get（即"直连路径"本身）。
        Assumptions.abort("FEAT-027.recovery.gateway-equivalence 长期门禁：GATEWAY 模式 GetTask 第二查询"
                + "路径无承载者（edpa-gateway 非 A2A 服务无 agent card；cust-rest 纯 send 无查询 API）。"
                + "待 SUT 交付承载者（A2A 网关 tasks/get 转发 / cust-rest 查询 API / SDK EndpointType"
                + "=GATEWAY）后恢复比对。直连路径等值已由 TC3 覆盖。");
    }

    /** delegation 四元组集合（source.agentId|source.taskId|target.agentId|target.taskId）。 */
    private static Set<String> tuplesOf(List<RemoteInvocationProbe.Delegation> tree) {
        return tree.stream().map(d -> d.source().agentId() + "|" + d.source().taskId()
                + "|" + d.target().agentId() + "|" + d.target().taskId()).collect(Collectors.toSet());
    }
}

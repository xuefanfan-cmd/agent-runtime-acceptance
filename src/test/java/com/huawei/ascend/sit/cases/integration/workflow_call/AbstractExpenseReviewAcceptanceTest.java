package com.huawei.ascend.sit.cases.integration.workflow_call;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.transport.InboundEvent;
import com.huawei.ascend.sit.transport.MessageProtocol;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 费用报销审核 Workflow Agent —— 端到端验收模板基类（人工审批中断/恢复 + 条件路由自动通过）。
 *
 * <p>驱动 {@code com.huawei.ascend:expense-review-workflow:0.2.0-SNAPSHOT}（单 jar 双 profile）：
 * <ul>
 *   <li>{@code expense-review-workflow}（默认 profile）—— 8 节点 DAG：Start→LLM(analyze)→Tool(check_policy)→
 *       LLM(audit)→Branch(route)→[risk=high: Questioner(approve)] 或 [risk=none: LLM(auto_approve)]→End。</li>
 *   <li>{@code expense-review-main}（{@code main} profile）—— 主控 ReActAgent，由 LLM 决策把报销请求作为
 *       远程 A2A 工具 {@code review_expense} 调用 workflow。</li>
 * </ul>
 *
 * <p><b>seam</b>：{@link #buildStack}（栈/中间件，abstract，继承自 {@link BaseManagedStackTest}）—— 叶子决定
 * in-memory 或 redis 中间件。流程、断言、协议参数化（4 种线协议）全部固定在基类（{@code final} 模板方法），叶子不改。
 *
 * <p><b>两场景</b>（对齐 README 场景 1/2），均经 {@code expense-review-main} 驱动（完整拓扑 main→workflow 远程 A2A）：
 * <ul>
 *   <li><b>场景 1 — 超标报销 → 人工审批（Path A）</b>：住宿 800/晚 &gt; 600、客户晚餐 800 &gt; 300 ⇒ {@code risk=high}
 *       ⇒ Questioner ⇒ 流式 {@code SUBMITTED → WORKING → INPUT_REQUIRED}（审批提示）；续接 {@code "approved"}
 *       （{@link InteractionFlow} 续轮自动携 taskId+contextId 续传原任务）⇒ {@code … → COMPLETED}。</li>
 *   <li><b>场景 2 — 合规报销 → 自动通过（Path B）</b>：全部条目在限额内 ⇒ {@code risk=none} ⇒ auto_approve ⇒
 *       流式 {@code SUBMITTED → WORKING → COMPLETED}（无需人工审批）。</li>
 * </ul>
 *
 * <p><b>协议参数化（4 种线协议）。</b>两场景各以 {@code @ParameterizedTest} 覆盖 {@link MessageProtocol} 全部四值
 * （A2A / REST 各一对流式·sync），与 {@code StreamingTravelPlanningTest} 同型——这是一面调试矩阵：哪个协议单元变红，
 * 就指向哪个 transport adapter 还没接好。每个调用以 {@code <scenario>-<protocol>} 为 {@code sessionId}，故八次调用不撞。
 *
 * <p><b>分协议断言（两层）：</b>{@code .awaitState(...)} 对四协议都严格（{@code InboundExchange} 已归一化终态/中断态），
 * 与 transport 无关；流式状态轨迹（{@code SUBMITTED → WORKING → terminal}）仅断言于 {@code A2A_STREAM}——sync 与 REST
 * 只呈现终态（已由 {@code .awaitState(...)} 覆盖），故 {@link #assertStreamTrajectory} 对其余三协议为 no-op。
 *
 * <p><b>调试矩阵：</b>场景 2（单轮 {@code COMPLETED}，终态可达）四协议应全绿；场景 1 是多轮 {@code INPUT_REQUIRED}，
 * 该中断态仅在 {@code A2A_STREAM} 下可靠呈现——sync/REST 下可能失败或超时，正是"这些 transport 尚未呈现
 * INPUT_REQUIRED"的信号（REST 侧即网关 INPUT_REQUIRED 透传仍未标定，属独立问题）。
 *
 * <p><b>断言形式。</b>该 SUT 把结果发在自定义 {@code workflow_final} 类型（非标准 {@code answer}）下，故各轮文本断言用
 * {@code .assertGenerated(...)}（读 {@code generatedText()} 超集，覆盖 {@code llm_output}/{@code content}/{@code answer}）；
 * {@code workflow_final.payload.output} 经共享分类器 {@code LlmPayload} 认作 {@code answerText()}（{@code TYPE_WORKFLOW_FINAL}→
 * {@code ANSWER}，两协议同构）。语义上 {@code workflow_final} 即结果。
 *
 * @see ExpenseReviewAcceptanceTest in-memory 变体
 * @see ExpenseReviewRedisAcceptanceTest redis 中间件变体
 * @see com.huawei.ascend.sit.cases.integration.react_travel.StreamingTravelPlanningTest
 */
@Tag("integration")
abstract class AbstractExpenseReviewAcceptanceTest extends BaseManagedStackTest {

    /** 驱动入口：主控 ReActAgent（完整拓扑 main→workflow）。需兜底时一行切 "expense-review-workflow"。 */
    protected static final String ENTRY_AGENT = "expense-review-main";
    /** 内嵌 8 节点 DAG 的 workflow agent，被 main 作为远程 A2A 工具 review_expense 调用。 */
    protected static final String WORKFLOW_AGENT = "expense-review-workflow";

    /** 场景 1 —— 超标报销：住宿 800/晚 &gt; 600、客户晚餐 800 &gt; 300 ⇒ risk=high ⇒ 人工审批。 */
    private static final String OVER_LIMIT_EXPENSE =
            "帮我审核这笔报销：机票5000，酒店3晚每晚800共2400，客户晚餐800";

    /** 场景 1 续轮 —— 经理审批通过，续传原任务恢复 workflow。 */
    private static final String APPROVE = "approved";

    /** 场景 2 —— 合规报销：机票 3000≤5000、住宿 500≤600、餐 200≤300 ⇒ risk=none ⇒ 自动通过。 */
    private static final String COMPLIANT_EXPENSE =
            "审核这笔报销：机票3000，酒店2晚每晚500共1000，餐费200";

    // buildStack(TestConfig) 不在此实现 —— 继承自 BaseManagedStackTest 仍为 abstract，叶子类 override 它切换中间件。

    // ---- 场景 1：超标报销 → 人工审批 → 续接恢复（Path A）----

    /**
     * 超标报销触发 {@code INPUT_REQUIRED}（审批提示），续接 {@code "approved"} 恢复至 {@code COMPLETED}。
     * 参数化覆盖全部四种线协议。
     *
     * <p>轮 1 流式 {@code SUBMITTED → WORKING → INPUT_REQUIRED}（审批提示，非空）；
     * 轮 2（续轮，携原 taskId+contextId）以 {@code WORKING → COMPLETED} 收尾（容忍续轮是否重发 {@code SUBMITTED}），
     * 审核结果非空。多轮 {@code INPUT_REQUIRED} 仅在 {@code A2A_STREAM} 下可靠呈现（见类注释调试矩阵）；状态轨迹
     * 仅在 {@code A2A_STREAM} 下断言，其余三协议 {@link #assertStreamTrajectory} 为 no-op（终态由 {@code .awaitState(...)} 覆盖）。
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @EnumSource(value = MessageProtocol.class, mode = EnumSource.Mode.INCLUDE,
            names = {"A2A_STREAM", "A2A_SYNC", "REST_QUERY", "REST_QUERY_SYNC"})
    @DisplayName("场景1: 超标报销 → INPUT_REQUIRED → 续接 approved → COMPLETED（Path A）")
    protected final void overLimitExpenseRequiresApprovalThenCompletesOnApprove(MessageProtocol protocol) {
        InteractionFlow.of(client(ENTRY_AGENT))
                .protocol(protocol)
                .withMetadata(Map.of("userId", "manual-user", "agentId", "expense-review-main"))
                .withTimeoutMs(config.getPollTimeoutSeconds() * 1000L)
                // 轮 1 — 超标（住宿 800>600、晚餐 800>300）：workflow 走 risk=high ⇒ Questioner 审批节点。
                .send(OVER_LIMIT_EXPENSE)
                    .awaitState(TaskState.TASK_STATE_INPUT_REQUIRED)
                    .assertThat(assertStreamTrajectory(protocol,
                            "场景1 轮1 流式状态序列: SUBMITTED → WORKING → INPUT_REQUIRED",
                            false, TaskState.TASK_STATE_INPUT_REQUIRED))
                    .assertGenerated(generated -> assertThat(generated)
                            .as("轮1 回复（审批提示）非空（多为 llm_output/content，无离散 ANSWER）")
                            .isNotBlank())
                // 轮 2 — 经理审批。InteractionFlow 续轮自动携 taskId+contextId 续传原任务，workflow 恢复至 End。
                .send(APPROVE)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertThat(assertStreamTrajectory(protocol,
                            "场景1 轮2 流式状态含 WORKING → COMPLETED（续轮——SUBMITTED 可选）",
                            true, TaskState.TASK_STATE_COMPLETED))
                    .assertGenerated(generated -> assertThat(generated)
                            .as("轮2 审核结果非空（workflow_final 在干净帧计入 answerText，两协议同构）")
                            .isNotBlank())
                .execute();
    }

    // ---- 场景 2：合规报销 → 自动通过（Path B）----

    /**
     * 合规报销（全部条目在限额内）⇒ {@code risk=none} ⇒ auto_approve ⇒
     * 流式 {@code SUBMITTED → WORKING → COMPLETED}（无需人工审批），回答非空且实质。
     * 参数化覆盖全部四种线协议；单轮 {@code COMPLETED}（终态可达）四协议应全绿（见类注释调试矩阵）。
     * 状态轨迹仅在 {@code A2A_STREAM} 下断言，其余三协议 {@link #assertStreamTrajectory} 为 no-op（终态由 {@code .awaitState(...)} 覆盖）。
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @EnumSource(value = MessageProtocol.class, mode = EnumSource.Mode.INCLUDE,
            names = {"A2A_STREAM", "A2A_SYNC", "REST_QUERY", "REST_QUERY_SYNC"})
    @DisplayName("场景2: 合规报销 → 自动通过 COMPLETED（Path B）")
    protected final void compliantExpenseAutoApprovesAndCompletes(MessageProtocol protocol) {
        InteractionFlow.of(client(ENTRY_AGENT))
                .protocol(protocol)
                .withMetadata(Map.of("userId", "manual-user", "agentId", "expense-review-main"))
                .withTimeoutMs(config.getPollTimeoutSeconds() * 1000L)
                .send(COMPLIANT_EXPENSE)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertThat(assertStreamTrajectory(protocol,
                            "场景2 流式状态序列: SUBMITTED → WORKING → COMPLETED",
                            false, TaskState.TASK_STATE_COMPLETED))
                    .assertGenerated(generated -> {
                        assertThat(generated).as("自动通过结果非空").isNotBlank();
                        assertThat(generated.length())
                                .as("结果实质（非空错误/拒答）")
                                .isGreaterThan(8);
                    })
                .execute();
    }

    // ---- helpers（与 StreamingTravelPlanningTest 同风格）----

    /**
     * 断言 {@link MessageProtocol#A2A_STREAM} 下的流式任务状态轨迹。sync 与 REST transport 只呈现终态（无
     * {@code SUBMITTED}/{@code WORKING}），而终态已由上游 {@code .awaitState(...)} 拘住——故对这些协议无可额外
     * 校验，本断言器为 no-op。{@code continuation} 切换流式匹配：严格（{@code containsExactly}：
     * {@code SUBMITTED → WORKING → terminal}，新轮）或宽松（{@code containsSubsequence}：{@code WORKING → terminal}，
     * {@code SUBMITTED} 可选，续轮）——运行时恢复进行中的任务时可能合法重发 {@code SUBMITTED}。
     */
    private static Consumer<InteractionFlow.RoundContext> assertStreamTrajectory(
            MessageProtocol protocol, String description, boolean continuation, TaskState terminal) {
        return ctx -> {
            if (protocol != MessageProtocol.A2A_STREAM) {
                return;
            }
            List<TaskState> trajectory = distinctStatesInOrder(ctx.events());
            if (continuation) {
                assertThat(trajectory)
                        .as(description)
                        .containsSubsequence(TaskState.TASK_STATE_WORKING, terminal);
            } else {
                assertThat(trajectory)
                        .as(description)
                        .containsExactly(
                                TaskState.TASK_STATE_SUBMITTED,
                                TaskState.TASK_STATE_WORKING,
                                terminal);
            }
        };
    }

    /** 去重保序的流式任务状态轨迹（多次 WORKING 进度只计一次），用于断言状态机序列。 */
    private static List<TaskState> distinctStatesInOrder(List<InboundEvent> events) {
        List<TaskState> seen = new ArrayList<>();
        for (InboundEvent e : events) {
            if (e.kind() == InboundEvent.Kind.STATE && !seen.contains(e.state())) {
                seen.add(e.state());
            }
        }
        return seen;
    }
}

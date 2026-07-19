package com.huawei.ascend.sit.cases.integration.workflow_call;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
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
 * 费用报销审核 Workflow Agent —— 端到端验收（人工审批中断/恢复 + 条件路由自动通过）.
 *
 * <p>驱动 {@code com.huawei.ascend:expense-review-workflow:0.2.0-SNAPSHOT}（单 jar 双 profile）：
 * <ul>
 *   <li>{@code expense-review-workflow}（默认 profile）—— 8 节点 DAG：
 *       Start→LLM(analyze)→Tool(check_policy)→LLM(audit)→Branch(route)→
 *       [risk=high: Questioner(approve)] 或 [risk=none: LLM(auto_approve)]→End。</li>
 *   <li>{@code expense-review-main}（{@code main} profile）—— 主控 ReActAgent，
 *       由 LLM 决策把报销请求作为远程 A2A 工具 {@code review_expense} 调用 workflow。</li>
 * </ul>
 *
 * <p>两场景（对齐 README 场景 1/2），均经 {@code expense-review-main} 驱动（完整拓扑 main→workflow 远程 A2A）：
 * <ul>
 *   <li><b>场景 1 — 超标报销 → 人工审批（Path A）</b>：住宿 800/晚 &gt; 600、客户晚餐 800 &gt; 300 ⇒
 *       {@code risk=high} ⇒ Questioner ⇒ 流式 {@code SUBMITTED → WORKING → INPUT_REQUIRED}（审批提示）；
 *       续接 {@code "approved"}（{@link InteractionFlow} 续轮自动携 taskId+contextId 续传原任务）⇒
 *       {@code … → COMPLETED}。</li>
 *   <li><b>场景 2 — 合规报销 → 自动通过（Path B）</b>：全部条目在限额内 ⇒ {@code risk=none} ⇒
 *       auto_approve ⇒ 流式 {@code SUBMITTED → WORKING → COMPLETED}（无需人工审批）。</li>
 * </ul>
 *
 * <p><b>LLM 与 profile。</b>两 agent 均读 {@code ${SAA_*}}（bundled yaml 占位符；{@code sut.java.system-properties}
 * 里的 {@code SAA_*} 以 {@code -D} 注入每个子进程，Spring {@code @Value} 解析）。运行：
 * {@code ./mvnw -Dtest=ExpenseReviewAcceptanceTest test}（单场景加 {@code #方法名}）。
 *
 * <p><b>协议参数化（4 种线协议）。</b>两场景各以 {@code @ParameterizedTest} 覆盖 {@link MessageProtocol} 全部四值
 * （A2A / REST 各一对流式·sync），与 {@link com.huawei.ascend.sit.cases.integration.react_travel.StreamingTravelPlanningTest}
 * 同型——这是一面调试矩阵：哪个协议单元变红，就指向哪个 transport adapter 还没接好。每个调用以
 * {@code <scenario>-<protocol>} 为 {@code sessionId}，故八次调用不撞。
 *
 * <p><b>分协议断言（两层）：</b>
 * <ul>
 *   <li>{@code .awaitState(...)} 对四协议都严格（{@code InboundExchange} 已归一化终态/中断态），与 transport 无关。</li>
 *   <li>流式状态轨迹（{@code SUBMITTED → WORKING → terminal}）仅断言于 {@code A2A_STREAM}——sync 与 REST 只呈现终态
 *       （已由 {@code .awaitState(...)} 覆盖），故 {@link #assertStreamTrajectory} 对其余三协议为 no-op。</li>
 * </ul>
 *
 * <p><b>调试矩阵：</b>场景 2（单轮 {@code COMPLETED}，终态可达）四协议应全绿；场景 1 是多轮 {@code INPUT_REQUIRED}，
 * 该中断态仅在 {@code A2A_STREAM} 下可靠呈现——sync/REST 下可能失败或超时，正是“这些 transport 尚未呈现
 * {@code INPUT_REQUIRED}”的信号（REST 侧即网关 INPUT_REQUIRED 透传仍未标定，属独立问题）。
 *
 * <p><b>已知 SUT 缺陷（非用例缺陷，待修复）—— 用例为 active，实跑前确认下述被测 jar 已重建。</b>被测 jar
 * {@code expense-review-workflow-0.2.0-SNAPSHOT} 的 workflow DAG {@code analyze}/{@code audit} 节点调用
 * {@code setResponseFormat(\{"type":"json"\})}，被 OpenJiuwen 0.1.12 拒绝（实测 code=101004：
 * {@code "json response format, output config should contain at least one field"}），{@code analyze} 节点
 * <b>确定性失败</b>（实测：场景1 每次1/1 失败；场景2 main 重试3/3 全失败）。因此：
 * <ul>
 *   <li><b>场景 1</b>无法到达 Questioner/{@code INPUT_REQUIRED}（{@code analyze} 先死）→ 用例报错（诚实红）。</li>
 *   <li><b>场景 2</b>经 main ReAct 3 次重试工具失败后回退自身文本 → {@code COMPLETED}，满足弱断言但<b>非真实 Path B</b>（假绿）。</li>
 * </ul>
 *
 * <p><b>更新（2026-07-14 实跑）：上述 analyze json-format 缺陷在当前 jar 已不复现</b>——场景1 正确到达
 * {@code INPUT_REQUIRED}（审批提示以流式 {@code llm_output} 下发，无离散 {@code answer} 帧），场景2 正确产出
 * {@code workflow_final} 自动审核报告并 {@code COMPLETED}。该 SUT 把结果发在自定义 {@code workflow_final} 类型
 * （非标准 {@code answer}）下，故各轮文本断言改用 {@code .assertGenerated(...)}（读 {@code generatedText()} 超集，
 * 覆盖 {@code llm_output}/{@code content}/{@code answer}）。并把 {@code workflow_final.payload.output} 认作
 * {@code answerText()}——该映射放在共享分类器 {@code LlmPayload}（{@code TYPE_WORKFLOW_FINAL}→{@code ANSWER}，
 * 两协议同构）：语义上 {@code workflow_final} 即结果。注意：REST_QUERY 下该帧经网关有时被双重转义（外层是 JSON 字符串、
 * 首字符 {@code "} 而非 {@code {}，被 {@code LlmPayload} 前置过滤跳过而落入 {@code content}）——这是该次实跑的帧
 * 数据质量问题，非取舍策略；断言用 {@code assertGenerated} 不受影响。另：人工审批的 {@code INPUT_REQUIRED} 经 REST
 * 的呈现仍待验证（场景1-REST 可能仍红，属独立问题）。
 *
 * @see com.huawei.ascend.sit.cases.integration.react_travel.StreamingTravelPlanningTest
 */
@Tag("integration")
class ExpenseReviewAcceptanceTest extends BaseManagedStackTest {

    /** 驱动入口：主控 ReActAgent（完整拓扑 main→workflow）。需兜底时一行切 "expense-review-workflow"。 */
    private static final String ENTRY_AGENT = "expense-review-main";

    /** 场景 1 —— 超标报销：住宿 800/晚 &gt; 600、客户晚餐 800 &gt; 300 ⇒ risk=high ⇒ 人工审批。 */
    private static final String OVER_LIMIT_EXPENSE =
            "帮我审核这笔报销：机票5000，酒店3晚每晚800共2400，客户晚餐800";

    /** 场景 1 续轮 —— 经理审批通过，续传原任务恢复 workflow。 */
    private static final String APPROVE = "approved";

    /** 场景 2 —— 合规报销：机票 3000≤5000、住宿 500≤600、餐 200≤300 ⇒ risk=none ⇒ 自动通过。 */
    private static final String COMPLIANT_EXPENSE =
            "审核这笔报销：机票3000，酒店2晚每晚500共1000，餐费200";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // 同一 jar、双 profile，叶子优先。profile 由 YAML 单点声明（sut.agents.expense-review-main.profile: main），
        // buildStack 只管拓扑：SutStack 把 workflow 的随机端口 URL 作为
        // --agent-runtime.remote-agents[0].url 注入 main（命令行覆盖 yaml 的 localhost:8080 默认值）。
        return SutStack.builder(config)
                .agent("expense-review-workflow")
                .agent("expense-review-main", a -> a.downstream("expense-review-workflow"));
    }

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
    void overLimitExpenseRequiresApprovalThenCompletesOnApprove(MessageProtocol protocol) {
        InteractionFlow.of(client(ENTRY_AGENT))
                .protocol(protocol)
                .withMetadata(Map.of("userId", "manual-user", "agentId", "expense-review-main",
                        "sessionId", "expense-scenario1-" + protocol.name()))
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
    void compliantExpenseAutoApprovesAndCompletes(MessageProtocol protocol) {
        InteractionFlow.of(client(ENTRY_AGENT))
                .protocol(protocol)
                .withMetadata(Map.of("userId", "manual-user", "agentId", "expense-review-main",
                        "sessionId", "expense-scenario2-" + protocol.name()))
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

    // ---- helpers（与 StreamingTravelPlanningTest 同风格，类内复制）----

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


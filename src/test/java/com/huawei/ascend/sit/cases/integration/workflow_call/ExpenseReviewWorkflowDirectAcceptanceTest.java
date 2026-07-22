package com.huawei.ascend.sit.cases.integration.workflow_call;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.transport.InboundEvent;
import com.huawei.ascend.sit.transport.MessageProtocol;
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;
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
 * <b>直连</b> {@code expense-review-workflow} 的四协议验收测试 —— 跳过 {@code expense-review-main} 中转，
 * 直接驱动 workflow agent（8 节点 DAG），覆盖 A2A 流式/同步 + REST 流式/同步四种线协议。
 *
 * <p>与 {@link ExpenseReviewAcceptanceTest}（经 main 路由再对接 workflow）对照：本类<b>不起 main、无 downstream</b>，
 * 单 agent 栈；两场景 × 四协议 = 8 用例全部经 {@code client(WORKFLOW_AGENT)} 直驱。直连是一条更纯净的探针 —— 无 main
 * 中转，直接验证 workflow 自身四种 transport 是否端到端可用、是否正确呈现 {@code INPUT_REQUIRED} 中断态。
 *
 * <p><b>两场景</b>（与 main-routed 对齐，prompt 本就是 workflow 直驱兼容的裸报销文本）：
 * <ul>
 *   <li><b>场景 1 — 超标报销 → 人工审批（Path A）</b>：住宿 800/晚 &gt; 600、客户晚餐 800 &gt; 300 ⇒ {@code risk=high}
 *       ⇒ Questioner ⇒ 流式 {@code SUBMITTED → WORKING → INPUT_REQUIRED}（审批提示）；续接 {@code "approved"}
 *       （{@link InteractionFlow} 续轮自动携 taskId+contextId 续传原任务）⇒ {@code … → COMPLETED}。</li>
 *   <li><b>场景 2 — 合规报销 → 自动通过（Path B）</b>：全部条目在限额内 ⇒ {@code risk=none} ⇒ auto_approve ⇒
 *       流式 {@code SUBMITTED → WORKING → COMPLETED}（无需人工审批）。</li>
 * </ul>
 *
 * <p><b>协议参数化（4 种线协议）。</b>两场景各以 {@code @ParameterizedTest} 覆盖 {@link MessageProtocol} 四值
 * （A2A / REST 各一对流式·sync）。每个调用以 {@code expense-direct-scenarioN-<protocol>} 为 {@code sessionId}，故八次调用不撞，
 * 亦不与 main-routed 测试撞（后者用 {@code expense-scenarioN-<protocol>}）。
 *
 * <p><b>分协议断言（两层）：</b>{@code .awaitState(...)} 对四协议都严格；流式状态轨迹（{@code SUBMITTED → WORKING → terminal}）
 * 仅断言于 {@code A2A_STREAM}——sync 与 REST 只呈现终态（已由 {@code .awaitState(...)} 覆盖），故 {@link #assertStreamTrajectory}
 * 对其余三协议为 no-op。
 *
 * <p><b>调试矩阵（拓扑无关，与 main-routed 同）：</b>场景 2（单轮 {@code COMPLETED}）四协议应全绿；场景 1 是多轮
 * {@code INPUT_REQUIRED}，该中断态仅在 {@code A2A_STREAM} 下可靠呈现——sync/REST 下可能失败或超时，正是
 * "这些 transport 尚未呈现 INPUT_REQUIRED"的信号。
 *
 * <p><b>断言形式。</b>workflow 把结果发在自定义 {@code workflow_final} 类型下，故各轮文本断言用
 * {@code .assertGenerated(...)}（读 {@code generatedText()} 超集）；{@code workflow_final.payload.output} 经共享分类器
 * {@code LlmPayload} 认作 {@code answerText()}（{@code TYPE_WORKFLOW_FINAL}→{@code ANSWER}，两协议同构）。
 *
 * @see ExpenseReviewAcceptanceTest 经 main 路由的对照变体
 * @see AbstractExpenseReviewAcceptanceTest 模板基类（常量与助手来源）
 */
@Tag("integration")
@Feature("FEAT-001: 标准化智能体服务入口")
class ExpenseReviewWorkflowDirectAcceptanceTest extends BaseManagedStackTest {

    /** 直驱目标：workflow agent（8 节点 DAG）。不起 main、无 downstream。 */
    private static final String WORKFLOW_AGENT = "expense-review-workflow";

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
        // 单 agent：仅 workflow，不起 main、无 downstream。workflow 自包含（YAML 无 remote-agents-prefix），
        // 全局 LLM keys 由 java.system-properties 注入。无环境 gate（镜像 in-memory ExpenseReviewAcceptanceTest，live 运行）。
        return SutStack.builder(config)
                .agent(WORKFLOW_AGENT);
    }

    // ---- 场景 1：超标报销 → 人工审批 → 续接恢复（Path A）----

    /**
     * 超标报销触发 {@code INPUT_REQUIRED}（审批提示），续接 {@code "approved"} 恢复至 {@code COMPLETED}。
     * 参数化覆盖全部四种线协议，直驱 workflow。
     *
     * <p>轮 1 流式 {@code SUBMITTED → WORKING → INPUT_REQUIRED}（审批提示，非空）；
     * 轮 2（续轮，携原 taskId+contextId）以 {@code WORKING → COMPLETED} 收尾。多轮 {@code INPUT_REQUIRED} 仅在
     * {@code A2A_STREAM} 下可靠呈现（见类注释调试矩阵）；状态轨迹仅在 {@code A2A_STREAM} 下断言，其余三协议
     * {@link #assertStreamTrajectory} 为 no-op（终态由 {@code .awaitState(...)} 覆盖）。
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @EnumSource(value = MessageProtocol.class, mode = EnumSource.Mode.INCLUDE,
            names = {"A2A_STREAM", "A2A_SYNC", "REST_QUERY", "REST_QUERY_SYNC"})
    @DisplayName("直连 workflow 场景1: 超标报销 → INPUT_REQUIRED → 续接 approved → COMPLETED（Path A）")
    @Stories({
            @Story("wf.input-required: 人工审批中断 INPUT_REQUIRED→续接恢复")
    })
    void overLimitExpenseRequiresApprovalThenCompletesOnApprove(MessageProtocol protocol) {
        InteractionFlow.of(client(WORKFLOW_AGENT))
                .protocol(protocol)
                .withMetadata(Map.of("userId", "direct-user", "agentId", "expense-review-workflow",
                        "sessionId", "expense-direct-scenario1-" + protocol.name()))
                .withTimeoutMs(config.getPollTimeoutSeconds() * 1000L)
                // 轮 1 — 超标（住宿 800>600、晚餐 800>300）：workflow 走 risk=high ⇒ Questioner 审批节点。
                .send(OVER_LIMIT_EXPENSE)
                    .awaitState(TaskState.TASK_STATE_INPUT_REQUIRED)
                    .assertThat(assertStreamTrajectory(protocol,
                            "场景1 轮1 流式状态序列: SUBMITTED → WORKING → INPUT_REQUIRED",
                            false, TaskState.TASK_STATE_INPUT_REQUIRED))
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
     * 参数化覆盖全部四种线协议，直驱 workflow；单轮 {@code COMPLETED}（终态可达）四协议应全绿。
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @EnumSource(value = MessageProtocol.class, mode = EnumSource.Mode.INCLUDE,
            names = {"A2A_STREAM", "A2A_SYNC", "REST_QUERY", "REST_QUERY_SYNC"})
    @DisplayName("直连 workflow 场景2: 合规报销 → 自动通过 COMPLETED（Path B）")
    @Stories({
            @Story("wf.direct-streaming: 直连 workflow A2A 流式 SendStreamingMessage"),
            @Story("wf.direct-blocking: 直连 workflow A2A 阻塞 SendMessage"),
            @Story("wf.direct-rest-query: 直连 workflow REST 流式/同步 POST /v1/query"),
            @Story("wf.rest-a2a-equivalence: 直连下 REST 与 A2A 入口结果等价"),
            @Story("wf.task-lifecycle: Task 状态序列 submitted→working→terminal"),
            @Story("wf.workflow-agent: 直驱标准化 workflow agent 入口")
    })
    void compliantExpenseAutoApprovesAndCompletes(MessageProtocol protocol) {
        InteractionFlow.of(client(WORKFLOW_AGENT))
                .protocol(protocol)
                .withMetadata(Map.of("userId", "direct-user", "agentId", "expense-review-workflow",
                        "sessionId", "expense-direct-scenario2-" + protocol.name()))
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

    // ---- helpers（与 AbstractExpenseReviewAcceptanceTest 同风格，逐字照搬）----

    /**
     * 断言 {@link MessageProtocol#A2A_STREAM} 下的流式任务状态轨迹。sync 与 REST transport 只呈现终态（无
     * {@code SUBMITTED}/{@code WORKING}），而终态已由上游 {@code .awaitState(...)} 拘住——故对这些协议无可额外
     * 校验，本断言器为 no-op。{@code continuation} 切换流式匹配：严格（{@code containsExactly}：
     * {@code SUBMITTED → WORKING → terminal}，新轮）或宽松（{@code containsSubsequence}：{@code WORKING → terminal}，
     * {@code SUBMITTED} 可选，续轮）。
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

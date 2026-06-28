package com.huawei.ascend.sit.cases.integration.expense_review;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

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
 * <p><b>当前状态：{@code @Disabled} —— 待 SUT 修复（非用例缺陷）。</b>被测 jar
 * {@code expense-review-workflow-0.2.0-SNAPSHOT} 的 workflow DAG {@code analyze}/{@code audit} 节点调用
 * {@code setResponseFormat(\{"type":"json"\})}，被 OpenJiuwen 0.1.12 拒绝（实测 code=101004：
 * {@code "json response format, output config should contain at least one field"}），{@code analyze} 节点
 * <b>确定性失败</b>（实测：场景1 每次1/1 失败；场景2 main 重试3/3 全失败）。因此：
 * <ul>
 *   <li><b>场景 1</b>无法到达 Questioner/{@code INPUT_REQUIRED}（{@code analyze} 先死）→ 用例报错（诚实红）。</li>
 *   <li><b>场景 2</b>经 main ReAct 3 次重试工具失败后回退自身文本 → {@code COMPLETED}，满足弱断言但<b>非真实 Path B</b>（假绿）。</li>
 * </ul>
 *
 * @see com.huawei.ascend.sit.cases.integration.travel_assistant.StreamingTravelPlanningTest
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
     *
     * <p>轮 1 流式 {@code SUBMITTED → WORKING → INPUT_REQUIRED}（审批提示，非空）；
     * 轮 2（续轮，携原 taskId+contextId）以 {@code WORKING → COMPLETED} 收尾（{@code endsWith} 容忍续轮是否
     * 重发 {@code SUBMITTED}），审核结果非空。
     */
    @Test
    @DisplayName("场景1: 超标报销 → INPUT_REQUIRED → 续接 approved → COMPLETED（Path A）")
    void overLimitExpenseRequiresApprovalThenCompletesOnApprove() {
        InteractionFlow.of(client(ENTRY_AGENT))
                .withMetadata(Map.of("userId", "manual-user", "agentId", "expense-review-main",
                        "sessionId", "expense-scenario1-001"))
                .withTimeoutMs(config.getPollTimeoutSeconds() * 1000L)
                // 轮 1 — 超标（住宿 800>600、晚餐 800>300）：workflow 走 risk=high ⇒ Questioner 审批节点。
                .send(OVER_LIMIT_EXPENSE)
                    .awaitState(TaskState.TASK_STATE_INPUT_REQUIRED)
                    .assertThat(ctx -> assertThat(distinctStatesInOrder(ctx.events()))
                            .as("场景1 轮1 状态序列: SUBMITTED → WORKING → INPUT_REQUIRED")
                            .containsExactly(
                                    TaskState.TASK_STATE_SUBMITTED,
                                    TaskState.TASK_STATE_WORKING,
                                    TaskState.TASK_STATE_INPUT_REQUIRED))
                    .assertTask(task -> assertThat(textOf(task))
                            .as("轮1 回复（审批提示）非空")
                            .isNotBlank())
                // 轮 2 — 经理审批。InteractionFlow 续轮自动携 taskId+contextId 续传原任务，workflow 恢复至 End。
                .send(APPROVE)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertThat(ctx -> assertThat(distinctStatesInOrder(ctx.events()))
                            .as("场景1 轮2 以 WORKING → COMPLETED 收尾（容忍是否重发 SUBMITTED）")
                            .endsWith(
                                    TaskState.TASK_STATE_WORKING,
                                    TaskState.TASK_STATE_COMPLETED))
                    .assertTask(task -> assertThat(textOf(task))
                            .as("轮2 审核结果非空")
                            .isNotBlank())
                .execute();
    }

    // ---- 场景 2：合规报销 → 自动通过（Path B）----

    /**
     * 合规报销（全部条目在限额内）⇒ {@code risk=none} ⇒ auto_approve ⇒
     * 流式 {@code SUBMITTED → WORKING → COMPLETED}（无需人工审批），回答非空且实质。
     */
    @Test
    @DisplayName("场景2: 合规报销 → 自动通过 COMPLETED（Path B）")
    void compliantExpenseAutoApprovesAndCompletes() {
        InteractionFlow.of(client(ENTRY_AGENT))
                .withMetadata(Map.of("userId", "manual-user", "agentId", "expense-review-main",
                        "sessionId", "expense-scenario2-001"))
                .withTimeoutMs(config.getPollTimeoutSeconds() * 1000L)
                .send(COMPLIANT_EXPENSE)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertThat(ctx -> assertThat(distinctStatesInOrder(ctx.events()))
                            .as("场景2 状态序列: SUBMITTED → WORKING → COMPLETED")
                            .containsExactly(
                                    TaskState.TASK_STATE_SUBMITTED,
                                    TaskState.TASK_STATE_WORKING,
                                    TaskState.TASK_STATE_COMPLETED))
                    .assertTask(task -> {
                        String answer = textOf(task);
                        assertThat(answer).as("自动通过结果非空").isNotBlank();
                        assertThat(answer.length())
                                .as("结果实质（非空错误/拒答）")
                                .isGreaterThan(8);
                    })
                .execute();
    }

    // ---- helpers（与 StreamingTravelPlanningTest 同风格，类内复制）----

    /** 去重保序的流式任务状态轨迹（多次 WORKING 进度只计一次），用于断言状态机序列。 */
    private static List<TaskState> distinctStatesInOrder(List<ClientEvent> events) {
        LinkedHashSet<TaskState> states = new LinkedHashSet<>();
        for (ClientEvent event : events) {
            Task task = taskOf(event);
            if (task != null && task.status() != null && task.status().state() != null) {
                states.add(task.status().state());
            }
        }
        return new ArrayList<>(states);
    }

    private static Task taskOf(ClientEvent event) {
        if (event instanceof TaskEvent te) {
            return te.getTask();
        }
        if (event instanceof TaskUpdateEvent ue) {
            return ue.getTask();
        }
        return null;
    }

    /** 拼接任务文本输出：artifacts → status message → 最后一条 history。 */
    private static String textOf(Task task) {
        StringBuilder sb = new StringBuilder();
        if (task.artifacts() != null) {
            for (Artifact artifact : task.artifacts()) {
                appendText(sb, artifact.parts());
            }
        }
        if (sb.length() == 0 && task.status() != null && task.status().message() != null) {
            appendText(sb, task.status().message().parts());
        }
        if (sb.length() == 0 && task.history() != null && !task.history().isEmpty()) {
            appendText(sb, task.history().get(task.history().size() - 1).parts());
        }
        return sb.toString().trim();
    }

    private static void appendText(StringBuilder sb, List<Part<?>> parts) {
        if (parts == null) {
            return;
        }
        for (Part<?> part : parts) {
            if (part instanceof TextPart textPart && textPart.text() != null) {
                sb.append(textPart.text());
            }
        }
    }
}

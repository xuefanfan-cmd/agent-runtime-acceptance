/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.openjiuwen.agents.orchestration.MemberSpec;
import com.openjiuwen.agents.orchestration.SubagentTranscriptTool;
import com.openjiuwen.da.toolcontext.DaToolContext;
import com.openjiuwen.da.toolcontext.DaToolContextHolder;
import com.openjiuwen.da.workflow.SendMessageTool;
import com.openjiuwen.agents.reactrails.loopforest.execution.SubAgentExecutor;
import com.openjiuwen.da.workflow.DaTaskBoard;
import com.openjiuwen.da.workflow.DaWorkflowRunner;
import com.openjiuwen.da.workflow.DelegateTaskTool;
import com.openjiuwen.da.workflow.TaskOutputTool;
import com.openjiuwen.da.workflow.WorkflowRunTool;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import org.junit.jupiter.api.Timeout;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 067-S18：{@code workflow_run} 与 {@code delegate_task} 的**幂等 / 重复保护**现状
 * （横切层扩审新增场景，见 `06-横切层MUST逐条判定-20261016.md` 第 5、6 条）。
 *
 * <p>Oracle 来源：`L0-constraints:35`「工具/技能调用**必须**经过授权、容量、**幂等**、审计和可观测性边界」；
 * `L0-constraints:41`「副作用安全 | 不可逆副作用**必须**具备幂等或重复保护，并留下审计证据」。
 *
 * <p><b>为什么这两件工具算"有副作用"</b>：{@code workflow_run} 一次调用会驱动 N 个真实模型成员
 * （每成员是裸 {@code ReActAgent}，{@code maxIterations} 默认 15）⇒ **产生真实 token 成本**；
 * {@code delegate_task} 会向固定线程池提交后台任务并占用任务板（而任务板终态无界累积，见 067-S17）。
 * 二者重复调用都会重复消耗资源，属 L0-constraints:41 所称需要"幂等或重复保护"的形态。
 *
 * <p><b>本类同样是特征化取证</b>：设计侧尚未裁决"哪些属不可逆副作用、是否要求幂等键"
 * （`06-…判定` 第 6 条判为**待裁决**），故不把"必须有幂等键"写成断言去判产品 FAIL；
 * 而是把现状固定为证据：重复调用**会**重复执行、**没有**幂等键/去重、每次都是新的执行标识。
 * 覆盖台账据此把该 REQ 记为 `degraded` + gap，缺陷主张走 ISSUE 草稿。
 */
@Tag("component")
@Tag("feat-067")
@Feature("FEAT-067: 编排承接——会话内工作流工具组")
class DaWorkflowIdempotencyTest {

    /** 生产装配需要 baseRoot（DaWorkflowTools.attach:40）；缺它 workflow_run 走"不落盘"降级形态。 */
    @TempDir
    Path baseRoot;

    private static final String RUN = OrchestrationTestSupport.uid();

    private static final String BLUEPRINT = """
            blueprint:
              id: T-067-S18
              type: pipeline
              members:
                - {id: m1, instruction: "第一步"}
                - {id: m2, instruction: "第二步"}
              steps:
                - {id: s1, member: m1}
                - {id: s2, member: m2}
            """;

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    // 【2026-10-21 按 docs#33 comment 194890365 的裁决改写定性，**断言一字未改**】
    // 裁决：「当期口径：**重复调用 workflow_run/delegate_task = 独立新执行是语义本身、非缺陷**；
    // 副作用清单（留痕累积/任务登记/消息投递/token 消耗）**全部是可审计留痕**（森林/任务板/消息区可查），
    // 满足『留下审计证据』的当期兑现面；**幂等键不支持，挂后续版本**（1130 候选）」。
    // ⇒ 下面两个方法原本把"无幂等键、无去重"当**疑似缺陷**取证；现改为**符合语义本身**的特征化证据。
    //    新增的第 3 个方法才是裁决要求的**当期兑现面**判据：四类副作用各自可经公开查询面取回。
    @Story("067-S18: 重复调用=独立新执行（裁决：语义本身、非缺陷）+ 副作用可审计")
    @DisplayName("067-S18① 同一入参连调两次 workflow_run → 两个不同 workflow_id、成员执行体各被调两次（**符合裁决：独立新执行是语义本身**）")
    void repeatedWorkflowRunReExecutesWholeBlueprint() {
        final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        final Function<MemberSpec, SubAgentExecutor> factory = member -> {
            final String id = member.id();
            return (userInput, subGoal) -> {
                calls.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
                return "OUT-" + id + "-" + RUN;
            };
        };
        final DaWorkflowRunner runner = DaWorkflowTestSupport.newRunner(factory, 4);
        final WorkflowRunTool tool = DaWorkflowTestSupport.newRunTool(runner, baseRoot);
        final Map<String, Object> args = new LinkedHashMap<>();
        args.put("blueprint", BLUEPRINT);
        args.put("question", "U-067-S18-" + RUN);

        final Map<String, Object> first = DaWorkflowTestSupport.dataOf(
                DaWorkflowTestSupport.output(tool.invoke(args, Map.of())));
        final Map<String, Object> second = DaWorkflowTestSupport.dataOf(
                DaWorkflowTestSupport.output(tool.invoke(args, Map.of())));

        assertThat(first).containsEntry("status", "completed");
        assertThat(second).containsEntry("status", "completed");
        // 两次都是全新执行：标识不同、产物齐备
        assertThat((String) first.get("workflow_id")).isNotBlank();
        assertThat((String) second.get("workflow_id")).isNotBlank();
        assertThat(second.get("workflow_id"))
                .as("完全相同的入参重复调用 ⇒ 产生**新的** workflow_id，即无幂等键、无去重（重复消耗模型成本）")
                .isNotEqualTo(first.get("workflow_id"));
        // 整张图纸被重复执行：每个成员执行体各被调用两次
        assertThat(calls).as("两个成员都应被记录").containsKeys("m1", "m2");
        assertThat(calls.get("m1").get()).as("m1 应被执行两次（第二次调用未被去重）").isEqualTo(2);
        assertThat(calls.get("m2").get()).as("m2 应被执行两次（第二次调用未被去重）").isEqualTo(2);
        System.out.println("[067-S18] 同入参连调两次：workflow_id " + first.get("workflow_id")
                + " / " + second.get("workflow_id") + "，成员调用次数 " + calls);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @Story("067-S18: 重复调用=独立新执行（裁决：语义本身、非缺陷）+ 副作用可审计")
    @DisplayName("067-S18② 同一 goal 连调两次 delegate_task → 两个不同 task_id、委托体执行两次、**两条都在任务板可查**（任务登记这一类副作用可审计）")
    void repeatedDelegateTaskCreatesDistinctTasks() throws Exception {
        final AtomicInteger delegateCalls = new AtomicInteger();
        final String goal = "G-067-S18-" + RUN;
        try (DaTaskBoard board = new DaTaskBoard(4)) {
            final DelegateTaskTool delegate = new DelegateTaskTool(board, g -> {
                delegateCalls.incrementAndGet();
                return "DONE-" + g;
            });
            final TaskOutputTool output = new TaskOutputTool(board);

            final String firstId = (String) DaWorkflowTestSupport.dataOf(DaWorkflowTestSupport.output(
                    delegate.invoke(Map.of("goal", goal), Map.of()))).get("task_id");
            final String secondId = (String) DaWorkflowTestSupport.dataOf(DaWorkflowTestSupport.output(
                    delegate.invoke(Map.of("goal", goal), Map.of()))).get("task_id");

            assertThat(firstId).isNotBlank();
            assertThat(secondId).as("完全相同的 goal 重复委托 ⇒ 产生**新的** task_id，即无幂等键").isNotBlank();
            assertThat(secondId).isNotEqualTo(firstId);

            awaitCompleted(output, firstId);
            awaitCompleted(output, secondId);
            assertThat(delegateCalls.get()).as("委托体应被执行两次（第二次未被去重，重复消耗资源）").isEqualTo(2);
            assertThat(board.listAll()).as("任务板应同时持有两个任务").contains(firstId, secondId);
            System.out.println("[067-S18] 同 goal 连调两次：task_id " + firstId + " / " + secondId
                    + "，委托体执行次数=" + delegateCalls.get());
        }
    }

    // ==================================================================================
    // 067-S18③：**当期兑现面**判据——四类副作用各自可经公开查询面取回（"可审计留痕"）
    //
    // Oracle：docs#33 comment `194890365` 裁决四.2「副作用清单（留痕累积 / 任务登记 / 消息投递 / token 消耗）
    // **全部是可审计留痕**（森林 / 任务板 / 消息区可查），满足 `L0-constraints:41`『留下审计证据』的当期兑现面」。
    // Feature-067 §5.2:126（`docs@b4f45c06`）已把该口径写成明示边界。
    //
    // 四类副作用的承接方法：
    //   ① **留痕累积（森林可查）** → 本方法：同一 owner 下两次执行，经 `subagent_transcript` 的 `roots` 动作
    //      应能看到**两次**运行的提问原文（各自可查、互不覆盖）。
    //   ② **任务登记（任务板可查）** → 同类 `repeatedDelegateTaskCreatesDistinctTasks`（067-S18②）已断言
    //      `board.listAll()` 同时含两个 task_id ⇒ 不重复实现。
    //   ③ **消息投递（消息区可查）** → 本方法：`send_message` 投递后，成员实收输入里能取回原文
    //      （完整语义"取走即清、不重放"由 067-S05 承接，此处只证"投递有可查痕迹"）。
    //   ④ **token 消耗** → 🔴 **不可观测，只登记不断言**：实测 `grep -rn "token"` 于
    //      `common/agents/deepanalyze-java/engine/src/main/java/com/openjiuwen/da/workflow/` **命中 0**
    //      ⇒ 编排工具面（workflow_run / delegate_task / task_output / send_message / subagent_transcript /
    //      workflow_status）**没有任何 token 用量的导出**。裁决把它列入"可审计留痕"清单，但当前无查询面可取。
    //      按门禁「不得由测试侧自行补写产品规则」与「环境/观测缺口不得反推 Oracle」：**不改成"不需要可查"来绕开**，
    //      也**不自行判缺陷**（裁决未明确 token 的查询面在哪）⇒ 登记为**待设计澄清的观察项**，
    //      已写入台账 REQ-048 的 gap 与本用例注释。
    // ==================================================================================

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @Story("067-S18: 重复调用=独立新执行（裁决：语义本身、非缺陷）+ 副作用可审计")
    @DisplayName("067-S18③ 两次执行的副作用**可审计**：森林经 roots 各自可查（含提问原文）、消息经成员实收输入可查；token 消耗不可观测（只登记不断言）")
    void repeatedExecutionSideEffectsAreAuditableThroughPublicQuerySurfaces() {
        try (DaWorkflowTestSupport.Fixture fixture = DaWorkflowTestSupport.fixture("067-S18", RUN)) {
            final WorkflowRunTool runTool = fixture.runTool();
            final String owner = "sess-067-s18-" + RUN;
            final SubagentTranscriptTool transcript = fixture.transcriptTool(() -> owner);
            final String q1 = "AUDIT-Q1-067-S18-" + RUN;
            final String q2 = "AUDIT-Q2-067-S18-" + RUN;

            // ① 留痕累积（森林可查）：owner 由 DaWorkflowRunner.run():163 经 DaOrchestrationScope.currentKey()
            //    → DaToolContextHolder 取**线程上下文**，故必须用生产机制把会话上下文设上，
            //    否则 owner 落到 UNSCOPED("")，roots 会查不到 → 本用例会"假红"（构造口径同 067-S07b）。
            try {
                DaToolContextHolder.set(new DaToolContext(owner, "tenant-067-s18", List.of()));
                final Map<String, Object> first = DaWorkflowTestSupport.dataOf(DaWorkflowTestSupport.output(
                        runTool.invoke(Map.of("blueprint", BLUEPRINT, "question", q1), Map.of())));
                final Map<String, Object> second = DaWorkflowTestSupport.dataOf(DaWorkflowTestSupport.output(
                        runTool.invoke(Map.of("blueprint", BLUEPRINT, "question", q2), Map.of())));
                assertThat(first).containsEntry("status", "completed");
                assertThat(second).containsEntry("status", "completed");
                assertThat(second.get("workflow_id"))
                        .as("两次是独立新执行（裁决：语义本身、非缺陷）")
                        .isNotEqualTo(first.get("workflow_id"));
            } finally {
                DaToolContextHolder.clear();
            }

            final Map<String, Object> roots = DaWorkflowTestSupport.dataOf(DaWorkflowTestSupport.output(
                    transcript.invoke(Map.of("branch_id", "roots"), Map.of())));
            final String rootsText = String.valueOf(roots);
            assertThat(rootsText).as("森林可查（正向自检）：roots 应能看到**第一次**运行的提问原文；实收=%s", rootsText)
                    .contains(q1);
            assertThat(rootsText).as("森林可查：roots 应**同时**看到第二次运行的提问原文（两次执行各自留痕、互不覆盖）；实收=%s",
                    rootsText).contains(q2);
            @SuppressWarnings("unchecked")
            final List<Map<String, Object>> rootList = (List<Map<String, Object>>) roots.get("roots");
            assertThat(rootList).as("两次执行应各自留下一个可下钻的执行根；实收=%s", rootList)
                    .isNotNull().hasSizeGreaterThanOrEqualTo(2);

            // ③ 消息投递（消息区可查）：投递与运行必须在**同一会话 scope** 下。
            //    🔴 首版把 send 放在 scope 外、run 放在 scope 内 ⇒ 成员实收里查不到留言（run-58 实测 FAIL，
            //    实收 subGoal 只有 [upstream outputs] 段、无 [pending messages] 段）。
            //    **这不是缺陷，恰恰是 D1/D2 修复后的 owner 分区在起作用**（`DaOrchestrationScope`）：
            //    未 scope 的投递落到 UNSCOPED("") 收件箱，scoped 运行读的是 owner 收件箱，两者不通。
            //    ⇒ 生产语义是"主 Agent 在**某会话内**留言、**同会话**的工作流运行取走"，测试必须按同一 scope 构造。
            //    （这次误构造反而多拿到一条正向证据：跨 scope 的留言确实取不到。）
            final SendMessageTool send = fixture.runner.getSendMessage();
            final String message = "AUDIT-MSG-067-S18-" + RUN;
            try {
                DaToolContextHolder.set(new DaToolContext(owner, "tenant-067-s18", List.of()));
                assertThat(DaWorkflowTestSupport.dataOf(DaWorkflowTestSupport.output(
                        send.invoke(Map.of("target", "m2", "content", message), Map.of()))))
                        // 【2026-10-26 r6 判据改按设计口径——**同族漏改的第三处**，本次靠全仓 grep 扫出】
                        // 原断言 containsEntry("status","sent") 钉的是实现细节：设计文档（Feature-067 §4/§5.1.3、
                        // L2-067 §78）从未定义 send_message 的出参字段名或 status 取值；r6 起实现改为
                        // status="queued" 并新增 mode/queued_for/recipients/delivered/delivery。
                        // 本处只是**前置投递**，真正的判据在下方「留言经成员实收输入取回原文」——那才对应
                        // L2-067 §78 的「接收方在下一步 subGoal 投影的 [pending messages] 段看到」。
                        // 故这里只断**消息本身**的字段（target 回带，出自 basics()），不再断状态口径。
                        .as("send_message 前置投递应回带 target（消息本身字段，非状态口径）")
                        .containsEntry("target", "m2");
                DaWorkflowTestSupport.dataOf(DaWorkflowTestSupport.output(
                        runTool.invoke(Map.of("blueprint", BLUEPRINT, "question", "AUDIT-Q3-067-S18-" + RUN), Map.of())));
            } finally {
                DaToolContextHolder.clear();
            }
            final OrchestrationTestSupport.Recording m2 = fixture.members.get("m2");
            assertThat(m2).as("m2 的执行体应已被装配").isNotNull();
            assertThat(String.valueOf(m2.allSubGoals()))
                    .as("消息区可查：投递的留言应经成员实收输入（subGoal 投影）取回原文；实收=%s", m2.allSubGoals())
                    .contains(message);
            System.out.println("[067-S18③] 森林 roots 含两次提问=" + (rootsText.contains(q1) && rootsText.contains(q2))
                    + " 执行根数=" + (rootList == null ? -1 : rootList.size())
                    + " 消息可取回=" + String.valueOf(m2.allSubGoals()).contains(message)
                    + " | token 消耗：编排工具面无导出（grep 命中 0）⇒ 只登记不断言");
        }
    }

    private static void awaitCompleted(final TaskOutputTool tool, final String taskId)
            throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            final Map<String, Object> data = DaWorkflowTestSupport.dataOf(DaWorkflowTestSupport.output(
                    tool.invoke(Map.of("task_id", taskId), Map.of())));
            if ("completed".equals(data.get("status"))) {
                assertThat(data.get("output")).as("完成任务 %s 应带产物", taskId).isNotNull();
                return;
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("有界等待内任务未进入 completed：" + taskId);
    }
}

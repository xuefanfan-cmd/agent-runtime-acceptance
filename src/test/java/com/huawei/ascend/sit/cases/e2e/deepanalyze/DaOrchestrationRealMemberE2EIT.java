/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.e2e.deepanalyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.openjiuwen.agents.orchestration.BlueprintLoader;
import com.openjiuwen.agents.orchestration.BlueprintResult;
import com.openjiuwen.agents.orchestration.BlueprintScheduler;
import com.openjiuwen.agents.orchestration.BlueprintSpec;
import com.openjiuwen.agents.orchestration.StepOutcome;
import com.openjiuwen.agents.orchestration.StepSpec;
import com.openjiuwen.agents.reactrails.loopforest.execution.SubAgentExecutor;
import com.openjiuwen.agents.reactrails.loopforest.observability.TraceForest;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 065-S22（真实 LLM 成员代表路径）、067-S14（宿主不改编排语义，L3 与 L1 结构对照）、
 * 067-S16（真实 LLM 主 Agent 经会话触发 {@code workflow_run}）——**同一个类共担**，
 * 与三份设计细档 §8 落点表一致。
 *
 * <p>Oracle 来源：
 * <ul>
 *   <li>FEAT-065 §1 面向角色「Agent 开发者：以图纸声明多 Agent 协作拓扑，绑定自有执行体」
 *       ⇒ 真实使用形态是**模型成员**；065-S22 的 Then 四项（{@code status=completed}；
 *       {@code steps[]} 条数 == 3、每步 {@code member} 与图纸声明一致、{@code skipped} 全 false；
 *       {@code final_output} == 末步 output 且非空；与 L1 同一图纸的结果**结构逐项一致**）。</li>
 *   <li>FEAT-067 §6 第 2 条「宿主不得改变 FEAT-065/066 的执行语义——本特性只做装配与暴露」
 *       ⇒ 067-S14 的 L3/L1 结构对照（步数、每步 member、每步 skipped、登记序、final_output 来源）。</li>
 *   <li>FEAT-067 §1 面向角色「会话用户：用自然语言驱动多 Agent 图纸执行」+ §4「模板启动」场景
 *       ⇒ 067-S16：主 Agent **自主调用** {@code workflow_run}，出参契约与模板图纸步数一致。</li>
 * </ul>
 *
 * <p><b>取证面</b>：compat 的 {@code GET /agent/status/{taskId}} 出参里的 {@code toolCalls}
 * （{@code DaCompatController.java:757}），从中取 {@code workflow_run} 的 {@code output} 原文解析出参契约
 * ——**不依赖模型是否在答复里复述**（详见基座类注释）。
 *
 * <p><b>INCONCLUSIVE 与 FAIL 严格分开</b>（设计三处明文）：主 Agent 未调用工具 ⇒ INCONCLUSIVE（模型未配合）；
 * 调用了但契约不符（条数/成员/收口错位）⇒ **FAIL**（宿主改了编排语义）。
 *
 * <p>不应断言：模型输出的**内容质量**；**不得断言"检索发生了"**（{@code research-pipeline} 名字含"检索"，
 * ⚠️【2026-10-27 更正·docs !379】原注「L2-067 §1.3 明示成员是裸 ReActAgent、无工具——检索员不能检索」已失效：
 * FEAT-067:50 现为 MUST「成员与委托只读工具」（14 件，含 grep/glob/read_file/list_files/kb_search 等），
 * L067:52 给落点 DaWorkflowRunner.java:58-64/:234-256 ⇒ 检索员**能**检索；本用例新增的判据①-b（067-S31/REQ-057）
 * 正是据此断言成员运行期实际工具面 ⊆ 14 件且不触 :51 的 MUST NOT。仍不断言的只是"检索是否命中"（模型输出不可控）。）；
 * 不断言耗时有界（FEAT-065 §5.1.1 明示同步无超时）。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-065")
@Tag("feat-067")
@Feature("FEAT-065: 确定性编排层(蓝图调度)")
class DaOrchestrationRealMemberE2EIT extends DaOrchestrationE2EBase {

    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    /** 与 FEAT-065 065-S01 ② **同一张** dag 图纸（三步：两无前置 + 一汇点），经 blueprint 入参直给。 */
    private static final String DAG_BLUEPRINT = """
            blueprint:
              id: T-065-S22-dag
              type: dag
              members:
                - {id: m1, instruction: "用一句话汇总该问题的技术要点"}
                - {id: m2, instruction: "用一句话汇总该问题的合规要点"}
                - {id: m3, instruction: "把上游两路要点合并成一句话结论"}
              steps:
                - {id: s1, member: m1}
                - {id: s2, member: m2}
                - {id: s3, member: m3, depends_on: [s1, s2]}
            """;

    private static final String TEMPLATE = "research-pipeline";

    /** FEAT-067:50（MUST，docs main@65118fda／!379）逐字列出的 14 件成员只读工具。 */
    private static final Set<String> MEMBER_READ_ONLY_TOOLS = Set.of(
            "read_file", "grep", "glob", "list_files", "notebook_read", "kb_search", "doc_grep",
            "expand", "web_search", "wikipedia", "wiki_browse", "scholar_search", "pdf_read", "run_sql");

    /** FEAT-067:51（MUST NOT）：写工具/执行沙箱工具 4 件 + 编排六件。 */
    private static final Set<String> MEMBER_FORBIDDEN_TOOLS = Set.of(
            "write_file", "edit_file", "bash", "powershell",
            "workflow_run", "workflow_status", "delegate_task", "task_output",
            "send_message", "subagent_transcript");

    @Test
    @Story("065-S22 / 067-S14: 真实 LLM 成员照图执行，且宿主不改执行语义（与 L1 结构对照）")
    @DisplayName("065-S22 + 067-S14 图纸直给：steps==3、member 与声明一致、skipped 全 false、"
            + "final_output==末步 output，且与 L1 同一图纸的结果结构逐项一致")
    void realMemberRunMatchesL1Structure() throws Exception {
        final String question = "U-065-S22-" + RUN;
        final String input = "请调用 workflow_run 工具执行下面这张图纸。"
                + "question 参数用：" + question + " 。"
                + "blueprint 参数请**原样**传入以下 YAML 全文（不要改写、不要增删键）：\n"
                + DAG_BLUEPRINT
                + "\n工具返回后，请只回复工具出参里 final_output 的原文。";

        final JsonNode snapshot = runAndSnapshot(newSession("sess-065-s22"), input);
        failIfTaskFailed(snapshot);                       // 工具被调用但没跑完 = 产品 FAIL，不是 INCONCLUSIVE
        final JsonNode call = requireWorkflowRunCall(snapshot);
        final String raw = requireSuccessOutput(call);     // JSON 形态（r4/run-62 实测更正：DaToolResultJsonRail，裁 1-B2）

        // ① status=completed
        assertThat(extractStatus(raw)).as("① 出参 status 应为 completed；实收=%s", truncate(raw, 2048))
                .isEqualTo("completed");
        // ② workflow_id 非空（SSE 订阅键来源）
        final String workflowId = extractWorkflowId(raw);
        assertThat(workflowId).as("② workflow_id 应非空").isNotBlank();
        assertThat(workflowId).as("② 订阅键形态应可拼成 workflow:<id>").matches("[^,}\\s]+");
        // ③ steps[] 条数 == 3、每步 member 与图纸声明一致、skipped 全 false
        final List<Step> steps = extractSteps(raw);
        assertThat(steps).as("③ steps 条数应等于图纸步数 3（宿主不得改编排语义）").hasSize(3);
        final List<String> members = new ArrayList<>();
        final List<String> stepIds = new ArrayList<>();
        for (Step step : steps) {
            assertThat(step.id()).as("每步应带步骤标识").isNotBlank();
            assertThat(step.member()).as("每步应带成员标识").isNotBlank();
            assertThat(step.skipped()).as("本图纸无跳过策略，步骤 %s 不应被跳过", step.id()).isFalse();
            assertThat(step.branchId())
                    .as("每步应带 branch_id（转测评审 #33 V3 的修复项，subagent_transcript 入参来源）")
                    .isNotBlank();
            assertThat(step.output()).as("每步应有真实模型产物（步骤 %s）", step.id()).isNotBlank();
            stepIds.add(step.id());
            members.add(step.member());
        }
        assertThat(stepIds).as("③ 步骤标识与登记序应与图纸一致").containsExactly("s1", "s2", "s3");
        assertThat(members).as("③ 每步 member 应与图纸声明一致").containsExactly("m1", "m2", "m3");
        // ④ final_output == 末步 output 且非空。
        // 【2026-10-21 形态改造】原写法 contains("final_output=" + lastOutput) 依赖 ToolOutput.toString()
        // 的括号文本；JSON 形态下该串会被转义（引号/换行）⇒ 改为结构化比对，并按 OutputArchive 的
        // 1500/4000 落盘截断契约分流（见 base#assertFinalOutputMatchesLastStep，档位写入 Allure）。
        // **语义判据不变**：收口产物必须等于末步产物原文，不得错位、不得改写。
        final Step lastStep = steps.get(steps.size() - 1);
        final String lastOutput = lastStep.output();
        assertFinalOutputMatchesLastStep(raw, lastStep);
        assertThat(lastOutput).as("④ 末步产物不应为空").isNotBlank();

        // ⑤ 与 L1（同一张图纸、确定性执行体、直接调 BlueprintScheduler）做**结构对照**
        final BlueprintSpec spec = BlueprintLoader.load(DAG_BLUEPRINT);
        final Map<String, SubAgentExecutor> bindings = new LinkedHashMap<>();
        for (var member : spec.members()) {
            final String id = member.id();
            bindings.put(id, (userInput, subGoal) -> "L1-" + id + "-" + RUN);
        }
        final BlueprintResult l1;
        try (BlueprintScheduler scheduler = new BlueprintScheduler(new TraceForest(), 4)) {
            l1 = scheduler.execute(spec, bindings, question);
        }
        final List<StepOutcome> l1Outcomes = l1.stepOutcomes();
        assertThat(l1Outcomes).as("L1 对照侧应同样产出 3 步").hasSize(3);
        assertThat(stepIds).as("⑤ L3 与 L1 的步骤标识与登记序应逐项一致")
                .containsExactlyElementsOf(l1Outcomes.stream().map(StepOutcome::stepId).toList());
        assertThat(members).as("⑤ L3 与 L1 的每步成员应逐项一致")
                .containsExactlyElementsOf(l1Outcomes.stream().map(StepOutcome::memberId).toList());
        assertThat(steps).as("⑤ L3 与 L1 的 skipped 标记应逐项一致")
                .allSatisfy(step -> assertThat(step.skipped()).isFalse());
        assertThat(l1Outcomes).as("⑤ L1 对照侧同样不应有跳过步")
                .allSatisfy(o -> assertThat(o.skipped()).isFalse());
        assertThat(l1.finalOutput()).as("⑤ L1 侧收口产物应等于其末步产物")
                .isEqualTo(l1Outcomes.get(l1Outcomes.size() - 1).output());
        // 差异只允许出现在 output **内容**层（真实模型 vs 确定性执行体）
        assertThat(lastOutput).as("⑤ L3 的末步产物来自真实模型，不应等于 L1 的确定性标记串")
                .isNotEqualTo(l1Outcomes.get(2).output());
        Allure.addAttachment("L1 对照侧结构导出", "text/plain", String.join("\n",
                l1Outcomes.stream().map(o -> o.stepId() + "|" + o.memberId() + "|skipped=" + o.skipped()).toList()));
    }

    @Test
    @Story("067-S16: 真实 LLM 主 Agent 经会话触发 workflow_run（模板启动）")
    @DisplayName("067-S16 会话里点名模板 → 主 Agent 自主调用 workflow_run，出参契约与模板图纸步数一致")
    void sessionTriggersTemplateRun() throws Exception {
        final String question = "U-067-S16-" + RUN;
        final String input = "请用 workflow_run 工具、以 template 参数 research-pipeline 分析这个问题："
                + question + " 。不要自己作答，必须调用该工具；工具返回后只回复 final_output 的原文。";

        final JsonNode snapshot = runAndSnapshot(newSession("sess-067-s16"), input);
        failIfTaskFailed(snapshot);
        final JsonNode call = requireWorkflowRunCall(snapshot);
        final String raw = requireSuccessOutput(call);

        assertThat(extractStatus(raw)).as("status 应为 completed；实收=%s", truncate(raw, 2048))
                .isEqualTo("completed");
        assertThat(extractWorkflowId(raw)).as("workflow_id 应非空").isNotBlank();
        assertThat(extractFinalOutput(raw)).as("final_output 应非空").isNotBlank();

        // steps[] 条数与该模板图纸的步数一致（**独立**从 classpath 装载同一份模板取基准，不用硬编码数字）
        final BlueprintSpec templateSpec = BlueprintLoader.load(readClasspath(
                "workflow-templates/" + TEMPLATE + ".yaml"));
        final List<String> expectedStepIds = templateSpec.steps().stream().map(StepSpec::id).toList();
        final List<String> expectedMembers = templateSpec.steps().stream().map(StepSpec::member).toList();
        final List<Step> steps = extractSteps(raw);
        assertThat(steps).as("steps 条数应等于模板图纸编译后的步数 %s", expectedStepIds.size())
                .hasSize(expectedStepIds.size());
        final List<String> actualIds = new ArrayList<>();
        final List<String> actualMembers = new ArrayList<>();
        for (Step step : steps) {
            actualIds.add(step.id());
            actualMembers.add(step.member());
            assertThat(step.branchId()).as("每步 branch_id 应非空").isNotBlank();
        }
        assertThat(actualIds).as("步骤标识与登记序应与模板图纸编译产物一致")
                .containsExactlyElementsOf(expectedStepIds);
        assertThat(actualMembers).as("每步 member 应与模板图纸声明一致")
                .containsExactlyElementsOf(expectedMembers);
        // 【2026-10-21 形态改造】同上：括号文本 contains → 结构化 + 截断感知比对，语义判据不变
        assertFinalOutputMatchesLastStep(raw, steps.get(steps.size() - 1));
        // 入参侧留痕：主 Agent 确实是以 template 形态调用的（证明"模板启动"这条场景走通）
        Allure.addAttachment("workflow_run 入参原文", "text/plain", truncate(call.path("input").toString(), 4096));
        assertThat(call.path("input").toString()).as("入参应体现模板启动（template=%s）", TEMPLATE)
                .contains(TEMPLATE);
    }

    /**
     * 065-S23：留痕跨多次执行累积可查（与 FEAT-067 的 067-S07 共担；067-S07 已在 L1t 验证并 gating 通过）。
     *
     * <p>Oracle：FEAT-065 §5.1.5「留痕结构归宿主，**跨多次执行累积可查**」+ §4「宿主可按执行根查询
     * 全部步骤留痕，含跳过标记」；FEAT-067 §2「支持列出全部执行根」；L2-067 §2.3「宿主级森林跨 run 可查」。
     *
     * <p><b>判据按权威口径写，不按设计细档 §6 的"两个执行根"</b>：三份权威文档都只承诺
     * "累积可查 / 列出全部执行根"，**没有一处承诺每次运行各成一个根**；实现是
     * {@code BlueprintScheduler.ensureRoot} 复用首根 + {@code DaWorkflowRunner} 自陈
     * "各 run 的执行分支累积于同一根下"。§7G/§7J 已把该差异登记为**测试设计侧过度指定**并追评到 ISSUE #31/#33。
     *
     * <p>因此本用例断言：同一会话内连续两次 {@code workflow_run}（用**不同问题**、同一模板）后，
     * 经 {@code subagent_transcript} 列举执行根并按根下钻，能取回**两次运行合计**的步骤留痕
     * （≥ 2 × 模板步数），即"跨多次执行累积可查"成立。
     */
    @Test
    @Story("065-S23: 留痕跨多次执行累积可查（与 067-S07 共担）")
    @DisplayName("065-S23 同一会话连续两次 workflow_run 后，按执行根下钻能取回两次运行合计的步骤留痕")
    void tracesAccumulateAcrossRunsAndAreQueryableByRoot() throws Exception {
        final String session = newSession("sess-065-s23");
        final String q1 = "U-065-S23-first-" + RUN;
        final String q2 = "U-065-S23-second-" + RUN;
        final String runPrompt = "请调用 workflow_run 工具，template 参数用 " + TEMPLATE
                + " ，question 参数用 %s 。不要自己作答，必须调用该工具；工具返回后只回复 final_output 原文。";

        final JsonNode first = runAndSnapshot(session, String.format(runPrompt, q1));
        failIfTaskFailed(first);
        final List<Step> firstSteps = extractSteps(requireSuccessOutput(requireWorkflowRunCall(first)));
        final JsonNode second = runAndSnapshot(session, String.format(runPrompt, q2));
        failIfTaskFailed(second);
        final List<Step> secondSteps = extractSteps(requireSuccessOutput(requireWorkflowRunCall(second)));
        assertThat(firstSteps).as("第一次运行应有留痕").isNotEmpty();
        assertThat(secondSteps).as("第二次运行应有留痕").isNotEmpty();

        // 列举执行根 → 按根下钻，取回全部步骤留痕
        final JsonNode roots = runAndSnapshot(session,
                "请调用 subagent_transcript 工具，branch_id 参数用 roots ，include_children 参数用 true。"
                        + "把工具返回的原始内容完整贴出来，不要省略任何字段，不要总结。");
        final JsonNode rootsCall = requireWorkflowRunCallOr(roots, "subagent_transcript");
        final String rootsRaw = rootsCall.path("output").asText();
        Allure.addAttachment("065-S23 roots 出参原文（截断 8KB）", "text/plain", truncate(rootsRaw, 8192));
        // 【2026-10-21 形态改造·补漏】原判据 contains("branch_id=") + firstTokenAfter(…, "branch_id=") 依赖
        // ToolOutput.toString() 的括号文本。subagent_transcript 与 workflow_run **同挂** DaToolResultJsonRail
        // （issue #441 裁 1-B2）⇒ 出参同样是纯 JSON。run-66 实测形态：
        //   {"roots":[{"branch_id":"r1/b-1","phases":[…],"terminal_reason":"PASSED","workflow_id":"wf-…"}, …]}
        // 首轮改造只改了 base 里 workflow_run 的解析器，**漏了本用例自己的内联锚点** ⇒ run-66 在此假失败
        // （批次记录 §7BG.2）。**语义判据一字不改**：仍是"至少一个执行根 + 取其 branch_id 作下钻入参"。
        // 【2026-10-26 判据按 docs#31 裁决 1 的分区语义重写（经用户授权）】
        // 原主断言是「下钻**一个**执行根应取回**两次运行合计**的步骤留痕（≥ 6 条）」，run-74 实测只取回 4 条
        // （根 r1/b-1 + 该次运行的 3 步）。**这不是产品缺陷**：docs#31 裁决 1（comment 194890332）明写
        // 「`r<n>` = **运行序号**（按宿主会话 owner 独立编号）…不同运行/不同会话的同号分支**不再碰撞**
        // （RunTranscripts 按 owner 分区、每 owner 独立编号）」⇒ 每次运行**独立成根**，
        // 下钻某个根只返回该次运行的留痕是**正确行为**。
        // 而 #457（!880）修复落地后出参才带上 `r<n>/` 前缀，这一分区语义**第一次真正可观测**——此前裸值 +
        // `locate` 的"最新 run 优先"宽容解析把两次运行混在一起，反而看不出分区。
        // ⇒ 原判据隐含假设"两次运行共用一棵树"，与裁决相悖，属**我方判据过度指定**。
        // 改法（**总量要求一点没降，反而多验一条**）：
        //   ① roots 必须列出**两次运行**（≥2 个执行根、`r<n>/` 前缀互不相同、workflow_id 互不相同、
        //      phases 分别含两次运行的 question 标记）——REQ-029「跨多次执行**累积可查**」的直接判据
        //   ② **分别**下钻每个根，各取回该次运行的步骤留痕（含 children/path_to_root/depth 三项详情）
        //      ——REQ-031「宿主可**按执行根**查询全部步骤留痕」的直接判据
        //   ③ 两次下钻取回的分支数**合计** ≥ 两次运行的步骤留痕之和（原有总量要求，原样保留）
        final JsonNode rootsJson = parseJsonObject(rootsRaw, "subagent_transcript 的 roots 出参应是 JSON 对象");
        final JsonNode rootList = rootsJson.path("roots");
        assertThat(rootList.isArray())
                .as("roots 出参应含 roots[] 数组；实收=%s", truncate(rootsRaw, 1024)).isTrue();

        // ---- ① 累积可查：两次运行各自成根、可同时列出、且可区分 ----
        assertThat(rootList)
                .as("应列出**两次运行**的执行根（REQ-029 跨多次执行累积可查）；实收=%s", truncate(rootsRaw, 2048))
                .hasSizeGreaterThanOrEqualTo(2);
        final Set<String> prefixes = new LinkedHashSet<>();
        final Set<String> workflowIds = new LinkedHashSet<>();
        for (JsonNode root : rootList) {
            final String bid = root.path("branch_id").asText("");
            assertThat(bid).as("每个执行根应带非空 branch_id；实收=%s", truncate(rootsRaw, 1024)).isNotBlank();
            final int slash = bid.indexOf('/');
            assertThat(slash)
                    .as("执行根 branch_id 应为 r<n>/b-<m> 限定形态（docs#31 裁决 1、#457 修复后的对外承诺）；实收=%s",
                            bid)
                    .isPositive();
            prefixes.add(bid.substring(0, slash));
            final String wf = root.path("workflow_id").asText("");
            if (!wf.isBlank()) {
                workflowIds.add(wf);
            }
        }
        assertThat(prefixes).as("两次运行的执行根前缀 r<n> 应互不相同（同号分支不碰撞）；实收=%s", prefixes)
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(workflowIds).as("两次运行应各有独立的 workflow_id；实收=%s", workflowIds)
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(rootsRaw)
                .as("roots 的 phases 应分别含两次运行的 question 标记（证明两次都在、且彼此可区分）；实收=%s",
                        truncate(rootsRaw, 2048))
                .contains(q1).contains(q2);

        // ---- ①-b【2026-10-27 增量审视新增·FEAT-067 REQ-057】成员运行期的实际工具面必须落在只读清单内 ----
        // Oracle：FEAT-067:50（MUST「成员与委托只读工具」，14 件）+ :51（MUST NOT「写工具与编排六件」）
        //         @docs main@65118fda（!379）。证据面＝subagent_transcript 的 roots 出参里每个成员的
        //         `last_tool`（末次调用的工具名）与 `tool_call_count`。
        // 为什么放在本用例：本用例是台账里唯一会取到 `members[]` 明细的端到端场景；067-S14 只取 workflow_run
        // 的 steps[]（不含工具面）。跨特性共担已在台账 REQ-057 行登记（证据车＝065-S23）。
        // 静态面（清单逐字一致 + 红线互斥 + 无 caller 不挂工具 + 范围收敛四象限）由 067-S28/S29/S30 承接；
        // 本条只补"运行期确实挂上了、且只用清单内的工具"这一层，两者不互相替代。
        final Set<String> observedTools = new LinkedHashSet<>();
        int memberCount = 0;
        long toolCallsTotal = 0;
        for (JsonNode root : rootList) {
            for (JsonNode member : root.path("members")) {
                memberCount++;
                final String lastTool = member.path("last_tool").asText("");
                if (!lastTool.isBlank() && !"null".equals(lastTool)) {
                    observedTools.add(lastTool);
                }
                toolCallsTotal += member.path("tool_call_count").asLong(0);
            }
        }
        Allure.addAttachment("065-S23/067-S31 成员运行期工具面（roots.members 汇总）", "text/plain",
                "成员留痕条数=" + memberCount + "\n工具调用合计=" + toolCallsTotal
                        + "\n出现过的 last_tool=" + observedTools
                        + "\n文档 14 件（FEAT-067:50@65118fda）=" + MEMBER_READ_ONLY_TOOLS
                        + "\nMUST NOT 10 件（FEAT-067:51）=" + MEMBER_FORBIDDEN_TOOLS);
        assertThat(memberCount).as("roots 出参应带成员留痕（否则本条判据无从评估）").isPositive();
        assertThat(observedTools)
                .as("FEAT-067:51（MUST NOT）：成员运行期**绝不得**调用写工具/执行沙箱工具/编排六件；实收工具面=%s",
                        observedTools)
                .doesNotContainAnyElementsOf(MEMBER_FORBIDDEN_TOOLS);
        assertThat(observedTools)
                .as("FEAT-067:50（MUST）：成员运行期用到的工具必须都在 14 件只读清单内；实收=%s", observedTools)
                .isSubsetOf(MEMBER_READ_ONLY_TOOLS);
        assertThat(toolCallsTotal)
                .as("成员应**真的用到**了挂载的工具（tool_call_count 合计 > 0）——否则『挂了 14 件』只是声明、"
                        + "运行期无从证明；实收合计=%s", toolCallsTotal)
                .isPositive();

        // ---- ② + ③ 分别下钻每个执行根，各取回该次运行的留痕，并核合计 ----
        final int expected = firstSteps.size() + secondSteps.size();
        int total = 0;
        int drilled = 0;
        for (JsonNode root : rootList) {
            final String rootId = root.path("branch_id").asText("");
            final JsonNode drill = runAndSnapshot(session,
                    "请调用 subagent_transcript 工具，branch_id 参数用 " + rootId
                            + " ，include_children 参数用 true。把工具返回的原始内容完整贴出来，不要省略任何字段。");
            final JsonNode drillCall = requireWorkflowRunCallOr(drill, "subagent_transcript");
            final String drillRaw = drillCall.path("output").asText();
            Allure.addAttachment("065-S23 按根下钻出参原文（" + rootId + "，截断 16KB）", "text/plain",
                    truncate(drillRaw, 16384));
            // 分支计数：递归统计 JSON 里 branch_id 字段的出现次数（不受转义与嵌套层级影响）
            final int branches = countBranchIdFields(
                    parseJsonObject(drillRaw, "subagent_transcript 的下钻出参应是 JSON 对象"));
            assertThat(branches)
                    .as("按执行根 %s 下钻应能取回该次运行的步骤留痕（含根本身，≥ 2 条）；实收 %s 条；下钻出参=%s",
                            rootId, branches, truncate(drillRaw, 2048))
                    .isGreaterThanOrEqualTo(2);
            assertThat(drillRaw)
                    .as("下钻结果应含 children 与回溯路径/深度（Feature §2 的三项详情）；根=%s；实收=%s",
                            rootId, truncate(drillRaw, 2048))
                    .contains("\"children\"").contains("\"path_to_root\"").contains("\"depth\"");
            total += branches;
            drilled++;
        }
        assertThat(total)
                .as("两次运行**分别**下钻后合计取回的分支数应 ≥ 两次运行的步骤留痕之和（≥ %s 条，实收 %s 条，"
                                + "下钻了 %s 个执行根）——REQ-029「跨多次执行累积可查」的总量判据，"
                                + "与改造前**等量**，只是按 docs#31 裁决 1 的分区语义分别取",
                        expected, total, drilled)
                .isGreaterThanOrEqualTo(expected);
        Allure.addAttachment("065-S23 判据③ 合计核对", "text/plain",
                "两次运行步骤留痕之和 expected=" + expected
                        + "\n分别下钻合计 total=" + total
                        + "\n下钻的执行根数=" + drilled
                        + "\nroots 前缀=" + prefixes
                        + "\nworkflow_id=" + workflowIds);
    }

    /** 从 toolCalls 里取指定工具的调用记录；未调用即中止为 INCONCLUSIVE（模型未配合）。 */
    private JsonNode requireWorkflowRunCallOr(final JsonNode snapshot, final String toolName) {
        failIfTaskFailed(snapshot);
        for (JsonNode call : snapshot.path("toolCalls")) {
            if (toolName.equals(call.path("toolName").asText()) && call.path("output").asText().length() > 0) {
                return call;
            }
        }
        org.junit.jupiter.api.Assumptions.abort("主 Agent 本轮未调用 " + toolName
                + "（模型未配合，属 L3 不确定性）⇒ 按设计记 INCONCLUSIVE，不判产品 FAIL；恢复动作 = 重试或强化指令语料。");
        throw new IllegalStateException("unreachable");
    }

    /**
     * 递归统计 JSON 里 {@code branch_id} 字段的出现次数。
     *
     * <p>替代旧的 {@code countOccurrences(drillRaw, "branch_id=")} 文本计数——同语义（数"有多少条带
     * branch_id 的留痕"），但不再依赖 {@code ToolOutput.toString()} 的括号文本形态。
     * 对对象与数组统一用 {@code for (JsonNode child : node)} 下钻（Jackson 对二者都迭代其子节点）。
     */
    private static int countBranchIdFields(final JsonNode node) {
        if (node == null) {
            return 0;
        }
        int count = node.isObject() && node.has("branch_id") ? 1 : 0;
        for (JsonNode child : node) {
            count += countBranchIdFields(child);
        }
        return count;
    }

    private static String readClasspath(final String path) throws Exception {
        try (InputStream in = DaOrchestrationRealMemberE2EIT.class.getClassLoader()
                .getResourceAsStream(path)) {
            assertThat(in).as("classpath 资源应存在：%s", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

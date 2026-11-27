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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 066-S16：真实 LLM 成员的三视角六标记汇聚（L2 端到端，**补开发自陈缺口**）。
 *
 * <p>Oracle 来源：FEAT-066 §2 + §4；L2-066 §7.2 自陈「审计收六份产物（三视角+三互审）——
 * **执行级背书为双视角四标记 UT；六份为结构断言+投影机制推导**」⇒ 本场景是**新增证据**，
 * 不是与 066-S06（L1 双视角四标记）重复。
 *
 * <p>五项 Then：① {@code status=completed}；② {@code steps[]} 条数 == **7**（3 视角 + 3 互审 + 1 审计）；
 * ③ 每步 {@code member} 标识形态正确（视角 id / {@code review_<视角id>} / 审计 id）；
 * ④ {@code final_output} 的**收口来源**按权威口径分流（见下）；
 * ⑤ 与 066-S05 的 **L1 编译产物结构逐项对照一致**（分层、依赖集、登记序）。
 *
 * <p><b>【2026-10-26 判据④ 改判，经用户授权】原写法「每步必有非空产物 + final_output == audit 产物且非空」
 * 属测试侧过度指定</b>。权威口径（改判当时为 docs `main@192d0b9d`；**2026-10-28 起为 `main@bd6ac83f`**，
 * 即 `!383` 修订后的现文——见下条括注）：
 * <ul>
 *   <li>`FEAT-066-cross-validation-audit.md:39`（MUST 行「最终答案收口」）。**现文（`!383` 后，
 *       `main@bd6ac83f`）**：「蓝图最终产物=审计步产物;**审计步不可跳过**;审计产物缺席(空白,判据同
 *       FEAT-065 §5.1.3)时,最终产物**按 FEAT-065 §2 的结果收口基础规则回退,不另设交叉验证专用口径**」；
 *       旧文（`!383` 前，我方 2026-10-26 改判时的依据）曾写「按 FEAT-065 收口规则**回退为末个非空白步产物**」
 *       ——该措辞与实现不等价，已由我方 `docs#41` 上报、`!383` 修正、2026-10-28 验证关单。
 *       ⇒ 空白审计产物 + 回退是**规格明文规定的合法形态**，不是缺陷；</li>
 *   <li>`FEAT-065-deterministic-blueprint-orchestration.md:45` 结果收口 MUST 行。**现文（`!383` 后）**已把
 *       三分支写明：「按拓扑层逐层收集当前层产物(空白判据见 §5.1.3):当前层产物**全部空白**时保留此前已收集结果;
 *       **恰好一份非空白**产物时直接以该产物原文替换既有收口结果,**不添加标题**;**多份非空白**产物时按步骤声明序
 *       拼接为 `## <步骤id>` 分节并替换既有收口结果」（旧文只写「步骤产物缺席**不覆盖既有收口产物**」，
 *       未定义多步层形态）；§5.1.3：「产物为 null、空串或仅含空白字符(isBlank)即缺席」；
 *       L2 侧同口径：`Feat-Func-065-…md:87`（三分支）/`:132`/`:221`、`Feat-Func-066-…md:93`（引用 FEAT-065 §2.3）；</li>
 *   <li>规格**没有任何一处**要求"每个视角/互审步必有非空产物"⇒ 该断言无出处，删。</li>
 * </ul>
 * 改后判据④＝**分流**：audit 非空白 ⇒ `final_output` 必须严格来自 audit（A 档，原判据原样保留）；
 * audit 空白 ⇒ `final_output` 必须回退为**上一层的既有收口产物**（B 档，按 FEAT-065:45 现文三分支：
 * 该层恰好一份非空白 ⇒ 即该步产物原文、不加标题；多份非空白 ⇒ 按声明序 `## <步骤id>` 分节聚合；
 * 该层全空 ⇒ 沿用更上一层），且 audit 步仍须在 `steps[]` 里
 * 以 `skipped=false` + 空白 output **可见**（由判据②③ 的条数/登记序/skipped 断言共同保证，可观测性不丢）；
 * 7 步全空白 ⇒ 无从评估收口来源，判 **INCONCLUSIVE**（不判产品 FAIL）。
 * 空白步的**数量与位置**一律写进 Allure 附件留痕，不静默放过——它虽不违反契约，但会削弱
 * `FEAT-066:38`「审计员输入含全体产物原文投影」的证据强度，属需持续观察项。
 * <p>背景（不是判据依据，只说明为什么会空白）：成员单次模型调用按 `FEAT-067:38` MUST 行的口径钉死
 * `maxTokens 4000` 且走非流式；推理模型可能把预算全耗在思维链上（run-76 实证：上游
 * `finish_reason=length`、`completion_tokens=4000`、`reasoning_tokens=3960`、`content` 为空），
 * 该截断信号在 core 非流式路径被合成成 `stop` 并报 `status=success`——由 `agent-core-java#199` 跟踪。
 *
 * <p><b>图纸用显式 {@code cross_validation} 直给，不用 {@code multi-perspective-council} 模板</b>：
 * 模板**内容**正确性属范围外（变更包范围卡 §4.1「归后续模板库特性」，现已立为 FEAT-068），
 * 用模板会把"模板内容对不对"混进本用例的判据；直给图纸则条数与成员形态完全由本用例控制。
 * 设计 §6 明文允许「模板 {@code multi-perspective-council}（Feature §4 明写"实装即 cross_validation 形态"）
 * **或等价的三视角 + review: true + 审计图纸直给**」。
 *
 * <p>不应断言：审计结论的**质量或正确性**（FEAT-066 §5.2 明文不承诺 ⇒ 判 FAIL 即误判）；
 * **不得断言"检索命中了"**（真实模型输出不可控，且工作区/知识库可用性随环境变化）；
 * 【2026-10-27 更正·docs !379】原文写的是「不得断言"检索发生了"（L2-067 §1.3：成员是裸 ReActAgent、无工具）」——
 * 该前提已失效：FEAT-067:50 现为 MUST，成员按发起方会话范围挂 **14 件只读工具**（含 grep/glob/read_file/kb_search 等）。
 * 因此"检索**发生**了"现在是**可观测且应验**的（由 067-S31 断言 members[].tool_call_count 合计 > 0、last_tool ⊆ 14 件）；
 * 本用例仍**不**断言检索是否命中、也不断言审计结论质量（FEAT-066 §5.2 明文不承诺）。
 * **不断言六份产物在审计输入里逐字可见**——真实模型输出不可控，L2 层的汇聚证据以
 * "条数 + 成员形态 + 收口来源"为主，逐字原文断言只在 L1（066-S06）做。
 *
 * <p>INCONCLUSIVE 与 FAIL 分开：主 Agent 未调用 {@code workflow_run} ⇒ INCONCLUSIVE；
 * 调用了但条数/成员形态/收口来源不符 ⇒ **FAIL**（真实成员下编译或调度变形）。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-066")
@Feature("FEAT-066: 交叉验证与汇总审计")
class DaCrossValidationRealMemberE2EIT extends DaOrchestrationE2EBase {

    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    /**
     * 用**实装模板** {@code multi-perspective-council} 而不是内联 YAML。
     *
     * <p>设计 §6 明文允许两种构造：「模板 {@code multi-perspective-council}（Feature §4 明写
     * "实装即 {@code cross_validation} 形态"）**或**等价的三视角 + {@code review: true} + 审计图纸直给」。
     * run-16 用内联 YAML 实测失败于 {@code workflow_run failed: missing top-level 'blueprint' mapping}
     * ——即**模型没有原样转述 YAML**，属"模型未配合"（INCONCLUSIVE 族），不是产品问题也不是判据问题。
     * 改用模板可彻底绕开这个不确定性；且该模板本身就是 3 视角 + {@code review: true} + 审计
     * ⇒ 编译产物同为 **7 步**，判据（条数/成员形态/收口来源/L1 结构对照）**一字未改**。
     */
    private static final String TEMPLATE = "multi-perspective-council";

    @Test
    @Story("066-S16: 真实 LLM 成员三视角六标记汇聚")
    @DisplayName("066-S16 三视角+互审+审计：steps==7、成员形态正确、final_output 收口来源按 FEAT-066:39 分流（audit 非空白=严格相等 / 空白=回退末个非空白步），且与 L1 编译产物结构一致")
    void realMemberCrossValidationAggregatesSevenSteps() throws Exception {
        final String question = "用多视角会商分析：一句话说明团队协作的价值。U-066-S16-" + RUN;
        final String input = "请调用 workflow_run 工具，template 参数用 " + TEMPLATE
                + " ，question 参数用：" + question
                + " 。不要自己作答，必须调用该工具；工具返回后只回复 final_output 的原文。";

        final JsonNode snapshot = runAndSnapshot(newSession("sess-066-s16"), input);
        failIfTaskFailed(snapshot);
        final JsonNode call = requireWorkflowRunCall(snapshot);
        final String raw = requireSuccessOutput(call);

        // ① status=completed
        assertThat(extractStatus(raw)).as("① 出参 status 应为 completed；实收=%s", truncate(raw, 2048))
                .isEqualTo("completed");
        assertThat(extractWorkflowId(raw)).as("workflow_id 应非空").isNotBlank();

        // ②③ 条数与成员形态：**以模板图纸的 L1 编译产物为基准**（不硬编码 7 与成员名）
        final BlueprintSpec templateSpec = BlueprintLoader.load(readTemplate(TEMPLATE));
        final List<String> expectedIds = templateSpec.steps().stream().map(StepSpec::id).toList();
        final List<String> expectedMembers = templateSpec.steps().stream().map(StepSpec::member).toList();
        final List<Step> steps = extractSteps(raw);
        assertThat(steps).as("② steps 条数应等于模板编译产物的步数 %s（3 视角 + 3 互审 + 1 审计）"
                + "——真实成员下编译或调度变形即不符", expectedIds.size()).hasSize(expectedIds.size());
        final List<String> stepIds = new ArrayList<>();
        final List<String> members = new ArrayList<>();
        final List<String> blankSteps = new ArrayList<>();
        for (Step step : steps) {
            assertThat(step.id()).as("每步应带步骤标识").isNotBlank();
            assertThat(step.member()).as("每步应带成员标识").isNotBlank();
            assertThat(step.skipped())
                    .as("编译产物 skipIf 恒规范化为 NEVER ⇒ 步骤 %s 不应被跳过", step.id()).isFalse();
            assertThat(step.branchId()).as("每步 branch_id 应非空（#33 V3）").isNotBlank();
            // 【2026-10-26 改判】此处原为 `assertThat(step.output()).isNotBlank()`（"每步应有真实模型产物"）。
            // 该断言**无权威出处**：FEAT-065/066 只定义"产物缺席(空白)"的判据与后果（不覆盖既有收口产物、
            // 审计空白则回退），从未要求每步必有非空产物 ⇒ 属测试侧过度指定，改为**观察项**计数并留痕。
            if (blank(step.output())) {
                blankSteps.add(step.id());
            }
            stepIds.add(step.id());
            members.add(step.member());
        }
        assertThat(stepIds).as("③ 步骤标识与登记序应为 p_（视角声明序）→ r_（视角声明序）→ audit")
                .containsExactlyElementsOf(expectedIds);
        assertThat(members).as("③ 成员标识形态应为 视角 id / review_<视角id> / 审计 id")
                .containsExactlyElementsOf(expectedMembers);

        // ④ final_output 的收口来源：按 FEAT-066:39 + FEAT-065 结果收口 MUST **分流**（2026-10-26 改判，经用户授权）
        final Step auditStep = steps.get(steps.size() - 1);
        assertThat(auditStep.id()).as("末步应是 audit（唯一汇点）").isEqualTo("audit");
        assertThat(auditStep.skipped())
                .as("④ FEAT-066:39 MUST「审计步不可跳过」——空白产物可以，跳过不行").isFalse();
        Allure.addAttachment("空白产物步（观察项，不判 FAIL）", "text/plain",
                blankSteps.isEmpty() ? "无（7 步全部产出非空产物）"
                        : "共 " + blankSteps.size() + " 步产物空白：" + blankSteps
                        + "；契约依据 FEAT-065 §5.1.3（null/空串/isBlank 即缺席）与 FEAT-066:39（空白审计产物触发回退），"
                        + "空白本身不违反契约，但会削弱 FEAT-066:38「审计员输入含全体产物原文投影」的证据强度，属持续观察项。");
        final Step closing;
        final String branch;
        if (!blank(auditStep.output())) {
            closing = auditStep;
            branch = "A 档：审计产物非空白 ⇒ final_output 必须严格来自 audit 步（FEAT-066:39 主句，原判据原样保留）";
            // 【2026-10-21 形态改造】结构化 + 截断感知比对（三档）；语义判据不降。
            assertFinalOutputMatchesLastStep(raw, closing);
        } else {
            // ================= B 档（2026-10-28 重写）=================
            // 🔴 原实现按 FEAT-066:39 的**字面**「回退为末个非空白步产物」去比，run-98（r7）因此**假失败**：
            //    实测 audit 空白时 final_output 是**上一层（互审层，恒 3 步）的既有收口产物**，即产品
            //    `BlueprintScheduler.collectResults` 对多步层的 `## <步骤id>` 分节聚合（!879 起的明文语义，
            //    代码注释「council / parallel 不能只取最后一个成员的输出」），而不是"末个非空白步"单步产物。
            //    两种说法在**单步层**上等价、在**多步层**上不等价 ⇒ FEAT-065「结果收口」MUST 行的措辞
            //    （「步骤产物缺席(空白)**不覆盖既有收口产物**」）才与实现一致；FEAT-066:39 的措辞需修正
            //    （已作为文档精度问题另行上报，见批次记录 §7BZ）。
            //    ✅【2026-10-28 已闭环】该文档缺口由 `docs#41` 上报、docs `!383`（`main@bd6ac83f`）修正：
            //    FEAT-065:45 / L2-065:87,:132,:221 现已明文写出三分支，FEAT-066:39 / L2-066:93 改为引用
            //    FEAT-065 §2 基础规则，全仓 `grep "末个非空白|末个非空步"` 命中 0；`docs#41` 已验证关单。
            //    ⇒ 本判据的出处引用现指向**文档现文**（不再只靠实现与代码注释），判据本身一字未改。
            //    本判据改按 FEAT-065 的权威措辞写，并按产品的单步/多步两种形态分流——**强度不降**：
            //    单步层仍要求严格相等；多步层要求分节 id 全部来自该层非空白步、首节为登记序第一步、
            //    且首节正文与该步 output 的 inline 正文逐字一致（前 300 字，两侧均可能被 OutputArchive 截断）。
            final List<Step> preceding = new ArrayList<>();
            for (int i = 0; i < steps.size() - 1; i++) {
                if (!blank(steps.get(i).output())) {
                    preceding.add(steps.get(i));
                }
            }
            if (preceding.isEmpty()) {
                Allure.addAttachment("判据④ 无法评估（audit 与其之前所有步产物全空白）", "text/plain", truncate(raw, 8192));
                org.junit.jupiter.api.Assumptions.abort("066-S16 判据④ INCONCLUSIVE：audit 及其之前所有步产物全部空白，"
                        + "无从评估收口来源。成员模型面未产出，属 L3 不确定性，不判产品 FAIL。");
                return;
            }
            final Step lastNonBlank = preceding.get(preceding.size() - 1);
            final String groupPrefix = layerPrefix(lastNonBlank.id());
            final List<Step> group = new ArrayList<>();
            for (Step candidate : preceding) {
                if (layerPrefix(candidate.id()).equals(groupPrefix)) {
                    group.add(candidate);
                }
            }
            closing = lastNonBlank;
            if (group.size() == 1) {
                branch = "B 档·单步层：审计产物空白 ⇒ final_output 回退为该层唯一非空白步（" + lastNonBlank.id()
                        + "）的产物（FEAT-065 结果收口 MUST；产品对单步层直接返回该步产物、不带 ## 头）";
                assertFinalOutputMatchesLastStep(raw, lastNonBlank);
            } else {
                branch = "B 档·多步层：审计产物空白 ⇒ final_output 回退为**上一层的既有收口产物**，即该层 "
                        + group.size() + " 个非空白步按 `## <步骤id>` 分节的聚合（FEAT-065 结果收口 MUST + 产品 collectResults 多步层语义）";
                final String finalOutput = extractFinalOutput(raw);
                assertThat(finalOutput).as("④ B 档下 final_output 不应为空（回退必须给出既有收口产物）").isNotBlank();
                final List<String> sectionIds = new ArrayList<>();
                final Matcher sectionMatcher = Pattern.compile("(?m)^## (\\S+)\\s*$").matcher(finalOutput);
                while (sectionMatcher.find()) {
                    sectionIds.add(sectionMatcher.group(1));
                }
                final List<String> groupIds = new ArrayList<>();
                for (Step member : group) {
                    groupIds.add(member.id());
                }
                assertThat(sectionIds)
                        .as("④ B 档多步层：final_output 应是 `## <步骤id>` 分节聚合；实收分节=%s，该层非空白步=%s，"
                                + "final_output 前 300 字=%s", sectionIds, groupIds,
                                finalOutput.substring(0, Math.min(300, finalOutput.length())))
                        .isNotEmpty();
                assertThat(sectionIds)
                        .as("④ 分节 id 必须全部来自审计步之前、同一层（前缀 `%s`）的非空白步；实收=%s，允许集合=%s",
                                groupPrefix, sectionIds, groupIds)
                        .isSubsetOf(groupIds);
                assertThat(sectionIds.get(0))
                        .as("④ 第一节应是该层登记序上的第一个非空白步（%s）——回退不得错位到更早的层", groupIds.get(0))
                        .isEqualTo(groupIds.get(0));
                final Step firstStep = group.get(0);
                final int headerAt = finalOutput.indexOf("## " + firstStep.id());
                final int lineEnd = finalOutput.indexOf('\n', headerAt);
                final String sectionBody = (lineEnd < 0 ? "" : finalOutput.substring(lineEnd + 1)).stripLeading();
                final String expectBody = firstStep.inlineBody();
                final int comparable = Math.min(300, Math.min(sectionBody.length(), expectBody.length()));
                assertThat(comparable)
                        .as("④ 第一节正文与该步 output 的 inline 正文应有可比重叠（≥50 字）；实收 节正文 %d 字 / 步 inline %d 字",
                                sectionBody.length(), expectBody.length())
                        .isGreaterThanOrEqualTo(50);
                assertThat(sectionBody.substring(0, comparable))
                        .as("④ 第一节（%s）正文前 %d 字应与该步 output 的 inline 正文**逐字一致**"
                                + "（两侧均可能被 OutputArchive 截断，故比公共前缀）", firstStep.id(), comparable)
                        .isEqualTo(expectBody.substring(0, comparable));
                Allure.addAttachment("判据④ B 档·多步层 分节核对", "text/plain",
                        "该层前缀=" + groupPrefix + "\n该层非空白步（登记序）=" + groupIds
                                + "\nfinal_output 实收分节=" + sectionIds
                                + "\n首节=" + sectionIds.get(0) + "（应为 " + groupIds.get(0) + "）"
                                + "\n首节正文与该步 inline 逐字比对字数=" + comparable
                                + "\nfinal_output 长度=" + finalOutput.length()
                                + "\nfinal_output 前 200 字=" + finalOutput.substring(0, Math.min(200, finalOutput.length())));
            }
        }
        Allure.addAttachment("判据④ 收口来源分流", "text/plain",
                branch + "\naudit.output 空白=" + blank(auditStep.output())
                        + "\n收口来源步=" + closing.id() + "\naudit.skipped=" + auditStep.skipped()
                        + "\n空白步=" + blankSteps);

        // ⑤ 与 L1 编译产物结构逐项对照一致（066-S05 的同一张图纸）
        final BlueprintSpec spec = templateSpec;
        assertThat(spec.steps()).as("L1 对照侧编译产物应为 7 步").hasSize(7);
        assertThat(stepIds).as("⑤ L3 步骤标识应与 L1 编译产物逐项一致")
                .containsExactlyElementsOf(spec.steps().stream().map(StepSpec::id).toList());
        assertThat(members).as("⑤ L3 成员标识应与 L1 编译产物逐项一致")
                .containsExactlyElementsOf(spec.steps().stream().map(StepSpec::member).toList());
        final Map<String, SubAgentExecutor> bindings = new LinkedHashMap<>();
        for (var member : spec.members()) {
            final String id = member.id();
            bindings.put(id, (userInput, subGoal) -> "L1-066-S16-" + id + "-" + RUN);
        }
        final BlueprintResult l1;
        try (BlueprintScheduler scheduler = new BlueprintScheduler(new TraceForest(), 4)) {
            l1 = scheduler.execute(spec, bindings, question);
        }
        final List<StepOutcome> l1Outcomes = l1.stepOutcomes();
        assertThat(l1Outcomes).as("L1 对照侧应同样产出 7 步留痕").hasSize(7);
        assertThat(l1Outcomes).extracting(StepOutcome::stepId)
                .as("⑤ L1 登记序应与 L3 一致").containsExactlyElementsOf(stepIds);
        assertThat(l1Outcomes).extracting(StepOutcome::memberId)
                .as("⑤ L1 成员序应与 L3 一致").containsExactlyElementsOf(members);
        // L1 对照侧用的是确定性执行体（每步必产出标记串），故 L1 的收口恒为 audit 步产物；
        // L3 侧则按判据④ 的分流结果取收口来源步。两侧**结构**（分层/依赖集/登记序/收口来自唯一汇点）对照一致，
        // output **内容层**允许差异（真实模型 vs 确定性标记串）。
        assertThat(l1.finalOutput()).as("⑤ L1 对照侧收口来源应是 audit 步产物")
                .isEqualTo(l1Outcomes.get(6).output());
        assertThat(closing.output())
                .as("⑤ L3 的收口来源步产物来自真实模型，不应等于 L1 的确定性标记串")
                .isNotEqualTo(l1Outcomes.get(6).output());
        Allure.addAttachment("L1 对照侧结构导出（066-S05 同图纸）", "text/plain",
                String.join("\n", l1Outcomes.stream()
                        .map(o -> o.stepId() + "|" + o.memberId() + "|skipped=" + o.skipped()).toList()));
        Allure.addAttachment("L3 steps[] 原文（截断 8KB）", "text/plain", truncate(raw, 8192));
    }

    /**
     * 产物缺席判据——逐字对齐 `FEAT-065-deterministic-blueprint-orchestration.md` §5.1.3：
     * 「产物为 null、空串或仅含空白字符(isBlank)即缺席」。
     */
    /**
     * 取步骤 id 的**层前缀**（第一个下划线之前的部分）：`p_risk`/`r_risk` → `p`/`r`，`audit` → `audit`。
     * 用于在 B 档里圈出"审计步之前、同一层"的非空白步——产品的收口回退是**按层**的
     * （{@code BlueprintScheduler.collectResults}：层内多步有产出则按 {@code ## <步骤id>} 分节聚合，
     * 单步则直接返回该步产物），不是"全文最后一个非空白步"。
     */
    private static String layerPrefix(final String stepId) {
        final int at = stepId.indexOf('_');
        return at < 0 ? stepId : stepId.substring(0, at);
    }

    private static boolean blank(final String text) {
        return text == null || text.isBlank();
    }

    /** 从制品内读模板资产（不硬编码模板内容，条数与成员形态都以装载产物为基准）。 */
    private static String readTemplate(final String name) throws Exception {
        try (java.io.InputStream in = DaCrossValidationRealMemberE2EIT.class.getClassLoader()
                .getResourceAsStream("workflow-templates/" + name + ".yaml")) {
            assertThat(in).as("模板资产应存在于制品内：workflow-templates/%s.yaml", name).isNotNull();
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}

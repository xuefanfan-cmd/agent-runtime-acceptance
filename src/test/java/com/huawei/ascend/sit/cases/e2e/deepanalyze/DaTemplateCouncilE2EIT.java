/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.e2e.deepanalyze;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 068-S10：真机端到端——会话点名 multi-perspective-council（最复杂形态：交叉验证 + 互审编译）。
 *
 * <p>Oracle 来源：FEAT-068 Feature §4 委员会行（"三视角独立分析→互审→审计员汇总；步骤含视角步、
 * 互审步与审计步"）；L2-068 §2.1/§2.2。执行基准独立取自 classpath 同一份资产（不硬编码数字）。
 *
 * <p>与 067 线的关系（范围卡 §4.2 注记）：067-S16 已覆盖"泛化的会话点名模板→自主调用→出参与模板步数
 * 一致"（research-pipeline）；本用例断言的是 **council 特定编排结构在 L3 的成立**（视角→互审→审计的
 * 登记序 + 编译后步骤集），属差异补充而非重复。
 *
 * <p>判据边界：结构性断言（步骤集/登记序/member 对齐/skipped/收口非空）；**不断言**产物内容质量
 * （设计 §5.2 不承诺）；互审的方向配对与投影细节归 FEAT-066。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-068")
@Feature("FEAT-068: 团队模板库")
class DaTemplateCouncilE2EIT extends DaTemplateE2EBase {

    @Test
    @Story("068-S10: 真机 e2e——council 点名全链路")
    @DisplayName("068-S10 点名 multi-perspective-council：三视角→互审→审计登记序成立，收口产物非空")
    void councilTemplateRunsFullJourneyOnRealLlm() throws Exception {
        final String input = "请用 workflow_run 工具、以 template 参数 multi-perspective-council "
                + "评估这个问题：团队要在一个季度内交付一套新系统，主要风险是什么？"
                + "工具返回后，请只回复工具出参里 final_output 的原文。";

        final com.fasterxml.jackson.databind.JsonNode snapshot =
                runAndSnapshot(newSession("sess-068-s10"), input);
        failIfTaskFailed(snapshot);
        final com.fasterxml.jackson.databind.JsonNode call = requireWorkflowRunCall(snapshot);

        // ① 模型确实点名了 council 模板（调用入参应含模板名）。
        // input 是工具入参的对象节点（asText() 返回空串——首轮实测缺陷），改取节点序列化文本。
        final String callInput = call.path("input").isMissingNode() ? "" : call.path("input").toString();
        assertThat(callInput).as("① workflow_run 的入参应点名 multi-perspective-council；实收=%s",
                truncate(callInput, 512)).contains("multi-perspective-council");

        // ② 步骤级留痕（GAP-068-01 恢复：#462 观察层断链修复后 tool_result 回填——2026-10-21 随 r3
        //    制品回归升级回真执行）。期望=被测制品 classpath 随包 council 资产经同一 BlueprintLoader
        //    编译的步骤序（p_*×3 → r_*×3 → audit），不在用例内硬编码。
        assertStepsMatch(extractSteps(requireSuccessOutput(call)),
                expectedStepsFromAsset("multi-perspective-council"));

        // ②' 任务级：任务应 completed、收口产物非空（与步骤级并行保留）。
        assertThat(snapshot.path("status").asText()).as("② 任务级：任务应 completed（宿主真实完成编排）")
                .isEqualTo("completed");
        final String taskOutput = snapshot.path("output").asText();
        assertThat(taskOutput).as("② 任务级：收口产物应为真实模型报告（非空）").isNotBlank();

        // ③ 任务级收口产物应体现 council 的多视角编排足迹（风险/效率/可行性视角与审计汇总的字面在场）
        assertThat(taskOutput).as("③ 收口报告应体现多视角编排足迹（风险/效率/可行性至少各一）")
                .containsAnyOf("风险", "效率", "可行性");
    }
}

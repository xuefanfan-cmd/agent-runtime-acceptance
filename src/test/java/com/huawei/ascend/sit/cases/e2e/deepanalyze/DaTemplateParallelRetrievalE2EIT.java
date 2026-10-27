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
 * 068-S11：真机端到端——会话点名 parallel-deep-retrieval（双路并行检索→汇总）。
 *
 * <p>Oracle 来源：FEAT-068 Feature §2 行1（parallel-deep-retrieval 为八套之一，"照各自形态执行"）；
 * L2-068 §2.1（{语义,精确}双路并行→汇总）；对应 L2 §7.1 行8 的同型判据（"双路标记汇聚到汇总员"）。
 *
 * <p>判据边界：结构性断言（步骤集/顺序/双路步与汇总步的产物在场）；不断言检索质量。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-068")
@Feature("FEAT-068: 团队模板库")
class DaTemplateParallelRetrievalE2EIT extends DaTemplateE2EBase {

    @Test
    @Story("068-S11: 真机 e2e——parallel-deep-retrieval 点名全链路")
    @DisplayName("068-S11 点名 parallel-deep-retrieval：双路检索标记汇聚到汇总步，收口产物非空")
    void parallelDeepRetrievalTemplateRunsFullJourneyOnRealLlm() throws Exception {
        final String input = "请用 workflow_run 工具、以 template 参数 parallel-deep-retrieval "
                + "分析这个问题：对比语义检索与精确检索在缺陷定位中的适用场景。"
                + "工具返回后，请只回复工具出参里 final_output 的原文。";

        final com.fasterxml.jackson.databind.JsonNode snapshot =
                runAndSnapshot(newSession("sess-068-s11"), input);
        failIfTaskFailed(snapshot);
        final com.fasterxml.jackson.databind.JsonNode call = requireWorkflowRunCall(snapshot);

        // ① 点名核对（input 为对象节点，取序列化文本——首轮实测缺陷）
        final String callInput = call.path("input").isMissingNode() ? "" : call.path("input").toString();
        assertThat(callInput).as("① workflow_run 的入参应点名 parallel-deep-retrieval；实收=%s",
                truncate(callInput, 512)).contains("parallel-deep-retrieval");

        // ② 步骤级留痕（GAP-068-01 恢复：#462 观察层断链修复后 tool_result 回填——2026-10-21 随 r3
        //    制品回归升级回真执行）。期望=被测制品 classpath 随包资产编译序（semantic→exact→summary，
        //    资产未声明 skip_if ⇒ 三步均不得 skipped），不在用例内硬编码。
        assertStepsMatch(extractSteps(requireSuccessOutput(call)),
                expectedStepsFromAsset("parallel-deep-retrieval"));

        // ②' 任务级：任务应 completed、收口产物非空（与步骤级并行保留）。
        assertThat(snapshot.path("status").asText()).as("② 任务级：任务应 completed（宿主真实完成编排）")
                .isEqualTo("completed");
        final String taskOutput = snapshot.path("output").asText();
        assertThat(taskOutput).as("② 任务级：收口产物应为真实模型报告（非空）").isNotBlank();

        // ③ 任务级收口产物应体现双路检索足迹（语义/精确两条检索路径的字面在场）
        assertThat(taskOutput).as("③ 收口报告应体现双路检索足迹（语义与精确至少各一）")
                .contains("语义").contains("精确");
    }
}

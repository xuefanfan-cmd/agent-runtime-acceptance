/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.deepanalyze_template;

import com.openjiuwen.agents.orchestration.MemberSpec;
import com.openjiuwen.agents.reactrails.loopforest.execution.SubAgentExecutor;
import com.openjiuwen.da.workflow.DaWorkflowRunner;
import com.openjiuwen.da.workflow.WorkflowRunTool;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 068-S08：双入参优先级——blueprint 全文与 template 同给时，**图纸全文优先**，模板名被忽略。
 *
 * <p>Oracle 来源：FEAT-068 Feature §2 行4 / §4 双入参行；L2-068 §2.4（钉子态 :83-89：
 * 仅 blueprint 为空且 template 非空才走模板解析）。白名单/穿越/未知名语义不在本类
 * （由 067 线 067-S09 承接，covered——见范围卡 §4.2）。
 *
 * <p>判据设计：单成员单步图纸（1 步）对 research-pipeline（装载后 3 步）——若模板名影响了执行，
 * 步骤数与步骤 id 必然暴露差异。模板名取**合法且实装**的名字（research-pipeline），
 * 保证"被忽略"不是因为解析失败。
 */
@Tag("component")
@Tag("feat-068")
@Feature("FEAT-068: 团队模板库")
class TemplateResolutionPrecedenceTest {

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @Story("068-S08: 双入参全文优先")
    @DisplayName("068-S08 blueprint 与实装模板名同给：按 blueprint 执行（1 步），research-pipeline 的 3 步不出现")
    void blueprintTextTakesPrecedenceOverTemplateName() {
        final String run = DeepanalyzeTemplateTestSupport.uid();
        final Function<MemberSpec, SubAgentExecutor> factory =
                DeepanalyzeTemplateTestSupport.echoFactory("M-068-S08-" + run);
        final DaWorkflowRunner runner = new DaWorkflowRunner(factory, 4);
        final WorkflowRunTool tool = new WorkflowRunTool(runner);

        final Map<String, Object> args = new LinkedHashMap<>();
        args.put("question", "U-068-S08-" + run);
        args.put("blueprint", DeepanalyzeTemplateTestSupport.singleMemberBlueprint("T-068-S08-" + run, "only-step"));
        args.put("template", "research-pipeline");

        final var out = DeepanalyzeTemplateTestSupport.output(tool.invoke(args, Map.of()));
        final var data = DeepanalyzeTemplateTestSupport.dataOf(out);
        assertThat(data).as("同给双入参应执行成功").containsEntry("status", "completed");

        final var steps = DeepanalyzeTemplateTestSupport.stepsOf(data);
        assertThat(steps).as("应按 blueprint 执行（恰 1 步）；若按模板执行则为 3 步（search/verify/write）")
                .hasSize(1);
        final var stepIds = steps.stream().map(s -> String.valueOf(s.get("id"))).toList();
        assertThat(stepIds).as("执行的步骤应只有 blueprint 声明的 only-step")
                .containsExactly("only-step");
        assertThat(String.join(",", stepIds))
                .as("research-pipeline 的步骤不得出现（模板名被忽略）")
                .doesNotContain("search").doesNotContain("verify").doesNotContain("write");
    }
}

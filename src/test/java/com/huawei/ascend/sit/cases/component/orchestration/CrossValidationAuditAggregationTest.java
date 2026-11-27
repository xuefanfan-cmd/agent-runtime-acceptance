/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.openjiuwen.agents.orchestration.BlueprintResult;
import com.openjiuwen.agents.reactrails.loopforest.observability.TraceForest;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 066-S06：审计员汇聚**双标记**（L1 层，双视角四标记）。
 *
 * <p>Oracle 来源：FEAT-066 §2「审计员汇聚」+ §5.1.2；Feature §6 明文
 * 「双标记断言 = 同时断言审计输入含各**视角产物文本**与**互审产物文本**」。
 *
 * <p>判据：审计员实收输入**同时含 4 个标记**（2 视角 + 2 互审），且每个标记**逐字完整**
 * ——证明引擎把上游产物**原文**投影给审计员，没有做摘要或改写。
 *
 * <p>不应断言：4 段的**顺序**（GAP-066-02 已收窄为 non-gating；§11.2 明确
 * "顺序事实已确定但设计未承诺 ⇒ 标 OUT / non-gating，顺序变化不判 FAIL"）；
 * 不断言有分栏标识或来源标签（L2-066 §1.3 自陈"无结构化分栏"⇒ 要求它会把符合设计的实现判 FAIL）。
 * 六视角/六标记的真实模型形态归 066-S16（端到端，本环境 blocked）。
 */
@Tag("component")
@Tag("feat-066")
@Feature("FEAT-066: 交叉验证与汇总审计")
class CrossValidationAuditAggregationTest {

    private static final String RUN = OrchestrationTestSupport.uid();

    /** 双视角 + review:true + 审计员 ⇒ 5 步（2 视角 + 2 互审 + 1 审计）。 */
    private static final String TWO_PERSPECTIVES_WITH_REVIEW = """
            blueprint:
              id: T-066-S06
              type: cross_validation
              review: true
              perspectives:
                - {id: pa, instruction: "从技术可行性视角作答"}
                - {id: pb, instruction: "从合规风险视角作答"}
              auditor: {id: aud, instruction: "汇总各方结论并给出审计意见"}
            """;

    @Test
    @Story("066-S06: 审计员汇聚双标记（视角产物 + 互审产物）")
    @DisplayName("066-S06 审计员实收输入同时含 2 个视角标记与 2 个互审标记，且逐字完整")
    void auditorReceivesAllUpstreamVerbatim() {
        final var loaded = OrchestrationTestSupport.load(TWO_PERSPECTIVES_WITH_REVIEW, "066-S06", RUN);
        final String perspectiveA = "P-066-S06-A-" + RUN;
        final String perspectiveB = "P-066-S06-B-" + RUN;
        final String reviewA = "R-066-S06-A-" + RUN;
        final String reviewB = "R-066-S06-B-" + RUN;
        loaded.recorders.get("pa").returns(perspectiveA);
        loaded.recorders.get("pb").returns(perspectiveB);
        loaded.recorders.get("review_pa").returns(reviewA);
        loaded.recorders.get("review_pb").returns(reviewB);
        // 审计员把实收输入原文镜像回来，使"汇聚了什么"成为可在产物上逐字核对的事实
        final var auditor = loaded.recorders.get("aud");
        auditor.returnsDynamic(() -> "AUDIT-MIRROR|" + auditor.lastSubGoal());

        final BlueprintResult result = loaded.execute("U-066-S06-" + RUN, 4, new TraceForest());

        assertThat(result.stepOutcomes()).as("双视角 + 双互审 + 审计 = 5 步").hasSize(5);
        assertThat(auditor.calls()).as("审计步应被执行一次").isEqualTo(1);

        final String goal = auditor.subGoal(0);
        final List<String> markers = List.of(perspectiveA, perspectiveB, reviewA, reviewB);
        // 主断言：4 个标记全部命中且**逐字完整**（未被摘要或改写）
        for (String marker : markers) {
            assertThat(goal).as("审计员实收输入应含标记 %s（逐字完整）；实收原文=[%s]", marker, goal)
                    .contains(marker);
            assertThat(goal).as("标记 %s 应恰好出现一次（不得重复投影或截断）", marker)
                    .containsOnlyOnce(marker);
        }
        // 镜像产物同样含 4 个标记 ⇒ 汇聚结果进入了审计员的输入位并可被下游观察
        final String auditOutput = result.stepOutcomes().get(4).output();
        assertThat(auditOutput).as("审计步产物应是其实收输入的镜像").startsWith("AUDIT-MIRROR|");
        for (String marker : markers) {
            assertThat(auditOutput).as("审计步产物应含标记 %s", marker).contains(marker);
        }
        // 登记序：视角层 → 互审层 → 审计（互审层同层按视角声明序）
        assertThat(result.stepOutcomes()).extracting(com.openjiuwen.agents.orchestration.StepOutcome::stepId)
                .containsExactly("p_pa", "p_pb", "r_pa", "r_pb", "audit");
        // OUT / non-gating：顺序事实（按 dependsOn 序）已读码确定，但设计未承诺 ⇒ 只作增强记录，
        // 顺序变化**不判 FAIL**（§11.2 对 066-S06 的更正）
        assertThat(goal.indexOf(perspectiveA)).as("增强证据（non-gating）：视角段应出现在互审段之前")
                .isLessThan(goal.indexOf(reviewA));
    }
}

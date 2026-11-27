/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.openjiuwen.agents.orchestration.BlueprintResult;
import com.openjiuwen.agents.orchestration.StepOutcome;
import com.openjiuwen.agents.reactrails.loopforest.observability.TraceForest;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 066-S14：视角部分缺席（Feature §4 明列场景）。
 *
 * <p>Oracle 来源：FEAT-066 §4「视角部分缺席」+ §5.1.3；FEAT-065 §5.1.3 的 isBlank 统一缺席判据；
 * 已核实编译步 {@code skipIf} 恒为规范化后的 {@code NEVER}（见 066-S05 ⑦）。
 *
 * <p>四项判据：① 缺席视角步 {@code skipped == false}（**视角步无上游，永不因策略跳过**）且产物缺席；
 * ② 留痕仍 7 条齐备；③ 审计员实收输入**不含**缺席视角的投影、**含**其余视角与全部互审标记；
 * ④ 互审步**照常执行**（即使某互审的上游有缺席）。
 *
 * <p>§11.2 更正：{@code "   "}（仅空白）形态**已解禁构造**（GAP-066-05 关闭）；
 * "全部其他视角均空白"的极端变体**也已解禁**（{@code skipIf} 恒 NEVER 已核实）⇒ 追加第 2 个实例。
 *
 * <p>拦住的故障：把"某视角缺席"误判成"该视角被跳过"（会污染留痕语义），
 * 或因一份缺席就让审计员收不到其余全体（过度传播）。
 */
@Tag("component")
@Tag("feat-066")
@Feature("FEAT-066: 交叉验证与汇总审计")
class CrossValidationAbsentPerspectiveTest {

    private static final String RUN = OrchestrationTestSupport.uid();

    private static final String THREE_PERSPECTIVES_WITH_REVIEW = """
            blueprint:
              id: T-066-S14-%s
              type: cross_validation
              review: true
              perspectives:
                - {id: pa, instruction: "从技术可行性视角作答"}
                - {id: pb, instruction: "从合规风险视角作答"}
                - {id: pc, instruction: "从成本收益视角作答"}
              auditor: {id: aud, instruction: "汇总各方结论并给出审计意见"}
            """;

    /** 缺席构造：成员 id → 该视角执行体返回的产物（null 表示按默认标记串返回）。 */
    private record AbsentForm(String label, String idSuffix, Map<String, String> absentOutputs,
            List<String> expectedAbsentPerspectives, List<String> expectedPresentPerspectives,
            String noProjectionReviewer) {
    }

    static Stream<Arguments> absentForms() {
        return Stream.of(
                new AbsentForm("① 单个视角缺席（pa 返回空串）", "one",
                        Map.of("pa", ""),
                        List.of("pa"), List.of("pb", "pc"), null),
                // 极端变体：其余两个视角均缺席（"" 与 "   " 两种形态，§11.2 已解禁）
                new AbsentForm("② 其余视角全部缺席（pb 返回空串、pc 返回仅空白）", "all",
                        Map.of("pb", "", "pc", "   "),
                        List.of("pb", "pc"), List.of("pa"), "review_pa"))
                .map(v -> Arguments.of(v.label(), v));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("absentForms")
    @Story("066-S14: 视角部分缺席")
    @DisplayName("066-S14 缺席视角步不算跳过、留痕齐备、审计员仍收其余全体、互审步照常执行")
    void absentPerspectiveDoesNotSkipAndDoesNotStarveAuditor(final String label, final AbsentForm form) {
        final String yaml = THREE_PERSPECTIVES_WITH_REVIEW.formatted(form.idSuffix());
        final var loaded = OrchestrationTestSupport.load(yaml, "066-S14-" + form.idSuffix(), RUN);
        final Map<String, String> perspectiveMarkers = Map.of(
                "pa", "P-066-S14-pa-" + RUN,
                "pb", "P-066-S14-pb-" + RUN,
                "pc", "P-066-S14-pc-" + RUN);
        final Map<String, String> reviewMarkers = Map.of(
                "review_pa", "R-066-S14-pa-" + RUN,
                "review_pb", "R-066-S14-pb-" + RUN,
                "review_pc", "R-066-S14-pc-" + RUN);
        perspectiveMarkers.forEach((memberId, marker) -> loaded.recorders.get(memberId).returns(marker));
        reviewMarkers.forEach((memberId, marker) -> loaded.recorders.get(memberId).returns(marker));
        loaded.recorders.get("aud").returns("AUDIT-066-S14-" + RUN);
        // 按构造把指定视角改为缺席
        form.absentOutputs().forEach((memberId, output) -> loaded.recorders.get(memberId).returns(output));

        final BlueprintResult result = loaded.execute("U-066-S14-" + form.idSuffix() + "-" + RUN,
                4, new TraceForest());

        // ① 缺席视角步 skipped == false 且产物缺席；在场视角步产物为唯一标记
        final List<StepOutcome> outcomes = result.stepOutcomes();
        assertThat(outcomes).as("%s：3 视角 + 3 互审 + 1 审计 = 7 条留痕齐备", label).hasSize(7);
        assertThat(outcomes).as("%s：本图纸无任何跳过策略 ⇒ 不应有步骤被标记跳过", label)
                .allSatisfy(o -> assertThat(o.skipped())
                        .as("%s：步骤 %s 不应被跳过（视角步无上游，永不因策略跳过）", label, o.stepId())
                        .isFalse());
        for (String absent : form.expectedAbsentPerspectives()) {
            final StepOutcome step = outcomeOf(outcomes, "p_" + absent);
            assertThat(step.outputText()).as("%s：缺席视角 %s 的产物应判缺席", label, absent).isEmpty();
            assertThat(loaded.recorders.get(absent).calls())
                    .as("%s：缺席视角的执行体确实被调用过（是产物为空，不是没跑）", label).isEqualTo(1);
        }
        for (String present : form.expectedPresentPerspectives()) {
            assertThat(outcomeOf(outcomes, "p_" + present).outputText())
                    .as("%s：在场视角 %s 应有产物", label, present).isPresent()
                    .get().asString().contains(perspectiveMarkers.get(present));
        }

        // ④ 互审步照常执行（skipIf 恒 NEVER，即使其上游有缺席）
        for (String reviewer : List.of("review_pa", "review_pb", "review_pc")) {
            assertThat(loaded.recorders.get(reviewer).calls())
                    .as("%s：互审成员 %s 应照常执行一次", label, reviewer).isEqualTo(1);
            assertThat(outcomeOf(outcomes, "r_" + reviewer.substring("review_".length())).outputText())
                    .as("%s：互审步 %s 应有产物", label, reviewer).isPresent();
        }
        // 极端变体：某互审的全部上游都缺席 ⇒ 其输入不应出现上游投影段
        if (form.noProjectionReviewer() != null) {
            final String goal = loaded.recorders.get(form.noProjectionReviewer()).subGoal(0);
            for (String absent : form.expectedAbsentPerspectives()) {
                assertThat(goal).as("%s：互审 %s 的输入不应含缺席视角 %s 的标记；实收=[%s]",
                        label, form.noProjectionReviewer(), absent, goal)
                        .doesNotContain(perspectiveMarkers.get(absent));
            }
            assertThat(goal).as("%s：互审 %s 的全部上游均缺席 ⇒ 输入应恰好等于其成员指令原文（无投影段）；"
                            + "实收=[%s]", label, form.noProjectionReviewer(), goal)
                    .isEqualTo(CrossValidationCompileTest.instructionOf(loaded.spec,
                            form.noProjectionReviewer()));
        }

        // ③ 审计员：不含缺席视角标记，含在场视角与**全部三个**互审标记
        final String auditGoal = loaded.recorders.get("aud").subGoal(0);
        for (String absent : form.expectedAbsentPerspectives()) {
            assertThat(auditGoal).as("%s：审计员输入不应含缺席视角 %s 的投影；实收=[%s]",
                    label, absent, auditGoal).doesNotContain(perspectiveMarkers.get(absent));
        }
        for (String present : form.expectedPresentPerspectives()) {
            assertThat(auditGoal).as("%s：审计员输入应含在场视角 %s 的产物；实收=[%s]",
                    label, present, auditGoal).contains(perspectiveMarkers.get(present));
        }
        reviewMarkers.forEach((reviewer, marker) ->
                assertThat(auditGoal).as("%s：审计员输入应含互审 %s 的产物（不得因一份缺席就收不到其余全体）；"
                        + "实收=[%s]", label, reviewer, auditGoal).contains(marker));

        // 收口仍在审计步
        assertThat(result.finalOutput()).as("%s：收口产物应为审计步产物", label)
                .isEqualTo("AUDIT-066-S14-" + RUN);
        assertThat(result.stepOutcomes()).extracting(StepOutcome::stepId)
                .containsExactly("p_pa", "p_pb", "p_pc", "r_pa", "r_pb", "r_pc", "audit");
    }

    private static StepOutcome outcomeOf(final List<StepOutcome> outcomes, final String stepId) {
        return outcomes.stream().filter(o -> o.stepId().equals(stepId)).findFirst().orElseThrow();
    }
}

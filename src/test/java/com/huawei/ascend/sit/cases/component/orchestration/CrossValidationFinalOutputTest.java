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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-066-D2 / FEAT-066-D3：交叉验证的结果收口与回退。
 *
 * <p>Oracle 来源：FEAT-066 §2「最终答案收口」+ §5.1.2；收口规则沿用 FEAT-065 §2。
 * **FEAT-065:45 现文（docs `!383`／`main@bd6ac83f` 起）**为当前层三分支：全部空白 ⇒ 保留此前已收集结果；
 * 恰好一份非空白 ⇒ 直接以该产物原文替换、不加标题；多份非空白 ⇒ 按声明序拼成 `## <步骤id>` 分节。
 * （旧文曾写「末个非空白步产物胜出」，与实现在多步层上不等价 ⇒ 我方 `docs#41` 上报、`!383` 修正、已验证关单。
 * 本类 S08 构造的是**恰好一份非空白**那一支：`pa` 有产物、`pb` 与 `aud` 空白。）
 *
 * <p>S07 的判据含一条**否定式**断言：不存在任何能让审计步被跳过的图纸写法——
 * 因为编译产物的 {@code skipIf} 恒为规范化后的 {@code NEVER}（已由 FEAT-066-C1 ⑦ 证明），
 * 作者无法通过 {@code cross_validation} 图纸给审计步挂条件跳过（{@code steps} 键本身就被拒，
 * 见 FEAT-066-E3 ⑤）。本用例据此断言审计步 {@code skipped == false} 且它就是收口来源。
 *
 * <p>S08 拦住的故障：审计员返回空白时，收口产物**被空覆盖**（应为回退到上一层的既有收口产物；
 * 本例该层恰好一份非空白 ⇒ 即该步产物原文）。
 */
@Tag("component")
@Tag("feat-066")
@Feature("FEAT-066: 交叉验证与汇总审计")
class CrossValidationFinalOutputTest {

    private static final String RUN = OrchestrationTestSupport.uid();

    private static final String TWO_PERSPECTIVES_NO_REVIEW = """
            blueprint:
              id: T-FEAT-066-%s
              type: cross_validation
              perspectives:
                - {id: pa, instruction: "从技术可行性视角作答"}
                - {id: pb, instruction: "从合规风险视角作答"}
              auditor: {id: aud, instruction: "汇总各方结论并给出审计意见"}
            """;

    @Test
    @Story("FEAT-066-D2: 最终答案收口于审计步")
    @DisplayName("FEAT-066-D2 finalOutput == 审计步产物，审计步未被跳过")
    void finalOutputIsTheAuditStepOutput() {
        final String yaml = TWO_PERSPECTIVES_NO_REVIEW.replace("T-FEAT-066-%s", "T-FEAT-066-D2");
        final var loaded = OrchestrationTestSupport.load(yaml, "FEAT-066-D2", RUN);
        final String auditMarker = "AUDIT-FEAT-066-D2-" + RUN;
        loaded.recorders.get("pa").returns("P-FEAT-066-D2-pa-" + RUN);
        loaded.recorders.get("pb").returns("P-FEAT-066-D2-pb-" + RUN);
        loaded.recorders.get("aud").returns(auditMarker);

        final BlueprintResult result = loaded.execute("U-FEAT-066-D2-" + RUN, 4, new TraceForest());

        final StepOutcome audit = result.stepOutcomes().stream()
                .filter(o -> o.stepId().equals("audit")).findFirst().orElseThrow();
        assertThat(audit.skipped()).as("审计步不应被跳过（编译产物 skipIf 恒 NEVER，作者无通道挂条件跳过）")
                .isFalse();
        assertThat(result.finalOutput()).as("收口产物应为审计步的产物").isEqualTo(auditMarker);
        assertThat(result.finalOutput()).as("收口产物应与审计步 output 逐字相同")
                .isEqualTo(audit.output());
        assertThat(result.finalOutputText()).as("收口产物不应为缺席").isPresent();
        assertThat(result.blueprintId()).isEqualTo("T-FEAT-066-D2");
        assertThat(result.stepOutcomes()).as("三步都应有留痕").hasSize(3);
        assertThat(result.stepOutcomes()).as("本图纸无任何步骤应被跳过")
                .allSatisfy(o -> assertThat(o.skipped()).isFalse());
    }

    @Test
    @Story("FEAT-066-D3: 审计步空白时按 FEAT-065 收口三层规则回退")
    @DisplayName("FEAT-066-D3 审计员返回空 → finalOutput 回退为末个非空白步产物，且 outcomes 仍含全部步")
    void blankAuditOutputFallsBackToLastNonBlank() {
        final String yaml = TWO_PERSPECTIVES_NO_REVIEW.replace("T-FEAT-066-%s", "T-FEAT-066-D3");
        final var loaded = OrchestrationTestSupport.load(yaml, "FEAT-066-D3", RUN);
        final String fallback = "FALLBACK-FEAT-066-D3-" + RUN;
        loaded.recorders.get("pa").returns(fallback);
        loaded.recorders.get("pb").returns("");        // 缺席
        loaded.recorders.get("aud").returns("");       // 审计员返回空

        final BlueprintResult result = loaded.execute("U-FEAT-066-D3-" + RUN, 4, new TraceForest());

        assertThat(result.finalOutput()).as("审计步空白时，收口产物应回退为上一层的既有收口产物"
                        + "（FEAT-065:45 现文：本层恰好一份非空白 ⇒ 直接以该产物原文替换、不加标题），**不得为空**")
                .isEqualTo(fallback);
        assertThat(result.finalOutputText()).as("收口产物不应为缺席").isPresent();
        final StepOutcome audit = result.stepOutcomes().stream()
                .filter(o -> o.stepId().equals("audit")).findFirst().orElseThrow();
        assertThat(audit.skipped()).as("审计步是**执行了但产物缺席**，不是被跳过").isFalse();
        assertThat(audit.outputText()).as("审计步产物应判缺席").isEmpty();
        assertThat(result.outcomes()).as("outcomes 仍应含全部步（含产物缺席的审计步）")
                .containsKeys("p_pa", "p_pb", "audit");
        assertThat(result.stepOutcomes()).extracting(StepOutcome::stepId)
                .containsExactly("p_pa", "p_pb", "audit");
    }

    /**
     * FEAT-066-D3 的**多产物 + 多层**格（TestPlan !508 判据：「覆盖多层与多产物」）：
     * 视角层（第 1 层）两份**全非空白** ⇒ 按三层规则③拼 `## <步骤id>` 分节聚合；
     * 审计层（第 2 层）全空白 ⇒ 按三层规则①④保留视角层的分节聚合结果，不回退成单份、也不置空。
     *
     * <p>Oracle：FEAT-066:39（!508 评审刷新后引用 FEAT-065:45 三层规则）；实现在
     * {@code BlueprintScheduler.collectResults}（与 065-S18 同一落点——审计步编译后就是普通步，
     * 收口走同一条逐层收集链）。旧口径「回退到末个非空白步产物」在多产物场景会**误判**
     * （会把分节聚合错读成单份产物胜出）⇒ 评审处置 [A] 后以三层规则为准。
     */
    @Test
    @Story("FEAT-066-D3: 审计步空白时按 FEAT-065 收口三层规则回退")
    @DisplayName("FEAT-066-D3 多产物格：双视角全非空白 + 审计空白 → 收口保留视角层 ## p_pa/## p_pb 分节聚合（跨层空白传递）")
    void blankAuditWithMultipleNonBlankPerspectivesKeepsSectionedAggregation() {
        final String yaml = TWO_PERSPECTIVES_NO_REVIEW.replace("T-FEAT-066-%s", "T-FEAT-066-D3-multi");
        final var loaded = OrchestrationTestSupport.load(yaml, "FEAT-066-D3-multi", RUN);
        final String markerA = "P-FEAT-066-D3-multi-pa-" + RUN;
        final String markerB = "P-FEAT-066-D3-multi-pb-" + RUN;
        loaded.recorders.get("pa").returns(markerA);
        loaded.recorders.get("pb").returns(markerB);
        loaded.recorders.get("aud").returns("");       // 审计员返回空

        final BlueprintResult result = loaded.execute("U-FEAT-066-D3-multi-" + RUN, 4, new TraceForest());

        // 视角层两份非空白 ⇒ 三层规则③：按声明序拼 "## p_pa" / "## p_pb" 分节，且各含产物原文
        assertThat(result.finalOutput())
                .as("视角层两份非空白应按声明序拼 ## 分节聚合；实收=%s", result.finalOutput())
                .contains("## p_pa").contains("## p_pb")
                .contains(markerA).contains(markerB);
        // 审计层全空白 ⇒ 三层规则①④：保留视角层聚合结果（不回退为单份、不置空）——即"跨层空白传递"
        assertThat(result.finalOutput())
                .as("审计层全空白时收口应保留视角层的分节聚合结果（跨层空白传递，不得置空或改写）；实收=%s",
                        result.finalOutput())
                .isNotBlank();
        // 空白审计步不得凭空产生分节头
        assertThat(result.finalOutput())
                .as("空白审计步不得在收口产物里出现分节头；实收=%s", result.finalOutput())
                .doesNotContain("## audit");
        final StepOutcome audit = result.stepOutcomes().stream()
                .filter(o -> o.stepId().equals("audit")).findFirst().orElseThrow();
        assertThat(audit.skipped()).as("审计步是执行了但产物缺席，不是被跳过").isFalse();
        assertThat(result.stepOutcomes()).as("outcomes 仍应含全部步").hasSize(3);
    }
}

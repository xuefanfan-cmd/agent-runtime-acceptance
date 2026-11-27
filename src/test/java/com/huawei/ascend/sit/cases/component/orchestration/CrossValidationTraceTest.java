/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.openjiuwen.agents.orchestration.BlueprintResult;
import com.openjiuwen.agents.orchestration.StepOutcome;
import com.openjiuwen.agents.reactrails.loopforest.observability.BranchedTrace;
import com.openjiuwen.agents.reactrails.loopforest.observability.TraceForest;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 066-S10：交叉验证的留痕（视角 / 互审 / 审计每步登记）。
 *
 * <p>Oracle 来源：FEAT-066 §2「留痕」；登记机制沿用 FEAT-065 §5.1.5（见 065-S09 / 065-S10）。
 *
 * <p>判据：三视角 + {@code review: true} + 审计（共 **7 步**）⇒ {@code stepOutcomes()} 7 条；
 * {@code stepId} 序列 == 3 个 {@code p_}（视角声明序）→ 3 个 {@code r_}（视角声明序）→ {@code audit}；
 * 每条 {@code memberId} 与 {@code skipped} 齐备、{@code branchId} 非空；
 * 森林可按执行根取回全部 7 条。
 *
 * <p>不应断言：{@code branchId} 的具体格式（同 065-S09）。
 */
@Tag("component")
@Tag("feat-066")
@Feature("FEAT-066: 交叉验证与汇总审计")
class CrossValidationTraceTest {

    private static final String RUN = OrchestrationTestSupport.uid();

    private static final String THREE_PERSPECTIVES_WITH_REVIEW = """
            blueprint:
              id: T-066-S10
              type: cross_validation
              review: true
              perspectives:
                - {id: pa, instruction: "从技术可行性视角作答"}
                - {id: pb, instruction: "从合规风险视角作答"}
                - {id: pc, instruction: "从成本收益视角作答"}
              auditor: {id: aud, instruction: "汇总各方结论并给出审计意见"}
            """;

    @Test
    @Story("066-S10: 视角/互审/审计每步登记")
    @DisplayName("066-S10 七步全部登记，序列 == p_(视角声明序) → r_(视角声明序) → audit，森林可按根取回")
    void allSevenStepsAreRegisteredInOrder() {
        final var loaded = OrchestrationTestSupport.load(THREE_PERSPECTIVES_WITH_REVIEW, "066-S10", RUN);
        final TraceForest forest = new TraceForest();

        final BlueprintResult result = loaded.execute("U-066-S10-" + RUN, 4, forest);

        final List<StepOutcome> outcomes = result.stepOutcomes();
        assertThat(outcomes).as("3 视角 + 3 互审 + 1 审计 = 7 条留痕").hasSize(7);
        assertThat(outcomes).extracting(StepOutcome::stepId)
                .as("登记序应为 p_（视角声明序）→ r_（视角声明序）→ audit")
                .containsExactly("p_pa", "p_pb", "p_pc", "r_pa", "r_pb", "r_pc", "audit");
        assertThat(outcomes).extracting(StepOutcome::memberId)
                .as("每条留痕的成员标识应与编译产物的步骤成员一致")
                .containsExactly("pa", "pb", "pc", "review_pa", "review_pb", "review_pc", "aud");
        for (StepOutcome o : outcomes) {
            assertThat(o.branchId()).as("步骤 %s 的分支标识不应为空", o.stepId()).isNotBlank();
            assertThat(o.outputText()).as("步骤 %s 应有产物（本图纸无跳过、无空白构造）", o.stepId()).isPresent();
            // 本图纸没有任何跳过策略 ⇒ 全部执行
            assertThat(o.skipped()).as("步骤 %s 不应被跳过", o.stepId()).isFalse();
        }
        // 森林侧：按执行根取回全部 7 条
        assertThat(forest.roots()).as("应恰有一个执行根").hasSize(1);
        final String rootId = forest.roots().get(0).branchId();
        final List<BranchedTrace> children = forest.childrenOf(rootId);
        assertThat(children).as("执行根下应能取回全部 7 条步骤留痕").hasSize(7);
        assertThat(children).extracting(BranchedTrace::branchId)
                .as("森林里的分支应与留痕条目一一对应")
                .containsExactlyElementsOf(outcomes.stream().map(StepOutcome::branchId).toList());
        assertThat(forest.size()).as("森林规模应为 1 个根 + 7 个步骤分支").isEqualTo(8);
        // 每个执行体各被调用一次（登记 != 执行的证明）
        loaded.recorders.forEach((memberId, rec) ->
                assertThat(rec.calls()).as("成员 %s 的执行体应被调用一次", memberId).isEqualTo(1));
    }
}

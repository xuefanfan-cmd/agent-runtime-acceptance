/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.openjiuwen.agents.orchestration.BlueprintResult;
import com.openjiuwen.agents.orchestration.StepOutcome;
import com.openjiuwen.agents.reactrails.loopforest.observability.BranchSignature;
import com.openjiuwen.agents.reactrails.loopforest.observability.BranchStatus;
import com.openjiuwen.agents.reactrails.loopforest.observability.TraceForest;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 065-S06 / S07 / S08：条件跳过语义三件（跳过触发、无上游守卫、链式传播与不过度传播）。
 *
 * <p>Oracle 来源：FEAT-065 §2「条件跳过」（{@code DEPS_INCONCLUSIVE}：全部已就绪上游产物缺席才跳过）
 * + §5.1.4 跳过传播；L2-065 §3.5。§5.1.3 的 isBlank 统一缺席判据（§11.2 已解禁 {@code "   "}）。
 *
 * <p>三件各自拦住的故障：
 * <ul>
 *   <li>S06——上游缺席 + 声明 {@code DEPS_INCONCLUSIVE} 的下游**必须**跳过，且执行体零调用
 *       （拦住"跳过策略被忽略"）；</li>
 *   <li>S07——**无上游**的步即使声明 {@code DEPS_INCONCLUSIVE} 也**照常执行**（拦住 anyDep 守卫缺失
 *       导致的首层全跳过）；</li>
 *   <li>S08——跳过沿依赖边**链式**传播，但**只**沿声明了该策略的边传播：同为 s1 下游、
 *       未声明策略的 s4 必须照常执行（拦住"过度传播"与"传播不足"两个方向）。</li>
 * </ul>
 *
 * <p>不应断言：跳过原因的字面格式（L2 §2.4 给的 {@code blueprint-skip:<步骤id>} 属实现约定，
 * Feature 只要求"原因可查"）⇒ 本类只断言原因非空且带被跳过步骤的定位。
 */
@Tag("component")
@Tag("feat-065")
@Feature("FEAT-065: 确定性编排层(蓝图调度)")
class BlueprintSkipPropagationTest {

    private static final String RUN = OrchestrationTestSupport.uid();

    @Test
    @Story("065-S06: 上游缺席时声明 DEPS_INCONCLUSIVE 的下游被跳过")
    @DisplayName("065-S06 上游产物缺席 → 下游 skipped=true、原因可查、执行体零调用")
    void downstreamWithDepsInconclusiveIsSkippedWhenUpstreamAbsent() {
        final String yaml = """
                blueprint:
                  id: T-065-S06
                  type: dag
                  members:
                    - {id: m1, instruction: "上游作答"}
                    - {id: m2, instruction: "下游作答"}
                  steps:
                    - {id: s1, member: m1}
                    - {id: s2, member: m2, depends_on: [s1], skip_if: DEPS_INCONCLUSIVE}
                """;
        final var loaded = OrchestrationTestSupport.load(yaml, "065-S06", RUN);
        loaded.recorders.get("m1").returns("");
        final TraceForest forest = new TraceForest();

        final BlueprintResult result = loaded.execute("U-065-S06-" + RUN, 4, forest);

        final List<StepOutcome> outcomes = result.stepOutcomes();
        assertThat(outcomes).hasSize(2);
        final StepOutcome s2 = outcomes.get(1);
        assertThat(s2.stepId()).isEqualTo("s2");
        assertThat(s2.skipped()).as("上游缺席 + 声明 DEPS_INCONCLUSIVE ⇒ s2 应被跳过").isTrue();
        assertThat(s2.outputText()).as("跳过步的产物应以缺席表达").isEmpty();
        assertThat(loaded.recorders.get("m2").calls()).as("被跳过步骤的执行体不应被调用").isZero();
        assertSkipTraceQueryable(forest, s2, "s2");
    }

    @Test
    @Story("065-S07: 无上游的步不被跳过（anyDep 守卫）")
    @DisplayName("065-S07 单步无上游且声明 DEPS_INCONCLUSIVE → 照常执行")
    void stepWithoutUpstreamIsNeverSkipped() {
        final String yaml = """
                blueprint:
                  id: T-065-S07
                  type: dag
                  members:
                    - {id: m1, instruction: "独立作答"}
                  steps:
                    - {id: s1, member: m1, skip_if: DEPS_INCONCLUSIVE}
                """;
        final var loaded = OrchestrationTestSupport.load(yaml, "065-S07", RUN);
        final TraceForest forest = new TraceForest();

        final BlueprintResult result = loaded.execute("U-065-S07-" + RUN, 4, forest);

        final List<StepOutcome> outcomes = result.stepOutcomes();
        assertThat(outcomes).hasSize(1);
        final StepOutcome s1 = outcomes.get(0);
        assertThat(s1.skipped()).as("无上游的步即使声明 DEPS_INCONCLUSIVE 也应照常执行（anyDep 守卫）").isFalse();
        assertThat(loaded.recorders.get("m1").calls()).as("执行体应被调用一次").isEqualTo(1);
        assertThat(s1.outputText()).as("应有实际产物").isPresent()
                .get().asString().contains(OrchestrationTestSupport.marker("065-S07", "m1", RUN));
        assertThat(result.finalOutputText()).as("收口产物应非空").isPresent();
    }

    @Test
    @Story("065-S08: 跳过链式传播但不过度传播")
    @DisplayName("065-S08 s2→s3 链式跳过、同上游的默认档 s4 照常执行且无投影段")
    void skipPropagatesAlongDeclaredEdgesOnly() {
        final String yaml = """
                blueprint:
                  id: T-065-S08
                  type: dag
                  members:
                    - {id: m1, instruction: "上游作答"}
                    - {id: m2, instruction: "第二层甲"}
                    - {id: m3, instruction: "第三层"}
                    - {id: m4, instruction: "第二层乙"}
                  steps:
                    - {id: s1, member: m1}
                    - {id: s2, member: m2, depends_on: [s1], skip_if: DEPS_INCONCLUSIVE}
                    - {id: s3, member: m3, depends_on: [s2], skip_if: DEPS_INCONCLUSIVE}
                    - {id: s4, member: m4, depends_on: [s1]}
                """;
        final var loaded = OrchestrationTestSupport.load(yaml, "065-S08", RUN);
        loaded.recorders.get("m1").returns("   ");   // §11.2 解禁形态：仅空白即缺席
        final TraceForest forest = new TraceForest();

        final BlueprintResult result = loaded.execute("U-065-S08-" + RUN, 4, forest);

        final List<StepOutcome> outcomes = result.stepOutcomes();
        assertThat(outcomes).hasSize(4);
        // 登记序 = 拓扑层序 × 层内声明序，且同层跳过步先登记（详见 065-S10）
        assertThat(outcomes).extracting(StepOutcome::stepId)
                .containsExactly("s1", "s2", "s4", "s3");

        final StepOutcome s1 = outcomes.get(0);
        assertThat(s1.skipped()).as("s1 无上游，应执行").isFalse();
        assertThat(s1.outputText()).as("s1 返回仅空白，应判缺席").isEmpty();

        final StepOutcome s2 = outcomes.get(1);
        assertThat(s2.skipped()).as("s2 的唯一上游缺席且声明策略 ⇒ 应跳过").isTrue();
        assertSkipTraceQueryable(forest, s2, "s2");

        final StepOutcome s4 = outcomes.get(2);
        assertThat(s4.skipped()).as("s4 同为 s1 下游但未声明策略 ⇒ **不得**被过度传播跳过").isFalse();
        assertThat(loaded.recorders.get("m4").calls()).as("s4 的执行体应被调用一次").isEqualTo(1);
        assertThat(loaded.recorders.get("m4").subGoal(0))
                .as("s4 的上游缺席 ⇒ 不应产生投影段，步骤目标位应恰好等于成员指令原文；实收=[%s]",
                        loaded.recorders.get("m4").subGoal(0))
                .isEqualTo("第二层乙");

        final StepOutcome s3 = outcomes.get(3);
        assertThat(s3.skipped()).as("s3 依赖被跳过的 s2（产物缺席）⇒ 应链式跳过").isTrue();
        assertSkipTraceQueryable(forest, s3, "s3");
        assertThat(loaded.recorders.get("m2").calls()).as("s2 执行体不应被调用").isZero();
        assertThat(loaded.recorders.get("m3").calls()).as("s3 执行体不应被调用").isZero();

        assertThat(result.finalOutputText())
                .as("收口产物应为 s4 的产物原文（pipeline 每层单步 ⇒ 当前层恰好一份非空白；"
                        + "FEAT-065:45 现文：直接以该产物原文替换、不加标题）").isPresent()
                .get().asString().contains(OrchestrationTestSupport.marker("065-S08", "m4", RUN));
    }

    /** 跳过原因"可查"：分支状态为剪枝态、原因非空且带被跳过步骤的定位（不断言字面格式）。 */
    private static void assertSkipTraceQueryable(final TraceForest forest, final StepOutcome skipped,
            final String stepId) {
        assertThat(skipped.branchId()).as("跳过步也应有留痕分支标识").isNotBlank();
        final Optional<BranchSignature> signature = forest.signatureOf(skipped.branchId());
        assertThat(signature).as("跳过步的分支签名应可查（branchId=%s）", skipped.branchId()).isPresent();
        assertThat(signature.get().status())
                .as("跳过步的分支状态应为 PRUNED（计划内在但未执行，对审计可见）")
                .isEqualTo(BranchStatus.PRUNED);
        assertThat(signature.get().pruneReason())
                .as("跳过原因应可查且带被跳过步骤的定位 %s", stepId)
                .isNotBlank().contains(stepId);
    }
}

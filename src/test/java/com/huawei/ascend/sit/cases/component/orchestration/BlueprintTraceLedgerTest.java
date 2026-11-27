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
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 065-S09（留痕字段齐备）与 065-S10（留痕登记序 == 拓扑层序 × 层内声明序，且可重放）。
 *
 * <p>Oracle 来源：FEAT-065 §2「每步留痕」（四项：步骤标识／成员标识／产物／跳过与否及原因）
 * + §5.1.5 留痕结构；L2-065 §3.6「留痕登记序」。
 * 补 L2-065 §1.3 自陈缺口 C5「流水线第二步留痕无直接断言」——本类对**每一步**（含跳过步）逐字段断言。
 *
 * <p><b>登记序是本特性的 parity 判据</b>：{@code BlueprintResult.outcomes()} 是 {@code LinkedHashMap}，
 * 其 javadoc 自称「登记序 = 执行层序 × 层内声明序——parity 判据」。因此 S10 用**故意与字母序相反**的
 * 声明序（sB 在 sA 之前）来区分"按声明序"与"按完成序/字母序"两种实现，并连跑 3 次证明可重放。
 *
 * <p><b>同层跳过步排在执行步之前</b>：这是可观察的实现事实，也是审计口径的一部分
 * （"计划过但未执行"先入账）；S10 的第二层同时含一个跳过步与两个执行步，用于固定该序。
 *
 * <p>不应断言：{@code branchId} 的具体格式（只断言非空且可在森林里查到）。
 * NC-04 已裁定：L2 自提的"输入与耗时字段"Feature 从未承诺 ⇒ **不作为判据**。
 */
@Tag("component")
@Tag("feat-065")
@Feature("FEAT-065: 确定性编排层(蓝图调度)")
class BlueprintTraceLedgerTest {

    private static final String RUN = OrchestrationTestSupport.uid();

    /**
     * 三层 dag：第二层声明序为 sB、sSkipped、sA（故意与字母序相反），
     * 其中 sSkipped 声明 {@code DEPS_INCONCLUSIVE} 且因 s1 产物缺席而被跳过。
     * 期望登记序 = s1, sSkipped, sB, sA, s3。
     */
    private static final String THREE_LAYER_DAG = """
            blueprint:
              id: T-065-S10
              type: dag
              members:
                - {id: m1, instruction: "第一层"}
                - {id: mB, instruction: "第二层乙"}
                - {id: mS, instruction: "第二层可跳过"}
                - {id: mA, instruction: "第二层甲"}
                - {id: m3, instruction: "第三层汇总"}
              steps:
                - {id: s1, member: m1}
                - {id: sB, member: mB, depends_on: [s1]}
                - {id: sSkipped, member: mS, depends_on: [s1], skip_if: DEPS_INCONCLUSIVE}
                - {id: sA, member: mA, depends_on: [s1]}
                - {id: s3, member: m3, depends_on: [sB, sA]}
            """;

    private static final List<String> EXPECTED_ORDER = List.of("s1", "sSkipped", "sB", "sA", "s3");

    @Test
    @Story("065-S09: 每步留痕字段齐备（含跳过步）")
    @DisplayName("065-S09 每步（含跳过步）留痕四要素齐备，且分支状态与原因在森林中可查")
    void everyStepHasCompleteTraceFields() {
        final String yaml = """
                blueprint:
                  id: T-065-S09
                  type: dag
                  members:
                    - {id: m1, instruction: "上游作答"}
                    - {id: m2, instruction: "下游作答"}
                  steps:
                    - {id: s1, member: m1}
                    - {id: s2, member: m2, depends_on: [s1], skip_if: DEPS_INCONCLUSIVE}
                """;
        final var loaded = OrchestrationTestSupport.load(yaml, "065-S09", RUN);
        loaded.recorders.get("m1").returns("NONEMPTY-065-S09-" + RUN);
        final TraceForest forest = new TraceForest();

        // s1 有产物 ⇒ s2 不该跳过；为覆盖"含跳过步"的字段齐备性，第二次跑构造缺席
        final BlueprintResult withOutput = loaded.execute("U-065-S09-a-" + RUN, 4, forest);
        assertFieldsComplete(withOutput, forest, List.of(), List.of());

        loaded.recorders.get("m1").returns("");
        final TraceForest forest2 = new TraceForest();
        final BlueprintResult withSkip = loaded.execute("U-065-S09-b-" + RUN, 4, forest2);
        // s1 本轮返回空串（这是构造 s2 跳过的前提）⇒ 它属"执行了但产物缺席"，不是跳过
        assertFieldsComplete(withSkip, forest2, List.of("s2"), List.of("s1"));
        assertThat(withSkip.stepOutcomes()).extracting(StepOutcome::stepId).containsExactly("s1", "s2");
        assertThat(withSkip.stepOutcomes().get(1).skipped()).as("第二次运行 s2 应被跳过").isTrue();
    }

    /** 逐字段核对 Feature §2 要求的四项留痕要素（步骤标识/成员标识/产物/跳过与否及原因）。 */
    private static void assertFieldsComplete(final BlueprintResult result, final TraceForest forest,
            final List<String> expectedSkipped, final List<String> expectedAbsentOutputs) {
        final List<StepOutcome> outcomes = result.stepOutcomes();
        assertThat(outcomes).as("每步都应有留痕条目").isNotEmpty();
        final List<String> actualSkipped = new ArrayList<>();
        for (StepOutcome o : outcomes) {
            assertThat(o.stepId()).as("留痕应含步骤标识").isNotBlank();
            assertThat(o.memberId()).as("留痕应含成员标识").isNotBlank();
            assertThat(o.branchId()).as("留痕应含分支标识（步态审计入口）").isNotBlank();
            // 产物：执行步有内容、跳过步以缺席表达（Feature「无结果以缺席表达，不双态」）
            if (o.skipped()) {
                actualSkipped.add(o.stepId());
                assertThat(o.outputText()).as("跳过步 %s 的产物应以缺席表达", o.stepId()).isEmpty();
                final BranchSignature sig = forest.signatureOf(o.branchId()).orElseThrow();
                assertThat(sig.status()).as("跳过步 %s 的分支状态应为 PRUNED", o.stepId())
                        .isEqualTo(BranchStatus.PRUNED);
                assertThat(sig.pruneReason()).as("跳过步 %s 的原因应可查且带定位", o.stepId())
                        .isNotBlank().contains(o.stepId());
            } else {
                assertThat(o.output()).as("执行步 %s 的产物字段应非 null（缺席以空串表达，不双态）",
                        o.stepId()).isNotNull();
                if (expectedAbsentOutputs.contains(o.stepId())) {
                    assertThat(o.outputText()).as("执行步 %s 本轮按构造返回空白，应判缺席", o.stepId()).isEmpty();
                } else {
                    assertThat(o.outputText()).as("执行步 %s 应有产物", o.stepId()).isPresent();
                }
                final BranchSignature sig = forest.signatureOf(o.branchId()).orElseThrow();
                assertThat(sig.status()).as("执行步 %s 的分支状态应为 ACTIVE", o.stepId())
                        .isEqualTo(BranchStatus.ACTIVE);
            }
            // 森林侧可按分支 id 反查到该分支，且挂在同一执行根下
            assertThat(forest.get(o.branchId())).as("分支 %s 应可在森林中查到", o.branchId()).isPresent();
            assertThat(forest.get(o.branchId()).orElseThrow().address().parentBranchId())
                    .as("每步分支应星形挂在执行根下").isEqualTo(forest.roots().get(0).branchId());
        }
        assertThat(actualSkipped).as("被跳过的步骤集合应与预期一致").containsExactlyElementsOf(expectedSkipped);
    }

    @RepeatedTest(3)
    @Story("065-S10: 留痕登记序 == 拓扑层序 × 层内声明序，且可重放")
    @DisplayName("065-S10 登记序严格等于「拓扑层序 × 层内声明序」，同层跳过步先登记，连跑 3 次一致")
    void registrationOrderIsLayerTimesDeclarationOrderAndReplayable() {
        final var loaded = OrchestrationTestSupport.load(THREE_LAYER_DAG, "065-S10", RUN);
        // s1 产物缺席 ⇒ sSkipped 被跳过；sB/sA 未声明策略仍照常执行
        loaded.recorders.get("m1").returns("");
        final TraceForest forest = new TraceForest();

        final BlueprintResult result = loaded.execute("U-065-S10-" + RUN, 4, forest);

        final List<String> actual = result.stepOutcomes().stream().map(StepOutcome::stepId).toList();
        assertThat(actual).as("登记序必须严格等于「拓扑层序 × 层内声明序」（第二层声明序为 sB、sSkipped、sA，"
                + "且同层跳过步排在执行步之前）；连跑三次必须完全一致").containsExactlyElementsOf(EXPECTED_ORDER);
        // 与字母序/完成序区分开：若实现按字母序登记，序列会是 s1,s3,sA,sB,sSkipped
        assertThat(actual).as("登记序不应退化为字母序")
                .isNotEqualTo(List.of("s1", "s3", "sA", "sB", "sSkipped"));
        assertThat(result.stepOutcomes().get(1).skipped()).as("sSkipped 应被跳过").isTrue();
        assertThat(result.stepOutcomes().get(2).skipped()).as("sB 应执行").isFalse();
        assertThat(result.stepOutcomes().get(3).skipped()).as("sA 应执行").isFalse();
        assertThat(result.outcomes()).as("outcomes 应是保序映射").isInstanceOf(java.util.LinkedHashMap.class);
    }
}

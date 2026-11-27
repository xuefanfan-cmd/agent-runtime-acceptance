/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.openjiuwen.agents.orchestration.BlueprintException;
import com.openjiuwen.agents.orchestration.BlueprintResult;
import com.openjiuwen.agents.reactrails.loopforest.observability.BranchStatus;
import com.openjiuwen.agents.reactrails.loopforest.observability.TraceForest;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 065-S12 / S13 / S14：失败与缺口的错误表面（绑定缺口、层内步失败、执行体抛异常）。
 *
 * <p>Oracle 来源：FEAT-065 §2「照图严格执行」+ §5.1.2 + §5.1.6 + §5.1.7 错误表；
 * L2-065 §7.3。§5.1.2 明文（§11.2 已把 GAP-065-02 关闭并把留痕条数断言**升为 gating**）：
 * 「**失败步自身不登记留痕**，不再登记后续留痕（同层已在跑的步不取消、其结果弃收）；
 * **执行以异常终止时不返回执行结果**」。
 *
 * <p>三条各拦住的故障：S12——绑定缺口在**入口**即拒（不得先跑一部分再报）；
 * S13——层内步失败时错误载体带图纸 id + 步骤 id、不返回部分结果、留痕止于失败步之前；
 * S14——执行体自身抛出的异常不被吞掉，原始消息可追。
 *
 * <p><b>时序固定</b>：S13 的同层另一步 s3 被门闸挡住，直到断言完成后才放行——
 * 否则它可能在断言之后才写留痕，使"留痕条数"断言变成竞态（本轮实现刻意避免该假失败/假通过）。
 *
 * <p>不应断言：同层并行步 s3 的产物去向（"弃收"的可观察口径 Feature 未承诺）。
 */
@Tag("component")
@Tag("feat-065")
@Feature("FEAT-065: 确定性编排层(蓝图调度)")
class BlueprintFailureSurfaceTest {

    private static final String RUN = OrchestrationTestSupport.uid();

    @Test
    @Story("065-S12: 绑定缺口在入口即拒")
    @DisplayName("065-S12 图纸声明 A、B 但只绑定 A → 入口抛异常并列出缺口成员，A 零调用")
    void missingBindingIsRejectedAtEntry() {
        final String yaml = """
                blueprint:
                  id: T-065-S12
                  type: parallel
                  members:
                    - {id: mA, instruction: "成员甲作答"}
                    - {id: mB, instruction: "成员乙作答"}
                """;
        final var loaded = OrchestrationTestSupport.load(yaml, "065-S12", RUN);
        // 只绑定 A，故意留下 B 的缺口
        final Map<String, com.openjiuwen.agents.reactrails.loopforest.execution.SubAgentExecutor> partial =
                new LinkedHashMap<>();
        partial.put("mA", loaded.recorders.get("mA"));

        final BlueprintException ex = catchThrowableOfType(
                () -> OrchestrationTestSupport.run(loaded.spec, partial,
                        "U-065-S12-" + RUN, 4, new TraceForest()),
                BlueprintException.class);

        assertThat(ex).as("绑定缺口必须抛 BlueprintException").isNotNull();
        assertThat(ex.getMessage()).as("错误消息应列出缺口成员标识 mB；实收=[%s]", ex.getMessage())
                .contains("mB");
        assertThat(loaded.recorders.get("mA").calls())
                .as("入口即拒 ⇒ 已绑定的 A 也不应被执行（不得先跑一部分再报）").isZero();
    }

    @Test
    @Story("065-S13: 层内步失败——不返回结果、留痕止于失败步之前")
    @DisplayName("065-S13 第二层 s2 抛异常 → 带图纸 id 与步骤 id、不返回 BlueprintResult、留痕条数 == 失败步之前")
    void layerFailureSurfacesWithLocatorsAndLeavesNoTraceForFailedStep() {
        final String yaml = """
                blueprint:
                  id: T-065-S13
                  type: dag
                  members:
                    - {id: m1, instruction: "第一层"}
                    - {id: mS, instruction: "第二层可跳过"}
                    - {id: m2, instruction: "第二层失败步"}
                    - {id: m3, instruction: "第二层慢速正常步"}
                  steps:
                    - {id: s1, member: m1}
                    - {id: sSkipped, member: mS, depends_on: [s1], skip_if: DEPS_INCONCLUSIVE}
                    - {id: s2, member: m2, depends_on: [s1]}
                    - {id: s3, member: m3, depends_on: [s1]}
                """;
        final var loaded = OrchestrationTestSupport.load(yaml, "065-S13", RUN);
        loaded.recorders.get("m1").returns("");   // s1 产物缺席 ⇒ sSkipped 被跳过（构造"失败步之前的跳过步"）
        loaded.recorders.get("m2").throwsOnExecute(new IllegalStateException("INJECT-065-S13-" + RUN));
        final CountDownLatch s3Gate = new CountDownLatch(1);
        loaded.recorders.get("m3").blocksUntil(s3Gate, "065-S13 s3 门闸未放行（测试装置超时）");
        final TraceForest forest = new TraceForest();

        final BlueprintResult[] returned = new BlueprintResult[1];
        try {
            final BlueprintException ex = catchThrowableOfType(
                    () -> returned[0] = OrchestrationTestSupport.run(loaded.spec,
                            OrchestrationTestSupport.bindings(loaded.recorders),
                            "U-065-S13-" + RUN, 4, forest),
                    BlueprintException.class);

            // ① 错误载体与定位齐备
            assertThat(ex).as("层内步失败必须抛 BlueprintException（不得吞异常）").isNotNull();
            assertThat(ex.getMessage()).as("消息应含图纸 id；实收=[%s]", ex.getMessage())
                    .contains("blueprint id=T-065-S13");
            assertThat(ex.getMessage()).as("消息应含失败步骤 id s2；实收=[%s]", ex.getMessage())
                    .contains("step id=s2");
            assertThat(ex.getMessage()).as("消息应可追到原始异常信息；实收=[%s]", ex.getMessage())
                    .contains("INJECT-065-S13-" + RUN);

            // ② 不返回 BlueprintResult——execute 以异常终止（Feature §5.1.2 明文）
            assertThat(returned[0]).as("执行以异常终止时不应返回执行结果（不得返回部分结果）").isNull();

            // ③ 留痕条数 == 失败步之前的步数（含第一层 s1 与同层排在前面的跳过步 sSkipped），
            //    失败步 s2 自身与其后步 s3 均无留痕
            final var root = forest.roots().get(0).branchId();
            assertThat(forest.childrenOf(root))
                    .as("留痕应止于失败步之前：期望 2 条（s1 + sSkipped），实收见下")
                    .hasSize(2);
            final long pruned = forest.childrenOf(root).stream()
                    .filter(b -> forest.signatureOf(b.branchId())
                            .map(s -> s.status() == BranchStatus.PRUNED).orElse(false))
                    .count();
            assertThat(pruned).as("其中应恰有 1 条是跳过步（sSkipped）").isEqualTo(1);
            assertThat(loaded.recorders.get("mS").calls()).as("跳过步执行体不应被调用").isZero();
            assertThat(loaded.recorders.get("m2").calls()).as("失败步执行体应被调用过一次").isEqualTo(1);
        } finally {
            s3Gate.countDown();
        }
    }

    @Test
    @Story("065-S14: 执行体抛异常——原始信息可追")
    @DisplayName("065-S14 单步执行体抛 RuntimeException → 消息含图纸 id、步骤 id 与原始异常信息")
    void executorExceptionIsNotSwallowed() {
        final String yaml = """
                blueprint:
                  id: T-065-S14
                  type: single
                  members:
                    - {id: m1, instruction: "独立作答"}
                """;
        final var loaded = OrchestrationTestSupport.load(yaml, "065-S14", RUN);
        final String boom = "BOOM-065-S14-" + RUN;
        loaded.recorders.get("m1").throwsOnExecute(new RuntimeException(boom));

        final BlueprintException ex = catchThrowableOfType(
                () -> loaded.execute("U-065-S14-" + RUN, 4, new TraceForest()),
                BlueprintException.class);

        assertThat(ex).as("执行体抛出的异常不得被吞掉").isNotNull();
        assertThat(ex.getMessage()).as("消息应含图纸 id；实收=[%s]", ex.getMessage())
                .contains("blueprint id=T-065-S14");
        assertThat(ex.getMessage()).as("消息应含步骤 id；实收=[%s]", ex.getMessage()).contains("step id=");
        assertThat(ex.getMessage()).as("消息应含原始异常信息 %s；实收=[%s]", boom, ex.getMessage())
                .contains(boom);
    }
}

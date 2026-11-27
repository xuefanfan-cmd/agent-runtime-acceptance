/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.openjiuwen.agents.orchestration.BlueprintException;
import com.openjiuwen.agents.orchestration.BlueprintResult;
import com.openjiuwen.agents.orchestration.BlueprintScheduler;
import com.openjiuwen.agents.orchestration.StepOutcome;
import com.openjiuwen.agents.reactrails.loopforest.observability.TraceForest;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 065-S02（层内并行真实性，闩锁构造）与 065-S20（并行度是构造期参数且运行期生效）。
 *
 * <p>Oracle 来源：FEAT-065 §5.1.2「层内并行」+ §2「依赖图(前置就绪即调度、层内并行)」；
 * L2-065 §3.3；§6「并行度」；L2-065 §6「{@code parallelism} ≥1，**非法值构造期拒绝
 * ({@code IllegalArgumentException})**，运行期不可变」（测试设计细档 §11.2 对 065-S20 的更正）。
 *
 * <p><b>闩锁构造为什么能证明"真并发"</b>：同层两步的执行体各自 {@code countDown()} 后
 * {@code await(2)}。若引擎把同层压扁成串行，第一个执行体永远等不到第二个 ⇒ 必然超时抛异常；
 * 只有两个执行体真的同时在跑，闩锁才会归零。这不是断言"耗时短"（设计明列不应断言），
 * 而是用会合点把"并行"变成可判定的布尔事实。
 *
 * <p><b>反例（negative control）是主断言的一部分</b>：同一张图纸在 {@code parallelism=1} 下
 * **必须**超时失败。若反例也通过，说明闩锁构造本身拦不住串行化故障，正例的 PASS 就没有意义。
 *
 * <p>不应断言：并行度上限、线程名（设计明列）。
 */
@Tag("component")
@Tag("feat-065")
@Feature("FEAT-065: 确定性编排层(蓝图调度)")
class BlueprintLayerParallelismTest {

    private static final String RUN = OrchestrationTestSupport.uid();

    /** 同层两步无前置的 dag——闩锁会合点。 */
    private static final String TWO_INDEPENDENT_STEPS = """
            blueprint:
              id: T-065-S02-dag
              type: dag
              members:
                - {id: m1, instruction: "技术视角作答"}
                - {id: m2, instruction: "合规视角作答"}
              steps:
                - {id: s1, member: m1}
                - {id: s2, member: m2}
            """;

    @Test
    @Story("065-S02: 层内并行闩锁证明")
    @DisplayName("065-S02 同层两步在 parallelism=2 下必然真并发（闩锁归零）")
    void sameLayerStepsReallyRunInParallel() {
        final var loaded = OrchestrationTestSupport.load(TWO_INDEPENDENT_STEPS, "065-S02", RUN);
        final CountDownLatch latch = new CountDownLatch(2);
        loaded.recorders.get("m1").awaitsLatch(latch, "065-S02 m1 等不到 m2 ⇒ 同层被串行化");
        loaded.recorders.get("m2").awaitsLatch(latch, "065-S02 m2 等不到 m1 ⇒ 同层被串行化");

        final BlueprintResult result = loaded.execute("U-065-S02-" + RUN, 2, new TraceForest());

        final List<StepOutcome> outcomes = result.stepOutcomes();
        assertThat(outcomes).as("两步都应执行、无跳过").hasSize(2)
                .allSatisfy(o -> assertThat(o.skipped()).isFalse());
        assertThat(outcomes).extracting(StepOutcome::stepId).containsExactly("s1", "s2");
        assertThat(outcomes.get(0).output()).as("s1 产物应含本用例唯一标记")
                .contains(OrchestrationTestSupport.marker("065-S02", "m1", RUN));
        assertThat(outcomes.get(1).output()).as("s2 产物应含本用例唯一标记")
                .contains(OrchestrationTestSupport.marker("065-S02", "m2", RUN));
        assertThat(latch.getCount()).as("闩锁应已归零（证明两个执行体确实会合过）").isZero();
    }

    @Test
    @Story("065-S02: 层内并行闩锁证明")
    @DisplayName("065-S02 反例：同一张图纸在 parallelism=1 下必须超时失败（证明闩锁真能拦住串行化）")
    void serialControlMustTimeOut() {
        final var loaded = OrchestrationTestSupport.load(TWO_INDEPENDENT_STEPS, "065-S02-ctrl", RUN);
        final CountDownLatch latch = new CountDownLatch(2);
        loaded.recorders.get("m1").awaitsLatch(latch, "065-S02 反例 m1 超时（预期）");
        loaded.recorders.get("m2").awaitsLatch(latch, "065-S02 反例 m2 超时（预期）");

        final BlueprintException ex = catchThrowableOfType(
                () -> loaded.execute("U-065-S02-ctrl-" + RUN, 1, new TraceForest()),
                BlueprintException.class);

        assertThat(ex).as("parallelism=1 时同层两步无法会合，execute 必须抛 BlueprintException；"
                + "若它正常返回，说明本用例的闩锁构造拦不住串行化故障，正例 PASS 不可信").isNotNull();
        assertThat(ex.getMessage()).as("异常消息应带图纸 id 与失败步骤 id 定位")
                .contains("blueprint id=T-065-S02-dag").contains("step id=");
    }

    @ParameterizedTest(name = "parallelism={0} → 期望成功={1}")
    @CsvSource({"1, false", "4, true"})
    @Story("065-S20: 并行度由构造期参数决定且运行期生效")
    @DisplayName("065-S20 同一张 dag 图纸：parallelism=1 超时失败、=4 通过")
    void parallelismIsAConstructionTimeParameter(final int parallelism, final boolean expectSuccess) {
        final var loaded = OrchestrationTestSupport.load(TWO_INDEPENDENT_STEPS, "065-S20", RUN);
        final CountDownLatch latch = new CountDownLatch(2);
        loaded.recorders.get("m1").awaitsLatch(latch, "065-S20 m1 等不到 m2（parallelism=" + parallelism + "）");
        loaded.recorders.get("m2").awaitsLatch(latch, "065-S20 m2 等不到 m1（parallelism=" + parallelism + "）");

        if (expectSuccess) {
            final BlueprintResult result =
                    loaded.execute("U-065-S20-" + RUN, parallelism, new TraceForest());
            assertThat(result.stepOutcomes()).as("parallelism=%s 应能跑完同层两步", parallelism).hasSize(2);
        } else {
            assertThatThrownBy(() -> loaded.execute("U-065-S20-" + RUN, parallelism, new TraceForest()))
                    .as("parallelism=%s 时同层无法会合，必须失败——证明并行度确实由构造期参数决定", parallelism)
                    .isInstanceOf(BlueprintException.class);
        }
    }

    @ParameterizedTest(name = "parallelism={0} 构造期即拒")
    @ValueSource(ints = {0, -1})
    @Story("065-S20: 并行度由构造期参数决定且运行期生效")
    @DisplayName("065-S20 非法并行度构造期拒绝（L2-065 §6，设计细档 §11.2 更正项）")
    void illegalParallelismRejectedAtConstruction(final int parallelism) {
        final TraceForest forest = new TraceForest();
        assertThatThrownBy(() -> new BlueprintScheduler(forest, parallelism))
                .as("parallelism=%s 属非法值，构造期就应拒绝而不是运行期静默降级", parallelism)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parallelism");
    }

    @Test
    @Story("065-S20: 并行度由构造期参数决定且运行期生效")
    @DisplayName("065-S20 增强证据：合法并行度下构造成功且调度器可关闭（运行期不可变，无 setter）")
    void schedulerExposesNoParallelismMutator() {
        final TraceForest forest = new TraceForest();
        try (BlueprintScheduler scheduler = new BlueprintScheduler(forest, 4)) {
            assertThat(scheduler).isNotNull();
        }
        // 运行期不可变：公开面上不存在任何可改并行度的方法（只读反射核对公开 API）
        final List<String> mutators = java.util.Arrays.stream(BlueprintScheduler.class.getMethods())
                .map(java.lang.reflect.Method::getName)
                .filter(n -> n.toLowerCase().contains("parallel"))
                .toList();
        assertThat(mutators).as("BlueprintScheduler 公开面不应暴露任何并行度修改入口").isEmpty();
    }
}

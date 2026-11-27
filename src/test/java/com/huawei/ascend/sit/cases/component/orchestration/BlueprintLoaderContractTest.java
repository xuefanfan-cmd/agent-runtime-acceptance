/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.openjiuwen.agents.orchestration.BlueprintLoader;
import com.openjiuwen.agents.orchestration.BlueprintResult;
import com.openjiuwen.agents.orchestration.BlueprintSpec;
import com.openjiuwen.agents.orchestration.StepOutcome;
import com.openjiuwen.agents.reactrails.loopforest.observability.TraceForest;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 065-S19：装载契约——{@code input}/{@code output} 两键装载器不消费。
 *
 * <p>Oracle 来源：FEAT-065 §3「图纸 YAML」；L2-065 §2.1 图纸 YAML 契约表末行
 * 「{@code input/output} \| — \| **语义固定(input=user/output=last)，装载器不消费**」。
 *
 * <p>判据：同一张流水线图纸的**带键**与**不带键**两个变体，其装载产物 {@code spec}
 * （type/members/steps，记录类 ⇒ 结构相等即逐项相等）、执行序、收口产物**完全一致**。
 * 若装载器悄悄消费了这两键（例如据此改投影或改收口），三者中必有一处出现差异。
 *
 * <p>不应断言：这两键在**宿主/引擎侧**的语义落地（L2 说的是"语义固定"，
 * 而本特性只承诺装载器不消费；宿主侧行为归 FEAT-067）。
 */
@Tag("component")
@Tag("feat-065")
@Feature("FEAT-065: 确定性编排层(蓝图调度)")
class BlueprintLoaderContractTest {

    private static final String RUN = OrchestrationTestSupport.uid();

    private static final String WITHOUT_IO_KEYS = """
            blueprint:
              id: T-065-S19
              type: pipeline
              members:
                - {id: m1, instruction: "汇总技术要点"}
                - {id: m2, instruction: "汇总合规要点"}
              steps:
                - {id: s1, member: m1}
                - {id: s2, member: m2}
            """;

    private static final String WITH_IO_KEYS = """
            blueprint:
              id: T-065-S19
              type: pipeline
              input: some-declared-input-key
              output: some-declared-output-key
              members:
                - {id: m1, instruction: "汇总技术要点"}
                - {id: m2, instruction: "汇总合规要点"}
              steps:
                - {id: s1, member: m1}
                - {id: s2, member: m2}
            """;

    @Test
    @Story("065-S19: 装载契约——input/output 键装载器不消费")
    @DisplayName("065-S19 带 input/output 键与不带：spec、执行序、收口产物完全一致")
    void inputOutputKeysAreNotConsumedByTheLoader() {
        final BlueprintSpec plain = BlueprintLoader.load(WITHOUT_IO_KEYS);
        final BlueprintSpec withKeys = BlueprintLoader.load(WITH_IO_KEYS);

        // ① 装载产物逐项相等（记录类的结构相等覆盖 type/members/steps）
        assertThat(withKeys).as("带 input/output 键的装载产物应与不带的**逐项相等**").isEqualTo(plain);
        assertThat(withKeys.type()).isEqualTo(plain.type());
        assertThat(withKeys.members()).containsExactlyElementsOf(plain.members());
        assertThat(withKeys.steps()).containsExactlyElementsOf(plain.steps());
        assertThat(withKeys.id()).isEqualTo(plain.id()).isEqualTo("T-065-S19");

        // ② 执行序与收口产物一致（同一组确定性执行体、同一随机后缀 ⇒ 产物逐字相同）
        final var plainLoaded = OrchestrationTestSupport.load(WITHOUT_IO_KEYS, "065-S19", RUN);
        final var keysLoaded = OrchestrationTestSupport.load(WITH_IO_KEYS, "065-S19", RUN);
        final BlueprintResult plainResult =
                plainLoaded.execute("U-065-S19-" + RUN, 4, new TraceForest());
        final BlueprintResult keysResult =
                keysLoaded.execute("U-065-S19-" + RUN, 4, new TraceForest());

        assertThat(keysResult.stepOutcomes()).extracting(StepOutcome::stepId)
                .as("执行序应一致")
                .containsExactlyElementsOf(plainResult.stepOutcomes().stream()
                        .map(StepOutcome::stepId).toList());
        assertThat(keysResult.stepOutcomes()).extracting(StepOutcome::output)
                .as("各步产物应逐字一致（证明投影未被这两键影响）")
                .containsExactlyElementsOf(plainResult.stepOutcomes().stream()
                        .map(StepOutcome::output).toList());
        assertThat(keysResult.finalOutput()).as("收口产物应一致")
                .isEqualTo(plainResult.finalOutput());
        assertThat(plainResult.finalOutputText()).as("收口产物不应为缺席").isPresent();

        // ③ 两个变体的成员实收步骤目标位一致（装载器未把这两键拼进投影）
        assertThat(keysLoaded.recorders.get("m2").subGoal(0))
                .as("带键变体的下游实收步骤目标位应与不带键的一致")
                .isEqualTo(plainLoaded.recorders.get("m2").subGoal(0));
        assertThat(keysLoaded.recorders.get("m2").subGoal(0))
                .as("这两键的字面值不应出现在投影里")
                .doesNotContain("some-declared-input-key")
                .doesNotContain("some-declared-output-key");
    }
}

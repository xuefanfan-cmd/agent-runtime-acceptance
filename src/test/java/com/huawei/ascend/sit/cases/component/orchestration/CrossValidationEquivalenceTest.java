/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.openjiuwen.agents.orchestration.BlueprintLoader;
import com.openjiuwen.agents.orchestration.BlueprintResult;
import com.openjiuwen.agents.orchestration.BlueprintSpec;
import com.openjiuwen.agents.orchestration.MemberSpec;
import com.openjiuwen.agents.orchestration.StepOutcome;
import com.openjiuwen.agents.orchestration.StepSpec;
import com.openjiuwen.agents.reactrails.loopforest.observability.TraceForest;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 066-S12：交叉验证编译产物与**手写等价 DAG** 完全一致（无隐藏通道）。
 *
 * <p>Oracle 来源：FEAT-066 §5.1.1「图纸一旦装载，执行语义与一张手写等价 DAG **完全一致**
 * ——无隐藏通道」；FEAT-066 §6「不得在执行层为交叉验证增加专用分支」。
 *
 * <p>与 065-S21 / 066-S13 的静态断言互为补充：那两条证明**源码里没有专用字面量**，
 * 本条证明**行为上没有专用通道**——同一组确定性执行体分别跑编译产物与手写 DAG，
 * 结构（成员/步骤/依赖/skipIf/类型/id）与执行结果（登记序、各步 skipped、各步产物、收口产物）
 * 必须逐项相同。出现任何差异即说明存在交叉验证专用通道。
 *
 * <p><b>手写 DAG 的构造方式</b>：成员与指令从编译产物**逐字取出**再写成 dag 图纸
 * （不是把实现文案硬编码进测试）——这样"逐字相同"是被构造保证的，
 * 而"结构与执行结果相同"才是被断言检验的，符合"不断言互审指令具体文案（OUT/non-gating）"的口径。
 */
@Tag("component")
@Tag("feat-066")
@Feature("FEAT-066: 交叉验证与汇总审计")
class CrossValidationEquivalenceTest {

    private static final String RUN = OrchestrationTestSupport.uid();

    private static final String CROSS_VALIDATION = """
            blueprint:
              id: T-066-S12
              type: cross_validation
              review: true
              perspectives:
                - {id: pa, instruction: "从技术可行性视角作答"}
                - {id: pb, instruction: "从合规风险视角作答"}
              auditor: {id: aud, instruction: "汇总各方结论并给出审计意见"}
            """;

    @Test
    @Story("066-S12: 与手写等价 DAG 完全一致（无隐藏通道）")
    @DisplayName("066-S12 编译产物与手写等价 DAG：spec 逐项相同 + 执行结果逐项相同")
    void compiledSpecIsIdenticalToHandWrittenEquivalentDag() {
        final BlueprintSpec compiled = BlueprintLoader.load(CROSS_VALIDATION);
        final String handWrittenYaml = toDagYaml(compiled);
        final BlueprintSpec handWritten = BlueprintLoader.load(handWrittenYaml);

        // ① 结构逐项相同（BlueprintSpec 是记录类 ⇒ equals 覆盖 id/type/members/steps）
        assertThat(handWritten).as("手写等价 DAG 的装载产物应与交叉验证编译产物**逐项相等**；"
                + "手写图纸原文=%s", handWrittenYaml).isEqualTo(compiled);
        assertThat(handWritten.type()).isEqualTo(compiled.type());
        assertThat(handWritten.id()).isEqualTo(compiled.id());
        assertThat(handWritten.members()).containsExactlyElementsOf(compiled.members());
        assertThat(handWritten.steps()).containsExactlyElementsOf(compiled.steps());
        // 逐字段再核一遍，使证据可读（不只看一个 equals 布尔）
        for (int i = 0; i < compiled.steps().size(); i++) {
            final StepSpec a = compiled.steps().get(i);
            final StepSpec b = handWritten.steps().get(i);
            assertThat(b.id()).as("第 %s 步的 id 应相同", i).isEqualTo(a.id());
            assertThat(b.member()).as("第 %s 步的成员应相同", i).isEqualTo(a.member());
            assertThat(b.dependsOn()).as("第 %s 步的依赖集应相同", i).containsExactlyElementsOf(a.dependsOn());
            assertThat(b.skipIf()).as("第 %s 步的 skipIf 应相同", i).isEqualTo(a.skipIf());
        }

        // ② 用**同一组确定性执行体**分别执行，结果逐项相同
        final BlueprintResult compiledResult = executeWith("066-S12-cv", compiled);
        final BlueprintResult handResult = executeWith("066-S12-dag", handWritten);

        assertThat(handResult.stepOutcomes()).extracting(StepOutcome::stepId)
                .as("登记序应完全一致")
                .containsExactlyElementsOf(compiledResult.stepOutcomes().stream()
                        .map(StepOutcome::stepId).collect(Collectors.toList()));
        assertThat(handResult.stepOutcomes()).extracting(StepOutcome::memberId)
                .as("各步成员应完全一致")
                .containsExactlyElementsOf(compiledResult.stepOutcomes().stream()
                        .map(StepOutcome::memberId).collect(Collectors.toList()));
        assertThat(handResult.stepOutcomes()).extracting(StepOutcome::skipped)
                .as("各步 skipped 应完全一致")
                .containsExactlyElementsOf(compiledResult.stepOutcomes().stream()
                        .map(StepOutcome::skipped).collect(Collectors.toList()));
        assertThat(handResult.stepOutcomes()).extracting(StepOutcome::output)
                .as("各步产物应逐字一致（同一执行体 ⇒ 同一标记串）")
                .containsExactlyElementsOf(compiledResult.stepOutcomes().stream()
                        .map(StepOutcome::output).collect(Collectors.toList()));
        assertThat(handResult.finalOutput()).as("收口产物应完全一致")
                .isEqualTo(compiledResult.finalOutput());
        assertThat(compiledResult.stepOutcomes()).as("应有 5 步（2 视角 + 2 互审 + 1 审计）").hasSize(5);
    }

    /** 用同一套标记规则装配执行体后执行一次（标记只依赖成员 id ⇒ 两次执行的产物逐字相同）。 */
    private static BlueprintResult executeWith(final String caseId, final BlueprintSpec spec) {
        final var loaded = new OrchestrationTestSupport.Loaded(spec,
                OrchestrationTestSupport.recorders(spec, caseId, RUN));
        // 标记串只按成员 id 生成，不带 caseId ⇒ 两次执行的产物可逐字比对
        loaded.recorders.forEach((memberId, rec) -> rec.returns("EQ-066-S12-" + memberId + "-" + RUN));
        return loaded.execute("U-066-S12-" + RUN, 4, new TraceForest());
    }

    /** 把编译产物**逐字**写成一张手写 dag 图纸（成员指令原样搬，不做任何改写）。 */
    private static String toDagYaml(final BlueprintSpec spec) {
        final StringBuilder sb = new StringBuilder();
        sb.append("blueprint:\n");
        sb.append("  id: ").append(spec.id()).append('\n');
        sb.append("  type: dag\n");
        sb.append("  members:\n");
        for (MemberSpec member : spec.members()) {
            sb.append("    - {id: ").append(member.id())
                    .append(", instruction: \"").append(member.instruction()).append("\"}\n");
        }
        sb.append("  steps:\n");
        for (StepSpec step : spec.steps()) {
            sb.append("    - {id: ").append(step.id())
                    .append(", member: ").append(step.member());
            final List<String> deps = step.dependsOn();
            if (!deps.isEmpty()) {
                sb.append(", depends_on: [").append(String.join(", ", deps)).append(']');
            }
            sb.append("}\n");
        }
        return sb.toString();
    }
}

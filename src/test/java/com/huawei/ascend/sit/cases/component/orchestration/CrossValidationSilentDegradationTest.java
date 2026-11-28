/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.openjiuwen.agents.orchestration.BlueprintException;
import com.openjiuwen.agents.orchestration.BlueprintLoader;
import com.openjiuwen.agents.orchestration.BlueprintSpec;
import com.openjiuwen.agents.orchestration.BlueprintType;
import com.openjiuwen.agents.orchestration.MemberSpec;
import com.openjiuwen.agents.orchestration.StepSpec;
import com.huawei.ascend.sit.cases.component.orchestration.OrchestrationTestSupport.Recording;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * FEAT-066-E3：{@code review} 非法形态与多余 {@code steps} 键——**装载期 fail-fast**（主断言已完全反转）。
 *
 * <p>⚠️ <b>§11.2 权威更正</b>：本条在旧基线（docs {@code 561f02dd} / solution {@code 53c1a174a}）下的
 * 主断言是"装载成功且无 {@code r_} 层"（当时实现为**静默降级**）。新基线下该期望已作废：
 * Feature §2 改为「仅 true/false/缺省三种形态合法，其余形态**装载期拒绝**」；
 * §5.1.3 新增两行错误表「互审开关非法形态(映射/列表/非布尔标量) | 装载期拒绝，错误含图纸 id 与键定位
 * ——**不静默降级**」与「图纸声明 steps 键 | 装载期拒绝(steps 由视角/审计展开，声明即写错)」；
 * L2-066 §2.1 / §7.2 同步。
 *
 * <p><b>本用例因此是一条回归闸门</b>：若任一非法形态被静默接受（即静默降级复活），判 FAIL。
 * GAP-066-03 / GAP-066-04 据此关闭（{@code degraded} → {@code planned}，产品风险缺口消除）。
 *
 * <p><b>不断言</b>：{@code review: TRUE} / {@code review: True} 等大小写变体的行为
 * （GAP-066-07：Feature 只写 "true/false"，实现是 {@code equalsIgnoreCase} ⇒ 设计未明确大小写口径，
 * 标 {@code partial}，**不猜**）。
 * 不应断言：异常消息的完整措辞（只断言含图纸 id 与键定位）。
 */
@Tag("component")
@Tag("feat-066")
@Feature("FEAT-066: 交叉验证与汇总审计")
class CrossValidationSilentDegradationTest {

    private static final String RUN = OrchestrationTestSupport.uid();

    /** 三视角 + 审计员的合法骨架，{@code %s} 处插入待测的 review / steps 写法。 */
    private static final String SKELETON = """
            blueprint:
              id: %s
              type: cross_validation
              perspectives:
                - {id: pa, instruction: "从技术可行性视角作答"}
                - {id: pb, instruction: "从合规风险视角作答"}
              auditor: {id: aud, instruction: "汇总各方结论并给出审计意见"}
            %s
            """;

    private record IllegalForm(String label, String blueprintId, String fragment, String expectedLocator) {
    }

    static Stream<Arguments> illegalForms() {
        return Stream.of(
                new IllegalForm("① review 为映射", "T-FEAT-066-E3-map", "  review: {enabled: true}",
                        "review must be a boolean scalar"),
                new IllegalForm("② review 为列表", "T-FEAT-066-E3-list", "  review: [true]",
                        "review must be a boolean scalar"),
                new IllegalForm("③ review 为非布尔标量", "T-FEAT-066-E3-num", "  review: 1",
                        "review must be a boolean scalar"),
                new IllegalForm("④ review 为非 true/false 字符串", "T-FEAT-066-E3-str", "  review: \"enabled\"",
                        "review must be a boolean scalar"),
                new IllegalForm("⑤ review 合法但多写 steps 键", "T-FEAT-066-E3-steps",
                        "  review: true\n  steps:\n    - {id: s1, member: pa}",
                        "must not declare 'steps'"))
                .map(v -> Arguments.of(v.label(), v));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("illegalForms")
    @Story("FEAT-066-E3: review 非法形态与多余 steps 键一律装载期拒绝（不静默降级）")
    @DisplayName("FEAT-066-E3 非法 review 形态与多写 steps 键：装载期拒绝 + 含图纸 id 与键定位 + 执行体零调用")
    void illegalReviewAndStepsAreRejectedAtLoad(final String label, final IllegalForm form) {
        assertRejected(form.blueprintId(), form.fragment(), form.expectedLocator());
    }

    /**
     * 共用的装载拒绝断言（FEAT-066-E2 的后两个实例复用本实现，见 §11.2「后两个与 FEAT-066-E3 共用断言实现」）。
     *
     * @param blueprintId     图纸 id（形状/交叉验证编译族的消息必带）
     * @param fragment        待插入骨架的非法写法
     * @param expectedLocator 期望的键定位子串
     */
    static void assertRejected(final String blueprintId, final String fragment, final String expectedLocator) {
        final String yaml = SKELETON.formatted(blueprintId, fragment);
        // 装载期即拒 ⇒ 执行体计数必须为 0（按图纸可能声明的成员逐个建计数器）
        final Map<String, Recording> recorders = new LinkedHashMap<>();
        for (String memberId : List.of("pa", "pb", "aud")) {
            recorders.put(memberId, new Recording(memberId,
                    OrchestrationTestSupport.marker("FEAT-066-E3", memberId, RUN)));
        }

        final BlueprintException ex =
                catchThrowableOfType(() -> BlueprintLoader.load(yaml), BlueprintException.class);

        assertThat(ex).as("非法写法必须装载期拒绝（静默接受即静默降级复活 = 回归）；图纸原文=%s", yaml)
                .isNotNull();
        assertThat(ex.getMessage()).as("消息应含图纸 id；实收=[%s]", ex.getMessage())
                .contains("blueprint id=" + blueprintId);
        assertThat(ex.getMessage()).as("消息应含键定位 %s；实收=[%s]", expectedLocator, ex.getMessage())
                .contains(expectedLocator);
        recorders.forEach((memberId, rec) -> assertThat(rec.calls())
                .as("装载期即拒 ⇒ 成员 %s 的执行体不应被调用", memberId).isZero());
    }

    @Test
    @Story("FEAT-066-E3: review 合法形态对照组")
    @DisplayName("FEAT-066-E3⑥ 对照组：review true/false/缺省三张合法图纸均装载成功，仅 true 有 r_ 层")
    void legalReviewFormsAreAccepted() {
        // review: true → 有互审层
        final BlueprintSpec withReview = BlueprintLoader.load(
                SKELETON.formatted("T-FEAT-066-E3-ok-true", "  review: true"));
        assertThat(withReview.type()).isEqualTo(BlueprintType.DAG);
        assertThat(withReview.steps()).extracting(StepSpec::id)
                .as("review:true 应派生 r_ 互审层")
                .contains("r_pa", "r_pb", "audit");
        assertThat(withReview.members()).extracting(MemberSpec::id)
                .as("review:true 应派生 review_ 互审成员")
                .contains("review_pa", "review_pb");
        assertThat(withReview.steps()).as("review:true 应共 5 步（2 视角 + 2 互审 + 1 审计）").hasSize(5);

        // review: false → 无互审层
        final BlueprintSpec reviewFalse = BlueprintLoader.load(
                SKELETON.formatted("T-FEAT-066-E3-ok-false", "  review: false"));
        assertThat(reviewFalse.type()).isEqualTo(BlueprintType.DAG);
        assertThat(reviewFalse.steps()).extracting(StepSpec::id)
                .as("review:false 不应派生 r_ 互审层")
                .containsExactly("p_pa", "p_pb", "audit");
        assertThat(reviewFalse.members()).extracting(MemberSpec::id)
                .as("review:false 不应派生 review_ 互审成员")
                .containsExactly("pa", "pb", "aud");

        // review 缺省 → 与 false 同构
        final BlueprintSpec reviewAbsent = BlueprintLoader.load(
                SKELETON.formatted("T-FEAT-066-E3-ok-absent", ""));
        assertThat(reviewAbsent.steps()).extracting(StepSpec::id)
                .as("review 缺省不应派生 r_ 互审层")
                .containsExactly("p_pa", "p_pb", "audit");
        assertThat(reviewAbsent.members()).extracting(MemberSpec::id)
                .containsExactly("pa", "pb", "aud");
        // 缺省与显式 false 的编译产物除 id 外结构一致
        assertThat(reviewAbsent.steps()).containsExactlyElementsOf(reviewFalse.steps());
        assertThat(reviewAbsent.members()).containsExactlyElementsOf(reviewFalse.members());
    }
}

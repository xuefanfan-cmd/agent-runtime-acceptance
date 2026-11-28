/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.openjiuwen.agents.orchestration.BlueprintException;
import com.openjiuwen.agents.orchestration.BlueprintLoader;
import com.openjiuwen.agents.orchestration.BlueprintSpec;
import com.openjiuwen.agents.orchestration.BlueprintType;
import com.openjiuwen.agents.orchestration.MemberSpec;
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
 * FEAT-066-E1（标识碰撞拒绝，**新基线 4 种形态全部可判定**）与 FEAT-066-E2（装载拒绝，§11.2 扩为 6 例）。
 *
 * <p>Oracle 来源（§11.2 权威更正）：
 * <ul>
 *   <li><b>S09</b>：Feature §2（新基线）「**互审启用时**，视角成员标识与**派生出的互审成员标识
 *       (review_&lt;视角 id&gt;)重名**即 fail-fast」+ §4「标识碰撞」前置条件"互审启用时"
 *       + §5.1.3 错误表；L2-066 §7.2。判定机制是"**重名**"而非"前缀"。</li>
 *   <li><b>S11</b>：Feature §5.1.3 错误表 + §3「成员声明与 FEAT-065 图纸成员同构」；
 *       §11.2 把 4 例扩为 6 例（后 2 例与 FEAT-066-E3 共用断言实现）。</li>
 * </ul>
 *
 * <p><b>S09 的 ②③ 被拒即判 FAIL（过度拦截）</b>——这正是旧基线下的歧义区，
 * 新口径已明确为"纳"（GAP-066-01 关闭）。
 *
 * <p><b>错误消息的族口径</b>按 L2-066/L2-065 §2.1「交叉验证编译族含图纸 id；
 * 解析层/顶层校验族（id 缺失/**成员声明违规**）只给定位、不带图纸 id」。
 * 据此，S11 的「perspectives 为空列表」与「视角 instruction 为空」属**成员声明违规**（解析层族），
 * 消息**不带**图纸 id——这与设计 §6 原文"①②③ 消息含图纸 id"对①的说法不符，
 * 以更权威的分族口径为准，并已在批次记录 §7C 登记该文档内部不一致。
 *
 * <p>不应断言：异常消息的完整措辞（只断言含图纸 id 与成员定位）。
 */
@Tag("component")
@Tag("feat-066")
@Feature("FEAT-066: 交叉验证与汇总审计")
class CrossValidationLoadRejectionTest {

    private static final String RUN = OrchestrationTestSupport.uid();

    private record CollisionForm(String label, String blueprintId, String yaml, boolean expectReject,
            String expectedLocator) {
    }

    static Stream<Arguments> collisionForms() {
        return Stream.of(
                // ① review:true + 视角含 review_a 且另有视角 a ⇒ 派生的 review_a 与已声明视角 review_a 重名 → 拒
                new CollisionForm("① review:true + 视角含 review_a 且另有视角 a → 拒", "T-FEAT-066-E1-reject", """
                        blueprint:
                          id: T-FEAT-066-E1-reject
                          type: cross_validation
                          review: true
                          perspectives:
                            - {id: review_a, instruction: "已占用互审命名的视角"}
                            - {id: a, instruction: "触发派生 review_a 的视角"}
                          auditor: {id: aud, instruction: "汇总审计"}
                        """, true, "duplicate member id=review_a"),
                // ② review 缺省 + 视角 id = review_x ⇒ 互审未启用、无派生成员 ⇒ 纳
                new CollisionForm("② review 缺省 + 视角 id = review_x → 纳", "T-FEAT-066-E1-absent", """
                        blueprint:
                          id: T-FEAT-066-E1-absent
                          type: cross_validation
                          perspectives:
                            - {id: review_x, instruction: "视角甲"}
                            - {id: pb, instruction: "视角乙"}
                          auditor: {id: aud, instruction: "汇总审计"}
                        """, false, null),
                // ③ review:true + 视角 {review_x, a, b}（**无**视角 x）⇒ 派生 review_review_x/review_a/review_b，无重名 → 纳
                new CollisionForm("③ review:true + 视角 {review_x,a,b}（无视角 x）→ 纳", "T-FEAT-066-E1-prefix", """
                        blueprint:
                          id: T-FEAT-066-E1-prefix
                          type: cross_validation
                          review: true
                          perspectives:
                            - {id: review_x, instruction: "视角甲"}
                            - {id: a, instruction: "视角乙"}
                            - {id: b, instruction: "视角丙"}
                          auditor: {id: aud, instruction: "汇总审计"}
                        """, false, null),
                // ④ 对照组：review:true + 视角 id 全不以 review_ 开头 → 纳
                new CollisionForm("④ 对照组：review:true + 常规视角 id → 纳", "T-FEAT-066-E1-normal", """
                        blueprint:
                          id: T-FEAT-066-E1-normal
                          type: cross_validation
                          review: true
                          perspectives:
                            - {id: pa, instruction: "视角甲"}
                            - {id: pb, instruction: "视角乙"}
                          auditor: {id: aud, instruction: "汇总审计"}
                        """, false, null))
                .map(v -> Arguments.of(v.label(), v));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("collisionForms")
    @Story("FEAT-066-E1: 标识碰撞——互审启用时按重名判定 fail-fast")
    @DisplayName("FEAT-066-E1 碰撞判定四形态：①拒（含图纸 id 与碰撞成员 id）②③④纳（被拒即过度拦截 FAIL）")
    void collisionGuardFourForms(final String label, final CollisionForm form) {
        final Map<String, Recording> recorders = new LinkedHashMap<>();
        for (String memberId : List.of("pa", "pb", "aud", "a", "b", "review_a", "review_x")) {
            recorders.put(memberId, new Recording(memberId,
                    OrchestrationTestSupport.marker("FEAT-066-E1", memberId, RUN)));
        }

        final BlueprintException ex =
                catchThrowableOfType(() -> BlueprintLoader.load(form.yaml()), BlueprintException.class);

        if (form.expectReject()) {
            assertThat(ex).as("%s：碰撞必须被拦（装载成功即碰撞未拦）", form.label()).isNotNull();
            assertThat(ex.getMessage()).as("%s：消息应含图纸 id；实收=[%s]", form.label(), ex.getMessage())
                    .contains("blueprint id=" + form.blueprintId());
            assertThat(ex.getMessage())
                    .as("%s：消息应含碰撞的成员 id 定位 %s；实收=[%s]",
                            form.label(), form.expectedLocator(), ex.getMessage())
                    .contains(form.expectedLocator());
            recorders.forEach((memberId, rec) -> assertThat(rec.calls())
                    .as("%s：装载期即拒 ⇒ 成员 %s 执行体不应被调用", form.label(), memberId).isZero());
        } else {
            // ②③④ 被拒即 FAIL（过度拦截）
            assertThat(ex).as("%s：新口径下应**纳**，被拒即过度拦截（旧基线歧义区）；实收消息=[%s]",
                    form.label(), ex == null ? "无" : ex.getMessage()).isNull();
            final BlueprintSpec spec = BlueprintLoader.load(form.yaml());
            assertThat(spec.type()).as("%s：装载后应表现为普通依赖图图纸", form.label())
                    .isEqualTo(BlueprintType.DAG);
            assertThat(spec.id()).isEqualTo(form.blueprintId());
            assertThat(spec.members()).as("%s：成员集合不应为空", form.label()).isNotEmpty();
            if ("③ review:true + 视角 {review_x,a,b}（无视角 x）→ 纳".equals(form.label())) {
                // 判定机制是"重名"而非"前缀"：派生出的 review_review_x 与已声明的 review_x 并存
                final List<String> memberIds = spec.members().stream().map(MemberSpec::id).toList();
                assertThat(memberIds).as("③ 应同时含已声明视角 review_x 与派生互审成员 review_review_x")
                        .contains("review_x", "review_review_x", "review_a", "review_b");
            }
        }
    }

    private record LoadRejection(String label, String yaml, String expectedLocator, boolean expectBlueprintId) {
    }

    static Stream<Arguments> loadRejections() {
        return Stream.of(
                new LoadRejection("① perspectives 为空列表", """
                        blueprint:
                          id: T-FEAT-066-E2-empty
                          type: cross_validation
                          perspectives: []
                          auditor: {id: aud, instruction: "汇总审计"}
                        """, "members must be a non-empty array", false),
                new LoadRejection("② 只有 1 个视角", """
                        blueprint:
                          id: T-FEAT-066-E2-one
                          type: cross_validation
                          perspectives:
                            - {id: pa, instruction: "视角甲"}
                          auditor: {id: aud, instruction: "汇总审计"}
                        """, "requires at least 2 perspectives", true),
                new LoadRejection("③ 缺 auditor 键", """
                        blueprint:
                          id: T-FEAT-066-E2-noauditor
                          type: cross_validation
                          perspectives:
                            - {id: pa, instruction: "视角甲"}
                            - {id: pb, instruction: "视角乙"}
                        """, "requires an auditor mapping", true),
                new LoadRejection("④ 某视角 instruction 为空（成员声明与 FEAT-065 同构）", """
                        blueprint:
                          id: T-FEAT-066-E2-noinstr
                          type: cross_validation
                          perspectives:
                            - {id: pa, instruction: "   "}
                            - {id: pb, instruction: "视角乙"}
                          auditor: {id: aud, instruction: "汇总审计"}
                        """, "member id=pa instruction must be non-blank", false),
                // ⑤⑥ 与 FEAT-066-E3 共用断言实现（§11.2：覆盖台账分别登记 REQ-013/014 与 REQ-011 的承接）
                new LoadRejection("⑤ review 非法形态（与 FEAT-066-E3 共用）", """
                        blueprint:
                          id: T-FEAT-066-E2-badreview
                          type: cross_validation
                          perspectives:
                            - {id: pa, instruction: "视角甲"}
                            - {id: pb, instruction: "视角乙"}
                          auditor: {id: aud, instruction: "汇总审计"}
                          review: {enabled: true}
                        """, "review must be a boolean scalar", true),
                // ⑤b/⑤c/⑤d：FEAT-066-E3（!508 评审 [A]，2026-11-18 复审撤销 GAP-066-07 的 partial 豁免）——
                // L1-066 L37 现行明文「仅 true/false/缺省三种形态合法，其余形态装载期拒绝」。
                // 映射（⑤a）／列表（⑤b）／非布尔标量（⑤c）／非 true-false 字符串（⑤d）均为非法形态。
                new LoadRejection("⑤b review 非法形态·列表 [true]（FEAT-066-E3）", """
                        blueprint:
                          id: T-FEAT-066-E3-list
                          type: cross_validation
                          perspectives:
                            - {id: pa, instruction: "视角甲"}
                            - {id: pb, instruction: "视角乙"}
                          auditor: {id: aud, instruction: "汇总审计"}
                          review: [true]
                        """, "review must be a boolean scalar", true),
                new LoadRejection("⑤c review 非法形态·非布尔标量 1（FEAT-066-E3）", """
                        blueprint:
                          id: T-FEAT-066-E3-scalar
                          type: cross_validation
                          perspectives:
                            - {id: pa, instruction: "视角甲"}
                            - {id: pb, instruction: "视角乙"}
                          auditor: {id: aud, instruction: "汇总审计"}
                          review: 1
                        """, "review must be a boolean scalar", true),
                new LoadRejection("⑤d review 非法形态·非 true-false 字符串 yes（FEAT-066-E3）", """
                        blueprint:
                          id: T-FEAT-066-E3-str
                          type: cross_validation
                          perspectives:
                            - {id: pa, instruction: "视角甲"}
                            - {id: pb, instruction: "视角乙"}
                          auditor: {id: aud, instruction: "汇总审计"}
                          review: "yes"
                        """, "review must be a boolean scalar", true),
                new LoadRejection("⑥ cross_validation 带 steps（与 FEAT-066-E3 共用）", """
                        blueprint:
                          id: T-FEAT-066-E2-withsteps
                          type: cross_validation
                          perspectives:
                            - {id: pa, instruction: "视角甲"}
                            - {id: pb, instruction: "视角乙"}
                          auditor: {id: aud, instruction: "汇总审计"}
                          steps:
                            - {id: s1, member: pa}
                        """, "must not declare 'steps'", true))
                .map(v -> Arguments.of(v.label(), v));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("loadRejections")
    @Story("FEAT-066-E2: 装载拒绝（视角不足 / 审计缺失 / 成员声明同构 / review 非法 / 带 steps）")
    @DisplayName("FEAT-066-E2 六例装载拒绝：均抛 BlueprintException + 定位可见 + 按族核对图纸 id + 执行体零调用")
    void loadRejectionsSixForms(final String label, final LoadRejection form) {
        final Map<String, Recording> recorders = new LinkedHashMap<>();
        for (String memberId : List.of("pa", "pb", "aud")) {
            recorders.put(memberId, new Recording(memberId,
                    OrchestrationTestSupport.marker("FEAT-066-E2", memberId, RUN)));
        }

        final BlueprintException ex =
                catchThrowableOfType(() -> BlueprintLoader.load(form.yaml()), BlueprintException.class);

        assertThat(ex).as("%s：必须抛 BlueprintException", label).isNotNull();
        assertThat(ex.getMessage()).as("%s：消息应含定位 %s；实收=[%s]",
                label, form.expectedLocator(), ex.getMessage()).contains(form.expectedLocator());
        if (form.expectBlueprintId()) {
            assertThat(ex.getMessage()).as("%s：属交叉验证编译族，消息应含图纸 id；实收=[%s]",
                    label, ex.getMessage()).contains("blueprint id=");
        } else {
            assertThat(ex.getMessage()).as("%s：属解析层/成员声明违规族，消息只含定位、不带图纸 id；实收=[%s]",
                    label, ex.getMessage()).doesNotContain("blueprint id=");
        }
        recorders.forEach((memberId, rec) -> assertThat(rec.calls())
                .as("%s：装载期即拒 ⇒ 成员 %s 执行体不应被调用", label, memberId).isZero());
    }

    /**
     * FEAT-066-E3 的**大小写变体**负向格（!508 评审 [A]，2026-11-18 复审）。
     *
     * <p>Oracle：L1-066 L37 现行明文「{@code review} 仅 true/false/缺省三种形态合法，
     * 其余形态**装载期拒绝**」⇒ {@code TRUE}／{@code False} 属非法形态。GAP-066-07 的 partial 豁免
     * （旧结论「设计未明确大小写口径」）已由 2026-11-18 复审**撤销**。
     *
     * <p><b>已知实现事实（r9 基线 {@code common@babb882b9}，BlueprintLoader.parseReviewFlag 源码核实）</b>：
     * 实现用 {@code equalsIgnoreCase("true")/equalsIgnoreCase("false")} 判定字符串形态
     * ⇒ 大小写变体会被**接受**，本负向断言将 FAIL ⇒ 按处置表口径**记开发缺陷**
     * （「实现若忽略大小写接受 → 开发缺陷」，TestPlan !508 §2 D 组），
     * 不作为可收口的 partial、不改判据。
     */
    @Test
    @Story("FEAT-066-E3: review 大小写变体属非法形态 → 装载期拒绝（实现若接受=开发缺陷）")
    @DisplayName("FEAT-066-E3 大小写变体 TRUE/False → 期望装载期拒绝；实现若接受 ⇒ 开发缺陷（GAP-066-07 豁免已撤销）")
    void reviewCaseVariantsAreRejectedAtLoadTime() {
        for (String variant : List.of("TRUE", "False")) {
            final String yaml = """
                    blueprint:
                      id: T-FEAT-066-E3-case
                      type: cross_validation
                      perspectives:
                        - {id: pa, instruction: "视角甲"}
                        - {id: pb, instruction: "视角乙"}
                      auditor: {id: aud, instruction: "汇总审计"}
                      review: %s
                    """.formatted(variant);
            final BlueprintException ex =
                    catchThrowableOfType(() -> BlueprintLoader.load(yaml), BlueprintException.class);
            assertThat(ex).as("review=%s 应按 L1-066 L37 在装载期拒绝（仅 true/false/缺省合法）；"
                            + "实收异常=%s（无异常即实现接受了非法形态 ⇒ 开发缺陷）", variant,
                    ex == null ? "无" : ex.getMessage())
                    .isNotNull();
        }
    }
}

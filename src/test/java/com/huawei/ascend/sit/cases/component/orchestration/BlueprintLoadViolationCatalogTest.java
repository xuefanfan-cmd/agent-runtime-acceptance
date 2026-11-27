/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.openjiuwen.agents.orchestration.BlueprintException;
import com.openjiuwen.agents.orchestration.BlueprintLoader;
import com.huawei.ascend.sit.cases.component.orchestration.OrchestrationTestSupport.Recording;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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
 * 065-S03：装载违规**全集**（对齐 L2-065 §2.1「装载违规唯一权威清单(12 项)」，docs@1c0f4594）。
 *
 * <p>Oracle 来源（测试设计细档 §11.2 的**权威更正**，不再用 §6 旧基线的"Feature 7 项 ∪ javadoc 9 项"并集；
 * 2026-10-20 按 docs@1c0f4594 的 12 项清单完成**内容级重审**后更新编号，见下方"11→12 项"段）：
 * L2-065 §2.1「装载违规唯一权威清单(12 项，Feature §2 与本节以此为唯一口径)」——
 * ①顶层 blueprint 映射缺失 ②图纸 id 空 ③类型不可识别 ④成员标识重复 ⑤成员指令为空
 * ⑥步骤标识重复 ⑦引用未声明成员 ⑧引用未声明步骤(含 DAG 幽灵步骤) ⑨依赖自环 ⑩依赖环(Kahn 残留)
 * ⑪非法跳过策略值——skip_if 取值非 NEVER/DEPS_INCONCLUSIVE(含小写，解析层 default 分支拒绝)
 * ⑫形状违规——pipeline 带 depends_on 或空步骤／dag 空步骤／parallel 带 steps／single 多成员或多步或带
 * depends_on／cross_validation 带 steps 或其 review 键非法形态。
 *
 * <p><b>错误消息按族做完整断言（不再是 {@code partial}）</b>：同节明文
 * 「形状族(分型校验)与交叉验证编译的错误**含图纸 id 与定位**；解析层/顶层校验族
 * (id 缺失/成员与步骤声明违规/skip_if 非法)**给出违规定位但不带图纸 id**」。
 * 因此本用例对每个实例既断言定位子串，也按其所属族断言 {@code blueprint id=} 的**有/无**。
 *
 * <p>清单外的形态（如 {@code steps: []} 之于 parallel、未列举的怪异键）**一律不断言**——
 * 门禁「不得由测试侧自行补写产品规则」，且 NC-06 已记录该判断。
 * <p><b>11→12 项的编号变更（2026-10-20 内容级重审结论）</b>：旧基线把 {@code skip_if} 非法当作
 * "编号外、按解析层族明文一并对齐"的附加两例；docs@1c0f4594 已把它升为**编号 ⑪ 项**
 * （「非法跳过策略值——skip_if 取值非 NEVER/DEPS_INCONCLUSIVE(含小写，解析层 default 分支拒绝)」），
 * 原 ⑪「形状违规」顺延为 ⑫。本用例的 **22 个参数实例、断言与 XML 身份一字未改**
 * （XML {@code name} 为 {@code everyAuthoritativeViolationIsRejectedAtLoad(String, Violation)[1..22]}，
 * 按序号而非显示名生成；skip_if 两例的期望定位串 {@code step id=s2 unknown skip_if=…} 与所属族
 * {@code PARSE} 均沿用），只把 Oracle 引用的**编号**对齐：2 例 skip_if 由 {@code "skip_if"} 改标
 * {@code "⑪"}，9 例形状违规由 {@code "⑪"} 改标 {@code "⑫"}。
 * ⇒ 12 项**逐项有据**（①×2 例、②~⑩ 各 1 例、⑪×2 例、⑫×9 例 = 22 例），无漏项、无测试侧自创项；
 * 065-S03 的覆盖结论与 run-49 的矩阵证据**不变**。
 *
 * <p>另断言：**执行体调用计数 == 0**——装载期即拒，不得产生任何执行（FEAT-065 §2「照图严格执行」）。
 *
 * <p>不应断言：消息的完整措辞（只断言含定位子串）。
 */
@Tag("component")
@Tag("feat-065")
@Feature("FEAT-065: 确定性编排层(蓝图调度)")
class BlueprintLoadViolationCatalogTest {

    private static final String RUN = OrchestrationTestSupport.uid();

    /** 错误消息族：SHAPE 含图纸 id，PARSE 只含定位。 */
    private enum Family { SHAPE, PARSE }

    /** 权威清单第 ⑫ 项「形状违规」的一个具体子形态（skip_if 两例则为第 ⑪ 项）。 */
    private record Violation(String item, String label, String yaml, String blueprintId,
            List<String> memberIds, String expectedLocator, Family family) {
    }

    static Stream<Arguments> violations() {
        return catalog().stream().map(v -> Arguments.of("[" + v.item() + "] " + v.label(), v));
    }

    private static List<Violation> catalog() {
        return List.of(
                // ① 顶层 blueprint 映射缺失（含空 YAML 子形态）
                new Violation("①", "顶层 blueprint 映射缺失", "foo: bar\n", null, List.of(),
                        "missing top-level 'blueprint' mapping", Family.PARSE),
                // 空 YAML 由**解析层**先拒（L2-065 §2.1 解析层族），消息给解析定位而非"缺 blueprint 映射"
                new Violation("①", "顶层 blueprint 映射缺失（空 YAML → 解析层拒绝）", "", null, List.of(),
                        "blueprint yaml is not parseable", Family.PARSE),
                // ② 图纸 id 空
                new Violation("②", "图纸 id 为空白", """
                        blueprint:
                          id: "   "
                          type: single
                          members:
                            - {id: m1, instruction: "独立作答"}
                        """, null, List.of("m1"), "blueprint.id must be non-blank", Family.PARSE),
                // ③ 类型不可识别
                new Violation("③", "类型不可识别", """
                        blueprint:
                          id: T-065-S03-type
                          type: mesh
                          members:
                            - {id: m1, instruction: "独立作答"}
                        """, "T-065-S03-type", List.of("m1"), "unknown blueprint.type=mesh", Family.PARSE),
                // ④ 成员标识重复
                new Violation("④", "成员标识重复", """
                        blueprint:
                          id: T-065-S03-dupmember
                          type: parallel
                          members:
                            - {id: m1, instruction: "技术视角"}
                            - {id: m1, instruction: "合规视角"}
                        """, "T-065-S03-dupmember", List.of("m1"), "duplicate member id=m1", Family.PARSE),
                // ⑤ 成员指令为空
                new Violation("⑤", "成员指令为空白", """
                        blueprint:
                          id: T-065-S03-noinstr
                          type: single
                          members:
                            - {id: m1, instruction: "   "}
                        """, "T-065-S03-noinstr", List.of("m1"),
                        "member id=m1 instruction must be non-blank", Family.PARSE),
                // ⑥ 步骤标识重复
                new Violation("⑥", "步骤标识重复", """
                        blueprint:
                          id: T-065-S03-dupstep
                          type: pipeline
                          members:
                            - {id: m1, instruction: "第一步"}
                          steps:
                            - {id: s1, member: m1}
                            - {id: s1, member: m1}
                        """, "T-065-S03-dupstep", List.of("m1"), "duplicate step id=s1", Family.PARSE),
                // ⑦ 引用未声明成员
                new Violation("⑦", "引用未声明成员", """
                        blueprint:
                          id: T-065-S03-ghostmember
                          type: pipeline
                          members:
                            - {id: m1, instruction: "第一步"}
                          steps:
                            - {id: s1, member: m9}
                        """, "T-065-S03-ghostmember", List.of("m1"),
                        "step id=s1 references undeclared member=m9", Family.PARSE),
                // ⑧ 引用未声明步骤（DAG 幽灵步骤）
                new Violation("⑧", "DAG 幽灵步骤引用", """
                        blueprint:
                          id: T-065-S03-ghoststep
                          type: dag
                          members:
                            - {id: m1, instruction: "第一步"}
                            - {id: m2, instruction: "第二步"}
                          steps:
                            - {id: s1, member: m1}
                            - {id: s2, member: m2, depends_on: [s9]}
                        """, "T-065-S03-ghoststep", List.of("m1", "m2"),
                        "step id=s2 depends_on unknown step=s9", Family.SHAPE),
                // ⑨ 依赖自环
                new Violation("⑨", "依赖自环", """
                        blueprint:
                          id: T-065-S03-selfloop
                          type: dag
                          members:
                            - {id: m1, instruction: "第一步"}
                          steps:
                            - {id: s1, member: m1, depends_on: [s1]}
                        """, "T-065-S03-selfloop", List.of("m1"),
                        "step id=s1 depends on itself", Family.SHAPE),
                // ⑩ 依赖环（Kahn 残留）
                new Violation("⑩", "依赖环", """
                        blueprint:
                          id: T-065-S03-cycle
                          type: dag
                          members:
                            - {id: m1, instruction: "第一步"}
                            - {id: m2, instruction: "第二步"}
                          steps:
                            - {id: s1, member: m1, depends_on: [s2]}
                            - {id: s2, member: m2, depends_on: [s1]}
                        """, "T-065-S03-cycle", List.of("m1", "m2"),
                        "dag has a dependency cycle", Family.SHAPE),
                // ⑫ 形状违规——pipeline 带 depends_on
                new Violation("⑫", "pipeline 带 depends_on", """
                        blueprint:
                          id: T-065-S03-pipedeps
                          type: pipeline
                          members:
                            - {id: m1, instruction: "第一步"}
                            - {id: m2, instruction: "第二步"}
                          steps:
                            - {id: s1, member: m1}
                            - {id: s2, member: m2, depends_on: [s1]}
                        """, "T-065-S03-pipedeps", List.of("m1", "m2"),
                        "pipeline step id=s2 must not declare depends_on", Family.SHAPE),
                // ⑫ 形状违规——pipeline 空步骤
                new Violation("⑫", "pipeline 空步骤", """
                        blueprint:
                          id: T-065-S03-pipeempty
                          type: pipeline
                          members:
                            - {id: m1, instruction: "第一步"}
                          steps: []
                        """, "T-065-S03-pipeempty", List.of("m1"),
                        "pipeline requires non-empty steps", Family.SHAPE),
                // ⑫ 形状违规——dag 空步骤
                new Violation("⑫", "dag 空步骤", """
                        blueprint:
                          id: T-065-S03-dagempty
                          type: dag
                          members:
                            - {id: m1, instruction: "第一步"}
                          steps: []
                        """, "T-065-S03-dagempty", List.of("m1"),
                        "dag requires non-empty steps", Family.SHAPE),
                // ⑫ 形状违规——parallel 带 steps
                new Violation("⑫", "parallel 带 steps", """
                        blueprint:
                          id: T-065-S03-parallelsteps
                          type: parallel
                          members:
                            - {id: m1, instruction: "技术视角"}
                          steps:
                            - {id: s1, member: m1}
                        """, "T-065-S03-parallelsteps", List.of("m1"),
                        "parallel must omit steps", Family.SHAPE),
                // ⑫ 形状违规——single 多成员
                new Violation("⑫", "single 多成员", """
                        blueprint:
                          id: T-065-S03-singlemulti
                          type: single
                          members:
                            - {id: m1, instruction: "技术视角"}
                            - {id: m2, instruction: "合规视角"}
                        """, "T-065-S03-singlemulti", List.of("m1", "m2"),
                        "single requires exactly one member", Family.SHAPE),
                // ⑫ 形状违规——single 多步
                new Violation("⑫", "single 多步", """
                        blueprint:
                          id: T-065-S03-singlesteps
                          type: single
                          members:
                            - {id: m1, instruction: "独立作答"}
                          steps:
                            - {id: s1, member: m1}
                            - {id: s2, member: m1}
                        """, "T-065-S03-singlesteps", List.of("m1"),
                        "single allows at most one step", Family.SHAPE),
                // ⑫ 形状违规——single 带 depends_on
                new Violation("⑫", "single 带 depends_on", """
                        blueprint:
                          id: T-065-S03-singledeps
                          type: single
                          members:
                            - {id: m1, instruction: "独立作答"}
                          steps:
                            - {id: s1, member: m1, depends_on: [s1]}
                        """, "T-065-S03-singledeps", List.of("m1"),
                        "single step must not declare depends_on", Family.SHAPE),
                // ⑫ 形状违规——cross_validation 带 steps（交叉验证编译族）
                new Violation("⑫", "cross_validation 带 steps", """
                        blueprint:
                          id: T-065-S03-cvsteps
                          type: cross_validation
                          perspectives:
                            - {id: p1, instruction: "技术视角"}
                            - {id: p2, instruction: "合规视角"}
                          auditor: {id: a1, instruction: "汇总审计"}
                          steps:
                            - {id: s1, member: p1}
                        """, "T-065-S03-cvsteps", List.of("p1", "p2", "a1"),
                        "cross_validation must not declare 'steps'", Family.SHAPE),
                // ⑫ 形状违规——cross_validation review 键非法形态（交叉验证编译族）
                new Violation("⑫", "cross_validation review 键非法形态", """
                        blueprint:
                          id: T-065-S03-cvreview
                          type: cross_validation
                          perspectives:
                            - {id: p1, instruction: "技术视角"}
                            - {id: p2, instruction: "合规视角"}
                          auditor: {id: a1, instruction: "汇总审计"}
                          review: {mode: strict}
                        """, "T-065-S03-cvreview", List.of("p1", "p2", "a1"),
                        "review must be a boolean scalar", Family.SHAPE),
                // ⑪ 非法跳过策略值（L2 §2.1 第 ⑪ 项，解析层族；小写 / 未知值两例）
                new Violation("⑪", "skip_if 小写被拒（L2 §2.1 第 ⑪ 项，解析层族）", """
                        blueprint:
                          id: T-065-S03-skiplower
                          type: dag
                          members:
                            - {id: m1, instruction: "第一步"}
                            - {id: m2, instruction: "第二步"}
                          steps:
                            - {id: s1, member: m1}
                            - {id: s2, member: m2, depends_on: [s1], skip_if: deps_inconclusive}
                        """, "T-065-S03-skiplower", List.of("m1", "m2"),
                        "step id=s2 unknown skip_if=deps_inconclusive", Family.PARSE),
                new Violation("⑪", "skip_if 未知值被拒（L2 §2.1 第 ⑪ 项，解析层族）", """
                        blueprint:
                          id: T-065-S03-skipunknown
                          type: dag
                          members:
                            - {id: m1, instruction: "第一步"}
                            - {id: m2, instruction: "第二步"}
                          steps:
                            - {id: s1, member: m1}
                            - {id: s2, member: m2, depends_on: [s1], skip_if: SOMETIMES}
                        """, "T-065-S03-skipunknown", List.of("m1", "m2"),
                        "step id=s2 unknown skip_if=SOMETIMES", Family.PARSE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("violations")
    @Story("065-S03: 装载违规全集")
    @DisplayName("065-S03 权威清单逐项：装载期拒绝 + 定位可见 + 分族图纸 id 口径 + 执行体零调用")
    void everyAuthoritativeViolationIsRejectedAtLoad(final String display, final Violation v) {
        assertThat(display).as("参数化显示名不应为空").isNotBlank();
        // Given：按图纸声明的成员建带调用计数器的执行体（装载失败 ⇒ 计数必须为 0）
        final Map<String, Recording> recorders = new LinkedHashMap<>();
        for (String memberId : v.memberIds()) {
            recorders.put(memberId,
                    new Recording(memberId, OrchestrationTestSupport.marker("065-S03", memberId, RUN)));
        }

        // When：装载
        final BlueprintException ex =
                catchThrowableOfType(() -> BlueprintLoader.load(v.yaml()), BlueprintException.class);

        // Then①：抛 BlueprintException（不是别的异常类型）
        assertThat(ex).as("清单项 %s（%s）必须在装载期抛 BlueprintException；若装载成功即校验漏放",
                v.item(), v.label()).isNotNull();

        // Then②：消息含违规定位
        assertThat(ex.getMessage()).as("清单项 %s（%s）的错误消息应含违规定位", v.item(), v.label())
                .contains(v.expectedLocator());

        // Then③：按族断言图纸 id 的有/无（L2-065 §2.1 分族口径，设计细档 §11.2 升为完整断言）
        if (v.family() == Family.SHAPE) {
            assertThat(v.blueprintId()).as("形状族用例必须声明图纸 id 才能做本断言").isNotBlank();
            assertThat(ex.getMessage())
                    .as("清单项 %s（%s）属形状族/交叉验证编译族，消息应含图纸 id", v.item(), v.label())
                    .contains("blueprint id=" + v.blueprintId());
        } else {
            assertThat(ex.getMessage())
                    .as("清单项 %s（%s）属解析层/顶层校验族，消息只含定位、不带图纸 id", v.item(), v.label())
                    .doesNotContain("blueprint id=");
        }

        // Then④：装载期即拒 ⇒ 不产生任何执行
        recorders.forEach((memberId, rec) ->
                assertThat(rec.calls()).as("清单项 %s（%s）：成员 %s 的执行体不应被调用",
                        v.item(), v.label(), memberId).isZero());
    }
}

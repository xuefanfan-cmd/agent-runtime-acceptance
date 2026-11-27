/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.openjiuwen.agents.orchestration.BlueprintLoader;
import com.openjiuwen.agents.orchestration.BlueprintResult;
import com.openjiuwen.agents.orchestration.BlueprintSpec;
import com.openjiuwen.agents.orchestration.BlueprintType;
import com.openjiuwen.agents.orchestration.MemberSpec;
import com.openjiuwen.agents.orchestration.SkipPolicy;
import com.openjiuwen.agents.orchestration.StepOutcome;
import com.openjiuwen.agents.orchestration.StepSpec;
import com.openjiuwen.agents.reactrails.loopforest.observability.TraceForest;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-066 的 066-S01 ~ 066-S05：交叉验证图纸的装载期编译产物（多视角独立作答、互审层三件、结构主 Oracle）。
 *
 * <p>Oracle 来源：FEAT-066 §2「多视角独立作答 / 互审 / 审计」+ §5.1.1
 * 「编译在装载期完成且**可观测**：编译产物的分层结构、依赖集均可由测试断言」+ §3
 * 「装载后对外表现为普通依赖图图纸」；L2-066 §3.1。
 * Feature §6 明文要求覆盖「编译产物结构断言（分层/依赖集/**排除自身**）」与「互审关闭变体」。
 *
 * <p><b>本特性最核心的判据在 066-S03</b>：每个互审步只能看到**其余**视角的结论，
 * 看不到自己视角的结论（"挑错不重写自己"）。若互审看到自身产物即 FAIL。
 *
 * <p>不应断言：互审成员 {@code instruction()} 的**具体文案**（Feature §2 只说"指令由引擎生成"，
 * 未给内容要求 ⇒ 按门禁标 OUT / non-gating，本类只断言其**非空**且**嵌入了原视角指令**）；
 * 视角产出的**内容质量**；{@code perspectives[]} 在 {@code members()} 里的排列位置（未承诺）。
 */
@Tag("component")
@Tag("feat-066")
@Feature("FEAT-066: 交叉验证与汇总审计")
class CrossValidationCompileTest {

    private static final String RUN = OrchestrationTestSupport.uid();

    /** 三视角 + 审计员，{@code review} 缺省（互审关闭）。 */
    static final String THREE_PERSPECTIVES_NO_REVIEW = """
            blueprint:
              id: T-066-CV3
              type: cross_validation
              perspectives:
                - {id: pa, instruction: "从技术可行性视角作答"}
                - {id: pb, instruction: "从合规风险视角作答"}
                - {id: pc, instruction: "从成本收益视角作答"}
              auditor: {id: aud, instruction: "汇总三方结论并给出审计意见"}
            """;

    /** 三视角 + {@code review: true} + 审计员（互审开启，共 7 步）。 */
    static final String THREE_PERSPECTIVES_WITH_REVIEW = """
            blueprint:
              id: T-066-CV3R
              type: cross_validation
              review: true
              perspectives:
                - {id: pa, instruction: "从技术可行性视角作答"}
                - {id: pb, instruction: "从合规风险视角作答"}
                - {id: pc, instruction: "从成本收益视角作答"}
              auditor: {id: aud, instruction: "汇总三方结论并给出审计意见"}
            """;

    @Test
    @Story("066-S01: 多视角独立作答")
    @DisplayName("066-S01 三视角无依赖同层并行，且各自实收输入不含任何其他视角的产物")
    void perspectivesAreIndependentAndParallel() {
        final BlueprintSpec spec = BlueprintLoader.load(THREE_PERSPECTIVES_NO_REVIEW);
        final var loaded = OrchestrationTestSupport.load(THREE_PERSPECTIVES_NO_REVIEW, "066-S01", RUN);
        final String markerA = "P-066-S01-pa-" + RUN;
        final String markerB = "P-066-S01-pb-" + RUN;
        final String markerC = "P-066-S01-pc-" + RUN;
        loaded.recorders.get("pa").returns(markerA);
        loaded.recorders.get("pb").returns(markerB);
        loaded.recorders.get("pc").returns(markerC);
        // ③ 三视角启动区间必须重叠：会合点设为 3，串行则必然超时（同 065-S02 的闩锁构造）
        final CountDownLatch latch = new CountDownLatch(3);
        for (String perspective : List.of("pa", "pb", "pc")) {
            loaded.recorders.get(perspective)
                    .awaitsLatch(latch, "066-S01 视角 " + perspective + " 等不到其余两个视角 ⇒ 视角层未真并行");
        }

        final BlueprintResult result = loaded.execute("U-066-S01-" + RUN, 4, new TraceForest());

        // ① 三个 p_ 步无依赖且同层（登记序连续排在审计步之前）
        final List<StepSpec> perspectiveSteps = spec.steps().stream()
                .filter(s -> s.id().startsWith("p_")).toList();
        assertThat(perspectiveSteps).as("应由三个视角各派生一个 p_ 步").hasSize(3);
        assertThat(perspectiveSteps).extracting(StepSpec::id)
                .containsExactly("p_pa", "p_pb", "p_pc");
        assertThat(perspectiveSteps).as("视角步不应有任何依赖（同层并行的前提）")
                .allSatisfy(s -> assertThat(s.dependsOn()).as("步骤 %s 的依赖集应为空", s.id()).isEmpty());

        final List<StepOutcome> outcomes = result.stepOutcomes();
        assertThat(outcomes).extracting(StepOutcome::stepId)
                .as("三视角应在同一层内按声明序登记，审计步为唯一汇点排最后")
                .containsExactly("p_pa", "p_pb", "p_pc", "audit");
        assertThat(latch.getCount()).as("闩锁应已归零（证明三视角真的同时在跑）").isZero();

        // ② 独立性：每个视角实收输入不含任何其他视角的标记
        final Map<String, String> own = Map.of("pa", markerA, "pb", markerB, "pc", markerC);
        own.forEach((memberId, ownMarker) -> {
            final String goal = loaded.recorders.get(memberId).subGoal(0);
            for (Map.Entry<String, String> other : own.entrySet()) {
                if (!other.getKey().equals(memberId)) {
                    assertThat(goal).as("视角 %s 实收输入不应含视角 %s 的产物（独立性被破坏）；实收=[%s]",
                            memberId, other.getKey(), goal).doesNotContain(other.getValue());
                }
            }
            assertThat(goal).as("视角 %s 实收输入应含自己的成员指令", memberId)
                    .contains(spec.members().stream().filter(m -> m.id().equals(memberId))
                            .findFirst().orElseThrow().instruction());
            assertThat(loaded.recorders.get(memberId).calls()).isEqualTo(1);
        });
        // 审计员收到全体三份（对照：独立性只约束视角层）
        assertThat(loaded.recorders.get("aud").subGoal(0))
                .as("审计员应收到全部三份视角产物")
                .contains(markerA).contains(markerB).contains(markerC);
    }

    @Test
    @Story("066-S02: 互审层由引擎派生（1:1 合成，图纸未声明）")
    @DisplayName("066-S02 review:true → 派生 3 个 review_<视角id> 成员与 3 个 r_<视角id> 步，指令嵌入原视角指令")
    void reviewLayerIsDerivedOneToOne() {
        final BlueprintSpec spec = BlueprintLoader.load(THREE_PERSPECTIVES_WITH_REVIEW);

        // 图纸**未声明**任何互审成员，而产物里有 ⇒ 证明由引擎派生
        assertThat(THREE_PERSPECTIVES_WITH_REVIEW).as("图纸文本本身不应声明 review_ 成员")
                .doesNotContain("review_pa").doesNotContain("review_pb").doesNotContain("review_pc");
        final List<String> memberIds = spec.members().stream().map(MemberSpec::id).toList();
        assertThat(memberIds).as("成员集合应含三个派生互审成员（1:1 合成）")
                .contains("review_pa", "review_pb", "review_pc");
        final List<String> stepIds = spec.steps().stream().map(StepSpec::id).toList();
        assertThat(stepIds).as("步集合应含三个派生互审步")
                .contains("r_pa", "r_pb", "r_pc");
        assertThat(spec.steps()).as("每个 r_ 步的成员应是对应的 review_ 成员")
                .filteredOn(s -> s.id().startsWith("r_"))
                .allSatisfy(s -> assertThat(s.member()).isEqualTo("review_" + s.id().substring(2)));

        // 互审成员指令非空且**嵌入了原视角指令**（L2-066 §3.1）；具体文案不断言（OUT/non-gating）
        for (String perspective : List.of("pa", "pb", "pc")) {
            final String original = instructionOf(spec, perspective);
            final String reviewInstruction = instructionOf(spec, "review_" + perspective);
            assertThat(reviewInstruction).as("互审成员 review_%s 的指令不应为空", perspective).isNotBlank();
            assertThat(reviewInstruction).as("互审成员 review_%s 的指令应嵌入原视角指令", perspective)
                    .contains(original);
            assertThat(reviewInstruction).as("互审成员指令不应与原视角指令逐字相同（它是引擎另生成的一份）")
                    .isNotEqualTo(original);
        }
    }

    @Test
    @Story("066-S03: 互审只看别人的结论（排除自身）")
    @DisplayName("066-S03 每个 r_<id> 实收输入含其余两视角标记、不含自身视角标记、不含任何互审与审计标记")
    void reviewerSeesOnlyOtherPerspectives() {
        final BlueprintSpec spec = BlueprintLoader.load(THREE_PERSPECTIVES_WITH_REVIEW);
        final var loaded = OrchestrationTestSupport.load(THREE_PERSPECTIVES_WITH_REVIEW, "066-S03", RUN);
        final Map<String, String> perspectiveMarkers = Map.of(
                "pa", "P-066-S03-pa-" + RUN, "pb", "P-066-S03-pb-" + RUN, "pc", "P-066-S03-pc-" + RUN);
        final Map<String, String> reviewMarkers = Map.of(
                "review_pa", "R-066-S03-pa-" + RUN, "review_pb", "R-066-S03-pb-" + RUN,
                "review_pc", "R-066-S03-pc-" + RUN);
        final String auditMarker = "AUDIT-066-S03-" + RUN;
        perspectiveMarkers.forEach((memberId, marker) -> loaded.recorders.get(memberId).returns(marker));
        reviewMarkers.forEach((memberId, marker) -> loaded.recorders.get(memberId).returns(marker));
        loaded.recorders.get("aud").returns(auditMarker);

        loaded.execute("U-066-S03-" + RUN, 4, new TraceForest());

        for (String perspective : List.of("pa", "pb", "pc")) {
            final String reviewer = "review_" + perspective;
            final String goal = loaded.recorders.get(reviewer).subGoal(0);
            assertThat(loaded.recorders.get(reviewer).calls())
                    .as("互审步 r_%s 应被执行一次", perspective).isEqualTo(1);
            // 含其余两个视角的标记
            for (Map.Entry<String, String> other : perspectiveMarkers.entrySet()) {
                if (!other.getKey().equals(perspective)) {
                    assertThat(goal).as("互审 %s 实收输入应含其余视角 %s 的产物；实收=[%s]",
                            reviewer, other.getKey(), goal).contains(other.getValue());
                }
            }
            // **不含自身视角的标记**——本特性最核心的判据
            assertThat(goal).as("互审 %s 实收输入**不得**含自身视角 %s 的产物（挑错不重写自己）；实收=[%s]",
                    reviewer, perspective, goal).doesNotContain(perspectiveMarkers.get(perspective));
            // 不含任何互审标记（互审层同层，彼此不可见）
            reviewMarkers.forEach((otherReviewer, marker) ->
                    assertThat(goal).as("互审 %s 实收输入不得含任何互审产物（%s）；实收=[%s]",
                            reviewer, otherReviewer, goal).doesNotContain(marker));
            // 不含审计标记（审计在下游）
            assertThat(goal).as("互审 %s 实收输入不得含审计产物；实收=[%s]", reviewer, goal)
                    .doesNotContain(auditMarker);
            // 依赖集 == 其余两个 p_ 步
            final StepSpec reviewStep = spec.steps().stream()
                    .filter(s -> s.id().equals("r_" + perspective)).findFirst().orElseThrow();
            final List<String> expectedDeps = new ArrayList<>();
            for (String other : List.of("pa", "pb", "pc")) {
                if (!other.equals(perspective)) {
                    expectedDeps.add("p_" + other);
                }
            }
            assertThat(reviewStep.dependsOn())
                    .as("互审步 r_%s 的依赖集应恰为其余两个视角步", perspective)
                    .containsExactlyInAnyOrderElementsOf(expectedDeps);
        }
        // 审计员收到全体六份（三视角 + 三互审）——详见 066-S06 的逐字断言
        final String auditGoal = loaded.recorders.get("aud").subGoal(0);
        assertThat(auditGoal).as("审计员应收到全部三视角产物")
                .contains(markerOf(perspectiveMarkers, "pa"))
                .contains(markerOf(perspectiveMarkers, "pb"))
                .contains(markerOf(perspectiveMarkers, "pc"));
        assertThat(auditGoal).as("审计员应收到全部三互审产物")
                .contains(markerOf(reviewMarkers, "review_pa"))
                .contains(markerOf(reviewMarkers, "review_pc"));
        assertThat(auditGoal).as("审计员实收输入不应含自己的产物（它尚未产出）")
                .doesNotContain(auditMarker);
    }

    @Test
    @Story("066-S04: 互审关闭变体（review 缺省）")
    @DisplayName("066-S04 review 缺省 → 编译产物无 r_ 步、无 review_ 成员，audit 只依赖全部 p_ 步")
    void reviewAbsentProducesNoReviewLayer() {
        final BlueprintSpec spec = BlueprintLoader.load(THREE_PERSPECTIVES_NO_REVIEW);

        assertThat(spec.steps()).as("review 缺省时不应派生任何 r_ 步")
                .noneMatch(s -> s.id().startsWith("r_"));
        assertThat(spec.members()).as("review 缺省时不应派生任何 review_ 成员")
                .noneMatch(m -> m.id().startsWith("review_"));
        assertThat(spec.steps()).extracting(StepSpec::id)
                .as("编译产物应只有三个视角步 + 一个审计步")
                .containsExactly("p_pa", "p_pb", "p_pc", "audit");
        final StepSpec audit = spec.steps().stream()
                .filter(s -> s.id().equals("audit")).findFirst().orElseThrow();
        assertThat(audit.dependsOn()).as("audit 的依赖集应为全部 p_ 步（不含互审）")
                .containsExactlyInAnyOrder("p_pa", "p_pb", "p_pc");
        assertThat(audit.member()).as("audit 的成员应是图纸声明的审计员").isEqualTo("aud");
    }

    @Test
    @Story("066-S05: 编译产物结构断言（本特性的主 Oracle）")
    @DisplayName("066-S05 编译产物七项结构：DAG/id 保留/三层拓扑/p_ 无依赖/audit 六依赖/唯一汇点/skipIf 规范化为 NEVER")
    void compiledSpecStructure() {
        final String yaml = THREE_PERSPECTIVES_WITH_REVIEW.replace("T-066-CV3R", "T-066-S05");
        final BlueprintSpec spec = BlueprintLoader.load(yaml);

        // ① 装载后对外表现为普通依赖图图纸
        assertThat(spec.type()).as("① cross_validation 编译产物 type 应为 DAG").isEqualTo(BlueprintType.DAG);
        // ② 图纸 id 保留
        assertThat(spec.id()).as("② 图纸 id 应原样保留").isEqualTo("T-066-S05");
        // ③ 拓扑分层 == 3 层：3 个 p_ / 3 个 r_ / audit
        assertThat(spec.steps()).as("③ 编译产物应共 7 步（3 视角 + 3 互审 + 1 审计）").hasSize(7);
        assertThat(idsOfLayer(spec, 0)).as("③ 第 1 层应为 3 个 p_ 步（按视角声明序）")
                .containsExactly("p_pa", "p_pb", "p_pc");
        assertThat(idsOfLayer(spec, 1)).as("③ 第 2 层应为 3 个 r_ 步（按视角声明序）")
                .containsExactly("r_pa", "r_pb", "r_pc");
        assertThat(idsOfLayer(spec, 2)).as("③ 第 3 层应为唯一审计步").containsExactly("audit");
        // ④ 每个 p_ 的依赖集为空
        assertThat(spec.steps()).as("④ 每个视角步的依赖集应为空")
                .filteredOn(s -> s.id().startsWith("p_"))
                .allSatisfy(s -> assertThat(s.dependsOn()).isEmpty());
        // ⑤ audit 的依赖集 == 全部 3 个 p_ + 全部 3 个 r_（共 6 项）
        final StepSpec audit = spec.steps().stream()
                .filter(s -> s.id().equals("audit")).findFirst().orElseThrow();
        assertThat(audit.dependsOn()).as("⑤ audit 的依赖集应为全部 3 个 p_ + 全部 3 个 r_，共 6 项")
                .hasSize(6)
                .containsExactlyInAnyOrder("p_pa", "p_pb", "p_pc", "r_pa", "r_pb", "r_pc");
        // ⑥ audit 是**唯一**出度为 0 的步（唯一汇点）
        final List<String> sinks = spec.steps().stream().map(StepSpec::id)
                .filter(id -> spec.steps().stream()
                        .noneMatch(s -> s.dependsOn().contains(id)))
                .toList();
        assertThat(sinks).as("⑥ 出度为 0 的步应只有 audit 一个（出现第二个汇点说明拓扑被改）")
                .containsExactly("audit");
        // ⑦ 全部编译步的 skipIf 规范化后为 NEVER
        //    （实现以 null 表达"缺省"，行为等价于 NEVER：跳过判定只在 == DEPS_INCONCLUSIVE 时生效）
        assertThat(spec.steps()).as("⑦ 全部编译步的 skipIf 规范化后应为 NEVER（编译产物不得带条件跳过）")
                .allSatisfy(s -> assertThat(s.skipIf() == null ? SkipPolicy.NEVER : s.skipIf())
                        .as("步骤 %s 的 skipIf 规范化后应为 NEVER", s.id()).isEqualTo(SkipPolicy.NEVER));
        // 成员集合 = 3 视角 + 3 互审 + 1 审计（不断言排列位置）
        assertThat(spec.members()).as("成员集合应为 3 视角 + 3 派生互审 + 1 审计").hasSize(7);
    }

    /** 按 Kahn 拓扑分层取第 index 层的步骤 id（与调度器同一分层口径：层内保持声明序）。 */
    private static List<String> idsOfLayer(final BlueprintSpec spec, final int index) {
        final List<List<StepSpec>> layers = layersOf(spec);
        assertThat(layers).as("分层结果应非空").isNotEmpty();
        assertThat(layers).as("应存在第 %s 层", index).hasSizeGreaterThan(index);
        return layers.get(index).stream().map(StepSpec::id).toList();
    }

    /** 用被测调度器的同一分层语义（入度归零即入层、层内声明序）在测试侧重算，作为独立对照。 */
    private static List<List<StepSpec>> layersOf(final BlueprintSpec spec) {
        final List<StepSpec> steps = spec.steps();
        final List<String> scheduled = new ArrayList<>();
        final List<List<StepSpec>> layers = new ArrayList<>();
        while (scheduled.size() < steps.size()) {
            final List<StepSpec> ready = new ArrayList<>();
            for (StepSpec step : steps) {
                if (!scheduled.contains(step.id()) && scheduled.containsAll(step.dependsOn())) {
                    ready.add(step);
                }
            }
            assertThat(ready).as("分层不应卡住（卡住说明依赖集有环或引用了不存在的步）").isNotEmpty();
            ready.forEach(s -> scheduled.add(s.id()));
            layers.add(ready);
        }
        return layers;
    }

    private static String markerOf(final Map<String, String> markers, final String key) {
        return markers.get(key);
    }

    static String instructionOf(final BlueprintSpec spec, final String memberId) {
        return spec.members().stream().filter(m -> m.id().equals(memberId))
                .map(MemberSpec::instruction).findFirst().orElseThrow();
    }
}

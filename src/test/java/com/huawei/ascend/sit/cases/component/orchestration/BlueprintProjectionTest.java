/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.openjiuwen.agents.orchestration.BlueprintResult;
import com.openjiuwen.agents.orchestration.StepOutcome;
import com.openjiuwen.agents.reactrails.loopforest.observability.TraceForest;
import com.huawei.ascend.sit.cases.component.orchestration.OrchestrationTestSupport.Recording;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 065-S04（上游产物与原始输入两个输入位都送达）与 065-S05（投影四段结构与缺席段不出现）。
 *
 * <p>Oracle 来源：FEAT-065 §3「成员绑定契约」（执行体收 {@code (userInput, subGoal)} 两个文本位）
 * + §5.1.3 产物投影四段 + REQ-033；L2-065 §3.4「产物投影四段」。
 * §5.1.3 并明写「产物缺席的统一判据：null、空串或仅含空白字符(isBlank)即缺席」。
 *
 * <p><b>断言口径（重要）</b>：FEAT-065 §5.1.3 明写「拼入形态由成员绑定实现决定」，
 * 设计细档 §6 亦明列**不应断言** {@code [upstream outputs]} 之类的段标题字面文本与分隔符。
 * 因此本类只断言三件可判定事实：① 两个输入位都送达且都非空；② 两个上游产物**都在**且
 * **彼此分离**（中间存在非空白内容，即没有被拼成一个串）；③ 上游缺席时**不追加任何投影内容**
 * （步骤目标位恰好等于成员指令原文）。
 *
 * <p>§11.2 更正项：{@code "   "}（仅空白）形态**已解禁构造**，期望被判为缺席（GAP-065-01 已关闭）。
 */
@Tag("component")
@Tag("feat-065")
@Feature("FEAT-065: 确定性编排层(蓝图调度)")
class BlueprintProjectionTest {

    private static final String RUN = OrchestrationTestSupport.uid();

    private static final String PIPELINE_TWO_STEPS = """
            blueprint:
              id: T-065-S04-pipeline
              type: pipeline
              members:
                - {id: m1, instruction: "汇总技术要点"}
                - {id: m2, instruction: "汇总合规要点"}
              steps:
                - {id: s1, member: m1}
                - {id: s2, member: m2}
            """;

    private static final String DAG_TWO_UPSTREAM_SINK = """
            blueprint:
              id: T-065-S05-dag
              type: dag
              members:
                - {id: m1, instruction: "技术视角作答"}
                - {id: m2, instruction: "合规视角作答"}
                - {id: m3, instruction: "合并两路要点"}
              steps:
                - {id: s1, member: m1}
                - {id: s2, member: m2}
                - {id: s3, member: m3, depends_on: [s1, s2]}
            """;

    @Test
    @Story("065-S04: 上游产物与原始输入两个输入位都送达")
    @DisplayName("065-S04 下游成员实收 userInput 与 subGoal 两位，且分别带原始输入与上游产物")
    void upstreamAndUserInputBothDelivered() {
        final var loaded = OrchestrationTestSupport.load(PIPELINE_TWO_STEPS, "065-S04", RUN);
        final Recording s2Member = loaded.recorders.get("m2");
        final String upstreamMarker = OrchestrationTestSupport.marker("065-S04-UP1", "m1", RUN);
        final String userMarker = "U-065-S04-" + RUN;
        loaded.recorders.get("m1").returns(upstreamMarker);
        // s2 把实收的两个入参原文镜像回来，使"送达到位"成为可在产物上判定的事实
        s2Member.returnsDynamic(() -> "MIRROR|user=" + s2Member.lastUserInput()
                + "|goal=" + s2Member.lastSubGoal());

        final BlueprintResult result =
                loaded.execute(userMarker, 4, new TraceForest());

        final List<StepOutcome> outcomes = result.stepOutcomes();
        assertThat(outcomes).hasSize(2);
        assertThat(s2Member.calls()).as("s2 的成员执行体应被调用一次").isEqualTo(1);

        // 主断言：两个输入位都非空
        assertThat(s2Member.userInput(0)).as("s2 实收的原始用户输入位不应为空").isNotBlank();
        assertThat(s2Member.subGoal(0)).as("s2 实收的步骤目标位不应为空").isNotBlank();

        // 主断言：s2 的镜像产物同时含上游产物标记与原始用户输入标记
        final String mirrored = outcomes.get(1).output();
        assertThat(mirrored).as("s2 实收输入的镜像应同时含上游产物标记与原始用户输入标记")
                .contains(upstreamMarker).contains(userMarker);

        // 位分工：原始用户输入位就是调用方传入的原文（不被投影污染）
        assertThat(s2Member.userInput(0)).as("原始用户输入位应原样送达").isEqualTo(userMarker);
        // 步骤目标位承载成员指令与上游产物
        assertThat(s2Member.subGoal(0)).as("步骤目标位应含成员指令原文")
                .contains("汇总合规要点").contains(upstreamMarker);
    }

    @Test
    @Story("065-S05: 投影四段结构与缺席段不出现")
    @DisplayName("065-S05① 两上游汇点：两个上游产物都送达且彼此分离（不断言分隔符字面）")
    void twoUpstreamOutputsBothProjectedAndSeparated() {
        final var loaded = OrchestrationTestSupport.load(DAG_TWO_UPSTREAM_SINK, "065-S05", RUN);
        final String up1 = OrchestrationTestSupport.marker("065-S05-UP1", "m1", RUN);
        final String up2 = OrchestrationTestSupport.marker("065-S05-UP2", "m2", RUN);
        loaded.recorders.get("m1").returns(up1);
        loaded.recorders.get("m2").returns(up2);

        final BlueprintResult result = loaded.execute("U-065-S05-" + RUN, 4, new TraceForest());

        final Recording sink = loaded.recorders.get("m3");
        assertThat(sink.calls()).as("汇点步应被执行一次").isEqualTo(1);
        final String goal = sink.subGoal(0);
        final int i1 = goal.indexOf(up1);
        final int i2 = goal.indexOf(up2);
        assertThat(i1).as("汇点步输入应含第一个上游产物 %s；实收原文=%s", up1, goal).isNotNegative();
        assertThat(i2).as("汇点步输入应含第二个上游产物 %s；实收原文=%s", up2, goal).isNotNegative();
        final int first = Math.min(i1, i2);
        final int second = Math.max(i1, i2);
        final String firstMarker = first == i1 ? up1 : up2;
        assertThat(goal.substring(first + firstMarker.length(), second))
                .as("两个上游产物之间应有非空白的分隔内容（证明是两段而非被拼成一个串）；实收原文=%s", goal)
                .isNotBlank();
        assertThat(result.stepOutcomes()).hasSize(3);
    }

    @Test
    @Story("065-S05: 投影四段结构与缺席段不出现")
    @DisplayName("065-S05② 有待读留言：留言内容随步骤目标位送达")
    void pendingMessageContentIsProjected() {
        final var loaded = OrchestrationTestSupport.load(PIPELINE_TWO_STEPS, "065-S05-msg", RUN);
        final String message = "MSG-065-S05-" + RUN;
        final Function<String, List<String>> drain =
                memberId -> "m2".equals(memberId) ? List.of(message) : List.of();

        final BlueprintResult result = OrchestrationTestSupport.run(loaded.spec,
                OrchestrationTestSupport.bindings(loaded.recorders),
                "U-065-S05-msg-" + RUN, 4, new TraceForest(), drain);

        final Recording s2Member = loaded.recorders.get("m2");
        assertThat(s2Member.calls()).isEqualTo(1);
        assertThat(s2Member.subGoal(0)).as("有待读留言时，留言内容应随步骤目标位送达；实收原文=%s",
                s2Member.subGoal(0)).contains(message);
        // 无留言的成员不应收到任何留言内容
        assertThat(loaded.recorders.get("m1").subGoal(0))
                .as("无留言的成员不应被拼入留言内容").doesNotContain(message);
        assertThat(result.stepOutcomes()).hasSize(2);
    }

    static Stream<Arguments> absentForms() {
        return Stream.of(
                Arguments.of("null（执行体返回 null）", (String) null),
                Arguments.of("空串 \"\"", ""),
                // §11.2 更正：仅空白字符形态**已解禁**，按 Feature §5.1.3 的 isBlank 统一判据应算缺席
                Arguments.of("仅空白 \"   \"（§11.2 解禁，应判缺席）", "   "));
    }

    @ParameterizedTest(name = "上游产物形态：{0}")
    @MethodSource("absentForms")
    @Story("065-S05: 投影四段结构与缺席段不出现")
    @DisplayName("065-S05③ 上游产物缺席（null / 空串 / 仅空白）时不产生投影段")
    void absentUpstreamProducesNoProjectionSegment(final String label, final String absentOutput) {
        final String dag = """
                blueprint:
                  id: T-065-S05-absent
                  type: dag
                  members:
                    - {id: m1, instruction: "上游作答"}
                    - {id: m2, instruction: "下游作答"}
                  steps:
                    - {id: s1, member: m1}
                    - {id: s2, member: m2, depends_on: [s1]}
                """;
        final var loaded = OrchestrationTestSupport.load(dag, "065-S05-absent", RUN);
        loaded.recorders.get("m1").returns(absentOutput);

        final BlueprintResult result = loaded.execute("U-065-S05-absent-" + RUN, 4, new TraceForest());

        final StepOutcome s1 = result.stepOutcomes().get(0);
        assertThat(s1.outputText()).as("%s：上游产物应被判为缺席", label).isEmpty();
        final Recording downstream = loaded.recorders.get("m2");
        assertThat(downstream.calls()).as("%s：下游未声明跳过策略，应照常执行", label).isEqualTo(1);
        // 缺席上游不产生投影段 ⇒ 步骤目标位恰好等于成员指令原文，无任何追加内容
        assertThat(downstream.subGoal(0))
                .as("%s：缺席上游不应产生投影段，步骤目标位应恰好等于成员指令原文；实收原文=[%s]",
                        label, downstream.subGoal(0))
                .isEqualTo("下游作答");
        final Map<String, StepOutcome> outcomes = result.outcomes();
        assertThat(outcomes).as("两步都应有留痕").containsKeys("s1", "s2");
    }
}

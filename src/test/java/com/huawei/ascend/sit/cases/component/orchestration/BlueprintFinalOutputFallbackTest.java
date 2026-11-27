/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.openjiuwen.agents.orchestration.BlueprintResult;
import com.openjiuwen.agents.orchestration.StepOutcome;
import com.openjiuwen.agents.reactrails.loopforest.observability.TraceForest;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 065-S18：收口回退链（**当前层的非空白产物胜出**，空白/跳过不覆盖既有收口产物）。
 *
 * <p>【2026-10-28 出处更新】FEAT-065:45 现文（docs `!383`／`main@bd6ac83f`）已把收口写成当前层三分支：
 * 全部空白 ⇒ 保留此前已收集结果；恰好一份非空白 ⇒ 直接以该产物原文替换、**不加标题**；多份非空白 ⇒
 * 按声明序拼成 `## <步骤id>` 分节。本类的 pipeline 每层单步 ⇒ 落在"恰好一份非空白"这一支；
 * 旧措辞「末个非空白步产物胜出」在**单步层**上与现文等价（多步层才不等价，那一支由 066-S16 的 B 档覆盖）。
 * 多步层的分节聚合由**本类** {@link #skippedStepDoesNotParticipateInFallback()}（dag 首层两份非空白 ⇒
 * `## sOne`/`## sThree` 分节、空白与跳过步不得出现分节头）、`BlueprintFourShapesExecutionOrderTest`
 * （四形态逐层核对：多步层要求"含末步产物原文 + 含末步分节头"）与 066-S16 端到端 B 档共同覆盖。
 *
 * <p>Oracle 来源：FEAT-065 §2「结果收口」+ §5.1.3；已核实实现为 {@code isBlank()} 判定 +
 * 从前往后扫描保留既有值（{@code BlueprintScheduler.collectOneResult}）。
 * §11.2 更正：{@code "   "}（仅空白）形态**已解禁**，按 isBlank 统一判据算缺席（GAP-065-01 关闭）。
 *
 * <p><b>与设计 §6 构造的一处偏差（已如实登记，见批次记录 §7C）</b>：§6 写的是
 * "pipeline 四步——s1 返回 NONEMPTY-1、s2 声明 DEPS_INCONCLUSIVE 且被跳过（构造其上游缺席）…"。
 * 但流水线经装载器物化为 {@code s2 depends_on [s1]}，而 s1 返回 NONEMPTY-1（非空白）⇒
 * **s2 不可能被跳过**，该构造在 pipeline 下不可实现。为同时保住 §6 的三条 Then
 * （finalOutput == NONEMPTY-3、不是 ""、outcomes 含全部步含跳过步），本类用两个方法分别覆盖：
 * <ul>
 *   <li>{@link #blankStepDoesNotOverwriteExistingFinalOutput()}——**照 §6 原文的 pipeline 四步**，
 *       s2 声明 DEPS_INCONCLUSIVE 但按 §5.1.4 的 anyDep+全缺席判据**不跳过**（如实断言），
 *       并以 {@code "   "} 表达"空白不覆盖"；</li>
 *   <li>{@link #skippedStepDoesNotParticipateInFallback()}——用依赖图构造出**真正被跳过**的步，
 *       证明跳过步同样不参与收口、且留痕仍在。</li>
 * </ul>
 * 判据本身（当前层非空白产物胜出、空白与跳过步都不覆盖既有收口产物）**未被放宽**。
 *
 * <p>不应断言：无（§6 原本列的 {@code "   "} 不构造已由 §11.2 解禁）。
 */
@Tag("component")
@Tag("feat-065")
@Feature("FEAT-065: 确定性编排层(蓝图调度)")
class BlueprintFinalOutputFallbackTest {

    private static final String RUN = OrchestrationTestSupport.uid();

    @Test
    @Story("065-S18: 收口三层规则（2026-11-18 评审口径：拓扑层逐层收集）")
    @DisplayName("065-S18 流水线逐层收口（三层规则②+①+④）：非空白层原文替换、空白层保留此前结果（跨层空白传递）")
    void blankStepDoesNotOverwriteExistingFinalOutput() {
        final String yaml = """
                blueprint:
                  id: T-065-S18
                  type: pipeline
                  members:
                    - {id: m1, instruction: "第一步"}
                    - {id: m2, instruction: "第二步"}
                    - {id: m3, instruction: "第三步"}
                    - {id: m4, instruction: "第四步"}
                  steps:
                    - {id: s1, member: m1}
                    - {id: s2, member: m2, skip_if: DEPS_INCONCLUSIVE}
                    - {id: s3, member: m3}
                    - {id: s4, member: m4}
                """;
        final var loaded = OrchestrationTestSupport.load(yaml, "065-S18", RUN);
        loaded.recorders.get("m1").returns("NONEMPTY-1-" + RUN);
        loaded.recorders.get("m2").returns("   ");     // §11.2 解禁：仅空白即缺席
        loaded.recorders.get("m3").returns("NONEMPTY-3-" + RUN);
        loaded.recorders.get("m4").returns("");

        final BlueprintResult result = loaded.execute("U-065-S18-" + RUN, 4, new TraceForest());

        final List<StepOutcome> outcomes = result.stepOutcomes();
        assertThat(outcomes).as("四步都应有留痕").hasSize(4);
        assertThat(outcomes).extracting(StepOutcome::stepId).containsExactly("s1", "s2", "s3", "s4");
        // s1 产物非空白 ⇒ s2 的唯一上游未缺席 ⇒ 按 §5.1.4 判据 s2 **不**跳过（如实断言）
        assertThat(outcomes.get(1).skipped())
                .as("s2 的上游 s1 产物非空白，按 DEPS_INCONCLUSIVE 的「全部已就绪上游缺席才跳过」判据不应跳过")
                .isFalse();
        assertThat(outcomes.get(1).outputText()).as("s2 返回仅空白，应判缺席").isEmpty();
        assertThat(outcomes.get(3).outputText()).as("s4 返回空串，应判缺席").isEmpty();

        // 三层规则②：s3 层恰好一份非空白 ⇒ 直接以该产物**原文**替换（不加标题——分节头只出现在多份形态）。
        // 该断言同时蕴含：s2/s4 层全空白 ⇒ 按三层规则①保留此前结果（s1→保留→s3→保留，跨层空白传递④）。
        // 旧口径「末个非空白步产物胜出」在每层单步的 pipeline 上与三层规则在**结果上等价**
        // （旧措辞已废止，2026-11-18 评审 [A]：判据改写为拓扑层逐层收集，多产物场景旧断言会误判）。
        assertThat(result.finalOutput()).as("收口产物应为 s3 层的原文替换结果 NONEMPTY-3（s2/s4 空白层按三层规则①保留此前结果；"
                        + "实收=%s", result.finalOutput())
                .isEqualTo("NONEMPTY-3-" + RUN);
        assertThat(result.finalOutputText()).as("收口产物不应为缺席").isPresent();
    }

    @Test
    @Story("065-S18: 收口三层规则（2026-11-18 评审口径：拓扑层逐层收集）")
    @DisplayName("065-S18 依赖图变体（三层规则③）：多份非空白按声明序拼 ## 分节替换；跳过步与空白步不得出现分节头")
    void skippedStepDoesNotParticipateInFallback() {
        final String yaml = """
                blueprint:
                  id: T-065-S18-dag
                  type: dag
                  members:
                    - {id: mBlank, instruction: "缺席上游"}
                    - {id: mOne, instruction: "第一份非空白"}
                    - {id: mSkip, instruction: "可跳过步"}
                    - {id: mThree, instruction: "末份非空白"}
                    - {id: mFour, instruction: "空产物步"}
                  steps:
                    - {id: sBlank, member: mBlank}
                    - {id: sOne, member: mOne}
                    - {id: sSkip, member: mSkip, depends_on: [sBlank], skip_if: DEPS_INCONCLUSIVE}
                    - {id: sThree, member: mThree}
                    - {id: sFour, member: mFour}
                """;
        final var loaded = OrchestrationTestSupport.load(yaml, "065-S18-dag", RUN);
        loaded.recorders.get("mBlank").returns("");
        loaded.recorders.get("mOne").returns("NONEMPTY-1-" + RUN);
        loaded.recorders.get("mThree").returns("NONEMPTY-3-" + RUN);
        loaded.recorders.get("mFour").returns("");

        final BlueprintResult result = loaded.execute("U-065-S18-dag-" + RUN, 4, new TraceForest());

        final List<StepOutcome> outcomes = result.stepOutcomes();
        assertThat(outcomes).as("全部步（含跳过步）都应在 outcomes 里").hasSize(5);
        final StepOutcome skipped = outcomes.stream().filter(StepOutcome::skipped).findFirst().orElseThrow();
        assertThat(skipped.stepId()).as("被跳过的应是 sSkip").isEqualTo("sSkip");
        assertThat(loaded.recorders.get("mSkip").calls()).as("跳过步执行体不应被调用").isZero();
        // 【2026-10-26 r6 判据改按新语义】final_output 的取值规则（BlueprintScheduler.collectResults:205-222）：
        // 层内**只有一步**有产出 ⇒ 严格等于该步产物；**多步**有产出 ⇒ 按 "## <步骤id>" 分节、按声明序拼接
        // （代码注释明写「council / parallel 不能只取最后一个成员的输出」⇒ **旧行为"只取末步"才是缺陷**）；
        // 整层都为空 ⇒ 沿用上一层产物。
        // 本图纸首层有**两**份非空白产物（sOne / sThree）⇒ r6 上是分节聚合而非单份，
        // 原判据 isEqualTo("NONEMPTY-3-…") 属**过度指定**（钉在旧的"只取末步"实现行为上，而当时设计文档
        // Feature-065/066 与 L2 均**未定义**多步层的收口取值规则）。
        // ✅【2026-10-28 出处更新】"文档未定义"已成历史：docs `!383`（`main@bd6ac83f`）把三分支写进了
        //    `FEAT-065:45`（MUST 行）与 `L2-065:87`/`:132`/`:221`，`FEAT-066:39`/`L2-066:93` 改为引用之
        //    （我方 `docs#41` 上报、已验证关单）⇒ 本处三条断言现在**同时有文档出处与实现落点**，判据一字未改。
        // **本用例的设计意图一字未改**：「跳过步与空白步都不得覆盖既有收口产物」⇒ 拆成三条更可核的断言：
        // ① 两份非空白产物都必须在收口产物里；② 空白步/跳过步不得出现分节；③ 聚合形态必须可辨识。
        assertThat(result.finalOutput())
                .as("两份非空白产物都应进入收口产物（不得只取末步——r6 起层内多步按 ## 分节聚合）；实收=%s",
                        result.finalOutput())
                .contains("NONEMPTY-1-" + RUN).contains("NONEMPTY-3-" + RUN);
        assertThat(result.finalOutput())
                .as("跳过步（sSkip）与空白步（sBlank / sFour）都不得覆盖或污染既有收口产物"
                        + "（不得出现它们的分节头）；实收=%s", result.finalOutput())
                .doesNotContain("## sBlank").doesNotContain("## sSkip").doesNotContain("## sFour");
        assertThat(result.finalOutput())
                .as("聚合形态应带 \"## <步骤id>\" 分节头（否则无法与单步形态区分）；实收=%s", result.finalOutput())
                .contains("## sOne").contains("## sThree");
    }
}

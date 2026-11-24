/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.deepanalyze_template;

import com.openjiuwen.agents.orchestration.BlueprintType;
import com.openjiuwen.agents.orchestration.DynamicTeamTemplates;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 068-S04/S05/S06：动态两形态生成契约（代码层纯函数直调——生成逻辑不涉模型调用，<b>不属 Mock LLM</b>，
 * 测试方向已经用户确认）。
 *
 * <p>Oracle 来源：FEAT-068 Feature §2 行3 / §4 场景表 / §5.1.4；L2-068 §2.3（crossKbComparison /
 * zeroHallucination）、§5（成员 id=searcher-i、步骤 id=search-i、comparator/cross-validator/citation-reporter）。
 * 源码挂点（基线 r6=babb882b9，2026-11-24 实读）：crossKbComparison :31-51、zeroHallucination :59-70、
 * 空列表 :33-35、null :32；类 javadoc 明写「1 个也照常生成单路形态」（:28）。
 *
 * <p><b>068-S05 单库退化=MUST 硬断言（!510 评审升格，2026-11-18）</b>：Oracle=Feature §4 场景表
 * 「单库退化」行（L1 :63 逐字："两种形态均照常生成单路形态,不报错:跨库对比=一路检索+对比汇总;
 * 零幻觉变体=一路检索+交叉验证+引用报告"，!344 后现行文）。两形态各一 gating 方法：
 * 跨库对比单库=一路检索+对比汇总（searcher-1/comparator，search-1→compare）；
 * 零幻觉变体单库=一路检索+交叉验证+引用报告（searcher-1/cross-validator/citation-reporter，
 * search-1→validate→report）。生成器为纯函数——"照常生成、不报错"由"调用无异常+结构断言全过"承载。
 * 原「保守观察项」口径随 !344 的 A1 销项（Feature 单库退化行扩写覆盖两形态）废止。
 *
 * <p>主断言与失效拦截：若生成器把 kbId 拼进标识或依赖闭合被破坏，S04 的 containsExactly/dependsOn
 * 断言即红（细档最小例子 2）；单库退化若报错或生成多路/缺员形态，S05 两个 gating 方法即红。
 */
@Tag("component")
@Tag("feat-068")
@Feature("FEAT-068: 团队模板库")
class DynamicTeamTemplateTest {

    /** 刻意含非 ASCII 与连字符的知识库 id：验证"只进指令、不进标识"。 */
    private static final List<String> KB_IDS = List.of("kb-阿尔法-α", "kb-beta/01", "kb:γ:特殊");

    /** 单库退化（MUST）的单一知识库 id。 */
    private static final List<String> SINGLE_KB = List.of("kb-only-1");

    @Test
    @Story("068-S04: 动态跨库对比契约")
    @DisplayName("068-S04 每库一检索员并行 + 对比分析师；序号命名；kbId 只进指令不进标识")
    void crossKbComparisonGeneratesPerKbSearchersAndComparator() {
        final var spec = DynamicTeamTemplates.crossKbComparison(KB_IDS);

        assertThat(spec.type()).as("跨库对比产物应为依赖图形态（DAG）").isEqualTo(BlueprintType.DAG);
        assertThat(spec.id()).as("产物图纸 id 应为「跨库对比分析」").isEqualTo("跨库对比分析");

        final var memberIds = spec.members().stream().map(m -> m.id()).toList();
        assertThat(memberIds).as("成员应恰为 N 检索员 + 对比分析师，序号命名（L2 §5）")
                .containsExactly("searcher-1", "searcher-2", "searcher-3", "comparator");

        final var stepIds = spec.steps().stream().map(s -> s.id()).toList();
        assertThat(stepIds).as("步骤应恰为 search-i × N + compare")
                .containsExactly("search-1", "search-2", "search-3", "compare");

        final var compare = spec.steps().stream().filter(s -> s.id().equals("compare")).findFirst().orElseThrow();
        assertThat(compare.dependsOn()).as("对比分析师应依赖全部检索步")
                .containsExactlyInAnyOrder("search-1", "search-2", "search-3");

        // kbId 只进指令文本、不进任何标识
        for (int i = 0; i < KB_IDS.size(); i++) {
            final int n = i + 1;
            final String kbId = KB_IDS.get(i);
            final var searcher = spec.members().stream().filter(m -> m.id().equals("searcher-" + n))
                    .findFirst().orElseThrow();
            assertThat(searcher.instruction()).as("检索员 %d 指令应含真实 kbId：%s", n, kbId)
                    .contains(kbId);
        }
        assertThat(memberIds.stream().noneMatch(id -> KB_IDS.stream().anyMatch(id::contains)))
                .as("任何 kbId 都不得泄入成员标识").isTrue();
        assertThat(stepIds.stream().noneMatch(id -> KB_IDS.stream().anyMatch(id::contains)))
                .as("任何 kbId 都不得泄入步骤标识").isTrue();
    }

    @Test
    @Story("068-S05: 动态零幻觉契约与单库退化硬断言")
    @DisplayName("068-S05 零幻觉变体（多库）：validate 依赖全部检索步、report 只依赖 validate；无 comparator")
    void zeroHallucinationAddsValidateAndCitationReport() {
        final var spec = DynamicTeamTemplates.zeroHallucination(KB_IDS);

        assertThat(spec.type()).as("零幻觉变体产物应为依赖图形态（DAG）").isEqualTo(BlueprintType.DAG);
        assertThat(spec.id()).as("产物图纸 id 应为「零幻觉分析-动态」").isEqualTo("零幻觉分析-动态");

        final var memberIds = spec.members().stream().map(m -> m.id()).toList();
        assertThat(memberIds).as("成员应含检索员×N + cross-validator + citation-reporter，且无 comparator")
                .contains("searcher-1", "searcher-2", "searcher-3", "cross-validator", "citation-reporter")
                .doesNotContain("comparator");

        final var validate = spec.steps().stream().filter(s -> s.id().equals("validate")).findFirst().orElseThrow();
        assertThat(validate.dependsOn()).as("validate 应依赖全部检索步")
                .containsExactlyInAnyOrder("search-1", "search-2", "search-3");
        final var report = spec.steps().stream().filter(s -> s.id().equals("report")).findFirst().orElseThrow();
        assertThat(report.dependsOn()).as("report 应只依赖 validate").containsExactly("validate");
        assertThat(spec.steps().stream().map(s -> s.id()))
                .as("不应存在 compare 步（已从跨库对比骨架移除）").doesNotContain("compare");
    }

    @Test
    @Story("068-S05: 动态零幻觉契约与单库退化硬断言")
    @DisplayName("068-S05 单库退化（MUST 硬断言）跨库对比形态：一路检索+对比汇总，照常生成不报错")
    void crossKbComparisonSingleKbDegeneratesToSinglePathForm() {
        // Oracle：Feature §4 场景表「单库退化」行（!344 现行文，L1 :63）——跨库对比=一路检索+对比汇总。
        // 2026-11-18 评审升格为 MUST 硬断言（原保守观察项废止）；生成器纯函数，调用无异常+结构全过=照常生成。
        final var spec = DynamicTeamTemplates.crossKbComparison(SINGLE_KB);

        assertThat(spec.type()).as("单库跨库对比产物应为依赖图形态（DAG）").isEqualTo(BlueprintType.DAG);
        assertThat(spec.id()).as("单库退化产物 id 应与多库一致（跨库对比分析）").isEqualTo("跨库对比分析");

        final var memberIds = spec.members().stream().map(m -> m.id()).toList();
        assertThat(memberIds).as("单库退化成员应恰为一路检索员 + 对比分析师（MUST：一路检索+对比汇总）")
                .containsExactly("searcher-1", "comparator");

        final var stepIds = spec.steps().stream().map(s -> s.id()).toList();
        assertThat(stepIds).as("单库退化步骤应恰为一路检索 + 对比汇总")
                .containsExactly("search-1", "compare");

        final var compare = spec.steps().stream().filter(s -> s.id().equals("compare")).findFirst().orElseThrow();
        assertThat(compare.dependsOn()).as("对比汇总步应依赖唯一检索步")
                .containsExactly("search-1");

        final var searcher = spec.members().stream().filter(m -> m.id().equals("searcher-1")).findFirst().orElseThrow();
        assertThat(searcher.instruction()).as("检索员指令应含真实 kbId（kbId 只进指令）")
                .contains("kb-only-1");
        assertThat(memberIds.stream().noneMatch(id -> id.contains("kb-only-1")))
                .as("kbId 不得泄入成员标识").isTrue();
    }

    @Test
    @Story("068-S05: 动态零幻觉契约与单库退化硬断言")
    @DisplayName("068-S05 单库退化（MUST 硬断言）零幻觉形态：一路检索+交叉验证+引用报告，照常生成不报错")
    void zeroHallucinationSingleKbDegeneratesToSinglePathForm() {
        // Oracle：Feature §4 场景表「单库退化」行（!344 现行文，L1 :63）——零幻觉变体=一路检索+交叉验证+引用报告。
        // 2026-11-18 评审升格为 MUST 硬断言（原方法 zeroHallucinationSingleKbDegeneratesGracefully 为
        // 非 gating 观察项，随 A1 销项废止并升格；源码挂点 zeroHallucination :59-70 复用 crossKbComparison）。
        final var spec = DynamicTeamTemplates.zeroHallucination(SINGLE_KB);

        assertThat(spec.type()).as("单库零幻觉产物应为依赖图形态（DAG）").isEqualTo(BlueprintType.DAG);
        assertThat(spec.id()).as("单库退化产物 id 应与多库一致（零幻觉分析-动态）").isEqualTo("零幻觉分析-动态");

        final var memberIds = spec.members().stream().map(m -> m.id()).toList();
        assertThat(memberIds).as("单库退化成员应恰为一路检索员 + 交叉验证员 + 引用报告员（MUST：三员单路形态）")
                .containsExactly("searcher-1", "cross-validator", "citation-reporter")
                .doesNotContain("comparator");

        final var stepIds = spec.steps().stream().map(s -> s.id()).toList();
        assertThat(stepIds).as("单库退化步骤应恰为 一路检索 → 交叉验证 → 引用报告")
                .containsExactly("search-1", "validate", "report")
                .doesNotContain("compare");

        final var validate = spec.steps().stream().filter(s -> s.id().equals("validate")).findFirst().orElseThrow();
        assertThat(validate.dependsOn()).as("交叉验证步应依赖唯一检索步")
                .containsExactly("search-1");
        final var report = spec.steps().stream().filter(s -> s.id().equals("report")).findFirst().orElseThrow();
        assertThat(report.dependsOn()).as("引用报告步应只依赖交叉验证步")
                .containsExactly("validate");

        final var searcher = spec.members().stream().filter(m -> m.id().equals("searcher-1")).findFirst().orElseThrow();
        assertThat(searcher.instruction()).as("检索员指令应含真实 kbId（kbId 只进指令）")
                .contains("kb-only-1");
    }

    @Test
    @Story("068-S06: 动态空列表拒绝")
    @DisplayName("068-S06 两形态空列表均抛 IllegalArgumentException（代码层契约，不过会话面）")
    void bothFormsRejectEmptyKbList() {
        assertThatThrownBy(() -> DynamicTeamTemplates.crossKbComparison(List.of()))
                .as("跨库对比空列表应以参数异常拒绝（L2 :33-35）")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DynamicTeamTemplates.zeroHallucination(List.of()))
                .as("零幻觉变体空列表同样拒绝（复用跨库对比入口）")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Story("068-S06: 动态空列表拒绝")
    @DisplayName("068-S06 补充（代码事实，L2 未单列 null 口径）：null 入参抛 NullPointerException")
    void nullKbListThrowsNpeAsImplemented() {
        // 基线态 :32 Objects.requireNonNull(kbIds, "kbIds")——按实现事实断言；
        // L2 未单列 null 行为，若后续实现改为 IllegalArgumentException，本断言随之更新并记录。
        assertThatThrownBy(() -> DynamicTeamTemplates.crossKbComparison(null))
                .isInstanceOf(NullPointerException.class);
    }
}

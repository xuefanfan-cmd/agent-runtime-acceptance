/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.deepanalyze_template;

import com.openjiuwen.agents.orchestration.BlueprintLoader;
import com.openjiuwen.agents.orchestration.BlueprintSpec;
import com.openjiuwen.agents.orchestration.BlueprintType;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 068-S02：原版四族→图纸形态映射口径判据；068-S03：council 模板保留原版角色与步骤结构。
 *
 * <p>Oracle 来源：FEAT-068 Feature §2 行2 / §5.1.3 映射表；L2-068 §2.2（council 装载期编译为 DAG，
 * 步骤含 {@code p_*}/{@code r_*}/{@code audit}；角色集 risk/efficiency/feasibility + review_* +
 * chief-analyst——与开发侧 TeamTemplateLibraryTest 判据同源，本类为 SIT 侧独立实现）。
 *
 * <p>不应断言：互审的方向配对与审计输入投影细节——交叉验证的<b>编译语义</b>归 FEAT-066 线；
 * 本类只断言模板资产经装载后的<b>结构事实</b>（068 的"模板即判据"职责）。
 */
@Tag("component")
@Tag("feat-068")
@Feature("FEAT-068: 团队模板库")
class TemplateMappingAndCouncilTest {

    /** Feature §5.1.3 / L2 §2.2 的映射口径（装载后类型：cross_validation 编译为 DAG）。 */
    private record Mapping(String template, BlueprintType expectedLoadedType) {
    }

    static Stream<Arguments> mappings() {
        return Stream.of(
                Arguments.of("管道族→流水线", new Mapping("research-pipeline", BlueprintType.PIPELINE)),
                Arguments.of("图(链式)→流水线", new Mapping("comprehensive-analysis", BlueprintType.PIPELINE)),
                Arguments.of("图→依赖图", new Mapping("comprehensive-deep-analysis", BlueprintType.DAG)),
                Arguments.of("图→依赖图", new Mapping("generic-parallel-retrieval", BlueprintType.DAG)),
                Arguments.of("图→依赖图", new Mapping("parallel-deep-retrieval", BlueprintType.DAG)),
                Arguments.of("图→依赖图", new Mapping("parallel-research", BlueprintType.DAG)),
                Arguments.of("委员会+互审→交叉验证(编译为依赖图)", new Mapping("multi-perspective-council", BlueprintType.DAG)),
                Arguments.of("图(含动态变体)", new Mapping("zero-hallucination", BlueprintType.DAG)));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("mappings")
    @Story("068-S02: 映射口径判据")
    @DisplayName("068-S02 八套装载后图纸类型与四族映射口径表逐一一致")
    void loadedTypeMatchesOriginalTeamModeMapping(final String label, final Mapping mapping) throws Exception {
        final String yaml = DeepanalyzeTemplateTestSupport.readClasspath(
                "workflow-templates/" + mapping.template() + ".yaml");
        final BlueprintSpec spec = BlueprintLoader.load(yaml);
        assertThat(spec.type()).as("%s：模板 %s 装载后类型应与映射口径一致", label, mapping.template())
                .isEqualTo(mapping.expectedLoadedType());
    }

    @Test
    @Story("068-S03: council 角色与步骤结构判据")
    @DisplayName("068-S03 council 装载产物：步骤恰含 p_*/r_*/audit，成员恰含三视角+三互审+首席分析师")
    void councilCarriesOriginalPerspectiveRolesAndSteps() throws Exception {
        final String yaml = DeepanalyzeTemplateTestSupport.readClasspath(
                "workflow-templates/multi-perspective-council.yaml");
        final BlueprintSpec spec = BlueprintLoader.load(yaml);

        final List<String> stepIds = spec.steps().stream().map(s -> s.id()).toList();
        assertThat(stepIds).as("council 步骤应含三视角步(p_*)、三互审步(r_*)与审计步(audit)")
                .contains("p_risk", "p_efficiency", "p_feasibility",
                        "r_risk", "r_efficiency", "r_feasibility", "audit");

        final List<String> memberIds = spec.members().stream().map(m -> m.id()).toList();
        assertThat(memberIds).as("council 成员应保留原版角色集：三视角 + 三互审 + 首席分析师")
                .contains("risk", "efficiency", "feasibility",
                        "review_risk", "review_efficiency", "review_feasibility", "chief-analyst");
    }
}

/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.deepanalyze_template;

import com.openjiuwen.agents.orchestration.BlueprintException;
import com.openjiuwen.agents.orchestration.BlueprintLoader;
import com.openjiuwen.agents.orchestration.BlueprintSpec;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 068-S01：八套随仓模板逐套装载与结构判据（含 REQ-015 坏图纸负例变体）。
 *
 * <p>Oracle 来源：FEAT-068 Feature §2 行1（"每套均可被图纸装载器装载为合法图纸，成员声明非空、
 * 图纸类型明确"）；L2-068 §2.1 结构表；L2 §7.3（权威清单已扩为 12 项，035a2afd）「模板 YAML 若被改坏（违规），装载期 fail-fast——
 * 与普通坏图纸同一错误面」（REQ-015）。资产读取自被测制品 classpath（随包分发的真实资产）。
 *
 * <p>主断言与失效拦截：若任何一套资产被改坏（成员空/类型错/依赖悬空），本类断言必须红——
 * 拦不住即"模板即判据"失效（细档最小例子 1）。
 */
@Tag("component")
@Tag("feat-068")
@Feature("FEAT-068: 团队模板库")
class TemplateShippedAssetTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "research-pipeline", "comprehensive-analysis", "comprehensive-deep-analysis",
            "generic-parallel-retrieval", "parallel-deep-retrieval", "parallel-research",
            "multi-perspective-council", "zero-hallucination"})
    @Story("068-S01: 八套逐套装载与结构判据")
    @DisplayName("068-S01 每套随仓资产解析为非空 YAML 且装载为合法图纸（成员/步骤闭合）")
    void shippedAssetLoadsAsLegalBlueprint(final String name) throws Exception {
        final String yaml = DeepanalyzeTemplateTestSupport.readClasspath("workflow-templates/" + name + ".yaml");
        assertThat(yaml).as("模板 %s 资产内容不应为空", name).isNotBlank();

        final BlueprintSpec spec = BlueprintLoader.load(yaml);
        assertThat(spec.id()).as("模板 %s 装载后应保留图纸 id", name).isNotBlank();
        assertThat(spec.type()).as("模板 %s 装载后类型应明确", name).isNotNull();
        assertThat(spec.members()).as("模板 %s 成员声明应非空（Feature §2 行1）", name).isNotEmpty();
        assertThat(spec.steps()).as("模板 %s 步骤声明应非空", name).isNotEmpty();

        // 结构闭合：每个步骤的 member 引用已声明成员；depends_on（若有）引用已声明步骤
        final var memberIds = spec.members().stream().map(m -> m.id()).collect(java.util.stream.Collectors.toSet());
        final var stepIds = spec.steps().stream().map(s -> s.id()).collect(java.util.stream.Collectors.toSet());
        for (var step : spec.steps()) {
            assertThat(memberIds).as("模板 %s 步骤 %s 的 member 引用应已声明", name, step.id())
                    .contains(step.member());
            assertThat(stepIds).as("模板 %s 步骤 %s 的依赖应已声明", name, step.id())
                    .containsAll(step.dependsOn());
        }
    }

    @Test
    @Story("068-S01: 八套逐套装载与结构判据")
    @DisplayName("068-S01 负例（REQ-015）：资产被改坏（删成员致引用悬空）→ 装载期 BlueprintException fail-fast")
    void brokenAssetFailsFastAtLoadTime() throws Exception {
        final String yaml = DeepanalyzeTemplateTestSupport.readClasspath("workflow-templates/research-pipeline.yaml");
        // 改坏方式：删除 searcher 成员声明，保留引用它的 search 步骤 ⇒ 违规⑦「引用未声明成员」
        final String broken = yaml.replace(
                "- {id: searcher, instruction: 检索并给出证据清单（每条注明来源）}", "");
        assertThat(broken).as("改坏构造应生效（成员行确已被删）").isNotEqualTo(yaml);

        final Throwable ex = catchThrowableOfType(() -> BlueprintLoader.load(broken), BlueprintException.class);
        assertThat(ex).as("改坏资产必须在装载期 fail-fast（与普通坏图纸同一错误面，L2 §7.3）").isNotNull();
    }

    @Test
    @Story("068-S01: 八套逐套装载与结构判据")
    @DisplayName("068-S01 负例（REQ-015）：资产类型被改成不可识别值 → 装载期 BlueprintException")
    void unknownTypeAssetFailsFastAtLoadTime() throws Exception {
        final String yaml = DeepanalyzeTemplateTestSupport.readClasspath("workflow-templates/zero-hallucination.yaml");
        final String broken = yaml.replace("type: dag", "type: not-a-known-type");
        assertThat(broken).as("改坏构造应生效（类型值确已被改）").isNotEqualTo(yaml);

        final Throwable ex = catchThrowableOfType(() -> BlueprintLoader.load(broken), BlueprintException.class);
        assertThat(ex).as("不可识别类型必须在装载期拒绝（违规③）").isNotNull();
    }
}

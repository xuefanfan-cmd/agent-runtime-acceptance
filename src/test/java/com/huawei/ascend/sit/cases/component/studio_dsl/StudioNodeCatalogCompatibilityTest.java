/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.studio_dsl;

import com.huawei.ascend.sit.fixtures.studio_dsl.StudioDslContractTestBase;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioExportCorpusFixture;
import com.openjiuwen.studio.dsl.ir.IrComponentFactory;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("component")
@Tag("contract")
@Tag("feat-031")
@Feature("FEAT-031: Studio DSL Java 承载")
class StudioNodeCatalogCompatibilityTest extends StudioDslContractTestBase {
    private static final String STUDIO_COMMIT = "11e86f7d67e493062bddfa3b4f20d5946d987c69";

    @Test
    @DisplayName("Studio 当前节点的导出或展开路径均被 Java 接受")
    @Story("RT-031-04-25: Studio 导出节点目录兼容")
    void currentStudioExportTypesRemainAccepted() {
        StudioExportCorpusFixture.Manifest manifest = StudioExportCorpusFixture.manifest();

        assertThat(manifest.studio().commit()).isEqualTo(STUDIO_COMMIT);
        assertThat(manifest.catalogSources()).hasSize(4).allSatisfy(source -> {
            assertThat(source.path()).isNotBlank();
            assertThat(source.sha256()).matches("[0-9A-F]{64}");
        });
        // 唯一性门禁的判定维度按 (节点, 语料) 组合（2026-09-21，RT-031-04-25 决策 A′）。
        // 真实 Studio 导出里同一节点类型可以有多份语料（如 KnowledgeRepo 的迁移前历史行与
        // feat031-l2s7-knowledge-repo 真实导出行）；按节点名单独去重会把它误判为重复登记。
        List<String> corpusIdentities = manifest.catalog().stream()
                .map(entry -> entry.node() + " | " + entry.artifact())
                .toList();
        assertThat(corpusIdentities)
                .as("同一节点类型的同一份语料只能登记一次（同一节点类型允许登记多份语料）")
                .doesNotHaveDuplicates();
        // 计数锚定冻结 Studio 目录的节点类型数，不锚定语料条数：给已有节点补第二份语料不应再触发假红。
        List<String> defaultNodeTypes = manifest.catalog().stream()
                .filter(entry -> "default".equals(entry.availability()))
                .map(StudioExportCorpusFixture.CatalogEntry::node)
                .distinct()
                .toList();
        assertThat(defaultNodeTypes)
                .as("冻结 Studio 目录的 default 节点类型数（按类型去重，不按语料条数）")
                .hasSize(22);
        List<String> incompatibleDefaultNodes = manifest.catalog().stream()
                .filter(entry -> "default".equals(entry.availability()))
                .filter(entry -> !hasSupportedExportPath(entry))
                .map(entry -> entry.node() + " -> " + entry.exportType() + " [" + entry.catalogGate() + "]")
                .toList();

        assertThat(incompatibleDefaultNodes)
                .as("default Studio nodes without a Java-compatible export or expansion path")
                .isEmpty();
    }

    /**
     * Card is a whitelist-conditional row without an exported artifact at the frozen Studio
     * commit. It is kept as its own testcase so that its open state stays visible without
     * aborting the default-node compatibility testcase above.
     */
    /**
     * Card 缺口存在性断言（2026-09-19：由 assumption 门控改为真执行断言）。
     *
     * <p>实测（2026-09-19）：在部署件上新建最小语料 {@code Start → Card → End}
     * （workflow {@code 9f5c60e5-892b-4ee4-ad8f-0db1b5206ec5}，脚本
     * {@code 01-Design_File/20260919/create-l2s7-card-corpus.ps1}）后 validate 返回
     * {@code success=false}、{@code id=node_card type=Card reason=无效的节点类型}：后端
     * {@code NodeType} 枚举没有 Card，部署 jar 内 {@code jiuwen/card} 与 Card 适配器命中均为 0，
     * 因此该构建无法产出 Card 真实导出 IR。消费端 Java ext 已支持
     * {@code jiuwen.card|jiuwen.flowcard → FlowCardNode}，缺口在**生产端导出链**。
     *
     * <p>本用例断言"缺口仍然存在"，属于**缺口状态断言，不计能力通过**；一旦断言失败（说明导出链已闭合），
     * 必须按升级触发把该行升级为真实导出的装配用例，登记见《FEAT-031-降级与待恢复登记-20260918.md》。
     */
    @Test
    @DisplayName("Card 导出链在冻结 Studio commit 上仍未闭合（缺口存在性断言，不计能力通过）")
    @Story("RT-031-04-25: Studio 导出节点目录兼容")
    void cardExportChainRemainsOpenAtTheFrozenStudioCommit() {
        StudioExportCorpusFixture.Manifest manifest = StudioExportCorpusFixture.manifest();
        StudioExportCorpusFixture.CatalogEntry card = manifest.catalog().stream()
                .filter(entry -> "card-whitelist".equals(entry.availability()))
                .findFirst()
                .orElseThrow();
        assertThat(card.artifact())
                .as("Card 真实导出语料（预期：无；一旦有语料说明导出链已闭合，请升级本用例）")
                .isNull();
        assertThat(hasSupportedExportPath(card))
                .as("Card 是否存在受支持的导出路径（预期：否，见 2026-09-19 实测 workflow 9f5c60e5…）")
                .isFalse();
    }

    private static boolean hasSupportedExportPath(StudioExportCorpusFixture.CatalogEntry entry) {
        if ("expanded".equals(entry.exportMode())) {
            return entry.expandedTypes() != null
                    && !entry.expandedTypes().isEmpty()
                    && entry.expandedTypes().stream().allMatch(IrComponentFactory::isSupportedType);
        }
        return List.of("compatible", "expanded").contains(entry.catalogGate())
                && IrComponentFactory.isSupportedType(entry.exportType());
    }
}

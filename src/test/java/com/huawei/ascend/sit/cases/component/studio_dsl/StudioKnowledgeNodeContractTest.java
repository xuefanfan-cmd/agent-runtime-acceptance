/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.studio_dsl;

import com.huawei.ascend.sit.fixtures.studio_dsl.StudioDslContractTestBase;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioExportCorpusFixture;
import com.huawei.ascend.sit.fixtures.studio_dsl.WorkflowAssemblyProbe;
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2 §7.1 知识库 / 记忆后端（§4 装配侧字段契约）——输入契约负向/边界族（C2–C6）。
 *
 * <p>语料全部来自 Studio 真实导出（探针脚本 {@code 01-Design_File/20260919/probe-l2s7-knowledge-contract.ps1}，
 * 报告 {@code 01-Design_File/20260919/knowledge-contract-probe/report.json}），非测试侧自造 IR。
 * 本类锁定"当前真实导出契约"，并把设计未定义/可疑的行为登记为事实：
 *
 * <ul>
 *   <li>C2（引用不存在的知识库 id）：写路径直接拒绝，错误码 {@code openjiuwen.03004087 = 知识库不存在}
 *       （{@code WorkflowManagementService#checkKnowledge}），因此不存在该形态的导出 IR —— 见发现记录。</li>
 *   <li>C3（引用状态 {@code CLOSE} 的知识库）：validate 通过，导出 IR 原样保留该 id（设计是否允许待确认）。</li>
 *   <li>C4（同节点两个知识库）：两个 id 都进入 {@code knowledgeBaseIds}，排序按后端查询
 *       {@code ORDER BY update_time desc}，不是声明顺序。</li>
 *   <li>C5（{@code top_k}=0 / 100000）：原样透传，无上下限校验；{@code search_mode} 非法值被静默归一为 {@code doc}。</li>
 *   <li>C6（{@code common_tags}）：标签进入 {@code retrievalConfig.tags}，但值被转义为 {@code (feat031\-probe\-tag)}。</li>
 * </ul>
 */
@Tag("component")
@Tag("contract")
@Tag("feat-031")
@Tag("knowledge-retrieval")
@Feature("FEAT-031: Studio DSL Java 承载")
class StudioKnowledgeNodeContractTest extends StudioDslContractTestBase {

    private static final String KB_NODE_ID = "node_kb";

    @Test
    @Story("RT-031-04-32: 多知识库知识检索节点装配字段契约")
    @DisplayName("同一节点绑定两个知识库：两个 id 都进入 knowledgeBaseIds 且装配为知识检索节点")
    void preservesMultipleKnowledgeBaseIdsFromRealExport() {
        Map<String, Object> configs = knowledgeRetrievalConfigs("feat031-l2s7-kb-multi");
        assertThat(ids(configs))
                .as("知识库 id 集合（顺序按后端 update_time desc，不是声明顺序）")
                .containsExactlyInAnyOrder("feat031_l2s7_kb_0001", "feat031_l2s7_kb_0002");
        assertThat(retrievalConfig(configs).keySet())
                .as("检索参数契约字段")
                .contains("topK", "recallThreshold", "searchMode", "faqThreshold");
        assertThat(assembledJavaType("feat031-l2s7-kb-multi"))
                .as("多知识库节点的装配目标类型")
                .isEqualTo("com.openjiuwen.studio.dsl.nodes.FlowKnowledgeRetrievalNode");
    }

    @Test
    @Story("RT-031-04-33: 停用知识库引用的事实登记")
    @DisplayName("引用状态 CLOSE 的知识库：导出 IR 原样保留该 id（现状锁定，设计口径待确认）")
    void keepsClosedKnowledgeBaseIdAsDeclared() {
        Map<String, Object> configs = knowledgeRetrievalConfigs("feat031-l2s7-kb-closed");
        assertThat(ids(configs))
                .as("停用知识库 id 的现状：原样进入 IR（未按状态过滤）")
                .containsExactly("feat031_l2s7_kb_closed");
        assertThat(assembledJavaType("feat031-l2s7-kb-closed"))
                .as("装配目标类型（现状：装配侧不因状态拒绝）")
                .isEqualTo("com.openjiuwen.studio.dsl.nodes.FlowKnowledgeRetrievalNode");
    }

    @Test
    @Stories({
        @Story("RT-031-04-34: top_k 边界值透传事实"),
        @Story("RT-031-04-35: 非法 search_mode 归一化事实")
    })
    @DisplayName("检索参数边界：top_k 原样透传（0 / 100000），非法 search_mode 归一为 doc")
    void documentsRetrievalParameterBoundaries() {
        Map<String, Object> zero = retrievalConfig(knowledgeRetrievalConfigs("feat031-l2s7-kb-topk-zero"));
        assertThat(zero.get("topK"))
                .as("top_k=0 的现状：原样透传（无下限校验）")
                .isEqualTo(0);

        Map<String, Object> large = retrievalConfig(knowledgeRetrievalConfigs("feat031-l2s7-kb-topk-large"));
        assertThat(large.get("topK"))
                .as("top_k=100000 的现状：原样透传（无上限校验）")
                .isEqualTo(100000);

        Map<String, Object> invalidMode = retrievalConfig(
                knowledgeRetrievalConfigs("feat031-l2s7-kb-search-mode-invalid"));
        assertThat(invalidMode.get("searchMode"))
                .as("search_mode 非法值的现状：静默归一为 doc（未报错）")
                .isEqualTo("doc");
    }

    @Test
    @Story("RT-031-04-36: 知识检索标签写入契约")
    @DisplayName("common_tags 进入 retrievalConfig.tags，且当前导出形态带转义")
    void recordsKnowledgeBaseTagShape() {
        Map<String, Object> tags = retrievalConfig(knowledgeRetrievalConfigs("feat031-l2s7-kb-tags"));
        assertThat(tags.get("tags"))
                .as("标签必须进入检索配置（当前真实导出值形态：括号 + 反斜杠转义）")
                .isEqualTo(List.of("(feat031\\-probe\\-tag)"));
    }

    private static Map<String, Object> knowledgeRetrievalConfigs(String artifactId) {
        StudioExportCorpusFixture.Manifest manifest = StudioExportCorpusFixture.manifest();
        Map<String, Object> ir = StudioExportCorpusFixture.artifactIr(manifest, artifactId);
        for (Object component : (List<?>) ir.get("components")) {
            Map<?, ?> node = (Map<?, ?>) component;
            if (!KB_NODE_ID.equals(String.valueOf(node.get("id")))) {
                continue;
            }
            assertThat(node.get("type")).as(artifactId + " 节点类型").isEqualTo("jiuwen.knowledgeRetrieval");
            @SuppressWarnings("unchecked")
            Map<String, Object> configs = (Map<String, Object>) node.get("configs");
            return configs;
        }
        throw new AssertionError("component " + KB_NODE_ID + " is missing from artifact " + artifactId);
    }

    private static Set<String> ids(Map<String, Object> configs) {
        Set<String> ids = new LinkedHashSet<>();
        for (Object id : (List<?>) configs.get("knowledgeBaseIds")) {
            ids.add(String.valueOf(id));
        }
        return ids;
    }

    private static Map<String, Object> retrievalConfig(Map<String, Object> configs) {
        @SuppressWarnings("unchecked")
        Map<String, Object> retrievalConfig = (Map<String, Object>) configs.get("retrievalConfig");
        assertThat(retrievalConfig).as("retrievalConfig").isNotNull();
        return retrievalConfig;
    }

    private static String assembledJavaType(String artifactId) {
        StudioExportCorpusFixture.Manifest manifest = StudioExportCorpusFixture.manifest();
        WorkflowAssemblyProbe.Snapshot snapshot = WorkflowAssemblyProbe.inspect(
                StudioExportCorpusFixture.assembleArtifact(manifest, artifactId).workflow());
        return snapshot.javaTypes().get(KB_NODE_ID);
    }
}

/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.studio_dsl;

import com.huawei.ascend.sit.fixtures.studio_dsl.StudioDslContractTestBase;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioExportCorpusFixture;
import com.huawei.ascend.sit.fixtures.studio_dsl.WorkflowAssemblyProbe;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

@Tag("component")
@Tag("contract")
@Tag("feat-031")
@Feature("FEAT-031: Studio DSL Java 承载")
class StudioExportCorpusAssemblyTest extends StudioDslContractTestBase {
    private static final String CATALOG_ROWS_PROPERTY = "sit.studio.export.catalog-rows";
    /** FEAT-031 §3.5.1: one component may carry several IR aliases that assemble to the same node type. */
    private static final Map<String, Set<String>> SIBLING_ALIASES = Map.of(
            "jiuwen.aggregation", Set.of("jiuwen.aggregate", "jiuwen.flowAggregate"),
            "jiuwen.aggregate", Set.of("jiuwen.aggregation", "jiuwen.flowAggregate"),
            "jiuwen.LLMReAct", Set.of("jiuwen.agent", "jiuwen.flowAgent"),
            "jiuwen.agent", Set.of("jiuwen.LLMReAct", "jiuwen.flowAgent"));
    /**
     * Composite nodes in this corpus reference a child workflow whose IR is not part of the bundle and
     * the fixture wires no child IR loader, so FEAT-031 §4 "missing child -> WARN and skip the unit,
     * never fail the parent assembly" applies. The lazy path is covered separately by
     * StudioIrSdkContractTest, which supplies a loader that returns a child IR.
     */
    private static final String SKIPPED_SUB_WORKFLOW =
            "com.openjiuwen.studio.dsl.ir.SkippedSubWorkflowComponent";
    private static final Map<String, String> EXPECTED_NODE_TYPES = expectedNodeTypes();

    @TestFactory
    @DisplayName("当前用户节点的真实 Studio 导出 IR 逐节点装配且关键字段保真")
    @Story("RT-031-04-26: 当前用户节点真实导出 IR 装配")
    Stream<DynamicTest> assemblesEveryCurrentStudioNodeExport() {
        StudioExportCorpusFixture.Manifest manifest = StudioExportCorpusFixture.manifest();
        Set<String> selectedRows = selectedCatalogRows(manifest);
        return manifest.catalog().stream()
                .filter(entry -> entry.artifact() != null)
                .filter(entry -> selectedRows.isEmpty() || selectedRows.contains(entry.node()))
                .map(entry -> dynamicTest(dynamicName(entry), () -> assertCatalogRow(manifest, entry)));
    }

    private static Set<String> selectedCatalogRows(StudioExportCorpusFixture.Manifest manifest) {
        String configuredRows = System.getProperty(CATALOG_ROWS_PROPERTY, "");
        if (configuredRows.isBlank()) {
            return Set.of();
        }

        Set<String> selectedRows = new LinkedHashSet<>();
        for (String row : configuredRows.split(",")) {
            if (!row.isBlank()) {
                selectedRows.add(row.trim());
            }
        }
        Set<String> availableRows = new LinkedHashSet<>();
        manifest.catalog().stream()
                .filter(entry -> entry.artifact() != null)
                .map(StudioExportCorpusFixture.CatalogEntry::node)
                .forEach(availableRows::add);
        assertThat(availableRows)
                .as("unknown Studio export catalog rows from -D" + CATALOG_ROWS_PROPERTY)
                .containsAll(selectedRows);
        return Set.copyOf(selectedRows);
    }

    private static void assertCatalogRow(
            StudioExportCorpusFixture.Manifest manifest,
            StudioExportCorpusFixture.CatalogEntry entry) {
        assertThat(entry.componentIds()).as(entry.node()).isNotEmpty();
        Map<String, Map<String, Object>> sourceComponents = new LinkedHashMap<>();
        for (String componentId : entry.componentIds()) {
            Map<String, Object> component = StudioExportCorpusFixture.component(manifest, entry, componentId);
            sourceComponents.put(componentId, component);
            String componentType = String.valueOf(component.get("type"));
            if (entry.observedType() != null) {
                assertThat(componentType).as(componentId).isEqualTo(entry.observedType());
            } else if ("expanded".equals(entry.exportMode())) {
                assertThat(entry.expandedTypes()).as(componentId).contains(componentType);
            } else {
                assertThat(componentType).as(componentId).isEqualTo(entry.exportType());
            }

            assertThat(component.get("configs")).as(componentId).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> configs = (Map<String, Object>) component.get("configs");
            assertThat(configs.keySet()).as(componentId)
                    .containsAll(entry.criticalFieldsFor(componentId));
        }

        WorkflowAssemblyProbe.Snapshot snapshot = WorkflowAssemblyProbe.inspect(
                StudioExportCorpusFixture.assembleArtifact(manifest, entry.artifact()).workflow());
        if (entry.nestedParentId() == null) {
            assertThat(snapshot.nodes().keySet())
                    .as(entry.node() + " assembled component IDs")
                    .containsAll(entry.componentIds());
        } else {
            // Loop body children are executed by the loop component itself; the probe reads the
            // workflow-level graph, so a body child must never surface as a top-level node.
            assertThat(snapshot.nodes().keySet())
                    .as(entry.node() + " nested parent assembled")
                    .contains(entry.nestedParentId());
            assertThat(snapshot.nodes().keySet())
                    .as(entry.node() + " loop body child stays inside the body")
                    .doesNotContainAnyElementsOf(entry.componentIds());
        }
        sourceComponents.forEach((componentId, component) -> {
            if (entry.nestedParentId() != null) {
                return; // body child: asserted through its parent loop row, not as a top-level node
            }
            String sourceType = String.valueOf(component.get("type"));
            String expectedType = expectedJavaType(entry, sourceType);
            assertThat(snapshot.javaTypes().get(componentId))
                    .as(entry.node() + "/" + componentId + " Java assembly target")
                    .isEqualTo(expectedType);
            if (snapshot.studioTypes().containsKey(componentId)) {
                assertThat(snapshot.studioTypes().get(componentId))
                        .as(entry.node() + "/" + componentId + " observed Studio type")
                        .isIn(acceptedSelfTypes(sourceType));
            }
        });
    }

    private static String dynamicName(StudioExportCorpusFixture.CatalogEntry entry) {
        String componentOrGraph = "expanded".equals(entry.exportMode())
                ? "expanded-graph"
                : entry.componentIds().get(0);
        return "RT-031-04-26/" + entry.node() + "/" + entry.artifact() + "/" + componentOrGraph;
    }

    /**
     * Accepts the component's own canonical name as well as any sibling alias declared by
     * FEAT-031 §3.5.1. The design states that aliases are carried as a single node type with
     * identical assembly results, so the assembled node is not required to echo the exact IR
     * string used by the export.
     *
     * @param sourceType type string found in the exported IR
     * @return accepted self-reported types for that component
     */
    private static Set<String> acceptedSelfTypes(String sourceType) {
        Set<String> accepted = new LinkedHashSet<>();
        accepted.add(sourceType);
        accepted.addAll(SIBLING_ALIASES.getOrDefault(sourceType, Set.of()));
        return accepted;
    }

    private static String expectedJavaType(
            StudioExportCorpusFixture.CatalogEntry entry, String sourceType) {
        if ("ParamExtraction".equals(entry.node())) {
            return switch (sourceType.toLowerCase()) {
                case "ei.paramoutput" -> "com.openjiuwen.studio.dsl.nodes.FlowParamOutputNode";
                case "jiuwen.branch" -> "com.openjiuwen.studio.dsl.nodes.FlowBranchNode";
                case "jiuwen.workflowcomposite" -> SKIPPED_SUB_WORKFLOW;
                default -> throw new AssertionError("unexpected ParamExtraction expanded type: " + sourceType);
            };
        }
        if ("exported-as-plugin".equals(entry.corpusStatus())) {
            // The exported artifact still carries the pre-migration jiuwen.plugin node; the manifest
            // records that with observedType/corpusStatus, so assert what the artifact really is.
            return "com.openjiuwen.studio.dsl.nodes.FlowPluginNode";
        }
        String expected = EXPECTED_NODE_TYPES.get(entry.node());
        if (expected == null) {
            throw new AssertionError("missing expected Java type for Studio node: " + entry.node());
        }
        return expected;
    }

    private static Map<String, String> expectedNodeTypes() {
        Map<String, String> types = new LinkedHashMap<>();
        types.put("Start", "com.openjiuwen.studio.dsl.nodes.FlowStartNode");
        types.put("End", "com.openjiuwen.studio.dsl.nodes.FlowEndNode");
        types.put("LLM", "com.openjiuwen.studio.dsl.nodes.FlowLlmNode");
        types.put("Workflow", SKIPPED_SUB_WORKFLOW);
        types.put("Branch", "com.openjiuwen.studio.dsl.nodes.FlowBranchNode");
        types.put("IntentDetection", "com.openjiuwen.studio.dsl.nodes.FlowIntentDetectionNode");
        types.put("Code", "com.openjiuwen.studio.dsl.nodes.FlowCodeNode");
        types.put("ComplexIntentDetection", "com.openjiuwen.studio.dsl.nodes.FlowComplexIntentDetectionNode");
        types.put("Loop", "com.openjiuwen.studio.dsl.nodes.FlowLoopNode");
        types.put("Plugin", "com.openjiuwen.studio.dsl.nodes.FlowPluginNode");
        types.put("Mcp", "com.openjiuwen.studio.dsl.nodes.FlowMcpNode");
        types.put("Message", "com.openjiuwen.studio.dsl.nodes.FlowMessageNode");
        types.put("Questioner", "com.openjiuwen.studio.dsl.nodes.FlowQuestionerNode");
        types.put("Input", "com.openjiuwen.studio.dsl.nodes.FlowInputNode");
        types.put("QA", "com.openjiuwen.studio.dsl.nodes.FlowQaNode");
        types.put("StructuredMessagesException", "com.openjiuwen.studio.dsl.nodes.FlowExceptionNode");
        types.put("SetVariable", "com.openjiuwen.studio.dsl.nodes.FlowSetVariableNode");
        types.put("Aggregation", "com.openjiuwen.studio.dsl.nodes.FlowAggregateNode");
        types.put("KnowledgeRepo", "com.openjiuwen.studio.dsl.nodes.FlowKnowledgeRetrievalNode");
        types.put("Agent", "com.openjiuwen.studio.dsl.nodes.FlowAgentNode");
        types.put("StreamTransform", "com.openjiuwen.studio.dsl.nodes.FlowStreamTransformNode");
        return Map.copyOf(types);
    }
}

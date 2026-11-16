/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.studio_dsl.controller_ir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.fixtures.studio_dsl.ControllerIrTestFixture;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioDslContractTestBase;
import com.openjiuwen.studio.dsl.ir.IrModelMapping;
import com.openjiuwen.studio.dsl.ir.StudioIrSdk;
import com.openjiuwen.studio.dsl.ir.controller.ControllerIrAssembleContext;
import com.openjiuwen.studio.dsl.ir.controller.ControllerIrAssembleException;
import com.openjiuwen.studio.dsl.ir.controller.ControllerIrAssembleResult;
import com.openjiuwen.studio.dsl.ir.controller.ControllerIrSource;
import com.openjiuwen.studio.dsl.multiagent.config.AgentConfig;
import com.openjiuwen.studio.dsl.multiagent.config.AgentGroupConfig;
import com.openjiuwen.studio.dsl.multiagent.config.GroupSettings;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

@Tag("component")
@Tag("contract")
@Tag("feat-031")
@Feature("FEAT-031: Studio DSL Java 承载")
class ControllerIrMechanismChainContractTest extends StudioDslContractTestBase {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @TestFactory
    @Story("MA-031-05-20-B01..B12: 控制器 IR 行为机制链")
    Stream<DynamicTest> mechanismChains() {
        return Stream.iterate(1, value -> value + 1)
                .limit(12)
                .map(value -> "B%02d".formatted(value))
                .map(id -> dynamicTest("MA-031-05-20-" + id, () -> assertMechanism(id)));
    }

    private void assertMechanism(String id) throws Exception {
        switch (id) {
            case "B01" -> assertRealBundleClosure();
            case "B02" -> assertMapJsonEquivalence();
            case "B03" -> assertNestedControllerRecursion();
            case "B04" -> assertParentOverrideAndImmutability();
            case "B05" -> assertGlobalIntentWorkflow();
            case "B06" -> assertWorkflowValidationFailure();
            case "B07" -> assertModelMappingMerge();
            case "B08" -> assertPluginPreservation();
            case "B09" -> assertMcpPreservation();
            case "B10" -> assertWorkflowCombination();
            case "B11" -> assertDirectJavaEquivalence();
            case "B12" -> assertPathAndPublishedVariants();
            default -> throw new IllegalArgumentException("unknown mechanism " + id);
        }
    }

    private void assertRealBundleClosure() {
        ControllerIrAssembleResult result = StudioIrSdk.loadAgentGroupFromBundleResult(
                ControllerIrTestFixture.materializeBundle(tempDir),
                ControllerIrTestFixture.ROOT_AGENT_ID,
                ControllerIrAssembleContext.builder()
                        .modelMapping(ControllerIrTestFixture.resolvedModelMapping())
                        .build());
        assertThat(result.rootAgentId()).isEqualTo(ControllerIrTestFixture.ROOT_AGENT_ID);
        assertThat(result.agentCount()).isEqualTo(1);
        assertThat(result.referencePaths()).hasSize(1);
        assertThat(result.groupConfig().getAgents()).hasSize(1);
    }

    private void assertMapJsonEquivalence() throws Exception {
        Map<String, Object> root = ControllerIrTestFixture.realRootIr();
        String json = MAPPER.writeValueAsString(root);
        AgentGroupConfig fromMap = StudioIrSdk.loadAgentGroup(root, realContext());
        AgentGroupConfig fromJson = StudioIrSdk.loadAgentGroupJson(json, realContext());
        assertThat(fromJson.getGroupId()).isEqualTo(fromMap.getGroupId());
        assertThat(fromJson.getGroupName()).isEqualTo(fromMap.getGroupName());
        assertThat(fromJson.getMaxAgentCalls()).isEqualTo(fromMap.getMaxAgentCalls());
        assertThat(fromJson.getMainAgent().getControllerConfig().getWorkflows())
                .hasSameSizeAs(fromMap.getMainAgent().getControllerConfig().getWorkflows());
    }

    private void assertNestedControllerRecursion() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        Map<String, Object> child = ControllerIrTestFixture.minimalChild("child");
        Map<String, Object> grandchild = ControllerIrTestFixture.minimalChild("grandchild");
        ControllerIrTestFixture.configs(root).put("agents",
                List.of(ControllerIrTestFixture.childReference("child", "ir/child.json")));
        ControllerIrTestFixture.configs(child).put("agents",
                List.of(ControllerIrTestFixture.childReference("grandchild", "ir/grandchild.json")));
        ControllerIrAssembleResult result = StudioIrSdk.loadAgentGroupResult(root,
                ControllerIrTestFixture.mapContext(Map.of(
                        "ir/child.json", child,
                        "ir/grandchild.json", grandchild)));
        assertThat(result.groupConfig().getAgents())
                .extracting(AgentConfig::getId)
                .containsExactly("grandchild", "child", "root");
    }

    @SuppressWarnings("unchecked")
    private void assertParentOverrideAndImmutability() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        Map<String, Object> child = ControllerIrTestFixture.minimalChild("child");
        Map<String, Object> reference = ControllerIrTestFixture.childReference("child", "ir/child.json");
        reference.put("description", "parent-description");
        reference.put("intent", Map.of("name", "parent-intent", "description", "parent-intent-description"));
        ControllerIrTestFixture.configs(root).put("agents", List.of(reference));
        Map<String, Object> rootBefore = MAPPER.convertValue(root, Map.class);
        Map<String, Object> childBefore = MAPPER.convertValue(child, Map.class);

        ControllerIrAssembleResult result = StudioIrSdk.loadAgentGroupResult(root,
                ControllerIrTestFixture.mapContext(Map.of("ir/child.json", child)));
        AgentConfig childConfig = result.groupConfig().getAgents().get(0);
        assertThat(childConfig.getControllerConfig().getMetadata().getDescription())
                .isEqualTo("parent-description");
        assertThat(childConfig.getControllerConfig().getMetadata().getIntentName())
                .isEqualTo("parent-intent");
        assertThat(root).isEqualTo(rootBefore);
        assertThat(child).isEqualTo(childBefore);
    }

    private void assertGlobalIntentWorkflow() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        String workflowPath = realDefaultWorkflowPath();
        ControllerIrTestFixture.configs(root).put("global_intents", List.of(Map.of(
                "name", "global",
                "handler_type", "Workflow",
                "handler", Map.of("ir_path", workflowPath, "name", "global-workflow"),
                "action", "Continue")));
        AgentGroupConfig group = StudioIrSdk.loadAgentGroup(root, realContext());
        assertThat(group.getMainAgent().getControllerConfig().getGlobalWorkflows()).hasSize(1);
    }

    private void assertWorkflowValidationFailure() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        ControllerIrTestFixture.configs(root).put("workflows",
                List.of(Map.of("ir_path", "ir/invalid.json", "workflow_type", "Normal")));
        assertThatThrownBy(() -> StudioIrSdk.loadAgentGroupResult(root,
                ControllerIrTestFixture.mapContext(Map.of("ir/invalid.json", Map.of("workflowId", "bad")))))
                .isInstanceOfSatisfying(ControllerIrAssembleException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ControllerIrAssembleException.CONTROLLER_WORKFLOW_ASSEMBLE_FAILED));
    }

    private void assertModelMappingMerge() {
        Path bundle = ControllerIrTestFixture.materializeBundle(tempDir);
        assertThatThrownBy(() -> StudioIrSdk.loadAgentGroupFromBundleResult(
                bundle,
                ControllerIrTestFixture.ROOT_AGENT_ID,
                ControllerIrAssembleContext.builder().build()))
                .isInstanceOfSatisfying(ControllerIrAssembleException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ControllerIrAssembleException.CONTROLLER_WORKFLOW_ASSEMBLE_FAILED));
        assertThat(StudioIrSdk.loadAgentGroupFromBundleResult(
                bundle,
                ControllerIrTestFixture.ROOT_AGENT_ID,
                ControllerIrAssembleContext.builder()
                        .modelMapping(ControllerIrTestFixture.resolvedModelMapping())
                        .build())).isNotNull();
    }

    private void assertPluginPreservation() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        Map<String, Object> plugin = Map.of(
                "id", "plugin-1",
                "name", "plugin",
                "headers", Map.of("X-Test", "plugin-canary"),
                "arguments", List.of("a"));
        ControllerIrTestFixture.configs(root).put("plugins", List.of(plugin));
        List<Object> plugins = StudioIrSdk.loadAgentGroup(root, emptyContext())
                .getMainAgent().getAgentRuntimeConfig().plugins();
        assertThat(plugins).containsExactly(Map.of(
                "id", "plugin-1",
                "name", "plugin",
                "arguments", List.of("a")));
        assertThat(plugins.toString()).doesNotContain("plugin-canary");
    }

    private void assertMcpPreservation() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        Map<String, Object> mcp = Map.of(
                "id", "mcp-1",
                "name", "mcp",
                "headers", Map.of("X-Test", "mcp-canary"),
                "arguments", List.of("a"));
        ControllerIrTestFixture.configs(root).put("mcps", List.of(mcp));
        List<Object> mcps = StudioIrSdk.loadAgentGroup(root, emptyContext())
                .getMainAgent().getAgentRuntimeConfig().mcps();
        assertThat(mcps).containsExactly(Map.of(
                "id", "mcp-1",
                "name", "mcp",
                "arguments", List.of("a")));
        assertThat(mcps.toString()).doesNotContain("mcp-canary");
    }

    private void assertWorkflowCombination() {
        AgentGroupConfig group = StudioIrSdk.loadAgentGroup(
                ControllerIrTestFixture.realRootIr(), realContext());
        assertThat(group.getMainAgent().getControllerConfig().getIntentWorkflows()).hasSize(1);
        assertThat(group.getMainAgent().getControllerConfig().getDefaultWorkflow()).isNotNull();
        assertThat(group.getMainAgent().getControllerConfig().getWorkflows()).hasSize(2);
    }

    private void assertDirectJavaEquivalence() {
        AgentGroupConfig fromIr = StudioIrSdk.loadAgentGroup(
                ControllerIrTestFixture.realRootIr(), realContext());
        AgentGroupConfig direct = AgentGroupConfig.builder()
                .groupId(fromIr.getGroupId())
                .groupName(fromIr.getGroupName())
                .description(fromIr.getDescription())
                .mainAgent(fromIr.getMainAgent())
                .agents(fromIr.getAgents())
                .groupSettings(fromIr.getGroupSettings())
                .maxAgentCalls(fromIr.getMaxAgentCalls())
                .build();
        assertThat(direct.getGroupId()).isEqualTo(fromIr.getGroupId());
        assertThat(direct.getMainAgent().getId()).isEqualTo(fromIr.getMainAgent().getId());
        assertThat(direct.getMaxAgentCalls()).isEqualTo(fromIr.getMaxAgentCalls());
        assertThat(direct.getGroupSettings()).isEqualTo(fromIr.getGroupSettings());
    }

    private void assertPathAndPublishedVariants() throws Exception {
        Path bundle = ControllerIrTestFixture.materializeBundle(tempDir);
        ControllerIrSource source = new ControllerIrSource.BundleControllerIrSource(bundle);
        Map<String, Object> manifest = ControllerIrTestFixture.resourceMap("manifest.json");
        @SuppressWarnings("unchecked")
        Map<String, String> pathMap = (Map<String, String>) manifest.get("irPathToLocal");
        String rootPath = pathMap.keySet().stream()
                .filter(path -> path.contains(ControllerIrTestFixture.ROOT_AGENT_ID))
                .findFirst()
                .orElseThrow();
        assertThat(source.load(rootPath)).containsEntry("is_published", true);
        assertThat(ControllerIrSource.isPublished("ir/name_1789891929354.json")).isTrue();
        assertThat(ControllerIrSource.isPublished("ir/name.json")).isFalse();
    }

    private ControllerIrAssembleContext realContext() {
        return ControllerIrTestFixture.mapContext(ControllerIrTestFixture.realIrByPath());
    }

    private ControllerIrAssembleContext emptyContext() {
        return ControllerIrAssembleContext.builder()
                .irSource(new ControllerIrSource.MapControllerIrSource(Map.of()))
                .modelMapping(ControllerIrTestFixture.resolvedModelMapping())
                .build();
    }

    private static String realDefaultWorkflowPath() {
        return ControllerIrTestFixture.realIrByPath().keySet().stream()
                .filter(path -> path.contains("8513a1b2"))
                .findFirst()
                .orElseThrow();
    }
}

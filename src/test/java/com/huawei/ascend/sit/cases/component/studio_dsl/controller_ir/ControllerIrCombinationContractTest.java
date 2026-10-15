/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.studio_dsl.controller_ir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.fixtures.studio_dsl.ControllerIrTestFixture;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioDslContractTestBase;
import com.openjiuwen.studio.dsl.ir.StudioIrSdk;
import com.openjiuwen.studio.dsl.ir.controller.ControllerIrAssembleException;
import com.openjiuwen.studio.dsl.ir.controller.ControllerIrAssembleContext;
import com.openjiuwen.studio.dsl.ir.controller.ControllerIrAssembleResult;
import com.openjiuwen.studio.dsl.ir.controller.ControllerIrSource;
import com.openjiuwen.studio.dsl.multiagent.config.AgentConfig;
import com.openjiuwen.studio.dsl.multiagent.config.AgentGroupConfig;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

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
class ControllerIrCombinationContractTest extends StudioDslContractTestBase {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TestFactory
    @Story("MA-031-05-20-C01..C06: 控制器 IR 跨机制组合")
    Stream<DynamicTest> combinationMatrix() {
        return Stream.iterate(1, value -> value + 1)
                .limit(6)
                .map(value -> "C%02d".formatted(value))
                .map(id -> dynamicTest("MA-031-05-20-" + id, () -> assertCombination(id)));
    }

    private void assertCombination(String id) throws Exception {
        switch (id) {
            case "C01" -> assertNestedAndExternalConfigCombination();
            case "C02" -> assertAllWorkflowSlotsCombination();
            case "C03" -> assertMapJsonRecursiveGlobalIntentCombination();
            case "C04" -> assertPluginAndMcpOrderCombination();
            case "C05" -> assertFailureAfterValidChild();
            case "C06" -> assertModelWorkflowExternalCombination();
            default -> throw new IllegalArgumentException("unknown combination case " + id);
        }
    }

    private void assertNestedAndExternalConfigCombination() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        Map<String, Object> child = ControllerIrTestFixture.minimalChild("child");
        Map<String, Object> grandchild = ControllerIrTestFixture.minimalChild("grandchild");
        Map<String, Object> childRef = ControllerIrTestFixture.childReference("child", "ir/child.json");
        childRef.put("description", "parent-description");
        childRef.put("intent", Map.of("name", "parent-intent"));
        ControllerIrTestFixture.configs(root).put("agents", List.of(childRef));
        ControllerIrTestFixture.configs(child).put("agents",
                List.of(ControllerIrTestFixture.childReference("grandchild", "ir/grandchild.json")));
        ControllerIrTestFixture.configs(child).put("plugins",
                List.of(Map.of("id", "plugin-1", "name", "plugin")));
        ControllerIrTestFixture.configs(child).put("mcps",
                List.of(Map.of("id", "mcp-1", "name", "mcp")));

        ControllerIrAssembleResult result = StudioIrSdk.loadAgentGroupResult(root,
                ControllerIrTestFixture.mapContext(Map.of(
                        "ir/child.json", child,
                        "ir/grandchild.json", grandchild)));

        assertThat(result.groupConfig().getAgents())
                .extracting(AgentConfig::getId)
                .containsExactly("grandchild", "child", "root");
        AgentConfig childConfig = result.groupConfig().getAgents().get(1);
        assertThat(childConfig.getControllerConfig().getMetadata().getDescription())
                .isEqualTo("parent-description");
        assertThat(childConfig.getControllerConfig().getMetadata().getIntentName())
                .isEqualTo("parent-intent");
        assertThat(childConfig.getAgentRuntimeConfig().plugins()).hasSize(1);
        assertThat(childConfig.getAgentRuntimeConfig().mcps()).hasSize(1);
    }

    private void assertAllWorkflowSlotsCombination() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        String workflowPath = realDefaultWorkflowPath();
        ControllerIrTestFixture.configs(root).put("workflows", List.of(
                workflow("start", workflowPath, "Start"),
                workflow("end", workflowPath, "End"),
                workflow("default", workflowPath, "Default"),
                workflow("intent", workflowPath, "IntentIdentification"),
                workflow("normal", workflowPath, "Normal")));

        AgentGroupConfig group = StudioIrSdk.loadAgentGroup(root, realContext());
        assertThat(group.getMainAgent().getControllerConfig().getStartWorkflow()).isNotNull();
        assertThat(group.getMainAgent().getControllerConfig().getEndWorkflow()).isNotNull();
        assertThat(group.getMainAgent().getControllerConfig().getDefaultWorkflow()).isNotNull();
        assertThat(group.getMainAgent().getControllerConfig().getIntentWorkflows()).hasSize(1);
        assertThat(group.getMainAgent().getControllerConfig().getWorkflows()).hasSize(1);
    }

    private void assertMapJsonRecursiveGlobalIntentCombination() throws Exception {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        Map<String, Object> child = ControllerIrTestFixture.minimalChild("child");
        ControllerIrTestFixture.configs(root).put("agents",
                List.of(ControllerIrTestFixture.childReference("child", "ir/child.json")));
        ControllerIrTestFixture.configs(child).put("global_intents", List.of(Map.of(
                "name", "global",
                "handler_type", "Workflow",
                "handler", Map.of("ir_path", realDefaultWorkflowPath(), "name", "global-workflow"),
                "action", "Continue")));
        Map<String, Map<String, Object>> sources = new java.util.LinkedHashMap<>(
                ControllerIrTestFixture.realIrByPath());
        sources.put("ir/child.json", child);
        ControllerIrAssembleContext context = ControllerIrTestFixture.mapContext(sources);

        AgentGroupConfig fromMap = StudioIrSdk.loadAgentGroup(root, context);
        AgentGroupConfig fromJson = StudioIrSdk.loadAgentGroupJson(
                MAPPER.writeValueAsString(root), context);
        assertThat(fromJson.getAgents()).extracting(AgentConfig::getId)
                .containsExactlyElementsOf(fromMap.getAgents().stream().map(AgentConfig::getId).toList());
        AgentConfig childConfig = fromJson.getAgents().stream()
                .filter(agent -> "child".equals(agent.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(childConfig.getControllerConfig().getGlobalWorkflows()).hasSize(1);
    }

    private void assertPluginAndMcpOrderCombination() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        List<Map<String, Object>> plugins = List.of(
                Map.of("id", "plugin-1", "name", "p1"),
                Map.of("id", "plugin-2", "name", "p2"));
        List<Map<String, Object>> mcps = List.of(
                Map.of("id", "mcp-1", "name", "m1"),
                Map.of("id", "mcp-2", "name", "m2"));
        ControllerIrTestFixture.configs(root).put("plugins", plugins);
        ControllerIrTestFixture.configs(root).put("mcps", mcps);

        AgentGroupConfig group = StudioIrSdk.loadAgentGroup(root, emptyContext());
        assertThat(group.getMainAgent().getAgentRuntimeConfig().plugins()).containsExactlyElementsOf(plugins);
        assertThat(group.getMainAgent().getAgentRuntimeConfig().mcps()).containsExactlyElementsOf(mcps);
    }

    private void assertFailureAfterValidChild() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        Map<String, Object> child = ControllerIrTestFixture.minimalChild("child");
        ControllerIrTestFixture.configs(root).put("agents",
                List.of(ControllerIrTestFixture.childReference("child", "ir/child.json")));
        ControllerIrTestFixture.configs(root).put("workflows", List.of(Map.of(
                "ir_path", "ir/invalid.json",
                "workflow_type", "Normal")));
        Map<String, Object> before = MAPPER.convertValue(root, Map.class);

        assertThatThrownBy(() -> StudioIrSdk.loadAgentGroupResult(root,
                ControllerIrTestFixture.mapContext(Map.of(
                        "ir/child.json", child,
                        "ir/invalid.json", Map.of("workflowId", "bad")))))
                .isInstanceOfSatisfying(ControllerIrAssembleException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ControllerIrAssembleException.CONTROLLER_WORKFLOW_ASSEMBLE_FAILED));
        assertThat(root).isEqualTo(before);
    }

    private void assertModelWorkflowExternalCombination() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        ControllerIrTestFixture.configs(root).put("modelConfig",
                Map.of("modelName", ControllerIrTestFixture.MODEL_ID));
        ControllerIrTestFixture.configs(root).put("workflows",
                List.of(workflow("default", realDefaultWorkflowPath(), "Default")));
        ControllerIrTestFixture.configs(root).put("plugins",
                List.of(Map.of("id", "plugin-1", "name", "plugin")));
        ControllerIrTestFixture.configs(root).put("mcps",
                List.of(Map.of("id", "mcp-1", "name", "mcp")));

        AgentGroupConfig group = StudioIrSdk.loadAgentGroup(root, realContext());
        assertThat(group.getMainAgent().getControllerConfig().getDefaultWorkflow()).isNotNull();
        assertThat(group.getMainAgent().getAgentRuntimeConfig().plugins()).hasSize(1);
        assertThat(group.getMainAgent().getAgentRuntimeConfig().mcps()).hasSize(1);
    }

    private static Map<String, Object> workflow(String id, String irPath, String type) {
        return Map.of("id", id, "name", id, "ir_path", irPath, "workflow_type", type);
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

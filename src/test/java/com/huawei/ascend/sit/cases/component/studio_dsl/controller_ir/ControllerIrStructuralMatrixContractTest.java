/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.studio_dsl.controller_ir;

import com.huawei.ascend.sit.fixtures.studio_dsl.ControllerIrTestFixture;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioDslContractTestBase;
import com.openjiuwen.studio.dsl.controller.common.ActionAfterCompletionType;
import com.openjiuwen.studio.dsl.controller.config.ControllerConfig;
import com.openjiuwen.studio.dsl.controller.config.WorkflowConfig;
import com.openjiuwen.studio.dsl.ir.StudioIrSdk;
import com.openjiuwen.studio.dsl.ir.controller.ControllerIrAssembleContext;
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

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
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
class ControllerIrStructuralMatrixContractTest extends StudioDslContractTestBase {
    @TempDir
    Path tempDir;

    @TestFactory
    @Story("MA-031-05-20-S01..S25: 控制器 IR 字段、结构与边界全表")
    Stream<DynamicTest> structuralMatrix() {
        return Stream.of(
                        "S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08", "S09",
                        "S10", "S11", "S12", "S13", "S14", "S15", "S16", "S17", "S18",
                        "S19", "S20", "S21", "S22", "S23", "S24", "S25")
                .map(id -> dynamicTest("MA-031-05-20-" + id, () -> assertStructuralCase(id)));
    }

    private void assertStructuralCase(String id) {
        switch (id) {
            case "S01" -> assertRootIdentity();
            case "S02" -> assertChildIdentity();
            case "S03" -> assertSourceInjection();
            case "S04" -> assertInputs();
            case "S05" -> assertModeAndPlan();
            case "S06" -> assertModelConfig();
            case "S07" -> assertIterationAndHistory();
            case "S08" -> assertGlobalIntents();
            case "S09" -> assertGlobalVariables();
            case "S10" -> assertPromptContextMemory();
            case "S11" -> assertPluginsAndMcps();
            case "S12" -> assertSkills();
            case "S13" -> assertIntentIdentification();
            case "S14" -> assertWorkflowSlots();
            case "S15" -> assertWorkflowConfigFields();
            case "S16" -> assertRecursiveClosure();
            case "S17" -> assertGroupConfigIdentity();
            case "S18" -> assertGroupSettingsAndBuilder();
            case "S19" -> assertMaxAgentCallsBoundary();
            case "S20" -> assertMaxIterationBoundary();
            case "S21" -> assertChatHistoryBoundary();
            case "S22" -> assertPromptBoundary();
            case "S23" -> assertMultiLayerIntentIdentification();
            case "S24" -> assertMissingWorkflowTypeDefaultsToNormal();
            case "S25" -> assertUnknownWorkflowTypeDefaultsToNormal();
            default -> throw new IllegalArgumentException("unknown structural case " + id);
        }
    }

    private void assertRootIdentity() {
        ControllerIrAssembleResult result = assembleRealBundle();
        AgentGroupConfig group = result.groupConfig();
        assertThat(group.getGroupId()).isEqualTo(ControllerIrTestFixture.ROOT_AGENT_ID);
        assertThat(group.getGroupName()).isEqualTo("test111");
        assertThat(group.getDescription()).isEqualTo("test");
        assertThat(group.getMainAgent().getAgentRuntimeConfig().agentVersion()).isEqualTo("0.6.0");
    }

    private void assertChildIdentity() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        Map<String, Object> child = ControllerIrTestFixture.minimalChild("child-a");
        ControllerIrTestFixture.configs(root).put("agents",
                List.of(ControllerIrTestFixture.childReference("child-a", "ir/child-a.json")));
        ControllerIrAssembleResult result = StudioIrSdk.loadAgentGroupResult(root,
                ControllerIrTestFixture.mapContext(Map.of("ir/child-a.json", child)));

        AgentConfig childConfig = result.groupConfig().getAgents().get(0);
        assertThat(childConfig.getId()).isEqualTo("child-a");
        assertThat(childConfig.getControllerConfig().getMetadata().getDescription())
                .isEqualTo("child-a-description");
        assertThat(childConfig.getIrPath()).isEqualTo("ir/child-a.json");
        assertThat(childConfig.isPublished()).isFalse();
    }

    private void assertSourceInjection() {
        ControllerIrAssembleResult result = assembleRealBundle();
        AgentConfig root = result.groupConfig().getMainAgent();
        assertThat(root.getIrPath()).contains("agent/ir/" + ControllerIrTestFixture.ROOT_AGENT_ID);
        assertThat(root.isPublished()).isTrue();
    }

    private void assertInputs() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        root.put("inputs", List.of(Map.of(
                "name", "query",
                "type", "string",
                "description", "user query",
                "required", true)));
        AgentConfig config = StudioIrSdk.loadAgentGroup(root, emptyContext()).getMainAgent();
        assertThat(config.getControllerConfig().getInputVariables())
                .singleElement()
                .satisfies(value -> assertThat(value)
                        .containsEntry("name", "query")
                        .containsEntry("type", "string")
                        .containsEntry("required", true));
    }

    private void assertModeAndPlan() {
        AgentConfig config = StudioIrSdk.loadAgentGroup(
                ControllerIrTestFixture.minimalRoot(), emptyContext()).getMainAgent();
        assertThat(config.getControllerConfig().getPlanMode()).isEqualTo("Controller");
        assertThat(config.getControllerConfig().getMetadata().getMode()).isEqualTo("Controller");
    }

    private void assertModelConfig() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        ControllerIrTestFixture.configs(root).put("modelConfig",
                Map.of("modelName", ControllerIrTestFixture.MODEL_ID, "modelType", "OpenAI",
                        "hyperParameters", Map.of("temperature", 0.1),
                        "apiKey", "must-not-leak", "endpoint", "http://must-not-leak"));
        Map<String, Object> model = (Map<String, Object>) StudioIrSdk
                .loadAgentGroup(root, emptyContext()).getMainAgent().getControllerConfig().getModel();
        assertThat(model)
                .containsOnlyKeys("modelName", "modelType", "hyperParameters")
                .containsEntry("modelName", ControllerIrTestFixture.MODEL_ID)
                .containsEntry("modelType", "OpenAI")
                .containsEntry("hyperParameters", Map.of("temperature", 0.1));
    }

    private void assertIterationAndHistory() {
        ControllerConfig config = StudioIrSdk.loadAgentGroup(
                ControllerIrTestFixture.minimalRoot(), emptyContext())
                .getMainAgent().getControllerConfig();
        assertThat(config.getMaxIterations()).isEqualTo(20);
        assertThat(config.getChatHistoryMaxTurn()).isEqualTo(20);
        assertThat(config.isSpecifyWorkflowOrder()).isFalse();
    }

    private void assertGlobalIntents() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        ControllerIrTestFixture.configs(root).put("global_intents", List.of(Map.of(
                "id", "intent-1",
                "name", "intent",
                "description", "intent description",
                "handler_type", "Text",
                "handler", Map.of("name", "handler"),
                "action", "Continue")));
        ControllerConfig config = StudioIrSdk.loadAgentGroup(root, emptyContext())
                .getMainAgent().getControllerConfig();
        assertThat(config.getGlobalIntents()).singleElement()
                .satisfies(value -> assertThat(value.getName()).isEqualTo("intent"));
    }

    private void assertGlobalVariables() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        ControllerIrTestFixture.configs(root).put("global_variables",
                List.of(Map.of("name", "tenant", "type", "string", "default", "A")));
        ControllerConfig config = StudioIrSdk.loadAgentGroup(root, emptyContext())
                .getMainAgent().getControllerConfig();
        assertThat(config.getGlobalVariables()).singleElement()
                .satisfies(value -> assertThat(value).containsEntry("default", "A"));
    }

    private void assertPromptContextMemory() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        ControllerIrTestFixture.configs(root).put("sysPromptTemplate", "prompt");
        ControllerIrTestFixture.configs(root).put("context", Map.of("custom", "context"));
        ControllerIrTestFixture.configs(root).put("memory", Map.of("store", "memory"));
        AgentConfig config = StudioIrSdk.loadAgentGroup(root, emptyContext()).getMainAgent();
        assertThat(config.getAgentRuntimeConfig().systemPromptTemplate()).isEqualTo("prompt");
        assertThat(config.getAgentRuntimeConfig().context()).isEqualTo(Map.of("custom", "context"));
        assertThat(config.getAgentRuntimeConfig().memory()).isEqualTo(Map.of("store", "memory"));
    }

    private void assertPluginsAndMcps() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        ControllerIrTestFixture.configs(root).put("plugins",
                List.of(Map.of("id", "plugin-1", "name", "plugin", "headers", Map.of("X-A", "B"))));
        ControllerIrTestFixture.configs(root).put("mcps",
                List.of(Map.of("id", "mcp-1", "name", "mcp", "arguments", List.of("a"))));
        AgentConfig config = StudioIrSdk.loadAgentGroup(root, emptyContext()).getMainAgent();
        assertThat(config.getAgentRuntimeConfig().plugins()).hasSize(1);
        assertThat(config.getAgentRuntimeConfig().mcps()).hasSize(1);
    }

    private void assertSkills() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        ControllerIrTestFixture.configs(root).put("skills", Map.of(
                "skill_dir", "/skills",
                "skill_info", List.of(Map.of("id", "skill-1"))));
        AgentConfig config = StudioIrSdk.loadAgentGroup(root, emptyContext()).getMainAgent();
        assertThat(config.getAgentRuntimeConfig().skillDir()).isEqualTo("/skills");
        assertThat(config.getAgentRuntimeConfig().skillInfos()).containsExactly(Map.of("id", "skill-1"));
    }

    private void assertIntentIdentification() {
        ControllerIrAssembleResult result = assembleRealBundle();
        assertThat(result.groupConfig().getMainAgent().getControllerConfig()
                .getIntentIdentification()).isNotNull();
    }

    private void assertMultiLayerIntentIdentification() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        Map<String, Object> multiLayerConfig = new LinkedHashMap<>();
        multiLayerConfig.put("first_layer", "first");
        multiLayerConfig.put("second_layer", "second");
        ControllerIrTestFixture.configs(root).put("intent_identification", Map.of(
                "type", "WORKFLOW",
                "id", "intent-config",
                "enable_multi_layer_detection", true,
                "multi_layer_id_cfg", multiLayerConfig));

        Object intentConfig = StudioIrSdk.loadAgentGroup(root, emptyContext())
                .getMainAgent().getControllerConfig().getIntentIdentification();
        assertThat(intentConfig).isNotNull();
        assertReflectedProperty(intentConfig, "enable_multi_layer_detection", true);
        assertReflectedProperty(intentConfig, "multi_layer_id_cfg", multiLayerConfig);
    }

    private void assertMissingWorkflowTypeDefaultsToNormal() {
        ControllerConfig config = controllerConfigWithWorkflowEntry(Map.of(
                "ir_path", realDefaultWorkflowPath()));
        assertThat(config.getDefaultWorkflow()).isNull();
        assertThat(config.getWorkflows()).hasSize(1);
        assertThat(((WorkflowConfig) config.getWorkflows().get(0)).getActionAfterCompletion())
                .isEqualTo(ActionAfterCompletionType.CONTINUE);
    }

    private void assertUnknownWorkflowTypeDefaultsToNormal() {
        ControllerConfig config = controllerConfigWithWorkflowEntry(Map.of(
                "ir_path", realDefaultWorkflowPath(),
                "workflow_type", "FutureType"));
        assertThat(config.getDefaultWorkflow()).isNull();
        assertThat(config.getWorkflows()).hasSize(1);
        assertThat(((WorkflowConfig) config.getWorkflows().get(0)).getActionAfterCompletion())
                .isEqualTo(ActionAfterCompletionType.CONTINUE);
    }

    private ControllerConfig controllerConfigWithWorkflowEntry(Map<String, Object> entry) {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        ControllerIrTestFixture.configs(root).put("workflows", List.of(entry));
        return StudioIrSdk.loadAgentGroup(root,
                        ControllerIrTestFixture.mapContext(ControllerIrTestFixture.realIrByPath()))
                .getMainAgent().getControllerConfig();
    }

    private String realDefaultWorkflowPath() {
        return ControllerIrTestFixture.realIrByPath().keySet().stream()
                .filter(path -> path.contains("8513a1b2"))
                .findFirst()
                .orElseThrow();
    }

    private void assertReflectedProperty(Object target, String snakeCaseName, Object expected) {
        String camelCaseName = toCamelCase(snakeCaseName);
        Method accessor = findAccessor(target.getClass(), camelCaseName);
        if (accessor == null) {
            throw new AssertionError("IntentIdentificationConfig missing L2 property: " + snakeCaseName);
        }
        try {
            assertThat(accessor.invoke(target)).as(snakeCaseName).isEqualTo(expected);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Cannot read IntentIdentificationConfig property: " + snakeCaseName,
                    exception);
        }
    }

    private Method findAccessor(Class<?> type, String camelCaseName) {
        String suffix = Character.toUpperCase(camelCaseName.charAt(0)) + camelCaseName.substring(1);
        for (String name : List.of("get" + suffix, "is" + suffix)) {
            try {
                return type.getMethod(name);
            } catch (NoSuchMethodException ignored) {
                // Try the next JavaBean accessor shape.
            }
        }
        return null;
    }

    private String toCamelCase(String snakeCaseName) {
        StringBuilder camelCase = new StringBuilder();
        for (String part : snakeCaseName.split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (camelCase.length() == 0) {
                camelCase.append(part);
            } else {
                camelCase.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return camelCase.toString();
    }

    private void assertWorkflowSlots() {
        ControllerConfig config = assembleRealBundle().groupConfig().getMainAgent().getControllerConfig();
        assertThat(config.getDefaultWorkflow()).isNotNull();
        assertThat(config.getIntentWorkflows()).hasSize(1);
        assertThat(config.getWorkflows()).hasSize(2);
    }

    private void assertWorkflowConfigFields() {
        WorkflowConfig defaultWorkflow = (WorkflowConfig) assembleRealBundle()
                .groupConfig().getMainAgent().getControllerConfig().getDefaultWorkflow();
        assertThat(defaultWorkflow.getConfigName()).contains("闲聊");
        assertThat(defaultWorkflow.getIntentName()).contains("闲聊");
        assertThat(defaultWorkflow.getActionAfterCompletion())
                .isEqualTo(ActionAfterCompletionType.WAITING_USER_INPUT);
        assertThat(defaultWorkflow.getArguments()).isNotEmpty();
        assertThat(defaultWorkflow.getWorkflowIr()).containsKey("ir_path");
    }

    private void assertRecursiveClosure() {
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
        assertThat(result.agentCount()).isEqualTo(3);
        assertThat(result.groupConfig().getAgents())
                .extracting(AgentConfig::getId)
                .containsExactly("grandchild", "child", "root");
        assertThat(result.referencePaths()).hasSize(3);
    }

    private void assertGroupConfigIdentity() {
        AgentGroupConfig group = assembleRealBundle().groupConfig();
        assertThat(group.getMainAgent().getId()).isEqualTo(group.getGroupId());
        assertThat(group.getAgents()).extracting(AgentConfig::getId).containsExactly(group.getGroupId());
    }

    private void assertGroupSettingsAndBuilder() {
        AgentGroupConfig group = assembleRealBundle().groupConfig();
        assertThat(group.getGroupSettings()).isNotNull();
        assertThat(group.getMaxAgentCalls()).isEqualTo(10);
        assertThatThrownBy(() -> AgentGroupConfig.builder()
                .groupId("g")
                .groupName("G")
                .mainAgent(group.getMainAgent())
                .agents(List.of(group.getMainAgent()))
                .groupSettings(GroupSettings.defaults())
                .maxAgentCalls(0)
                .build()).isInstanceOf(IllegalArgumentException.class);
    }

    private void assertMaxAgentCallsBoundary() {
        for (int value : new int[] {1, 7}) {
            Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
            root.put("max_agent_calls", value);
            assertThat(StudioIrSdk.loadAgentGroup(root, emptyContext()).getMaxAgentCalls())
                    .isEqualTo(value);
        }
    }

    private void assertMaxIterationBoundary() {
        for (int value : new int[] {0, 100}) {
            Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
            ControllerIrTestFixture.configs(root).put("maxIteration", value);
            assertThat(StudioIrSdk.loadAgentGroup(root, emptyContext())
                    .getMainAgent().getControllerConfig().getMaxIterations())
                    .isEqualTo(value);
        }
    }

    private void assertChatHistoryBoundary() {
        for (int value : new int[] {0, 40}) {
            Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
            ControllerIrTestFixture.configs(root).put("chatHistoryMaxTurn", value);
            assertThat(StudioIrSdk.loadAgentGroup(root, emptyContext())
                    .getMainAgent().getControllerConfig().getChatHistoryMaxTurn())
                    .isEqualTo(value);
        }
    }

    private void assertPromptBoundary() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        String prompt = "x".repeat(10000);
        ControllerIrTestFixture.configs(root).put("sysPromptTemplate", prompt);
        assertThat(StudioIrSdk.loadAgentGroup(root, emptyContext())
                .getMainAgent().getAgentRuntimeConfig().systemPromptTemplate())
                .isEqualTo(prompt);
    }

    private ControllerIrAssembleResult assembleRealBundle() {
        return StudioIrSdk.loadAgentGroupFromBundleResult(
                ControllerIrTestFixture.materializeBundle(tempDir),
                ControllerIrTestFixture.ROOT_AGENT_ID,
                ControllerIrAssembleContext.builder()
                        .modelMapping(ControllerIrTestFixture.resolvedModelMapping())
                        .build());
    }

    private ControllerIrAssembleContext emptyContext() {
        return ControllerIrAssembleContext.builder()
                .irSource(new ControllerIrSource.MapControllerIrSource(Map.of()))
                .modelMapping(ControllerIrTestFixture.resolvedModelMapping())
                .build();
    }
}

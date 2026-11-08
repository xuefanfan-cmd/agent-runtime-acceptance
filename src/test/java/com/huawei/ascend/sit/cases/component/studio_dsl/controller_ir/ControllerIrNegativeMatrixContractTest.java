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
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.assertj.core.api.SoftAssertions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

@Tag("component")
@Tag("contract")
@Tag("feat-031")
@Feature("FEAT-031: Studio DSL Java 承载")
class ControllerIrNegativeMatrixContractTest extends StudioDslContractTestBase {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @TestFactory
    @Story("MA-031-05-20-N01..N49: 控制器 IR 负面、错误与诊断全表")
    Stream<DynamicTest> negativeMatrix() {
        return Stream.iterate(1, value -> value + 1)
                .limit(49)
                .map(value -> "N%02d".formatted(value))
                .map(id -> dynamicTest("MA-031-05-20-" + id, () -> assertNegativeCase(id)));
    }

    private void assertNegativeCase(String id) {
        switch (id) {
            case "N01" -> assertFailure(
                    () -> StudioIrSdk.loadAgentGroupJson("{", emptyContext()),
                    ControllerIrAssembleException.CONTROLLER_IR_INVALID);
            case "N02" -> {
                Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
                root.put("agentVersion", "999");
                assertFailure(root, Map.of(), ControllerIrAssembleException.CONTROLLER_IR_VERSION_NOT_SUPPORTED);
            }
            case "N03" -> {
                Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
                root.remove("schemaVersion");
                assertFailure(root, Map.of(), ControllerIrAssembleException.CONTROLLER_IR_SCHEMA_INVALID);
            }
            case "N04" -> {
                Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
                root.put("configs", "invalid");
                assertFailure(root, Map.of(), ControllerIrAssembleException.CONTROLLER_IR_SCHEMA_INVALID);
            }
            case "N05" -> {
                Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
                ControllerIrTestFixture.configs(root).put("mode", "ReAct");
                assertFailure(root, Map.of(), ControllerIrAssembleException.CONTROLLER_IR_MODE_INVALID);
            }
            case "N06" -> assertChildModeFailure();
            case "N07" -> {
                Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
                Map<String, Object> reference = ControllerIrTestFixture.childReference("child", "ir/child.json");
                reference.remove("id");
                ControllerIrTestFixture.configs(root).put("agents", List.of(reference));
                assertFailure(root, Map.of(), ControllerIrAssembleException.CONTROLLER_IR_SCHEMA_INVALID);
            }
            case "N08" -> {
                Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
                ControllerIrTestFixture.configs(root).put("agents",
                        List.of(ControllerIrTestFixture.childReference("child", "ir/child.json")));
                assertFailure(root, Map.of("ir/child.json", ControllerIrTestFixture.minimalChild("other")),
                        ControllerIrAssembleException.CONTROLLER_IR_REFERENCE_MISSING);
            }
            case "N09" -> {
                Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
                ControllerIrTestFixture.configs(root).put("agents", List.of(
                        ControllerIrTestFixture.childReference("child", "ir/a.json"),
                        ControllerIrTestFixture.childReference("child", "ir/b.json")));
                assertFailure(root,
                        Map.of("ir/a.json", ControllerIrTestFixture.minimalChild("child"),
                                "ir/b.json", ControllerIrTestFixture.minimalChild("child")),
                        ControllerIrAssembleException.CONTROLLER_AGENT_ID_INVALID);
            }
            case "N10" -> assertRootChildIdCollision();
            case "N11" -> assertCycleById();
            case "N12" -> assertCycleByPath();
            case "N13" -> assertDepthExceeded();
            case "N14" -> {
                Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
                ControllerIrTestFixture.configs(root).put("agents",
                        List.of(ControllerIrTestFixture.childReference("child", "ir/child.json")));
                assertFailureWithContext(root, ControllerIrAssembleContext.builder().build(),
                        ControllerIrAssembleException.CONTROLLER_IR_REFERENCE_MISSING);
            }
            case "N15" -> assertFailure(
                    () -> StudioIrSdk.loadAgentGroupFromBundleResult(
                            ControllerIrTestFixture.materializeBundle(tempDir), "missing-agent", emptyContext()),
                    ControllerIrAssembleException.CONTROLLER_IR_INVALID);
            case "N16" -> assertBundlePathEscape();
            case "N17" -> assertModelMappingMissing();
            case "N18" -> {
                Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
                ControllerIrTestFixture.configs(root).put("maxIteration", -1);
                assertFailure(root, Map.of(), ControllerIrAssembleException.CONTROLLER_IR_SCHEMA_INVALID);
            }
            case "N19" -> {
                Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
                ControllerIrTestFixture.configs(root).put("intent_identification",
                        Map.of("type", "UNKNOWN"));
                assertFailure(root, Map.of(), ControllerIrAssembleException.CONTROLLER_IR_SCHEMA_INVALID);
            }
            case "N20" -> assertDuplicateWorkflowSlot();
            case "N21" -> assertPluginAndMcpFailure();
            case "N22" -> assertSensitiveAndAtomicBoundary();
            case "N23" -> assertWorkflowFailureAfterValidChild();
            case "N24" -> assertPluginFailureAfterValidWorkflow();
            case "N25" -> assertRootAgentNameBlank();
            case "N26" -> assertParentModeInvalidDiagnostics();
            case "N27" -> assertMaxAgentCallsInvalid("10");
            case "N28" -> assertMaxAgentCallsInvalid(true);
            case "N29" -> assertMaxAgentCallsInvalid(0);
            case "N30" -> assertMaxAgentCallsInvalid(-1);
            case "N31" -> assertMaxAgentCallsInvalid(Integer.MIN_VALUE);
            case "N32" -> assertMaxAgentCallsInvalidDiagnostics();
            case "N33" -> assertWorkflowActionInvalid();
            case "N34" -> assertMaxIterationUpperBoundary();
            case "N35" -> assertChatHistoryNegative();
            case "N36" -> assertPromptUpperBoundary();
            case "N37" -> assertModelMappingBlankEndpoint();
            case "N38" -> assertModelMappingBlankApiKey();
            case "N39" -> assertDescendantIdCollisionDiagnostics();
            case "N40" -> assertBundleSymlinkEscape();
            case "N41" -> assertNonStringWorkflowType();
            case "N42" -> assertDottedPublishedFilename();
            case "N43" -> assertDotSegmentCycleNormalization();
            case "N44" -> assertNestedSensitiveAndExceptionBoundary();
            case "N45" -> assertRootAgentNameWhitespace();
            case "N46" -> assertOtherNonStringWorkflowTypeTypes();
            case "N47" -> assertNormalizedPathCycleVariants();
            case "N48" -> assertMaxAgentCallsInvalid(1.5d);
            case "N49" -> assertMaxAgentCallsInvalid(2147483648L);
            default -> throw new IllegalArgumentException("unknown negative case " + id);
        }
    }

    private void assertWorkflowFailureAfterValidChild() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        Map<String, Object> child = ControllerIrTestFixture.minimalChild("child");
        ControllerIrTestFixture.configs(root).put("agents",
                List.of(ControllerIrTestFixture.childReference("child", "ir/child.json")));
        ControllerIrTestFixture.configs(root).put("workflows", List.of(Map.of(
                "ir_path", "ir/invalid.json",
                "workflow_type", "Normal")));
        Map<String, Object> before = MAPPER.convertValue(root, Map.class);
        assertFailureWithContext(root, ControllerIrTestFixture.mapContext(Map.of(
                        "ir/child.json", child,
                        "ir/invalid.json", Map.of("workflowId", "bad"))),
                ControllerIrAssembleException.CONTROLLER_WORKFLOW_ASSEMBLE_FAILED);
        assertThat(root).isEqualTo(before);
    }

    private void assertPluginFailureAfterValidWorkflow() {
        Map<String, Object> root = ControllerIrTestFixture.realRootIr();
        ControllerIrTestFixture.configs(root).put("plugins",
                List.of(Map.of("headers", Map.of())));
        Map<String, Object> before = MAPPER.convertValue(root, Map.class);
        assertFailureWithContext(root, ControllerIrTestFixture.mapContext(ControllerIrTestFixture.realIrByPath()),
                ControllerIrAssembleException.CONTROLLER_PLUGIN_ASSEMBLE_FAILED);
        assertThat(root).isEqualTo(before);
    }

    private void assertRootAgentNameBlank() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        root.put("agentName", "");
        // L2 §5.4 (issue #430 裁决): 根 agentName 只校验字段存在且为字符串，空串合法并原样映射 groupName。
        AgentGroupConfig group = StudioIrSdk.loadAgentGroupResult(root, emptyContext()).groupConfig();
        assertThat(group.getGroupName()).isEmpty();
    }

    private void assertRootAgentNameWhitespace() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        root.put("agentName", "   ");
        // L2 §5.4 只要求字段存在且为字符串，未规定 trim；空白串同样合法并原样映射 groupName。
        AgentGroupConfig group = StudioIrSdk.loadAgentGroupResult(root, emptyContext()).groupConfig();
        assertThat(group.getGroupName()).isEqualTo("   ");
    }

    private void assertParentModeInvalidDiagnostics() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        Map<String, Object> child = ControllerIrTestFixture.minimalChild("child");
        Map<String, Object> reference = ControllerIrTestFixture.childReference("child", "ir/child.json");
        reference.put("mode", "ReAct");
        ControllerIrTestFixture.configs(root).put("agents", List.of(reference));
        assertThatThrownBy(() -> StudioIrSdk.loadAgentGroupResult(root,
                ControllerIrTestFixture.mapContext(Map.of("ir/child.json", child))))
                .isInstanceOfSatisfying(ControllerIrAssembleException.class, exception -> {
                    assertThat(exception.code())
                            .isEqualTo(ControllerIrAssembleException.CONTROLLER_IR_MODE_INVALID);
                    assertThat(exception.agentId()).isEqualTo("child");
                    assertThat(exception.agentPath()).isEqualTo("root->child");
                    assertThat(exception.irPath()).isEqualTo("ir/child.json");
                    assertThat(exception.referencePath()).contains("child");
                });
    }

    private void assertMaxAgentCallsInvalid(Object value) {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        root.put("max_agent_calls", value);
        assertFailure(root, Map.of(), ControllerIrAssembleException.CONTROLLER_GROUP_CONFIG_INVALID);
    }

    private void assertMaxAgentCallsInvalidDiagnostics() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        root.put("max_agent_calls", 0);
        assertThatThrownBy(() -> StudioIrSdk.loadAgentGroupResult(root, emptyContext()))
                .isInstanceOfSatisfying(ControllerIrAssembleException.class, exception -> {
                    assertThat(exception.code())
                            .isEqualTo(ControllerIrAssembleException.CONTROLLER_GROUP_CONFIG_INVALID);
                    assertThat(exception.agentId()).isEqualTo("root");
                    assertThat(exception.agentPath()).isEqualTo("root");
                });
    }

    private void assertWorkflowActionInvalid() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        Map<String, Map<String, Object>> sources = ControllerIrTestFixture.realIrByPath();
        String workflowPath = sources.keySet().stream()
                .filter(path -> path.contains("8513a1b2"))
                .findFirst()
                .orElseThrow();
        ControllerIrTestFixture.configs(root).put("workflows", List.of(Map.of(
                "ir_path", workflowPath,
                "action_after_completion", "Bogus")));
        assertFailureWithContext(root, ControllerIrAssembleContext.builder()
                        .irSource(new ControllerIrSource.MapControllerIrSource(sources))
                        .modelMapping(ControllerIrTestFixture.resolvedModelMapping())
                        .build(),
                ControllerIrAssembleException.CONTROLLER_WORKFLOW_ACTION_INVALID);
    }

    private void assertMaxIterationUpperBoundary() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        ControllerIrTestFixture.configs(root).put("maxIteration", 101);
        assertFailure(root, Map.of(), ControllerIrAssembleException.CONTROLLER_IR_SCHEMA_INVALID);
    }

    private void assertChatHistoryNegative() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        ControllerIrTestFixture.configs(root).put("chatHistoryMaxTurn", -1);
        assertFailure(root, Map.of(), ControllerIrAssembleException.CONTROLLER_IR_SCHEMA_INVALID);
    }

    private void assertPromptUpperBoundary() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        ControllerIrTestFixture.configs(root).put("sysPromptTemplate", "x".repeat(10001));
        assertFailure(root, Map.of(), ControllerIrAssembleException.CONTROLLER_IR_SCHEMA_INVALID);
    }

    private void assertModelMappingBlankEndpoint() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        ControllerIrTestFixture.configs(root).put("modelConfig", Map.of("modelName", "model-a"));
        assertFailureWithContext(root, ControllerIrAssembleContext.builder()
                        .irSource(new ControllerIrSource.MapControllerIrSource(Map.of()))
                        .modelMapping(IrModelMapping.filledPlaceholders(
                                Set.of("model-a"), "", "contract-key"))
                        .build(),
                ControllerIrAssembleException.CONTROLLER_MODEL_MAPPING_MISSING);
    }

    private void assertModelMappingBlankApiKey() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        ControllerIrTestFixture.configs(root).put("modelConfig", Map.of("modelName", "model-a"));
        assertFailureWithContext(root, ControllerIrAssembleContext.builder()
                        .irSource(new ControllerIrSource.MapControllerIrSource(Map.of()))
                        .modelMapping(IrModelMapping.filledPlaceholders(
                                Set.of("model-a"), "http://127.0.0.1:1", ""))
                        .build(),
                ControllerIrAssembleException.CONTROLLER_MODEL_MAPPING_MISSING);
    }

    private void assertDescendantIdCollisionDiagnostics() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        root.put("agentId", "shared");
        Map<String, Object> child = ControllerIrTestFixture.minimalChild("child");
        Map<String, Object> grandchild = ControllerIrTestFixture.minimalChild("shared");
        ControllerIrTestFixture.configs(root).put("agents",
                List.of(ControllerIrTestFixture.childReference("child", "ir/child.json")));
        ControllerIrTestFixture.configs(child).put("agents",
                List.of(ControllerIrTestFixture.childReference("shared", "ir/grandchild.json")));
        assertThatThrownBy(() -> StudioIrSdk.loadAgentGroupResult(root,
                ControllerIrTestFixture.mapContext(Map.of(
                        "ir/child.json", child,
                        "ir/grandchild.json", grandchild))))
                .isInstanceOfSatisfying(ControllerIrAssembleException.class, exception -> {
                    assertThat(exception.code())
                            .isEqualTo(ControllerIrAssembleException.CONTROLLER_AGENT_ID_INVALID);
                    assertThat(exception.agentId()).isEqualTo("shared");
                    assertThat(exception.agentPath()).isEqualTo("root->child->shared");
                    assertThat(exception.irPath()).isEqualTo("ir/grandchild.json");
                    assertThat(exception.referencePath()).contains("shared");
                });
    }

    private void assertBundleSymlinkEscape() {
        try {
            Path bundle = Files.createDirectories(tempDir.resolve("symlink-bundle"));
            Path outside = Files.writeString(tempDir.resolve("outside-symlink.json"), "{}");
            Files.createSymbolicLink(bundle.resolve("link.json"), outside);
            Files.writeString(bundle.resolve("manifest.json"),
                    "{\"irPathToLocal\":{\"ir/outside.json\":\"link.json\"}}");
            ControllerIrSource source = new ControllerIrSource.BundleControllerIrSource(bundle);
            assertThatThrownBy(() -> source.load("ir/outside.json"))
                    .isInstanceOfSatisfying(ControllerIrAssembleException.class,
                            exception -> assertThat(exception.code())
                                    .isEqualTo(ControllerIrAssembleException.CONTROLLER_IR_REFERENCE_MISSING));
        } catch (Exception exception) {
            throw new AssertionError("cannot build symlink escape fixture", exception);
        }
    }

    private void assertNonStringWorkflowType() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        Map<String, Map<String, Object>> sources = ControllerIrTestFixture.realIrByPath();
        String workflowPath = sources.keySet().stream()
                .filter(path -> path.contains("8513a1b2"))
                .findFirst()
                .orElseThrow();
        ControllerIrTestFixture.configs(root).put("workflows", List.of(Map.of(
                "ir_path", workflowPath,
                "workflow_type", 42)));
        assertFailureWithContext(root, ControllerIrAssembleContext.builder()
                        .irSource(new ControllerIrSource.MapControllerIrSource(sources))
                        .modelMapping(ControllerIrTestFixture.resolvedModelMapping())
                        .build(),
                ControllerIrAssembleException.CONTROLLER_IR_SCHEMA_INVALID);
    }

    private void assertOtherNonStringWorkflowTypeTypes() {
        Map<String, Map<String, Object>> sources = ControllerIrTestFixture.realIrByPath();
        String workflowPath = sources.keySet().stream()
                .filter(path -> path.contains("8513a1b2"))
                .findFirst()
                .orElseThrow();
        SoftAssertions softly = new SoftAssertions();
        for (Object value : List.<Object>of(true, List.of("x"), Map.of("type", "object"))) {
            Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
            ControllerIrTestFixture.configs(root).put("workflows", List.of(Map.of(
                    "ir_path", workflowPath,
                    "workflow_type", value)));
            softly.assertThatThrownBy(() -> StudioIrSdk.loadAgentGroupResult(root,
                            ControllerIrAssembleContext.builder()
                                    .irSource(new ControllerIrSource.MapControllerIrSource(sources))
                                    .modelMapping(ControllerIrTestFixture.resolvedModelMapping())
                                    .build()))
                    .as("workflow_type type %s", value.getClass().getSimpleName())
                    .isInstanceOfSatisfying(ControllerIrAssembleException.class,
                            exception -> assertThat(exception.code())
                                    .isEqualTo(ControllerIrAssembleException.CONTROLLER_IR_SCHEMA_INVALID));
        }
        softly.assertAll();
    }

    private void assertDottedPublishedFilename() {
        assertThat(ControllerIrSource.isPublished("ir/name.with.dot_1780000000000.json"))
                .as("oracle open_utils split(\".\")[0]: 含点文件名按第一个点截断，判定未发布")
                .isFalse();
    }

    private void assertDotSegmentCycleNormalization() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        root.put("ir_path", "ir/root.json");
        Map<String, Object> child = ControllerIrTestFixture.minimalChild("root");
        ControllerIrTestFixture.configs(root).put("agents",
                List.of(ControllerIrTestFixture.childReference("root", "./ir/root.json")));
        assertFailure(root, Map.of("./ir/root.json", child),
                ControllerIrAssembleException.CONTROLLER_IR_CYCLE);
    }

    private void assertNormalizedPathCycleVariants() {
        SoftAssertions softly = new SoftAssertions();
        for (String alias : List.of("./ir/root.json", "ir/../ir/root.json", "ir//root.json", "ir\\root.json")) {
            Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
            root.put("ir_path", "ir/root.json");
            Map<String, Object> child = ControllerIrTestFixture.minimalChild("root");
            ControllerIrTestFixture.configs(root).put("agents",
                    List.of(ControllerIrTestFixture.childReference("root", alias)));
            softly.assertThatThrownBy(() -> StudioIrSdk.loadAgentGroupResult(
                            root, ControllerIrTestFixture.mapContext(Map.of(alias, child))))
                    .as("path alias %s", alias)
                    .isInstanceOfSatisfying(ControllerIrAssembleException.class,
                            exception -> assertThat(exception.code())
                                    .isEqualTo(ControllerIrAssembleException.CONTROLLER_IR_CYCLE));
        }
        softly.assertAll();
    }

    private void assertNestedSensitiveAndExceptionBoundary() {
        String marker = "S7-N44-SECRET";
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("safe", "kept");
        nested.put("apiKey", marker);
        ControllerIrTestFixture.configs(root).put("plugins", List.of(Map.of(
                "id", "plugin-1",
                "name", "plugin",
                "tags", List.of(List.of(nested)))));
        AgentConfig config = StudioIrSdk.loadAgentGroup(root, emptyContext()).getMainAgent();
        assertThat(config.getAgentRuntimeConfig().plugins().toString())
                .doesNotContain(marker)
                .contains("kept");

        Map<String, Object> failingRoot = ControllerIrTestFixture.minimalRoot();
        ControllerIrTestFixture.configs(failingRoot).put("plugins", List.of(Map.of(
                "headers", Map.of("Authorization", marker),
                "arguments", Map.of("apiKey", marker))));
        assertThatThrownBy(() -> StudioIrSdk.loadAgentGroup(failingRoot, emptyContext()))
                .isInstanceOfSatisfying(ControllerIrAssembleException.class, exception -> {
                    assertThat(exception.getMessage()).doesNotContain(marker);
                    assertThat(String.valueOf(exception.getCause())).doesNotContain(marker);
                });
    }

    private void assertChildModeFailure() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        ControllerIrTestFixture.configs(root).put("agents",
                List.of(ControllerIrTestFixture.childReference("child", "ir/child.json")));
        Map<String, Object> child = ControllerIrTestFixture.minimalChild("child");
        ControllerIrTestFixture.configs(child).put("mode", "ReAct");
        assertFailure(root, Map.of("ir/child.json", child),
                ControllerIrAssembleException.CONTROLLER_IR_MODE_INVALID);
    }

    private void assertRootChildIdCollision() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        root.put("agentId", "shared");
        ControllerIrTestFixture.configs(root).put("agents",
                List.of(ControllerIrTestFixture.childReference("shared", "ir/child.json")));
        assertFailure(root, Map.of("ir/child.json", ControllerIrTestFixture.minimalChild("shared")),
                ControllerIrAssembleException.CONTROLLER_AGENT_ID_INVALID);
    }

    private void assertCycleById() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        root.put("ir_path", "ir/root.json");
        Map<String, Object> child = ControllerIrTestFixture.minimalChild("child");
        ControllerIrTestFixture.configs(root).put("agents",
                List.of(ControllerIrTestFixture.childReference("child", "ir/child.json")));
        ControllerIrTestFixture.configs(child).put("agents",
                List.of(ControllerIrTestFixture.childReference("root", "ir/root.json")));
        assertFailure(root, Map.of("ir/child.json", child, "ir/root.json", root),
                ControllerIrAssembleException.CONTROLLER_IR_CYCLE);
    }

    private void assertCycleByPath() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        root.put("ir_path", "ir/root.json");
        Map<String, Object> child = ControllerIrTestFixture.minimalChild("child");
        child.put("ir_path", "ir/child.json");
        ControllerIrTestFixture.configs(root).put("agents",
                List.of(ControllerIrTestFixture.childReference("child", "ir/child.json")));
        ControllerIrTestFixture.configs(child).put("agents",
                List.of(ControllerIrTestFixture.childReference("child", "ir/child.json")));
        assertFailure(root, Map.of("ir/child.json", child, "ir/root.json", root),
                ControllerIrAssembleException.CONTROLLER_IR_CYCLE);
    }

    private void assertDepthExceeded() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        Map<String, Object> child = ControllerIrTestFixture.minimalChild("child");
        Map<String, Object> grandchild = ControllerIrTestFixture.minimalChild("grandchild");
        ControllerIrTestFixture.configs(root).put("agents",
                List.of(ControllerIrTestFixture.childReference("child", "ir/child.json")));
        ControllerIrTestFixture.configs(child).put("agents",
                List.of(ControllerIrTestFixture.childReference("grandchild", "ir/grandchild.json")));
        ControllerIrAssembleContext context = ControllerIrAssembleContext.builder()
                .irSource(new ControllerIrSource.MapControllerIrSource(Map.of(
                        "ir/child.json", child,
                        "ir/grandchild.json", grandchild)))
                .modelMapping(ControllerIrTestFixture.resolvedModelMapping())
                .maxDepth(1)
                .build();
        assertFailureWithContext(root, context, ControllerIrAssembleException.CONTROLLER_IR_DEPTH_EXCEEDED);
    }

    private void assertBundlePathEscape() {
        try {
            Path bundle = Files.createDirectories(tempDir.resolve("escape-bundle"));
            Path outside = Files.writeString(tempDir.resolve("outside.json"), "{}");
            Files.writeString(bundle.resolve("manifest.json"), """
                    {"irPathToLocal":{"outside":"../outside.json"}}
                    """);
            ControllerIrSource source = new ControllerIrSource.BundleControllerIrSource(bundle);
            assertThatThrownBy(() -> source.load("outside"))
                    .isInstanceOfSatisfying(ControllerIrAssembleException.class,
                            exception -> assertThat(exception.code())
                                    .isEqualTo(ControllerIrAssembleException.CONTROLLER_IR_REFERENCE_MISSING));
            assertThat(outside).exists();
        } catch (Exception exception) {
            throw new AssertionError("cannot build path escape fixture", exception);
        }
    }

    private void assertModelMappingMissing() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        ControllerIrTestFixture.configs(root).put("modelConfig", Map.of("modelName", "missing-model"));
        assertFailureWithContext(root, ControllerIrAssembleContext.builder()
                        .irSource(new ControllerIrSource.MapControllerIrSource(Map.of()))
                        .modelMapping(IrModelMapping.empty())
                        .build(),
                ControllerIrAssembleException.CONTROLLER_MODEL_MAPPING_MISSING);
    }

    private void assertDuplicateWorkflowSlot() {
        Map<String, Object> root = ControllerIrTestFixture.realRootIr();
        String workflowPath = ControllerIrTestFixture.realIrByPath().keySet().stream()
                .filter(path -> path.contains("8513a1b2"))
                .findFirst()
                .orElseThrow();
        ControllerIrTestFixture.configs(root).put("workflows", List.of(
                Map.of("ir_path", workflowPath, "workflow_type", "Start"),
                Map.of("ir_path", workflowPath, "workflow_type", "Start")));
        assertFailure(root, ControllerIrTestFixture.realIrByPath(),
                ControllerIrAssembleException.CONTROLLER_IR_SCHEMA_INVALID);
    }

    private void assertPluginAndMcpFailure() {
        Map<String, Object> pluginRoot = ControllerIrTestFixture.minimalRoot();
        ControllerIrTestFixture.configs(pluginRoot).put("plugins", List.of(Map.of("headers", Map.of())));
        assertFailure(pluginRoot, Map.of(),
                ControllerIrAssembleException.CONTROLLER_PLUGIN_ASSEMBLE_FAILED);

        Map<String, Object> mcpRoot = ControllerIrTestFixture.minimalRoot();
        ControllerIrTestFixture.configs(mcpRoot).put("mcps", List.of(Map.of("arguments", List.of())));
        assertFailure(mcpRoot, Map.of(),
                ControllerIrAssembleException.CONTROLLER_MCP_ASSEMBLE_FAILED);
    }

    private void assertSensitiveAndAtomicBoundary() {
        String marker = "S7-SECRET-MARKER";
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        ControllerIrTestFixture.configs(root).put("modelConfig",
                Map.of("modelName", ControllerIrTestFixture.MODEL_ID, "apiKey", marker));
        ControllerIrTestFixture.configs(root).put("plugins",
                List.of(Map.of("name", "plugin", "headers", Map.of("Authorization", marker))));
        ControllerIrAssembleResult result = StudioIrSdk.loadAgentGroupResult(root, emptyContext());
        assertThat(result.toString()).doesNotContain(marker);
        assertThat(result.groupConfig().getMainAgent().getControllerConfig().getModel().toString())
                .doesNotContain(marker);
        assertThat(result.groupConfig().getMainAgent().getAgentRuntimeConfig().plugins().toString())
                .doesNotContain(marker);
    }

    private void assertFailure(Map<String, Object> root, Map<String, Map<String, Object>> sources,
                               String expectedCode) {
        assertFailureWithContext(root, ControllerIrTestFixture.mapContext(sources), expectedCode);
    }

    private void assertFailureWithContext(Map<String, Object> root, ControllerIrAssembleContext context,
                                          String expectedCode) {
        assertFailure(() -> StudioIrSdk.loadAgentGroupResult(root, context), expectedCode);
    }

    private void assertFailure(ThrowingCall call, String expectedCode) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ControllerIrAssembleException.class,
                        exception -> assertThat(exception.code()).isEqualTo(expectedCode));
    }

    private ControllerIrAssembleContext emptyContext() {
        return ControllerIrAssembleContext.builder()
                .irSource(new ControllerIrSource.MapControllerIrSource(Map.of()))
                .modelMapping(ControllerIrTestFixture.resolvedModelMapping())
                .build();
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}

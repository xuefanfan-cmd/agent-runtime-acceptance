/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.studio_dsl.controller_ir;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.fixtures.studio_dsl.ControllerIrTestFixture;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioDslContractTestBase;
import com.openjiuwen.studio.dsl.ir.StudioIrSdk;
import com.openjiuwen.studio.dsl.ir.controller.ControllerIrAssembleException;
import com.openjiuwen.studio.dsl.multiagent.config.AgentConfig;
import com.openjiuwen.studio.dsl.multiagent.config.AgentGroupConfig;
import com.openjiuwen.studio.dsl.multiagent.config.GroupSettings;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import java.security.MessageDigest;
import java.util.HexFormat;
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
class ControllerIrAntiSelfGreenContractTest extends StudioDslContractTestBase {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> EXTERNAL_CASES = Set.of();

    @TestFactory
    @Story("MA-031-05-20-R01..R06: 反自证、来源与回归")
    Stream<DynamicTest> antiSelfGreenMatrix() {
        return Stream.iterate(1, value -> value + 1)
                .limit(6)
                .map(value -> "R%02d".formatted(value))
                .map(id -> dynamicTest("MA-031-05-20-" + id, () -> assertCase(id)));
    }

    private void assertCase(String id) throws Exception {
        switch (id) {
            case "R01" -> assertDirectBuilderDefaults();
            case "R02" -> assertDirectBuilderCompatibility();
            case "R03" -> assertMutationSensitivity();
            case "R04" -> assertFrozenProvenance();
            case "R05" -> assertSensitiveRecursiveScan();
            case "R06" -> assertUnverifiedRegistry();
            default -> throw new IllegalArgumentException("unknown anti-self-green case " + id);
        }
    }

    private void assertDirectBuilderDefaults() {
        AgentConfig direct = AgentConfig.builder().id("direct").build();
        AgentGroupConfig group = AgentGroupConfig.builder()
                .groupId("direct")
                .groupName("Direct")
                .mainAgent(direct)
                .agents(List.of(direct))
                .groupSettings(GroupSettings.defaults())
                .maxAgentCalls(10)
                .build();
        assertThat(group.getMaxAgentCalls()).isEqualTo(10);
    }

    private void assertDirectBuilderCompatibility() {
        AgentConfig direct = AgentConfig.builder().id("direct").build();
        AgentGroupConfig group = AgentGroupConfig.builder()
                .groupId("direct")
                .groupName("Direct")
                .mainAgent(direct)
                .agents(List.of(direct))
                .groupSettings(GroupSettings.defaults())
                .maxAgentCalls(5)
                .build();
        assertThat(group.getMainAgent().getControllerConfig()).isNull();
        assertThat(group.getMainAgent().getAgentRuntimeConfig()).isNull();
    }

    private void assertMutationSensitivity() {
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        Map<String, Object> child = ControllerIrTestFixture.minimalChild("child");
        ControllerIrTestFixture.configs(child).put("mode", "ReAct");
        ControllerIrTestFixture.configs(root).put("agents",
                List.of(ControllerIrTestFixture.childReference("child", "ir/child.json")));
        assertThatThrownBy(() -> StudioIrSdk.loadAgentGroup(root,
                ControllerIrTestFixture.mapContext(Map.of("ir/child.json", child))))
                .isInstanceOfSatisfying(ControllerIrAssembleException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ControllerIrAssembleException.CONTROLLER_IR_MODE_INVALID));
    }

    private void assertFrozenProvenance() throws Exception {
        assertThat(sha256(ControllerIrTestFixture.resourceBytes(
                "ir/" + ControllerIrTestFixture.ROOT_AGENT_ID + ".json")))
                .isEqualTo("AD5AA4AF7075E42D82912A04CA9FC5B00D6432490D83907BCCCC4021872E449C");
        assertThat(sha256(ControllerIrTestFixture.resourceBytes("manifest.json")))
                .isEqualTo("CC1D0D23337C7B6AE2C5565F0C23F1F1E3828B0546762B48CC3730D9378702EC");
    }

    private void assertSensitiveRecursiveScan() {
        String marker = "S7-R05-SECRET";
        Map<String, Object> root = ControllerIrTestFixture.minimalRoot();
        ControllerIrTestFixture.configs(root).put("modelConfig",
                Map.of("modelName", ControllerIrTestFixture.MODEL_ID, "apiKey", marker));
        ControllerIrTestFixture.configs(root).put("plugins",
                List.of(Map.of("name", "plugin", "headers", Map.of("Authorization", marker))));
        ControllerIrTestFixture.configs(root).put("mcps",
                List.of(Map.of("name", "mcp", "headers", Map.of("X-Token", marker))));
        AgentGroupConfig group = StudioIrSdk.loadAgentGroup(root, ControllerIrTestFixture.mapContext(Map.of()));
        assertThat(flatten(group.getMainAgent().getControllerConfig().getModel())).doesNotContain(marker);
        assertThat(group.getMainAgent().getAgentRuntimeConfig().plugins().toString()).doesNotContain(marker);
        assertThat(group.getMainAgent().getAgentRuntimeConfig().mcps().toString()).doesNotContain(marker);
    }

    private void assertUnverifiedRegistry() throws Exception {
        Map<String, Object> registry = MAPPER.readValue(
                ControllerIrTestFixture.classpathBytes(
                        "/testdata/studio_dsl/controller_ir/unverified-external-cases.json"),
                new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cases = (List<Map<String, Object>>) registry.get("cases");
        assertThat(cases).extracting(value -> value.get("id")).containsExactlyInAnyOrderElementsOf(EXTERNAL_CASES);
        assertThat(cases).allSatisfy(value -> {
            assertThat(value.get("owner")).as("owner").isNotNull();
            assertThat(value.get("resume_when")).as("resume_when").isNotNull();
            assertThat(value.get("evidence")).as("evidence").isNotNull();
        });
    }

    private static String flatten(Object value) {
        return String.valueOf(value);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().withUpperCase().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}

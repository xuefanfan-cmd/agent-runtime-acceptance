package com.huawei.ascend.sit.cases.integration.tscript;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;

@Tag("integration")
@Tag("tscript")
@Tag("feat-039")
@Feature("FEAT-039: 业务扩展工具话术（EDPA）")
class ToolScriptProviderLifecycleTest {
    private static final String SUT = "tool-scripts-edpa";
    private static final String MALFORMED_SUT = "tool-scripts-edpa-malformed-provider";
    private final ToolScriptWireClient wire = new ToolScriptWireClient();

    @Test
    @Story("ts039.spi-load-failure: Provider 构建失败不扩散")
    @DisplayName("TS039-C18: a provider build failure does not hide a later provider")
    void providerBuildFailureDoesNotHideLaterProvider() throws Exception {
        for (String mode : List.of("build-failure", "build-runtime")) {
            try (SutStack stack = start(SUT, "default", mode)) {
                Map<String, Integer> counts = wire.counts(stack.baseUrl(SUT));
                assertThat(counts.getOrDefault("build.tscript_aux", 0)).isEqualTo(0);
                assertThat(counts.getOrDefault("build.tscript_tail", 0)).isEqualTo(1);
                assertThat(ToolScriptFixtureSupport.log(stack, SUT))
                        .contains("EDPA-INVALID-TOOL-PROVIDER: build failed for tscript_aux");
            }
        }
    }

    @Test
    @Story("ts039.spi-builtin-collision: SPI 与内置重名时内置优先")
    @DisplayName("TS039-C21: a provider cannot replace a built-in tool")
    void providerCannotReplaceBuiltinTool() throws Exception {
        try (SutStack stack = start(SUT, "builtin-collision", "builtin-collision")) {
            assertThat(ToolScriptFixtureSupport.log(stack, SUT))
                    .contains("EDPA-TOOL-PROVIDER-SHADOWED: built-in wins for call_mcp");
            assertThat(wire.counts(stack.baseUrl(SUT)).getOrDefault("build.tscript_tail", 0)).isEqualTo(1);
        }
    }

    @Test
    @Story("ts039.spi-external-duplicate: 外部 Provider 重名 fail-fast")
    @DisplayName("TS039-C22: duplicate external providers fail startup deterministically")
    void duplicateExternalProvidersFailStartup() {
        assertThatThrownBy(() -> start(SUT, "default", "external-duplicate"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EDPA-DUPLICATE-TOOL-PROVIDER");
    }

    @Test
    @Story("ts039.spi-invalid-isolation: Provider 无效变体隔离与游标连续性")
    @DisplayName("TS039-C23: invalid provider variants do not hide the valid tail provider")
    void invalidProviderVariantsDoNotHideTailProvider() throws Exception {
        for (String mode : List.of(
                "constructor-failure", "constructor-runtime", "blank-name", "tool-name-failure",
                "tool-name-runtime", "build-failure", "build-runtime", "null-tool", "wrong-card")) {
            try (SutStack stack = start(SUT, "default", mode)) {
                assertThat(wire.counts(stack.baseUrl(SUT)).getOrDefault("build.tscript_tail", 0))
                        .as("tail provider after mode %s", mode)
                        .isEqualTo(1);
            }
        }
    }

    @Test
    @Story("ts039.spi-iterator-stop: ServiceLoader 无法前进时有界终止")
    @DisplayName("TS039-C35: malformed provider discovery terminates with diagnostics")
    void malformedProviderDiscoveryTerminatesWithDiagnostics() {
        assertTimeout(Duration.ofSeconds(70), () -> {
            try (SutStack stack = start(MALFORMED_SUT, "default", "normal")) {
                Map<String, Integer> counts = wire.counts(stack.baseUrl(MALFORMED_SUT));
                assertThat(counts.getOrDefault("provider.construct.Main", 0)).isEqualTo(1);
                assertThat(counts.getOrDefault("build.tscript_probe", 0)).isEqualTo(1);
                assertThat(ToolScriptFixtureSupport.log(stack, MALFORMED_SUT))
                        .contains("EDPA-INVALID-TOOL-PROVIDER");
            }
        });
    }

    @Test
    @Story("ts039.allowed-deduplicate: allowed_tools 保序去重并跳过未知项")
    @DisplayName("TS039-C24: allowed tools are deduplicated before provider builds")
    void allowedToolsAreDeduplicatedBeforeBuild() throws Exception {
        try (SutStack stack = start(SUT, "allowed-deduplicate", "normal")) {
            Map<String, Integer> counts = wire.counts(stack.baseUrl(SUT));
            assertThat(counts.getOrDefault("build.tscript_probe", 0)).isEqualTo(1);
            assertThat(counts.getOrDefault("build.tscript_tail", 0)).isEqualTo(1);
            assertThat(counts.keySet()).noneMatch(key -> key.contains("tscript_unknown"));
            assertThat(ToolScriptFixtureSupport.log(stack, SUT))
                .contains("Unknown tool in allowed_tools: tscript_unknown, skipping");
        }
    }

    @Test
    @Story("ts039.dualguard-no-side-effects: 装配失败后零部分注册")
    @DisplayName("TS039-C30: an assembly failure leaves no partially registered tool")
    void assemblyFailureLeavesNoPartialRegistration() throws Exception {
        Path stdout = Path.of(System.getProperty("basedir", System.getProperty("user.dir")),
                "target", "sit-logs", SUT, "stdout.log");
        int offset = Files.exists(stdout) ? (int) Files.size(stdout) : 0;

        assertThatThrownBy(() -> start(SUT, "default", "external-duplicate"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EDPA-DUPLICATE-TOOL-PROVIDER");

        assertThat(stdout).as("failed startup log").exists();
        byte[] log = Files.readAllBytes(stdout);
        int from = Math.min(offset, log.length);
        String attempt = new String(log, from, log.length - from, StandardCharsets.UTF_8);
        assertThat(attempt)
                .as("the deterministic assembly failure must be recorded")
                .contains("EDPA-DUPLICATE-TOOL-PROVIDER");
        assertThat(attempt)
                .as("provider discovery must have started before the failure")
                .contains("[TSCRIPT-FIXTURE] provider constructed: tscript_probe");
        assertThat(attempt)
                .as("the legal tool listed first must not be built or registered after the failure")
                .doesNotContain("[TSCRIPT-FIXTURE] built tool ");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("descriptionKeywordScenarios")
    @Story("ts039.keyword-invalid-config: description_keywords 非法配置 fail-fast")
    @DisplayName("TS039-C26: description keyword configuration is validated")
    void descriptionKeywordConfigurationIsValidated(String scenario, boolean valid) throws Exception {
        if (!valid) {
            assertThatThrownBy(() -> start(SUT, scenario, "normal"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("EDPA-INVALID-DESCRIPTION-KEYWORD");
            return;
        }
        try (SutStack stack = start(SUT, scenario, "normal")) {
            ToolScriptWireClient.Run run = wire.send(stack.baseUrl(SUT), "ctx-valid-empty",
                    "TSCRIPT call exactly call_mcp once with query_intent=intent_a and script_command=noop.");
            assertThat(run.statusCode()).isEqualTo(200);
            assertThat(run.events("call_mcp", "tool_start")).extracting(event -> event.path("content").asText())
                    .containsExactly("VALID_EMPTY_START");
        }
    }

    private static Stream<Arguments> descriptionKeywordScenarios() {
        return Stream.of(
                Arguments.of("invalid-keyword", false),
                Arguments.of("invalid-keyword-list", false),
                Arguments.of("invalid-keyword-element", false),
                Arguments.of("invalid-keyword-blank", false),
                Arguments.of("invalid-keyword-duplicate", false),
                Arguments.of("invalid-keyword-start", false),
                Arguments.of("invalid-keyword-end", false),
                Arguments.of("valid-empty-keywords", true));
    }

    private static SutStack start(String sut, String scenario, String mode) {
        return ToolScriptFixtureSupport.stackBuilder(TestConfig.load(), sut, scenario, mode).start();
    }
}

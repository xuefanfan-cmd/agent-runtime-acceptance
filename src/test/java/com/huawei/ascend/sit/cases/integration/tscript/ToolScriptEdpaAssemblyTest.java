package com.huawei.ascend.sit.cases.integration.tscript;

import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Tag("tscript")
@Tag("feat-039")
@Feature("FEAT-039: 业务扩展工具话术（EDPA）")
class ToolScriptEdpaAssemblyTest extends AbstractToolScriptSitTest {
    @Test
    @Story("ts039.newtool-emit: 三件套齐备的新工具发射话术")
    @DisplayName("TS039-C01: SPI, allowed_tools and card scripts enable a new tool")
    void completeRegistrationTrioEnablesNewToolScripts() throws Exception {
        assertPair(invoke("tscript_probe", "plain", "finance"),
                "tscript_probe", "FINANCE_START", "FINANCE_END");
    }

    @Test
    @Story("ts039.trio-missing-scripts: 缺卡片话术时工具仍可调用且静默")
    @DisplayName("TS039-C04: a registered tool without scripts remains silent")
    void registeredToolWithoutScriptsRemainsSilent() throws Exception {
        ToolScriptWireClient.Run run = invoke("tscript_no_scripts", "plain", null);
        assertThat(run.events("tscript_no_scripts", "tool_start")).isEmpty();
        assertThat(run.events("tscript_no_scripts", "tool_end")).isEmpty();
        assertThat(counts().getOrDefault("execute.tscript_no_scripts", 0)).isEqualTo(1);
    }

    @Test
    @Story("ts039.no-dual-emit: 新旧链路分治无双发")
    @DisplayName("TS039-C16: a card-driven tool emits one start and one end")
    void cardDrivenToolDoesNotEmitThroughBothScriptPaths() throws Exception {
        ToolScriptWireClient.Run run = invoke("tscript_probe", "plain", "finance");
        assertThat(run.events("tscript_probe", "tool_start")).hasSize(1);
        assertThat(run.events("tscript_probe", "tool_end")).hasSize(1);
    }

    @Test
    @Story("ts039.field-parity: 新旧话术事件字段兼容")
    @DisplayName("TS039-C17: new and legacy events retain the same required fields")
    void newAndLegacyEventsRetainRequiredFields() throws Exception {
        ToolScriptWireClient.Run modern = invoke("tscript_probe", "plain", "finance");
        ToolScriptWireClient.Run legacy = invokeLegacy("call_mcp", "intent_no_keyword", "NO_KEYWORD");
        assertRequiredFields(modern.events("tscript_probe", "tool_start"));
        assertRequiredFields(legacy.events("call_mcp", "tool_start"));
        assertThat(modern.raw()).contains("custom");
        assertThat(legacy.raw()).contains("custom");
    }

    @Test
    @Story("ts039.concurrency-isolation: 并发多工具独立发射")
    @DisplayName("TS039-C20: concurrent distinct tools do not mix script events")
    void concurrentDistinctToolsDoNotMixScripts() throws Exception {
        String context = "ctx-mixed-" + System.nanoTime();
        ToolScriptWireClient.Run run = wire.send(stack.baseUrl(sutName()), context,
                "TSCRIPT call tscript_probe and tscript_aux in the same model turn, exactly once each. "
                        + "Use mode=plain biz_type=finance and distinct canary values MIXED_A and MIXED_B.");
        assertThat(run.statusCode()).as(run.raw()).isEqualTo(200);
        assertToolCallsArePaired(run, "tscript_probe");
        assertToolCallsArePaired(run, "tscript_aux");

        Set<String> probeIds = callIds(run.events("tscript_probe", "tool_start"));
        Set<String> auxiliaryIds = callIds(run.events("tscript_aux", "tool_start"));
        assertThat(probeIds).doesNotContainAnyElementsOf(auxiliaryIds);
        assertThat(counts().getOrDefault("execute.tscript_probe", 0)).isEqualTo(probeIds.size());
        assertThat(counts().getOrDefault("execute.tscript_aux", 0)).isEqualTo(auxiliaryIds.size());
    }

    @Test
    @Story("ts039.singleton-pertask-parity: 单例与 per-task 默认能力一致")
    @DisplayName("TS039-C25: every task receives a fresh scripted tool instance")
    void everyTaskReceivesFreshScriptedToolInstances() throws Exception {
        Map<String, Integer> before = counts();
        assertPair(invoke("tscript_probe", "plain", "finance"),
                "tscript_probe", "FINANCE_START", "FINANCE_END");
        assertPair(invoke("tscript_probe", "plain", "finance"),
                "tscript_probe", "FINANCE_START", "FINANCE_END");
        Map<String, Integer> after = counts();
        assertThat(after.getOrDefault("build.tscript_probe", 0)
                - before.getOrDefault("build.tscript_probe", 0)).isEqualTo(2);
        assertThat(after.getOrDefault("provider.construct.Main", 0)
                - before.getOrDefault("provider.construct.Main", 0)).isEqualTo(2);
        assertThat(after.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("provider.build.Main.instance."))
                .filter(entry -> entry.getValue() == 1)
                .count()).isEqualTo(2);
    }

    private static void assertRequiredFields(List<JsonNode> events) {
        assertThat(events).hasSize(1);
        JsonNode event = events.get(0);
        assertThat(event.path("event").asText()).isNotBlank();
        assertThat(event.path("tool").asText()).isNotBlank();
        assertThat(event.path("content").asText()).isNotBlank();
        assertThat(event.path("timestamp").canConvertToLong()).isTrue();
        assertThat(event.path("conversation_id").asText()).isNotBlank();
    }

    private static void assertToolCallsArePaired(ToolScriptWireClient.Run run, String tool) {
        List<JsonNode> starts = run.events(tool, "tool_start");
        List<JsonNode> ends = run.events(tool, "tool_end");
        assertThat(starts).isNotEmpty();
        assertThat(ends).isNotEmpty();
        assertThat(starts).extracting(event -> event.path("tool_call_id").asText())
                .allSatisfy(id -> assertThat(id).isNotBlank())
                .doesNotHaveDuplicates();
        assertThat(ends).extracting(event -> event.path("tool_call_id").asText())
                .allSatisfy(id -> assertThat(id).isNotBlank())
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(callIds(starts));
    }

    private static Set<String> callIds(List<JsonNode> events) {
        return events.stream()
                .map(event -> event.path("tool_call_id").asText())
                .collect(Collectors.toSet());
    }
}

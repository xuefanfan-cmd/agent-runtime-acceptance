package com.huawei.ascend.sit.cases.integration.tscript;

import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Tag("tscript")
@Tag("feat-038")
@Feature("FEAT-038: 业务扩展工具话术")
class ToolScriptPriorityTest extends AbstractToolScriptSitTest {
    @Test
    @Story("ts038.prio-intent-hit: 意图命中发意图话术")
    @DisplayName("TS038-C01: intent hit uses intent scripts")
    void intentHitUsesIntentScripts() throws Exception {
        assertPair(invoke("tscript_probe", "plain", "finance"),
                "tscript_probe", "FINANCE_START", "FINANCE_END");
    }

    @Test
    @Story("ts038.prio-intent-missing: 意图缺失回落默认")
    @DisplayName("TS038-C02: missing intent falls back to defaults")
    void missingIntentFallsFallsBackToDefaults() throws Exception {
        assertPair(invoke("tscript_probe", "plain", null),
                "tscript_probe", "DEFAULT_START", "DEFAULT_END");
    }

    @Test
    @Story("ts038.prio-intent-nomatch: 意图不匹配回落默认")
    @DisplayName("TS038-C03: unknown intent falls back to defaults")
    void unknownIntentFallsBackToDefaults() throws Exception {
        assertPair(invoke("tscript_probe", "plain", "unknown"),
                "tscript_probe", "DEFAULT_START", "DEFAULT_END");
        assertPair(invoke("tscript_intent_number", "plain", "42"),
                "tscript_intent_number", "DEFAULT_START", "DEFAULT_END");
        assertPair(invoke("tscript_intent_boolean", "plain", "true"),
                "tscript_intent_boolean", "DEFAULT_START", "DEFAULT_END");
    }

    @Test
    @Story("ts038.uinotice-success: ui_notice 成功态覆盖")
    @DisplayName("TS038-C04: success notice overrides the intent end script")
    void successNoticeOverridesIntentEndScript() throws Exception {
        ToolScriptWireClient.Run run = invoke("tscript_probe", "success", "finance");
        assertContents(run.events("tscript_probe", "tool_start"), "FINANCE_START");
        assertSingleContentMatches(run.events("tscript_probe", "tool_end"),
                "SUCCESS result=42\\.0 canary=TS[0-9a-f]{10}");
    }

    @Test
    @Story("ts038.uinotice-failed: ui_notice 失败态覆盖")
    @DisplayName("TS038-C05: business failure notice is status independent")
    void businessFailureNoticeOverridesDefaultEnd() throws Exception {
        ToolScriptWireClient.Run run = invoke("tscript_probe", "business_failure", "finance");
        assertSingleContentStartsWith(run.events("tscript_probe", "tool_end"),
                "FAILED reason=DECLINED canary=TS");
    }

    @Test
    @Story("ts038.uinotice-degraded: ui_notice 降级态覆盖")
    @DisplayName("TS038-C06: degraded notice overrides default end")
    void degradedNoticeOverridesDefaultEnd() throws Exception {
        ToolScriptWireClient.Run run = invoke("tscript_probe", "degraded", "finance");
        assertThat(run.events("tscript_probe", "tool_end"))
                .isNotEmpty()
                .allSatisfy(event -> assertThat(event.path("content").asText())
                        .startsWith("DEGRADED reason=FALLBACK canary=TS"));
    }

    @Test
    @Story("ts038.uinotice-event-fallback: ui_notice event 非法回落")
    @DisplayName("TS038-C07: missing or wrong notice event falls back")
    void missingOrWrongNoticeEventFallsBack() throws Exception {
        assertContents(invoke("tscript_probe", "missing_event", "finance")
                .events("tscript_probe", "tool_end"), "FINANCE_END");
        assertContents(invoke("tscript_probe", "wrong_event", "finance")
                .events("tscript_probe", "tool_end"), "FINANCE_END");
    }

    @Test
    @Story("ts038.uinotice-key-miss: ui_notice key 未命中回落")
    @DisplayName("TS038-C08: unknown notice key falls back")
    void unknownNoticeKeyFallsBack() throws Exception {
        assertContents(invoke("tscript_probe", "missing_key", "finance")
                .events("tscript_probe", "tool_end"), "FINANCE_END");
    }

    @Test
    @Story("ts038.var-missing-literal: 变量缺失保留字面量")
    @DisplayName("TS038-C09: unresolved variable remains literal")
    void unresolvedVariableRemainsLiteral() throws Exception {
        ToolScriptWireClient.Run run = invoke("tscript_probe", "missing_variable", "finance");
        assertThat(run.events("tscript_probe", "tool_end"))
                .isNotEmpty()
                .allSatisfy(event -> assertThat(event.path("content").asText())
                        .startsWith("MISSING value={value} canary=TS"));
    }

    @Test
    @Story("ts038.silent-unconfigured: 未配置话术静默")
    @DisplayName("TS038-C11: an unconfigured tool emits no script events")
    void unconfiguredToolEmitsNoScriptEvents() throws Exception {
        ToolScriptWireClient.Run run = invoke("tscript_no_scripts", "plain", null);
        assertThat(run.events("tscript_no_scripts", "tool_start")).isEmpty();
        assertThat(run.events("tscript_no_scripts", "tool_end")).isEmpty();
        assertThat(counts().getOrDefault("execute.tscript_no_scripts", 0)).isEqualTo(1);
    }

    private static void assertSingleContentStartsWith(List<JsonNode> events, String prefix) {
        assertThat(events).hasSize(1);
        assertThat(events.get(0).path("content").asText()).startsWith(prefix);
    }

    private static void assertSingleContentMatches(List<JsonNode> events, String regex) {
        assertThat(events).hasSize(1);
        assertThat(events.get(0).path("content").asText()).matches(regex);
    }
}

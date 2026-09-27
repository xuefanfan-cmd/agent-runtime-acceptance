package com.huawei.ascend.sit.cases.integration.tscript;

import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Tag("tscript")
@Tag("feat-039")
@Feature("FEAT-039: 业务扩展工具话术（EDPA）")
class ToolScriptLegacyKeywordTest extends AbstractToolScriptSitTest {
    @Test
    @Story("ts039.legacy-mcp: 存量 call_mcp 话术回归")
    @DisplayName("TS039-C05: call_mcp retains configured legacy scripts")
    void callMcpRetainsConfiguredLegacyScripts() throws Exception {
        assertPair(invokeLegacy("call_mcp", "intent_no_keyword", "NO_KEYWORD"),
                "call_mcp", "LEGACY_NO_KEYWORD_START", "LEGACY_NO_KEYWORD_END");
    }

    @Test
    @Story("ts039.legacy-versatile: 存量 call_versatile 话术回归")
    @DisplayName("TS039-C06: call_versatile retains configured legacy scripts")
    void callVersatileRetainsConfiguredLegacyScripts() throws Exception {
        assertPair(invokeLegacy("call_versatile", "intent_no_keyword", "NO_KEYWORD"),
                "call_versatile", "LEGACY_NO_KEYWORD_START", "LEGACY_NO_KEYWORD_END");
    }

    @Test
    @Story("ts039.legacy-subagent: 存量 call_subagent 话术回归")
    @DisplayName("TS039-C07: call_subagent retains configured legacy scripts")
    void callSubagentRetainsConfiguredLegacyScripts() throws Exception {
        ToolScriptWireClient.Run run = invokeLegacy("call_subagent", "intent_no_keyword", "NO_KEYWORD");
        List<JsonNode> starts = run.events("call_subagent", "tool_start");
        List<JsonNode> ends = run.events("call_subagent", "tool_end");

        assertThat(starts).isNotEmpty().allSatisfy(event -> assertThat(event.path("content").asText())
                .isIn("LEGACY_GENERAL_START call_subagent", "LEGACY_NO_KEYWORD_START"));
        assertThat(ends).isNotEmpty().allSatisfy(event -> assertThat(event.path("content").asText())
                .isIn("LEGACY_GENERAL_END call_subagent", "LEGACY_NO_KEYWORD_END"));

        Set<String> startIds = callIds(starts);
        Set<String> endIds = callIds(ends);
        assertThat(starts).extracting(event -> event.path("tool_call_id").asText()).doesNotHaveDuplicates();
        assertThat(ends).extracting(event -> event.path("tool_call_id").asText()).doesNotHaveDuplicates();
        assertThat(startIds).doesNotContain("").containsExactlyInAnyOrderElementsOf(endIds);
    }

    @Test
    @Story("ts039.legacy-nokeyword-parity: 未配关键字时默认链路不变")
    @DisplayName("TS039-C08: an intent without keywords uses its defaults")
    void intentWithoutKeywordsUsesDefaults() throws Exception {
        assertPair(invokeLegacy("call_mcp", "intent_no_keyword", "ANY_DESCRIPTION"),
                "call_mcp", "LEGACY_NO_KEYWORD_START", "LEGACY_NO_KEYWORD_END");
    }

    @Test
    @Story("ts039.keyword-hit-order: 多关键字同时命中时声明顺序首个生效")
    @DisplayName("TS039-C09: the first matching keyword wins")
    void firstMatchingKeywordWins() throws Exception {
        ToolScriptWireClient.Run run = invokeLegacy("call_versatile", "intent_a", "KEY_FIRST KEY_SECOND");
        assertContents(run.events("call_versatile", "tool_start"), "LEGACY_KEY_FIRST_START");
        assertContents(run.events("call_versatile", "tool_end"), "LEGACY_A_END");
    }

    @Test
    @Story("ts039.keyword-miss-fallback: 关键字未命中回落 intent 默认")
    @DisplayName("TS039-C10: a keyword miss falls back to intent defaults")
    void keywordMissFallsBackToIntentDefaults() throws Exception {
        assertPair(invokeLegacy("call_versatile", "intent_a", "NO_MATCH"),
                "call_versatile", "LEGACY_A_START", "LEGACY_A_END");
    }

    @Test
    @Story("ts039.keyword-absent-ok: 未配置 description_keywords 不报错")
    @DisplayName("TS039-C11: an absent keyword list has no error surface")
    void absentKeywordListHasNoErrorSurface() throws Exception {
        ToolScriptWireClient.Run run = invokeLegacy("call_mcp", "intent_no_keyword", "NO_KEYWORDS_DEFINED");
        assertThat(run.statusCode()).isEqualTo(200);
        assertPair(run, "call_mcp", "LEGACY_NO_KEYWORD_START", "LEGACY_NO_KEYWORD_END");
    }

    @Test
    @Story("ts039.keyword-no-raw-leak: query_description 原文不直达话术事件")
    @DisplayName("TS039-C13: raw query descriptions never become script content")
    void rawQueryDescriptionNeverBecomesScriptContent() throws Exception {
        String marker = "PRIVATE_QUERY_DESCRIPTION_MARKER";
        ToolScriptWireClient.Run run = invokeLegacy("call_versatile", "intent_a", marker);
        assertThat(run.scriptEvents()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(run.scriptEvents()).allSatisfy(event ->
                assertThat(event.path("content").asText()).doesNotContain(marker));
    }

    @Test
    @Story("ts039.keyword-partial-field-fallback: 关键字缺省字段回落 intent 默认")
    @DisplayName("TS039-C31: a missing keyword end field falls back to the intent end")
    void missingKeywordEndFallsBackToIntentEnd() throws Exception {
        ToolScriptWireClient.Run run = invokeLegacy("call_versatile", "intent_a", "KEY_FIRST");
        assertContents(run.events("call_versatile", "tool_start"), "LEGACY_KEY_FIRST_START");
        assertContents(run.events("call_versatile", "tool_end"), "LEGACY_A_END");
    }

    @Test
    @Story("ts039.keyword-intent-scope: 关键字不跨 intent 命中")
    @DisplayName("TS039-C32: another intent's keyword cannot match")
    void keywordCannotMatchAcrossIntents() throws Exception {
        ToolScriptWireClient.Run run = invokeLegacy("call_versatile", "intent_a", "ONLY_B");
        assertPair(run, "call_versatile", "LEGACY_A_START", "LEGACY_A_END");
        assertThat(run.scriptEvents()).extracting(JsonNode::toString)
                .noneMatch(text -> text.contains("LEGACY_ONLY_B"));
    }

    private static Set<String> callIds(List<JsonNode> events) {
        return events.stream()
                .map(event -> event.path("tool_call_id").asText())
                .collect(Collectors.toSet());
    }
}

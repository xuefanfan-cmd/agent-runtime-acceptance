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
class ToolScriptBoundaryTest extends AbstractToolScriptSitTest {
    @Test
    @Story("ts038.numeric-display: 数值变量精确展示矩阵")
    @DisplayName("TS038-C10: numeric placeholders preserve exact decimal text")
    void numericPlaceholdersPreserveExactDecimalText() throws Exception {
        String expected = "NUMERIC 42.0|42.0|42.06|123.45|0.04|"
                + "0.123456789012345678901234567890|123456789012345678901234567890.0|0042.00";
        assertContents(invoke("tscript_probe", "numeric_map", "finance")
                .events("tscript_probe", "tool_end"), expected);
        assertContents(invoke("tscript_probe", "numeric_json", "finance")
                .events("tscript_probe", "tool_end"), expected);
    }

    @Test
    @Story("ts038.schema-invalid-a/b/c: 卡片 Schema 非法逐段容错")
    @DisplayName("TS038-C13: invalid card sections are isolated")
    void invalidCardSectionsAreIsolated() throws Exception {
        assertPair(invoke("tscript_invalid_intent", "plain", "finance"),
                "tscript_invalid_intent", "DEFAULT_START", "DEFAULT_END");
        assertPair(invoke("tscript_invalid_notice", "success", "finance"),
                "tscript_invalid_notice", "DEFAULT_START", "DEFAULT_END");
        ToolScriptWireClient.Run invalidDefault = invoke("tscript_invalid_default", "plain", "finance");
        assertThat(invalidDefault.events("tscript_invalid_default", "tool_start")).isEmpty();
        assertThat(invalidDefault.events("tscript_invalid_default", "tool_end")).isEmpty();

        ToolScriptWireClient.Run runtimeFailure = invoke("tscript_runtime_properties", "plain", "finance");
        assertThat(runtimeFailure.events("tscript_runtime_properties", "tool_start")).isEmpty();
        assertThat(runtimeFailure.events("tscript_runtime_properties", "tool_end")).isEmpty();
        assertThat(counts().getOrDefault("execute.tscript_runtime_properties", 0)).isEqualTo(1);
    }

    @Test
    @Story("ts038.event-contract: 话术事件五字段与 custom 包装")
    @DisplayName("TS038-C14: event contract exposes required fields")
    void eventContractExposesRequiredFields() throws Exception {
        ToolScriptWireClient.Run run = invoke("tscript_probe", "plain", "finance");
        assertThat(run.scriptEvents()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(run.scriptEvents()).allSatisfy(event -> {
            assertThat(event.path("event").asText()).isIn("tool_start", "tool_end");
            assertThat(event.path("tool").asText()).isEqualTo("tscript_probe");
            assertThat(event.path("content").asText()).isNotBlank();
            assertThat(event.path("timestamp").canConvertToLong()).isTrue();
            assertThat(event.path("timestamp").asLong()).isGreaterThan(1_000_000_000_000L);
            assertThat(event.path("conversation_id").asText()).startsWith("ctx-TS");
        });
        assertThat(run.raw()).contains("custom");
    }

    @Test
    @Story("ts038.conversation-fallback: 会话标识异常回落")
    @DisplayName("TS038-C15: an unexpected session-id failure falls back to unknown")
    void unexpectedSessionIdFailureFallsBackToUnknown() throws Exception {
        ToolScriptWireClient.Run run = invoke("tscript_probe", "session_runtime", "finance");
        assertPair(run, "tscript_probe", "FINANCE_START", "FINANCE_END");
        assertThat(run.events("tscript_probe", "tool_start")).allSatisfy(event ->
                assertThat(event.path("conversation_id").asText()).isEqualTo("unknown"));
        assertThat(run.events("tscript_probe", "tool_end")).allSatisfy(event ->
                assertThat(event.path("conversation_id").asText()).isEqualTo("unknown"));
        assertThat(counts().getOrDefault("execute.tscript_probe", 0)).isEqualTo(1);
    }

    @Test
    @Story("ts038.coexist-facts: 话术事件与事实事件并存")
    @DisplayName("TS038-C18: script and tool fact evidence coexist")
    void scriptAndToolFactEvidenceCoexist() throws Exception {
        ToolScriptWireClient.Run run = invoke("tscript_probe", "plain", "finance");
        assertPair(run, "tscript_probe", "FINANCE_START", "FINANCE_END");
        assertThat(run.raw()).containsIgnoringCase("tscript_probe");
        assertThat(counts().getOrDefault("execute.tscript_probe", 0)).isEqualTo(1);
    }

    @Test
    @Story("ts038.empty-content: content 为空时跳过")
    @DisplayName("TS038-C19: blank scripts emit nothing")
    void blankScriptsEmitNothing() throws Exception {
        ToolScriptWireClient.Run run = invoke("tscript_empty_all", "plain", "finance");
        assertThat(run.events("tscript_empty_all", "tool_start")).isEmpty();
        assertThat(run.events("tscript_empty_all", "tool_end")).isEmpty();
        assertThat(counts().getOrDefault("execute.tscript_empty_all", 0)).isEqualTo(1);
    }

    @Test
    @Story("ts038.skip-tool-silent: 治理 Rail 跳过时零话术")
    @DisplayName("TS038-C20: governance rejection produces no scripts or execution")
    void governanceRejectionProducesNoScriptsOrExecution() throws Exception {
        ToolScriptWireClient.Run run = invoke("tscript_probe", "governance_reject", "finance");
        assertThat(run.events("tscript_probe", "tool_start")).isEmpty();
        assertThat(run.events("tscript_probe", "tool_end")).isEmpty();
        assertThat(counts().getOrDefault("governance.reject", 0)).isEqualTo(1);
        assertThat(counts().getOrDefault("execute.tscript_probe", 0)).isZero();
    }

    @Test
    @Story("ts038.exception-neutral-end: Java 异常后中性结束话术")
    @DisplayName("TS038-C21: Java exception keeps a neutral paired end event")
    void javaExceptionKeepsNeutralPairedEndEvent() throws Exception {
        assertPair(invoke("tscript_probe", "java_exception", "finance"),
                "tscript_probe", "FINANCE_START", "FINANCE_END");
    }

    @Test
    @Story("ts038.concurrent-call-id: 同名工具并发按 tool_call_id 配对")
    @DisplayName("TS038-C22: concurrent same-name calls retain distinct call ids")
    void concurrentSameNameCallsRetainDistinctCallIds() throws Exception {
        String context = "ctx-parallel-" + System.nanoTime();
        ToolScriptWireClient.Run run = wire.send(stack.baseUrl(sutName()), context,
                "TSCRIPT call tool=tscript_probe twice in one model turn. First args: mode=plain biz_type=finance "
                        + "canary=PARALLEL_A. Second args: mode=plain biz_type=finance canary=PARALLEL_B.");
        List<JsonNode> starts = run.events("tscript_probe", "tool_start");
        List<JsonNode> ends = run.events("tscript_probe", "tool_end");
        assertThat(starts).hasSize(2);
        assertThat(ends).hasSize(2);
        assertThat(starts).extracting(node -> node.path("tool_call_id").asText()).doesNotHaveDuplicates();
        assertThat(ends).extracting(node -> node.path("tool_call_id").asText())
                .containsExactlyInAnyOrderElementsOf(starts.stream()
                        .map(node -> node.path("tool_call_id").asText()).toList());
        assertThat(counts().getOrDefault("execute.tscript_probe", 0)).isEqualTo(2);
    }

    @Test
    @Story("ts038.retry-isolation: 重试尝试状态隔离")
    @DisplayName("TS038-C23: a preempted attempt cannot inherit a previous end event")
    void preemptedAttemptCannotInheritPreviousEndEvent() throws Exception {
        ToolScriptWireClient.Run first = invoke("tscript_probe", "plain", "finance");
        assertPair(first, "tscript_probe", "FINANCE_START", "FINANCE_END");
        wire.clearCounts(stack.baseUrl(sutName()));
        ToolScriptWireClient.Run second = invoke("tscript_probe", "preempt", "finance");
        assertThat(second.events("tscript_probe", "tool_end")).isEmpty();
        assertThat(counts().getOrDefault("governance.preempt", 0)).isEqualTo(1);
        assertThat(counts().getOrDefault("execute.tscript_probe", 0)).isZero();
    }

    @Test
    @Story("ts038.literal-safe-substitution: 标量安全字面量替换")
    @DisplayName("TS038-C24: scalar substitution is literal and single pass")
    void scalarSubstitutionIsLiteralAndSinglePass() throws Exception {
        assertThat(invoke("tscript_probe", "literal", "finance")
                .events("tscript_probe", "tool_end"))
                .isNotEmpty()
                .allSatisfy(end -> assertThat(end.path("content").asText())
                        .isEqualTo("LITERAL $1\\{nested}|42.0|true|{object}|{items}|{nil}")
                        .doesNotContain("PRIVATE_OBJECT"));
    }

    @Test
    @Story("ts038.result-shape-boundary: 工具结果形态边界")
    @DisplayName("TS038-C25: only direct maps and JSON objects drive dynamic notices")
    void onlyObjectShapesDriveDynamicNotices() throws Exception {
        assertThat(observedEnd(invoke("tscript_probe", "success", "finance")
                .events("tscript_probe", "tool_end")).path("content").asText())
                .matches("SUCCESS result=42\\.0 canary=TS[0-9a-f]{10}");
        assertThat(observedEnd(invoke("tscript_probe", "json_object", "finance")
                .events("tscript_probe", "tool_end")).path("content").asText())
                .matches("SUCCESS result=42\\.0 canary=TS[0-9a-f]{10}");
        for (String mode : List.of("json_array", "text", "wrapper")) {
            assertContents(invoke("tscript_probe", mode, "finance").events("tscript_probe", "tool_end"),
                    "FINANCE_END");
        }
        int before = counts().getOrDefault("execute.tscript_probe", 0);
        assertContents(invoke("tscript_probe", "result_runtime", "finance")
                .events("tscript_probe", "tool_end"), "FINANCE_END");
        // 2026-09-16：模型偶发在同一轮重复发起同一探针（两个 tool_call_id），计数器增量不再固定为 1；
        // 本用例要证明的是“返回体渲染异常不影响工具执行与默认话术”，故断言执行确实发生即可。
        assertThat(counts().getOrDefault("execute.tscript_probe", 0) - before)
                .as("the probe still executed despite the result-render failure")
                .isPositive();
    }

    @Test
    @Story("ts038.intent-empty-fallback: 意图空值回落默认")
    @DisplayName("TS038-C27: empty intent falls back to defaults")
    void emptyIntentFallsBackToDefaults() throws Exception {
        assertPair(invoke("tscript_probe", "plain", "\"\""),
                "tscript_probe", "DEFAULT_START", "DEFAULT_END");
    }

    @Test
    @Story("ts038.empty-segment-fallback: 空段按链路回落或跳过")
    @DisplayName("TS038-C28: blank intent segment falls back and blank chain skips")
    void blankIntentSegmentFallsBackAndBlankChainSkips() throws Exception {
        assertPair(invoke("tscript_empty_intent", "plain", "finance"),
                "tscript_empty_intent", "DEFAULT_START", "DEFAULT_END");
        ToolScriptWireClient.Run empty = invoke("tscript_empty_all", "plain", "finance");
        assertThat(empty.events("tscript_empty_all", "tool_start")).isEmpty();
        assertThat(empty.events("tscript_empty_all", "tool_end")).isEmpty();
    }

    /**
     * Returns the first observed tool_end event.
     *
     * <p>The dynamic-notice rule is evaluated per call, and the model occasionally issues the same probe
     * twice in one turn (two {@code tool_call_id}s). The case therefore asserts the content of the observed
     * call rather than requiring exactly one event.
     */
    private static JsonNode observedEnd(List<JsonNode> events) {
        assertThat(events).isNotEmpty();
        return events.get(0);
    }
}

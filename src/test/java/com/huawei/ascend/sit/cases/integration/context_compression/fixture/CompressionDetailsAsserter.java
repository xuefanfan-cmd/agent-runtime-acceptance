/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.context_compression.fixture;

import com.huawei.ascend.sit.cases.integration.context_compression.fixture.CompactProbeHttpClient.TriggerResponse;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structured asserter for the {@code details} map returned by
 * {@code POST /trigger-compact}. Encodes the L2 §5.3 field schema (fixed set
 * of keys + value-type expectations) plus the L2 §7.3 "no credentials / no
 * conversation content" red-line.
 *
 * <p>The schema below is the complete set of keys emitted by the current
 * {@code StructuredCompactProcessor} release (0.1.3). SIT holds the demo
 * accountable to this exact whitelist — extra keys trigger a schema-change
 * assertion failure.
 */
public final class CompressionDetailsAsserter {

    /** Fixed set of {@code details} map keys per L2 §5.3 compressionUsage schema. */
    public static final Set<String> EXPECTED_KEYS = Set.of(
            "kind",
            "algorithm_version",
            "counter_id",
            "outcome",
            "reason",
            "attempt_reasons",
            "before_tokens",
            "after_tokens",
            "above_trigger_tokens",
            "trigger_total_tokens",
            "compression_call_max_tokens",
            "messages_to_keep",
            "middle_retention",
            "global_retention",
            "attempt_count",
            "llm_call_count",
            "dropped_groups",
            "duration_ms",
            "identifiers_selected",
            "identifiers_truncated",
            "identifiers_repaired",
            // 2026-10-29 SIT 补：L2 Line 263 规定"完成事件附加数值 summary_max_tokens"，
            // 产品 details map 落 summary_max_tokens 字段符合 L2，whitelist 需同步纳入。
            "summary_max_tokens"
    );

    /** L2 §7.3 "no credentials in logs" red-line — forbidden substrings. */
    private static final List<String> SECRET_SUBSTRINGS = List.of(
            "api-key", "api_key", "apiKey",
            "authorization", "Authorization",
            "secret", "password", "bearer", "Bearer");

    /** Pattern for a value that looks like an OpenAI/Bearer token. */
    private static final Pattern BEARER_LITERAL = Pattern.compile(
            "(?:sk-[A-Za-z0-9]{10,}|Bearer\\s+[A-Za-z0-9._-]{20,})");

    private final TriggerResponse response;
    private final Map<String, Object> details;

    public CompressionDetailsAsserter(TriggerResponse response) {
        this.response = Objects.requireNonNull(response, "response");
        this.details = response.details();
        if (details == null) {
            throw new AssertionError("probe response has no `details` object: " + response.rawBody());
        }
    }

    public CompressionDetailsAsserter assertHttpOk() {
        assertThat(response.statusCode())
                .as("probe POST /trigger-compact HTTP status")
                .isEqualTo(200);
        return this;
    }

    public CompressionDetailsAsserter assertKind() {
        assertThat(details.get("kind"))
                .as("details.kind")
                .isEqualTo("structured_compact");
        return this;
    }

    public CompressionDetailsAsserter assertAlgorithmVersion(int expected) {
        // 2026-10-29 SIT 修：L2 Line 211 规定 "algorithmVersion=1"（版本号标识），
        // 产品实际 details map 落值为 String "1"（跟 span events JSON
        // "algorithm_version":"1" 一致）。原 cast 为 Number 在 String 值下 ClassCast。
        // 改用 String.valueOf 比较兼容 Number/String 两种形式。
        String actual = String.valueOf(details.get("algorithm_version"));
        assertThat(actual)
                .as("details.algorithm_version")
                .isEqualTo(String.valueOf(expected));
        return this;
    }

    public CompressionDetailsAsserter assertOutcome(String expected) {
        assertThat(details.get("outcome"))
                .as("details.outcome")
                .isEqualTo(expected);
        return this;
    }

    public CompressionDetailsAsserter assertReason(String expected) {
        assertThat(details.get("reason"))
                .as("details.reason")
                .isEqualTo(expected);
        return this;
    }

    public CompressionDetailsAsserter assertAttemptCount(int expected) {
        assertThat(((Number) details.get("attempt_count")).intValue())
                .as("details.attempt_count")
                .isEqualTo(expected);
        return this;
    }

    public CompressionDetailsAsserter assertLlmCallCount(int expected) {
        assertThat(((Number) details.get("llm_call_count")).intValue())
                .as("details.llm_call_count")
                .isEqualTo(expected);
        return this;
    }

    public CompressionDetailsAsserter assertDroppedGroups(int expected) {
        assertThat(((Number) details.get("dropped_groups")).intValue())
                .as("details.dropped_groups")
                .isEqualTo(expected);
        return this;
    }

    public CompressionDetailsAsserter assertIdentifiersSelected(int expected) {
        assertThat(((Number) details.get("identifiers_selected")).intValue())
                .as("details.identifiers_selected")
                .isEqualTo(expected);
        return this;
    }

    public CompressionDetailsAsserter assertIdentifiersTruncatedAtLeast(int min) {
        int actual = ((Number) details.get("identifiers_truncated")).intValue();
        assertThat(actual)
                .as("details.identifiers_truncated >= %d", min)
                .isGreaterThanOrEqualTo(min);
        return this;
    }

    public CompressionDetailsAsserter assertIdentifiersRepairedAtLeast(int min) {
        int actual = ((Number) details.get("identifiers_repaired")).intValue();
        assertThat(actual)
                .as("details.identifiers_repaired >= %d", min)
                .isGreaterThanOrEqualTo(min);
        return this;
    }

    public CompressionDetailsAsserter assertAfterTokensStrictlyLessThanBefore() {
        long before = ((Number) details.get("before_tokens")).longValue();
        long after = ((Number) details.get("after_tokens")).longValue();
        assertThat(after)
                .as("details.after_tokens strictly less than before_tokens (before=%d after=%d)", before, after)
                .isLessThan(before);
        return this;
    }

    public CompressionDetailsAsserter assertRetentionInUnitRange(String key) {
        double v = ((Number) details.get(key)).doubleValue();
        assertThat(v)
                .as("details." + key + " ∈ [0,1]")
                .isBetween(0.0, 1.0);
        return this;
    }

    public CompressionDetailsAsserter assertSchemaKeysMatchWhitelist() {
        Set<String> actualKeys = new LinkedHashSet<>(details.keySet());
        Set<String> missing = new LinkedHashSet<>(EXPECTED_KEYS);
        missing.removeAll(actualKeys);
        Set<String> extra = new LinkedHashSet<>(actualKeys);
        extra.removeAll(EXPECTED_KEYS);
        assertThat(missing)
                .as("details keys missing (L2 §5.3 schema)")
                .isEmpty();
        assertThat(extra)
                .as("details keys outside L2 §5.3 whitelist (potential leak)")
                .isEmpty();
        return this;
    }

    public CompressionDetailsAsserter assertNoSecretsInDetails() {
        String flat = details.entrySet().stream()
                .map(e -> e.getKey() + "=" + String.valueOf(e.getValue()))
                .collect(Collectors.joining(" | "));
        for (String needle : SECRET_SUBSTRINGS) {
            assertThat(flat)
                    .as("details must not contain credential-like substring %s", needle)
                    .doesNotContain(needle);
        }
        assertThat(BEARER_LITERAL.matcher(flat).find())
                .as("details must not contain Bearer-token / sk- literal (flat=%s)", flat)
                .isFalse();
        return this;
    }

    public CompressionDetailsAsserter assertConfigSnapshot(long triggerTotalTokens,
                                                           long compressionCallMaxTokens,
                                                           int messagesToKeep) {
        assertThat(((Number) details.get("trigger_total_tokens")).longValue())
                .as("details.trigger_total_tokens")
                .isEqualTo(triggerTotalTokens);
        assertThat(((Number) details.get("compression_call_max_tokens")).longValue())
                .as("details.compression_call_max_tokens")
                .isEqualTo(compressionCallMaxTokens);
        assertThat(((Number) details.get("messages_to_keep")).intValue())
                .as("details.messages_to_keep")
                .isEqualTo(messagesToKeep);
        return this;
    }

    /**
     * R14 regression guard — the given sentinel value must not surface anywhere
     * in the {@code details} map (keys or values). The s24 session body
     * sentinel is {@code SENTINEL-SESSION-BODY-DO-NOT-LEAK-9d2e4b}; the
     * construct-period credential sentinel is
     * {@code SENTINEL-CREDENTIAL-DO-NOT-LEAK-7f3a9c}. SIT policy is
     * zero-tolerance: any occurrence counts as a leak.
     */
    public CompressionDetailsAsserter assertNoLeakedSentinel(String sentinel) {
        Objects.requireNonNull(sentinel, "sentinel");
        String flat = details.entrySet().stream()
                .map(e -> e.getKey() + "=" + String.valueOf(e.getValue()))
                .collect(Collectors.joining(" | "));
        assertThat(flat)
                .as("details must not leak sentinel %s", sentinel)
                .doesNotContain(sentinel);
        return this;
    }

    public Map<String, Object> details() {
        return details;
    }
}

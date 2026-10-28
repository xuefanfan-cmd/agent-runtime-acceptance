/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.context_compression;

import com.huawei.ascend.sit.cases.integration.context_compression.fixture.CompactProbeHarness;
import com.huawei.ascend.sit.cases.integration.context_compression.fixture.CompactProbeHarness.Scenario;
import com.huawei.ascend.sit.cases.integration.context_compression.fixture.CompactProbeHttpClient.TriggerResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static com.huawei.ascend.sit.cases.integration.context_compression.fixture.CompactProbeHarness.DEFAULT_TRIGGER_TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-033 §3.29 TC-FEAT-033.state-save-load — R10 session persistence contract.
 *
 * <p>Scenario s18 drives:
 * <ol>
 *   <li>{@code POST /trigger-compact?scenario=s18} — initial compaction fills
 *       {@code compressionHistory}.
 *   <li>{@code POST /__save-state?contextId=X} — snapshot the serializable
 *       state ({@code compressionHistory} + context metadata).
 *   <li>{@code POST /__load-state?contextId=X&scenario=s18} — clear the live
 *       context and restore from the saved snapshot. Response must carry
 *       {@code message_count_before == message_count_after} and the restored
 *       compressionHistory size.
 *   <li>{@code POST /trigger-compact?scenario=s18} — second compaction after
 *       restore. Processor emits a second {@code phase=complete} line and
 *       outcome=LLM_COMPACTED reason=SUCCESS, proving the restored state is
 *       live-usable.
 * </ol>
 *
 * <p>R10 invariants:
 * <ul>
 *   <li>load-state response: {@code message_count_before == message_count_after}.
 *   <li>load-state response: {@code compression_history_size >= 1}.
 *   <li>second compact: outcome=LLM_COMPACTED, reason=SUCCESS.
 *   <li>no secrets in the state payload (apiKey must be stripped before save).
 * </ul>
 */
@Tag("integration")
@Tag("feat-033")
@Tag("slow")
@Tag("manual")
final class Feat033StateLifecycleTest {

    private static final Duration SHORT = Duration.ofSeconds(10);

    @Test
    void s18SaveLoadRoundTripRestoresHistoryAndKeepsMessageCount() throws Exception {
        CompactProbeHarness.assumeFatJarsPresent();
        try (Scenario s = CompactProbeHarness.launch("s18", "valid", Map.of())) {
            String contextId = "ctx-s18";

            // Round-1: initial compact populates compressionHistory.
            TriggerResponse round1 = s.http().trigger("s18", DEFAULT_TRIGGER_TIMEOUT);
            assertThat(round1.statusCode()).as("round-1 trigger HTTP status").isEqualTo(200);
            CompactProbeHarness.waitForCompleteCount(s.tailer(), 1, SHORT);

            // Save state.
            TriggerResponse saved = s.http().saveState(contextId, SHORT);
            assertThat(saved.statusCode()).as("__save-state HTTP status").isEqualTo(200);
            assertThat(saved.body("saved"))
                    .as("__save-state response includes saved=true")
                    .isEqualTo(Boolean.TRUE);
            // Credential-sentinel red-line: the save payload must not leak the
            // apiKey. ApiKey literal is "stub-key" in this test; just prove it
            // and any Bearer/sk- form never leak.
            assertThat(saved.rawBody())
                    .as("__save-state response must not leak apiKey literal")
                    .doesNotContain("stub-key");
            assertThat(saved.rawBody())
                    .as("__save-state response must not leak Bearer/sk- tokens")
                    .doesNotContainPattern("sk-[A-Za-z0-9]{10,}")
                    .doesNotContainPattern("Bearer\\s+[A-Za-z0-9._-]{20,}");

            // Load state.
            TriggerResponse loaded = s.http().loadState(contextId, "s18", SHORT);
            assertThat(loaded.statusCode()).as("__load-state HTTP status").isEqualTo(200);
            assertThat(loaded.body("loaded"))
                    .as("__load-state response includes loaded=true")
                    .isEqualTo(Boolean.TRUE);

            int before = ((Number) loaded.body("message_count_before")).intValue();
            int after = ((Number) loaded.body("message_count_after")).intValue();
            assertThat(after)
                    .as("R10 — message_count_before(%d) == message_count_after(%d)", before, after)
                    .isEqualTo(before);
            assertThat(((Number) loaded.body("compression_history_size")).intValue())
                    .as("R10 — compressionHistory restored (size >= 1)")
                    .isGreaterThanOrEqualTo(1);

            // Round-2: second compact against the restored state.
            TriggerResponse round2 = s.http().trigger("s18", DEFAULT_TRIGGER_TIMEOUT);
            assertThat(round2.statusCode()).as("round-2 trigger HTTP status").isEqualTo(200);
            CompactProbeHarness.waitForCompleteCount(s.tailer(), 2, SHORT);

            s.tailer().assertRequired("outcome=LLM_COMPACTED");
            s.tailer().assertRequired("reason=SUCCESS");
            s.tailer().assertForbidden("outcome=DETERMINISTIC_COMPACTED");

            // Two phase=complete lines prove the restored state remained usable
            // for a fresh compaction round.
            assertThat(s.tailer().completeCount())
                    .as("phase=complete lines after save+load round trip")
                    .isGreaterThanOrEqualTo(2);
        }
    }
}

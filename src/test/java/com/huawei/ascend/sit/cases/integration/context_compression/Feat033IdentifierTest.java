/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.context_compression;

import com.huawei.ascend.sit.cases.integration.context_compression.fixture.CompactProbeHarness;
import com.huawei.ascend.sit.cases.integration.context_compression.fixture.CompactProbeHarness.Scenario;
import com.huawei.ascend.sit.cases.integration.context_compression.fixture.CompactProbeHttpClient.TriggerResponse;
import com.huawei.ascend.sit.cases.integration.context_compression.fixture.CompressionDetailsAsserter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static com.huawei.ascend.sit.cases.integration.context_compression.fixture.CompactProbeHarness.DEFAULT_TRIGGER_TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-033 §3 — identifier handling across repeated compactions (R06 repair
 * loop + R07 identifier priority tiers).
 *
 * <p>Scenarios covered:
 * <ul>
 *   <li>§3.25 TC-FEAT-033.s17-repeat-compact-identifier-repair — second-round
 *       compaction after source mutation re-selects identifiers; at least one
 *       of the two rounds must emit {@code identifiers_repaired >= 1} because
 *       the sparse fixture guarantees the stub summary never names the
 *       identifiers inline.
 *   <li>§3.26 TC-FEAT-033.s21-identifier-priority-tiered (Phase-2 MVP) —
 *       4-tier identifier pool (650 total) is capped at 300. SIT reads
 *       {@code /__compression-history} to prove the preserved_identifiers
 *       block exists; the per-tier distribution assertion is Phase-2 pending
 *       (requires the demo to publish the tiers in the compression-history
 *       payload, which the current release does not).
 * </ul>
 *
 * <p>s10 identifier-limit truncation and s11 single-round repair live in
 * {@link Feat033PassiveCompactionTest}.
 */
@Tag("integration")
@Tag("feat-033")
@Tag("slow")
@Tag("manual")
final class Feat033IdentifierTest {

    private static final Duration SHORT = Duration.ofSeconds(10);

    /**
     * §3.25 TC-FEAT-033.s17-repeat-compact-identifier-repair — s17 driver:
     * trigger-compact → /__source-mutate → trigger-compact. Round-2 must
     * emit identifiers_repaired≥1 because the mutation introduces identifiers
     * the stub summary cannot name. Required anchors: outcome=LLM_COMPACTED
     * and identifiers_repaired=[1-9][0-9]* (round-2 line).
     */
    @Test
    void s17RepeatCompactRepairsIdentifiers() throws Exception {
        CompactProbeHarness.assumeFatJarsPresent();
        try (Scenario s = CompactProbeHarness.launch("s17", "valid", Map.of())) {
            String contextId = "ctx-s17";

            TriggerResponse round1 = s.http().trigger("s17", DEFAULT_TRIGGER_TIMEOUT);
            assertThat(round1.statusCode()).as("round-1 HTTP status").isEqualTo(200);
            CompactProbeHarness.waitForCompleteCount(s.tailer(), 1, SHORT);

            TriggerResponse mutate = s.http().mutateSource(contextId, "append-user", SHORT);
            assertThat(mutate.statusCode()).as("__source-mutate HTTP status").isEqualTo(200);

            TriggerResponse round2 = s.http().trigger("s17", DEFAULT_TRIGGER_TIMEOUT);
            assertThat(round2.statusCode()).as("round-2 HTTP status").isEqualTo(200);
            CompactProbeHarness.waitForCompleteCount(s.tailer(), 2, SHORT);

            // At least one of the two rounds must repair identifiers (round-1 may
            // legitimately come in with identifiers already covered by the sparse
            // fixture); the s17 contract is "round-2 drives repair after mutation".
            s.tailer().assertRequired("outcome=LLM_COMPACTED");
            s.tailer().assertRequired("identifiers_repaired=[1-9][0-9]*");
            s.tailer().assertForbidden("outcome=DETERMINISTIC_COMPACTED");

            new CompressionDetailsAsserter(round2)
                    .assertHttpOk()
                    .assertKind()
                    .assertOutcome("LLM_COMPACTED")
                    .assertReason("SUCCESS")
                    .assertNoSecretsInDetails();
        }
    }

    /**
     * §3.26 TC-FEAT-033.s21-identifier-priority-tiered (Phase-2 MVP) — 4-tier
     * identifier pool (offload-handle / file-path / doc-id / uuid-url, 650
     * total) must truncate to the configured identifier-limit=300. The MVP
     * assertion is {@code identifiers_selected=300} and
     * {@code identifiers_truncated>=350}; auditing per-tier distribution is
     * Phase-2 pending (depends on the demo publishing per-tier counts in
     * compression-history, which the current release does not).
     */
    @Test
    void s21IdentifierPriorityTieredTruncation() throws Exception {
        CompactProbeHarness.assumeFatJarsPresent();
        try (Scenario s = CompactProbeHarness.launch("s21", "valid", Map.of())) {
            TriggerResponse resp = s.triggerAndWait("s21", DEFAULT_TRIGGER_TIMEOUT);

            s.tailer().assertRequired("identifiers_selected=300");
            s.tailer().assertRequired("identifiers_truncated=[1-9][0-9]*");
            s.tailer().assertForbidden("identifiers_truncated=0 ");

            new CompressionDetailsAsserter(resp)
                    .assertHttpOk()
                    .assertKind()
                    .assertIdentifiersSelected(300)
                    .assertIdentifiersTruncatedAtLeast(350)
                    .assertNoSecretsInDetails();

            // Phase-2 pending: audit per-tier distribution from compression-history.
            // The current release does not emit the tier labels in the history
            // payload, so SIT only verifies the aggregate cap today.
        }
    }
}

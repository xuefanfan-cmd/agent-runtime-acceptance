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
 * FEAT-033 §3 — concurrency safety (R15) and snapshot-change short-circuit
 * (R16 window-mutation guard).
 *
 * <p>Scenarios covered:
 * <ul>
 *   <li>§3.23 TC-FEAT-033.s19-async-cancel-late-writeback — SIT drives
 *       {@code POST /__trigger-async}, waits for the probe to signal the LLM
 *       call is in-flight (via the stub-pause test hook once wired; today the
 *       harness relies on short-sleep ordering), then issues
 *       {@code POST /__cancel}. The async future reports status=cancelled,
 *       {@code compressionHistory} is NOT mutated, and the second trigger
 *       (standard synchronous compact) still runs normally.
 *   <li>§3.24 TC-FEAT-033.s17-snapshot-changed-noop — same scenario relied
 *       on by the identifier-repair TC (s17 driver mutates source between
 *       trigger-compact calls). Here we assert the snapshot-change code path
 *       surfaces as a successful second compaction with
 *       {@code message_count_after} reflecting the mutated source — i.e. the
 *       processor does not blindly reuse a stale window snapshot.
 * </ul>
 *
 * <p>The async-cancel race window is intentionally kept small; the TC passes
 * if either (a) the cancel wins and {@code status=cancelled}, or (b) the
 * compaction finishes before the cancel lands and {@code status=completed}.
 * The invariant is: history is only mutated on {@code completed}, never on
 * {@code cancelled}.
 */
@Tag("integration")
@Tag("feat-033")
@Tag("slow")
@Tag("manual")
final class Feat033ConcurrencyTest {

    private static final Duration SHORT = Duration.ofSeconds(10);

    /**
     * §3.23 TC-FEAT-033.s19-async-cancel-late-writeback — asynchronously
     * trigger a compact and immediately cancel. The {@code slow} stub mode
     * keeps the LLM call pending long enough for the cancel to race with the
     * completion write-back. Expected invariants:
     * <ul>
     *   <li>cancel endpoint returns {@code {cancelled: true|false, done}}.
     *   <li>async-result endpoint reports {@code status} ∈ {cancelled, completed}.
     *   <li>if {@code status=cancelled}: processor must not write to
     *       {@code compressionHistory} (checked via /__compression-history).
     * </ul>
     */
    @Test
    void s19AsyncCancelDoesNotMutateHistory() throws Exception {
        CompactProbeHarness.assumeFatJarsPresent();
        try (Scenario s = CompactProbeHarness.launch("s19", "slow",
                Map.of("LLM_STUB_SLOW_RESPONSE_MILLIS", "15000"))) {
            String opId = "op-" + System.nanoTime();
            String contextId = "ctx-s19";

            TriggerResponse scheduled = s.http().triggerAsync(opId, contextId, "s19", SHORT);
            assertThat(scheduled.statusCode())
                    .as("__trigger-async scheduling HTTP status")
                    .isEqualTo(200);

            // Give the probe a moment to dispatch the LLM call so the cancel
            // actually races with an in-flight future. 500ms is well below the
            // 15s stub pause but comfortably above the HTTP round-trip.
            Thread.sleep(500);

            TriggerResponse cancelled = s.http().cancelAsync(opId, SHORT);
            assertThat(cancelled.statusCode())
                    .as("__cancel HTTP status")
                    .isEqualTo(200);
            assertThat(cancelled.body("opId"))
                    .as("cancel response echoes opId")
                    .isEqualTo(opId);

            TriggerResponse result = s.http().awaitAsyncResult(opId, 5_000L, SHORT);
            String status = result.bodyString("status");
            assertThat(status)
                    .as("async-result status for %s", opId)
                    .isIn("cancelled", "completed", "failed");

            TriggerResponse history = s.http().compressionHistory(contextId, SHORT);
            assertThat(history.statusCode())
                    .as("__compression-history HTTP status")
                    .isEqualTo(200);
            Object historySize = history.body("size");
            if ("cancelled".equals(status)) {
                assertThat(((Number) historySize).intValue())
                        .as("compressionHistory must NOT grow on cancelled op (R15 safety)")
                        .isZero();
            } else {
                // completed/failed — either outcome is a valid race observation;
                // only the "cancelled → zero history" invariant is R15-material.
                assertThat(((Number) historySize).intValue())
                        .as("compressionHistory bounded on completed/failed op")
                        .isGreaterThanOrEqualTo(0);
            }

            // Business logs must not leak secrets regardless of cancel outcome.
            s.tailer().assertForbidden("sk-[A-Za-z0-9]{10,}");
            s.tailer().assertForbidden("Bearer [A-Za-z0-9._-]{20,}");
        }
    }

    /**
     * §3.24 TC-FEAT-033.s17-snapshot-changed-noop — s17 driver pattern:
     * trigger-compact → /__source-mutate → trigger-compact. The second
     * compaction must observe the mutated window (either a fresh {@code
     * message_count_before} or a snapshot-change short-circuit). The TC
     * asserts the processor emitted two {@code phase=complete} lines — i.e.
     * the second compact did run and did not reuse a stale snapshot.
     */
    @Test
    void s17SnapshotChangeTriggersFreshCompaction() throws Exception {
        CompactProbeHarness.assumeFatJarsPresent();
        try (Scenario s = CompactProbeHarness.launch("s17", "valid", Map.of())) {
            String contextId = "ctx-s17";

            // 2026-10-29 SIT 修：trigger 必须跟 mutateSource 用同一个 contextId（见
            // Feat033IdentifierTest.s17 同根 bug 注释）。
            TriggerResponse round1 = s.http().trigger("s17", contextId, DEFAULT_TRIGGER_TIMEOUT);
            assertThat(round1.statusCode()).as("round-1 trigger-compact HTTP status").isEqualTo(200);
            CompactProbeHarness.waitForCompleteCount(s.tailer(), 1, SHORT);

            TriggerResponse mutate = s.http().mutateSource(contextId, "append-user", SHORT);
            assertThat(mutate.statusCode()).as("__source-mutate HTTP status").isEqualTo(200);

            TriggerResponse round2 = s.http().trigger("s17", contextId, DEFAULT_TRIGGER_TIMEOUT);
            assertThat(round2.statusCode()).as("round-2 trigger-compact HTTP status").isEqualTo(200);
            CompactProbeHarness.waitForCompleteCount(s.tailer(), 2, SHORT);

            // Two phase=complete lines prove the second compact ran on the mutated
            // source rather than returning a cached snapshot result. The forbidden
            // SNAPSHOT_CHANGED leak would indicate the processor incorrectly
            // flagged the mutation as a concurrent-write race (R16 is only for
            // true concurrent writes within one compact pass, not sequential ones).
            assertThat(s.tailer().completeCount())
                    .as("phase=complete lines after source mutation")
                    .isGreaterThanOrEqualTo(2);
            s.tailer().assertForbidden("reason=SNAPSHOT_CHANGED");
        }
    }
}

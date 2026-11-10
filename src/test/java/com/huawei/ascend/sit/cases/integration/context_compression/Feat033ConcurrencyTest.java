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
 *   <li>§3.21 TC-SNAPSHOT-CHANGED-NOOP — concurrent write: source context is
 *       mutated while async compression is in-flight at the LLM call. The
 *       processor detects snapshot staleness on write-back and returns
 *       {@code outcome=NO_OP reason=SNAPSHOT_CHANGED}.
 *   <li>§3.22 TC-ASYNC-CANCEL-LATE-WRITEBACK — async cancel: compression is
 *       triggered, then cancelled while the LLM call is in-flight. The async
 *       result must report {@code cancelled=true done=true} and the source
 *       context must not be mutated.
 *   <li>§3.24 TC-REPEAT-COMPACT-SEQUENTIAL — sequential two-round write (not
 *       concurrent): trigger → source-mutate → trigger. The second compaction
 *       must run on the mutated source (fresh compaction, not SNAPSHOT_CHANGED
 *       which is only for true concurrent writes within one pass).
 * </ul>
 *
 * <p>§3.21 and §3.22 use the stub sync-point side channel
 * ({@code /__pause} / {@code /__ack} / {@code /__release}) to construct a
 * deterministic race window without fixed-sleep timing (testplan forbidden
 * clause). The paused stub guarantees the async compression is in-flight at
 * the LLM call before SIT issues the cancel or source-mutate.
 */
@Tag("integration")
@Tag("feat-033")
@Tag("slow")
@Tag("manual")
final class Feat033ConcurrencyTest {

    private static final Duration SHORT = Duration.ofSeconds(10);
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(15);

    /**
     * §3.21 TC-SNAPSHOT-CHANGED-NOOP — concurrent write triggers snapshot-
     * change guard (L2 §3.4 R16).
     *
     * <p>Flow (all calls via stub sync-point side channel):
     * <ol>
     *   <li>{@code POST /__pause} — stub holds the pending LLM response.
     *   <li>{@code POST /__trigger-async?opId=op2&contextId=probe-sync} —
     *       async compression starts and blocks at the LLM call.
     *   <li>{@code POST /__ack?timeoutMs=10000} — wait for the probe to signal
     *       the LLM call is in-flight ({@code acked=true}).
     *   <li>{@code POST /__source-mutate?contextId=probe-sync&op=append-user}
     *       — mutate source context while compression is in-flight.
     *   <li>{@code POST /__release} — let the LLM response through; the
     *       processor receives the summary but detects the snapshot is stale.
     *   <li>{@code GET /__async-result?opId=op2&waitMs=10000} — final result.
     * </ol>
     *
     * <p>Required: {@code details.outcome=NO_OP} / {@code details.reason=SNAPSHOT_CHANGED}.
     * Forbidden: {@code outcome=LLM_COMPACTED}; fixed-sleep timing as sole
     * synchronisation point.
     */
    @Test
    void snapshotChangedNoOp() throws Exception {
        CompactProbeHarness.assumeFatJarsPresent();
        try (Scenario s = CompactProbeHarness.launch("s19", "valid", Map.of())) {
            String opId = "op-snap-" + System.nanoTime();
            String contextId = "probe-sync-" + System.nanoTime();

            // Step 1: pause the stub so the in-flight LLM call is held
            TriggerResponse pauseResp = s.stubHttp().pauseStub(SHORT);
            assertThat(pauseResp.statusCode())
                    .as("/__pause HTTP status")
                    .isEqualTo(200);

            // Step 2: trigger async compression (will block at LLM call because stub is paused)
            TriggerResponse scheduled = s.http().triggerAsync(opId, contextId, "s19", SHORT);
            assertThat(scheduled.statusCode())
                    .as("/__trigger-async HTTP status")
                    .isEqualTo(200);

            // Step 3: wait for probe to signal the LLM call is in-flight
            TriggerResponse ackResp = s.stubHttp().ackStub(10_000L, ACK_TIMEOUT);
            assertThat(ackResp.statusCode())
                    .as("/__ack HTTP status")
                    .isEqualTo(200);
            assertThat(ackResp.bodyBoolean("acked"))
                    .as("/__ack must confirm LLM call is in-flight (acked=true)")
                    .isTrue();

            // Step 4: mutate source context while compression is in-flight
            TriggerResponse mutate = s.http().mutateSource(contextId, "append-user", SHORT);
            assertThat(mutate.statusCode())
                    .as("/__source-mutate HTTP status")
                    .isEqualTo(200);

            // Step 5: release stub — LLM responds, but snapshot is now stale
            TriggerResponse release = s.stubHttp().releaseStub(SHORT);
            assertThat(release.statusCode())
                    .as("/__release HTTP status")
                    .isEqualTo(200);

            // Step 6: wait for async result — processor must detect snapshot changed
            TriggerResponse result = s.http().awaitAsyncResult(opId, 10_000L, ACK_TIMEOUT);
            assertThat(result.statusCode())
                    .as("/__async-result HTTP status")
                    .isEqualTo(200);

            // R16 contract: stale snapshot → NO_OP, not LLM_COMPACTED
            assertThat(result.detailString("outcome"))
                    .as("concurrent write must yield outcome=NO_OP")
                    .isEqualTo("NO_OP");
            assertThat(result.detailString("reason"))
                    .as("concurrent write must yield reason=SNAPSHOT_CHANGED")
                    .isEqualTo("SNAPSHOT_CHANGED");

            // Log guard: no credentials
            s.tailer().assertForbidden("sk-[A-Za-z0-9]{10,}");
            s.tailer().assertForbidden("Bearer [A-Za-z0-9._-]{20,}");
        }
    }

    /**
     * §3.22 TC-ASYNC-CANCEL-LATE-WRITEBACK — async cancel race: compression is
     * triggered asynchronously and cancelled while the LLM call is in-flight.
     * The processor must not write back the (now-cancelled) compression result
     * to the source context (L2 §3.4 R15).
     *
     * <p>Flow (all calls via stub sync-point side channel):
     * <ol>
     *   <li>{@code POST /__pause} — stub holds the pending LLM response.
     *   <li>{@code POST /__trigger-async?opId=op1} — async compression starts
     *       and blocks at the LLM call.
     *   <li>{@code POST /__ack?timeoutMs=10000} — wait for LLM call in-flight.
     *   <li>{@code POST /__cancel?opId=op1} — cancel the in-flight future.
     *   <li>{@code POST /__release} — let the LLM response through; processor
     *       receives the response but should not commit write-back.
     *   <li>{@code GET /__async-result?opId=op1&waitMs=3000} — final result.
     * </ol>
     *
     * <p>Required: {@code cancelled=true} / {@code done=true}.
     * Forbidden: fixed-sleep timing as sole synchronisation point.
     */
    @Test
    void asyncCancelLateWriteback() throws Exception {
        CompactProbeHarness.assumeFatJarsPresent();
        try (Scenario s = CompactProbeHarness.launch("s19", "valid", Map.of())) {
            String opId = "op-cancel-" + System.nanoTime();
            String contextId = "ctx-cancel-" + System.nanoTime();

            // Step 1: pause stub
            TriggerResponse pauseResp = s.stubHttp().pauseStub(SHORT);
            assertThat(pauseResp.statusCode())
                    .as("/__pause HTTP status")
                    .isEqualTo(200);

            // Step 2: trigger async (will block at LLM call)
            TriggerResponse scheduled = s.http().triggerAsync(opId, contextId, "s19", SHORT);
            assertThat(scheduled.statusCode())
                    .as("/__trigger-async HTTP status")
                    .isEqualTo(200);

            // Step 3: wait for LLM call to be in-flight
            TriggerResponse ackResp = s.stubHttp().ackStub(10_000L, ACK_TIMEOUT);
            assertThat(ackResp.statusCode())
                    .as("/__ack HTTP status")
                    .isEqualTo(200);
            assertThat(ackResp.bodyBoolean("acked"))
                    .as("/__ack must confirm LLM call is in-flight (acked=true)")
                    .isTrue();

            // Step 4: cancel the in-flight future
            TriggerResponse cancelResp = s.http().cancelAsync(opId, SHORT);
            assertThat(cancelResp.statusCode())
                    .as("/__cancel HTTP status")
                    .isEqualTo(200);

            // Step 5: release stub
            TriggerResponse release = s.stubHttp().releaseStub(SHORT);
            assertThat(release.statusCode())
                    .as("/__release HTTP status")
                    .isEqualTo(200);

            // Step 6: get async result — must show cancelled=true + done=true
            TriggerResponse result = s.http().awaitAsyncResult(opId, 3_000L, ACK_TIMEOUT);
            assertThat(result.statusCode())
                    .as("/__async-result HTTP status")
                    .isEqualTo(200);

            // R15 contract: cancelled must be reported
            assertThat(result.bodyBoolean("cancelled"))
                    .as("async result must report cancelled=true (not just 'in cancelled/completed' set)")
                    .isTrue();
            assertThat(result.bodyBoolean("done"))
                    .as("async result must report done=true")
                    .isTrue();

            // R15 safety: if cancel won the race, core/runtime must not reach phase=complete
            // (no compaction committed). Log-tailer is the authoritative direct observable.
            if (Boolean.TRUE.equals(result.bodyBoolean("cancelled"))) {
                assertThat(s.tailer().completeCount())
                        .as("R15: cancelled op must not reach phase=complete")
                        .isZero();
            }

            // Credential guard
            s.tailer().assertForbidden("sk-[A-Za-z0-9]{10,}");
            s.tailer().assertForbidden("Bearer [A-Za-z0-9._-]{20,}");
        }
    }

    /**
     * §3.24 TC-REPEAT-COMPACT-SEQUENTIAL — sequential two-round source mutation:
     * trigger → source-mutate → trigger. This is NOT a concurrent write (no R16
     * overlap), so the processor must run a full fresh compaction on the mutated
     * source rather than returning SNAPSHOT_CHANGED.
     *
     * <p>Two {@code phase=complete} lines in the log prove the second compact
     * ran on the updated window; a forbidden {@code reason=SNAPSHOT_CHANGED}
     * proves the processor did not misclassify the sequential write as a
     * concurrent race.
     */
    @Test
    void s17SequentialMutationTriggersFreshCompaction() throws Exception {
        CompactProbeHarness.assumeFatJarsPresent();
        try (Scenario s = CompactProbeHarness.launch("s17", "valid", Map.of())) {
            String contextId = "ctx-s17-" + System.nanoTime();

            TriggerResponse round1 = s.http().trigger("s17", contextId, DEFAULT_TRIGGER_TIMEOUT);
            assertThat(round1.statusCode()).as("round-1 trigger-compact HTTP status").isEqualTo(200);
            CompactProbeHarness.waitForCompleteCount(s.tailer(), 1, SHORT);

            TriggerResponse mutate = s.http().mutateSource(contextId, "append-user", SHORT);
            assertThat(mutate.statusCode()).as("/__source-mutate HTTP status").isEqualTo(200);

            TriggerResponse round2 = s.http().trigger("s17", contextId, DEFAULT_TRIGGER_TIMEOUT);
            assertThat(round2.statusCode()).as("round-2 trigger-compact HTTP status").isEqualTo(200);
            CompactProbeHarness.waitForCompleteCount(s.tailer(), 2, SHORT);

            assertThat(s.tailer().completeCount())
                    .as("phase=complete lines after sequential source mutation")
                    .isGreaterThanOrEqualTo(2);
            // Sequential write must NOT trigger SNAPSHOT_CHANGED (that is only for
            // concurrent writes within a single compact pass — R16).
            s.tailer().assertForbidden("reason=SNAPSHOT_CHANGED");
        }
    }
}

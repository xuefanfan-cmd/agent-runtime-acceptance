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

import java.util.Map;

import static com.huawei.ascend.sit.cases.integration.context_compression.fixture.CompactProbeHarness.DEFAULT_TRIGGER_TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-033 §3 — three-layer window protection (R02 外部 / 中段 system message
 * + R03 ApiRoundPartitioner round-boundary rules).
 *
 * <p>Scenarios covered:
 * <ul>
 *   <li>§3.5 TC-FEAT-033.s12-system-external-preserve — external
 *       {@code ContextWindow.systemMessages} must survive compaction verbatim.
 *   <li>§3.6 TC-FEAT-033.s13-system-mid-rejected — a middle-injected
 *       {@code SystemMessage} forces NO_OP {@code UNSUPPORTED_MESSAGES}, zero
 *       LLM call, history unchanged.
 *   <li>§3.7 TC-FEAT-033.s22-round-boundary-even — the trailing
 *       {@code messagesToKeep=2} already aligns on a complete API round, so
 *       {@code protectedTail} = 2 and middle covers the first 5 groups.
 *   <li>§3.8 TC-FEAT-033.s23-round-boundary-odd-extend-backward —
 *       {@code messagesToKeep=3} crosses a group boundary, forcing
 *       {@code ApiRoundPartitioner} to extend backward so
 *       {@code protectedTail} = 4 (one full pair = 2 groups).
 * </ul>
 *
 * <p>These TCs use the standard trigger entry {@code POST /trigger-compact} and
 * read {@code details.messages_to_keep} + processor log anchors. See
 * {@link Feat033PassiveCompactionTest} for the sibling s0..s11 TCs that share
 * the same {@link CompactProbeHarness} plumbing.
 */
@Tag("integration")
@Tag("feat-033")
@Tag("slow")
@Tag("manual")
final class Feat033LayerProtectionTest {

    /** §3.5 TC-FEAT-033.s12-system-external-preserve. */
    @Test
    void s12ExternalSystemMessagePreserved() throws Exception {
        CompactProbeHarness.assumeFatJarsPresent();
        try (Scenario s = CompactProbeHarness.launch("s12", "valid", Map.of())) {
            TriggerResponse resp = s.triggerAndWait("s12", DEFAULT_TRIGGER_TIMEOUT);

            s.tailer().assertRequired("outcome=LLM_COMPACTED");
            s.tailer().assertRequired("reason=SUCCESS");
            s.tailer().assertForbidden("reason=UNSUPPORTED_MESSAGES");

            new CompressionDetailsAsserter(resp)
                    .assertHttpOk()
                    .assertKind()
                    .assertOutcome("LLM_COMPACTED")
                    .assertReason("SUCCESS")
                    .assertAfterTokensStrictlyLessThanBefore()
                    .assertSchemaKeysMatchWhitelist()
                    .assertNoSecretsInDetails();

            // External system message is preserved outside the compressed window: the
            // probe-side `details` map does not reproduce the body, but the processor
            // must not have logged a reject on the external system entry.
            s.tailer().assertForbidden("ignoring middle system message");
        }
    }

    /**
     * §3.6 TC-FEAT-033.s13-system-mid-rejected — illegal mid-injected system
     * message forces an immediate NO_OP with reason=UNSUPPORTED_MESSAGES.
     * processor must perform zero LLM calls and leave history untouched.
     */
    @Test
    void s13MiddleInjectedSystemMessageRejected() throws Exception {
        CompactProbeHarness.assumeFatJarsPresent();
        try (Scenario s = CompactProbeHarness.launch("s13", "valid", Map.of())) {
            TriggerResponse resp = s.triggerAndWait("s13", DEFAULT_TRIGGER_TIMEOUT);

            s.tailer().assertRequired("outcome=NO_OP");
            s.tailer().assertRequired("reason=UNSUPPORTED_MESSAGES");
            s.tailer().assertRequired("llm_calls=0");
            s.tailer().assertForbidden("outcome=LLM_COMPACTED");
            s.tailer().assertForbidden("outcome=DETERMINISTIC_COMPACTED");
            s.tailer().assertForbidden("phase=attempt");

            new CompressionDetailsAsserter(resp)
                    .assertHttpOk()
                    .assertKind()
                    .assertOutcome("NO_OP")
                    .assertReason("UNSUPPORTED_MESSAGES")
                    .assertAttemptCount(0)
                    .assertLlmCallCount(0)
                    .assertNoSecretsInDetails();
        }
    }

    /**
     * §3.7 TC-FEAT-033.s22-round-boundary-even — 6-group fixture, keep=2 which
     * already covers one full {@code (userN, assistantN)} round, so
     * ApiRoundPartitioner commits protectedTail=2 without extension. Middle
     * window covers the first 5 groups → compaction succeeds.
     */
    @Test
    void s22RoundBoundaryEvenCount() throws Exception {
        CompactProbeHarness.assumeFatJarsPresent();
        try (Scenario s = CompactProbeHarness.launch("s22", "valid", Map.of())) {
            TriggerResponse resp = s.triggerAndWait("s22", DEFAULT_TRIGGER_TIMEOUT);

            s.tailer().assertRequired("outcome=LLM_COMPACTED");
            s.tailer().assertRequired("reason=SUCCESS");

            new CompressionDetailsAsserter(resp)
                    .assertHttpOk()
                    .assertKind()
                    .assertOutcome("LLM_COMPACTED")
                    .assertReason("SUCCESS")
                    .assertAfterTokensStrictlyLessThanBefore()
                    .assertNoSecretsInDetails();

            // Config echo — demo profile sets messagesToKeep=2 for s22.
            assertThat(((Number) resp.detail("messages_to_keep")).intValue())
                    .as("s22 messages_to_keep (even-count — no extend-backward)")
                    .isEqualTo(2);
        }
    }

    /**
     * §3.8 TC-FEAT-033.s23-round-boundary-odd-extend-backward — same 6-group
     * fixture, keep=3 which crosses a group boundary. ApiRoundPartitioner must
     * extend backward so protectedTail covers one full 2-group pair
     * (protectedTail=4). Middle window covers the first 4 groups.
     */
    @Test
    void s23RoundBoundaryOddExtendBackward() throws Exception {
        CompactProbeHarness.assumeFatJarsPresent();
        try (Scenario s = CompactProbeHarness.launch("s23", "valid", Map.of())) {
            TriggerResponse resp = s.triggerAndWait("s23", DEFAULT_TRIGGER_TIMEOUT);

            s.tailer().assertRequired("outcome=LLM_COMPACTED");
            s.tailer().assertRequired("reason=SUCCESS");

            new CompressionDetailsAsserter(resp)
                    .assertHttpOk()
                    .assertKind()
                    .assertOutcome("LLM_COMPACTED")
                    .assertReason("SUCCESS")
                    .assertAfterTokensStrictlyLessThanBefore()
                    .assertNoSecretsInDetails();

            // Config echo — demo profile sets messagesToKeep=3 for s23. The
            // partitioner's extend-backward decision is not directly surfaced in
            // the details map; the smoke behavior (SUCCESS + after < before)
            // is the only cross-process evidence the demo exposes today.
            assertThat(((Number) resp.detail("messages_to_keep")).intValue())
                    .as("s23 messages_to_keep (odd-count — extend-backward)")
                    .isEqualTo(3);
        }
    }
}

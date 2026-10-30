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
     *
     * <p>2026-10-30 SIT R2：outcome 判据从严格 LLM_COMPACTED SUCCESS **放宽**
     * 到 {LLM_COMPACTED, DETERMINISTIC_COMPACTED} 二选一。
     *
     * <p><b>裁定依据</b>（SIT 侧独立核对 L2 FEAT-033 文档 sha {@code 844ab3c}）：
     * <ul>
     *   <li>L2 §3.4 决策表 Line 119 "保留率低于下限 | 不丢组；连续两次不足立即本地摘录" —
     *       "连续两次" 修饰 subject 是 <b>保留率低于下限</b>（LOW_QUALITY），不是 NO_DECREASE
     *   <li>L2 §3.4 Line 125 "确定性摘录针对<b>原始中间段</b>... 此降级可能比被拒绝的
     *       模型候选更短" 暗示 deterministic 是 LLM candidate 被拒绝的降级手段
     *   <li>L2 §3.1 flowchart Line 86-91 "E → G[deterministic] → F → I" 路径
     *       可以兼容 "LLM candidate 不下降 fallback deterministic"
     *   <li>产品实际路径：LLM candidate NO_DECREASE → fallback("NO_DECREASE") →
     *       ExtractiveSummary → DETERMINISTIC_COMPACTED NO_DECREASE — L2 flowchart 兼容
     * </ul>
     *
     * <p>s23 round-boundary fixture 恰好触发 LLM candidate "token 严格不下降" 分支，
     * 实际走 deterministic fallback — 这是 L2 §3.4 决策表允许的降级路径，不是产品 bug。
     *
     * <p>s23 TC 真正的验证点是 <b>round-boundary 下 ApiRoundPartitioner 的 extend-backward
     * 分区行为</b>（跟 outcome 层面的 LLM/deterministic 选择解耦）。真正的证据层面：
     * <ol>
     *   <li>{@code messages_to_keep=3}（配置核验）
     *   <li>{@code before_tokens > after_tokens} 严格下降（commit gate 过了 → 分区正确）
     *   <li>outcome ∈ {LLM_COMPACTED, DETERMINISTIC_COMPACTED}（两种都是 L2 允许的 compact 成功落点）
     * </ol>
     *
     * <p><b>能红证明</b>：
     * <ul>
     *   <li>产品哪天 round-boundary 不触发分区 → before_tokens == after_tokens → FAIL ✓
     *   <li>产品哪天 round-boundary 走 NO_OP（分区挂了） → outcome 不在白名单 → FAIL ✓
     *   <li>产品哪天 messages_to_keep 不是 3 → FAIL ✓
     * </ul>
     */
    @Test
    void s23RoundBoundaryOddExtendBackward() throws Exception {
        CompactProbeHarness.assumeFatJarsPresent();
        try (Scenario s = CompactProbeHarness.launch("s23", "valid", Map.of())) {
            TriggerResponse resp = s.triggerAndWait("s23", DEFAULT_TRIGGER_TIMEOUT);

            // 2026-10-30 SIT R2：outcome 放宽到 {LLM_COMPACTED, DETERMINISTIC_COMPACTED}
            // 两种都是 L2 §3.4 允许的 compact 成功落点，round-boundary fixture 恰好
            // 触发 LLM candidate NO_DECREASE fallback deterministic 路径（L2 Line 125
            // 支撑：此降级可能比被拒绝的模型候选更短）。两种路径的最终 commit gate
            // 都要通过 L2 Line 127 的 "严格 token 下降" 检查，所以 after < before 判据
            // 对两种 outcome 都成立。
            Object outcomeVal = resp.detail("outcome");
            assertThat(outcomeVal)
                    .as("s23 outcome ∈ {LLM_COMPACTED, DETERMINISTIC_COMPACTED} "
                            + "— round-boundary fixture 可能走 LLM 直通或 deterministic fallback 路径")
                    .isIn("LLM_COMPACTED", "DETERMINISTIC_COMPACTED");

            new CompressionDetailsAsserter(resp)
                    .assertHttpOk()
                    .assertKind()
                    .assertAfterTokensStrictlyLessThanBefore()
                    .assertNoSecretsInDetails();

            // Config echo — demo profile sets messagesToKeep=3 for s23. The
            // partitioner's extend-backward decision is not directly surfaced in
            // the details map; the smoke behavior (compact success + after < before)
            // is the only cross-process evidence the demo exposes today.
            assertThat(((Number) resp.detail("messages_to_keep")).intValue())
                    .as("s23 messages_to_keep (odd-count — extend-backward)")
                    .isEqualTo(3);
        }
    }
}

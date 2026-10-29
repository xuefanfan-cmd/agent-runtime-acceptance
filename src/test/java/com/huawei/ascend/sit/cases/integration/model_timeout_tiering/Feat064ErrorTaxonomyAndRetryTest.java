/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.model_timeout_tiering;

import com.huawei.ascend.sit.cases.integration.model_timeout_tiering.fixture.ErrorEnvelopeAsserter;
import com.huawei.ascend.sit.cases.integration.model_timeout_tiering.fixture.StubStatsClient;
import com.huawei.ascend.sit.cases.integration.model_timeout_tiering.fixture.SutLogTailer;
import com.huawei.ascend.sit.cases.integration.model_timeout_tiering.fixture.TimeoutDemoLauncher;
import com.huawei.ascend.sit.cases.integration.model_timeout_tiering.fixture.TimeoutProbeHttpClient;
import com.huawei.ascend.sit.cases.integration.model_timeout_tiering.fixture.TimeoutProbeHttpClient.QueryResponse;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-064 — model call error taxonomy and retry, 7 classification TCs
 * + 1 details.category aggregation TC. All reuse the FEAT-058 fixture quartet
 * (TimeoutDemoLauncher / TimeoutProbeHttpClient / SutLogTailer /
 * ErrorEnvelopeAsserter) with the FEAT-064 asserter extensions
 * ({@code assertCategory} / {@code assertStatusCode} /
 * {@code assertRetryLogCount} / {@code assertNoRetryLog}).
 *
 * <p>Covers the 8 TCs with {@code status=runnable} in
 * docs/cases/FEAT-064-model-call-error-taxonomy-and-retry.md §4 status table
 * (excluding TC-MAX-RETRIES-NEGATIVE-REJECTED which lives in a sibling class
 * because it uses {@code startExpectingFailure()} rather than readiness):
 * <ul>
 *   <li>TC-RATE-LIMITED-PERSISTENT (§3.1) — S7
 *   <li>TC-RATE-LIMITED-RECOVER (§3.2) — S8
 *   <li>TC-QUOTA-EXCEEDED (§3.3) — S9
 *   <li>TC-AUTH-FAILED (§3.4) — S10
 *   <li>TC-TEMPORARY-EXHAUSTED (§3.5) — S11 (doubles as default maxRetries=2 proof)
 *   <li>TC-CONTEXT-LENGTH-EXCEEDED (§3.6) — S12
 *   <li>TC-STREAM-ERROR-FRAME (§3.7) — S13
 *   <li>TC-DETAILS-CATEGORY-FIELD (§3.9) — aggregates 5 traces
 *   <li>TC-SUCCESS-NO-INTERFERENCE (cases §3.14, r5 "stub CI 补充") — stub
 *       {@code ok} happy path. Does NOT claim L1 §8.3 ST-R01 anchor (ST-R01 is
 *       owned by {@code Feat064RealEndpointSamplingTest.realEndpointHappyPath}).
 *   <li>TC-STREAM-RECOVER-BEFORE-FIRST (ST-S01, L1 §8.3) — stub
 *       {@code rate-limited-recover} first-segment boundary + stream data integrity
 *   <li>TC-NON-STREAM-RATE-LIMITED-RECOVER (cases §3.18, ST-R02) — blocking
 *       path (stream:false) rate-limit recover; single JSON wire parse.
 *   <li>TC-STREAM-RATE-LIMITED-FIRST-SEGMENT-AFTER (cases §3.20, ST-S02) —
 *       demo S21 {@code stream-rate-limited-first-segment-after}: emits
 *       first segment then 429; MUST#7 pins "首段后不透明重试".
 * </ul>
 *
 * <p>All TCs use the demo {@code s0} profile (baseline — no three-layer timeout
 * overrides, no max-retries override) so classification + retry behavior is not
 * confounded with FEAT-058 timeout tiering. Per cases §2.3 all TCs run under
 * default max-retries (SDK default = 2 after FEAT-064).
 *
 * <p>USER_CANCELLED (M14 C1-C5) is NOT covered here — deferred until FEAT-058
 * MUST#11 cancel-observability output is adjudicated; see
 * {@code SIT-CLARIFY-REPLY-must11-cancel-definition.md}. UT-C10/UT-R06 in
 * agent-core-java currently carry the burden.
 *
 * <p>Preconditions (expressed as {@link Assumptions#assumeTrue} — missing jar
 * → test SKIPPED, not FAILED): same as FEAT-058.
 */
@Tag("integration")
@Tag("feat-064")
@Tag("slow")
@Tag("manual")
final class Feat064ErrorTaxonomyAndRetryTest {

    private static final Duration DEFAULT_INVOKE_TIMEOUT = Duration.ofSeconds(60);

    private static final String PROMPT = "ping";

    /** FEAT-064 default max-retries after !420 (was 3, now 2). */
    private static final int DEFAULT_MAX_RETRIES = 2;

    /**
     * FEAT-064 default guardrail retries on {@code ClassifiedModelFailureRail}.
     * Rail is unconditionally registered by {@code ReActAgent}; its default is
     * 1. Rail does NOT consume {@code retryOnRateLimit}/{@code retryOnTemporary}
     * switches — only {@code maxRetries>0}.
     *
     * <p>Two-layer math for persistent retryable classification under default
     * config: {@code (DEFAULT_MAX_RETRIES+1) × (DEFAULT_GUARDRAIL_RETRIES+1)
     *   = (2+1)×(1+1) = 6} stub calls.
     *
     * <p>Inner retry log count (emitted only by {@code ModelCallRetrier}, not
     * Rail): {@code DEFAULT_MAX_RETRIES × (DEFAULT_GUARDRAIL_RETRIES+1) = 2×2 = 4}.
     */
    private static final int DEFAULT_GUARDRAIL_RETRIES = 1;

    private static final int PERSISTENT_RETRYABLE_STUB_CALLS =
            (DEFAULT_MAX_RETRIES + 1) * (DEFAULT_GUARDRAIL_RETRIES + 1);  // 6

    private static final int PERSISTENT_RETRYABLE_RETRY_LOG_COUNT =
            DEFAULT_MAX_RETRIES * (DEFAULT_GUARDRAIL_RETRIES + 1);        // 4

    /**
     * TC-RATE-LIMITED-PERSISTENT (cases §3.1). stub mode
     * {@code rate-limited-persistent} returns 429 + {@code rate_limit_exceeded}
     * on every call. Expect: 181009 + {@code category=RATE_LIMITED} +
     * {@value PERSISTENT_RETRYABLE_RETRY_LOG_COUNT} retry log lines +
     * {@value PERSISTENT_RETRYABLE_STUB_CALLS} stub calls (two-layer defaults).
     */
    @Test
    void rateLimitedPersistentExhaustsBudget() throws Exception {
        assumeFatJarsPresent();

        try (QueryTrace trace = runStubbed("rate-limited-persistent")) {
            new ErrorEnvelopeAsserter(trace.response)
                    .assertStatusCode(ErrorEnvelopeAsserter.MODEL_CALL_RATE_LIMITED)
                    .assertCategory(ErrorEnvelopeAsserter.CATEGORY_RATE_LIMITED)
                    .assertRetryLogCount(trace.tailer, PERSISTENT_RETRYABLE_RETRY_LOG_COUNT)
                    .assertStubCallCount(trace.stats, "rate-limited-persistent",
                            PERSISTENT_RETRYABLE_STUB_CALLS);

            assertNoOtherClassifiedCodes(trace.tailer,
                    ErrorEnvelopeAsserter.MODEL_CALL_RATE_LIMITED);
        }
    }

    /**
     * TC-RATE-LIMITED-RECOVER (cases §3.2). stub mode
     * {@code rate-limited-recover} returns 429 first, 200 afterwards
     * ({@code LLM_STUB_RATE_LIMITED_RECOVER_AFTER=1}). Expect: HTTP 200 +
     * {@code result_type=answer} + exactly 1 retry log line + 2 stub calls
     * (first 429 → retry → 200) + no 181009.
     */
    @Test
    void rateLimitedRecoverSucceedsAfterOneRetry() throws Exception {
        assumeFatJarsPresent();

        Map<String, String> stubExtra = new LinkedHashMap<>();
        stubExtra.put("LLM_STUB_RATE_LIMITED_RECOVER_AFTER", "1");
        stubExtra.put("LLM_STUB_RATE_LIMITED_RETRY_AFTER_SECONDS", "1");

        try (QueryTrace trace = runStubbed("rate-limited-recover", stubExtra)) {
            // Success path: HTTP 200 + answer surface
            assertThat(trace.response.statusCode())
                    .as("HTTP status for rate-limited-recover success")
                    .isEqualTo(200);
            assertThat(trace.response.resultType())
                    .as("result_type for rate-limited-recover success")
                    .isEqualTo("answer");

            // Exactly one inner retry (first 429 → retry → 200), 2 stub calls
            new ErrorEnvelopeAsserter(trace.response)
                    .assertRetryLogCount(trace.tailer, 1)
                    .assertStubCallCount(trace.stats, "rate-limited-recover", 2);

            // No classified error codes should have landed as terminal failures
            assertNoOtherClassifiedCodes(trace.tailer, -1);
        }
    }

    /**
     * TC-QUOTA-EXCEEDED (cases §3.3). stub mode {@code quota-exceeded} returns
     * 402 + {@code insufficient_quota}. {@code PermanentModelError.defaultRecoverable=false}
     * → short-circuit, zero retries. Expect: 181010 + {@code category=QUOTA_EXCEEDED}
     * + no retry log + 1 stub call (Rail gates permanent classification; neither
     * Retrier nor Rail retries).
     */
    @Test
    void quotaExceededIsPermanentAndShortCircuits() throws Exception {
        assumeFatJarsPresent();

        try (QueryTrace trace = runStubbed("quota-exceeded")) {
            new ErrorEnvelopeAsserter(trace.response)
                    .assertStatusCode(ErrorEnvelopeAsserter.MODEL_CALL_QUOTA_EXCEEDED)
                    .assertCategory(ErrorEnvelopeAsserter.CATEGORY_QUOTA_EXCEEDED)
                    .assertNoRetryLog(trace.tailer)
                    .assertStubCallCount(trace.stats, "quota-exceeded", 1)
                    .assertTerminalLogCategory(trace.tailer,
                            ErrorEnvelopeAsserter.CATEGORY_QUOTA_EXCEEDED,
                            ErrorEnvelopeAsserter.REASON_NOT_RETRYABLE, 1);

            assertNoOtherClassifiedCodes(trace.tailer,
                    ErrorEnvelopeAsserter.MODEL_CALL_QUOTA_EXCEEDED);
        }
    }

    /**
     * TC-AUTH-FAILED (cases §3.4). stub mode {@code auth-failed} returns 401 +
     * {@code invalid_api_key}. Expect: 181011 + {@code category=AUTH_FAILED}
     * + no retry log + 1 stub call (permanent short-circuit).
     */
    @Test
    void authFailedIsPermanentAndShortCircuits() throws Exception {
        assumeFatJarsPresent();

        try (QueryTrace trace = runStubbed("auth-failed")) {
            new ErrorEnvelopeAsserter(trace.response)
                    .assertStatusCode(ErrorEnvelopeAsserter.MODEL_CALL_AUTH_FAILED)
                    .assertCategory(ErrorEnvelopeAsserter.CATEGORY_AUTH_FAILED)
                    .assertNoRetryLog(trace.tailer)
                    .assertStubCallCount(trace.stats, "auth-failed", 1)
                    .assertTerminalLogCategory(trace.tailer,
                            ErrorEnvelopeAsserter.CATEGORY_AUTH_FAILED,
                            ErrorEnvelopeAsserter.REASON_NOT_RETRYABLE, 1);

            assertNoOtherClassifiedCodes(trace.tailer,
                    ErrorEnvelopeAsserter.MODEL_CALL_AUTH_FAILED);
        }
    }

    /**
     * TC-TEMPORARY-EXHAUSTED (cases §3.5). stub mode {@code server-5xx} returns
     * 500 + {@code server_error} on every call. Expect: 181001 +
     * {@code category=TEMPORARY} + {@value PERSISTENT_RETRYABLE_RETRY_LOG_COUNT}
     * retry log lines + {@value PERSISTENT_RETRYABLE_STUB_CALLS} stub calls
     * (two-layer defaults — proves TEMPORARY walks the retry path AND default
     * {@code maxRetries} is 2 after FEAT-064 !420).
     *
     * <p>Dual-purpose TC — see cases §3.5 note. The retry-log + stub-call pair
     * is load-bearing for both §1.2.6 and §1.2.9.
     */
    @Test
    void temporaryExhaustsAtDefaultMaxRetriesTwo() throws Exception {
        assumeFatJarsPresent();

        try (QueryTrace trace = runStubbed("server-5xx")) {
            new ErrorEnvelopeAsserter(trace.response)
                    .assertStatusCode(ErrorEnvelopeAsserter.MODEL_CALL_ERROR)
                    .assertCategory(ErrorEnvelopeAsserter.CATEGORY_TEMPORARY)
                    .assertRetryLogCount(trace.tailer, PERSISTENT_RETRYABLE_RETRY_LOG_COUNT)
                    .assertStubCallCount(trace.stats, "server-5xx",
                            PERSISTENT_RETRYABLE_STUB_CALLS)
                    .assertTerminalLogCategory(trace.tailer,
                            ErrorEnvelopeAsserter.CATEGORY_TEMPORARY,
                            ErrorEnvelopeAsserter.REASON_RETRY_BUDGET_EXHAUSTED, 2);

            assertNoOtherClassifiedCodes(trace.tailer,
                    ErrorEnvelopeAsserter.MODEL_CALL_ERROR);
            assertThat(trace.tailer.totalTimeoutCount())
                    .as("TEMPORARY exhaustion must not emit any 181008")
                    .isZero();
        }
    }

    /**
     * TC-CONTEXT-LENGTH-EXCEEDED (cases §3.6). stub mode
     * {@code context-length-exceeded} returns 400 + {@code code=context_length_exceeded}.
     * Expect: 181012 + {@code category=CONTEXT_LENGTH_EXCEEDED} + no retry log
     * + 1 stub call (permanent short-circuit).
     */
    @Test
    void contextLengthExceededIsPermanentAndShortCircuits() throws Exception {
        assumeFatJarsPresent();

        try (QueryTrace trace = runStubbed("context-length-exceeded")) {
            new ErrorEnvelopeAsserter(trace.response)
                    .assertStatusCode(ErrorEnvelopeAsserter.MODEL_CONTEXT_LENGTH_EXCEEDED)
                    .assertCategory(ErrorEnvelopeAsserter.CATEGORY_CONTEXT_LENGTH_EXCEEDED)
                    .assertNoRetryLog(trace.tailer)
                    .assertStubCallCount(trace.stats, "context-length-exceeded", 1)
                    .assertTerminalLogCategory(trace.tailer,
                            ErrorEnvelopeAsserter.CATEGORY_CONTEXT_LENGTH_EXCEEDED,
                            ErrorEnvelopeAsserter.REASON_NOT_RETRYABLE, 1);

            assertNoOtherClassifiedCodes(trace.tailer,
                    ErrorEnvelopeAsserter.MODEL_CONTEXT_LENGTH_EXCEEDED);
        }
    }

    /**
     * TC-STREAM-ERROR-FRAME (cases §3.7). stub mode {@code stream-error-frame}
     * emits SSE first segment OK, then an error frame. {@code FirstSegmentRetryIterator}
     * must NOT whole-call retry after first segment; Rail is also bypassed by
     * the first-segment boundary. Expect: 181001 + zero retry log lines + 1
     * stub call.
     */
    @Test
    void streamErrorFrameDoesNotTriggerWholeCallRetry() throws Exception {
        assumeFatJarsPresent();

        try (QueryTrace trace = runStubbed("stream-error-frame")) {
            new ErrorEnvelopeAsserter(trace.response)
                    .assertStatusCode(ErrorEnvelopeAsserter.MODEL_CALL_ERROR)
                    .assertNoRetryLog(trace.tailer)
                    .assertStubCallCount(trace.stats, "stream-error-frame", 1);

            assertThat(trace.tailer.totalTimeoutCount())
                    .as("stream-error-frame must not emit any 181008")
                    .isZero();
        }
    }

    /**
     * TC-DETAILS-CATEGORY-FIELD (cases §3.9). Aggregates the 5 category traces
     * (RATE_LIMITED persistent / QUOTA_EXCEEDED / AUTH_FAILED / TEMPORARY /
     * CONTEXT_LENGTH_EXCEEDED) and asserts each one carries a populated
     * {@code details.category} field matching the 1:1 category ↔ status_code
     * table.
     *
     * <p>Runs the 5 scenarios inline (self-contained, no cross-test state).
     * Expensive — expect ~2-3 min; already behind {@code slow} + {@code manual}
     * tags for Nightly scheduling.
     */
    @Test
    void detailsCategoryFieldAcrossFiveClassifications() throws Exception {
        assumeFatJarsPresent();

        // (stub mode, expected status_code, expected category)
        List<Classification> table = List.of(
                new Classification("rate-limited-persistent",
                        ErrorEnvelopeAsserter.MODEL_CALL_RATE_LIMITED,
                        ErrorEnvelopeAsserter.CATEGORY_RATE_LIMITED),
                new Classification("quota-exceeded",
                        ErrorEnvelopeAsserter.MODEL_CALL_QUOTA_EXCEEDED,
                        ErrorEnvelopeAsserter.CATEGORY_QUOTA_EXCEEDED),
                new Classification("auth-failed",
                        ErrorEnvelopeAsserter.MODEL_CALL_AUTH_FAILED,
                        ErrorEnvelopeAsserter.CATEGORY_AUTH_FAILED),
                new Classification("server-5xx",
                        ErrorEnvelopeAsserter.MODEL_CALL_ERROR,
                        ErrorEnvelopeAsserter.CATEGORY_TEMPORARY),
                new Classification("context-length-exceeded",
                        ErrorEnvelopeAsserter.MODEL_CONTEXT_LENGTH_EXCEEDED,
                        ErrorEnvelopeAsserter.CATEGORY_CONTEXT_LENGTH_EXCEEDED));

        for (Classification c : table) {
            try (QueryTrace trace = runStubbed(c.stubMode)) {
                new ErrorEnvelopeAsserter(trace.response)
                        .assertStatusCode(c.expectedStatusCode)
                        .assertCategory(c.expectedCategory);
            }
        }
    }

    /**
     * TC-SUCCESS-NO-INTERFERENCE (cases §3.14, r5 "stub CI 补充"). stub mode
     * {@code ok} returns HTTP 200 + an SSE answer stream with a real completion
     * body. Baseline regression check that FEAT-064 taxonomy + Rail wiring does
     * not interfere with the happy path: expect HTTP 200 +
     * {@code result_type=answer} + non-empty {@code output} + zero retry log
     * lines + exactly 1 stub call + no classified error codes in the SUT log.
     *
     * <p>Does NOT claim L1 §8.3 ST-R01: ST-R01 is the real-endpoint绿路 anchor
     * (owned by {@code Feat064RealEndpointSamplingTest.realEndpointHappyPath}).
     * This TC is the stub-CI 补充版本 — cheap (sub-second) and the only
     * green-path anchor in the stub class — it fails loudly if a future
     * FEAT-064 code change accidentally injects an error landing into the
     * success surface.
     */
    @Test
    void successPathIsNotInterferedWith() throws Exception {
        assumeFatJarsPresent();

        try (QueryTrace trace = runStubbed("ok")) {
            assertThat(trace.response.statusCode())
                    .as("HTTP status for ok success")
                    .isEqualTo(200);
            assertThat(trace.response.resultType())
                    .as("result_type for ok success")
                    .isEqualTo("answer");
            assertThat(trace.response.output())
                    .as("output for ok success must be non-empty")
                    .isNotNull()
                    .isNotBlank();

            new ErrorEnvelopeAsserter(trace.response)
                    .assertNoRetryLog(trace.tailer)
                    .assertStubCallCount(trace.stats, "ok", 1);

            assertNoOtherClassifiedCodes(trace.tailer, -1);
            assertThat(trace.tailer.totalTimeoutCount())
                    .as("ok success must not emit any 181008")
                    .isZero();
        }
    }

    /**
     * TC-STREAM-RECOVER-BEFORE-FIRST (L1 §8.3 ST-S01). stub mode
     * {@code rate-limited-recover} emits HTTP 429 on first attempt (before any
     * SSE segment lands) then HTTP 200 with full SSE answer stream on retry.
     * This exercises the first-segment boundary: {@code FirstSegmentRetryIterator}
     * MAY whole-call retry before the first segment lands, and the recovered
     * answer must flow through unmodified.
     *
     * <p>Differs from {@link #rateLimitedRecoverSucceedsAfterOneRetry()} in
     * anchor + emphasis: that TC pins the §3.2 M-series retry-count contract
     * ({@code 2 stub calls / 1 retry log}); this TC pins the L1 §8.3 ST-series
     * stream-data-integrity contract (end-to-end {@code result_type=answer} +
     * non-empty {@code output} after recover). The two share stub config but
     * assert different acceptance surfaces — both are kept so a change to only
     * one surface still fails the right test.
     */
    @Test
    void streamRecoverBeforeFirstSegmentPreservesAnswer() throws Exception {
        assumeFatJarsPresent();

        Map<String, String> stubExtra = new LinkedHashMap<>();
        stubExtra.put("LLM_STUB_RATE_LIMITED_RECOVER_AFTER", "1");
        stubExtra.put("LLM_STUB_RATE_LIMITED_RETRY_AFTER_SECONDS", "1");

        try (QueryTrace trace = runStubbed("rate-limited-recover", stubExtra, true)) {
            assertThat(trace.response.statusCode())
                    .as("HTTP status after first-segment-boundary recover")
                    .isEqualTo(200);
            assertThat(trace.response.resultType())
                    .as("result_type after recover")
                    .isEqualTo("answer");
            assertThat(trace.response.output())
                    .as("output after recover must survive the retry transition")
                    .isNotNull()
                    .isNotBlank();

            // At least 1 retry fired (exact count pinned by §3.2 sibling TC).
            new ErrorEnvelopeAsserter(trace.response)
                    .assertRetryLogCount(trace.tailer, 1)
                    .assertStubCallCount(trace.stats, "rate-limited-recover", 2);

            assertNoOtherClassifiedCodes(trace.tailer, -1);
        }
    }

    /**
     * TC-NON-STREAM-RATE-LIMITED-RECOVER (cases §3.18, ST-R02). stub mode
     * {@code rate-limited-recover} returns 429 first, 200 afterwards. Caller
     * forces {@code stream=false} on the probe-agent HTTP body — exercises the
     * blocking single-JSON wire path (as opposed to §3.2 which rides SSE).
     * Expect: HTTP 200 + {@code result_type=answer} + exactly 1 retry log line
     * + strong-eq 2 stub calls (first 429 → retry → 200).
     *
     * <p>Fills the ST-R02 slot opened by r5 — "非流式 recover" complements
     * §3.2's streaming recover and pins the single-JSON parse path.
     */
    @Test
    void nonStreamRateLimitedRecoverSucceedsAfterRetry() throws Exception {
        assumeFatJarsPresent();

        Map<String, String> stubExtra = new LinkedHashMap<>();
        stubExtra.put("LLM_STUB_RATE_LIMITED_RECOVER_AFTER", "1");
        stubExtra.put("LLM_STUB_RATE_LIMITED_RETRY_AFTER_SECONDS", "1");

        try (QueryTrace trace = runStubbed("rate-limited-recover", stubExtra, false)) {
            assertThat(trace.response.statusCode())
                    .as("HTTP status for non-stream rate-limited-recover success")
                    .isEqualTo(200);
            // 2026-10-29 SIT 实证订正：/v1/query 带 stream=false 的 blocking 响应 wire schema 是
            //   {"result":{"role":"assistant","content":"ok"},"conversation_id":"..."}
            // 跟 streaming SSE 的 {"type":"answer","payload":{"result_type":"answer","output":"..."}}
            // 完全不同——blocking wire 没有 result_type 字段（通过 result.role=assistant 隐式标识
            // 是 agent answer）。r5 新建此 TC 时按 streaming 字段写判据不符实际 blocking wire schema。
            // 本次订正：删除 .resultType()=="answer" 判据（blocking 没该字段），改为验 output 非空
            // （fixture TimeoutProbeHttpClient.parseSingleJson 已补从 result.content 读 output）。
            // 实证来源：2026-10-29 ECS curl 复现 /v1/query stream=false 的 raw body。
            assertThat(trace.response.output())
                    .as("output for non-stream rate-limited-recover success "
                            + "(parsed from result.content per blocking wire schema)")
                    .isNotNull()
                    .isNotBlank();

            new ErrorEnvelopeAsserter(trace.response)
                    .assertRetryLogCount(trace.tailer, 1)
                    .assertStubCallCount(trace.stats, "rate-limited-recover", 2);

            assertNoOtherClassifiedCodes(trace.tailer, -1);
        }
    }

    /**
     * TC-STREAM-RATE-LIMITED-FIRST-SEGMENT-AFTER (cases §3.20, ST-S03 子型
     * — r6 relabel S02→S03). stub mode {@code stream-rate-limited-first-segment-after}
     * emits the first SSE segment OK, then injects a 429. {@code FirstSegmentRetryIterator}
     * MUST NOT whole-call retry past the first segment (MUST#7 "首段后不透明重试");
     * Rail is also bypassed at that boundary. Expect: numericCode ∈
     * {181009, 181001} (the exact code is schema-dependent — DashScope family
     * maps 429-after-first to TEMPORARY; OpenAI family keeps RATE_LIMITED) +
     * zero retry log lines + strong-eq 1 stub call.
     *
     * <p>Demo S21 covers this scenario per 2026-10-28 dev confirmation —
     * SIT does not need to construct a custom stream.
     *
     * <p>r6 relabel: this TC is "retryable semantics + first-segment-after"
     * sub-variant of ST-S03. The true ST-S02 (strict pin 181001 unique, pure
     * TCP disconnect without payload) is covered by §3.22 TC
     * {@link #streamDisconnectFirstSegmentAfterStrictPinsTemporary}.
     */
    @Test
    void streamRateLimitedFirstSegmentAfterShortCircuits() throws Exception {
        assumeFatJarsPresent();

        try (QueryTrace trace = runStubbed("stream-rate-limited-first-segment-after")) {
            Integer actualCode = trace.response.errorStatusCode();
            assertThat(actualCode)
                    .as("Expected numericCode present for first-segment-after rate-limit")
                    .isNotNull();
            assertThat(actualCode)
                    .as("Expected numericCode ∈ {181009 RATE_LIMITED, 181001 TEMPORARY} "
                            + "for first-segment-after 429 (schema-agnostic landing)")
                    .isIn(ErrorEnvelopeAsserter.MODEL_CALL_RATE_LIMITED,
                            ErrorEnvelopeAsserter.MODEL_CALL_ERROR);

            new ErrorEnvelopeAsserter(trace.response)
                    .assertNoRetryLog(trace.tailer)
                    .assertStubCallCount(trace.stats,
                            "stream-rate-limited-first-segment-after", 1);

            assertThat(trace.tailer.totalTimeoutCount())
                    .as("stream-rate-limited-first-segment-after must not emit any 181008")
                    .isZero();
        }
    }

    /**
     * TC-STREAM-DISCONNECT-FIRST-SEGMENT-AFTER-STRICT (cases §3.22, ST-S02
     * 真型 — r6 新增). stub mode {@code stream-disconnect-first-segment-after}
     * emits the first SSE segment OK, then closes TCP without any wire
     * payload (no 429, no error frame, pure disconnect). Classifier MUST
     * strict-pin to {@code numericCode==181001} (MODEL_CALL_ERROR / TEMPORARY)
     * — the unique landing for payload-less disconnect — and MUST NOT
     * whole-call retry (MUST#7 "首段后不透明重试"). Expect: strong-eq
     * numericCode==181001 + zero retry log + strong-eq 1 stub call.
     *
     * <p>r6 新增: separates "retryable semantics after first segment" (§3.20,
     * ST-S03 子型) from "pure disconnect after first segment" (this TC, true
     * ST-S02). stub mode is new and needs demo-handoff smoke.
     */
    @Test
    void streamDisconnectFirstSegmentAfterStrictPinsTemporary() throws Exception {
        assumeFatJarsPresent();

        try (QueryTrace trace = runStubbed("stream-disconnect-first-segment-after")) {
            Integer actualCode = trace.response.errorStatusCode();
            assertThat(actualCode)
                    .as("Expected numericCode strict-pin 181001 (TEMPORARY) for "
                            + "first-segment-after pure disconnect without wire payload")
                    .isEqualTo(ErrorEnvelopeAsserter.MODEL_CALL_ERROR);

            new ErrorEnvelopeAsserter(trace.response)
                    .assertNoRetryLog(trace.tailer)
                    .assertStubCallCount(trace.stats,
                            "stream-disconnect-first-segment-after", 1);

            assertThat(trace.tailer.totalTimeoutCount())
                    .as("stream-disconnect-first-segment-after must not emit any 181008")
                    .isZero();
        }
    }

    // ---------------- helpers ----------------

    private QueryTrace runStubbed(String stubMode) throws Exception {
        return runStubbed(stubMode, Map.of());
    }

    private QueryTrace runStubbed(String stubMode, Map<String, String> stubExtraEnv) throws Exception {
        return runStubbed(stubMode, stubExtraEnv, null);
    }

    /**
     * Variant with explicit HTTP body {@code stream} flag — pass {@code false}
     * for non-stream TCs (§3.18 ST-R02) and {@code true} for explicit-stream
     * TCs (§3.15 stream wire pinning). {@code null} omits the field (demo
     * default behavior).
     */
    private QueryTrace runStubbed(String stubMode, Map<String, String> stubExtraEnv,
                                  Boolean streamFlag) throws Exception {
        Map<String, String> stubEnv = commonEnv();
        stubEnv.put("LLM_STUB_DEFAULT_MODE", stubMode);
        stubEnv.putAll(stubExtraEnv);

        TimeoutDemoLauncher stub = TimeoutDemoLauncher.llmStubServer()
                .envAll(stubEnv)
                .build()
                .start();
        TimeoutDemoLauncher probe = null;
        try {
            probe = TimeoutDemoLauncher.probeAgent()
                    .profile("s0")
                    .envAll(probeEnv(stub))
                    .build()
                    .start();
            QueryResponse resp = new TimeoutProbeHttpClient(probe.baseUrl())
                    .query(PROMPT, DEFAULT_INVOKE_TIMEOUT, streamFlag);
            SutLogTailer tailer = new SutLogTailer(probe.logFile());
            StubStatsClient stats = new StubStatsClient(stub.baseUrl());
            return new QueryTrace(stub, probe, resp, tailer, stats);
        } catch (Exception | Error e) {
            if (probe != null) {
                probe.close();
            }
            stub.close();
            throw e;
        }
    }

    /**
     * Assert the SUT log does not carry classified error codes other than the
     * expected one (and never any timeout 181008 — FEAT-064 TCs run under
     * default max-retries without three-layer timeout overrides, so 181008 is
     * never a legal landing). Pass {@code -1} for the {@code expected} arg to
     * disallow all classified codes (e.g. for TC-RATE-LIMITED-RECOVER success).
     */
    private void assertNoOtherClassifiedCodes(SutLogTailer tailer, int expectedCode) throws Exception {
        int[] classified = {
                ErrorEnvelopeAsserter.MODEL_CALL_RATE_LIMITED,
                ErrorEnvelopeAsserter.MODEL_CALL_QUOTA_EXCEEDED,
                ErrorEnvelopeAsserter.MODEL_CALL_AUTH_FAILED,
                ErrorEnvelopeAsserter.MODEL_CONTEXT_LENGTH_EXCEEDED,
                ErrorEnvelopeAsserter.MODEL_CALL_TIMEOUT_CODE,
        };
        for (int code : classified) {
            if (code == expectedCode) {
                continue;
            }
            long count = tailer.countOfStatusCode(code);
            assertThat(count)
                    .as("SUT log must not contain [%d] (expected only [%d])", code, expectedCode)
                    .isZero();
        }
    }

    private void assumeFatJarsPresent() {
        Assumptions.assumeTrue(
                Files.isRegularFile(TimeoutDemoLauncher.DEFAULT_PROBE_JAR)
                        || System.getProperty(TimeoutDemoLauncher.PROBE_JAR_PROP) != null,
                "timeout-probe-agent fat jar not present; set -D" + TimeoutDemoLauncher.PROBE_JAR_PROP);
        Assumptions.assumeTrue(
                Files.isRegularFile(TimeoutDemoLauncher.DEFAULT_STUB_JAR)
                        || System.getProperty(TimeoutDemoLauncher.STUB_JAR_PROP) != null,
                "llm-stub-server fat jar not present; set -D" + TimeoutDemoLauncher.STUB_JAR_PROP);
    }

    private static Map<String, String> commonEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("JAVA_HOME", System.getProperty("java.home"));
        env.put("PATH", System.getenv().getOrDefault("PATH", ""));
        env.put("LANG", "en_US.UTF-8");
        return env;
    }

    private static Map<String, String> baseProbeEnv() {
        Map<String, String> env = commonEnv();
        env.put("LLM_PROVIDER", "OpenAI");
        env.put("LLM_API_KEY", "stub-key");
        env.put("LLM_MODEL", "stub-model");
        env.put("LLM_SSL_VERIFY", "false");
        env.put("LLM_TEMPERATURE", "0.0");
        return env;
    }

    private static Map<String, String> probeEnv(TimeoutDemoLauncher stub) {
        Map<String, String> env = baseProbeEnv();
        env.put("LLM_API_BASE", stub.baseUrl() + "/v1");
        return env;
    }

    /**
     * Trace bundle for a single stubbed-request run. Implements {@link AutoCloseable}
     * so TCs can use try-with-resources to tear down both the stub and probe
     * subprocess with a single statement.
     */
    private static final class QueryTrace implements AutoCloseable {
        final TimeoutDemoLauncher stub;
        final TimeoutDemoLauncher probe;
        final QueryResponse response;
        final SutLogTailer tailer;
        final StubStatsClient stats;

        QueryTrace(TimeoutDemoLauncher stub, TimeoutDemoLauncher probe,
                   QueryResponse response, SutLogTailer tailer, StubStatsClient stats) {
            this.stub = stub;
            this.probe = probe;
            this.response = response;
            this.tailer = tailer;
            this.stats = stats;
        }

        @Override
        public void close() {
            probe.close();
            stub.close();
        }
    }

    private record Classification(String stubMode, int expectedStatusCode, String expectedCategory) {}
}

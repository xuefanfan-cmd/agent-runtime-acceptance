/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.model_timeout_tiering;

import com.huawei.ascend.sit.cases.integration.model_timeout_tiering.fixture.ErrorEnvelopeAsserter;
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
     * TC-RATE-LIMITED-PERSISTENT (cases §3.1). stub mode
     * {@code rate-limited-persistent} returns 429 + {@code rate_limit_exceeded}
     * on every call. Expect: 181009 + {@code category=RATE_LIMITED} +
     * exactly {@code DEFAULT_MAX_RETRIES} retry log lines.
     */
    @Test
    void rateLimitedPersistentExhaustsBudget() throws Exception {
        assumeFatJarsPresent();

        try (QueryTrace trace = runStubbed("rate-limited-persistent")) {
            new ErrorEnvelopeAsserter(trace.response)
                    .assertStatusCode(ErrorEnvelopeAsserter.MODEL_CALL_RATE_LIMITED)
                    .assertCategory(ErrorEnvelopeAsserter.CATEGORY_RATE_LIMITED)
                    .assertRetryLogCount(trace.tailer, DEFAULT_MAX_RETRIES);

            assertNoOtherClassifiedCodes(trace.tailer,
                    ErrorEnvelopeAsserter.MODEL_CALL_RATE_LIMITED);
        }
    }

    /**
     * TC-RATE-LIMITED-RECOVER (cases §3.2). stub mode
     * {@code rate-limited-recover} returns 429 first, 200 afterwards
     * ({@code LLM_STUB_RATE_LIMITED_RECOVER_AFTER=1}). Expect: HTTP 200 +
     * {@code result_type=answer} + exactly 1 retry log line + no 181009.
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

            // Exactly one retry (first 429 → retry → 200)
            new ErrorEnvelopeAsserter(trace.response)
                    .assertRetryLogCount(trace.tailer, 1);

            // No classified error codes should have landed as terminal failures
            assertNoOtherClassifiedCodes(trace.tailer, -1);
        }
    }

    /**
     * TC-QUOTA-EXCEEDED (cases §3.3). stub mode {@code quota-exceeded} returns
     * 402 + {@code insufficient_quota}. {@code PermanentModelError.defaultRecoverable=false}
     * → short-circuit, zero retries. Expect: 181010 + {@code category=QUOTA_EXCEEDED}
     * + no retry log.
     */
    @Test
    void quotaExceededIsPermanentAndShortCircuits() throws Exception {
        assumeFatJarsPresent();

        try (QueryTrace trace = runStubbed("quota-exceeded")) {
            new ErrorEnvelopeAsserter(trace.response)
                    .assertStatusCode(ErrorEnvelopeAsserter.MODEL_CALL_QUOTA_EXCEEDED)
                    .assertCategory(ErrorEnvelopeAsserter.CATEGORY_QUOTA_EXCEEDED)
                    .assertNoRetryLog(trace.tailer);

            assertNoOtherClassifiedCodes(trace.tailer,
                    ErrorEnvelopeAsserter.MODEL_CALL_QUOTA_EXCEEDED);
        }
    }

    /**
     * TC-AUTH-FAILED (cases §3.4). stub mode {@code auth-failed} returns 401 +
     * {@code invalid_api_key}. Expect: 181011 + {@code category=AUTH_FAILED}
     * + no retry log.
     */
    @Test
    void authFailedIsPermanentAndShortCircuits() throws Exception {
        assumeFatJarsPresent();

        try (QueryTrace trace = runStubbed("auth-failed")) {
            new ErrorEnvelopeAsserter(trace.response)
                    .assertStatusCode(ErrorEnvelopeAsserter.MODEL_CALL_AUTH_FAILED)
                    .assertCategory(ErrorEnvelopeAsserter.CATEGORY_AUTH_FAILED)
                    .assertNoRetryLog(trace.tailer);

            assertNoOtherClassifiedCodes(trace.tailer,
                    ErrorEnvelopeAsserter.MODEL_CALL_AUTH_FAILED);
        }
    }

    /**
     * TC-TEMPORARY-EXHAUSTED (cases §3.5). stub mode {@code server-5xx} returns
     * 500 + {@code server_error} on every call. Expect: 181001 +
     * {@code category=TEMPORARY} + exactly 2 retry log lines (proves TEMPORARY
     * walks the retry path AND default maxRetries is 2 after FEAT-064 !420).
     *
     * <p>Dual-purpose TC — see cases §3.5 note. The {@code "恰 2 次"} assertion
     * is load-bearing for both M5 and M9.
     */
    @Test
    void temporaryExhaustsAtDefaultMaxRetriesTwo() throws Exception {
        assumeFatJarsPresent();

        try (QueryTrace trace = runStubbed("server-5xx")) {
            new ErrorEnvelopeAsserter(trace.response)
                    .assertStatusCode(ErrorEnvelopeAsserter.MODEL_CALL_ERROR)
                    .assertCategory(ErrorEnvelopeAsserter.CATEGORY_TEMPORARY)
                    .assertRetryLogCount(trace.tailer, DEFAULT_MAX_RETRIES);

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
     * Expect: 181012 + {@code category=CONTEXT_LENGTH_EXCEEDED} + no retry log.
     */
    @Test
    void contextLengthExceededIsPermanentAndShortCircuits() throws Exception {
        assumeFatJarsPresent();

        try (QueryTrace trace = runStubbed("context-length-exceeded")) {
            new ErrorEnvelopeAsserter(trace.response)
                    .assertStatusCode(ErrorEnvelopeAsserter.MODEL_CALL_CONTEXT_LENGTH_EXCEEDED)
                    .assertCategory(ErrorEnvelopeAsserter.CATEGORY_CONTEXT_LENGTH_EXCEEDED)
                    .assertNoRetryLog(trace.tailer);

            assertNoOtherClassifiedCodes(trace.tailer,
                    ErrorEnvelopeAsserter.MODEL_CALL_CONTEXT_LENGTH_EXCEEDED);
        }
    }

    /**
     * TC-STREAM-ERROR-FRAME (cases §3.7). stub mode {@code stream-error-frame}
     * emits SSE first segment OK, then an error frame. {@code FirstSegmentRetryIterator}
     * must NOT whole-call retry after first segment. Expect: 181001 + zero
     * retry log lines.
     */
    @Test
    void streamErrorFrameDoesNotTriggerWholeCallRetry() throws Exception {
        assumeFatJarsPresent();

        try (QueryTrace trace = runStubbed("stream-error-frame")) {
            new ErrorEnvelopeAsserter(trace.response)
                    .assertStatusCode(ErrorEnvelopeAsserter.MODEL_CALL_ERROR)
                    .assertNoRetryLog(trace.tailer);

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
                        ErrorEnvelopeAsserter.MODEL_CALL_CONTEXT_LENGTH_EXCEEDED,
                        ErrorEnvelopeAsserter.CATEGORY_CONTEXT_LENGTH_EXCEEDED));

        for (Classification c : table) {
            try (QueryTrace trace = runStubbed(c.stubMode)) {
                new ErrorEnvelopeAsserter(trace.response)
                        .assertStatusCode(c.expectedStatusCode)
                        .assertCategory(c.expectedCategory);
            }
        }
    }

    // ---------------- helpers ----------------

    private QueryTrace runStubbed(String stubMode) throws Exception {
        return runStubbed(stubMode, Map.of());
    }

    private QueryTrace runStubbed(String stubMode, Map<String, String> stubExtraEnv) throws Exception {
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
                    .query(PROMPT, DEFAULT_INVOKE_TIMEOUT);
            SutLogTailer tailer = new SutLogTailer(probe.logFile());
            return new QueryTrace(stub, probe, resp, tailer);
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
                ErrorEnvelopeAsserter.MODEL_CALL_CONTEXT_LENGTH_EXCEEDED,
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

        QueryTrace(TimeoutDemoLauncher stub, TimeoutDemoLauncher probe,
                   QueryResponse response, SutLogTailer tailer) {
            this.stub = stub;
            this.probe = probe;
            this.response = response;
            this.tailer = tailer;
        }

        @Override
        public void close() {
            probe.close();
            stub.close();
        }
    }

    private record Classification(String stubMode, int expectedStatusCode, String expectedCategory) {}
}

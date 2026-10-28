/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.model_timeout_tiering.fixture;

import com.huawei.ascend.sit.cases.integration.model_timeout_tiering.fixture.SutLogTailer.TimeoutPhase;
import com.huawei.ascend.sit.cases.integration.model_timeout_tiering.fixture.TimeoutProbeHttpClient.QueryResponse;

import java.util.Objects;

/**
 * Structural assertions over the FEAT-058 error envelope on HTTP response body.
 *
 * <p>FEAT-058 §2 MUST#10 + MUST#12 require that every timeout error on the
 * HTTP surface carries a {@code details} object with four fields:
 * <ul>
 *   <li>{@code phase} — one of {@code BEFORE_CONNECT} / {@code BEFORE_FIRST_SEGMENT}
 *       / {@code AFTER_FIRST_SEGMENT};
 *   <li>{@code timeout_seconds} — the effective timeout in seconds (double);
 *   <li>{@code layer} — one of {@code connect} / {@code header_wait} /
 *       {@code stream_idle};
 *   <li>{@code stream} — boolean, {@code true} for streaming paths,
 *       {@code false} for blocking paths.
 * </ul>
 *
 * <p>And the error envelope SDK status code is
 * {@code StatusCode.MODEL_CALL_TIMEOUT(181008)}.
 *
 * <p>This asserter wraps a {@link QueryResponse} and provides chainable
 * assertions with contextual AssertionError messages that include the full
 * raw body for diagnosis. Each method returns {@code this} to allow chaining.
 */
public final class ErrorEnvelopeAsserter {

    public static final int MODEL_CALL_TIMEOUT_CODE = 181008;

    public static final String LAYER_CONNECT = "connect";
    public static final String LAYER_HEADER_WAIT = "header_wait";
    public static final String LAYER_STREAM_IDLE = "stream_idle";

    /** FEAT-064 four new error codes + the two reused ones (181001 generic, 181008 timeout). */
    public static final int MODEL_CALL_RATE_LIMITED = 181009;
    public static final int MODEL_CALL_QUOTA_EXCEEDED = 181010;
    public static final int MODEL_CALL_AUTH_FAILED = 181011;
    public static final int MODEL_CALL_CONTEXT_LENGTH_EXCEEDED = 181012;
    public static final int MODEL_CALL_ERROR = 181001;

    /** FEAT-064 {@code ModelErrorCategory} enum values present on wire. */
    public static final String CATEGORY_RATE_LIMITED = "RATE_LIMITED";
    public static final String CATEGORY_QUOTA_EXCEEDED = "QUOTA_EXCEEDED";
    public static final String CATEGORY_AUTH_FAILED = "AUTH_FAILED";
    public static final String CATEGORY_TEMPORARY = "TEMPORARY";
    public static final String CATEGORY_CONTEXT_LENGTH_EXCEEDED = "CONTEXT_LENGTH_EXCEEDED";

    /**
     * FEAT-064 r6: {@code reason} enum values on T3 terminal log — landed via
     * agent-core-java PR!449 (2026-10-27). Shape of T3 line:
     * {@code "Model call failed. {category=<CAT>, reason=<REASON>}"}.
     */
    public static final String REASON_PERMANENT_ERROR = "PERMANENT_ERROR";
    public static final String REASON_RETRY_BUDGET_EXHAUSTED = "RETRY_BUDGET_EXHAUSTED";
    public static final String REASON_RETRY_DISABLED_BY_SWITCH = "RETRY_DISABLED_BY_SWITCH";
    public static final String REASON_RETRY_BUDGET_ZERO = "RETRY_BUDGET_ZERO";

    private final QueryResponse response;

    public ErrorEnvelopeAsserter(QueryResponse response) {
        this.response = Objects.requireNonNull(response, "response");
    }

    public QueryResponse response() {
        return response;
    }

    public ErrorEnvelopeAsserter assertTimeoutStatusCode() {
        return assertStatusCode(MODEL_CALL_TIMEOUT_CODE);
    }

    /**
     * FEAT-064 wire-layer: assert the error envelope SDK status code equals
     * {@code expectedStatusCode}. Covers 181009/181010/181011/181012 (new) and
     * 181001 (reused) in addition to 181008 (via {@link #assertTimeoutStatusCode()}).
     */
    public ErrorEnvelopeAsserter assertStatusCode(int expectedStatusCode) {
        if (response.errorStatusCode() == null) {
            throw new AssertionError(
                    "Expected error envelope with status_code=" + expectedStatusCode
                            + " but errorStatusCode was null"
                            + "\n--- raw body ---\n" + response.rawBody());
        }
        if (response.errorStatusCode() != expectedStatusCode) {
            throw new AssertionError(
                    "Expected error status_code=" + expectedStatusCode
                            + " but got " + response.errorStatusCode()
                            + "\n--- raw body ---\n" + response.rawBody());
        }
        return this;
    }

    /**
     * FEAT-064 details.category contract: every classified error must carry a
     * {@code category} string field inside the {@code details} object with
     * value ∈ {RATE_LIMITED, QUOTA_EXCEEDED, AUTH_FAILED, TEMPORARY,
     * CONTEXT_LENGTH_EXCEEDED, USER_CANCELLED}.
     */
    public ErrorEnvelopeAsserter assertCategory(String expectedCategory) {
        String actual = response.detailsString("category");
        if (actual == null) {
            throw new AssertionError("Expected details.category=" + expectedCategory
                    + " but field missing"
                    + "\n--- raw body ---\n" + response.rawBody());
        }
        if (!actual.equals(expectedCategory)) {
            throw new AssertionError("Expected details.category=" + expectedCategory
                    + " but got " + actual
                    + "\n--- raw body ---\n" + response.rawBody());
        }
        return this;
    }

    /**
     * FEAT-064 retry-log count assertion. Uses {@link SutLogTailer#countOfRetryLog()}
     * to count {@code "Model call will retry."} lines emitted by
     * {@code ModelCallRetrier} and compares against {@code expected}.
     */
    public ErrorEnvelopeAsserter assertRetryLogCount(SutLogTailer tailer, int expected) {
        long actual;
        try {
            actual = tailer.countOfRetryLog();
        } catch (java.io.IOException e) {
            throw new AssertionError("Failed to read SUT log at " + tailer.logFile(), e);
        }
        if (actual != expected) {
            throw new AssertionError("Expected 'Model call will retry.' log count=" + expected
                    + " but got " + actual
                    + "\n--- log tail (last 80 lines) ---\n"
                    + TimeoutDemoLauncher.tail(tailer.logFile(), 80));
        }
        return this;
    }

    /**
     * FEAT-064 no-retry assertion for permanent / first-segment-boundary TCs.
     * Equivalent to {@code assertRetryLogCount(tailer, 0)} with a tailored
     * message that highlights the short-circuit expectation.
     */
    public ErrorEnvelopeAsserter assertNoRetryLog(SutLogTailer tailer) {
        long actual;
        try {
            actual = tailer.countOfRetryLog();
        } catch (java.io.IOException e) {
            throw new AssertionError("Failed to read SUT log at " + tailer.logFile(), e);
        }
        if (actual != 0) {
            throw new AssertionError("Expected zero 'Model call will retry.' lines "
                    + "(permanent error / first-segment boundary) but got " + actual
                    + "\n--- log tail (last 80 lines) ---\n"
                    + TimeoutDemoLauncher.tail(tailer.logFile(), 80));
        }
        return this;
    }

    /**
     * FEAT-064 r6: T3 terminal failure log strong-eq assertion — landed via
     * agent-core-java PR!449 (2026-10-27). Asserts exactly {@code expected}
     * log lines matching {@code "Model call failed. {category=<CAT>,
     * reason=<REASON>}"} with the given category + reason pair.
     *
     * <p>Used for:
     * <ul>
     *   <li>permanent-class strong-eq 1: QUOTA_EXCEEDED / AUTH_FAILED /
     *       CONTEXT_LENGTH_EXCEEDED, reason=PERMANENT_ERROR;
     *   <li>retryable-exhausted strong-eq 1: TEMPORARY / RATE_LIMITED,
     *       reason=RETRY_BUDGET_EXHAUSTED;
     *   <li>switch-off strong-eq 1: reason=RETRY_DISABLED_BY_SWITCH;
     *   <li>budget-zero strong-eq 1: reason=RETRY_BUDGET_ZERO.
     * </ul>
     */
    public ErrorEnvelopeAsserter assertTerminalLogCategory(SutLogTailer tailer,
            String category, String reason, int expected) {
        long actual;
        try {
            actual = tailer.countOfTerminalFailureLog(category, reason);
        } catch (java.io.IOException e) {
            throw new AssertionError("Failed to read SUT log at " + tailer.logFile(), e);
        }
        if (actual != expected) {
            throw new AssertionError("Expected T3 'Model call failed.' log count="
                    + expected + " for category=" + category + ", reason=" + reason
                    + " but got " + actual
                    + "\n--- log tail (last 80 lines) ---\n"
                    + TimeoutDemoLauncher.tail(tailer.logFile(), 80));
        }
        return this;
    }

    /**
     * FEAT-064 wire-level stub call count assertion. Reads {@code GET /__stats}
     * from the llm-stub-server side channel and asserts the hit count for the
     * given mode equals {@code expected}. This disambiguates inner Retrier
     * retries (which emit {@code "Model call will retry."} log lines) from
     * outer Rail guardrail retries (which do NOT emit that log line but still
     * issue a fresh wire call).
     *
     * <p>Example two-layer defaults for persistent RATE_LIMITED/TEMPORARY:
     * {@code 6 stub calls / 4 retry logs} per {@code (2+1)×(1+1)=6}.
     *
     * @param stats stub-server stats client (bound to llm-stub-server base url)
     * @param mode stub mode name as recorded in {@code /__stats}
     *             (e.g. {@code rate-limited-persistent}, {@code server-5xx})
     * @param expected expected hit count
     */
    public ErrorEnvelopeAsserter assertStubCallCount(StubStatsClient stats, String mode, int expected) {
        int actual;
        try {
            actual = stats.callCount(mode);
        } catch (Exception e) {
            throw new AssertionError("Failed to read /__stats for mode=" + mode, e);
        }
        if (actual != expected) {
            throw new AssertionError("Expected stub /__stats[" + mode + "]=" + expected
                    + " but got " + actual);
        }
        return this;
    }

    public ErrorEnvelopeAsserter assertPhase(TimeoutPhase expected) {
        String actual = response.detailsString("phase");
        if (actual == null) {
            throw new AssertionError("Expected details.phase=" + expected
                    + " but field missing"
                    + "\n--- raw body ---\n" + response.rawBody());
        }
        if (!actual.equals(expected.name())) {
            throw new AssertionError("Expected details.phase=" + expected
                    + " but got " + actual
                    + "\n--- raw body ---\n" + response.rawBody());
        }
        return this;
    }

    public ErrorEnvelopeAsserter assertLayer(String expectedLayer) {
        String actual = response.detailsString("layer");
        if (actual == null) {
            throw new AssertionError("Expected details.layer=" + expectedLayer
                    + " but field missing"
                    + "\n--- raw body ---\n" + response.rawBody());
        }
        if (!actual.equals(expectedLayer)) {
            throw new AssertionError("Expected details.layer=" + expectedLayer
                    + " but got " + actual
                    + "\n--- raw body ---\n" + response.rawBody());
        }
        return this;
    }

    public ErrorEnvelopeAsserter assertTimeoutSeconds(double expected, double toleranceSeconds) {
        Double actual = response.detailsDouble("timeout_seconds");
        if (actual == null) {
            throw new AssertionError("Expected details.timeout_seconds=" + expected
                    + " (±" + toleranceSeconds + "s) but field missing"
                    + "\n--- raw body ---\n" + response.rawBody());
        }
        if (Math.abs(actual - expected) > toleranceSeconds) {
            throw new AssertionError("Expected details.timeout_seconds≈" + expected
                    + " (±" + toleranceSeconds + "s) but got " + actual
                    + "\n--- raw body ---\n" + response.rawBody());
        }
        return this;
    }

    public ErrorEnvelopeAsserter assertStream(boolean expected) {
        Boolean actual = response.detailsBoolean("stream");
        if (actual == null) {
            throw new AssertionError("Expected details.stream=" + expected
                    + " but field missing"
                    + "\n--- raw body ---\n" + response.rawBody());
        }
        if (actual != expected) {
            throw new AssertionError("Expected details.stream=" + expected
                    + " but got " + actual
                    + "\n--- raw body ---\n" + response.rawBody());
        }
        return this;
    }

    /**
     * MUST#12 aggregate: the four fields are each independently present. This
     * check does not verify values — pair with the per-field asserts.
     */
    public ErrorEnvelopeAsserter assertAllFourFieldsPresent() {
        if (response.errorDetails() == null) {
            throw new AssertionError(
                    "Expected error envelope details object but errorDetails was null"
                            + "\n--- raw body ---\n" + response.rawBody());
        }
        String[] required = {"phase", "timeout_seconds", "layer", "stream"};
        for (String key : required) {
            if (!response.errorDetails().containsKey(key)) {
                throw new AssertionError("Expected details." + key
                        + " present but missing. details=" + response.errorDetails()
                        + "\n--- raw body ---\n" + response.rawBody());
            }
        }
        return this;
    }

    /**
     * Classification per cases §5 iterated-version rules:
     * <ul>
     *   <li>four fields present + phase/layer match → PASS;
     *   <li>error envelope entirely missing (HTTP 2xx answer surface) → FAIL
     *       (timeout TCs expect a timeout, not an answer);
     *   <li>HTTP non-2xx but no 181008 code / details fields missing → FAIL;
     *   <li>caller aborted mid-flight → INCONCLUSIVE (test setup problem).
     * </ul>
     */
    public Verdict classify(TimeoutPhase expectedPhase, String expectedLayer) {
        if (response.callerAborted()) {
            return new Verdict(VerdictKind.INCONCLUSIVE,
                    "caller aborted mid-flight: " + response.callerAbortReason());
        }
        if (response.errorStatusCode() == null
                || response.errorStatusCode() != MODEL_CALL_TIMEOUT_CODE) {
            return new Verdict(VerdictKind.FAIL,
                    "expected 181008 timeout envelope; got status_code="
                            + response.errorStatusCode()
                            + ", resultType=" + response.resultType());
        }
        if (response.errorDetails() == null) {
            return new Verdict(VerdictKind.FAIL,
                    "181008 envelope present but details object missing");
        }
        String actualPhase = response.detailsString("phase");
        if (actualPhase == null || !actualPhase.equals(expectedPhase.name())) {
            return new Verdict(VerdictKind.FAIL,
                    "expected details.phase=" + expectedPhase
                            + " but got " + actualPhase);
        }
        String actualLayer = response.detailsString("layer");
        if (actualLayer == null || !actualLayer.equals(expectedLayer)) {
            return new Verdict(VerdictKind.FAIL,
                    "expected details.layer=" + expectedLayer
                            + " but got " + actualLayer);
        }
        return new Verdict(VerdictKind.PASS, "phase=" + expectedPhase
                + ", layer=" + expectedLayer
                + ", timeout_seconds=" + response.detailsDouble("timeout_seconds")
                + ", stream=" + response.detailsBoolean("stream"));
    }

    public enum VerdictKind { PASS, FAIL, INCONCLUSIVE }

    public record Verdict(VerdictKind kind, String reason) {}
}

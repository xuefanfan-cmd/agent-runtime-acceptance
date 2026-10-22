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

    private final QueryResponse response;

    public ErrorEnvelopeAsserter(QueryResponse response) {
        this.response = Objects.requireNonNull(response, "response");
    }

    public QueryResponse response() {
        return response;
    }

    public ErrorEnvelopeAsserter assertTimeoutStatusCode() {
        if (response.errorStatusCode() == null) {
            throw new AssertionError(
                    "Expected error envelope with status_code=" + MODEL_CALL_TIMEOUT_CODE
                            + " but errorStatusCode was null"
                            + "\n--- raw body ---\n" + response.rawBody());
        }
        if (response.errorStatusCode() != MODEL_CALL_TIMEOUT_CODE) {
            throw new AssertionError(
                    "Expected error status_code=" + MODEL_CALL_TIMEOUT_CODE
                            + " but got " + response.errorStatusCode()
                            + "\n--- raw body ---\n" + response.rawBody());
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

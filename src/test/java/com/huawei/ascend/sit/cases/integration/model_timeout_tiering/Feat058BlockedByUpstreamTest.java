/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.model_timeout_tiering;

import com.huawei.ascend.sit.cases.integration.model_timeout_tiering.fixture.ErrorEnvelopeAsserter;
import com.huawei.ascend.sit.cases.integration.model_timeout_tiering.fixture.SutLogTailer;
import com.huawei.ascend.sit.cases.integration.model_timeout_tiering.fixture.SutLogTailer.TimeoutPhase;
import com.huawei.ascend.sit.cases.integration.model_timeout_tiering.fixture.TimeoutDemoLauncher;
import com.huawei.ascend.sit.cases.integration.model_timeout_tiering.fixture.TimeoutProbeHttpClient;
import com.huawei.ascend.sit.cases.integration.model_timeout_tiering.fixture.TimeoutProbeHttpClient.QueryResponse;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-058 — model-call three-layer timeout tiering, TCs blocked by upstream
 * bug B1 ({@code OpenAIModelClient.buildRequest} wires
 * {@code HttpRequest.timeout()} as total response time, not header-wait only).
 *
 * <p>Each TC here corresponds to a cases §4 row with
 * {@code status=blocked_by_upstream}:
 * <ul>
 *   <li>TC-AFTER-FIRST-SEGMENT-IDLE (§3.4) — MUST#3 / MUST#5
 *   <li>TC-NO-TOTAL-CAP-STREAM (§3.5) — MUST#4
 *   <li>TC-PER-CALL-IDLE-OVERRIDE (§3.9) — MUST#7
 * </ul>
 *
 * <p>Methods below are implemented but annotated {@link Disabled} because the
 * upstream B1 bug currently masks the {@code AFTER_FIRST_SEGMENT} phase on
 * which these three TCs depend. The implementation is kept in-tree (not in
 * a {@code .disabled} filename) so that once the upstream PR lands, the
 * {@code @Disabled} line is a single-line removal and the TC flips to
 * runnable without re-scaffolding.
 *
 * <p>Reference upstream issue draft: see cases §4 "Known upstream block B1".
 */
@Tag("integration")
@Tag("feat-058")
@Tag("slow")
@Tag("manual")
@Tag("blocked")
final class Feat058BlockedByUpstreamTest {

    private static final Duration IDLE_INVOKE_TIMEOUT = Duration.ofSeconds(400);
    private static final Duration LONG_STREAM_INVOKE_TIMEOUT = Duration.ofSeconds(60);
    private static final String PROMPT = "ping";

    /**
     * TC-AFTER-FIRST-SEGMENT-IDLE (cases §3.4). s3 profile,
     * {@code stream-idle-seconds=300s}, stub mode {@code after-first-segment-idle}
     * holds silence for 310s after the first chunk.
     * Expect: 181008 at phase={@code AFTER_FIRST_SEGMENT},
     * layer={@code stream_idle}, timeout_seconds≈300, stream=true.
     *
     * <p>2026-10-27 unblocked: HANDOFF revised B1 attribution — the previously
     * suspected upstream bug was actually a local JDK 24+ environment mismatch
     * (本机 JDK 26 下 HttpClient 行为漂移), not a code bug. On JDK 17 / 21
     * conforming SIT environment this TC is expected to PASS. UPSTREAM-ISSUE-DRAFT
     * is RETRACTED.
     */
    @Test
    void afterFirstSegmentIdleIndependentOfHeaderWait() throws Exception {
        assumeFatJarsPresent();

        Map<String, String> stubEnv = commonEnv();
        stubEnv.put("LLM_STUB_DEFAULT_MODE", "after-first-segment-idle");
        try (TimeoutDemoLauncher stub = TimeoutDemoLauncher.llmStubServer()
                .envAll(stubEnv)
                .build()
                .start();
             TimeoutDemoLauncher probe = TimeoutDemoLauncher.probeAgent()
                     .profile("s3")
                     .envAll(probeEnv(stub))
                     .build()
                     .start()) {

            TimeoutProbeHttpClient http = new TimeoutProbeHttpClient(probe.baseUrl());
            QueryResponse resp = http.query(PROMPT, IDLE_INVOKE_TIMEOUT);

            new ErrorEnvelopeAsserter(resp)
                    .assertTimeoutStatusCode()
                    .assertAllFourFieldsPresent()
                    .assertPhase(TimeoutPhase.AFTER_FIRST_SEGMENT)
                    .assertLayer(ErrorEnvelopeAsserter.LAYER_STREAM_IDLE)
                    .assertTimeoutSeconds(300.0, 1.0)
                    .assertStream(true);

            SutLogTailer tailer = new SutLogTailer(probe.logFile());
            assertThat(tailer.countOfPhase(TimeoutPhase.AFTER_FIRST_SEGMENT))
                    .as("exactly one AFTER_FIRST_SEGMENT 181008 line expected")
                    .isEqualTo(1);
            assertThat(tailer.countOfPhase(TimeoutPhase.BEFORE_CONNECT)).isZero();
        }
    }

    /**
     * TC-NO-TOTAL-CAP-STREAM (cases §3.5). s4 profile, legacy {@code timeout=10s},
     * stub mode {@code long-answer-heartbeat} emits a chunk every 1.5s for 15s
     * total.
     * Expect: HTTP 200 + {@code result_type=answer}, zero {@code [181008]}
     * lines, stream completes naturally.
     *
     * <p>2026-10-27 unblocked: HANDOFF revised B1 attribution — the previously
     * suspected upstream bug was actually a local JDK 24+ environment mismatch,
     * not a code bug. On JDK 17 / 21 conforming SIT environment the stream
     * completes naturally without hitting the legacy timeout. UPSTREAM-ISSUE-DRAFT
     * is RETRACTED.
     */
    @Test
    void longAnswerStreamNotCappedByTotalTime() throws Exception {
        assumeFatJarsPresent();

        Map<String, String> stubEnv = commonEnv();
        stubEnv.put("LLM_STUB_DEFAULT_MODE", "long-answer-heartbeat");
        try (TimeoutDemoLauncher stub = TimeoutDemoLauncher.llmStubServer()
                .envAll(stubEnv)
                .build()
                .start();
             TimeoutDemoLauncher probe = TimeoutDemoLauncher.probeAgent()
                     .profile("s4")
                     .envAll(probeEnv(stub))
                     .build()
                     .start()) {

            TimeoutProbeHttpClient http = new TimeoutProbeHttpClient(probe.baseUrl());
            QueryResponse resp = http.query(PROMPT, LONG_STREAM_INVOKE_TIMEOUT);

            assertThat(resp.statusCode())
                    .as("HTTP status for long-answer-heartbeat stream")
                    .isEqualTo(200);
            assertThat(resp.resultType())
                    .as("stream completed naturally, result_type=answer")
                    .isEqualTo("answer");

            SutLogTailer tailer = new SutLogTailer(probe.logFile());
            assertThat(tailer.totalTimeoutCount())
                    .as("no 181008 lines on natural stream completion")
                    .isZero();
        }
    }

    /**
     * TC-PER-CALL-IDLE-OVERRIDE (cases §3.9). s3-per-call profile (needs demo
     * side or SIT yaml override): client-level {@code stream-idle-seconds=600s}
     * + per-call {@code ModelInvokeOptions.timeout=300s} on the first call
     * only. Stub mode {@code after-first-segment-idle} holds silence 310s
     * after first chunk.
     *
     * <p>Expect (after B1 fix):
     * <ul>
     *   <li>First call (with per-call override=300s): 181008 at
     *       {@code timeout_seconds≈300} — override wins over client-level 600s;
     *   <li>Second call (without override): HTTP 200 + {@code result_type=answer}
     *       — client-level 600s window exceeds 310s stall, request completes
     *       naturally; proves per-call override released, client config not
     *       polluted.
     * </ul>
     *
     * <p>Original design overlaid per-call {@code ModelInvokeOptions.timeout}
     * with agent-level {@code ReActAgentConfig.modelStreamIdleSeconds} in the
     * same TC — the per-call-release-vs-persistent-override ambiguity is
     * removed in r1 by narrowing this TC to the per-call layer only (agent-level
     * persistent-field validation is a UT responsibility).
     */
    @Disabled("blocked_by_upstream B1 + awaits demo-side s3-per-call profile; see cases §3.9")
    @Test
    void perCallIdleOverrideDoesNotPolluteClientConfig() throws Exception {
        assumeFatJarsPresent();

        Map<String, String> stubEnv = commonEnv();
        stubEnv.put("LLM_STUB_DEFAULT_MODE", "after-first-segment-idle");
        try (TimeoutDemoLauncher stub = TimeoutDemoLauncher.llmStubServer()
                .envAll(stubEnv)
                .build()
                .start();
             TimeoutDemoLauncher probe = TimeoutDemoLauncher.probeAgent()
                     .profile("s3-per-call")
                     .envAll(probeEnv(stub))
                     .build()
                     .start()) {

            TimeoutProbeHttpClient http = new TimeoutProbeHttpClient(probe.baseUrl());

            // First call: per-call override=300s wins over client-level 600s.
            // Stub stalls 310s > 300s → trips at 300s with timeout envelope.
            QueryResponse first = http.query(PROMPT, IDLE_INVOKE_TIMEOUT);
            new ErrorEnvelopeAsserter(first)
                    .assertTimeoutStatusCode()
                    .assertAllFourFieldsPresent()
                    .assertPhase(TimeoutPhase.AFTER_FIRST_SEGMENT)
                    .assertLayer(ErrorEnvelopeAsserter.LAYER_STREAM_IDLE)
                    .assertTimeoutSeconds(300.0, 1.0)
                    .assertStream(true);

            // Second call: no override, client-level 600s window applies.
            // Stub stalls 310s < 600s → completes naturally, no 181008.
            // (This is the key assertion: per-call didn't pollute client config.)
            QueryResponse second = http.query(PROMPT, IDLE_INVOKE_TIMEOUT);
            assertThat(second.statusCode())
                    .as("second call must complete under client-level 600s window")
                    .isEqualTo(200);
            assertThat(second.resultType())
                    .as("second call result_type=answer (natural completion)")
                    .isEqualTo("answer");

            SutLogTailer tailer = new SutLogTailer(probe.logFile());
            assertThat(tailer.totalTimeoutCount())
                    .as("exactly one 181008 line (from first call only)")
                    .isEqualTo(1);
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

    private static Map<String, String> probeEnv(TimeoutDemoLauncher stub) {
        Map<String, String> env = commonEnv();
        env.put("LLM_PROVIDER", "OpenAI");
        env.put("LLM_API_KEY", "stub-key");
        env.put("LLM_MODEL", "stub-model");
        env.put("LLM_SSL_VERIFY", "false");
        env.put("LLM_TEMPERATURE", "0.0");
        env.put("LLM_API_BASE", stub.baseUrl());
        return env;
    }
}

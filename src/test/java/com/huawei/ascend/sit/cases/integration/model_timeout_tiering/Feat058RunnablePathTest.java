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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-058 — model-call three-layer timeout tiering, runnable-path TCs.
 *
 * <p>Covers the 8 TCs with {@code status=runnable} in
 * docs/cases/FEAT-058-model-call-timeout-tiering.md §4 status table:
 * <ul>
 *   <li>TC-DEFAULT-BASELINE (§3.1)
 *   <li>TC-CONNECT-TIMEOUT (§3.2)
 *   <li>TC-HEADER-WAIT-LEGACY (§3.3)
 *   <li>TC-BLOCKING-SAME-SEMANTICS (§3.6)
 *   <li>TC-CANCEL-NOT-TIMEOUT (§3.7)
 *   <li>TC-ERROR-DETAILS-PAYLOAD (§3.8) — aggregates three preceding traces
 *   <li>TC-RANGE-VALIDATION (§3.10)
 *   <li>TC-UNIFIED-STACK-COVERAGE (§3.11)
 * </ul>
 *
 * <p>Each TC launches the {@code timeout-probe-agent} fat jar (and, where
 * applicable, the {@code llm-stub-server} fat jar) under its scenario profile,
 * drives one or more {@code POST /v1/query} calls, and asserts on the
 * {@code [181008]} log lines + {@code details.{phase, timeout_seconds, layer, stream}}
 * payload fields.
 *
 * <p>Preconditions (all expressed as {@link Assumptions#assumeTrue} — missing
 * precondition → test SKIPPED, not FAILED):
 * <ul>
 *   <li>{@code timeout-probe-agent-0.1.0.jar} + {@code llm-stub-server-0.1.0.jar}
 *       exist at the dev-side build location, or overridden via
 *       {@code -Dfeat058.probe-jar=...} / {@code -Dfeat058.stub-jar=...}.
 *   <li>Corporate proxy is unset ({@code unset HTTP_PROXY HTTPS_PROXY}) — the
 *       demo stub is on 127.0.0.1:18094 loopback.
 *   <li>Ports 18092 (probe) + 18094 (stub) are free.
 * </ul>
 *
 * <p>The three {@code blocked_by_upstream B1} TCs are in a separate class
 * ({@code Feat058BlockedByUpstreamTest}) all marked {@link org.junit.jupiter.api.Disabled}
 * pending the upstream {@code OpenAIModelClient.buildRequest HttpRequest.timeout}
 * wire-up PR.
 */
@Tag("integration")
@Tag("feat-058")
@Tag("slow")
@Tag("manual")
final class Feat058RunnablePathTest {

    private static final Duration DEFAULT_INVOKE_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration LONG_INVOKE_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration CANCEL_INVOKE_TIMEOUT = Duration.ofSeconds(3);

    private static final String PROMPT = "ping";

    /**
     * TC-DEFAULT-BASELINE (cases §3.1). s0 profile + {@code ok} stub mode.
     * Expect: HTTP 200, {@code result_type=answer}, zero 181008 log lines.
     */
    @Test
    void defaultBaselineHasNoTimeoutAndReturnsAnswer() throws Exception {
        assumeFatJarsPresent();

        Map<String, String> stubEnv = commonEnv();
        stubEnv.put("LLM_STUB_DEFAULT_MODE", "ok");
        try (TimeoutDemoLauncher stub = TimeoutDemoLauncher.llmStubServer()
                .envAll(stubEnv)
                .build()
                .start();
             TimeoutDemoLauncher probe = TimeoutDemoLauncher.probeAgent()
                     .profile("s0")
                     .envAll(probeEnv(stub))
                     .build()
                     .start()) {

            TimeoutProbeHttpClient http = new TimeoutProbeHttpClient(probe.baseUrl());
            QueryResponse resp = http.query(PROMPT, DEFAULT_INVOKE_TIMEOUT);
            SutLogTailer tailer = new SutLogTailer(probe.logFile());

            assertThat(resp.statusCode())
                    .as("HTTP status for s0 happy path")
                    .isEqualTo(200);
            assertThat(resp.resultType())
                    .as("result_type for s0 happy path")
                    .isEqualTo("answer");
            assertThat(tailer.totalTimeoutCount())
                    .as("181008 lines must be absent on s0 happy path")
                    .isZero();
        }
    }

    /**
     * TC-CONNECT-TIMEOUT (cases §3.2). s1 profile, {@code connect-timeout=5s},
     * api-base pointed to TEST-NET-1 black hole {@code 192.0.2.1:81}.
     * Expect: 181008 at phase={@code BEFORE_CONNECT}, layer={@code connect},
     * timeout_seconds≈5.
     */
    @Test
    void connectLayerTimeoutIndependent() throws Exception {
        assumeFatJarsPresent();

        String unreachable = System.getenv().getOrDefault(
                "LLM_API_BASE_UNREACHABLE", "http://192.0.2.1:81");

        Map<String, String> probeEnvMap = baseProbeEnv();
        probeEnvMap.put("LLM_API_BASE", unreachable);
        try (TimeoutDemoLauncher probe = TimeoutDemoLauncher.probeAgent()
                .profile("s1")
                .envAll(probeEnvMap)
                .build()
                .start()) {

            TimeoutProbeHttpClient http = new TimeoutProbeHttpClient(probe.baseUrl());
            QueryResponse resp = http.query(PROMPT, DEFAULT_INVOKE_TIMEOUT);

            ErrorEnvelopeAsserter asserter = new ErrorEnvelopeAsserter(resp)
                    .assertTimeoutStatusCode()
                    .assertAllFourFieldsPresent()
                    .assertPhase(TimeoutPhase.BEFORE_CONNECT)
                    .assertLayer(ErrorEnvelopeAsserter.LAYER_CONNECT)
                    .assertTimeoutSeconds(5.0, 0.5);

            SutLogTailer tailer = new SutLogTailer(probe.logFile());
            assertThat(tailer.countOfPhase(TimeoutPhase.BEFORE_CONNECT))
                    .as("exactly one BEFORE_CONNECT 181008 line expected")
                    .isEqualTo(1);
            assertThat(tailer.countOfPhase(TimeoutPhase.BEFORE_FIRST_SEGMENT))
                    .as("no BEFORE_FIRST_SEGMENT when connect layer trips")
                    .isZero();
            assertThat(tailer.countOfPhase(TimeoutPhase.AFTER_FIRST_SEGMENT))
                    .as("no AFTER_FIRST_SEGMENT when connect layer trips")
                    .isZero();
            // asserter.response() surfaced for diagnostic use in CI logs
            assertThat(asserter.response().rawBody()).isNotBlank();
        }
    }

    /**
     * TC-HEADER-WAIT-LEGACY (cases §3.3). s2 profile, legacy {@code timeout=8s},
     * stub mode {@code header-wait-silent}.
     * Expect: 181008 at phase={@code BEFORE_FIRST_SEGMENT}, layer={@code header_wait},
     * timeout_seconds≈8.
     */
    @Test
    void headerWaitLegacyPathEmitsBeforeFirstSegment() throws Exception {
        assumeFatJarsPresent();

        Map<String, String> stubEnv = commonEnv();
        stubEnv.put("LLM_STUB_DEFAULT_MODE", "header-wait-silent");
        try (TimeoutDemoLauncher stub = TimeoutDemoLauncher.llmStubServer()
                .envAll(stubEnv)
                .build()
                .start();
             TimeoutDemoLauncher probe = TimeoutDemoLauncher.probeAgent()
                     .profile("s2")
                     .envAll(probeEnv(stub))
                     .build()
                     .start()) {

            TimeoutProbeHttpClient http = new TimeoutProbeHttpClient(probe.baseUrl());
            QueryResponse resp = http.query(PROMPT, DEFAULT_INVOKE_TIMEOUT);

            new ErrorEnvelopeAsserter(resp)
                    .assertTimeoutStatusCode()
                    .assertAllFourFieldsPresent()
                    .assertPhase(TimeoutPhase.BEFORE_FIRST_SEGMENT)
                    .assertLayer(ErrorEnvelopeAsserter.LAYER_HEADER_WAIT)
                    .assertTimeoutSeconds(8.0, 0.5);

            SutLogTailer tailer = new SutLogTailer(probe.logFile());
            assertThat(tailer.countOfPhase(TimeoutPhase.BEFORE_FIRST_SEGMENT))
                    .as("exactly one BEFORE_FIRST_SEGMENT 181008 line expected")
                    .isEqualTo(1);
            assertThat(tailer.countOfPhase(TimeoutPhase.BEFORE_CONNECT)).isZero();
        }
    }

    /**
     * TC-BLOCKING-SAME-SEMANTICS (cases §3.6). s5 profile, legacy {@code timeout=8s},
     * stub mode {@code idle-silent-blocking} (blocking path).
     * Expect: 181008 at phase={@code BEFORE_FIRST_SEGMENT}, layer={@code header_wait},
     * timeout_seconds≈8, stream=false.
     */
    @Test
    void blockingPathSameTieringSemantics() throws Exception {
        assumeFatJarsPresent();

        Map<String, String> stubEnv = commonEnv();
        stubEnv.put("LLM_STUB_DEFAULT_MODE", "idle-silent-blocking");
        try (TimeoutDemoLauncher stub = TimeoutDemoLauncher.llmStubServer()
                .envAll(stubEnv)
                .build()
                .start();
             TimeoutDemoLauncher probe = TimeoutDemoLauncher.probeAgent()
                     .profile("s5")
                     .envAll(probeEnv(stub))
                     .build()
                     .start()) {

            TimeoutProbeHttpClient http = new TimeoutProbeHttpClient(probe.baseUrl());
            QueryResponse resp = http.query(PROMPT, DEFAULT_INVOKE_TIMEOUT);

            new ErrorEnvelopeAsserter(resp)
                    .assertTimeoutStatusCode()
                    .assertAllFourFieldsPresent()
                    .assertPhase(TimeoutPhase.BEFORE_FIRST_SEGMENT)
                    .assertLayer(ErrorEnvelopeAsserter.LAYER_HEADER_WAIT)
                    .assertTimeoutSeconds(8.0, 0.5)
                    .assertStream(false);
        }
    }

    /**
     * TC-CANCEL-NOT-TIMEOUT (cases §3.7). s6 profile, stub mode
     * {@code long-hold-for-cancel}, caller aborts after 3s.
     * Expect: driver sees HttpTimeoutException (callerAborted=true); SUT log
     * has zero 181008 lines (cancel is not classified as timeout).
     *
     * <p><b>Weak-layer regression</b> — SUT's internal idle window is 600s
     * (s6 default) and this test only observes a 2–3s window, so "no 181008"
     * is vacuously true within the observation window. MUST#11 "cancel vs
     * timeout" judgment power rests with UT ({@code OpenAIModelTimeoutTieringTest}
     * + {@code ModelCircuitBreakerTest} covering
     * {@code ModelCircuitBreaker.isConnectFailure} internal branches). This
     * SIT TC only guards against a hard regression where cancel is
     * misclassified AND 181008 is emitted immediately.
     */
    @Test
    void callerCancelIsNotTimeout() throws Exception {
        assumeFatJarsPresent();

        Map<String, String> stubEnv = commonEnv();
        stubEnv.put("LLM_STUB_DEFAULT_MODE", "long-hold-for-cancel");
        try (TimeoutDemoLauncher stub = TimeoutDemoLauncher.llmStubServer()
                .envAll(stubEnv)
                .build()
                .start();
             TimeoutDemoLauncher probe = TimeoutDemoLauncher.probeAgent()
                     .profile("s6")
                     .envAll(probeEnv(stub))
                     .build()
                     .start()) {

            TimeoutProbeHttpClient http = new TimeoutProbeHttpClient(probe.baseUrl());
            QueryResponse resp = http.query(PROMPT, CANCEL_INVOKE_TIMEOUT);

            assertThat(resp.callerAborted())
                    .as("driver side should receive HttpTimeoutException on 3s abort")
                    .isTrue();

            // Allow a small window for the SUT to log any stray 181008 if cancel
            // were misclassified. 2s is well below the probe-agent default header-wait.
            Thread.sleep(2_000);

            SutLogTailer tailer = new SutLogTailer(probe.logFile());
            assertThat(tailer.hasNoTimeout())
                    .as("cancel must not emit [181008] in SUT log")
                    .isTrue();
        }
    }

    /**
     * TC-ERROR-DETAILS-PAYLOAD (cases §3.8). Aggregates three traces across
     * TC-CONNECT-TIMEOUT + TC-HEADER-WAIT-LEGACY + TC-BLOCKING-SAME-SEMANTICS
     * and checks the four-field contract on each.
     *
     * <p>Executed inline here (rather than cross-referencing other tests) so
     * the assertion is self-contained; the overhead is three fat-jar launches
     * which the {@code slow} tag already defers to Nightly.
     */
    @Test
    void errorDetailsPayloadFourFieldsAcrossThreePhases() throws Exception {
        assumeFatJarsPresent();

        QueryResponse connectResp;
        QueryResponse headerResp;
        QueryResponse blockingResp;

        String unreachable = System.getenv().getOrDefault(
                "LLM_API_BASE_UNREACHABLE", "http://192.0.2.1:81");

        Map<String, String> connectProbeEnv = baseProbeEnv();
        connectProbeEnv.put("LLM_API_BASE", unreachable);
        try (TimeoutDemoLauncher probe = TimeoutDemoLauncher.probeAgent()
                .profile("s1")
                .envAll(connectProbeEnv)
                .build()
                .start()) {
            connectResp = new TimeoutProbeHttpClient(probe.baseUrl())
                    .query(PROMPT, DEFAULT_INVOKE_TIMEOUT);
        }

        Map<String, String> headerStubEnv = commonEnv();
        headerStubEnv.put("LLM_STUB_DEFAULT_MODE", "header-wait-silent");
        try (TimeoutDemoLauncher stub = TimeoutDemoLauncher.llmStubServer()
                .envAll(headerStubEnv)
                .build()
                .start();
             TimeoutDemoLauncher probe = TimeoutDemoLauncher.probeAgent()
                     .profile("s2")
                     .envAll(probeEnv(stub))
                     .build()
                     .start()) {
            headerResp = new TimeoutProbeHttpClient(probe.baseUrl())
                    .query(PROMPT, DEFAULT_INVOKE_TIMEOUT);
        }

        Map<String, String> blockingStubEnv = commonEnv();
        blockingStubEnv.put("LLM_STUB_DEFAULT_MODE", "idle-silent-blocking");
        try (TimeoutDemoLauncher stub = TimeoutDemoLauncher.llmStubServer()
                .envAll(blockingStubEnv)
                .build()
                .start();
             TimeoutDemoLauncher probe = TimeoutDemoLauncher.probeAgent()
                     .profile("s5")
                     .envAll(probeEnv(stub))
                     .build()
                     .start()) {
            blockingResp = new TimeoutProbeHttpClient(probe.baseUrl())
                    .query(PROMPT, DEFAULT_INVOKE_TIMEOUT);
        }

        new ErrorEnvelopeAsserter(connectResp).assertAllFourFieldsPresent()
                .assertPhase(TimeoutPhase.BEFORE_CONNECT)
                .assertLayer(ErrorEnvelopeAsserter.LAYER_CONNECT);
        new ErrorEnvelopeAsserter(headerResp).assertAllFourFieldsPresent()
                .assertPhase(TimeoutPhase.BEFORE_FIRST_SEGMENT)
                .assertLayer(ErrorEnvelopeAsserter.LAYER_HEADER_WAIT);
        new ErrorEnvelopeAsserter(blockingResp).assertAllFourFieldsPresent()
                .assertPhase(TimeoutPhase.BEFORE_FIRST_SEGMENT)
                .assertLayer(ErrorEnvelopeAsserter.LAYER_HEADER_WAIT)
                .assertStream(false);
    }

    /**
     * TC-RANGE-VALIDATION (cases §3.10). Launch probe-agent with an invalid
     * {@code connect-timeout=3s} (below the SDK hard range [5,15]s). Expect:
     * fat jar fails to come ready, exit code non-zero, log contains the
     * {@code ModelClientConfig$Builder.build()} range exception string.
     */
    @Test
    void rangeValidationRejectsOutOfRangeConnectTimeout() throws Exception {
        assumeFatJarsPresent();

        Map<String, String> probeEnvMap = baseProbeEnv();
        // Push the out-of-range value as a top-level JVM property override so the
        // probe-agent Spring binding fails early (the demo s0 profile uses the SDK
        // default; we inject OJ_LLM_CONNECT_TIMEOUT=3s which the demo application.yml
        // consumes as ${LLM_CONNECT_TIMEOUT:...} if wired, else the test is
        // INCONCLUSIVE because the demo didn't expose a connect-timeout env.
        probeEnvMap.put("LLM_CONNECT_TIMEOUT", "3s");
        try (TimeoutDemoLauncher probe = TimeoutDemoLauncher.probeAgent()
                .profile("s0")
                .envAll(probeEnvMap)
                .startupTimeout(Duration.ofSeconds(30))
                .build()) {
            probe.startExpectingFailure();
            assertThat(probe.isAlive())
                    .as("probe-agent must exit when connect-timeout is below [5,15]s")
                    .isFalse();
            assertThat(probe.exitValue())
                    .as("probe-agent exit code must be non-zero for config range violation")
                    .isNotZero();
            String tail = TimeoutDemoLauncher.tail(probe.logFile(), 200);
            assertThat(tail)
                    .as("startup failure log must mention ModelClientConfig range validation")
                    .containsAnyOf("ModelClientConfig", "connect", "range", "must be between");
        }
    }

    /**
     * TC-UNIFIED-STACK-COVERAGE (cases §3.11). Loop through the five
     * client subclasses sharing the same resolver: {@code OpenAI},
     * {@code OpenAiCompatible}, {@code DashScope}, {@code SiliconFlow},
     * {@code DeepSeek}. For each, drive s2 + header-wait-silent and assert
     * the four fields are literally equal across providers (apart from the
     * {@code provider} field itself if present).
     */
    @Test
    void unifiedStackCoverageAcrossClientSubclasses() throws Exception {
        assumeFatJarsPresent();

        List<String> providers = List.of(
                "OpenAI", "OpenAiCompatible", "DashScope", "SiliconFlow", "DeepSeek");

        String referencePhase = null;
        String referenceLayer = null;
        Double referenceTimeout = null;
        Boolean referenceStream = null;

        for (String provider : providers) {
            Map<String, String> stubEnv = commonEnv();
            stubEnv.put("LLM_STUB_DEFAULT_MODE", "header-wait-silent");
            Map<String, String> probeEnvMap;
            try (TimeoutDemoLauncher stub = TimeoutDemoLauncher.llmStubServer()
                    .envAll(stubEnv)
                    .build()
                    .start()) {
                probeEnvMap = probeEnv(stub);
                probeEnvMap.put("LLM_PROVIDER", provider);
                try (TimeoutDemoLauncher probe = TimeoutDemoLauncher.probeAgent()
                        .profile("s2")
                        .envAll(probeEnvMap)
                        .build()
                        .start()) {

                    QueryResponse resp = new TimeoutProbeHttpClient(probe.baseUrl())
                            .query(PROMPT, DEFAULT_INVOKE_TIMEOUT);
                    new ErrorEnvelopeAsserter(resp).assertAllFourFieldsPresent();
                    String phase = resp.detailsString("phase");
                    String layer = resp.detailsString("layer");
                    Double timeout = resp.detailsDouble("timeout_seconds");
                    Boolean stream = resp.detailsBoolean("stream");

                    if (referencePhase == null) {
                        referencePhase = phase;
                        referenceLayer = layer;
                        referenceTimeout = timeout;
                        referenceStream = stream;
                    } else {
                        assertThat(phase)
                                .as("phase must match across providers (ref=OpenAI, provider=" + provider + ")")
                                .isEqualTo(referencePhase);
                        assertThat(layer)
                                .as("layer must match across providers (provider=" + provider + ")")
                                .isEqualTo(referenceLayer);
                        assertThat(timeout)
                                .as("timeout_seconds must match across providers (provider=" + provider + ")")
                                .isEqualTo(referenceTimeout);
                        assertThat(stream)
                                .as("stream must match across providers (provider=" + provider + ")")
                                .isEqualTo(referenceStream);
                    }
                }
            }
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
        // stub 的 OpenAI 兼容端点在 /v1/chat/completions（LlmStubController:47），SDK 对 api-base
        // 直接拼 /chat/completions，裸 host 会撞 404。实证 2026-10-22 S2 场景 FAIL 后定位。
        env.put("LLM_API_BASE", stub.baseUrl() + "/v1");
        return env;
    }
}

/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.model_timeout_tiering;

import com.huawei.ascend.sit.cases.integration.model_timeout_tiering.fixture.ErrorEnvelopeAsserter;
import com.huawei.ascend.sit.cases.integration.model_timeout_tiering.fixture.SutLogTailer;
import com.huawei.ascend.sit.cases.integration.model_timeout_tiering.fixture.SutLogTailer.TimeoutPhase;
import com.huawei.ascend.sit.cases.integration.model_timeout_tiering.fixture.TimeoutDemoLauncher;
import com.huawei.ascend.sit.cases.integration.model_timeout_tiering.fixture.TimeoutProbeHttpClient;
import com.huawei.ascend.sit.cases.integration.model_timeout_tiering.fixture.TimeoutProbeHttpClient.BlockingResponse;
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
            assertThat(tailer.probeStarted())
                    .as("SUT must have started successfully (REQUIRED per demo asserter s0)")
                    .isTrue();
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
     * TC-BLOCKING-SAME-SEMANTICS (cases §3.6). s5 profile with
     * {@code stream-idle-seconds=300s} override (SDK lower bound), stub mode
     * {@code idle-silent-blocking} with 310s blocking-body idle.
     * Expect: 181008 at phase={@code BEFORE_FIRST_SEGMENT}, layer={@code stream_idle},
     * timeout_seconds≈300, stream=false.
     *
     * <p>2026-10-28 batch triage 议题三 路径 Y（正测试，证超窗必掐断）：MUST#9 窄义
     * 解读下，HTTP 响应头到齐后 header_wait 不再触发；"头到齐 → body 首字节"窗口
     * 归 stream_idle 层保护。stub 发 status 200 header 后沉默 310s，probe 应该在
     * 300s 时 stream_idle 层触发 timeout → 181008 at BEFORE_FIRST_SEGMENT with
     * layer=stream_idle（不是 header_wait）。
     *
     * <p>原路径 X 判据（layer=header_wait, timeout_seconds=8.0）记录于 2026-10-22
     * 对 MUST#9 的广义解读；经 2026-10-28 batch triage 议题三回帖裁定后改用窄义 +
     * 路径 Y 判据（layer=stream_idle, timeout_seconds=300.0）。
     */
    @Test
    void blockingPathSameTieringSemantics() throws Exception {
        assumeFatJarsPresent();

        Map<String, String> stubEnv = commonEnv();
        stubEnv.put("LLM_STUB_DEFAULT_MODE", "idle-silent-blocking");
        // 路径 Y：stub 沉默 310s（超过 stream_idle 300s 下限，触发 stream_idle 超时）
        stubEnv.put("LLM_STUB_BLOCKING_IDLE_MS", "310000");
        try (TimeoutDemoLauncher stub = TimeoutDemoLauncher.llmStubServer()
                .envAll(stubEnv)
                .build()
                .start();
             TimeoutDemoLauncher probe = TimeoutDemoLauncher.probeAgent()
                     .profile("s5")
                     .envAll(probeEnv(stub))
                     // 路径 Y：override stream-idle=300s（SDK 下限 [300,900]，必须带 s 单位否则 Spring 当 ms 解析）
                     .arg("--openjiuwen.service.llm.stream-idle-seconds=300s")
                     .build()
                     .start()) {

            TimeoutProbeHttpClient http = new TimeoutProbeHttpClient(probe.baseUrl());
            // driver wait ≥ 320s（stream_idle 310s + buffer），避免 driver 先关连接
            QueryResponse resp = http.query(PROMPT, Duration.ofSeconds(400));

            new ErrorEnvelopeAsserter(resp)
                    .assertTimeoutStatusCode()
                    .assertAllFourFieldsPresent()
                    .assertPhase(TimeoutPhase.BEFORE_FIRST_SEGMENT)
                    .assertLayer(ErrorEnvelopeAsserter.LAYER_STREAM_IDLE)
                    .assertTimeoutSeconds(300.0, 1.0)
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

            // 2026-10-22 判据对齐 demo asserter：MUST#11 的 SIT 侧 wire 观察锚**只在 SUT 端**——
            // 日志无 [181008] 即证 "cancel 不被归类为超时"；caller 端是否收到 HttpTimeoutException
            // 并非 MUST#11 要求（MUST#11 原文：上层主动取消不得归类为超时失败）。原 callerAborted
            // 断言依赖 HttpClient.request.timeout 对流式响应的行为（header 已到后 timeout 不再触发
            // abort），不可靠且与契约无关，删除；callerAborted/response 字段仅作诊断留档。
            // 对齐 demo scripts/trace-asserter.sh 的 s6 判据：REQUIRED=["Started TimeoutProbeAgent"]
            // + FORBIDDEN=["\\[181008\\]"]。
            @SuppressWarnings("unused")
            QueryResponse diagnostic = resp;

            // Allow a small window for the SUT to log any stray 181008 if cancel
            // were misclassified. 2s is well below the probe-agent default header-wait.
            Thread.sleep(2_000);

            SutLogTailer tailer = new SutLogTailer(probe.logFile());
            assertThat(tailer.probeStarted())
                    .as("SUT must have started successfully (REQUIRED per demo asserter s6)")
                    .isTrue();
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
        // 2026-10-28 batch triage 议题三 路径 Y：跟 blockingPathSameTieringSemantics 同步
        blockingStubEnv.put("LLM_STUB_BLOCKING_IDLE_MS", "310000");
        try (TimeoutDemoLauncher stub = TimeoutDemoLauncher.llmStubServer()
                .envAll(blockingStubEnv)
                .build()
                .start();
             TimeoutDemoLauncher probe = TimeoutDemoLauncher.probeAgent()
                     .profile("s5")
                     .envAll(probeEnv(stub))
                     .arg("--openjiuwen.service.llm.stream-idle-seconds=300s")
                     .build()
                     .start()) {
            blockingResp = new TimeoutProbeHttpClient(probe.baseUrl())
                    .query(PROMPT, Duration.ofSeconds(400));
        }

        new ErrorEnvelopeAsserter(connectResp).assertAllFourFieldsPresent()
                .assertPhase(TimeoutPhase.BEFORE_CONNECT)
                .assertLayer(ErrorEnvelopeAsserter.LAYER_CONNECT);
        new ErrorEnvelopeAsserter(headerResp).assertAllFourFieldsPresent()
                .assertPhase(TimeoutPhase.BEFORE_FIRST_SEGMENT)
                .assertLayer(ErrorEnvelopeAsserter.LAYER_HEADER_WAIT);
        new ErrorEnvelopeAsserter(blockingResp).assertAllFourFieldsPresent()
                .assertPhase(TimeoutPhase.BEFORE_FIRST_SEGMENT)
                .assertLayer(ErrorEnvelopeAsserter.LAYER_STREAM_IDLE)
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
        // 2026-10-27 修正：原本设环境变量 LLM_CONNECT_TIMEOUT=3s，但 s0 profile 的
        // application.yml 没有读这个环境变量的绑定（只有 s1 profile 读），导致 probe-agent
        // 用默认 10s 启动成功，判据"probe 应启动失败"失败。
        // 改用启动参数直接覆盖 Spring 配置（跟 P3-2 修 negativeMaxRetriesFailsStartup 一个套路），
        // 让 openjiuwen.service.llm.connect-timeout=3s 直接命中 LlmProperties，
        // 构造 ModelClientConfig 时 SDK validate(3<5) 拒绝 → probe-agent 启动期 fail-fast。
        try (TimeoutDemoLauncher probe = TimeoutDemoLauncher.probeAgent()
                .profile("s0")
                .envAll(probeEnvMap)
                .arg("--openjiuwen.service.llm.connect-timeout=3s")
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
     * providers sharing the same resolver (external {@code LLM_PROVIDER} env
     * values): {@code OpenAI}, {@code OpenRouter}, {@code DashScope},
     * {@code SiliconFlow}, {@code DeepSeek}. For each, drive s2 +
     * header-wait-silent and assert the four fields are literally equal
     * across providers (apart from the {@code provider} field itself if
     * present).
     *
     * <p>2026-10-28 provider rename note (batch triage 议题二 Path A): the
     * external provider enum value previously known as {@code OpenAiCompatible}
     * was renamed to {@code OpenRouter} in agent-core-java-0.1.17's
     * {@code ProviderType} enum. The underlying impl class
     * {@code OpenAiCompatibleModelClient} is unchanged — L2 MUST#8 covers
     * client-subclass families by impl class name (which did not change), so
     * the contract is not violated; only the public {@code LLM_PROVIDER} env
     * value changed.
     */
    @Test
    void unifiedStackCoverageAcrossClientSubclasses() throws Exception {
        assumeFatJarsPresent();

        List<String> providers = List.of(
                "OpenAI", "OpenRouter", "DashScope", "SiliconFlow", "DeepSeek");

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

    /**
     * TC-BLOCKING-HEADER-WAIT (cases §3.14) — MUST#9 header_wait side on
     * blocking path. Pairs with {@link #blockingPathSameTieringSemantics}
     * (stream_idle side on blocking path) to cover both L2 §1.2.9
     * observation points.
     *
     * <p>Profile {@code s2-blocking} reuses legacy
     * {@code openjiuwen.service.llm.timeout=8s} as the header-wait window and
     * routes the single invoke through
     * {@link com.openjiuwen.example.timeouttiering.service.BlockingProbeController#queryBlocking}
     * (one-shot {@code model.invoke(...)}, maxRetries=0). stub mode
     * {@code header-wait-silent} accepts TCP + swallows the request so no
     * response headers ever arrive — the SDK's {@code PreparedRequest.timeout}
     * (header-wait window) must trip at ~8s.
     *
     * <p>Expect:
     * <ul>
     *   <li>HTTP 200 wrapper (demo serializes BaseError into Map body);
     *   <li>{@code completed=false}, {@code exception_type="ModelError"};
     *   <li>{@code message} contains {@code "effective timeout 8.0s"};
     *   <li>{@code error_details.phase=BEFORE_FIRST_SEGMENT}
     *       (headers never arrived);
     *   <li>{@code error_details.layer=header_wait} (MUST#9 header_wait side —
     *       {@code IdleGuardedBodyReader} never got the chance to engage because
     *       the body never started);
     *   <li>{@code error_details.timeout_seconds=8.0±0.5};
     *   <li>{@code error_details.stream=false} (blocking path marker).
     * </ul>
     *
     * <p>Demo-side extensions landed 2026-10-30 per
     * {@code docs/review/SIT-REQUEST-feat-058-demo-side-extensions-20261030.md §1}:
     * {@code BlockingProbeController} + {@code blockingProbeModel} bean +
     * {@code s2-blocking} Spring profile.
     */
    @Test
    void blockingHeaderWaitPathObservesLayer() throws Exception {
        assumeFatJarsPresent();

        Map<String, String> stubEnv = commonEnv();
        stubEnv.put("LLM_STUB_DEFAULT_MODE", "header-wait-silent");
        try (TimeoutDemoLauncher stub = TimeoutDemoLauncher.llmStubServer()
                .envAll(stubEnv)
                .build()
                .start();
             TimeoutDemoLauncher probe = TimeoutDemoLauncher.probeAgent()
                     .profile("s2-blocking")
                     .envAll(probeEnv(stub))
                     .build()
                     .start()) {

            TimeoutProbeHttpClient http = new TimeoutProbeHttpClient(probe.baseUrl());
            // driver wait = 20s (8s legacy timeout + buffer for Spring MVC overhead)
            BlockingResponse resp = http.queryBlocking(Duration.ofSeconds(20));

            assertThat(resp.statusCode())
                    .as("blocking probe wraps BaseError into HTTP 200 Map body")
                    .isEqualTo(200);
            assertThat(resp.completed())
                    .as("completed=false expected (header-wait silence must trip)")
                    .isFalse();
            assertThat(resp.exceptionType())
                    .as("exception_type must be ModelError for header-wait timeout")
                    .isEqualTo("ModelError");
            assertThat(resp.message())
                    .as("message must contain 'effective timeout 8.0s' marker")
                    .contains("effective timeout 8.0s");

            // Reuse the ErrorEnvelopeAsserter via the QueryResponse adapter so the
            // four-field contract check is identical to the SSE-wire TCs.
            new ErrorEnvelopeAsserter(resp.toQueryResponse())
                    .assertTimeoutStatusCode()
                    .assertAllFourFieldsPresent()
                    .assertPhase(TimeoutPhase.BEFORE_FIRST_SEGMENT)
                    .assertLayer(ErrorEnvelopeAsserter.LAYER_HEADER_WAIT)
                    .assertTimeoutSeconds(8.0, 0.5)
                    .assertStream(false);
        }
    }

    /**
     * TC-AGENT-LEVEL-IDLE-OVERRIDE (cases §3.15) — MUST#7 agent-level source
     * of {@code streamIdleSeconds}. L2 §1.2.7 defines three legal sources with
     * priority per-call &gt; agent-level &gt; client-level; per-call is
     * covered by {@link Feat058BlockedByUpstreamTest#perCallIdleOverrideDoesNotPolluteClientConfig}
     * (§3.9) and client-level by {@link #blockingPathSameTieringSemantics}
     * (§3.6). This TC exercises the agent-level source
     * ({@code ReActAgentConfig.modelStreamIdleSeconds}) in isolation.
     *
     * <p>Profile {@code s3-agent-level-idle} pins client-level
     * {@code stream-idle-seconds=600s} (baseline) and agent-level
     * {@code demo.timeout-probe.model-stream-idle-seconds=300.0} (override).
     * {@code TimeoutProbeAgentFactory.build} conditionally invokes
     * {@code agentConfig.setModelStreamIdleSeconds(...)} when the property is
     * non-null. stub mode {@code after-first-segment-idle} with
     * {@code LLM_STUB_FIRST_SEGMENT_MS=310000} emits the first chunk then
     * holds silence 310s.
     *
     * <p>Critical assertion: {@code timeout_seconds} must be {@code 300.0}
     * (agent-level wins over client-level 600s). {@code timeout_seconds=600.0}
     * would mean the agent-level field was not consumed by the SDK (MUST#7
     * breach).
     *
     * <p>Demo-side extensions landed 2026-10-30 per
     * {@code docs/review/SIT-REQUEST-feat-058-demo-side-extensions-20261030.md §2}:
     * {@code TimeoutProbeAgentProperties.modelStreamIdleSeconds} field +
     * conditional {@code setModelStreamIdleSeconds} injection in
     * {@code TimeoutProbeAgentFactory.build} + {@code s3-agent-level-idle}
     * Spring profile.
     */
    @Test
    void agentLevelIdleOverridesClientLevel() throws Exception {
        assumeFatJarsPresent();

        Map<String, String> stubEnv = commonEnv();
        stubEnv.put("LLM_STUB_DEFAULT_MODE", "after-first-segment-idle");
        // stub holds silence 310s after first chunk — exceeds agent-level 300s
        // but under client-level 600s so this scenario trips at agent-level only.
        stubEnv.put("LLM_STUB_FIRST_SEGMENT_MS", "310000");
        try (TimeoutDemoLauncher stub = TimeoutDemoLauncher.llmStubServer()
                .envAll(stubEnv)
                .build()
                .start()) {
            Map<String, String> probeEnvMap = probeEnv(stub);
            // Agent-level override via ${AGENT_LEVEL_IDLE_SECONDS:300.0}; pinning
            // 300.0 explicitly keeps the TC resilient to future yaml default
            // changes.
            probeEnvMap.put("AGENT_LEVEL_IDLE_SECONDS", "300.0");
            try (TimeoutDemoLauncher probe = TimeoutDemoLauncher.probeAgent()
                    .profile("s3-agent-level-idle")
                    .envAll(probeEnvMap)
                    .build()
                    .start()) {

                TimeoutProbeHttpClient http = new TimeoutProbeHttpClient(probe.baseUrl());
                // driver wait = 400s (300s agent-level trip + buffer for SSE flush)
                QueryResponse resp = http.query(PROMPT, Duration.ofSeconds(400));

                new ErrorEnvelopeAsserter(resp)
                        .assertTimeoutStatusCode()
                        .assertAllFourFieldsPresent()
                        .assertPhase(TimeoutPhase.AFTER_FIRST_SEGMENT)
                        .assertLayer(ErrorEnvelopeAsserter.LAYER_STREAM_IDLE)
                        // Critical: 300.0 == agent-level override; 600.0 would be
                        // client-level — the latter means MUST#7 breach (agent-level
                        // field not consumed by SDK).
                        .assertTimeoutSeconds(300.0, 1.0)
                        .assertStream(true);

                SutLogTailer tailer = new SutLogTailer(probe.logFile());
                assertThat(tailer.countOfPhase(TimeoutPhase.AFTER_FIRST_SEGMENT))
                        .as("exactly one AFTER_FIRST_SEGMENT 181008 line expected")
                        .isEqualTo(1);
                assertThat(tailer.countOfPhase(TimeoutPhase.BEFORE_CONNECT)).isZero();
                assertThat(tailer.countOfPhase(TimeoutPhase.BEFORE_FIRST_SEGMENT)).isZero();
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

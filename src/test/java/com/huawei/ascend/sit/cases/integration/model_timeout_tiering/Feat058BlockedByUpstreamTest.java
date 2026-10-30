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
        // stub handleAfterFirstSegmentIdle 的 sleep 时长 = props.firstSegmentIdleMillis，
        // application.yml 默认 20_000ms（远小于 probe s3 profile 的 stream-idle=300s）→
        // stub 20s 就发完 "first"+"late" 自然完成，probe 永远不触发 timeout。
        // 必须把 stub sleep 推到 >300s 才能让 probe 的 stream-idle 判据生效。
        // 实证根因：2026-10-27 翻 @Disabled 跑一次 FAIL（wire 完整 "first"+"late"+answer），
        // 对账 stub TieredTimeoutHandlers.java:163 定位到此。
        stubEnv.put("LLM_STUB_FIRST_SEGMENT_MS", "310000");
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
     * TC-PER-CALL-IDLE-OVERRIDE (cases §3.9). s3-per-call profile: client-level
     * {@code stream-idle-seconds=600s} + first call carries per-call
     * {@code ModelInvokeOptions.timeout=300s}; second call carries no override.
     * Stub mode {@code after-first-segment-idle} holds silence 310s after
     * first chunk.
     *
     * <p>2026-10-28 batch triage 议题四 landed: demo side added
     * {@code PerCallProbeController} + {@code s3-per-call} profile (commit
     * {@code ab9e03ba6581}). SIT drives both calls via the side channel
     * {@code POST /probe/per-call} using {@link TimeoutProbeHttpClient#queryPerCall}.
     *
     * <p>Expect:
     * <ul>
     *   <li>First call (with per-call override=300s): per-call override wins
     *       over client-level 600s baseline, trips at 300s stream_idle →
     *       {@code [181008]} line in SUT log with
     *       {@code effective timeout 300.0s} in the message;
     *   <li>Second call (without override): HTTP 200 + {@code completed=true}
     *       — client-level 600s window exceeds 310s stub stall, iterator
     *       runs to natural end; proves per-call override released, client
     *       config not polluted.
     * </ul>
     *
     * <p>Original design overlaid per-call {@code ModelInvokeOptions.timeout}
     * with agent-level {@code ReActAgentConfig.modelStreamIdleSeconds} in the
     * same TC — the per-call-release-vs-persistent-override ambiguity is
     * removed in r1 by narrowing this TC to the per-call layer only (agent-level
     * persistent-field validation is a UT responsibility).
     */
    @Test
    void perCallIdleOverrideDoesNotPolluteClientConfig() throws Exception {
        assumeFatJarsPresent();

        Map<String, String> stubEnv = commonEnv();
        stubEnv.put("LLM_STUB_DEFAULT_MODE", "after-first-segment-idle");
        // stub 首块后沉默 310s（超过 per-call override 300s + client-level 600s
        // 的 300s 下限）；对第一腿而言 300s 先触发；对第二腿而言 310s < 600s 自然完成
        stubEnv.put("LLM_STUB_FIRST_SEGMENT_MS", "310000");
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

            // 第 1 腿：per_call_timeout_seconds=300, stub 310s 沉默 → trip 在 300s
            // driver wait 必须 ≥ 320s (300s trip + buffer)
            TimeoutProbeHttpClient.PerCallResponse first = http.queryPerCall(
                    Duration.ofSeconds(400), 300);
            assertThat(first.statusCode())
                    .as("first call: HTTP 200 from side-channel (iterator terminated via throwable)")
                    .isEqualTo(200);
            assertThat(first.perCallTimeoutSeconds())
                    .as("first call: echoed per_call_timeout_seconds")
                    .isEqualTo(300);
            assertThat(first.terminatedByException())
                    .as("first call: iterator must terminate via throwable (per-call 300s < stub 310s)")
                    .isTrue();
            assertThat(first.message())
                    .as("first call: expected 'effective timeout 300.0s' in exception message")
                    .contains("effective timeout 300.0s");

            SutLogTailer tailer = new SutLogTailer(probe.logFile());
            assertThat(tailer.countOfPhase(TimeoutPhase.AFTER_FIRST_SEGMENT))
                    .as("exactly one AFTER_FIRST_SEGMENT 181008 line expected (first call only)")
                    .isEqualTo(1);

            // 第 2 腿：no override, client-level 600s 应用 → stub 310s < 600s → 自然完成
            // driver wait 必须 ≥ 320s (310s stub stall + answer completion buffer)
            TimeoutProbeHttpClient.PerCallResponse second = http.queryPerCall(
                    Duration.ofSeconds(700), null);
            assertThat(second.statusCode())
                    .as("second call: HTTP 200 (natural completion)")
                    .isEqualTo(200);
            assertThat(second.completedNaturally())
                    .as("second call: iterator must complete naturally (client-level 600s > stub 310s)")
                    .isTrue();
            assertThat(second.terminatedByException())
                    .as("second call: no exception (per-call override released, client config not polluted)")
                    .isFalse();

            // 全程只应该有第 1 腿产生的那一条 [181008]
            assertThat(tailer.totalTimeoutCount())
                    .as("exactly one 181008 line (from first call only — proves per-call override released)")
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
        // stub 的 OpenAI 兼容端点在 /v1/chat/completions（LlmStubController:47），SDK 对 api-base
        // 直接拼 /chat/completions，裸 host 会撞 404。对齐 Feat058RunnablePathTest:503 的 /v1
        // 修复（10-22 commit eb9bf49）——当时本测试类三条全 @Disabled 所以漏修，2026-10-27
        // 翻 S3/S4 @Disabled 后 S4 7s EOF 实证了同一个坑。
        env.put("LLM_API_BASE", stub.baseUrl() + "/v1");
        return env;
    }
}

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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-064 — real-endpoint sampling (L1 §8.3 ST-R01 / ST-C01 / ST-C02 per r5
 * ST mapping). Points the timeout-probe-agent at a real LLM endpoint (via
 * shell env {@code LLM_API_BASE} + {@code LLM_API_KEY} + {@code LLM_MODEL}
 * sourced from {@code ~/.llmrc}) and verifies the FEAT-064 classification
 * contract holds against a genuine provider rather than the llm-stub-server.
 * This is the schema-agnostic R6 state-code evidence outside the stub harness.
 *
 * <ul>
 *   <li>TC-REAL-ENDPOINT-OK (ST-R01) — real endpoint happy path: HTTP 200 +
 *       {@code result_type=answer} + non-empty {@code output}. Baseline that
 *       the probe wiring reaches a real provider. Owns the ST-R01 anchor
 *       (stub-side {@code successPathIsNotInterferedWith} is "stub CI 补充"
 *       per r5, not claiming ST-R01).
 *   <li>TC-REAL-ENDPOINT-AUTH-FAILED (ST-C01) — real endpoint with intentionally
 *       bad {@code LLM_API_KEY}: expect {@code 181011} + {@code category=AUTH_FAILED}
 *       on the SIT HTTP surface. Proves the classifier hookpoint resolves a
 *       real-provider 401 the same way as the stub 401.
 *   <li>TC-REAL-ENDPOINT-CONTEXT-LENGTH (ST-C02, cases §3.19) — real endpoint
 *       with an intentionally-oversized prompt (16× model context window);
 *       expect {@code 181012} + {@code category=CONTEXT_LENGTH_EXCEEDED}
 *       on the SIT HTTP surface. Proves the context-length classifier
 *       hookpoint resolves on a real provider.
 * </ul>
 *
 * <p>All TCs are gated behind {@link Assumptions#assumeTrue} on
 * {@code LLM_API_BASE} env presence — if the operator has not sourced
 * {@code ~/.llmrc}, the TCs are SKIPPED rather than FAILED. See memory
 * {@code reference_llmrc.md}.
 *
 * <p>No stub subprocess is launched. The probe binds directly to the external
 * provider. Corporate proxy must be unset by the caller (memory
 * {@code local-dev-clear-corporate-proxy-before-sit}): internal LLM endpoints
 * get rejected by HIS proxy with a 504 HTML page otherwise.
 */
@Tag("integration")
@Tag("feat-064")
@Tag("slow")
@Tag("manual")
@Tag("real-endpoint")
final class Feat064RealEndpointSamplingTest {

    private static final Duration DEFAULT_INVOKE_TIMEOUT = Duration.ofSeconds(60);

    private static final String PROMPT = "ping";

    /**
     * TC-REAL-ENDPOINT-OK (L1 §8.3 ST-R01 per r5 ST mapping). Hits the real
     * LLM endpoint configured via {@code ~/.llmrc} shell env. Expect HTTP 200
     * + {@code result_type=answer} + non-empty {@code output} + no classified
     * error codes in SUT log. Serves as the real-provider green-path anchor.
     */
    @Test
    void realEndpointHappyPath() throws Exception {
        assumeFatJarsPresent();
        assumeRealLlmConfigured();

        Map<String, String> probeEnvOverride = baseProbeEnv();
        // Inherit the shell-provided LLM_* env verbatim (do NOT force stub-key).
        copyIfPresent(probeEnvOverride, "LLM_API_BASE");
        copyIfPresent(probeEnvOverride, "LLM_API_KEY");
        copyIfPresent(probeEnvOverride, "LLM_MODEL");
        copyIfPresent(probeEnvOverride, "LLM_PROVIDER");

        try (TimeoutDemoLauncher probe = TimeoutDemoLauncher.probeAgent()
                .profile("s0")
                .envAll(probeEnvOverride)
                .build()
                .start()) {
            QueryResponse resp = new TimeoutProbeHttpClient(probe.baseUrl())
                    .query(PROMPT, DEFAULT_INVOKE_TIMEOUT);
            SutLogTailer tailer = new SutLogTailer(probe.logFile());

            assertThat(resp.statusCode())
                    .as("HTTP status for real-endpoint ok")
                    .isEqualTo(200);
            assertThat(resp.resultType())
                    .as("result_type for real-endpoint ok")
                    .isEqualTo("answer");
            assertThat(resp.output())
                    .as("output for real-endpoint ok must be non-empty")
                    .isNotNull()
                    .isNotBlank();

            assertNoClassifiedErrorsInLog(tailer);
        }
    }

    /**
     * TC-REAL-ENDPOINT-AUTH-FAILED (L1 §8.3 ST-C01 per r5 ST mapping). Hits
     * the real LLM endpoint with an intentionally bad {@code LLM_API_KEY}
     * (overrides the one sourced from {@code ~/.llmrc}). Expect {@code 181011}
     * + {@code category=AUTH_FAILED} + no retry log (permanent short-circuit).
     * Proves the classifier hookpoint resolves a real-provider 401 identically
     * to the stub 401 (R6 state-code rule is schema-agnostic by design).
     */
    @Test
    void realEndpointAuthFailureClassifiesAs181011() throws Exception {
        assumeFatJarsPresent();
        assumeRealLlmConfigured();

        Map<String, String> probeEnvOverride = baseProbeEnv();
        copyIfPresent(probeEnvOverride, "LLM_API_BASE");
        copyIfPresent(probeEnvOverride, "LLM_MODEL");
        copyIfPresent(probeEnvOverride, "LLM_PROVIDER");
        // Force a bad key regardless of the shell value.
        probeEnvOverride.put("LLM_API_KEY", "sk-sit-feat064-intentionally-invalid-key");

        try (TimeoutDemoLauncher probe = TimeoutDemoLauncher.probeAgent()
                .profile("s0")
                .envAll(probeEnvOverride)
                .build()
                .start()) {
            QueryResponse resp = new TimeoutProbeHttpClient(probe.baseUrl())
                    .query(PROMPT, DEFAULT_INVOKE_TIMEOUT);
            SutLogTailer tailer = new SutLogTailer(probe.logFile());

            new ErrorEnvelopeAsserter(resp)
                    .assertStatusCode(ErrorEnvelopeAsserter.MODEL_CALL_AUTH_FAILED)
                    .assertCategory(ErrorEnvelopeAsserter.CATEGORY_AUTH_FAILED)
                    .assertNoRetryLog(tailer);
        }
    }

    /**
     * TC-REAL-ENDPOINT-CONTEXT-LENGTH (cases §3.19, ST-C02). Hits the real LLM
     * endpoint with an intentionally-oversized prompt (16× typical model
     * context window — ~128 KiB of filler) so the provider must reject with
     * a context-length / token-limit error. Expect {@code 181012} on the SIT
     * HTTP surface (status_code channel pinned by R6 state-code rule) + no
     * retry log (permanent short-circuit).
     *
     * <p>Does NOT assert {@code details.category} per r5 "T3 终态日志通道当前
     * 未落地" → category channel is partial, status_code channel is load-bearing.
     *
     * <p>Note on prompt sizing: 128 KiB is >= every mainstream provider's
     * context limit at time of writing (DeepSeek 64K, OpenAI gpt-4o 128K
     * output but 128K context so filler must push past, Qwen 32K, …). If a
     * future model lands with multi-MB context, bump the filler accordingly.
     */
    @Test
    void realEndpointContextLengthClassifiesAs181012() throws Exception {
        assumeFatJarsPresent();
        assumeRealLlmConfigured();

        Map<String, String> probeEnvOverride = baseProbeEnv();
        copyIfPresent(probeEnvOverride, "LLM_API_BASE");
        copyIfPresent(probeEnvOverride, "LLM_API_KEY");
        copyIfPresent(probeEnvOverride, "LLM_MODEL");
        copyIfPresent(probeEnvOverride, "LLM_PROVIDER");

        // 128 KiB of filler — far enough past every mainstream context window
        // that the provider must reject with a context_length_exceeded /
        // token_limit style error.
        String oversizedPrompt = "x".repeat(128 * 1024);

        try (TimeoutDemoLauncher probe = TimeoutDemoLauncher.probeAgent()
                .profile("s0")
                .envAll(probeEnvOverride)
                .build()
                .start()) {
            QueryResponse resp = new TimeoutProbeHttpClient(probe.baseUrl())
                    .query(oversizedPrompt, DEFAULT_INVOKE_TIMEOUT);
            SutLogTailer tailer = new SutLogTailer(probe.logFile());

            new ErrorEnvelopeAsserter(resp)
                    .assertStatusCode(ErrorEnvelopeAsserter.MODEL_CALL_CONTEXT_LENGTH_EXCEEDED)
                    .assertNoRetryLog(tailer);
        }
    }

    // ---------------- helpers ----------------

    private static void assumeRealLlmConfigured() {
        String base = System.getenv("LLM_API_BASE");
        String key = System.getenv("LLM_API_KEY");
        String model = System.getenv("LLM_MODEL");
        Assumptions.assumeTrue(base != null && !base.isBlank(),
                "LLM_API_BASE not set; source ~/.llmrc before running real-endpoint TCs");
        Assumptions.assumeTrue(key != null && !key.isBlank(),
                "LLM_API_KEY not set; source ~/.llmrc before running real-endpoint TCs");
        Assumptions.assumeTrue(model != null && !model.isBlank(),
                "LLM_MODEL not set; source ~/.llmrc before running real-endpoint TCs");
    }

    private void assumeFatJarsPresent() {
        Assumptions.assumeTrue(
                Files.isRegularFile(TimeoutDemoLauncher.DEFAULT_PROBE_JAR)
                        || System.getProperty(TimeoutDemoLauncher.PROBE_JAR_PROP) != null,
                "timeout-probe-agent fat jar not present; set -D" + TimeoutDemoLauncher.PROBE_JAR_PROP);
    }

    private static Map<String, String> baseProbeEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("JAVA_HOME", System.getProperty("java.home"));
        env.put("PATH", System.getenv().getOrDefault("PATH", ""));
        env.put("LANG", "en_US.UTF-8");
        env.put("LLM_SSL_VERIFY", "false");
        env.put("LLM_TEMPERATURE", "0.0");
        return env;
    }

    private static void copyIfPresent(Map<String, String> target, String key) {
        String v = System.getenv(key);
        if (v != null && !v.isBlank()) {
            target.put(key, v);
        }
    }

    private static void assertNoClassifiedErrorsInLog(SutLogTailer tailer) throws Exception {
        int[] classified = {
                ErrorEnvelopeAsserter.MODEL_CALL_RATE_LIMITED,
                ErrorEnvelopeAsserter.MODEL_CALL_QUOTA_EXCEEDED,
                ErrorEnvelopeAsserter.MODEL_CALL_AUTH_FAILED,
                ErrorEnvelopeAsserter.MODEL_CALL_CONTEXT_LENGTH_EXCEEDED,
                ErrorEnvelopeAsserter.MODEL_CALL_TIMEOUT_CODE,
                ErrorEnvelopeAsserter.MODEL_CALL_ERROR,
        };
        for (int code : classified) {
            long count = tailer.countOfStatusCode(code);
            assertThat(count)
                    .as("real-endpoint ok must not surface classified error [%d]", code)
                    .isZero();
        }
    }
}

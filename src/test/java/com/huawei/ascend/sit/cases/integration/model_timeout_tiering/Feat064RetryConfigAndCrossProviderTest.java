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
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-064 — 4 incremental TCs added per the dev-team 2026-10-26 reply
 * (demo 2026-10-26 next-push adds probe profiles s14/s15/s16 bound to
 * {@code demo.timeout-probe.retry-on-rate-limited} /
 * {@code .retry-on-temporary} / {@code .max-retries-override}, and stub env
 * {@code LLM_STUB_RESPONSE_SCHEMA=openai|dashscope} for the s17 scenario):
 *
 * <ul>
 *   <li>TC-RATE-LIMITED-SWITCH-OFF (cases §3.10) — s14 profile, stub
 *       {@code rate-limited-persistent} + {@code retry-on-rate-limited=false};
 *       expect {@code [181009]} + zero retry-log lines (switch OFF bypasses
 *       retry loop). L2 §1.2.9 (MUST) switch-OFF branch.
 *   <li>TC-TEMPORARY-SWITCH-OFF (cases §3.11) — s15 profile, stub
 *       {@code server-5xx} + {@code retry-on-temporary=false}; expect
 *       {@code [181001]} + zero retry-log lines. L2 §1.2.9 (MUST) switch-OFF
 *       branch.
 *   <li>TC-MAX-RETRIES-ZERO-NO-RETRY (cases §3.12) — s16 profile, stub
 *       {@code server-5xx} + {@code max-retries-override=0}; expect
 *       {@code [181001]} + {@code max_retries=0} field in log + zero retry-log
 *       lines. L2 §1.2.9 (MUST) budget-shutdown wire-observable (归属校正：
 *       从 §1.2.11 Rail 装配事实剥到 §1.2.9).
 *   <li>TC-CROSS-PROVIDER-CLASSIFICATION (cases §3.13) — s17 scenario: probe
 *       s0 profile (shared with TC-AUTH-FAILED), stub {@code auth-failed} +
 *       env {@code LLM_STUB_RESPONSE_SCHEMA=dashscope}; expect {@code [181011]}
 *       + {@code category=AUTH_FAILED} + zero retry-log lines. L2 §1.2.1
 *       (MUST) classifier hookpoint coverage (DashScope schema × 401 as the
 *       representative schema-agnostic R6 state-code path).
 * </ul>
 *
 * <p>Why a sibling class rather than appending to
 * {@link Feat064ErrorTaxonomyAndRetryTest}: those 9 TCs all run under probe
 * {@code s0} profile (default — no three-layer timeout overrides, no
 * max-retries override). These 4 TCs require per-TC probe profile overrides
 * (s14/s15/s16) and per-TC stub schema env overrides (s17). Keeping them in a
 * separate class makes the "profile matrix" visible at the class level.
 *
 * <p>Preconditions (expressed as {@link Assumptions#assumeTrue} — missing jar
 * → test SKIPPED, not FAILED): same as {@link Feat064ErrorTaxonomyAndRetryTest}.
 */
@Tag("integration")
@Tag("feat-064")
@Tag("slow")
@Tag("manual")
final class Feat064RetryConfigAndCrossProviderTest {

    private static final Duration DEFAULT_INVOKE_TIMEOUT = Duration.ofSeconds(60);

    private static final String PROMPT = "ping";

    /**
     * TC-RATE-LIMITED-SWITCH-OFF (cases §3.10). s14 profile sets
     * {@code demo.timeout-probe.retry-on-rate-limited: false} → bypass retry
     * loop for RATE_LIMITED. stub mode {@code rate-limited-persistent}
     * returns 429 persistently. Expect 181009 landing + zero retry-log lines.
     *
     * <p>Does NOT assert {@code details.category} string per cases §3.10 note:
     * platform's {@code "Model call will retry."} log line (which carries the
     * category field) does not fire when the switch is OFF, so the category
     * substring is co-absent with the retry log. Status-code 181009 ↔
     * RATE_LIMITED 1:1 mapping is already pinned by R6 state-code rule.
     */
    @Test
    void rateLimitedSwitchOffBypassesRetry() throws Exception {
        assumeFatJarsPresent();

        try (QueryTrace trace = runStubbed("rate-limited-persistent", "s14", Map.of())) {
            new ErrorEnvelopeAsserter(trace.response)
                    .assertStatusCode(ErrorEnvelopeAsserter.MODEL_CALL_RATE_LIMITED)
                    .assertNoRetryLog(trace.tailer);

            assertNoOtherClassifiedCodes(trace.tailer,
                    ErrorEnvelopeAsserter.MODEL_CALL_RATE_LIMITED);
        }
    }

    /**
     * TC-TEMPORARY-SWITCH-OFF (cases §3.11). s15 profile sets
     * {@code demo.timeout-probe.retry-on-temporary: false} → bypass retry
     * loop for TEMPORARY. stub mode {@code server-5xx} returns 500
     * persistently. Expect 181001 landing + zero retry-log lines.
     *
     * <p>Same no-category-assertion caveat as TC-RATE-LIMITED-SWITCH-OFF.
     */
    @Test
    void temporarySwitchOffBypassesRetry() throws Exception {
        assumeFatJarsPresent();

        try (QueryTrace trace = runStubbed("server-5xx", "s15", Map.of())) {
            new ErrorEnvelopeAsserter(trace.response)
                    .assertStatusCode(ErrorEnvelopeAsserter.MODEL_CALL_ERROR)
                    .assertNoRetryLog(trace.tailer);

            assertNoOtherClassifiedCodes(trace.tailer,
                    ErrorEnvelopeAsserter.MODEL_CALL_ERROR);
            assertThat(trace.tailer.totalTimeoutCount())
                    .as("TEMPORARY switch-off must not emit any 181008")
                    .isZero();
        }
    }

    /**
     * TC-MAX-RETRIES-ZERO-NO-RETRY (cases §3.12). s16 profile sets
     * {@code demo.timeout-probe.max-retries-override: 0} → close the retry
     * budget (L2 §2.3 原文: {@code maxRetries=0} 等价于关闭全部自动重试).
     * stub mode {@code server-5xx}. Expect 181001 landing + {@code max_retries=0}
     * field in log + zero retry-log lines (first failure is terminal).
     */
    @Test
    void maxRetriesZeroShutsDownRetryBudget() throws Exception {
        assumeFatJarsPresent();

        try (QueryTrace trace = runStubbed("server-5xx", "s16", Map.of())) {
            new ErrorEnvelopeAsserter(trace.response)
                    .assertStatusCode(ErrorEnvelopeAsserter.MODEL_CALL_ERROR)
                    .assertNoRetryLog(trace.tailer);

            assertNoOtherClassifiedCodes(trace.tailer,
                    ErrorEnvelopeAsserter.MODEL_CALL_ERROR);
            assertThat(trace.tailer.totalTimeoutCount())
                    .as("maxRetries=0 must not emit any 181008")
                    .isZero();
            assertLogContains(trace.tailer.logFile(), "max_retries=0",
                    "SUT log must carry 'max_retries=0' field proving the budget-shutdown "
                            + "wire-observable (L2 §2.3)");
        }
    }

    /**
     * TC-CROSS-PROVIDER-CLASSIFICATION (cases §3.13). Probe runs the default
     * s0 profile (shared with TC-AUTH-FAILED). Stub runs {@code auth-failed}
     * mode with env {@code LLM_STUB_RESPONSE_SCHEMA=dashscope} → emits a
     * DashScope-style 401 body ({@code {"code":"InvalidApiKey",...}}) instead
     * of the OpenAI schema. Expect 181011 + {@code category=AUTH_FAILED}
     * + zero retry-log lines — proving the classifier hookpoint does not
     * swallow the status code on a foreign schema body (L2 §1.2.1 挂点覆盖
     * requirement; R6 state-code rule is schema-agnostic by design).
     */
    @Test
    void crossProviderSchemaHookpointCoverage() throws Exception {
        assumeFatJarsPresent();

        Map<String, String> stubExtra = new LinkedHashMap<>();
        stubExtra.put("LLM_STUB_RESPONSE_SCHEMA", "dashscope");

        try (QueryTrace trace = runStubbed("auth-failed", "s0", stubExtra)) {
            new ErrorEnvelopeAsserter(trace.response)
                    .assertStatusCode(ErrorEnvelopeAsserter.MODEL_CALL_AUTH_FAILED)
                    .assertCategory(ErrorEnvelopeAsserter.CATEGORY_AUTH_FAILED)
                    .assertNoRetryLog(trace.tailer);

            assertNoOtherClassifiedCodes(trace.tailer,
                    ErrorEnvelopeAsserter.MODEL_CALL_AUTH_FAILED);
        }
    }

    // ---------------- helpers ----------------

    private QueryTrace runStubbed(String stubMode, String probeProfile,
                                  Map<String, String> stubExtraEnv) throws Exception {
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
                    .profile(probeProfile)
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

    private static void assertLogContains(Path logFile, String needle, String message) throws Exception {
        if (!Files.isRegularFile(logFile)) {
            throw new AssertionError(message + " — log file missing: " + logFile);
        }
        boolean found = false;
        for (String line : Files.readAllLines(logFile)) {
            if (line.contains(needle)) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new AssertionError(message + " — needle '" + needle + "' not found"
                    + "\n--- log tail (last 80 lines) ---\n"
                    + TimeoutDemoLauncher.tail(logFile, 80));
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
        env.put("LLM_API_BASE", stub.baseUrl());
        return env;
    }

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
}

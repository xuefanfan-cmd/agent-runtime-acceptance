/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.context_compression.fixture;

import com.huawei.ascend.sit.cases.integration.context_compression.fixture.CompactProbeHttpClient.TriggerResponse;

import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Scenario harness for the FEAT-033 structured-compact-sit-demo. Encapsulates
 * the (stub, probe, http client, log tailer) lifecycle so individual test
 * classes can focus on scenario-specific assertions.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #assumeFatJarsPresent()} — SKIP (not FAIL) when the demo jars
 *       are not laid out at the default dev path and no {@code -D} override
 *       is given.
 *   <li>{@link #launch(String, String, Map)} — fire up the stub + probe and
 *       return a {@link Scenario} that owns both processes plus the HTTP
 *       client. The caller invokes HTTP endpoints and asserts on the log /
 *       response body / history / span events.
 * </ol>
 *
 * <p>The harness never inherits the parent JVM env, and the probe always has
 * corporate proxy env cleared via {@code pb.environment().clear()} in
 * {@link CompactProbeDemoLauncher}. Callers can add scenario-specific env
 * (e.g. {@code LLM_STUB_SLOW_RESPONSE_MILLIS}) via the {@code stubExtras}
 * map on {@link #launch(String, String, Map)}.
 */
public final class CompactProbeHarness {

    public static final Duration DEFAULT_TRIGGER_TIMEOUT = Duration.ofSeconds(60);
    public static final Duration SLOW_TRIGGER_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration SHORT_TIMEOUT = Duration.ofSeconds(10);

    private CompactProbeHarness() {
    }

    /** JUnit {@code assumeTrue} — SKIP the test if the demo jars are absent. */
    public static void assumeFatJarsPresent() {
        assumeTrue(
                Files.isRegularFile(CompactProbeDemoLauncher.DEFAULT_PROBE_JAR)
                        || System.getProperty(CompactProbeDemoLauncher.PROBE_JAR_PROP) != null,
                "context-probe-agent fat jar not present; set -D"
                        + CompactProbeDemoLauncher.PROBE_JAR_PROP);
        assumeTrue(
                Files.isRegularFile(CompactProbeDemoLauncher.DEFAULT_STUB_JAR)
                        || System.getProperty(CompactProbeDemoLauncher.STUB_JAR_PROP) != null,
                "llm-stub-server fat jar not present; set -D"
                        + CompactProbeDemoLauncher.STUB_JAR_PROP);
    }

    public static Scenario launch(String profile, String stubMode,
                                  Map<String, String> stubExtras) throws Exception {
        return launch(profile, stubMode, stubExtras, Map.of());
    }

    public static Scenario launch(String profile, String stubMode,
                                  Map<String, String> stubExtras,
                                  Map<String, String> probeExtras) throws Exception {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(stubMode, "stubMode");

        Map<String, String> stubEnv = commonEnv();
        stubEnv.put("LLM_STUB_PORT", "18194");
        stubEnv.put("LLM_STUB_DEFAULT_MODE", stubMode);
        if (stubExtras != null) {
            stubEnv.putAll(stubExtras);
        }

        CompactProbeDemoLauncher stub = CompactProbeDemoLauncher.llmStubServer()
                .envAll(stubEnv)
                .build()
                .start();
        CompactProbeDemoLauncher probe = null;
        try {
            Map<String, String> probeEnv = probeEnv(stub);
            if (probeExtras != null) {
                probeEnv.putAll(probeExtras);
            }
            probe = CompactProbeDemoLauncher.probeAgent()
                    .profile(profile)
                    .envAll(probeEnv)
                    .build()
                    .start();
            return new Scenario(stub, probe);
        } catch (Exception | Error e) {
            if (probe != null) {
                probe.close();
            }
            stub.close();
            throw e;
        }
    }

    /**
     * Wait up to {@code timeout} for the processor to emit {@code
     * phase=complete} at least {@code expectedCount} times. Scenarios that
     * drive multiple compactions (s17 / s18) call this with
     * {@code expectedCount=2}.
     */
    public static void waitForCompleteCount(ProbeLogTailer tailer, int expectedCount,
                                            Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (tailer.completeCount() >= expectedCount) {
                return;
            }
            Thread.sleep(100);
        }
    }

    /** Convenience: wait for a single {@code phase=complete} line. */
    public static void waitForComplete(ProbeLogTailer tailer) throws Exception {
        waitForCompleteCount(tailer, 1, SHORT_TIMEOUT);
    }

    public static Map<String, String> commonEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("JAVA_HOME", System.getProperty("java.home"));
        env.put("PATH", System.getenv().getOrDefault("PATH", ""));
        env.put("LANG", "en_US.UTF-8");
        return env;
    }

    public static Map<String, String> probeEnv(CompactProbeDemoLauncher stub) {
        Map<String, String> env = commonEnv();
        env.put("LLM_PROVIDER", "OpenAI");
        env.put("LLM_API_KEY", "stub-key");
        env.put("LLM_MODEL", "stub-summary-model");
        env.put("LLM_API_BASE", stub.baseUrl() + "/v1");
        env.put("LLM_STUB_URL", stub.baseUrl() + "/v1");
        env.put("LLM_SSL_VERIFY", "false");
        env.put("LLM_TEMPERATURE", "0.0");
        return env;
    }

    /**
     * Owns the two launched processes + convenience accessors for the probe's
     * HTTP client and stdout tailer. {@link #close()} shuts both processes
     * down; use with try-with-resources.
     */
    public static final class Scenario implements AutoCloseable {
        private final CompactProbeDemoLauncher stub;
        private final CompactProbeDemoLauncher probe;
        private final CompactProbeHttpClient http;
        private final ProbeLogTailer tailer;

        private Scenario(CompactProbeDemoLauncher stub, CompactProbeDemoLauncher probe) {
            this.stub = stub;
            this.probe = probe;
            this.http = new CompactProbeHttpClient(probe.baseUrl());
            this.tailer = new ProbeLogTailer(probe.logFile());
        }

        public CompactProbeHttpClient http() {
            return http;
        }

        public ProbeLogTailer tailer() {
            return tailer;
        }

        public CompactProbeDemoLauncher probe() {
            return probe;
        }

        public CompactProbeDemoLauncher stub() {
            return stub;
        }

        /** Fire {@code POST /trigger-compact?scenario=<profile>} and wait for completion. */
        public TriggerResponse triggerAndWait(String scenario, Duration triggerTimeout)
                throws Exception {
            TriggerResponse resp = http.trigger(scenario, triggerTimeout);
            waitForComplete(tailer);
            return resp;
        }

        @Override
        public void close() {
            try {
                probe.close();
            } finally {
                stub.close();
            }
        }
    }
}

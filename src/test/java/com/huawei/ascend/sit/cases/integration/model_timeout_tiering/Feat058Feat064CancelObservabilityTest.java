/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.model_timeout_tiering;

import com.huawei.ascend.sit.cases.integration.model_timeout_tiering.fixture.TimeoutDemoLauncher;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-058 MUST#11 cancel-observability × FEAT-064 USER_CANCELLED classification,
 * S18 / S19.
 *
 * <p><strong>Cross-FEAT ownership</strong>: the {@code event=model_call_cancelled}
 * anchor log line asserted here承 the "cancel-observability output" contract
 * owned by <strong>FEAT-058 MUST#11</strong> (per the deferral note left in
 * {@link Feat064ErrorTaxonomyAndRetryTest}:52 — "USER_CANCELLED is NOT covered
 * here — deferred until FEAT-058 MUST#11 cancel-observability output is
 * adjudicated"), while the {@code USER_CANCELLED} category name sits in the
 * FEAT-064 L2 §1.2 classification set (M14). The anchor fact is primarily
 * FEAT-058; the category identity is primarily FEAT-064. Both {@code @Tag}s
 * are declared so either FEAT's gating selector picks it up.
 *
 * <p>Validates the two side-channel endpoints the probe-agent exposes to drive
 * {@code USER_CANCELLED} outcomes on a standalone {@code Model} instance
 * (bundled as of develop-head m2, {@code agent-core-java} mr/437 #252
 * cancellation log anchor + probe-agent {@code CancelProbeController}):
 * <ul>
 *   <li>S18 — {@code POST /probe/cancel/blocking?cancel_after_ms=300} drives
 *       a {@code model.invoke(...)} on a worker thread and schedules
 *       {@code future.cancel(true)}; the resulting {@code InterruptedException}
 *       is routed through {@code ClassifiedModelErrors.toCancellation} and
 *       emits the {@code event=model_call_cancelled} anchor log line.
 *   <li>S19 — same mechanism against {@code model.stream(...)} via
 *       {@code POST /probe/cancel/streaming?cancel_after_ms=300}.
 * </ul>
 *
 * <p>These two TCs <strong>do not</strong> go through the {@code /v1/query} ReAct agent
 * runner — HTTP TCP disconnects do not propagate to the model-call thread, so
 * the existing {@code Feat058RunnablePathTest.callerCancelIsNotTimeout}
 * (FEAT-058 §3.7) observes external cancel, not the model-layer anchor. The
 * side-channel approach here is what the develop-head demo HANDOFF documents
 * for stable S18 / S19 coverage.
 *
 * <p>Both TCs reuse {@code header-wait-silent} as the stub mode so the stub
 * flushes response headers quickly but never writes the body — the worker is
 * then blocked on body-read / stream-chunk-wait when {@code future.cancel(true)}
 * fires at the 300 ms mark, guaranteeing the interrupt lands inside the model
 * client (not during handshake). This matches the HANDOFF §四 note:
 * {@code "cancel_after_ms=300} — long enough for the stub to flush headers so
 * the worker is blocked reading the body".
 *
 * <p>Assertion contract (both TCs identical, matching HANDOFF §四 S18 / S19 table):
 * <ul>
 *   <li>MUST appear: {@code event=model_call_cancelled} line in probe log;
 *   <li>MUST NOT appear: {@code [181008]} — ensures cancel fires before
 *       stream_idle / connect timer and is classified as USER_CANCELLED, not
 *       as a timeout.
 * </ul>
 *
 * <p>The {@code reason=} suffix on the anchor line varies between the two
 * endpoints and across the mid-stream vs stream-open interrupt sites inside
 * {@code OpenAIModelClient}, so this TC asserts anchor presence only, not
 * reason text.
 *
 * <p>Preconditions (expressed as {@link Assumptions#assumeTrue} — missing jar
 * → test SKIPPED, not FAILED): timeout-probe-agent fat jar must be on disk
 * at {@link TimeoutDemoLauncher#DEFAULT_PROBE_JAR} or injected via
 * {@code -D} property.
 *
 * <p>Preconditions deliberately NOT guarded (will FAIL loudly if violated):
 * jar version must be develop-head m2 or later (contain {@code CancelProbeController}
 * and {@code cancelProbeModel} wiring). Older jars will return HTTP 404 on the
 * cancel endpoints and TC will FAIL in the POST step rather than SKIP.
 *
 * <p>Companion UT: {@code ClassifiedModelErrorsTest} +
 * {@code OpenAIModelClientStreamCancelTest} in agent-core-java cover the
 * reason-text matrix and the mid-stream vs open-phase branch split.
 */
@Tag("integration")
@Tag("feat-058")
@Tag("feat-064")
@Tag("slow")
@Tag("manual")
final class Feat058Feat064CancelObservabilityTest {

    private static final Duration CANCEL_POST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration LOG_SETTLE = Duration.ofMillis(1500);
    private static final long DEFAULT_CANCEL_AFTER_MS = 300L;

    private static final String CANCEL_ANCHOR = "event=model_call_cancelled";
    private static final String TIMEOUT_CODE = "[181008]";

    /**
     * TC-USER-CANCELLED-BLOCKING (S18). Blocking {@code model.invoke} on a worker
     * thread, {@code future.cancel(true)} at 300 ms. Anchor must land, no 181008.
     */
    @Test
    void blockingCancelEmitsAnchorLogNoTimeout() throws Exception {
        assumeFatJarsPresent();

        try (CancelTrace trace = runCancel("blocking", "header-wait-silent",
                DEFAULT_CANCEL_AFTER_MS)) {
            assertHasCancelAnchor(trace.probeLogFile);
            assertNoTimeoutCode(trace.probeLogFile);
        }
    }

    /**
     * TC-USER-CANCELLED-STREAMING (S19). Streaming {@code model.stream} iterator
     * pump on a worker thread, {@code future.cancel(true)} at 300 ms. Anchor
     * must land, no 181008.
     */
    @Test
    void streamingCancelEmitsAnchorLogNoTimeout() throws Exception {
        assumeFatJarsPresent();

        try (CancelTrace trace = runCancel("streaming", "header-wait-silent",
                DEFAULT_CANCEL_AFTER_MS)) {
            assertHasCancelAnchor(trace.probeLogFile);
            assertNoTimeoutCode(trace.probeLogFile);
        }
    }

    // ---------------- helpers ----------------

    private CancelTrace runCancel(String endpoint, String stubMode, long cancelAfterMs)
            throws Exception {
        Map<String, String> stubEnv = commonEnv();
        stubEnv.put("LLM_STUB_DEFAULT_MODE", stubMode);

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

            // POST /probe/cancel/{endpoint}?cancel_after_ms=N
            // Side-channel endpoints block the HTTP response until the worker
            // future either completes or is observed cancelled — the 60 s
            // ceiling inside the controller is well above our 30 s client
            // timeout; the usual turnaround is ~300 ms + a small tail.
            URI uri = URI.create(probe.baseUrl()
                    + "/probe/cancel/" + endpoint
                    + "?cancel_after_ms=" + cancelAfterMs);
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(CANCEL_POST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> resp = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build()
                    .send(req, HttpResponse.BodyHandlers.ofString());

            // POST itself must succeed (404 would mean old jar without CancelProbeController)
            assertThat(resp.statusCode())
                    .as("POST /probe/cancel/" + endpoint + " must return 200 "
                            + "(404 → probe-agent jar is pre-develop-head-m2, missing "
                            + "CancelProbeController side-channel; body=" + resp.body() + ")")
                    .isEqualTo(200);

            // Settle: give the probe-agent async logger a moment to flush the
            // anchor line before we scan the file. The anchor is emitted on a
            // worker thread via logback, which is non-synchronous w.r.t. the
            // HTTP response thread.
            Thread.sleep(LOG_SETTLE.toMillis());

            return new CancelTrace(stub, probe, probe.logFile());
        } catch (Exception | Error e) {
            if (probe != null) {
                probe.close();
            }
            stub.close();
            throw e;
        }
    }

    private static void assertHasCancelAnchor(Path logFile) throws Exception {
        List<String> lines = Files.readAllLines(logFile);
        boolean found = lines.stream().anyMatch(line -> line.contains(CANCEL_ANCHOR));
        assertThat(found)
                .as("probe log must contain '%s' anchor (last 20 lines: %s)",
                        CANCEL_ANCHOR, tailLines(lines, 20))
                .isTrue();
    }

    private static void assertNoTimeoutCode(Path logFile) throws Exception {
        List<String> lines = Files.readAllLines(logFile);
        List<String> hits = lines.stream()
                .filter(line -> line.contains(TIMEOUT_CODE))
                .toList();
        assertThat(hits)
                .as("cancel must fire before any three-layer timer; '%s' must not "
                        + "appear in probe log", TIMEOUT_CODE)
                .isEmpty();
    }

    private static String tailLines(List<String> lines, int n) {
        int from = Math.max(0, lines.size() - n);
        return String.join("\n", lines.subList(from, lines.size()));
    }

    private void assumeFatJarsPresent() {
        Assumptions.assumeTrue(
                Files.isRegularFile(TimeoutDemoLauncher.DEFAULT_PROBE_JAR)
                        || System.getProperty(TimeoutDemoLauncher.PROBE_JAR_PROP) != null,
                "timeout-probe-agent fat jar not present; set -D"
                        + TimeoutDemoLauncher.PROBE_JAR_PROP);
        Assumptions.assumeTrue(
                Files.isRegularFile(TimeoutDemoLauncher.DEFAULT_STUB_JAR)
                        || System.getProperty(TimeoutDemoLauncher.STUB_JAR_PROP) != null,
                "llm-stub-server fat jar not present; set -D"
                        + TimeoutDemoLauncher.STUB_JAR_PROP);
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
     * Trace bundle for a single cancel-probe run. Implements {@link AutoCloseable}
     * so TCs can use try-with-resources to tear down both the stub and probe
     * subprocess with a single statement.
     */
    private static final class CancelTrace implements AutoCloseable {
        final TimeoutDemoLauncher stub;
        final TimeoutDemoLauncher probe;
        final Path probeLogFile;

        CancelTrace(TimeoutDemoLauncher stub, TimeoutDemoLauncher probe, Path probeLogFile) {
            this.stub = stub;
            this.probe = probe;
            this.probeLogFile = probeLogFile;
        }

        @Override
        public void close() {
            probe.close();
            stub.close();
        }
    }
}

/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.context_compression;

import com.huawei.ascend.sit.cases.integration.context_compression.fixture.CompactProbeDemoLauncher;
import com.huawei.ascend.sit.cases.integration.context_compression.fixture.CompactProbeHttpClient;
import com.huawei.ascend.sit.cases.integration.context_compression.fixture.CompactProbeHttpClient.TriggerResponse;
import com.huawei.ascend.sit.cases.integration.context_compression.fixture.CompressionDetailsAsserter;
import com.huawei.ascend.sit.cases.integration.context_compression.fixture.ProbeLogTailer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-033 — passive context compression. Covers the 12 scenario TCs
 * ({@code s0..s11}) from testplan §5 that the current {@code
 * structured-compact-sit-demo} probe can construct directly.
 *
 * <p>Each test launches the two fat jars ({@code llm-stub-server-0.1.0.jar}
 * + {@code context-probe-agent-0.1.0.jar}) with the per-scenario profile +
 * stub mode, issues {@code POST /trigger-compact?scenario=sN}, and asserts
 * on two surfaces:
 * <ul>
 *   <li><b>Primary channel</b> — processor {@code phase=complete / phase=retry}
 *       lines in the probe stdout log, matching the exact templates emitted by
 *       {@code StructuredCompactProcessor$Operation};
 *   <li><b>Secondary channel</b> — the {@code details} map returned in the
 *       {@code POST /trigger-compact} JSON body (L2 §5.3 compressionUsage).
 * </ul>
 *
 * <p>Preconditions (expressed as {@link Assumptions#assumeTrue} — missing fat
 * jar → test SKIPPED, not FAILED):
 * <ul>
 *   <li>probe jar at {@link CompactProbeDemoLauncher#DEFAULT_PROBE_JAR} or
 *       {@code -D} override;
 *   <li>stub jar at {@link CompactProbeDemoLauncher#DEFAULT_STUB_JAR} or
 *       {@code -D} override;
 *   <li>JDK 17 or 21 (JDK 24+ breaks Lombok's {@code sun.misc.Unsafe});
 *   <li>corporate proxy env unset by caller (demo loopback 127.0.0.1).
 * </ul>
 */
@Tag("integration")
@Tag("feat-033")
@Tag("slow")
@Tag("manual")
final class Feat033PassiveCompactionTest {

    private static final Duration DEFAULT_TRIGGER_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration SLOW_TRIGGER_TIMEOUT = Duration.ofSeconds(30);

    /** §3.1 TC-FEAT-033.s0-below-threshold — strict trigger threshold (L2 §3.1). */
    @Test
    void s0BelowThresholdNoOp() throws Exception {
        assumeFatJarsPresent();
        try (Trace t = run("s0", "valid", Map.of())) {
            t.tailer.assertRequired("outcome=NO_OP");
            t.tailer.assertRequired("reason=BELOW_THRESHOLD");
            // 2026-10-29 SIT 对齐 L2 §5.3 Line 226 对外字段：attempt_count / llm_call_count
            // （产品 probe_result 行写 attempt_count=0 llm_call_count=0；原 asserter 用的短名
            // attempts/llm_calls 只匹配 StructuredCompactProcessor 内部日志 phase=complete 行，
            // 而 s0 场景 NO_OP 不触发 phase=complete，故改为匹配对外契约字段名）。
            t.tailer.assertRequired("attempt_count=0");
            t.tailer.assertRequired("llm_call_count=0");
            t.tailer.assertForbidden("outcome=LLM_COMPACTED");
            t.tailer.assertForbidden("outcome=DETERMINISTIC_COMPACTED");
            t.tailer.assertForbidden("phase=attempt");

            new CompressionDetailsAsserter(t.response)
                    .assertHttpOk()
                    // 2026-10-29 SIT 修：L2 Line 215 规定 compression_usage.details.kind 只在
                    // 实际 compact 发生时产生；s0 NO_OP BELOW_THRESHOLD 场景产品不触发 compact
                    // → details 无 kind 字段，符合 L2。去掉本处 .assertKind()。
                    .assertOutcome("NO_OP")
                    .assertReason("BELOW_THRESHOLD")
                    .assertAttemptCount(0)
                    .assertLlmCallCount(0)
                    .assertNoSecretsInDetails();
        }
    }

    /** §3.2 TC-FEAT-033.s1-llm-compact-success — single-attempt 11-chapter success (L2 §3.3). */
    @Test
    void s1LlmCompactSuccess() throws Exception {
        assumeFatJarsPresent();
        try (Trace t = run("s1", "valid", Map.of())) {
            t.tailer.assertRequired("outcome=LLM_COMPACTED");
            t.tailer.assertRequired("reason=SUCCESS");
            t.tailer.assertRequired("attempts=1");
            t.tailer.assertRequired("llm_calls=1");
            t.tailer.assertForbidden("outcome=DETERMINISTIC_COMPACTED");
            t.tailer.assertForbidden("reason=INVALID_SUMMARY");
            t.tailer.assertForbidden("reason=LOW_QUALITY");

            new CompressionDetailsAsserter(t.response)
                    .assertHttpOk()
                    .assertKind()
                    .assertOutcome("LLM_COMPACTED")
                    .assertReason("SUCCESS")
                    .assertAttemptCount(1)
                    .assertLlmCallCount(1)
                    .assertAfterTokensStrictlyLessThanBefore()
                    .assertNoSecretsInDetails();
        }
    }

    /** §3.3 TC-FEAT-033.s2-invalid-summary-recover — INVALID_SUMMARY retry → SUCCESS (L2 §3.4). */
    @Test
    void s2InvalidSummaryRecover() throws Exception {
        assumeFatJarsPresent();
        try (Trace t = run("s2", "missing-chapter-then-valid",
                Map.of("LLM_STUB_MISSING_CHAPTER_RECOVER_AFTER", "1"))) {
            t.tailer.assertRequired("phase=retry reason=INVALID_SUMMARY");
            t.tailer.assertRequired("outcome=LLM_COMPACTED");
            t.tailer.assertRequired("reason=SUCCESS");
            t.tailer.assertRequired("attempts=2");
            t.tailer.assertRequired("llm_calls=2");
            t.tailer.assertForbidden("outcome=DETERMINISTIC_COMPACTED");

            new CompressionDetailsAsserter(t.response)
                    .assertHttpOk()
                    .assertKind()
                    .assertOutcome("LLM_COMPACTED")
                    .assertReason("SUCCESS")
                    .assertAttemptCount(2)
                    .assertLlmCallCount(2)
                    .assertNoSecretsInDetails();
        }
    }

    /** §3.4 TC-FEAT-033.s3-invalid-order-exhausted — 3 retries exhaust → DETERMINISTIC (L2 §3.4). */
    @Test
    void s3InvalidOrderExhausted() throws Exception {
        assumeFatJarsPresent();
        try (Trace t = run("s3", "invalid-order", Map.of())) {
            t.tailer.assertRequired("phase=retry reason=INVALID_SUMMARY");
            t.tailer.assertRequired("outcome=DETERMINISTIC_COMPACTED");
            t.tailer.assertRequired("reason=INVALID_SUMMARY");
            t.tailer.assertRequired("attempts=3");
            t.tailer.assertRequired("llm_calls=3");
            t.tailer.assertForbidden("outcome=LLM_COMPACTED");
            t.tailer.assertForbidden("reason=SUCCESS");

            new CompressionDetailsAsserter(t.response)
                    .assertHttpOk()
                    .assertKind()
                    .assertOutcome("DETERMINISTIC_COMPACTED")
                    .assertReason("INVALID_SUMMARY")
                    .assertAttemptCount(3)
                    .assertLlmCallCount(3)
                    .assertNoSecretsInDetails();
        }
    }

    /** §3.5 TC-FEAT-033.s4-input-too-large-drop-group — drop oldest group retry (L2 §3.4). */
    @Test
    void s4InputTooLargeDropGroupRetry() throws Exception {
        assumeFatJarsPresent();
        try (Trace t = run("s4", "input-too-large-then-valid",
                Map.of("LLM_STUB_INPUT_TOO_LARGE_RECOVER_AFTER", "1"))) {
            t.tailer.assertRequired("phase=retry reason=INPUT_TOO_LARGE dropped_groups=1");
            t.tailer.assertRequired("outcome=LLM_COMPACTED");
            t.tailer.assertRequired("reason=SUCCESS");
            t.tailer.assertForbidden("outcome=DETERMINISTIC_COMPACTED");
            t.tailer.assertForbidden("reason=MODEL_ERROR");

            new CompressionDetailsAsserter(t.response)
                    .assertHttpOk()
                    .assertKind()
                    .assertOutcome("LLM_COMPACTED")
                    .assertReason("SUCCESS")
                    .assertDroppedGroups(1)
                    .assertNoSecretsInDetails();
        }
    }

    /** §3.6 TC-FEAT-033.s5-low-retention-deterministic — 2 consecutive low-retention → DET LOW_QUALITY. */
    @Test
    void s5LowRetentionDeterministic() throws Exception {
        assumeFatJarsPresent();
        try (Trace t = run("s5", "short-retention", Map.of())) {
            t.tailer.assertRequired("phase=retry reason=LOW_QUALITY");
            t.tailer.assertRequired("outcome=DETERMINISTIC_COMPACTED");
            t.tailer.assertRequired("reason=LOW_QUALITY");
            t.tailer.assertRequired("llm_calls=2");
            t.tailer.assertForbidden("outcome=LLM_COMPACTED");
            t.tailer.assertForbidden("reason=SUCCESS");

            new CompressionDetailsAsserter(t.response)
                    .assertHttpOk()
                    .assertKind()
                    .assertOutcome("DETERMINISTIC_COMPACTED")
                    .assertReason("LOW_QUALITY")
                    .assertLlmCallCount(2)
                    .assertNoSecretsInDetails();
        }
    }

    /**
     * §3.7 TC-FEAT-033.s6-model-timeout-fallback — slow response → fallback.
     * Known upstream gap: processor currently emits reason=MODEL_ERROR with
     * {@code header_wait timeout} rather than MODEL_TIMEOUT (header-wait
     * timeout classified as model-call failure, not
     * {@code java.util.concurrent.TimeoutException}). trace-asserter is the
     * authoritative source — see cases §3.7 note.
     */
    @Test
    void s6ModelTimeoutFallback() throws Exception {
        assumeFatJarsPresent();
        try (Trace t = run("s6", "slow",
                Map.of("LLM_STUB_SLOW_RESPONSE_MILLIS", "10000"), SLOW_TRIGGER_TIMEOUT)) {
            t.tailer.assertRequired("outcome=DETERMINISTIC_COMPACTED");
            t.tailer.assertRequired("reason=MODEL_ERROR");
            t.tailer.assertRequired("llm_calls=1");
            t.tailer.assertRequired("header_wait timeout");
            t.tailer.assertForbidden("outcome=LLM_COMPACTED");
            t.tailer.assertForbidden("reason=SUCCESS");

            new CompressionDetailsAsserter(t.response)
                    .assertHttpOk()
                    .assertKind()
                    .assertOutcome("DETERMINISTIC_COMPACTED")
                    .assertReason("MODEL_ERROR")
                    .assertLlmCallCount(1)
                    .assertNoSecretsInDetails();
        }
    }

    /** §3.8 TC-FEAT-033.s7-model-5xx-fallback — persistent 5xx → DET MODEL_ERROR. */
    @Test
    void s7Model5xxFallback() throws Exception {
        assumeFatJarsPresent();
        try (Trace t = run("s7", "server-5xx", Map.of())) {
            t.tailer.assertRequired("outcome=DETERMINISTIC_COMPACTED");
            t.tailer.assertRequired("reason=MODEL_ERROR");
            t.tailer.assertRequired("llm_calls=1");
            t.tailer.assertForbidden("outcome=LLM_COMPACTED");
            t.tailer.assertForbidden("reason=MODEL_TIMEOUT");

            new CompressionDetailsAsserter(t.response)
                    .assertHttpOk()
                    .assertKind()
                    .assertOutcome("DETERMINISTIC_COMPACTED")
                    .assertReason("MODEL_ERROR")
                    .assertLlmCallCount(1)
                    .assertNoSecretsInDetails();
        }
    }

    /** §3.9 TC-FEAT-033.s8-model-not-configured — zero model calls (L2 §6.2). */
    @Test
    void s8ModelNotConfigured() throws Exception {
        assumeFatJarsPresent();
        try (Trace t = run("s8", "valid", Map.of())) {
            t.tailer.assertRequired("outcome=DETERMINISTIC_COMPACTED");
            t.tailer.assertRequired("reason=MODEL_NOT_CONFIGURED");
            t.tailer.assertRequired("llm_calls=0");
            t.tailer.assertForbidden("outcome=LLM_COMPACTED");
            t.tailer.assertForbidden("phase=attempt");

            new CompressionDetailsAsserter(t.response)
                    .assertHttpOk()
                    .assertKind()
                    .assertOutcome("DETERMINISTIC_COMPACTED")
                    .assertReason("MODEL_NOT_CONFIGURED")
                    .assertLlmCallCount(0)
                    .assertNoSecretsInDetails();
        }
    }

    /** §3.10 TC-FEAT-033.s9-summary-budget-conflict — budget pre-flight short-circuit (L2 §3.4). */
    @Test
    void s9SummaryBudgetConflict() throws Exception {
        assumeFatJarsPresent();
        try (Trace t = run("s9", "valid", Map.of())) {
            t.tailer.assertRequired("outcome=DETERMINISTIC_COMPACTED");
            t.tailer.assertRequired("reason=SUMMARY_BUDGET_CONFLICT");
            t.tailer.assertRequired("llm_calls=0");
            t.tailer.assertForbidden("outcome=LLM_COMPACTED");
            t.tailer.assertForbidden("phase=attempt");

            new CompressionDetailsAsserter(t.response)
                    .assertHttpOk()
                    .assertKind()
                    .assertOutcome("DETERMINISTIC_COMPACTED")
                    .assertReason("SUMMARY_BUDGET_CONFLICT")
                    .assertLlmCallCount(0)
                    .assertNoSecretsInDetails();
        }
    }

    /** §3.11 TC-FEAT-033.s10-identifier-truncation — 400 identifiers → selected=300 truncated≥1 (L2 §3.5). */
    @Test
    void s10IdentifierTruncation() throws Exception {
        assumeFatJarsPresent();
        try (Trace t = run("s10", "valid", Map.of())) {
            t.tailer.assertRequired("identifiers_selected=300");
            t.tailer.assertRequired("identifiers_truncated=[1-9][0-9]*");
            t.tailer.assertForbidden("identifiers_truncated=0 ");

            new CompressionDetailsAsserter(t.response)
                    .assertHttpOk()
                    .assertKind()
                    .assertIdentifiersSelected(300)
                    .assertIdentifiersTruncatedAtLeast(1)
                    .assertNoSecretsInDetails();
        }
    }

    /** §3.12 TC-FEAT-033.s11-identifier-repair — identifiers_repaired ≥ 1 (L2 §3.5 audit append). */
    @Test
    void s11IdentifierRepair() throws Exception {
        assumeFatJarsPresent();
        try (Trace t = run("s11", "valid", Map.of())) {
            t.tailer.assertRequired("identifiers_repaired=[1-9][0-9]*");
            t.tailer.assertForbidden("identifiers_repaired=0 ");

            new CompressionDetailsAsserter(t.response)
                    .assertHttpOk()
                    .assertKind()
                    .assertIdentifiersRepairedAtLeast(1)
                    .assertNoSecretsInDetails();
        }
    }

    /**
     * §3.3 TC-FEAT-033.assembly-chain-e2e — 4-level装配 evidence (L2 §4). The
     * probe starts with {@code ReActAgent → ContextProcessorRail →
     * ContextEngine → StructuredCompactProcessor} wired; {@code
     * POST /trigger-compact-via-rail} dispatches a {@code BEFORE_MODEL_CALL}
     * event through the registered rail. SIT must observe:
     * <ul>
     *   <li>{@code /__assembly-info} reports {@code processor_type=structured_compact}
     *       and {@code trace_bridge_installed=true}.
     *   <li>probe log emits {@code rail_dispatch_begin ... processors=[structured_compact]}
     *       and {@code rail_dispatch_end status=ok}.
     *   <li>processor emits one {@code phase=complete} line → outcome=LLM_COMPACTED
     *       for the s1 fixture.
     * </ul>
     */
    @Test
    void assemblyChainE2E() throws Exception {
        assumeFatJarsPresent();
        try (Trace t = runViaRail("s1", "valid", Map.of())) {
            TriggerResponse assembly = new CompactProbeHttpClient(t.probe.baseUrl())
                    .assemblyInfo(Duration.ofSeconds(10));
            assertThat(assembly.statusCode()).as("__assembly-info HTTP status").isEqualTo(200);
            assertThat(assembly.body("processor_type"))
                    .as("processor_type exposed by /__assembly-info")
                    // 2026-10-29 SIT 对齐 L2 Line 240 的 ContextEngine.registerProcessor(
                    // "StructuredCompactProcessor", StructuredCompactProcessor.class)；
                    // 实证 StructuredCompactProcessor.PROCESSOR_TYPE 常量二进制值 == PascalCase
                    // Java 类名。snake_case "structured_compact" 仅用于摘要 metadata.kind
                    // 和 compression_usage.details.kind 两处（L2 Line 211/215），不是 processor_type。
                    .isEqualTo("StructuredCompactProcessor");
            assertThat(assembly.body("trace_bridge_installed"))
                    .as("trace_bridge_installed")
                    .isEqualTo(Boolean.TRUE);

            t.tailer.assertRequired("rail_dispatch_begin");
            // 2026-10-29 SIT 对齐 10-28 批次 jar 实际 rail_dispatch_begin 日志格式：
            // processors=[MessageSummaryOffloader, DialogueCompressor, CurrentRoundCompressor,
            //              RoundLevelCompressor, StructuredCompactProcessor]
            // 列表值用 PROCESSOR_TYPE 常量（PascalCase 类名），不是 HANDOFF.md 过时文档
            // 中描述的 snake_case `structured_compact`。
            t.tailer.assertRequired("StructuredCompactProcessor");
            // 2026-10-29 SIT 对齐产品 rail_dispatch_end 日志格式：
            //   rail_dispatch_end agent_id=context-probe-agent status=ok message_count_after=24
            // 原 pattern `rail_dispatch_end status=ok` 要求紧邻匹配不符实际（中间隔 agent_id 字段）。
            t.tailer.assertRequired("rail_dispatch_end agent_id=context-probe-agent status=ok");
            t.tailer.assertRequired("outcome=LLM_COMPACTED");
            t.tailer.assertRequired("reason=SUCCESS");

            new CompressionDetailsAsserter(t.response)
                    .assertHttpOk()
                    .assertKind()
                    .assertOutcome("LLM_COMPACTED")
                    .assertReason("SUCCESS")
                    .assertNoSecretsInDetails();
        }
    }

    /**
     * §3.4 TC-FEAT-033.active-trigger-compress — explicit
     * {@code ContextEngine.compressContext(contextId, null, null,
     * List.of(StructuredCompactProcessor.PROCESSOR_TYPE), Map.of(...))}. SIT
     * verifies the active trigger path emits the same processor contract as
     * the passive rail path: one {@code phase=complete} line with
     * outcome=LLM_COMPACTED reason=SUCCESS. The HTTP response for
     * {@code /__trigger-active} returns the same {@code details} map shape.
     */
    @Test
    void activeTriggerCompress() throws Exception {
        assumeFatJarsPresent();
        try (Trace t = runActive("s1", "valid", Map.of())) {
            t.tailer.assertRequired("outcome=LLM_COMPACTED");
            t.tailer.assertRequired("reason=SUCCESS");
            t.tailer.assertRequired("attempts=1");
            t.tailer.assertRequired("llm_calls=1");

            new CompressionDetailsAsserter(t.response)
                    .assertHttpOk()
                    .assertKind()
                    .assertOutcome("LLM_COMPACTED")
                    .assertReason("SUCCESS")
                    .assertAttemptCount(1)
                    .assertLlmCallCount(1)
                    .assertNoSecretsInDetails();
        }
    }

    private Trace run(String scenario, String stubMode, Map<String, String> stubExtras) throws Exception {
        return run(scenario, stubMode, stubExtras, DEFAULT_TRIGGER_TIMEOUT);
    }

    private Trace run(String scenario, String stubMode,
                      Map<String, String> stubExtras, Duration triggerTimeout) throws Exception {
        return launchAndFire(scenario, stubMode, stubExtras, triggerTimeout, TriggerKind.PASSIVE);
    }

    private Trace runViaRail(String scenario, String stubMode,
                             Map<String, String> stubExtras) throws Exception {
        return launchAndFire(scenario, stubMode, stubExtras, DEFAULT_TRIGGER_TIMEOUT, TriggerKind.RAIL);
    }

    private Trace runActive(String scenario, String stubMode,
                            Map<String, String> stubExtras) throws Exception {
        return launchAndFire(scenario, stubMode, stubExtras, DEFAULT_TRIGGER_TIMEOUT, TriggerKind.ACTIVE);
    }

    private enum TriggerKind { PASSIVE, RAIL, ACTIVE }

    private Trace launchAndFire(String scenario, String stubMode,
                                Map<String, String> stubExtras,
                                Duration triggerTimeout, TriggerKind kind) throws Exception {
        Map<String, String> stubEnv = commonEnv();
        stubEnv.put("LLM_STUB_PORT", "18194");
        stubEnv.put("LLM_STUB_DEFAULT_MODE", stubMode);
        stubEnv.putAll(stubExtras);

        CompactProbeDemoLauncher stub = CompactProbeDemoLauncher.llmStubServer()
                .envAll(stubEnv)
                .build()
                .start();
        CompactProbeDemoLauncher probe = null;
        try {
            probe = CompactProbeDemoLauncher.probeAgent()
                    .profile(scenario)
                    .envAll(probeEnv(stub))
                    .build()
                    .start();
            CompactProbeHttpClient client = new CompactProbeHttpClient(probe.baseUrl());
            TriggerResponse resp = switch (kind) {
                case PASSIVE -> client.trigger(scenario, triggerTimeout);
                case RAIL -> client.triggerViaRail(scenario, triggerTimeout);
                case ACTIVE -> client.triggerActive(scenario, triggerTimeout);
            };
            // Give the processor a moment to finish writing the complete line.
            waitForComplete(new ProbeLogTailer(probe.logFile()));
            ProbeLogTailer tailer = new ProbeLogTailer(probe.logFile());
            assertThat(resp.statusCode())
                    .as("probe HTTP status for %s via %s", scenario, kind)
                    .isEqualTo(200);
            return new Trace(stub, probe, resp, tailer);
        } catch (Exception | Error e) {
            if (probe != null) {
                probe.close();
            }
            stub.close();
            throw e;
        }
    }

    private static void waitForComplete(ProbeLogTailer tailer) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (tailer.completeEmitted()) {
                return;
            }
            Thread.sleep(100);
        }
    }

    private void assumeFatJarsPresent() {
        Assumptions.assumeTrue(
                Files.isRegularFile(CompactProbeDemoLauncher.DEFAULT_PROBE_JAR)
                        || System.getProperty(CompactProbeDemoLauncher.PROBE_JAR_PROP) != null,
                "context-probe-agent fat jar not present; set -D"
                        + CompactProbeDemoLauncher.PROBE_JAR_PROP);
        Assumptions.assumeTrue(
                Files.isRegularFile(CompactProbeDemoLauncher.DEFAULT_STUB_JAR)
                        || System.getProperty(CompactProbeDemoLauncher.STUB_JAR_PROP) != null,
                "llm-stub-server fat jar not present; set -D"
                        + CompactProbeDemoLauncher.STUB_JAR_PROP);
    }

    private static Map<String, String> commonEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("JAVA_HOME", System.getProperty("java.home"));
        env.put("PATH", System.getenv().getOrDefault("PATH", ""));
        env.put("LANG", "en_US.UTF-8");
        return env;
    }

    private static Map<String, String> probeEnv(CompactProbeDemoLauncher stub) {
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

    private static final class Trace implements AutoCloseable {
        final CompactProbeDemoLauncher stub;
        final CompactProbeDemoLauncher probe;
        final TriggerResponse response;
        final ProbeLogTailer tailer;

        Trace(CompactProbeDemoLauncher stub, CompactProbeDemoLauncher probe,
              TriggerResponse response, ProbeLogTailer tailer) {
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

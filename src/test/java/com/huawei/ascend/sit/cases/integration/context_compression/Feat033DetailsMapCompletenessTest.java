/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.context_compression;

import com.huawei.ascend.sit.cases.integration.context_compression.fixture.CompactProbeDemoLauncher;
import com.huawei.ascend.sit.cases.integration.context_compression.fixture.CompactProbeHttpClient;
import com.huawei.ascend.sit.cases.integration.context_compression.fixture.CompactProbeHttpClient.TriggerResponse;
import com.huawei.ascend.sit.cases.integration.context_compression.fixture.CompressionDetailsAsserter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FEAT-033 §3.13 TC-FEAT-033.details-map-completeness — SIT-added TC.
 *
 * <p>Reuses the {@code s1} happy-path profile (single-attempt 11-chapter
 * success) to exercise an outcome where every schema field is populated with
 * a real value, then asserts the full L2 §5.3 {@code compressionUsage.details}
 * schema:
 * <ul>
 *   <li>exact key whitelist matches (no extras, no misses);
 *   <li>fixed enums ({@code kind="structured_compact"}, {@code algorithm_version=1});
 *   <li>config snapshot matches the s1 profile ({@code trigger_total_tokens=1000}
 *       / {@code compression_call_max_tokens=200000} /
 *       {@code messages_to_keep=10});
 *   <li>result triple ({@code outcome=LLM_COMPACTED} / {@code reason=SUCCESS});
 *   <li>token accounting ({@code after_tokens < before_tokens});
 *   <li>retention ratios in [0,1];
 *   <li>no credentials / Bearer-token / sk- literals leak (L2 §7.3 red-line).
 * </ul>
 *
 * <p>Rationale: the demo trace-asserter only grep-matches ~8 fields on the
 * processor finish line. The remaining 13 schema keys (algorithm_version,
 * counter_id, above_trigger_tokens, trigger_total_tokens,
 * compression_call_max_tokens, messages_to_keep, global_retention,
 * attempt_count, llm_call_count, dropped_groups, duration_ms, attempt_reasons,
 * and the kind sentinel) have no demo-side observable; this SIT TC covers that
 * schema-completeness blind spot.
 */
@Tag("integration")
@Tag("feat-033")
@Tag("slow")
@Tag("manual")
final class Feat033DetailsMapCompletenessTest {

    private static final Duration TRIGGER_TIMEOUT = Duration.ofSeconds(60);

    @Test
    void detailsMapMatchesL2Schema() throws Exception {
        assumeFatJarsPresent();

        Map<String, String> stubEnv = commonEnv();
        stubEnv.put("LLM_STUB_PORT", "18194");
        stubEnv.put("LLM_STUB_DEFAULT_MODE", "valid");

        try (CompactProbeDemoLauncher stub = CompactProbeDemoLauncher.llmStubServer()
                .envAll(stubEnv)
                .build()
                .start();
             CompactProbeDemoLauncher probe = CompactProbeDemoLauncher.probeAgent()
                     .profile("s1")
                     .envAll(probeEnv(stub))
                     .build()
                     .start()) {

            TriggerResponse resp = new CompactProbeHttpClient(probe.baseUrl())
                    .trigger("s1", TRIGGER_TIMEOUT);

            new CompressionDetailsAsserter(resp)
                    .assertHttpOk()
                    .assertSchemaKeysMatchWhitelist()
                    .assertKind()
                    .assertAlgorithmVersion(1)
                    .assertOutcome("LLM_COMPACTED")
                    .assertReason("SUCCESS")
                    .assertAttemptCount(1)
                    .assertLlmCallCount(1)
                    .assertDroppedGroups(0)
                    .assertAfterTokensStrictlyLessThanBefore()
                    .assertRetentionInUnitRange("middle_retention")
                    .assertRetentionInUnitRange("global_retention")
                    .assertConfigSnapshot(1000L, 200000L, 10)
                    .assertNoSecretsInDetails();
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
}

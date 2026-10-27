/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.model_timeout_tiering;

import com.huawei.ascend.sit.cases.integration.model_timeout_tiering.fixture.TimeoutDemoLauncher;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-064 — TC-MAX-RETRIES-NEGATIVE-REJECTED (cases §3.8). Startup-time
 * fail-fast on invalid {@code demo.timeout-probe.max-retries-override=-1}:
 * the {@code ModelClientConfig$Builder.validateMaxRetries} setter must reject
 * negatives → Spring Boot binding / Builder.build() fails → probe-agent exits
 * non-zero without ever becoming ready.
 *
 * <p>Mirrors the FEAT-058 TC-RANGE-VALIDATION pattern: uses
 * {@link TimeoutDemoLauncher#startExpectingFailure()} rather than readiness
 * probe. No stub is launched (this TC does not go over the wire).
 *
 * <p>The override is injected as a CLI arg
 * ({@code --demo.timeout-probe.max-retries-override=-1}) targeting the demo's
 * own {@code TimeoutProbeAgentProperties}. probe-agent does NOT bind the
 * platform-level {@code openjiuwen.service.llm.*} namespace — per dev-side
 * 2026-10-26 FEAT-064 coverage-gaps REPLY §A.1 / §C.1 clarification, the demo
 * builds its {@code ModelClientConfig} via {@code TimeoutProbeAgentFactory.build()}
 * which calls {@code Builder.maxRetries(propsValue)} directly, so only the
 * {@code demo.timeout-probe.*} namespace reaches the SDK validate.
 */
@Tag("integration")
@Tag("feat-064")
@Tag("manual")
final class Feat064MaxRetriesNegativeRejectedTest {

    @Test
    void negativeMaxRetriesFailsStartup() throws Exception {
        Assumptions.assumeTrue(
                Files.isRegularFile(TimeoutDemoLauncher.DEFAULT_PROBE_JAR)
                        || System.getProperty(TimeoutDemoLauncher.PROBE_JAR_PROP) != null,
                "timeout-probe-agent fat jar not present; set -D" + TimeoutDemoLauncher.PROBE_JAR_PROP);

        try (TimeoutDemoLauncher probe = TimeoutDemoLauncher.probeAgent()
                .profile("s0")
                .envAll(baseProbeEnv())
                .arg("--demo.timeout-probe.max-retries-override=-1")
                .startupTimeout(Duration.ofSeconds(30))
                .build()) {
            probe.startExpectingFailure();
            assertThat(probe.isAlive())
                    .as("probe-agent must exit when max-retries=-1 (ModelClientConfig validateMaxRetries)")
                    .isFalse();
            assertThat(probe.exitValue())
                    .as("probe-agent exit code must be non-zero for max-retries range violation")
                    .isNotZero();
            String tail = TimeoutDemoLauncher.tail(probe.logFile(), 300);
            assertThat(tail)
                    .as("startup failure log must surface max-retries validation (ModelClientConfig / IllegalArgumentException / BindException)")
                    .containsAnyOf(
                            "ModelClientConfig",
                            "max-retries",
                            "maxRetries",
                            "IllegalArgumentException",
                            "BindException",
                            "ConfigurationPropertiesBindException");
        }
    }

    private static Map<String, String> baseProbeEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("JAVA_HOME", System.getProperty("java.home"));
        env.put("PATH", System.getenv().getOrDefault("PATH", ""));
        env.put("LANG", "en_US.UTF-8");
        env.put("LLM_PROVIDER", "OpenAI");
        env.put("LLM_API_KEY", "stub-key");
        env.put("LLM_MODEL", "stub-model");
        env.put("LLM_SSL_VERIFY", "false");
        env.put("LLM_TEMPERATURE", "0.0");
        // Point at a stub URL that doesn't need to resolve — this TC fails before
        // any wire activity. Keeping the var set avoids demo config-missing noise
        // in the startup log that could obscure the maxRetries BindException.
        env.put("LLM_API_BASE", "http://127.0.0.1:18094/v1");
        return env;
    }
}

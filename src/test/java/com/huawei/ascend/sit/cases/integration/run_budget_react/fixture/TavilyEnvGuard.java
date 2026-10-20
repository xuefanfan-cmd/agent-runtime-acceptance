/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.run_budget_react.fixture;

import org.junit.jupiter.api.Assumptions;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Collects the FEAT-057 demo required environment and surfaces assumeTrue
 * gates for cases that need Tavily (S1, S5).
 *
 * <p>Not a hard dependency — if the required env is absent, the test is
 * marked SKIPPED (= INCONCLUSIVE per §8 of the testplan), not FAILED. This
 * matches memory [[reference_llmrc]]: callers {@code source ~/.llmrc} +
 * {@code source ~/.tavilyrc} + {@code unset HTTP_PROXY HTTPS_PROXY} before
 * invoking Maven; this guard simply enforces the shell step was done.
 */
public final class TavilyEnvGuard {

    public static final String LLM_API_KEY = "LLM_API_KEY";
    public static final String LLM_API_BASE = "LLM_API_BASE";
    public static final String LLM_MODEL = "LLM_MODEL";
    public static final String LLM_TEMPERATURE = "LLM_TEMPERATURE";
    public static final String TAVILY_API_KEY = "TAVILY_API_KEY";

    private TavilyEnvGuard() {}

    /** LLM env — required for every FEAT-057 scenario except S0. */
    public static Map<String, String> requireLlmEnv() {
        Map<String, String> out = new LinkedHashMap<>();
        require(out, LLM_API_KEY, true);
        require(out, LLM_API_BASE, true);
        require(out, LLM_MODEL, false);
        require(out, LLM_TEMPERATURE, false);
        return out;
    }

    /** Tavily env — required by S1 and S5. assumes the test is skipped if absent. */
    public static Map<String, String> requireTavily() {
        Map<String, String> out = requireLlmEnv();
        require(out, TAVILY_API_KEY, true);
        return out;
    }

    /** S0 does not need LLM env if the stub web search path is taken. */
    public static Map<String, String> optionalLlmEnv() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : new String[] {LLM_API_KEY, LLM_API_BASE, LLM_MODEL, LLM_TEMPERATURE}) {
            String v = System.getenv(key);
            if (v != null && !v.isBlank()) {
                out.put(key, v);
            }
        }
        return out;
    }

    private static void require(Map<String, String> sink, String key, boolean required) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            if (required) {
                Assumptions.assumeTrue(false, "env " + key + " not set — "
                        + "source ~/.llmrc / ~/.tavilyrc before running (INCONCLUSIVE)");
            }
            return;
        }
        sink.put(key, value);
    }
}

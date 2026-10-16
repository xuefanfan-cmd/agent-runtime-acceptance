package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;

import java.util.function.Consumer;

/**
 * Shared stack builder for the FEAT-019 Python multi-deep-research demo — spins up all three
 * demo processes (verify-agent, search-agent, deep-research) under framework-owned lifecycle so
 * SIT no longer depends on the dev-provided {@code scripts/smoke_real_llm.py --serve-only}.
 *
 * <p>Topology mirrors what smoke_real_llm.py used to build by hand: {@code deep-research} depends
 * on {@code search-agent} and {@code verify-agent}, with each downstream's resolved base URL
 * injected as an env var ({@code SEARCH_AGENT_URL} / {@code VERIFY_AGENT_URL}) — that's how the
 * Python demo modules read peers, not via Spring properties. All three YAML entries live under
 * {@code sut.agents.{verify-agent-python,search-agent-python,deep-research-python}.python} in
 * {@code application-openjiuwen.yml}.
 *
 * <p>A test that also wants a per-agent env override (e.g. {@code SEARCH_AGENT_FAIL_VENDOR} for
 * partial-failure) still adds it in its own {@link SutStack.Builder#agent(String, java.util.function.Consumer)}
 * override after this builder returns; see {@link PythonParallelSearchPartialFailureTest}.
 */
final class PythonDeepResearchStackSupport {

    static final String DEEP_RESEARCH = "deep-research-python";
    static final String SEARCH_AGENT = "search-agent-python";
    static final String VERIFY_AGENT = "verify-agent-python";

    private PythonDeepResearchStackSupport() {
    }

    /** The default three-agent Python demo stack. Streaming defaults to {@code true} (SSE tests). */
    static SutStack.Builder builder(TestConfig config) {
        return builder(config, a -> {});
    }

    /**
     * Variant that lets a caller add per-agent overrides (env vars, properties, etc.) to the
     * {@code search-agent-python} spawn — used by fail-injection tests to set
     * {@code SEARCH_AGENT_FAIL_VENDOR} before the process starts. Other two agents keep defaults.
     */
    static SutStack.Builder builder(TestConfig config, Consumer<SutStack.AgentBuilder> searchAgentCustomizer) {
        return SutStack.builder(config)
                .streaming(true)
                .agent(VERIFY_AGENT)
                .agent(SEARCH_AGENT, searchAgentCustomizer)
                .agent(DEEP_RESEARCH, a -> a
                        .downstreamEnv(SEARCH_AGENT, "SEARCH_AGENT_URL")
                        .downstreamEnv(VERIFY_AGENT, "VERIFY_AGENT_URL"));
    }
}

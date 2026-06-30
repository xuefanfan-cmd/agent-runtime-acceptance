package com.huawei.ascend.sit.cases.component.boundary;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;

/**
 * Builds a single-agent mainplan stack for C-09 with intentionally bad LLM wiring.
 *
 * <p>Managed (LOCAL default): launches mainplan jar with G3 Spring overrides.
 * Remote (optional): {@code sut.agents.mainplan.url-llm-down} pre-deployed bad instance.</p>
 */
final class LlmUnavailableStackSupport {

    private LlmUnavailableStackSupport() {
    }

    static SutStack.Builder buildMainplanStack(TestConfig config, boolean streaming) {
        if (LlmUnavailableSupport.isRemoteMode(config)) {
            return SutStack.builder(config)
                    .streaming(streaming)
                    .remoteAgent("mainplan", LlmUnavailableSupport.llmDownUrl(config));
        }
        return SutStack.builder(config)
                .streaming(streaming)
                .agent("mainplan", LlmUnavailableStackSupport::applyBadLlm);
    }

    private static void applyBadLlm(SutStack.AgentBuilder agent) {
        agent.property(LlmUnavailableSupport.MAINPLAN_API_KEY, LlmUnavailableSupport.BAD_LLM_API_KEY);
        agent.property(LlmUnavailableSupport.MAINPLAN_API_BASE, LlmUnavailableSupport.BAD_LLM_API_BASE);
    }
}

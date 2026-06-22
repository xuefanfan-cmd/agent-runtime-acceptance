package com.huawei.ascend.sit.cases.component.boundary;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;

/**
 * Builds a single-agent mainplan stack for C-06 (managed or remote).
 */
final class C06StackSupport {

    private C06StackSupport() {
    }

    static SutStack.Builder buildMainplanStack(TestConfig config, boolean streaming) {
        if (C06Gate.isRemoteMode(config)) {
            String mainplanUrl = config.getString("sut.agents.mainplan.url");
            return SutStack.builder(config)
                    .streaming(streaming)
                    .remoteAgent("mainplan", mainplanUrl);
        }
        return SutStack.builder(config)
                .streaming(streaming)
                .agent("mainplan", a -> {
                    String apiKey = System.getenv(C06Gate.LLM_KEY_ENV);
                    if (apiKey != null && !apiKey.isBlank()) {
                        a.property("main-plan-agent.api-key", apiKey);
                    }
                });
    }
}

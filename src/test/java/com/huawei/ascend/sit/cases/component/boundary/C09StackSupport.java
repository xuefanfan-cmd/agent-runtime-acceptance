package com.huawei.ascend.sit.cases.component.boundary;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;

/**
 * Builds a remote mainplan stack for C-09 (only {@code url-llm-down}, never {@code mainplan.url}).
 */
final class C09StackSupport {

    private C09StackSupport() {
    }

    static SutStack.Builder buildMainplanStack(TestConfig config, boolean streaming) {
        String llmDownUrl = C09Gate.llmDownUrl(config);
        if (llmDownUrl == null || llmDownUrl.isBlank()) {
            throw new IllegalStateException(
                    "C-09 requires " + C09Gate.URL_LLM_DOWN_KEY
                            + " pointing at the bad-LLM mainplan instance (port 13005)");
        }
        return SutStack.builder(config)
                .streaming(streaming)
                .remoteAgent("mainplan", llmDownUrl);
    }
}

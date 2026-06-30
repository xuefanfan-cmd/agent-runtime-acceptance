package com.huawei.ascend.sit.cases.component.boundary;

import com.huawei.ascend.sit.config.TestConfig;

/**
 * Stack-mode and bad-LLM constants for C-09.
 *
 * <p>Remote when {@code sut.agents.mainplan.url-llm-down} is set (SIT pre-deployed bad instance);
 * otherwise managed launch with G3 fault injection at startup.</p>
 */
final class LlmUnavailableSupport {

    static final String URL_LLM_DOWN_KEY = "sut.agents.mainplan.url-llm-down";

    /** G3 — invalid key even if a gateway were reachable. */
    static final String BAD_LLM_API_KEY = "sit-invalid-key-for-c09";

    /** G3 — unreachable on the agent host for fast connection failure. */
    static final String BAD_LLM_API_BASE = "http://127.0.0.1:9/v1";

    static final String MAINPLAN_API_KEY = "main-plan-agent.api-key";
    static final String MAINPLAN_API_BASE = "main-plan-agent.api-base";

    private LlmUnavailableSupport() {
    }

    static boolean isRemoteMode() {
        return isRemoteMode(TestConfig.load());
    }

    static boolean isRemoteMode(TestConfig config) {
        String url = config.getString(URL_LLM_DOWN_KEY, "");
        return url != null && !url.isBlank();
    }

    static String llmDownUrl() {
        return TestConfig.load().getString(URL_LLM_DOWN_KEY, "");
    }

    static String llmDownUrl(TestConfig config) {
        return config.getString(URL_LLM_DOWN_KEY, "");
    }
}

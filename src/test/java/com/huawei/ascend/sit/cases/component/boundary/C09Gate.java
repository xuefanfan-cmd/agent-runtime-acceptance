package com.huawei.ascend.sit.cases.component.boundary;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.config.TestEnvironment;

/**
 * Environment gate for C-09 (remote bad-LLM mainplan on {@code url-llm-down}).
 */
final class C09Gate {

    static final String URL_LLM_DOWN_KEY = "sut.agents.mainplan.url-llm-down";

    private C09Gate() {
    }

    static boolean isExecutable() {
        TestEnvironment env = TestEnvironment.current();
        return env == TestEnvironment.SIT || env == TestEnvironment.UAT;
    }

    static void requireLlmDownUrl() {
        String url = llmDownUrl();
        if (url == null || url.isBlank()) {
            throw new AssertionError(
                    "C-09 requires " + URL_LLM_DOWN_KEY
                            + " (bad-LLM mainplan, e.g. http://host:13005); missing → hard FAIL per case spec");
        }
    }

    static String llmDownUrl() {
        return TestConfig.load().getString(URL_LLM_DOWN_KEY, "");
    }

    static String llmDownUrl(TestConfig config) {
        return config.getString(URL_LLM_DOWN_KEY, "");
    }
}

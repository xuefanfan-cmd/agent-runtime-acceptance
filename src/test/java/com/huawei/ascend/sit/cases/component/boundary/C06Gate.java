package com.huawei.ascend.sit.cases.component.boundary;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.config.TestEnvironment;

/**
 * Environment gate for C-06 (remote mainplan or managed stack with LLM).
 */
final class C06Gate {

    static final String LLM_KEY_ENV = "SIT_LLM_API_KEY";

    private C06Gate() {
    }

    static boolean isExecutable() {
        TestEnvironment env = TestEnvironment.current();
        if (env != TestEnvironment.SIT && env != TestEnvironment.UAT) {
            return false;
        }
        if (isRemoteMode()) {
            return true;
        }
        return hasLlmKey();
    }

    static void requireLlmKeyIfManaged() {
        if (!isRemoteMode() && !hasLlmKey()) {
            throw new AssertionError("SIT_LLM_API_KEY or LLM_API_KEY must be set for managed C-06");
        }
    }

    static boolean isRemoteMode() {
        return isRemoteMode(TestConfig.load());
    }

    static boolean isRemoteMode(TestConfig config) {
        String url = config.getString("sut.agents.mainplan.url", "");
        return url != null && !url.isBlank();
    }

    static boolean hasLlmKey() {
        String sitKey = System.getenv(LLM_KEY_ENV);
        if (sitKey != null && !sitKey.isBlank()) {
            return true;
        }
        String llmKey = System.getenv("LLM_API_KEY");
        return llmKey != null && !llmKey.isBlank();
    }
}

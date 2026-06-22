package com.huawei.ascend.sit.cases.component.protocol;

import com.huawei.ascend.sit.config.TestEnvironment;

/**
 * Shared LLM / environment gate for A-06 protocol tests.
 */
final class A06LlmGate {

    static final String LLM_KEY_ENV = "SIT_LLM_API_KEY";

    private A06LlmGate() {
    }

    static boolean isExecutable() {
        TestEnvironment env = TestEnvironment.current();
        return env == TestEnvironment.SIT || env == TestEnvironment.UAT;
    }
}

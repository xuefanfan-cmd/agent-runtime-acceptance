package com.huawei.ascend.sit.cases.integration.checkpointer;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.config.TestEnvironment;
import org.testcontainers.DockerClientFactory;

/**
 * Environment gate for B-03 ({@code remote} pre-deployed stack vs local managed + Testcontainers).
 */
final class B03Gate {

    static final String LLM_KEY_ENV = "SIT_LLM_API_KEY";

    private B03Gate() {
    }

    static boolean isExecutable() {
        TestEnvironment env = TestEnvironment.current();
        if (env != TestEnvironment.SIT && env != TestEnvironment.UAT) {
            return false;
        }
        if (isRemoteMode()) {
            return true;
        }
        return isDockerAvailable() && hasLlmKey();
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

    private static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }
}

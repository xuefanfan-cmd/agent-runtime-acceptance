package com.huawei.ascend.sit.cases.integration.checkpointer;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.config.TestEnvironment;
import org.testcontainers.DockerClientFactory;

/**
 * Environment gate for B-04 (managed two-stack vs remote dual-endpoint).
 */
final class B04Gate {

    static final String URL_INMEMORY_KEY = "sut.agents.mainplan.url-inmemory";

    private B04Gate() {
    }

    static boolean isExecutable() {
        TestEnvironment env = TestEnvironment.current();
        if (env != TestEnvironment.SIT && env != TestEnvironment.UAT) {
            return false;
        }
        if (isRemoteMode()) {
            return hasRemotePhase1Url() && hasRemotePhase2Url();
        }
        return B03Gate.hasLlmKey() && isDockerAvailable();
    }

    static boolean isRemoteMode() {
        return B03Gate.isRemoteMode();
    }

    static boolean hasRemotePhase1Url() {
        String url = TestConfig.load().getString(URL_INMEMORY_KEY, "");
        return url != null && !url.isBlank();
    }

    static boolean hasRemotePhase2Url() {
        String url = TestConfig.load().getString("sut.agents.mainplan.url", "");
        return url != null && !url.isBlank();
    }

    static String phase1RemoteUrl(TestConfig config) {
        String url = config.getString(URL_INMEMORY_KEY, "");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "B-04 remote Phase1 requires " + URL_INMEMORY_KEY
                            + " (in-memory mainplan endpoint)");
        }
        return url;
    }

    static String phase2RemoteUrl(TestConfig config) {
        String url = config.getString("sut.agents.mainplan.url", "");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "B-04 remote Phase2 requires sut.agents.mainplan.url (redis mainplan endpoint)");
        }
        return url;
    }

    private static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }
}

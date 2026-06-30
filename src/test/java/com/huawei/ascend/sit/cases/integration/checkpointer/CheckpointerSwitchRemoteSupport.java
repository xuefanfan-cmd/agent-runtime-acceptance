package com.huawei.ascend.sit.cases.integration.checkpointer;

import com.huawei.ascend.sit.config.TestConfig;

/**
 * Remote-endpoint helpers for B-04 dual-phase (in-memory vs redis pre-deployed mainplan).
 */
final class CheckpointerSwitchRemoteSupport {

    static final String URL_INMEMORY_KEY = "sut.agents.mainplan.url-inmemory";

    private CheckpointerSwitchRemoteSupport() {
    }

    static boolean isRemoteMode() {
        return CheckpointerRemoteMode.isRemoteMode();
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
}

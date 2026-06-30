package com.huawei.ascend.sit.cases.integration.checkpointer;

import com.huawei.ascend.sit.config.TestConfig;

/**
 * Stack-mode probe for B-03 / B-04 ({@code remote} pre-deployed mainplan vs local managed stack).
 */
final class CheckpointerRemoteMode {

    private CheckpointerRemoteMode() {
    }

    static boolean isRemoteMode() {
        return isRemoteMode(TestConfig.load());
    }

    static boolean isRemoteMode(TestConfig config) {
        String url = config.getString("sut.agents.mainplan.url", "");
        return url != null && !url.isBlank();
    }
}

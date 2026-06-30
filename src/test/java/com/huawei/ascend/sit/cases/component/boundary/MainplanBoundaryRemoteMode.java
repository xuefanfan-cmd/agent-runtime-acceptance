package com.huawei.ascend.sit.cases.component.boundary;

import com.huawei.ascend.sit.config.TestConfig;

/**
 * Stack-mode probe for C-06 / C-07 ({@code remote} pre-deployed mainplan vs local managed stack).
 */
final class MainplanBoundaryRemoteMode {

    private MainplanBoundaryRemoteMode() {
    }

    static boolean isRemoteMode() {
        return isRemoteMode(TestConfig.load());
    }

    static boolean isRemoteMode(TestConfig config) {
        String url = config.getString("sut.agents.mainplan.url", "");
        return url != null && !url.isBlank();
    }
}

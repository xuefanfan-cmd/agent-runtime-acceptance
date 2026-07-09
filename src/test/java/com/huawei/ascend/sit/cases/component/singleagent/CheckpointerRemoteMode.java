package com.huawei.ascend.sit.cases.component.singleagent;

import com.huawei.ascend.sit.config.TestConfig;

/**
 * Stack-mode probe for B-03 / B-04 ({@code remote} pre-deployed mainplan vs local managed stack).
 */
public final class CheckpointerRemoteMode {

    private CheckpointerRemoteMode() {
    }

    public static boolean isRemoteMode() {
        return isRemoteMode(TestConfig.load());
    }

    public static boolean isRemoteMode(TestConfig config) {
        String url = config.getString("sut.agents.mainplan.url", "");
        return url != null && !url.isBlank();
    }
}

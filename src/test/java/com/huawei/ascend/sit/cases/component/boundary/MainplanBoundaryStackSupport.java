package com.huawei.ascend.sit.cases.component.boundary;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;

/**
 * Builds a single-agent mainplan stack for C-06 / C-07 (managed or remote).
 */
final class MainplanBoundaryStackSupport {

    private MainplanBoundaryStackSupport() {
    }

    static SutStack.Builder buildMainplanStack(TestConfig config, boolean streaming) {
        if (MainplanBoundaryRemoteMode.isRemoteMode(config)) {
            String mainplanUrl = config.getString("sut.agents.mainplan.url");
            return SutStack.builder(config)
                    .streaming(streaming)
                    .remoteAgent("mainplan", mainplanUrl);
        }
        return SutStack.builder(config)
                .streaming(streaming)
                .agent("mainplan");
    }
}

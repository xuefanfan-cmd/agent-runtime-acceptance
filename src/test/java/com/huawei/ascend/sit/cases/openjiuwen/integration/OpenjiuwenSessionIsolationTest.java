package com.huawei.ascend.sit.cases.openjiuwen.integration;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.cases.openjiuwen.OpenjiuwenSessionIsolationRunner;
import com.huawei.ascend.sit.cases.openjiuwen.OpenjiuwenStackSupport;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.model.openjiuwen.OpenjiuwenSessionIsolationScenarioData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * OJ-04 — openjiuwen session isolation (different contextId, serial execution).
 *
 * <p>See {@code docs/cases/OJ-04-openjiuwen-session-isolation.md}.</p>
 */
@Tag("integration")
@Tag("openjiuwen")
class OpenjiuwenSessionIsolationTest extends BaseManagedStackTest {

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return OpenjiuwenStackSupport.mainplanSync(config);
    }

    @Test
    @DisplayName("OJ-04: 不同 contextId 串行两 session — 业务输出不串扰")
    void oj04_serialSessions_doNotCrossPollinate() {
        OpenjiuwenSessionIsolationScenarioData scenario = OpenjiuwenSessionIsolationScenarioData.loadDefault();
        OpenjiuwenSessionIsolationRunner.run(
                client(OpenjiuwenStackSupport.MAINPLAN),
                scenario,
                "OJ-04");
    }
}

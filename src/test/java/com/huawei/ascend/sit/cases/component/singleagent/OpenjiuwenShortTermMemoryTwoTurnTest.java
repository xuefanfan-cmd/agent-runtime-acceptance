package com.huawei.ascend.sit.cases.component.singleagent;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.cases.support.openjiuwen.OpenjiuwenStackSupport;
import com.huawei.ascend.sit.cases.support.openjiuwen.OpenjiuwenSyncTwoTurnRunner;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.model.openjiuwen.OpenjiuwenTwoTurnScenarioData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * OJ-03 — openjiuwen short-term memory across two turns (same contextId).
 *
 * <p>See {@code docs/cases/OJ-03-openjiuwen-short-term-memory-two-turn.md}.</p>
 */
@Tag("component")
@Tag("openjiuwen")
@Tag("smoke")
class OpenjiuwenShortTermMemoryTwoTurnTest extends BaseManagedStackTest {

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // 单 mainplan：Turn2 信息充分时可能 delegate trip 失败并停在 INPUT_REQUIRED（已允许）。
        // mainplan+trip / 全链栈需 SutStack 同时注入 remote-agents[i].name + url（见 SutStack.wireDownstream）。
        return OpenjiuwenStackSupport.mainplanSync(config);
    }

    @Test
    @DisplayName("OJ-03: 两轮同 contextId — Turn2 理解 Turn1 出差意图")
    void oj03_twoTurnSync_preservesShortTermContext() {
        OpenjiuwenTwoTurnScenarioData scenario = OpenjiuwenTwoTurnScenarioData.loadDefault();
        OpenjiuwenSyncTwoTurnRunner.run(client(OpenjiuwenStackSupport.MAINPLAN), scenario, "OJ-03");
    }
}

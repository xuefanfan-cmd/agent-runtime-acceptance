package com.huawei.ascend.sit.cases.openjiuwen.integration;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.cases.openjiuwen.OpenjiuwenStackSupport;
import com.huawei.ascend.sit.cases.openjiuwen.OpenjiuwenThreeTurnRunner;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.model.openjiuwen.OpenjiuwenThreeTurnScenarioData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OJ-05 — openjiuwen three-turn INPUT_REQUIRED collection (full chain, sync send).
 *
 * <p>See {@code docs/cases/OJ-05-openjiuwen-three-turn-input-required.md}.</p>
 */
@Tag("integration")
@Tag("openjiuwen")
class OpenjiuwenThreeTurnInputRequiredTest extends BaseManagedStackTest {

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return OpenjiuwenStackSupport.fullChainSync(config);
    }

    @Test
    @DisplayName("OJ-05: 三轮信息收集 — INPUT_REQUIRED→INPUT_REQUIRED→COMPLETED")
    void oj05_threeTurnSync_followsInputRequiredThenCompleted() {
        OpenjiuwenThreeTurnScenarioData scenario = OpenjiuwenThreeTurnScenarioData.loadDefault();
        long defaultTimeoutMs = OpenjiuwenStackSupport.timeoutMs(config);

        OpenjiuwenThreeTurnRunner.Result result = OpenjiuwenThreeTurnRunner.run(
                client(OpenjiuwenStackSupport.MAINPLAN),
                scenario,
                Map.of("sessionId", scenario.sessionId()),
                defaultTimeoutMs,
                "OJ-05");

        assertThat(result.turns()).as("OJ-05 round count").hasSize(3);
        assertThat(result.turn(0).state())
                .as("OJ-05 turn-1 terminal state")
                .isIn(scenario.turns().get(0).resolvedAllowedStates());
        assertThat(result.turn(1).state())
                .as("OJ-05 turn-2 terminal state")
                .isIn(scenario.turns().get(1).resolvedAllowedStates());
        assertThat(result.turn(2).state())
                .as("OJ-05 turn-3 terminal state")
                .isIn(scenario.turns().get(2).resolvedAllowedStates());
        assertThat(result.turn(0).contextId())
                .as("OJ-05 contextId stable across turns")
                .isEqualTo(scenario.sessionId())
                .isEqualTo(result.turn(2).contextId());
    }
}

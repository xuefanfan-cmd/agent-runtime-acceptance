package com.huawei.ascend.sit.cases.component.boundary;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.model.component.boundary.C09ScenarioData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * C-09-Y — LLM unavailable on synchronous {@code message/send}.
 *
 * <p>Remote: {@code sut.agents.mainplan.url-llm-down} → bad-LLM mainplan (port 13005).</p>
 *
 * <p>See {@code docs/cases/C-09-llm-unavailable.md}.</p>
 */
@Tag("component")
@EnabledIf("com.huawei.ascend.sit.cases.component.boundary.C09Gate#isExecutable")
class LlmUnavailableSyncTest extends BaseManagedStackTest {

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return C09StackSupport.buildMainplanStack(config, false);
    }

    @Test
    @DisplayName("C-09-Y: 同步 LLM 不可用 → 非成功终态")
    void c09_syncLlmUnavailable_reachesNonSuccessTerminalState() throws InterruptedException {
        C09Gate.requireLlmDownUrl();
        C09ScenarioData scenario = C09ScenarioData.loadDefault();
        C09LlmUnavailableFlow.run(client("mainplan"), scenario, "C-09.Y");
    }
}

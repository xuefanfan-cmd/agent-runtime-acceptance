package com.huawei.ascend.sit.cases.component.boundary;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.model.component.boundary.LlmUnavailableScenarioData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * C-09-S — LLM unavailable on streaming {@code message/stream}.
 *
 * <p>Managed: bad LLM injected at launch via {@link LlmUnavailableStackSupport} (G3). Remote optional:
 * {@code sut.agents.mainplan.url-llm-down}. See {@code docs/cases/C-09-llm-unavailable.md}.</p>
 */
@Tag("component")
class LlmUnavailableStreamTest extends BaseManagedStackTest {

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return LlmUnavailableStackSupport.buildMainplanStack(config, true);
    }

    @Test
    @DisplayName("C-09-S: 流式 LLM 不可用 → 非成功终态")
    void c09_streamLlmUnavailable_reachesNonSuccessTerminalState() throws InterruptedException {
        LlmUnavailableScenarioData scenario = LlmUnavailableScenarioData.loadDefault();
        LlmUnavailableFlow.run(client("mainplan"), scenario, "C-09.S", true);
    }
}

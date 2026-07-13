package com.huawei.ascend.sit.cases.component.boundary;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * C-09-Y — LLM unavailable on synchronous {@code message/send}.
 *
 * <p>Managed: bad LLM injected at launch via {@link LlmUnavailableStackSupport} (G3). Remote optional:
 * {@code sut.agents.mainplan.url-llm-down}. See {@code docs/cases/reactagent/C-09-llm-unavailable.md}.</p>
 */
@Tag("component")
class LlmUnavailableSyncTest extends BaseManagedStackTest {

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return LlmUnavailableStackSupport.buildMainplanStack(config, false);
    }

    @Test
    @DisplayName("C-09-Y: 同步 LLM 不可用 → 非成功终态")
    void c09_syncLlmUnavailable_reachesNonSuccessTerminalState() throws InterruptedException {
        LlmUnavailableFlow.run(client("mainplan"), "C-09.Y", false);
    }
}

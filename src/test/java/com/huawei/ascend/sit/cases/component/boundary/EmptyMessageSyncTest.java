package com.huawei.ascend.sit.cases.component.boundary;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.model.component.boundary.EmptyMessageScenarioData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * C-06-Y — empty message on synchronous {@code message/send}.
 *
 * <p>Remote: {@code sut.agents.mainplan.url} → {@code http://host:13003}.</p>
 *
 * <p>LLM credentials are not checked in this class — configure {@code LLM_*} (or equivalent)
 * before launch for managed mode; remote mode uses LLM on the pre-deployed SUT. See
 * {@code docs/cases/C-06-empty-message.md}.</p>
 */
@Tag("component")
class EmptyMessageSyncTest extends BaseManagedStackTest {

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return MainplanBoundaryStackSupport.buildMainplanStack(config, false);
    }

    @Test
    @DisplayName("C-06-Y: 同步空消息 FAILED + 后置「你好」COMPLETED")
    void c06_syncEmptyMessage_failsThenHealthProbeCompletes() throws InterruptedException {
        EmptyMessageScenarioData scenario = EmptyMessageScenarioData.loadDefault();
        EmptyMessageFlow.run(client("mainplan"), scenario, "C-06.Y", false);
    }
}

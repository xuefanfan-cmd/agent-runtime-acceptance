package com.huawei.ascend.sit.cases.component.boundary;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * C-07-Y — long message on synchronous {@code message/send}.
 *
 * <p>Remote: {@code sut.agents.mainplan.url} → {@code http://host:13003}.</p>
 *
 * <p>LLM credentials are not checked in this class — configure {@code LLM_*} (or equivalent)
 * before launch for managed mode; remote mode uses LLM on the pre-deployed SUT. See
 * {@code docs/cases/reactagent/C-07-long-message.md}.</p>
 */
@Tag("component")
class LongMessageSyncTest extends BaseManagedStackTest {

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return MainplanBoundaryStackSupport.buildMainplanStack(config, false);
    }

    @Test
    @DisplayName("C-07-Y: 同步超长消息到达终态 + 后置「你好」COMPLETED")
    void c07_syncLongMessage_reachesTerminalState_thenHealthProbeCompletes() throws InterruptedException {
        LongTravelMessageFlow.run(client("mainplan"), "C-07.Y", false);
    }
}

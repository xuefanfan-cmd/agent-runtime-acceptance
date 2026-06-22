package com.huawei.ascend.sit.cases.component.boundary;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.model.component.boundary.C07ScenarioData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * C-07-S — long message on streaming {@code message/stream}.
 *
 * <p>Remote: {@code sut.agents.mainplan.url} → {@code http://host:13003}.</p>
 *
 * <p>See {@code docs/cases/C-07-long-message.md}.</p>
 */
@Tag("component")
@EnabledIf("com.huawei.ascend.sit.cases.component.boundary.C06Gate#isExecutable")
class LongMessageStreamTest extends BaseManagedStackTest {

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return C06StackSupport.buildMainplanStack(config, true);
    }

    @Test
    @DisplayName("C-07-S: 流式超长消息到达终态 + 后置「你好」COMPLETED")
    void c07_streamLongMessage_reachesTerminalState_thenHealthProbeCompletes() throws InterruptedException {
        C06Gate.requireLlmKeyIfManaged();
        C07ScenarioData scenario = C07ScenarioData.loadDefault();
        C07LongMessageFlow.run(client("mainplan"), scenario, "C-07.S", true);
    }
}

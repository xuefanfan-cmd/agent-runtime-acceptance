package com.huawei.ascend.sit.cases.component.boundary;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.model.component.boundary.C06ScenarioData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * C-06-S — empty message on streaming {@code message/stream}.
 *
 * <p>Remote: {@code sut.agents.mainplan.url} → {@code http://host:13003}.</p>
 *
 * <p>See {@code docs/cases/C-06-empty-message.md}.</p>
 */
@Tag("component")
@EnabledIf("com.huawei.ascend.sit.cases.component.boundary.C06Gate#isExecutable")
class EmptyMessageStreamTest extends BaseManagedStackTest {

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return C06StackSupport.buildMainplanStack(config, true);
    }

    @Test
    @DisplayName("C-06-S: 流式空消息 FAILED + 后置「你好」COMPLETED")
    void c06_streamEmptyMessage_failsThenHealthProbeCompletes() throws InterruptedException {
        C06Gate.requireLlmKeyIfManaged();
        C06ScenarioData scenario = C06ScenarioData.loadDefault();
        C06EmptyMessageFlow.run(client("mainplan"), scenario, "C-06.S", true);
    }
}

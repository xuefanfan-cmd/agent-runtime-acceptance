package com.huawei.ascend.sit.cases.component.boundary;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * C-06-S — empty message on streaming {@code message/stream}.
 *
 * <p>Remote: {@code sut.agents.mainplan.url} → {@code http://host:13003}.</p>
 *
 * <p>LLM credentials are not checked in this class — configure {@code LLM_*} (or equivalent)
 * before launch for managed mode; remote mode uses LLM on the pre-deployed SUT. Health probe
 * requires a working LLM at runtime. See {@code docs/cases/reactagent/C-06-empty-message.md}.</p>
 */
@Tag("component")
class EmptyMessageStreamTest extends BaseManagedStackTest {

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return MainplanBoundaryStackSupport.buildMainplanStack(config, true);
    }

    @Test
    @DisplayName("C-06-S: 流式空消息 FAILED + 后置「你好」COMPLETED")
    void c06_streamEmptyMessage_failsThenHealthProbeCompletes() throws InterruptedException {
        EmptyMessageFlow.run(client("mainplan"), "C-06.S", true);
    }
}

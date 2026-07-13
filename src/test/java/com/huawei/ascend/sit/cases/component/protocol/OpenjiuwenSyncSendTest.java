package com.huawei.ascend.sit.cases.component.protocol;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.client.TaskTextExtractor;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OJ-01 — openjiuwen A2A synchronous SendMessage minimal loop.
 *
 * <p>See {@code docs/cases/reactagent/OJ-01-openjiuwen-sync-send-minimal.md}.</p>
 */
@Tag("component")
@Tag("openjiuwen")
@Tag("smoke")
class OpenjiuwenSyncSendTest extends BaseManagedStackTest {

    private static final String MAINPLAN = "mainplan";
    /** Matches {@code testdata/component/protocol/oj-01-sync-send.json}. */
    private static final String INPUT_TEXT = "你好";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .streaming(false)
                .agent(MAINPLAN);
    }

    @Test
    @DisplayName("OJ-01: 同步 message/send — SUBMITTED→COMPLETED，回复非空")
    void oj01_syncSend_reachesCompletedWithNonBlankText() {
        InteractionFlow.of(client(MAINPLAN))
                .withTimeoutMs(config.getPollTimeoutSeconds() * 1000L)
                .send(INPUT_TEXT)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertTask(task -> assertThat(TaskTextExtractor.textOf(task))
                            .as("OJ-01.B response text")
                            .isNotBlank())
                .execute();
    }
}

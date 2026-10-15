/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.e2e.multi_agent_controller;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-031 §5 deterministic multi-agent group fixture smoke test.
 */
@Tag("e2e")
@Tag("multi-agent-controller")
@Tag("feat-031")
@Feature("FEAT-031: Studio DSL Java 承载")
class MultiAgentGroupSitE2ETest extends BaseManagedStackTest {

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config).agent("multi-agent-group-sit");
    }

    @Test
    @Story("MA-031-05-01: 01-b 标准首轮可用")
    @DisplayName("组启动后标准首轮调用正常完成")
    void startedGroupCompletesFirstStandardRequest() {
        InteractionFlow.of(client("multi-agent-group-sit"))
                .withContextId("ma-031-05-01-" + System.nanoTime())
                .send("group-start-canary")
                .awaitState(TaskState.TASK_STATE_COMPLETED)
                .assertGenerated(text -> assertThat(text)
                        .contains("main", "group-start-canary"))
                .execute();
    }

    @Test
    @Story("MA-031-05-02: 02-a 无信号时主 Agent 兜底")
    @DisplayName("无 handoff 或中断时首轮由主 Agent 应答")
    void firstTurnFallsBackToMainAgent() {
        InteractionFlow.of(client("multi-agent-group-sit"))
                .withContextId("ma-031-05-02-" + System.nanoTime())
                .send("main-fallback-canary")
                .awaitState(TaskState.TASK_STATE_COMPLETED)
                .assertGenerated(text -> assertThat(text)
                        .contains("main", "main-fallback-canary")
                        .doesNotContain("sub-a", "sub-b"))
                .execute();
    }

    @Test
    @Story("MA-031-05-03: 03-a handoff 转移且消费即清除")
    @DisplayName("handoff 仅转移一次且后续轮次回到主 Agent")
    void handoffTransfersOnceAndClearsContext() {
        InteractionFlow.of(client("multi-agent-group-sit"))
                .withContextId("ma-031-05-03-" + System.nanoTime())
                .send("go-b")
                .awaitState(TaskState.TASK_STATE_COMPLETED)
                .assertGenerated(text -> assertThat(text).contains("sub-b", "go-b"))
                .send("after-handoff")
                .awaitState(TaskState.TASK_STATE_COMPLETED)
                .assertGenerated(text -> assertThat(text)
                        .contains("main", "after-handoff")
                        .doesNotContain("sub-b"))
                .execute();
    }

    @Test
    @Story("MA-031-05-14: FEAT-002 标准入口接入")
    @DisplayName("多 Agent 组 fixture 经标准 A2A 调用完成")
    void groupIsExposedThroughStandardA2a() {
        A2aServiceClient client = client("multi-agent-group-sit");
        InteractionFlow.of(client)
                .withContextId("multi-agent-group-sit-" + System.nanoTime())
                .send("hello")
                .awaitState(TaskState.TASK_STATE_COMPLETED)
                .assertGenerated(text -> assertThat(text).contains("main", "hello"))
                .execute();
    }
}

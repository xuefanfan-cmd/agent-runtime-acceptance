package com.huawei.ascend.sit.cases.component.agents.pingpong;

import com.huawei.ascend.sit.base.BaseComponentTest;
import com.huawei.ascend.sit.client.InteractionFlow;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PingPongDemo Agent component tests.
 *
 * <p>PingPongDemo 是最简单的单轮智能体：发送消息后直接返回 COMPLETED 状态
 * 和一个 artifact。用于验证 A2A 协议基础交互链路的正确性。</p>
 */
@Tag("component")
@Tag("smoke")
class PingPongDemoTest extends BaseComponentTest {

    @Test
    @DisplayName("PingPongDemo: 发送你好 -> Agent 返回 COMPLETED 状态和 artifact")
    void pingPong_sendGreeting_shouldReturnCompletedWithArtifact() {
        InteractionFlow.of(a2aClient)
                .withMetadata(Map.of("userId", "manual-user", "agentId", "openjiuwen-react-agent",
                        "sessionId", "manual-session-001"))
                .withTimeoutMs(config.getPollTimeoutSeconds() * 1000L)
                .send("你好")
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertTask(task -> {
                        assertThat(task.status().state())
                                .as("Task 最终状态应为 COMPLETED")
                                .isEqualTo(TaskState.TASK_STATE_COMPLETED);
                        assertThat(task.artifacts())
                                .as("PingPongDemo 应返回至少一个 artifact")
                                .isNotEmpty();
                    })
                .execute();
    }
}

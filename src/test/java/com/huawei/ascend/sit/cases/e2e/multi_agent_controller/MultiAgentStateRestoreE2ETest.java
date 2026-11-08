/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.e2e.multi_agent_controller;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import com.huawei.ascend.sit.lifecycle.SutStack;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** FEAT-031 section 5 Redis-backed interruption recovery through the standard A2A entrypoint. */
@Tag("e2e")
@Tag("multi-agent-controller")
@Tag("feat-031")
@Feature("FEAT-031: Studio DSL Java 承载")
class MultiAgentStateRestoreE2ETest extends BaseManagedStackTest {
    private static final String SUT = "multi-agent-group-sit";
    private static final String REDIS_PREFIX = "openjiuwen.service.middleware.";
    private static final long FLOW_TIMEOUT_MS = 60_000L;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .streaming(true)
                .agent(SUT, agent -> agent
                        .profile("ma08")
                        .serviceBinding("redis", REDIS_PREFIX + "redis.ma-state.host", "{{host}}")
                        .serviceBinding("redis", REDIS_PREFIX + "redis.ma-state.port", "{{port}}")
                        .property(REDIS_PREFIX + "checkpointer.type", "redis")
                        .property(REDIS_PREFIX + "checkpointer.redis-ref", "ma-state")
                        .property(REDIS_PREFIX + "redis.ma-state.type", "standalone")
                        .property(REDIS_PREFIX + "redis.ma-state.database", "0")
                        .property(REDIS_PREFIX + "redis.ma-state.encrypted-password", ""));
    }

    @Test
    @Story("MA-031-05-08-a: 中断状态落库并跨重启续跑")
    @DisplayName("中断任务在 SUT 重启后由原成员继续并完成")
    void interruptedConversationResumesAfterRestart() {
        String contextId = context("resume");
        WaitingTask waiting = interrupt(contextId);

        restartSut();
        assertPersistedWaitingTask(waiting);

        InteractionFlow.FlowResult resumed = resume(waiting, "resume-after-restart");
        assertSameTask(waiting, resumed);
        assertResumedSubA(resumed.round(0).generatedText(), contextId);
    }

    @Test
    @Story("MA-031-05-08-b: 已完成动作不重放且会话隔离")
    @DisplayName("重启后已完成动作不重放且其他会话不消费中断状态")
    void completedActionsAreNotReplayedAndSessionsStayIsolated() {
        String interruptedContext = context("no-replay");
        String isolatedContext = context("isolated");
        WaitingTask waiting = interrupt(interruptedContext);

        restartSut();

        InteractionFlow.FlowResult isolated = InteractionFlow.of(client(SUT))
                .withTimeoutMs(FLOW_TIMEOUT_MS)
                .withContextId(isolatedContext)
                .send("isolation-probe")
                .awaitState(TaskState.TASK_STATE_COMPLETED)
                .execute();
        assertFreshMain(isolated.round(0).generatedText(), isolatedContext);

        InteractionFlow.FlowResult resumed = resume(waiting, "resume-without-replay");
        assertSameTask(waiting, resumed);
        assertResumedSubA(resumed.round(0).generatedText(), interruptedContext);
    }

    private WaitingTask interrupt(String contextId) {
        InteractionFlow.FlowResult result = InteractionFlow.of(client(SUT))
                .withTimeoutMs(FLOW_TIMEOUT_MS)
                .withContextId(contextId)
                .send("ask-user")
                .awaitState(TaskState.TASK_STATE_INPUT_REQUIRED)
                .execute();
        InteractionFlow.RoundResult round = result.round(0);
        assertThat(round.taskId()).as("interrupted task id").isNotBlank();
        assertThat(round.contextId()).as("interrupted context id").isEqualTo(contextId);
        return new WaitingTask(round.taskId(), contextId);
    }

    private InteractionFlow.FlowResult resume(WaitingTask waiting, String input) {
        return InteractionFlow.of(client(SUT))
                .withTimeoutMs(FLOW_TIMEOUT_MS)
                .withContextId(waiting.contextId())
                .withTaskId(waiting.taskId())
                .send(input)
                .awaitState(TaskState.TASK_STATE_COMPLETED)
                .execute();
    }

    private void restartSut() {
        int originalPort = stack.port(SUT);
        long originalPid = managedSut().pid();
        stack.stop(SUT);
        await().atMost(Duration.ofSeconds(15)).until(() -> !stack.isRunning(SUT));
        stack.start(SUT);
        assertThat(stack.port(SUT)).as("restarted SUT port").isEqualTo(originalPort);
        assertThat(managedSut().pid()).as("restarted SUT pid").isNotEqualTo(originalPid);
    }

    private void assertPersistedWaitingTask(WaitingTask waiting) {
        Task snapshot = client(SUT).getTask(waiting.taskId());
        assertThat(snapshot).as("Redis-backed Task snapshot after restart").isNotNull();
        assertThat(snapshot.id()).isEqualTo(waiting.taskId());
        assertThat(snapshot.contextId()).isEqualTo(waiting.contextId());
        assertThat(snapshot.status().state()).isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);
    }

    private static void assertSameTask(WaitingTask waiting, InteractionFlow.FlowResult resumed) {
        InteractionFlow.RoundResult round = resumed.round(0);
        assertThat(round.taskId()).as("resumed task id").isEqualTo(waiting.taskId());
        assertThat(round.contextId()).as("resumed context id").isEqualTo(waiting.contextId());
    }

    private static void assertResumedSubA(String generated, String contextId) {
        assertThat(generated)
                .contains("agent_id", "sub-a", "phase", "resumed", "conversation_id", contextId);
        assertCounter(generated, "pre_interrupt_actions", 1);
        assertCounter(generated, "resume_actions", 1);
    }

    private static void assertFreshMain(String generated, String contextId) {
        assertThat(generated)
                .contains("agent_id", "main", "phase", "fresh", "conversation_id", contextId)
                .doesNotContain("sub-a", "resumed");
        assertCounter(generated, "pre_interrupt_actions", 0);
        assertCounter(generated, "resume_actions", 0);
    }

    private static void assertCounter(String generated, String key, int expected) {
        assertThat(generated).containsPattern(
                "(?:\\\"?" + key + "\\\"?)\\s*[:=]\\s*" + expected + "(?:\\.0)?(?:\\D|$)");
    }

    private ManagedSutInstance managedSut() {
        assertThat(stack.managedInstance(SUT)).isInstanceOf(ManagedSutInstance.class);
        return (ManagedSutInstance) stack.managedInstance(SUT);
    }

    private static String context(String suffix) {
        return "feat031-ma-08-" + suffix + '-' + UUID.randomUUID().toString().substring(0, 8);
    }

    private record WaitingTask(String taskId, String contextId) {
    }
}

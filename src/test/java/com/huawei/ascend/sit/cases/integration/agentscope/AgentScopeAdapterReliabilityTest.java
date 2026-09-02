/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.huawei.ascend.sit.client.A2aServiceClient
 *  com.huawei.ascend.sit.client.InteractionFlow
 *  com.huawei.ascend.sit.client.InteractionFlow$FlowResult
 *  com.huawei.ascend.sit.config.TestConfig
 *  com.huawei.ascend.sit.lifecycle.SutStack
 *  com.huawei.ascend.sit.lifecycle.SutStack$Builder
 *  io.qameta.allure.Feature
 *  io.qameta.allure.Stories
 *  io.qameta.allure.Story
 *  org.a2aproject.sdk.spec.TaskState
 *  org.assertj.core.api.AbstractBooleanAssert
 *  org.assertj.core.api.AbstractComparableAssert
 *  org.assertj.core.api.AbstractStringAssert
 *  org.assertj.core.api.Assertions
 *  org.junit.jupiter.api.DisplayName
 *  org.junit.jupiter.api.Tag
 *  org.junit.jupiter.api.Tags
 *  org.junit.jupiter.api.Test
 */
package com.huawei.ascend.sit.cases.integration.agentscope;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.cases.integration.agentscope.AgentScopeAdapterFixtures;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;
import java.util.Set;
import org.a2aproject.sdk.spec.TaskState;
import org.assertj.core.api.AbstractBooleanAssert;
import org.assertj.core.api.AbstractComparableAssert;
import org.assertj.core.api.AbstractStringAssert;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

@Tags(value={@Tag(value="integration"), @Tag(value="manual")})
@Feature(value="FEAT-002: \u5f02\u6784\u667a\u80fd\u4f53\u6846\u67b6\u517c\u5bb9")
@Stories(value={@Story(value="agsc.parent-fail-on-unreachable: \u6837\u4f8b agent \u4e0d\u53ef\u8fbe \u2192 \u7236\u4efb\u52a1\u6536\u655b\u4e3a\u5931\u8d25\u7ec8\u6001"), @Story(value="agsc.parent-fail-on-mid-interrupt-kill: \u4e2d\u65ad\u6001\u65f6\u6837\u4f8b agent \u88ab\u6740 \u2192 \u7236\u4efb\u52a1\u4e0d\u5f97 COMPLETED\uff08\u65e0 End \u6d88\u606f MUST\uff09")})
class AgentScopeAdapterReliabilityTest
extends BaseManagedStackTest {
    private static final long TIMEOUT_MS = 60000L;
    private static final Set<TaskState> FAILURE_TERMINAL_STATES = Set.of(TaskState.TASK_STATE_FAILED, TaskState.TASK_STATE_CANCELED, TaskState.TASK_STATE_REJECTED);

    AgentScopeAdapterReliabilityTest() {
    }

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder((TestConfig)config).agent("travel-trip-agentscope");
    }

    @Test
    @DisplayName(value="R1: \u6837\u4f8b agent \u4e0d\u53ef\u8fbe \u2192 \u7236\u4efb\u52a1\u5728\u65f6\u9650\u5185\u6536\u655b\u5230\u5931\u8d25\u7ec8\u6001\uff0c\u4e0d\u5f97 COMPLETED")
    void sampleAgentUnreachableParentTaskFails() {
        AgentScopeAdapterFixtures.assumeTripReadyAndHotelUnreachable(this.config);
        A2aServiceClient trip = this.client("travel-trip-agentscope");
        InteractionFlow.FlowResult flow = InteractionFlow.of((A2aServiceClient)trip).withTimeoutMs(60000L).withContextId(AgentScopeAdapterFixtures.contextIdFor("r1-unreachable")).send("\u5e2e\u6211\u9884\u8ba2 BJ-001 \u7684 BJ-001-R1 \u623f\u578b,2026-09-01 \u5165\u4f4f\u5230 2026-09-02,\u5bbe\u5ba2\u59d3\u540d\u5f20\u4e09").mayReachState(TaskState.TASK_STATE_FAILED).assertThat(ctx -> {
            ((AbstractComparableAssert)Assertions.assertThat((Comparable)ctx.taskState()).as("R1: \u6837\u4f8b agent \u4e0d\u53ef\u8fbe\u65f6\u7236\u4efb\u52a1\u7ec8\u6001\u4e0d\u5f97\u4e3a COMPLETED\uff08feature \u00a75.1.2 MUST\uff09", new Object[0])).isNotEqualTo((Object)TaskState.TASK_STATE_COMPLETED);
            ((AbstractComparableAssert)Assertions.assertThat((Comparable)ctx.taskState()).as("R1: \u5e94\u6536\u655b\u5230\u5931\u8d25\u5bb6\u65cf\u7ec8\u6001 {FAILED, CANCELED, REJECTED}\uff0c\u5b9e\u9645 %s", new Object[]{ctx.taskState()})).isIn(FAILURE_TERMINAL_STATES);
            AgentScopeAdapterFixtures.assertNoOrderId(ctx.generatedText());
            String combined = (ctx.generatedText() == null ? "" : ctx.generatedText()) + " " + (ctx.answerText() == null ? "" : ctx.answerText());
            boolean hasCausalHint = AgentScopeAdapterReliabilityTest.containsIgnoreCase(combined, "hotel", "connect", "unreachable", "refused", "timeout", "fail", "error", "\u8fdc\u7aef", "\u4e0d\u53ef\u8fbe", "\u5931\u8d25", "\u9519\u8bef", "\u8fde\u63a5");
            ((AbstractBooleanAssert)Assertions.assertThat((boolean)hasCausalHint).as("R1: \u00a75.1.5 \u5f02\u5e38\u56e0\u679c\u94fe\u5e94\u4fdd\u7559\u53ef\u8fa8\u8bc6\u5173\u952e\u8bcd\uff08\u9519\u8bef\u4fe1\u606f\u4e0d\u5f97\u7a7a\u767d/\u4f2a\u9020\uff09", new Object[0])).isTrue();
        }).execute();
        ((AbstractComparableAssert)((AbstractComparableAssert)((AbstractComparableAssert)Assertions.assertThat((Comparable)flow.round(0).taskState()).as("R1: \u7ec8\u6001\u5728 %dms \u5185\u53ef\u8fa8\u8bc6\uff0c\u4e0d\u5f97\u4e3a\u7a7a/WORKING", new Object[]{60000L})).isNotNull()).isNotEqualTo((Object)TaskState.TASK_STATE_WORKING)).isNotEqualTo((Object)TaskState.TASK_STATE_SUBMITTED);
    }

    @Test
    @DisplayName(value="R2: \u4e2d\u65ad\u6001\u65f6\u6837\u4f8b agent \u88ab\u6740 \u2192 \u7eed\u8f6e\u4efb\u52a1\u4e0d\u5f97 COMPLETED\uff0ctext \u4e0d\u5f97\u542b\u771f\u8c03\u8bc1\u636e ID")
    void sampleAgentKilledMidInterruptParentNotCompleted() throws InterruptedException {
        AgentScopeAdapterFixtures.assumeTripAndHotelReady(this.config);
        A2aServiceClient trip = this.client("travel-trip-agentscope");
        String ctxId = AgentScopeAdapterFixtures.contextIdFor("r2-killed");
        InteractionFlow.FlowResult phaseA = InteractionFlow.of((A2aServiceClient)trip).withTimeoutMs(60000L).withContextId(ctxId).send("\u5e2e\u6211\u9884\u8ba2 BJ-001 \u7684 BJ-001-R1 \u623f\u578b,2026-09-01 \u5165\u4f4f\u5230 2026-09-02,\u5bbe\u5ba2\u59d3\u540d\u5f20\u4e09").awaitState(TaskState.TASK_STATE_INPUT_REQUIRED).execute();
        String taskId = phaseA.round(0).taskId();
        ((AbstractStringAssert)Assertions.assertThat((String)taskId).as("R2 phaseA taskId", new Object[0])).isNotBlank();
        System.err.println("[R2] \u26a0\ufe0f INPUT_REQUIRED \u5df2\u5230\u8fbe taskId=" + taskId + " \u2014 \u8bf7\u5728 30s \u5185**\u624b\u52a8\u5173\u505c\u6837\u4f8b agent \u8fdb\u7a0b**\uff08Ctrl+C \u6216 Stop-Process\uff09\uff0c\u6d4b\u8bd5\u5c06\u5728 30s \u540e\u53d1\u8d77\u7eed\u8f6e APPROVE");
        Thread.sleep(30000L);
        InteractionFlow.FlowResult phaseB = InteractionFlow.of((A2aServiceClient)trip).withTimeoutMs(60000L).withContextId(ctxId).withTaskId(taskId).send("APPROVE").withMetadata(AgentScopeAdapterFixtures.confirmationResumeMetadata()).mayReachState(TaskState.TASK_STATE_COMPLETED).assertThat(ctx -> {
            ((AbstractComparableAssert)Assertions.assertThat((Comparable)ctx.taskState()).as("R2: \u6837\u4f8b agent \u88ab\u6740\u540e\u7eed\u8f6e\u7ec8\u6001\u4e0d\u5f97\u4e3a COMPLETED\uff08feature \u00a75.1.2 MUST\uff1a\u65e0 End \u6d88\u606f\u4e0d\u5f97 COMPLETED\uff09", new Object[0])).isNotEqualTo((Object)TaskState.TASK_STATE_COMPLETED);
            AgentScopeAdapterFixtures.assertNoOrderId(ctx.generatedText());
            ((AbstractStringAssert)Assertions.assertThat((String)ctx.generatedText()).as("R2: SSE \u4e0d\u5f97\u51fa\u73b0\u4f2a\u9020\u7684\u6b63\u5e38\u6536\u5c3e\uff08'\u9884\u8ba2\u6210\u529f \u2705' \u7c7b\u5f53\u524d\u6837\u4f8b\u8bed\u4e49\uff09", new Object[0])).doesNotContain(new CharSequence[]{"\u9884\u8ba2\u6210\u529f"});
            String combined = (ctx.generatedText() == null ? "" : ctx.generatedText()) + " " + (ctx.answerText() == null ? "" : ctx.answerText());
            boolean hasCausalHint = AgentScopeAdapterReliabilityTest.containsIgnoreCase(combined, "hotel", "connect", "unreachable", "refused", "timeout", "fail", "error", "\u8fdc\u7aef", "\u4e0d\u53ef\u8fbe", "\u5931\u8d25", "\u9519\u8bef", "\u8fde\u63a5", "interrupt");
            ((AbstractBooleanAssert)Assertions.assertThat((boolean)hasCausalHint).as("R2: \u00a75.1.5 \u5f02\u5e38\u56e0\u679c\u94fe\u5e94\u4fdd\u7559\u53ef\u8fa8\u8bc6\u5173\u952e\u8bcd", new Object[0])).isTrue();
        }).execute();
        ((AbstractComparableAssert)((AbstractComparableAssert)Assertions.assertThat((Comparable)phaseB.round(0).taskState()).as("R2: \u7eed\u8f6e\u7ec8\u6001\u5728 %dms \u5185\u53ef\u8fa8\u8bc6\uff0c\u4e0d\u5f97\u4e3a\u7a7a/WORKING", new Object[]{60000L})).isNotNull()).isNotEqualTo((Object)TaskState.TASK_STATE_WORKING);
    }

    private static boolean containsIgnoreCase(String haystack, String ... needles) {
        if (haystack == null || haystack.isEmpty()) {
            return false;
        }
        String lower = haystack.toLowerCase();
        for (String n : needles) {
            if (n == null || n.isEmpty() || !lower.contains(n.toLowerCase())) continue;
            return true;
        }
        return false;
    }
}

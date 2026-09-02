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
 *  org.a2aproject.sdk.spec.Task
 *  org.a2aproject.sdk.spec.TaskState
 *  org.assertj.core.api.AbstractComparableAssert
 *  org.assertj.core.api.AbstractStringAssert
 *  org.assertj.core.api.Assertions
 *  org.assertj.core.api.ListAssert
 *  org.assertj.core.api.MapAssert
 *  org.assertj.core.api.ObjectAssert
 *  org.junit.jupiter.api.DisplayName
 *  org.junit.jupiter.api.Tag
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.assertj.core.api.AbstractComparableAssert;
import org.assertj.core.api.AbstractStringAssert;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.ListAssert;
import org.assertj.core.api.MapAssert;
import org.assertj.core.api.ObjectAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag(value="integration")
@Feature(value="FEAT-002: \u5f02\u6784\u667a\u80fd\u4f53\u6846\u67b6\u517c\u5bb9")
@Stories(value={@Story(value="agsc.confirmation-marker-shape: AgentScope confirmation \u4e2d\u65ad marker shape"), @Story(value="agsc.confirmation-reject: \u7cbe\u786e REJECT \u8f6c ConfirmResult \u77ed\u8def tool"), @Story(value="agsc.no-natural-language-confirmation: \u00a75.1.5 \u4e0d\u627f\u8bfa\u81ea\u7136\u8bed\u8a00\u786e\u8ba4\u8fb9\u754c")})
class AgentScopeAdapterConfirmationInterruptTest
extends BaseManagedStackTest {
    private static final long TIMEOUT_MS = 90000L;

    AgentScopeAdapterConfirmationInterruptTest() {
    }

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        AgentScopeAdapterFixtures.assumeTripAndHotelReady(config);
        return SutStack.builder((TestConfig)config).agent("travel-trip-agentscope");
    }

    @Test
    @DisplayName(value="I3: INPUT_REQUIRED \u5e27\u643a\u5e26 _interrupt marker\uff08items + \u6865\u63a5 toolCallId + toolName\uff09")
    void interruptFrameCarriesConfirmationMarker() {
        A2aServiceClient trip = this.client("travel-trip-agentscope");
        AtomicReference taskIdRef = new AtomicReference();
        InteractionFlow.of((A2aServiceClient)trip).withTimeoutMs(90000L).withContextId(AgentScopeAdapterFixtures.contextIdFor("i3-marker")).send("\u5e2e\u6211\u9884\u8ba2 BJ-001 \u7684 BJ-001-R1 \u623f\u578b,2026-09-01 \u5165\u4f4f\u5230 2026-09-02,\u5bbe\u5ba2\u59d3\u540d\u5f20\u4e09").awaitState(TaskState.TASK_STATE_INPUT_REQUIRED).assertThat(ctx -> {
            ((AbstractStringAssert)Assertions.assertThat((String)ctx.taskId()).as("\u9996\u8f6e INPUT_REQUIRED taskId", new Object[0])).isNotBlank();
            taskIdRef.set(ctx.taskId());
        }).execute();
        Task task = trip.getTask((String)taskIdRef.get());
        ((ObjectAssert)Assertions.assertThat((Object)task).as("getTask(taskId) \u8fd4\u56de\u503c", new Object[0])).isNotNull();
        ((AbstractComparableAssert)Assertions.assertThat((Comparable)task.status().state()).as("\u5feb\u7167\u7ec8\u6001\u5e94\u4e3a INPUT_REQUIRED", new Object[0])).isEqualTo((Object)TaskState.TASK_STATE_INPUT_REQUIRED);
        Object statusMessage = AgentScopeAdapterConfirmationInterruptTest.invokeSafely(task.status(), "message");
        ((ObjectAssert)Assertions.assertThat((Object)statusMessage).as("status.message \u975e\u7a7a\uff08AgentScope \u4e2d\u65ad\u4fe1\u606f\u8f7d\u4f53\uff09", new Object[0])).isNotNull();
        Map metadata = (Map)AgentScopeAdapterConfirmationInterruptTest.invokeSafely(statusMessage, "metadata");
        ((MapAssert)Assertions.assertThat((Map)metadata).as("status.message.metadata \u975e\u7a7a\uff08\u5e94\u542b _interrupt marker\uff09", new Object[0])).isNotNull();
        Map interrupt = (Map)metadata.get("_interrupt");
        ((MapAssert)Assertions.assertThat((Map)interrupt).as("metadata._interrupt \u975e\u7a7a\uff08AgentScope confirmation \u4e2d\u65ad\u6807\u8bb0\uff09", new Object[0])).isNotNull();
        ((ObjectAssert)Assertions.assertThat(interrupt.get("message")).as("_interrupt.message \u975e\u7a7a\uff08\u7528\u6237\u53ef\u8bfb\u63d0\u793a\uff09", new Object[0])).isNotNull();
        List items = (List)interrupt.get("items");
        ((ListAssert)((ListAssert)Assertions.assertThat((List)items).as("_interrupt.items[] \u81f3\u5c11 1 \u9879", new Object[0])).isNotNull()).isNotEmpty();
        Map firstItem = (Map)items.get(0);
        ((ObjectAssert)Assertions.assertThat(firstItem.get("toolCallId")).as("items[0].toolCallId \u975e\u7a7a\uff08adapter \u6865\u63a5\u5206\u914d\u7684\u5173\u8054\u6062\u590d\u6807\u8bc6\uff09", new Object[0])).isNotNull();
        Object toolName = firstItem.get("toolName");
        ((ObjectAssert)Assertions.assertThat(toolName).as("items[0].toolName \u975e\u7a7a\uff08external tool \u540d\uff0c\u4f9b\u5916\u90e8\u6267\u884c\uff09", new Object[0])).isNotNull();
        ((AbstractStringAssert)Assertions.assertThat((String)String.valueOf(toolName).toLowerCase()).as("items[0].toolName \u5e94\u542b 'hotel' \u5b50\u4e32\uff08\u5f53\u524d\u6837\u4f8b\uff1a\u7236\u4fa7\u770b\u5230\u7684\u59d4\u6258\u5de5\u5177\u540d travel-hotel\uff09", new Object[0])).contains(new CharSequence[]{"hotel"});
    }

    @Test
    @DisplayName(value="I1: \u7cbe\u786e REJECT \u8f6c ConfirmResult \u2014 COMPLETED + '\u53d6\u6d88' \u8bed\u4e49 + \u4e0d\u5f97\u542b\u771f\u8c03\u8bc1\u636e ID")
    void confirmationRejectShortCircuitsWithoutToolEvidence() {
        A2aServiceClient trip = this.client("travel-trip-agentscope");
        InteractionFlow.of((A2aServiceClient)trip).withTimeoutMs(90000L).withContextId(AgentScopeAdapterFixtures.contextIdFor("i1-reject")).send("\u5e2e\u6211\u9884\u8ba2 BJ-001 \u7684 BJ-001-R1 \u623f\u578b,2026-09-01 \u5165\u4f4f\u5230 2026-09-02,\u5bbe\u5ba2\u59d3\u540d\u5f20\u4e09").awaitState(TaskState.TASK_STATE_INPUT_REQUIRED).send("REJECT").withMetadata(AgentScopeAdapterFixtures.confirmationResumeMetadata()).awaitState(TaskState.TASK_STATE_COMPLETED).assertGenerated(text -> {
            AgentScopeAdapterFixtures.assertNoStackLeak(text);
            AgentScopeAdapterFixtures.assertRejectSemantics(text);
            AgentScopeAdapterFixtures.assertNoOrderId(text);
        }).execute();
    }

    @Test
    @DisplayName(value="I2: \u00a75.1.5 \u4e0d\u627f\u8bfa\u81ea\u7136\u8bed\u8a00\u786e\u8ba4 \u2014 \u7eed\u8f6e\u7f3a kind \u4e0d\u5f97 COMPLETED")
    void resumeMissingKindRejected() {
        A2aServiceClient trip = this.client("travel-trip-agentscope");
        InteractionFlow.FlowResult flow = InteractionFlow.of((A2aServiceClient)trip).withTimeoutMs(90000L).withContextId(AgentScopeAdapterFixtures.contextIdFor("i2-nokind")).send("\u5e2e\u6211\u9884\u8ba2 BJ-001 \u7684 BJ-001-R1 \u623f\u578b,2026-09-01 \u5165\u4f4f\u5230 2026-09-02,\u5bbe\u5ba2\u59d3\u540d\u5f20\u4e09").awaitState(TaskState.TASK_STATE_INPUT_REQUIRED).send("APPROVE").mayReachState(TaskState.TASK_STATE_COMPLETED).execute();
        TaskState finalState = flow.round(1).taskState();
        ((AbstractComparableAssert)Assertions.assertThat((Comparable)finalState).as("\u00a75.1.5 \u8fb9\u754c\uff1a\u65e0 kind \u5f15\u5bfc\u7684\u7eed\u8f6e\u4e0d\u5f97\u8d70\u81ea\u7136\u8bed\u8a00\u786e\u8ba4\u8def\u5f84\u6536\u655b\u4e3a COMPLETED", new Object[0])).isNotEqualTo((Object)TaskState.TASK_STATE_COMPLETED);
    }

    private static Object invokeSafely(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            return target.getClass().getMethod(methodName, new Class[0]).invoke(target, new Object[0]);
        }
        catch (NoSuchMethodException e) {
            String getter = "get" + Character.toUpperCase(methodName.charAt(0)) + methodName.substring(1);
            try {
                return target.getClass().getMethod(getter, new Class[0]).invoke(target, new Object[0]);
            }
            catch (Exception ex) {
                throw new AssertionError("SDK POJO accessor not found: " + methodName + " / " + getter + " on " + target.getClass().getName(), ex);
            }
        }
        catch (ReflectiveOperationException e) {
            throw new AssertionError("SDK POJO accessor invoke failed: " + methodName + " on " + target.getClass().getName(), e);
        }
    }
}

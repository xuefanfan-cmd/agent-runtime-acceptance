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
 *  org.assertj.core.api.AbstractBooleanAssert
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
import org.assertj.core.api.AbstractBooleanAssert;
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
@Stories(value={@Story(value="agsc.tool-result-marker: tool_result INPUT_REQUIRED \u5e27\u643a\u5e26 external tool name/arguments"), @Story(value="agsc.tool-result-resume: \u5916\u90e8\u7ed3\u679c\u7eed\u8f6e \u2192 ToolResultBlock \u2192 COMPLETED"), @Story(value="agsc.tool-result-kind-contract: \u7eed\u8f6e kind=tool_result \u5f3a\u5236\u5951\u7ea6\uff08\u00a75.1.5 \u8fb9\u754c\u770b\u5b88\uff09")})
class AgentScopeAdapterToolResultInterruptTest
extends BaseManagedStackTest {
    private static final long TIMEOUT_MS = 120000L;

    AgentScopeAdapterToolResultInterruptTest() {
    }

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        AgentScopeAdapterFixtures.assumeHarnessReady(config);
        return SutStack.builder((TestConfig)config).agent("agentscope-hotel-harness");
    }

    @Test
    @DisplayName(value="T1: tool_result INPUT_REQUIRED \u5e27\u643a\u5e26 external tool name + arguments")
    void toolResultInterruptCarriesExternalToolNameAndArguments() {
        Map argsMap;
        A2aServiceClient harness = this.client("agentscope-hotel-harness");
        AtomicReference taskIdRef = new AtomicReference();
        InteractionFlow.of((A2aServiceClient)harness).withTimeoutMs(120000L).withContextId(AgentScopeAdapterFixtures.contextIdFor("t1-marker")).send("\u67e5\u4e00\u4e0b\u5ba2\u6237 C001 \u7684\u5408\u7ea6\u7b49\u7ea7").awaitState(TaskState.TASK_STATE_INPUT_REQUIRED).assertThat(ctx -> {
            ((AbstractStringAssert)Assertions.assertThat((String)ctx.taskId()).as("\u9996\u8f6e INPUT_REQUIRED taskId", new Object[0])).isNotBlank();
            taskIdRef.set(ctx.taskId());
        }).execute();
        Task task = harness.getTask((String)taskIdRef.get());
        ((ObjectAssert)Assertions.assertThat((Object)task).as("getTask(taskId) \u8fd4\u56de\u503c", new Object[0])).isNotNull();
        ((AbstractComparableAssert)Assertions.assertThat((Comparable)task.status().state()).as("\u5feb\u7167\u7ec8\u6001\u5e94\u4e3a INPUT_REQUIRED\uff08AgentScope TOOL_SUSPENDED \u2192 adapter \u7ffb\u8bd1\uff09", new Object[0])).isEqualTo((Object)TaskState.TASK_STATE_INPUT_REQUIRED);
        Object statusMessage = AgentScopeAdapterToolResultInterruptTest.invokeSafely(task.status(), "message");
        ((ObjectAssert)Assertions.assertThat((Object)statusMessage).as("status.message \u975e\u7a7a\uff08tool_result \u4e2d\u65ad\u4fe1\u606f\u8f7d\u4f53\uff09", new Object[0])).isNotNull();
        Map metadata = (Map)AgentScopeAdapterToolResultInterruptTest.invokeSafely(statusMessage, "metadata");
        ((MapAssert)Assertions.assertThat((Map)metadata).as("status.message.metadata \u975e\u7a7a", new Object[0])).isNotNull();
        Map interrupt = (Map)metadata.get("_interrupt");
        ((MapAssert)Assertions.assertThat((Map)interrupt).as("metadata._interrupt \u975e\u7a7a\uff08tool_result \u4e2d\u65ad\u6807\u8bb0\uff09", new Object[0])).isNotNull();
        Map payload = (Map)interrupt.get("payload");
        List items = payload != null ? (List)payload.get("items") : (List)interrupt.get("items");
        ((ListAssert)((ListAssert)Assertions.assertThat((List)items).as("_interrupt.payload.items[] \u81f3\u5c11 1 \u9879\uff08tool_result \u7ed3\u6784\uff0c\u56de\u9000\u5230 _interrupt.items[]\uff09", new Object[0])).isNotNull()).isNotEmpty();
        Map first = (Map)items.get(0);
        Object toolName = first.get("name") != null ? first.get("name") : first.get("toolName");
        ((ObjectAssert)Assertions.assertThat(toolName).as("items[0].name/toolName \u975e\u7a7a\uff08external tool \u540d\uff09", new Object[0])).isNotNull();
        ((AbstractStringAssert)Assertions.assertThat((String)String.valueOf(toolName)).as("items[0].name \u5e94\u542b external tool \u7279\u5f81\uff08\u5f53\u524d\u6837\u4f8b\uff1a%s\uff09", new Object[]{"lookup_customer_profile"})).contains(new CharSequence[]{"lookup_customer_profile"});
        Object args = first.get("arguments");
        boolean hasArgs = args instanceof Map ? !(argsMap = (Map)args).isEmpty() : first.containsKey("args") || first.get("message") != null && !String.valueOf(first.get("message")).isBlank();
        ((AbstractBooleanAssert)Assertions.assertThat((boolean)hasArgs).as("items[0] \u5e94\u542b\u53c2\u6570\u4fe1\u606f\uff08arguments Map \u975e\u7a7a\uff0c\u5f53\u524d\u6837\u4f8b\u5e94\u542b customer_id/attribute\uff09", new Object[0])).isTrue();
        ((AbstractBooleanAssert)Assertions.assertThat((boolean)first.containsKey("toolCallId")).as("tool_result \u4fa7 items[0] \u4e0d\u5e94\u542b toolCallId \u5b57\u6bb5\uff08\u00a72\uff1a\u4e0d\u5f97\u66b4\u9732\u5185\u90e8 tool-call ID\uff09", new Object[0])).isFalse();
    }

    @Test
    @DisplayName(value="T2: \u5916\u90e8\u7ed3\u679c\u7eed\u8f6e \u2192 ToolResultBlock \u2192 COMPLETED + \u7ed3\u679c\u88ab LLM \u6c47\u603b")
    void externalResultResumesToCompleted() {
        A2aServiceClient harness = this.client("agentscope-hotel-harness");
        InteractionFlow.of((A2aServiceClient)harness).withTimeoutMs(120000L).withContextId(AgentScopeAdapterFixtures.contextIdFor("t2-resume")).send("\u67e5\u4e00\u4e0b\u5ba2\u6237 C001 \u7684\u5408\u7ea6\u7b49\u7ea7").awaitState(TaskState.TASK_STATE_INPUT_REQUIRED).send("VIP \u94c2\u91d1,\u6708\u5ea6\u5dee\u65c5\u4e0a\u9650 3000 \u5143,\u504f\u597d\u54c1\u724c:\u5168\u5b63/\u4e9a\u6735").withMetadata(AgentScopeAdapterFixtures.toolResultResumeMetadata()).awaitState(TaskState.TASK_STATE_COMPLETED).assertGenerated(text -> {
            AgentScopeAdapterFixtures.assertNoStackLeak(text);
            boolean referencesExternalResult = text.contains("VIP") || text.contains("\u94c2\u91d1") || text.contains("3000") || text.contains("\u5168\u5b63") || text.contains("\u4e9a\u6735");
            ((AbstractBooleanAssert)Assertions.assertThat((boolean)referencesExternalResult).as("\u6700\u7ec8 text \u5e94\u5f15\u7528\u5916\u90e8\u7ed3\u679c\u5173\u952e\u5b57\u6bb5\uff08\u8bc1\u660e ToolResultBlock \u6062\u590d\u8def\u5f84\u751f\u6548\uff09", new Object[0])).isTrue();
        }).execute();
    }

    @Test
    @DisplayName(value="T3: \u00a75.1.5 \u8fb9\u754c \u2014 tool_result \u7eed\u8f6e\u7f3a kind \u4e0d\u5f97 COMPLETED")
    void resumeMissingKindRejected_toolResult() {
        A2aServiceClient harness = this.client("agentscope-hotel-harness");
        InteractionFlow.FlowResult flow = InteractionFlow.of((A2aServiceClient)harness).withTimeoutMs(120000L).withContextId(AgentScopeAdapterFixtures.contextIdFor("t3-nokind")).send("\u67e5\u4e00\u4e0b\u5ba2\u6237 C001 \u7684\u5408\u7ea6\u7b49\u7ea7").awaitState(TaskState.TASK_STATE_INPUT_REQUIRED).send("VIP \u94c2\u91d1,\u6708\u5ea6\u5dee\u65c5\u4e0a\u9650 3000 \u5143,\u504f\u597d\u54c1\u724c:\u5168\u5b63/\u4e9a\u6735").mayReachState(TaskState.TASK_STATE_COMPLETED).execute();
        TaskState finalState = flow.round(1).taskState();
        ((AbstractComparableAssert)Assertions.assertThat((Comparable)finalState).as("\u00a75.1.5 \u8fb9\u754c\uff1atool_result \u7eed\u8f6e\u7f3a kind \u65f6\u4e0d\u5f97\u8d70\u5b57\u9762\u91cf/\u81ea\u7136\u8bed\u8a00\u8def\u5f84\u6536\u655b\u4e3a COMPLETED", new Object[0])).isNotEqualTo((Object)TaskState.TASK_STATE_COMPLETED);
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

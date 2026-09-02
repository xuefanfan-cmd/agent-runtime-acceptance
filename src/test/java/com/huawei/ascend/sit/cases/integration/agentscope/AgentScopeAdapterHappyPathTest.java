/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.huawei.ascend.sit.client.A2aServiceClient
 *  com.huawei.ascend.sit.client.InteractionFlow
 *  com.huawei.ascend.sit.config.TestConfig
 *  com.huawei.ascend.sit.lifecycle.SutStack
 *  com.huawei.ascend.sit.lifecycle.SutStack$Builder
 *  io.qameta.allure.Feature
 *  io.qameta.allure.Story
 *  org.a2aproject.sdk.spec.TaskState
 *  org.assertj.core.api.AbstractBooleanAssert
 *  org.assertj.core.api.Assertions
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
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.TaskState;
import org.assertj.core.api.AbstractBooleanAssert;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag(value="integration")
@Feature(value="FEAT-002: \u5f02\u6784\u667a\u80fd\u4f53\u6846\u67b6\u517c\u5bb9")
@Story(value="agsc.happy-path: AgentScope \u672c\u5730 ReActAgent \u6302\u8f7d + confirmation APPROVE \u95ed\u73af")
class AgentScopeAdapterHappyPathTest
extends BaseManagedStackTest {
    private static final long TIMEOUT_MS = 90000L;

    AgentScopeAdapterHappyPathTest() {
    }

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        AgentScopeAdapterFixtures.assumeTripAndHotelReady(config);
        return SutStack.builder((TestConfig)config).agent("travel-trip-agentscope");
    }

    @Test
    @DisplayName(value="H1: \u65e0\u4e2d\u65ad\u67e5\u8be2\u94fe\u8def\u4e32\u901a \u2014 \u7ec8\u6001 COMPLETED + \u5019\u9009\u5173\u952e\u8bcd")
    void noInterruptQueryReachesCompleted() {
        A2aServiceClient trip = this.client("travel-trip-agentscope");
        InteractionFlow.of((A2aServiceClient)trip).withTimeoutMs(90000L).withContextId(AgentScopeAdapterFixtures.contextIdFor("h1-noint-query")).send("\u5e2e\u6211\u67e5\u4e00\u4e0b 2026-09-01 \u5230 2026-09-02 \u5317\u4eac 800 \u5143\u4ee5\u5185\u7684\u56db\u661f\u7ea7\u9152\u5e97").awaitState(TaskState.TASK_STATE_COMPLETED).assertGenerated(text -> {
            AgentScopeAdapterFixtures.assertNoStackLeak(text);
            int hits = AgentScopeAdapterHappyPathTest.countOccurrences(text, "\u9152\u5e97");
            boolean brand = text.contains("\u4e9a\u6735") || text.contains("\u6854\u5b50") || text.contains("\u5e0c\u5c14\u987f") || text.contains("\u6c49\u5ead") || text.contains("\u5982\u5bb6");
            ((AbstractBooleanAssert)Assertions.assertThat((hits >= 2 || brand ? 1 : 0) != 0).as("\u6700\u7ec8 text \u5e94\u542b\u81f3\u5c11 2 \u5904 '\u9152\u5e97' \u5173\u952e\u8bcd\u6216\u5df2\u77e5\u54c1\u724c\u4e4b\u4e00\uff08\u5b9e\u9645 hits=%d\uff09", new Object[]{hits})).isTrue();
        }).execute();
    }

    @Test
    @DisplayName(value="H2: \u786e\u5b9a\u6027 tool \u67e5\u8be2\u94fe\u8def\u4e32\u901a \u2014 \u7ec8\u6001 COMPLETED + tool \u7a33\u5b9a\u5b57\u6bb5\u53ef\u65ad\u8a00")
    void deterministicToolQueryReachesCompleted() {
        A2aServiceClient trip = this.client("travel-trip-agentscope");
        InteractionFlow.of((A2aServiceClient)trip).withTimeoutMs(90000L).withContextId(AgentScopeAdapterFixtures.contextIdFor("h2-noint-detail")).send("\u67e5\u4e00\u4e0b BJ-001 \u8fd9\u5bb6\u9152\u5e97\u7684\u8be6\u60c5,\u4ee5\u53ca\u6240\u6709\u623f\u578b").awaitState(TaskState.TASK_STATE_COMPLETED).assertGenerated(text -> {
            AgentScopeAdapterFixtures.assertNoStackLeak(text);
            boolean hotelHit = text.contains("BJ-001");
            boolean roomHit = text.contains("BJ-001-R1") || text.contains("BJ-001-R2");
            ((AbstractBooleanAssert)Assertions.assertThat((hotelHit || roomHit ? 1 : 0) != 0).as("\u6700\u7ec8 text \u5e94\u542b BJ-001 hotel ID\uff08tool \u771f\u8c03\u8bc1\u636e\uff09\u6216 BJ-001-R* \u623f\u578b ID\uff08\u66f4\u5f3a\uff09", new Object[0])).isTrue();
        }).execute();
    }

    @Test
    @DisplayName(value="H3: confirmation APPROVE \u95ed\u73af \u2014 INPUT_REQUIRED \u2192 APPROVE \u2192 COMPLETED + tool \u771f\u8c03\u8bc1\u636e")
    void confirmationApproveReachesCompletedWithToolEvidence() {
        A2aServiceClient trip = this.client("travel-trip-agentscope");
        InteractionFlow.of((A2aServiceClient)trip).withTimeoutMs(90000L).withContextId(AgentScopeAdapterFixtures.contextIdFor("h3-approve")).send("\u5e2e\u6211\u9884\u8ba2 BJ-001 \u7684 BJ-001-R1 \u623f\u578b,2026-09-01 \u5165\u4f4f\u5230 2026-09-02,\u5bbe\u5ba2\u59d3\u540d\u5f20\u4e09").awaitState(TaskState.TASK_STATE_INPUT_REQUIRED).send("APPROVE").withMetadata(AgentScopeAdapterFixtures.confirmationResumeMetadata()).awaitState(TaskState.TASK_STATE_COMPLETED).assertGenerated(text -> {
            AgentScopeAdapterFixtures.assertNoStackLeak(text);
            AgentScopeAdapterFixtures.assertOrderIdPresent(text);
        }).execute();
    }

    private static int countOccurrences(String text, String needle) {
        if (text == null || text.isBlank() || needle == null || needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) != -1) {
            ++count;
            idx += needle.length();
        }
        return count;
    }
}

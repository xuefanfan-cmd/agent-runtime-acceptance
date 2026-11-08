/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.dynamic_model_config;

import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** FEAT-050-CORE §4 SC-CORE-06 / §5.1.2：同一会话不同轮次允许使用不同模型，且上下文不丢。 */
@Tag("integration")
@Tag("dynamic-model-config")
@Tag("feat-050")
@Feature("FEAT-050: 动态模型配置（agent-core）")
class DynamicModelConversationAcceptanceTest extends DynamicModelStackTestBase {

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return carrier(config, endpointA, endpointB, Map.of("dmcsit.default-model-id", "A", "dmcsit.instance-enabled", "true", "dmcsit.instance-model-id", "A"));
    }

    @Test
    @Story("dmc.conversation.switch: 同会话不同轮次使用不同模型")
    @DisplayName("同一会话第一轮用 A、第二轮用 B，两轮分别落到各自端点且第二轮带上第一轮内容")
    void sameConversationSwitchesModels() {
        String contextId = "dmc-conversation-switch-" + System.nanoTime();
        String firstRoundToken = "dmc-first-round-token-" + System.nanoTime();
        int baselineA = endpointA.requestCount();
        int baselineB = endpointB.requestCount();

        InteractionFlow.RoundResult first = sendInSession(contextId, firstRoundToken + " reply briefly.",
                Map.of("model_name", "A"));
        InteractionFlow.RoundResult second = sendInSession(contextId,
                "Quote my previous message verbatim, then reply briefly.", Map.of("model_name", "B"));

        assertRealModelAnswered(first, "DynamicModelConversationAcceptanceTest");
        assertRealModelAnswered(second, "DynamicModelConversationAcceptanceTest");
        assertThat(endpointA.requestsSince(baselineA)).as("A 端点应收到第一轮调用").hasSize(1);
        assertThat(endpointB.requestsSince(baselineB)).as("B 端点应收到第二轮调用").hasSize(1);

        String secondRoundBody = endpointB.requestsSince(baselineB).get(0).body().toString();
        assertThat(secondRoundBody)
                .as("第二轮发往 B 的请求体应包含第一轮内容（会话上下文不丢）")
                .contains(firstRoundToken);
    }
}

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

/**
 * FEAT-050-CORE §5.1.2: ❷ 实例级默认模型优先于 ❸ 进程级全局默认模型。
 *
 * <p>Carrier wiring: instance default = B, process-wide default = A. A request without a model name
 * must therefore be served by B.</p>
 */
@Tag("integration")
@Tag("dynamic-model-config")
@Tag("feat-050")
@Feature("FEAT-050: 动态模型配置（agent-core）")
class DynamicModelInstanceDefaultAcceptanceTest extends DynamicModelStackTestBase {

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return carrier(config, endpointA, endpointB, Map.of("dmcsit.default-model-id", "A", "dmcsit.instance-enabled", "true", "dmcsit.instance-model-id", "B"));
    }

    @Test
    @Story("dmc.route.instance-default: 实例默认优先于进程全局默认")
    @DisplayName("实例默认模型为 B、全局默认模型为 A 时，未指定模型的请求由 B 完成")
    void instanceDefaultWinsOverGlobalDefault() {
        int baselineA = endpointA.requestCount();
        int baselineB = endpointB.requestCount();

        InteractionFlow.RoundResult round = send("dmc-route-instance-default", "Reply with one short sentence.",
                Map.of());

        assertRealModelAnswered(round, "DynamicModelInstanceDefaultAcceptanceTest");
        assertThat(endpointB.requestsSince(baselineB)).as("B 端点应收到本次调用").hasSize(1);
        assertThat(endpointA.requestsSince(baselineA)).as("A 端点不应收到本次调用").isEmpty();
    }
}

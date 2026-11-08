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
 * FEAT-050-CORE §5.1.2 ❸ 级：进程级全局默认模型。
 *
 * <p>载体接线：实例**不配置**模型（instance-enabled=false），进程池启用且全局默认=B。此时不传模型名
 * 的请求必须由 ❸ 级全局默认完成 → 应答来自 B 端点。若实现忽略该级、退回其他来源，断言会红。</p>
 *
 * <p>存在意义：`dmc.route.default` 用"实例默认 A = 全局默认 A"的配置，无法区分 ❷ 与 ❸；本用例专门
 * 把 ❸ 单独隔离出来，避免"优先级链只验证了 ❷"的假覆盖。</p>
 */
@Tag("integration")
@Tag("dynamic-model-config")
@Tag("feat-050")
@Feature("FEAT-050: 动态模型配置（agent-core）")
class DynamicModelGlobalDefaultAcceptanceTest extends DynamicModelStackTestBase {

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return carrier(config, endpointA, endpointB, Map.of("dmcsit.pool-enabled", "true", "dmcsit.default-model-id", "B", "dmcsit.instance-enabled", "false"));
    }

    @Test
    @Story("dmc.route.global-default: 实例未配置模型时走 ❸ 全局默认")
    @DisplayName("实例不配置模型、全局默认=B 时，不传模型名的请求由 B 端点完成")
    void globalDefaultIsUsedWhenInstanceHasNoModel() {
        int baselineA = endpointA.requestCount();
        int baselineB = endpointB.requestCount();

        InteractionFlow.RoundResult round = send("dmc-route-global-default", "Reply with one short sentence.",
                Map.of());

        assertRealModelAnswered(round, "DynamicModelGlobalDefaultAcceptanceTest");
        assertThat(endpointB.requestsSince(baselineB)).as("B 端点应收到本次调用").hasSize(1);
        assertThat(endpointA.requestsSince(baselineA)).as("A 端点不应收到本次调用").isEmpty();
    }
}

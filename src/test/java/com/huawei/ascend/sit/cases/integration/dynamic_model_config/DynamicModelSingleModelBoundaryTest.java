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
 * 模型基数下限边界：只有 1 个可达模型时，"动态传入模型"没有可观测差异。
 *
 * <p><b>本用例不计入路由能力覆盖（non-gating）</b>：池中只有一个可达模型（实例默认 A）时，
 * 不传模型名、传 A、传未登记名字三种请求都会落到同一个端点，无法用"落到哪个端点"区分
 * ❶/❷/❸ 是否生效。它只证明"单模型配置下行为稳定、不报错"，不能证明请求级路由成立——
 * 这正是把模型基数写进输入契约的原因。</p>
 */
@Tag("integration")
@Tag("dynamic-model-config")
@Tag("feat-050")
@Feature("FEAT-050: 动态模型配置（agent-core）")
class DynamicModelSingleModelBoundaryTest extends DynamicModelStackTestBase {

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // 进程级模型池关闭，只保留实例级默认模型 A → 可达模型基数为 1。
        return carrier(config, endpointA, endpointB, Map.of("dmcsit.pool-enabled", "false", "dmcsit.instance-enabled", "true", "dmcsit.instance-model-id", "A", "dmcsit.default-model-id", ""));
    }

    @Test
    @Story("dmc.route.single-model: 单模型下限边界（不计入路由覆盖）")
    @DisplayName("可达模型只有 1 个时，三种请求都落到同一端点且不报错")
    void singleModelCaseHasNoObservableRoutingDifference() {
        int baselineA = endpointA.requestCount();
        int baselineB = endpointB.requestCount();

        InteractionFlow.RoundResult withoutName = send("dmc-single-model-default",
                "Reply with one short sentence.", Map.of());
        InteractionFlow.RoundResult withSameName = send("dmc-single-model-explicit",
                "Reply with one short sentence.", Map.of("model_name", "A"));
        InteractionFlow.RoundResult withUnknownName = send("dmc-single-model-unknown",
                "Reply with one short sentence.", Map.of("model_name", "ghost-" + System.nanoTime()));

        assertRealModelAnswered(withoutName, "DynamicModelSingleModelBoundaryTest");
        assertRealModelAnswered(withSameName, "DynamicModelSingleModelBoundaryTest");
        assertRealModelAnswered(withUnknownName, "DynamicModelSingleModelBoundaryTest");
        assertThat(endpointA.requestsSince(baselineA)).as("三次请求都应由 A 端点完成").hasSize(3);
        assertThat(endpointB.requestsSince(baselineB)).as("B 端点不应被触达").isEmpty();
    }
}

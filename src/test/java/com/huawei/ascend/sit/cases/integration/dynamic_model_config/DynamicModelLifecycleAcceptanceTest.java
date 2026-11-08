/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.dynamic_model_config;

import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** FEAT-050-CORE §5.1.3 / FRS-050 AC-01：模型删除后新请求不再路由到该模型。 */
@Tag("integration")
@Tag("dynamic-model-config")
@Tag("feat-050")
@Feature("FEAT-050: 动态模型配置（agent-core）")
class DynamicModelLifecycleAcceptanceTest extends DynamicModelStackTestBase {

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return carrier(config, endpointA, endpointB, Map.of("dmcsit.default-model-id", "A", "dmcsit.instance-enabled", "true", "dmcsit.instance-model-id", "A"));
    }

    @BeforeEach
    void resetPool() {
        control().reset();
    }

    @Test
    @Story("dmc.lifecycle.remove: 中途删除模型 → 新请求不再路由到它")
    @DisplayName("删除模型 B 后，指定 B 的请求回退默认模型 A，B 端点不再被触达")
    void removedModelIsNotRoutedAnymore() {
        assertThat(control().remove("B").ok()).as("删除 B 应成功").isTrue();

        int baselineA = endpointA.requestCount();
        int baselineB = endpointB.requestCount();

        InteractionFlow.RoundResult byDefault = send("dmc-lifecycle-remove-default",
                "Reply with one short sentence.", Map.of());
        InteractionFlow.RoundResult byRemovedId = send("dmc-lifecycle-remove-explicit",
                "Reply with one short sentence.", Map.of("model_name", "B"));

        assertRealModelAnswered(byDefault, "DynamicModelLifecycleAcceptanceTest");
        assertRealModelAnswered(byRemovedId, "DynamicModelLifecycleAcceptanceTest");
        assertThat(endpointB.requestsSince(baselineB)).as("B 端点不应再被触达").isEmpty();
        assertThat(endpointA.requestsSince(baselineA)).as("两次请求都应由 A 端点完成").hasSize(2);
    }

    private DynamicModelControlClient controlClient() {
        return control();
    }
}

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
 * FEAT-050-CORE request-level model routing (candidate snapshot).
 *
 * <p>Scope note: the agent-core feature is consumed through a fixture that plays the "customer code"
 * role (FEAT-050-CORE §3.2). The product-side {@code model_name} parsing belongs to FEAT-051 and is
 * not judged here.</p>
 */
@Tag("integration")
@Tag("dynamic-model-config")
@Tag("feat-050")
@Feature("FEAT-050: 动态模型配置（agent-core）")
class DynamicModelRoutingAcceptanceTest extends DynamicModelStackTestBase {

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return carrier(config, endpointA, endpointB, Map.of("dmcsit.default-model-id", "A", "dmcsit.instance-enabled", "true", "dmcsit.instance-model-id", "A"));
    }

    @Test
    @Story("dmc.route.explicit: 请求指定模型 → 由指定模型完成")
    @DisplayName("指定 model_name=B 的请求由 B 的模型端点完成，A 端点不被触达")
    void specifiedModelIsServedByThatModel() {
        int baselineA = endpointA.requestCount();
        int baselineB = endpointB.requestCount();

        InteractionFlow.RoundResult round = send("dmc-route-explicit", "Reply with one short sentence.",
                Map.of("model_name", "B"));

        // 夹具自检快照：同时提供"是否看到 metadata""解析出的模型名""rail 实际写入的标识"，
        // 用于区分"夹具输入通道未送达"与"产品未按标识路由"。
        String diagnostic = diagnostic();

        assertRealModelAnswered(round, "DynamicModelRoutingAcceptanceTest");
        assertThat(endpointB.requestsSince(baselineB))
                .as("B 端点应收到本次调用；夹具自检=%s", diagnostic)
                .hasSize(1);
        assertThat(endpointA.requestsSince(baselineA))
                .as("A 端点不应收到本次调用；夹具自检=%s", diagnostic)
                .isEmpty();
    }

    @Test
    @Story("dmc.route.default: 未指定模型 → 走默认模型（向后兼容）")
    @DisplayName("不带 model_name 的请求由实例默认模型（A）完成")
    void noModelParamFallsBackToDefault() {
        int baselineA = endpointA.requestCount();
        int baselineB = endpointB.requestCount();

        InteractionFlow.RoundResult round = send("dmc-route-default", "Reply with one short sentence.", Map.of());

        assertRealModelAnswered(round, "DynamicModelRoutingAcceptanceTest");
        assertThat(endpointA.requestsSince(baselineA)).as("A 端点应收到本次调用").hasSize(1);
        assertThat(endpointB.requestsSince(baselineB)).as("B 端点不应收到本次调用").isEmpty();
    }

    @Test
    @Story("dmc.route.fallback: 指定了不存在的模型 → 回退默认模型")
    @DisplayName("指定未登记 model_name 的请求回退到默认模型 A，且请求成功")
    void unknownModelFallsBackToDefault() {
        int baselineA = endpointA.requestCount();
        int baselineB = endpointB.requestCount();

        InteractionFlow.RoundResult round = send("dmc-route-fallback", "Reply with one short sentence.",
                Map.of("model_name", "ghost-" + System.nanoTime()));

        assertRealModelAnswered(round, "DynamicModelRoutingAcceptanceTest");
        assertThat(endpointA.requestsSince(baselineA)).as("回退后应由 A 端点完成本次调用").hasSize(1);
        assertThat(endpointB.requestsSince(baselineB)).as("B 端点不应收到本次调用").isEmpty();
    }

    private String diagnostic() {
        try {
            return control().lastRequest().toString();
        } catch (RuntimeException exception) {
            return "control probe unavailable: " + exception.getMessage();
        }
    }

    @Test
    @Story("dmc.route.concurrent-isolation: 并发请求各自使用指定模型、互不干扰")
    @DisplayName("并发两请求分别指定 A 与 B 时，两个端点各收到一次且不串道")
    void concurrentRequestsUseTheirOwnModels() throws Exception {
        int baselineA = endpointA.requestCount();
        int baselineB = endpointB.requestCount();

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<InteractionFlow.RoundResult> toA = pool.submit(() ->
                    send("dmc-route-concurrent-a", "Reply with one short sentence.", Map.of("model_name", "A")));
            java.util.concurrent.Future<InteractionFlow.RoundResult> toB = pool.submit(() ->
                    send("dmc-route-concurrent-b", "Reply with one short sentence.", Map.of("model_name", "B")));

            assertRealModelAnswered(toA.get(120, java.util.concurrent.TimeUnit.SECONDS), "dmc.route.concurrent-isolation");
            assertRealModelAnswered(toB.get(120, java.util.concurrent.TimeUnit.SECONDS), "dmc.route.concurrent-isolation");
        } finally {
            pool.shutdownNow();
        }

        assertThat(endpointA.requestsSince(baselineA)).as("A 端点应只收到属于它的那次调用").hasSize(1);
        assertThat(endpointB.requestsSince(baselineB)).as("B 端点应只收到属于它的那次调用").hasSize(1);
    }
}

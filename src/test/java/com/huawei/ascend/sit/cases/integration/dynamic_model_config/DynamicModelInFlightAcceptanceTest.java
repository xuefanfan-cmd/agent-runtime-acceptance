/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.dynamic_model_config;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-050-CORE §5.1.3：模型增删后，已在执行的请求按原模型完成、不被改道。
 *
 * <p><b>已登记的降级</b>：本用例只验证"在途请求的模型调用已经发往原模型端点，删除该模型后不会
 * 被改道到其他端点"。"完整返回"这一半依赖可控的长响应前置（记录端点当前只提供"不响应"型触发件），
 * 因此不在此断言，按 {@code partial} 记录在覆盖台账，恢复条件是具备可控长响应端点或产品侧可注入
 * 长生成窗口。</p>
 */
@Tag("integration")
@Tag("dynamic-model-config")
@Tag("feat-050")
@Feature("FEAT-050: 动态模型配置（agent-core）")
class DynamicModelInFlightAcceptanceTest extends DynamicModelStackTestBase {

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return carrier(config, endpointA, endpointB, Map.of("dmcsit.default-model-id", "A", "dmcsit.instance-enabled", "true", "dmcsit.instance-model-id", "A"));
    }

    @Test
    @Story("dmc.lifecycle.inflight: 在途请求不被改道且完整返回")
    @DisplayName("在途请求已发往 B 时删除模型 B，该请求既不改道到 A，也拿到原本模型的完整回答")
    void inFlightRequestCompletesWithOriginalModel() throws Exception {
        // 真实模型照常被调用，只是在回写响应前停留 8 秒：足够在"在途"期间删除模型 B，
        // 又能在删除之后拿到原本模型的完整回答（不伪造模型产出）。
        endpointB.holdResponseMillis(8_000L);
        int baselineA = endpointA.requestCount();
        int baselineB = endpointB.requestCount();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var inFlight = pool.submit(() ->
                    send("dmc-lifecycle-inflight", "Reply with one short sentence.", Map.of("model_name", "B")));

            Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
            while (endpointB.requestCount() == baselineB && Instant.now().isBefore(deadline)) {
                Thread.sleep(200);
            }
            assertThat(endpointB.requestCount())
                    .as("在途请求的模型调用应已发往 B 端点")
                    .isGreaterThan(baselineB);

            assertThat(control().remove("B").ok()).as("删除在途模型 B 应成功").isTrue();
            Thread.sleep(2_000);

            assertThat(endpointA.requestsSince(baselineA))
                    .as("在途请求不应被改道到 A 端点")
                    .isEmpty();

            // 完整返回：删除发生后放开延迟，请求必须仍由原本的模型 B 完成并返回真实回答。
            var round = inFlight.get(90, TimeUnit.SECONDS);
            assertThat(round.taskState())
                    .as("在途请求应正常完成（而非超时/失败）")
                    .isEqualTo(org.a2aproject.sdk.spec.TaskState.TASK_STATE_COMPLETED);
            assertRealModelAnswered(round, "dmc.lifecycle.inflight");
            assertThat(endpointB.requestsSince(baselineB))
                    .as("整个在途请求只应调用 B 端点一次（不因删除而重发或改道）")
                    .hasSize(1);
        } finally {
            pool.shutdownNow();
            endpointB.holdResponseMillis(0L);
        }
    }
}

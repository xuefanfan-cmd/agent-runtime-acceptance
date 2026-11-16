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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-050-CORE §3.3 / §5.1.5: 三级都取不到模型时，请求必须被拒绝且失败可归因。
 *
 * <p>Carrier wiring: process-wide pool disabled and instance model disabled, so no candidate model
 * exists. The request must fail (not silently succeed) and no model endpoint may be touched.</p>
 */
@Tag("integration")
@Tag("dynamic-model-config")
@Tag("feat-050")
@Feature("FEAT-050: 动态模型配置（agent-core）")
class DynamicModelUnavailableAcceptanceTest extends DynamicModelStackTestBase {

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return carrier(config, endpointA, endpointB, Map.of("dmcsit.pool-enabled", "false", "dmcsit.instance-enabled", "false", "dmcsit.default-model-id", ""));
    }

    @Test
    @Story("dmc.route.unavailable: 无可用模型 → 请求被拒绝")
    @DisplayName("没有任何可用模型时请求失败，且不静默成功、不触达模型端点")
    void noModelAvailableIsRejected() throws Exception {
        int baselineA = endpointA.requestCount();
        int baselineB = endpointB.requestCount();

        var round = sendExpecting("dmc-route-unavailable", "Reply with one short sentence.", Map.of(),
                org.a2aproject.sdk.spec.TaskState.TASK_STATE_FAILED);

        // 内层原因取证（证据门禁第 10 条）：失败原文 + 宿主/rail 异常探针必须落盘，
        // 否则"被拒绝"只能判 INCONCLUSIVE，无法判定拒绝原因是否符合设计要求。
        recordProbe(round);

        assertThat(round.taskState())
                .as("无可用模型时任务必须失败，而不是静默完成；夹具异常探针=%s", diagnostic())
                .isEqualTo(org.a2aproject.sdk.spec.TaskState.TASK_STATE_FAILED);
        assertThat(endpointA.requestsSince(baselineA)).as("不应触达任何模型端点（A）").isEmpty();
        assertThat(endpointB.requestsSince(baselineB)).as("不应触达任何模型端点（B）").isEmpty();
    }

    private void recordProbe(com.huawei.ascend.sit.client.InteractionFlow.RoundResult round)
            throws java.io.IOException {
        java.nio.file.Path directory = java.nio.file.Path.of("target", "feature-acceptance-testing");
        java.nio.file.Files.createDirectories(directory);
        StringBuilder json = new StringBuilder("{\n");
        json.append("  \"case\": \"dmc.route.unavailable\",\n");
        json.append("  \"taskState\": \"").append(round.taskState()).append("\",\n");
        json.append("  \"answerText\": ").append(quote(round.answerText())).append(",\n");
        json.append("  \"generatedText\": ").append(quote(round.generatedText())).append(",\n");
        json.append("  \"controlLastRequest\": ").append(quote(String.valueOf(diagnostic()))).append(",\n");
        json.append("  \"note\": \"失败轮次内层原因取证；判定见 dmc-real-model-full-run-20260922.md\"\n}\n");
        java.nio.file.Files.writeString(directory.resolve("dmc-unavailable-probe.json"), json.toString(),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String quote(String value) {
        String text = value == null ? "" : value;
        if (text.length() > 4000) {
            text = text.substring(0, 4000) + "...<truncated>";
        }
        return "\"" + text.replace("\\", "\\\\").replace("\"", "'").replace("\n", " ").replace("\r", " ") + "\"";
    }

    private String diagnostic() {
        try {
            return control().lastRequest().toString();
        } catch (RuntimeException exception) {
            return "control probe unavailable: " + exception.getMessage();
        }
    }
}

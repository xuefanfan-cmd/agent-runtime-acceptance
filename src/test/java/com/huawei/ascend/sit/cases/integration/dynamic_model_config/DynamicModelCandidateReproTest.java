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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 候选反馈 CAND-01/02/03 的独立复现取证（FEAT-050-CORE，候选快照 {@code agent-core-java} 930 / PR #74）。
 *
 * <p><b>使用方式</b>：本类被标记 {@code candidate-repro}，**不在常规套件里跑**；需要取证时显式指定：
 * {@code -Dtest=DynamicModelCandidateReproTest}。三条用例按设计要求断言，因此在当前候选制品上
 * **预期为 FAIL**——FAIL 本身就是"与设计不符"的复现证据，与原始 XML 一起进入候选反馈台账。</p>
 *
 * <p>三条候选事实（详见 {@code 01-Design_File/20260921/FEAT-050/FEAT-050-设计与实现偏差清单（L2缺失期）.md} 第 3 节）：
 * CAND-01 回退默认时缺少设计要求的 WARN 日志；CAND-02 {@code setDefaultModelId(null)} 未拒绝；
 * CAND-03 删除模型返回的不是"被删除的模型"。</p>
 */
@Tag("integration")
@Tag("dynamic-model-config")
@Tag("feat-050")
@Tag("candidate-repro")
@Feature("FEAT-050: 动态模型配置（agent-core）")
class DynamicModelCandidateReproTest extends DynamicModelStackTestBase {

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return carrier(config, endpointA, endpointB, Map.of("dmcsit.default-model-id", "A", "dmcsit.instance-enabled", "true", "dmcsit.instance-model-id", "A"));
    }

    @BeforeEach
    void resetPool() {
        control().reset();
    }

    @Test
    @Story("CAND-01: 回退默认模型时必须记录 WARN 日志")
    @DisplayName("指定未登记模型回退默认模型时，全链路日志应出现指向该模型的 WARN")
    void cand01FallbackLogsWarn() throws IOException {
        String ghost = "ghost-" + System.nanoTime();
        int baselineA = endpointA.requestCount();
        List<String> logLinesBefore = readFixtureLog();

        InteractionFlow.RoundResult round = send("cand01-fallback-warn", "Reply with one short sentence.",
                Map.of("model_name", ghost));

        List<String> newLines = readFixtureLog();
        newLines = newLines.subList(Math.min(logLinesBefore.size(), newLines.size()), newLines.size());

        List<String> warnAboutGhost = new ArrayList<>();
        List<String> warnAny = new ArrayList<>();
        for (String line : newLines) {
            if (line.contains("WARN")) {
                warnAny.add(line);
                if (line.contains(ghost)) {
                    warnAboutGhost.add(line);
                }
            }
        }

        writeEvidence("cand01", Map.of(
                "requestedModelName", ghost,
                "requestCompleted", String.valueOf(round.generatedText() != null),
                "fallbackEndpointCalled", String.valueOf(endpointA.requestCount() - baselineA == 1),
                "warnLineCountTotal", String.valueOf(warnAny.size()),
                "warnLineCountMentioningRequestedModel", String.valueOf(warnAboutGhost.size()),
                "warnSample", warnAny.isEmpty() ? "(none)" : warnAny.get(0),
                "evidenceScope", "载体 run.log + stdout.log 中本次请求时间窗内的日志行"));

        assertThat(warnAboutGhost)
                .as("设计要求：指定模型不存在时回退默认并记录 WARN（FEAT-050 §2/§5.1.5/SC-CORE-04）。"
                        + "本次请求 model_name=%s，回退实现所在分段落日志行数=%s；证据文件见表内路径",
                        ghost, warnAboutGhost.size())
                .isNotEmpty();
    }

    @Test
    @Story("CAND-02: 设置默认模型传空值必须被拒绝")
    @DisplayName("setDefaultModelId(null) 应抛异常（设计 §3.3），当前行为与默认模型状态应被记录")
    void cand02SetDefaultNullIsRejected() throws IOException {
        String defaultBefore = control().defaultModel().get("defaultModelId").asText();
        DynamicModelControlClient.Response response = control().setDefault("null");
        String defaultAfter = control().defaultModel().get("defaultModelId").asText();

        writeEvidence("cand02", Map.of(
                "httpStatus", String.valueOf(response.status()),
                "responseBody", String.valueOf(response.rawBody()),
                "defaultBefore", defaultBefore,
                "defaultAfter", defaultAfter,
                "designExpectation", "FEAT-050 §3.3：modelId 为空（设置默认模型）应抛 IllegalArgumentException"));

        assertThat(response.status())
                .as("设计要求 modelId 为空时拒绝；实际 HTTP 状态=%s，响应体=%s，默认模型 %s → %s",
                        response.status(), response.rawBody(), defaultBefore, defaultAfter)
                .isEqualTo(400);
    }

    @Test
    @Story("CAND-03: 删除模型应返回被删除的模型")
    @DisplayName("删除模型的返回值应为被删除的模型实例，而非供应器或通用对象")
    void cand03RemovedModelIsReturned() throws IOException {
        DynamicModelControlClient.Response response = control().remove("B");
        String removedType = String.valueOf(response.text("removedType"));
        String removedValue = String.valueOf(response.text("removedValue"));

        writeEvidence("cand03", Map.of(
                "httpStatus", String.valueOf(response.status()),
                "removedType", removedType,
                "removedValue", removedValue,
                "designExpectation", "FEAT-050 §3.1 removeModel：返回被删除的模型，供调用方按需清理资源"));

        assertThat(response.status()).as("删除 B 应成功，实际=%s", response.rawBody()).isEqualTo(200);
        assertThat(removedType)
                .as("设计要求返回被删除的模型；实际返回类型=%s，值=%s", removedType, removedValue)
                .contains("Model")
                .doesNotContain("Supplier");
    }

    private List<String> readFixtureLog() throws IOException {
        List<String> lines = new ArrayList<>();
        for (String relative : List.of("run/run.log", "stdout.log")) {
            Path file = Path.of("target", "sit-logs", SUT).resolve(relative);
            if (!Files.exists(file)) {
                continue;
            }
            try (Stream<String> stream = Files.lines(file, StandardCharsets.UTF_8)) {
                stream.forEach(lines::add);
            }
        }
        return lines;
    }

    private void writeEvidence(String candidateId, Map<String, Object> payload) throws IOException {
        Path directory = Path.of("target", "feature-acceptance-testing");
        Files.createDirectories(directory);
        StringBuilder json = new StringBuilder("{\n  \"candidate\": \"").append(candidateId).append("\",\n");
        payload.forEach((key, value) -> json.append("  \"").append(key).append("\": \"")
                .append(String.valueOf(value).replace("\"", "'")).append("\",\n"));
        json.append("  \"note\": \"候选快照复现取证；判定与提单见候选反馈台账\"\n}\n");
        Files.writeString(directory.resolve("dmc-" + candidateId + "-evidence.json"), json.toString(),
                StandardCharsets.UTF_8);
    }
}

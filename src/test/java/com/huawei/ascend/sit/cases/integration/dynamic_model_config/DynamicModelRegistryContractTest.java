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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-050-CORE §3.1 / §3.3：模型清单管理语义（新增/更新/删除/查询/存在性/默认模型）。
 *
 * <p>驱动方式是载体提供的测试专用管理入口（客户代码调用 core 的模型管理 Java API）。其中
 * {@code setDefaultModelId(null)} 与删除返回值形态属"与设计不符"候选事实，本轮只做取证记录，
 * 不在用例里断言产品缺陷——判定按 failure-and-defect 流程处理。</p>
 */
@Tag("integration")
@Tag("dynamic-model-config")
@Tag("feat-050")
@Feature("FEAT-050: 动态模型配置（agent-core）")
class DynamicModelRegistryContractTest extends DynamicModelStackTestBase {

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // 清单管理语义只针对进程级模型池：实例级默认（❷）一旦配置，会被框架按
        // buildModelId(modelName, provider) 自动并入池（id 形如 "<model>@<provider>"），
        // 使"删除最后一个模型"永远不可能发生。故本类关闭实例级默认，让池恰好等于 A/B/C。
        return carrier(config, endpointA, endpointB,
                Map.of("dmcsit.default-model-id", "A", "dmcsit.instance-enabled", "false"));
    }

    @BeforeEach
    void resetPool() {
        control().reset();
    }

    @Test
    @Story("dmc.registry.add: 新增模型语义（含重复与空标识拒绝）")
    @DisplayName("新增模型可被查询到；重复标识与空标识被拒绝")
    void addModelSemantics() {
        DynamicModelControlClient client = control();

        DynamicModelControlClient.Response added = client.add("D", "model-d", endpointB.baseUrl());
        assertThat(added.accepted()).as("新增 D 应成功，实际=%s", added.rawBody()).isTrue();
        assertThat(client.list().toString()).as("清单应包含 D").contains("D");

        // 只查清单是"弱 Oracle"：再发一次指定 D 的请求，验证"新增后新请求即时可用"（FR-01）。
        // D 指向 B 端点（默认 A 指向 A 端点），因此"实现忽略请求级模型名"时本次会落到 A 端点，断言会红。
        int baselineA = endpointA.requestCount();
        int baselineB = endpointB.requestCount();
        InteractionFlow.RoundResult round = send("dmc-registry-add-routable", "Reply with one short sentence.",
                Map.of("model_name", "D"));
        assertRealModelAnswered(round, "DynamicModelRegistryContractTest");
        assertThat(endpointB.requestsSince(baselineB)).as("D 指向的端点应收到调用").hasSize(1);
        assertThat(endpointA.requestsSince(baselineA)).as("默认模型端点不应收到该请求").isEmpty();

        DynamicModelControlClient.Response duplicate = client.add("A", "model-a", endpointA.baseUrl());
        assertThat(duplicate.accepted())
                .as("重复新增 A 应被拒绝（门面 Result 语义），实际=%s", duplicate.rawBody())
                .isFalse();

        DynamicModelControlClient.Response blank = client.add("", "model-blank", endpointA.baseUrl());
        assertThat(blank.accepted()).as("空标识新增应被拒绝，实际=%s", blank.rawBody()).isFalse();
    }

    @Test
    @Story("dmc.registry.update: 更新模型语义（覆盖与自动注册）")
    @DisplayName("更新已存在模型指向新端点后新请求走新端点；更新未登记标识会被自动注册")
    void updateModelSemantics() {
        DynamicModelControlClient client = control();

        DynamicModelControlClient.Response updated = client.update("A", "model-a", endpointB.baseUrl());
        assertThat(updated.accepted()).as("更新 A 应成功，实际=%s", updated.rawBody()).isTrue();

        int baselineA = endpointA.requestCount();
        int baselineB = endpointB.requestCount();
        InteractionFlow.RoundResult round = send("dmc-registry-update", "Reply with one short sentence.",
                Map.of("model_name", "A"));
        assertRealModelAnswered(round, "DynamicModelRegistryContractTest");
        assertThat(endpointB.requestsSince(baselineB)).as("新端点应收到调用").hasSize(1);
        assertThat(endpointA.requestsSince(baselineA)).as("旧端点不应再收到调用").isEmpty();

        DynamicModelControlClient.Response autoRegister = client.update("X", "model-x", endpointA.baseUrl());
        assertThat(autoRegister.accepted())
                .as("按设计，更新未登记标识应自动注册（实际=%s）", autoRegister.rawBody())
                .isTrue();
        assertThat(client.list().toString()).as("清单应包含自动注册的 X").contains("X");
    }

    @Test
    @Story("dmc.registry.remove: 删除模型与默认模型自动切换、最后一个模型拒绝")
    @DisplayName("删除默认模型后默认自动切换；删除最后一个模型被拒绝；返回内容形态被记录")
    void removeModelAndDefaultSwitch() throws IOException {
        DynamicModelControlClient client = control();

        DynamicModelControlClient.Response removedDefault = client.remove("A");
        assertThat(removedDefault.accepted())
                .as("删除默认模型应成功，实际=%s", removedDefault.rawBody()).isTrue();
        String defaultAfter = client.defaultModel().get("defaultModelId").asText();
        assertThat(defaultAfter).as("删除默认模型后应有新的默认模型").isNotEqualTo("A").isNotEqualTo("null");

        DynamicModelControlClient.Response removedAnother = client.remove("B");
        assertThat(removedAnother.accepted()).as("删除 B 应成功，实际=%s", removedAnother.rawBody()).isTrue();
        DynamicModelControlClient.Response lastOne = client.remove("C");
        assertThat(lastOne.accepted())
                .as("删除最后一个模型应被拒绝（设计 §3.3），实际=%s", lastOne.rawBody())
                .isFalse();

        recordProbe("remove-return-shape", Map.of(
                "removedDefaultType", String.valueOf(removedDefault.text("removedType")),
                "removedDefaultValue", String.valueOf(removedDefault.text("removedValue"))));
    }

    @Test
    @Story("dmc.registry.query: 清单查询、存在性检查、默认模型设置与查询")
    @DisplayName("清单/存在性/默认模型语义正确；空值设置行为按候选事实记录")
    void queryAndDefaultSemantics() throws IOException {
        DynamicModelControlClient client = control();

        assertThat(client.list().toString()).as("清单应含 A/B/C").contains("A", "B", "C");
        assertThat(client.has("A").get("present").asBoolean()).as("has(A) 应为 true").isTrue();
        assertThat(client.has("ZZZ").get("present").asBoolean()).as("has(ZZZ) 应为 false").isFalse();

        assertThat(client.setDefault("\"B\"").ok()).as("设置默认模型为 B 应成功").isTrue();
        assertThat(client.defaultModel().get("defaultModelId").asText()).isEqualTo("B");

        DynamicModelControlClient.Response unknown = client.setDefault("\"ZZZ\"");
        assertThat(unknown.ok()).as("用未登记标识设置默认应被拒绝").isFalse();

        DynamicModelControlClient.Response nullValue = client.setDefault("null");
        recordProbe("set-default-null", Map.of(
                "httpStatus", String.valueOf(nullValue.status()),
                "body", String.valueOf(nullValue.rawBody()),
                "defaultAfter", client.defaultModel().get("defaultModelId").asText()));
    }

    private void recordProbe(String name, Map<String, Object> payload) throws IOException {
        Path directory = Path.of("target", "feature-acceptance-testing");
        Files.createDirectories(directory);
        Path file = directory.resolve("dmc-contract-probe-" + name + ".json");
        StringBuilder json = new StringBuilder("{\n");
        Map<String, Object> ordered = new LinkedHashMap<>(payload);
        ordered.forEach((key, value) -> json.append("  \"").append(key).append("\": \"")
                .append(String.valueOf(value).replace("\"", "'")).append("\",\n"));
        json.append("  \"note\": \"候选事实取证记录；判定见设计与实现偏差清单\"\n}\n");
        Files.writeString(file, json.toString(), StandardCharsets.UTF_8);
    }
}

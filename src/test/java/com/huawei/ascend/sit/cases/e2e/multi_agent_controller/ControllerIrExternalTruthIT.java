/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.e2e.multi_agent_controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.fixtures.studio_dsl.RecordingOpenAiEndpointFixture;
import com.huawei.ascend.sit.fixtures.studio_dsl.RecordingPluginMcpEndpointFixture;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioExportCorpusFixture;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.transport.InboundEvent;
import com.huawei.ascend.sit.transport.MessageProtocol;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.qameta.allure.Stories;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-031 §7 E2E proofs that need a controlled model, a controlled plugin endpoint, or a derived bundle.
 */
@Tag("e2e")
@Tag("multi-agent-controller")
@Tag("feat-031")
@Feature("FEAT-031: Studio DSL Java 承载")
class ControllerIrExternalTruthIT {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SUT = "controller-ir-demo";
    private static final String DEFAULT_WORKFLOW_ID = "8513a1b2-b053-488d-87f6-1fab954a9001";
    private static final long FLOW_TIMEOUT_MS = 120_000L;
    private static final Path E04_REAL_BUNDLE = Path.of(
            "src/test/resources/testdata/studio_dsl/controller_ir/e04-real-20261009");
    private static final String E04_REAL_ROOT_AGENT_ID = "82d1467d-cd10-4dac-9cc2-7db23f9fac8f";
    private static final String E04_START_CANARY = "E04-START-CANARY-20261009202341";
    private static final String E04_END_CANARY = "E04-END-CANARY-20261009202341";
    private static final Path E15_REAL_BUNDLE = Path.of(
            "src/test/resources/testdata/studio_dsl/controller_ir/e15-real-default-20261012");
    private static final Path E16_REAL_BUNDLE = Path.of(
            "src/test/resources/testdata/studio_dsl/controller_ir/e16-real-intent-multi-20261012");
    private static final Path E17_REAL_BUNDLE = Path.of(
            "src/test/resources/testdata/studio_dsl/controller_ir/e17-real-loop-20261012");
    private static final Path E18_REAL_BUNDLE = Path.of(
            "src/test/resources/testdata/studio_dsl/controller_ir/e18-real-plugin-20261012");

    private static TestConfig config;
    private static Path tempRoot;

    @BeforeAll
    static void prepare() throws IOException {
        config = TestConfig.load();
        tempRoot = Path.of("target", "sit-logs", "controller-ir-external-truth", UUID.randomUUID().toString());
        Files.createDirectories(tempRoot);
    }

    @AfterAll
    static void cleanup() {
        config = null;
    }

    @Test
    @Story("MA-031-05-20-E06: 真实 LLM 输出被下游消费")
    @DisplayName("真实模型返回唯一 canary，目标工作流 End 产物消费该 canary")
    void realLlmOutputReachesDownstreamEnd() throws Exception {
        String canary = "E06-REAL-LLM-CANARY-" + UUID.randomUUID().toString().substring(0, 8);
        Path bundle = writeLlmBundle("e06", "Return exactly " + canary + " and nothing else.", canary);
        try (StackHandle handle = startRealModelStack(bundle, "e06-root")) {
            InteractionFlow.FlowResult result = InteractionFlow.of(handle.stack().client(SUT))
                    .withTimeoutMs(FLOW_TIMEOUT_MS)
                    .withContextId(context("E06"))
                    .send("please return the canary")
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .execute();

            assertEndCanary(result.round(0).events(), "e06-workflow", canary);
        }
    }

    @Test
    @Story("MA-031-05-20-E08: 流式 End 结果来源")
    @DisplayName("流式最终业务 canary 来自 jiuwen.end 目标产物")
    void streamingEndArtifactCarriesModelCanary() throws Exception {
        String canary = "E08-STREAM-END-CANARY-" + UUID.randomUUID().toString().substring(0, 8);
        Path bundle = writeLlmBundle("e08", "Return exactly " + canary + ".", canary);
        try (RecordingOpenAiEndpointFixture model = new RecordingOpenAiEndpointFixture()) {
            model.responseContent(canary);
            try (StackHandle handle = startControlledStack(model, bundle, "e08-root", null)) {
                InteractionFlow.FlowResult result = InteractionFlow.of(handle.stack().client(SUT))
                        .protocol(MessageProtocol.A2A_STREAM)
                        .withTimeoutMs(FLOW_TIMEOUT_MS)
                        .withContextId(context("E08"))
                        .send("stream-end")
                        .awaitState(TaskState.TASK_STATE_COMPLETED)
                        .execute();

                assertEndCanary(result.round(0).events(), "e08-workflow", canary);
                assertThat(endEventCount(result.round(0).events())).isGreaterThanOrEqualTo(1);
            }
        }
    }

    @Test
    @Story("MA-031-05-20-E09: 模型额外请求头")
    @DisplayName("受控模型端点收到声明请求头，模型结果继续进入 End")
    void modelExtraHeadersReachControlledEndpoint() throws Exception {
        String canary = "E09-MODEL-RESULT-CANARY-" + UUID.randomUUID().toString().substring(0, 8);
        String headerValue = "e09-header-" + UUID.randomUUID().toString().substring(0, 8);
        Path bundle = writeLlmBundle("e09", "Return exactly " + canary + ".", canary);
        try (RecordingOpenAiEndpointFixture model = new RecordingOpenAiEndpointFixture()) {
            model.responseContent(canary);
            String headers = MAPPER.writeValueAsString(Map.of("X-FEAT031-E09", headerValue));
            try (StackHandle handle = startControlledStack(model, bundle, "e09-root", headers)) {
                int baseline = model.requestCount();
                InteractionFlow.FlowResult result = InteractionFlow.of(handle.stack().client(SUT))
                        .withTimeoutMs(FLOW_TIMEOUT_MS)
                        .withContextId(context("E09"))
                        .send("header-check")
                        .awaitState(TaskState.TASK_STATE_COMPLETED)
                        .execute();

                assertEndCanary(result.round(0).events(), "e09-workflow", canary);
                List<RecordingOpenAiEndpointFixture.RequestRecord> requests = model.requestsSince(baseline);
                assertThat(requests).isNotEmpty();
                assertThat(requests)
                        .allSatisfy(request -> assertThat(request.header("X-FEAT031-E09"))
                                .isEqualTo(headerValue));
            }
        }
    }

    @Test
    @Stories({
        @Story("MA-031-05-20-E07: 插件/MCP 外部调用回灌"),
        @Story("MA-031-05-20-B13: 插件配置到运行期下游 canary")
    })
    @DisplayName("真实插件工作流命中受控端点恰一次，插件 canary 到达 End")
    void pluginResponseReachesDownstreamEnd() throws Exception {
        Path bundle = writePluginBundle("e07");
        try (RecordingPluginMcpEndpointFixture endpoint = new RecordingPluginMcpEndpointFixture();
                RecordingOpenAiEndpointFixture model = new RecordingOpenAiEndpointFixture()) {
            model.responseContent(MAPPER.writeValueAsString(Map.of(
                    "class", "其他", "reason", "default route", "canary", "E07-MODEL-CANARY")));
            try (StackHandle handle = startControlledStack(model, bundle, "e07-root", null)) {
                int baseline = endpoint.requestCount();
                InteractionFlow.FlowResult result = InteractionFlow.of(handle.stack().client(SUT))
                        .withTimeoutMs(FLOW_TIMEOUT_MS)
                        .withContextId(context("E07"))
                        .send("plugin-check")
                        .awaitState(TaskState.TASK_STATE_COMPLETED)
                        .execute();

                List<RecordingPluginMcpEndpointFixture.RequestRecord> calls =
                        endpoint.requestsSince(baseline);
                Allure.addAttachment("E07 controlled plugin requests", calls.toString());
                assertThat(calls).hasSize(1);
                assertThat(calls.get(0).path()).startsWith("/plugin/weather");
                assertEndCanary(result.round(0).events(), "e07-plugin-workflow", "plugin-canary");
            }
        }
    }

    @Test
    @Stories({
        @Story("MA-031-05-20-E13: MCP 外部调用回灌"),
        @Story("MA-031-05-20-B14: MCP 配置到运行期下游 canary")
    })
    @DisplayName("真实 MCP 工作流调用受控 tools/call 恰一次，下游分支收到 MCP 结果")
    void mcpResponseReachesDownstreamBranch() throws Exception {
        Path bundle = writeMcpBundle("e13");
        try (RecordingPluginMcpEndpointFixture endpoint = new RecordingPluginMcpEndpointFixture();
                RecordingOpenAiEndpointFixture model = new RecordingOpenAiEndpointFixture()) {
            model.responseContent(MAPPER.writeValueAsString(Map.of(
                    "class", "其他", "reason", "default route")));
            try (StackHandle handle = startControlledStack(model, bundle, "e13-root", null)) {
                int baseline = endpoint.requestCount();
                InteractionFlow.FlowResult result = InteractionFlow.of(handle.stack().client(SUT))
                        .withTimeoutMs(FLOW_TIMEOUT_MS)
                        .withContextId(context("E13"))
                        .send("mcp-check")
                        .awaitState(TaskState.TASK_STATE_COMPLETED)
                        .execute();

                List<RecordingPluginMcpEndpointFixture.RequestRecord> calls =
                        endpoint.requestsSince(baseline);
                long toolCalls = calls.stream()
                        .filter(call -> "tools/call".equals(call.body().path("method").asText()))
                        .count();
                Allure.addAttachment("E13 controlled MCP requests", calls.toString());
                assertThat(toolCalls).isEqualTo(1);
                assertThat(calls).allSatisfy(call -> assertThat(call.path()).startsWith("/mcp"));
                assertEndCanary(result.round(0).events(), "e13-mcp-workflow", "PATH=IF");
            }
        }
    }

    @Test
    @Story("MA-031-05-20-E04: Start/End 工作流 canary")
    @DisplayName("Start 与 End 工作流均实际执行并产生各自目标产物")
    void startAndEndWorkflowsBothExecute() throws Exception {
        Path bundle = E04_REAL_BUNDLE;
        assertRealStartEndBundleShape(bundle);
        try (RecordingOpenAiEndpointFixture model = new RecordingOpenAiEndpointFixture()) {
            try (StackHandle handle = startControlledStack(model, bundle, E04_REAL_ROOT_AGENT_ID, null)) {
                InteractionFlow.FlowResult result = InteractionFlow.of(handle.stack().client(SUT))
                        .withTimeoutMs(FLOW_TIMEOUT_MS)
                        .withContextId(context("E04"))
                        .send("start-end-check")
                        .awaitState(TaskState.TASK_STATE_COMPLETED)
                        .execute();

                assertEndCanary(result.round(0).events(), "e04_start", E04_START_CANARY);
                assertEndCanary(result.round(0).events(), "e04_end", E04_END_CANARY);
            }
        }
    }

    private static void assertRealStartEndBundleShape(Path bundle) throws IOException {
        JsonNode root = MAPPER.readTree(bundle.resolve("ir/" + E04_REAL_ROOT_AGENT_ID + ".json").toFile());
        JsonNode manifest = MAPPER.readTree(bundle.resolve("manifest.json").toFile());
        JsonNode provenance = MAPPER.readTree(bundle.resolve("provenance.json").toFile());
        JsonNode paths = manifest.path("irPathToLocal");
        List<String> roles = root.path("configs").path("workflows").findValuesAsText("workflow_type");
        assertThat(roles).containsExactly("Start", "Normal", "End");
        assertThat(provenance.path("source").path("exportSha256").asText())
                .isEqualTo("E4CC6D5D5F65514B99FD9FB73FF35324D0264981377CB76E401A65B42B0D3532");

        provenance.path("files").fields().forEachRemaining(entry ->
                assertThat(sha256(bundle.resolve(entry.getKey())))
                        .as("byte-for-byte Studio export hash for %s", entry.getKey())
                        .isEqualTo(entry.getValue().asText()));

        for (JsonNode workflow : root.path("configs").path("workflows")) {
            String irPath = workflow.path("ir_path").asText();
            assertThat(paths.has(irPath))
                    .as("real controller root IR reference must be present in the bundle manifest: %s", irPath)
                    .isTrue();
            if ("End".equals(workflow.path("workflow_type").asText())) {
                assertThat(workflow.path("action_after_completion").asText()).isEqualTo("Terminal");
            }
        }
    }

    @Test
    @Story("MA-031-05-20-E14: 真实 Studio 工作流 IR 经 Controller IR 执行")
    @DisplayName("真实 Studio 导出子工作流经合成 Controller 根和标准 A2A 回显调用输入")
    void realStudioWorkflowArtifactRunsThroughController() throws Exception {
        Map<String, Object> realWorkflow = StudioExportCorpusFixture.artifactIr(
                StudioExportCorpusFixture.manifest(), "feat031-e3-child");
        ObjectNode workflow = (ObjectNode) MAPPER.valueToTree(realWorkflow);
        Path bundle = writeBundle("e14-real-workflow", "e14-real-root",
                List.of(new WorkflowEntry(
                        "bd2031b4-dfcc-4691-9056-8fac2f50650f",
                        "FEAT031_CH4_E3_Child_V1",
                        workflow,
                        "Default",
                        "Terminal")), true);

        try (RecordingOpenAiEndpointFixture model = new RecordingOpenAiEndpointFixture()) {
            int modelBaseline = model.requestCount();
            try (StackHandle handle = startControlledStack(model, bundle, "e14-real-root", null)) {
                String canary = "E14-REAL-WORKFLOW-CANARY-" + UUID.randomUUID().toString().substring(0, 8);
                InteractionFlow.FlowResult result = InteractionFlow.of(handle.stack().client(SUT))
                        .withTimeoutMs(FLOW_TIMEOUT_MS)
                        .withContextId(context("E14"))
                        .send(canary)
                        .awaitState(TaskState.TASK_STATE_COMPLETED)
                        .execute();

                assertThat(result.round(0).generatedText())
                        .as("real Studio workflow query echo")
                        .contains(canary);
                assertThat(model.requestsSince(modelBaseline))
                        .as("controller intent recognition before the real E3 child workflow")
                        .singleElement()
                        .satisfies(request -> assertThat(request.body().toString())
                                .contains("你是一个识别用户输入意图的AI助手"));
            }
        }
    }

    @Test
    @Story("MA-031-05-20-E15: 真实 Controller 根与 Default 工作流")
    @DisplayName("真实 Studio Controller 根挂真实 Default 顺序流，模型 canary 到达真实 End")
    void realControllerRootRunsRealDefaultWorkflow() throws Exception {
        String canary = "E15-REAL-DEFAULT-CANARY-" + UUID.randomUUID().toString().substring(0, 8);
        assertRealStudioBundle(E15_REAL_BUNDLE, 2);
        try (RecordingOpenAiEndpointFixture model = new RecordingOpenAiEndpointFixture()) {
            model.responseContent("{\"class\":\"其他\"}");
            int baseline = model.requestCount();
            try (StackHandle handle = startControlledStack(
                    model, E15_REAL_BUNDLE, rootAgentId(E15_REAL_BUNDLE), null)) {
                InteractionFlow.FlowResult result = InteractionFlow.of(handle.stack().client(SUT))
                        .withTimeoutMs(FLOW_TIMEOUT_MS)
                        .withContextId(context("E15"))
                        .send(canary)
                        .awaitState(TaskState.TASK_STATE_COMPLETED)
                        .execute();

                assertEndCanary(result.round(0).events(), "e15_default", canary);
                assertThat(model.requestsSince(baseline))
                        .as("Default selection still asks the controller model to classify intent")
                        .hasSize(1);
            }
        }
    }

    @Test
    @Story("MA-031-05-20-E16: 真实 Controller Intent 路由多 Normal")
    @DisplayName("真实 Studio Intent 工作流返回路由 ID 后命中真实 Normal 工作流，不串路")
    void realControllerRootRoutesIntentToRealNormalWorkflow() throws Exception {
        String queryCanary = "E16-REAL-NORMAL-CANARY-" + UUID.randomUUID().toString().substring(0, 8);
        assertRealStudioBundle(E16_REAL_BUNDLE, 6);
        try (RecordingPluginMcpEndpointFixture endpoint = new RecordingPluginMcpEndpointFixture();
                RecordingOpenAiEndpointFixture model = new RecordingOpenAiEndpointFixture()) {
            model.responseContent("{\"class\":\"理财\",\"reason\":\"route to real normal workflow\"}");
            int pluginBaseline = endpoint.requestCount();
            int baseline = model.requestCount();
            try (StackHandle handle = startControlledStack(
                    model, E16_REAL_BUNDLE, rootAgentId(E16_REAL_BUNDLE), null)) {
                InteractionFlow.FlowResult first = InteractionFlow.of(handle.stack().client(SUT))
                        .withTimeoutMs(FLOW_TIMEOUT_MS)
                        .withContextId(context("E16"))
                        .send(queryCanary)
                        .awaitState(TaskState.TASK_STATE_INPUT_REQUIRED)
                        .execute();
                model.responseContentWhenBodyContains("参数提取助手", "{\"city\":\"E16-real-city\"}");
                InteractionFlow.FlowResult result = InteractionFlow.of(handle.stack().client(SUT))
                        .withTimeoutMs(FLOW_TIMEOUT_MS)
                        .withContextId(first.round(0).contextId())
                        .withTaskId(first.round(0).taskId())
                        .send("E16-real-city")
                        .awaitState(TaskState.TASK_STATE_COMPLETED)
                        .execute();

                assertThat(endpoint.requestsSince(pluginBaseline))
                        .as("intent id 2 must select the real plugin Normal workflow, not Default")
                        .singleElement()
                        .satisfies(request -> assertThat(request.path()).startsWith("/plugin/weather"));
                Allure.addAttachment(
                        "E16 End text (non-gating: Studio data-port binding is tracked separately)",
                        result.round(0).generatedText());
                assertThat(model.requestsSince(baseline).stream()
                        .noneMatch(request -> request.body().toString().contains("分类1: 意图不明")))
                        .as("intent routing must not fall back to the fine-grained classification pass")
                        .isTrue();
            }
        }
    }

    @Test
    @Story("MA-031-05-20-E17: 真实 Controller Loop 与递归子工作流")
    @DisplayName("真实 Studio Controller 根执行 Loop 父流和递归引用子流，列表输出到达 End")
    void realControllerRootRunsRealLoopClosure() throws Exception {
        List<String> loopItems = List.of("E17-LOOP-ITEM-1", "E17-LOOP-ITEM-2");
        String loopCanary = "E17-REAL-LOOP-CANARY-" + UUID.randomUUID().toString().substring(0, 8);
        assertRealStudioBundle(E17_REAL_BUNDLE, 3);
        try (RecordingOpenAiEndpointFixture model = new RecordingOpenAiEndpointFixture()) {
            model.responseContent("{\"class\":\"其他\",\"canary\":\"" + loopCanary + "\"}");
            int baseline = model.requestCount();
            try (StackHandle handle = startControlledStack(
                    model, E17_REAL_BUNDLE, rootAgentId(E17_REAL_BUNDLE), null)) {
                InteractionFlow.FlowResult first = InteractionFlow.of(handle.stack().client(SUT))
                        .withTimeoutMs(FLOW_TIMEOUT_MS)
                        .withContextId(context("E17"))
                        .send("E17-real-loop")
                        .awaitState(TaskState.TASK_STATE_INPUT_REQUIRED)
                        .execute();
                model.responseContentWhenBodyContains(
                        "参数提取助手",
                        "{\"questions\":[\"" + loopItems.get(0) + "\",\"" + loopItems.get(1) + "\"]}");
                InteractionFlow.FlowResult result = InteractionFlow.of(handle.stack().client(SUT))
                        .withTimeoutMs(FLOW_TIMEOUT_MS)
                        .withContextId(first.round(0).contextId())
                        .withTaskId(first.round(0).taskId())
                        .send("E17-LOOP-ITEM-1,E17-LOOP-ITEM-2")
                        .awaitState(TaskState.TASK_STATE_COMPLETED)
                        .execute();

                assertEndCanary(result.round(0).events(), "e17_default", loopCanary);
                assertThat(model.requestsSince(baseline))
                        .as("the real loop body must be called at least once per metadata.inputs item")
                        .hasSizeGreaterThanOrEqualTo(loopItems.size());
            }
        }
    }

    @Test
    @Story("MA-031-05-20-E18: 真实 Controller Plugin 外部端点")
    @DisplayName("真实 Studio Plugin IR 命中固定端口受控端点恰一次，插件 canary 到达 End")
    void realControllerRootRunsRealPluginWorkflow() throws Exception {
        assertRealStudioBundle(E18_REAL_BUNDLE, 2);
        try (RecordingPluginMcpEndpointFixture endpoint = new RecordingPluginMcpEndpointFixture();
                RecordingOpenAiEndpointFixture model = new RecordingOpenAiEndpointFixture()) {
            model.responseContent("{\"class\":\"其他\"}");
            int baseline = endpoint.requestCount();
            try (StackHandle handle = startControlledStack(
                    model, E18_REAL_BUNDLE, rootAgentId(E18_REAL_BUNDLE), null)) {
                InteractionFlow.FlowResult first = InteractionFlow.of(handle.stack().client(SUT))
                        .withTimeoutMs(FLOW_TIMEOUT_MS)
                        .withContextId(context("E18"))
                        .send("E18-real-plugin")
                        .awaitState(TaskState.TASK_STATE_INPUT_REQUIRED)
                        .execute();
                model.responseContentWhenBodyContains(
                        "参数提取助手", "{\"city\":\"E18-real-city\"}");
                InteractionFlow.FlowResult result = InteractionFlow.of(handle.stack().client(SUT))
                        .withTimeoutMs(FLOW_TIMEOUT_MS)
                        .withContextId(first.round(0).contextId())
                        .withTaskId(first.round(0).taskId())
                        .send("E18-real-city")
                        .awaitState(TaskState.TASK_STATE_COMPLETED)
                        .execute();

                List<RecordingPluginMcpEndpointFixture.RequestRecord> calls =
                        endpoint.requestsSince(baseline);
                Allure.addAttachment("E18 real plugin requests", calls.toString());
                assertThat(calls).hasSize(1);
                assertThat(calls.get(0).path()).startsWith("/plugin/weather");
                Allure.addAttachment(
                        "E18 End text (non-gating: Studio data-port binding is tracked separately)",
                        result.round(0).generatedText());
            }
        }
    }

    private static String sha256(Path path) {
        try {
            return HexFormat.of().withUpperCase().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (Exception exception) {
            throw new IllegalStateException("cannot hash " + path, exception);
        }
    }

    private static String rootAgentId(Path bundle) throws IOException {
        JsonNode provenance = MAPPER.readTree(bundle.resolve("provenance.json").toFile());
        String rootAgentId = provenance.path("source").path("rootAgentId").asText();
        assertThat(rootAgentId).isNotBlank();
        return rootAgentId;
    }

    private static void assertRealStudioBundle(Path bundle, int expectedIrCount)
            throws IOException {
        String rootAgentId = rootAgentId(bundle);
        JsonNode root = MAPPER.readTree(bundle.resolve("ir/" + rootAgentId + ".json").toFile());
        JsonNode manifest = MAPPER.readTree(bundle.resolve("manifest.json").toFile());
        JsonNode provenance = MAPPER.readTree(bundle.resolve("provenance.json").toFile());
        JsonNode paths = manifest.path("irPathToLocal");
        assertThat(paths.size()).isEqualTo(expectedIrCount);
        assertThat(provenance.path("files").size()).isEqualTo(expectedIrCount);
        assertThat(provenance.path("source").path("exportSha256").asText()).hasSize(64);

        provenance.path("files").fields().forEachRemaining(entry ->
                assertThat(sha256(bundle.resolve(entry.getKey())))
                        .as("byte-for-byte Studio export hash for %s", entry.getKey())
                        .isEqualTo(entry.getValue().asText()));

        for (JsonNode workflow : root.path("configs").path("workflows")) {
            String irPath = workflow.path("ir_path").asText();
            assertThat(paths.has(irPath))
                    .as("real controller root IR reference must be present in the bundle manifest: %s", irPath)
                    .isTrue();
        }
    }

    @Test
    @Stories({
        @Story("MA-031-05-20-E11: 直接 Java 与 IR 外部等价"),
        @Story("MA-031-05-20-B15: 直接 builder 与 IR 运行期行为等价")
    })
    @DisplayName("同输入下直接 builder 主成员与 IR 路径产生同一业务 canary")
    void directBuilderAndIrPathKeepSameBusinessCanary() throws Exception {
        String canary = "E11-BUSINESS-CANARY";
        Path bundle = writeTelemetryBundle("e11");
        try (RecordingOpenAiEndpointFixture model = new RecordingOpenAiEndpointFixture();
                SutStack direct = SutStack.builder(config)
                        .streaming(true)
                        .agent("multi-agent-group-sit")
                        .start();
                StackHandle ir = startControlledStack(model, bundle, "e11-root", null)) {
            InteractionFlow.RoundResult directRound = InteractionFlow.of(direct.client("multi-agent-group-sit"))
                    .withTimeoutMs(FLOW_TIMEOUT_MS)
                    .withContextId(context("E11-direct"))
                    .send(canary)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .execute()
                    .round(0);
            InteractionFlow.RoundResult irRound = InteractionFlow.of(ir.stack().client(SUT))
                    .withTimeoutMs(FLOW_TIMEOUT_MS)
                    .withContextId(context("E11-ir"))
                    .send(canary)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .execute()
                    .round(0);

            assertThat(directRound.taskState()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
            assertThat(irRound.taskState()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
            JsonNode directTelemetry = directTelemetry(directRound.events());
            JsonNode irTelemetry = MAPPER.readTree(endResultText(irRound.events()));
            assertThat(irTelemetry.path("agent_id").asText()).isEqualTo(directTelemetry.path("agent_id").asText());
            assertThat(irTelemetry.path("echo").asText()).isEqualTo(directTelemetry.path("echo").asText());
            assertThat(irTelemetry.path("phase").asText()).isEqualTo(directTelemetry.path("phase").asText());
            assertThat(irTelemetry.path("pre_interrupt_actions").asInt())
                    .isEqualTo(directTelemetry.path("pre_interrupt_actions").asInt());
            assertThat(irTelemetry.path("resume_actions").asInt())
                    .isEqualTo(directTelemetry.path("resume_actions").asInt());
        }
    }

    private static StackHandle startRealModelStack(Path bundle, String rootAgentId) {
        String apiKey = requiredEnv("LLM_API_KEY");
        String baseUrl = requiredAny("LLM_API_BASE", "LLM_BASE_URL");
        String model = requiredAny("LLM_MODEL", "LLM_MODEL_NAME");
        SutStack stack = commonBuilder(apiKey, baseUrl, model, bundle, rootAgentId, null).start();
        return new StackHandle(stack, null, null);
    }

    private static StackHandle startControlledStack(
            RecordingOpenAiEndpointFixture model, Path bundle, String rootAgentId, String extraHeaders) {
        SutStack stack = commonBuilder("sit-controlled-key", model.baseUrl(), "deepseek-flash",
                bundle, rootAgentId, extraHeaders).start();
        return new StackHandle(stack, model, null);
    }

    private static SutStack.Builder commonBuilder(
            String apiKey, String baseUrl, String model, Path bundle, String rootAgentId, String extraHeaders) {
        String redisHost = requiredEnv("REDIS_HOST");
        String redisPort = requiredEnv("REDIS_PORT");
        return SutStack.builder(config)
                .streaming(true)
                .agent(SUT, agent -> {
                    agent.env("LLM_API_KEY", apiKey)
                            .env("LLM_API_BASE", baseUrl)
                            .env("LLM_MODEL_NAME", model)
                            .env("LLM_MODEL_TYPE", System.getenv().getOrDefault("LLM_MODEL_TYPE", "OpenAI"))
                            .env("REDIS_HOST", redisHost)
                            .env("REDIS_PORT", redisPort)
                            .env("JAVA_TOOL_OPTIONS", "-Dstudio.dsl.outbound.allowPrivate=true")
                            .env("CONTROLLER_IR_BUNDLE_DIR", bundle.toString())
                            .env("CONTROLLER_IR_ROOT_AGENT_ID", rootAgentId)
                            .property("openjiuwen.service.middleware.checkpointer.type", "redis")
                            .property("openjiuwen.service.middleware.checkpointer.redis-ref", "default")
                            .property("openjiuwen.service.middleware.redis.default.type", "standalone")
                            .property("openjiuwen.service.middleware.redis.default.host", redisHost)
                            .property("openjiuwen.service.middleware.redis.default.port", redisPort)
                            .property("openjiuwen.service.middleware.redis.default.database", "0")
                            .property("openjiuwen.service.middleware.redis.default.timeout-ms", "5000")
                            .property("openjiuwen.service.middleware.redis.default.encrypted-password", "")
                            .property("studio.dsl.outbound.allowPrivate", "true");
                    if (extraHeaders != null) {
                        agent.env("LLM_EXTRA_HEADERS", extraHeaders);
                    }
                });
    }

    private static Path writeLlmBundle(String name, String prompt, String canary) throws IOException {
        String workflowId = name + "-workflow";
        ObjectNode workflow = workflowBase(workflowId);
        ArrayNode components = workflow.putArray("components");
        components.add(startComponent());
        components.add(llmComponent(prompt));
        components.add(endComponent("${node_llm.userFields.raw_output}"));
        connect(workflow, "node_start", "node_llm", null);
        connect(workflow, "node_llm", "node_end", null);
        return writeBundle(name, name + "-root",
                List.of(new WorkflowEntry(workflowId, null, workflow, "Default", "Terminal")), true);
    }

    private static Path writePluginBundle(String name) throws IOException {
        String workflowId = name + "-plugin-workflow";
        String pluginJson = Files.readString(Path.of(
                "src/test/resources/testdata/studio_dsl/studio-export/corpus/"
                        + "feat031_e5_plugin_endpoint_ir.json"))
                .replace("${node_start.userFields.city}", "${node_start.systemFields.query}");
        ObjectNode pluginWorkflow = (ObjectNode) MAPPER.readTree(pluginJson);
        ObjectNode pluginNode = null;
        for (JsonNode component : pluginWorkflow.withArray("components")) {
            if ("node_e5_plugin".equals(component.path("id").asText())) {
                pluginNode = (ObjectNode) component;
                break;
            }
        }
        if (pluginNode == null) {
            throw new IllegalStateException("plugin workflow is missing node_e5_plugin");
        }
        for (JsonNode component : pluginWorkflow.withArray("components")) {
            if ("node_start".equals(component.path("id").asText())) {
                ((ObjectNode) component).withObject("configs").withObject("userFields").putArray("inputs");
            }
        }
        ArrayNode arguments = (ArrayNode) pluginNode.path("configs").path("arguments");
        arguments.removeAll();
        pluginNode.withObject("configs").withObject("userFields").putArray("inputs");
        pluginNode.putObject("inputs").putObject("userFields");
        return writeBundle(name, name + "-root",
                List.of(new WorkflowEntry(workflowId, null, pluginWorkflow,
                        "Default", "Terminal")), true);
    }

    private static Path writeMcpBundle(String name) throws IOException {
        String workflowId = name + "-mcp-workflow";
        String workflowJson = Files.readString(Path.of(
                        "src/test/resources/testdata/studio_dsl/studio-export/corpus/"
                                + "feat031_mx_mcp_upstream_branch_ir.json"))
                .replace("43ac4024-66e7-4f0d-bd38-70b337b3726d", "deepseek-flash")
                .replace("\"workflowVersion\":\"0.0.1\"", "\"workflowVersion\":\"0.6.0\"");
        ObjectNode workflow = (ObjectNode) MAPPER.readTree(workflowJson);
        return writeBundle(name, name + "-root",
                List.of(new WorkflowEntry(workflowId, null, workflow, "Default", "Terminal")), true);
    }

    private static Path writeStartEndBundle(String name) throws IOException {
        ObjectNode start = messageWorkflow(name + "-start-workflow", "E04-START-CANARY");
        ObjectNode normal = messageWorkflow(name + "-normal-workflow", "E04-NORMAL-CANARY");
        ObjectNode end = messageWorkflow(name + "-end-workflow", "E04-END-CANARY");
        return writeBundle(name, name + "-root", List.of(
                new WorkflowEntry(name + "-start-workflow", "start", start, "Start", "Continue"),
                new WorkflowEntry(name + "-normal-workflow", "normal", normal, "Normal", "Continue"),
                new WorkflowEntry(name + "-end-workflow", "end", end, "End", "Terminal")), false);
    }

    private static Path writeLiteralBundle(String name, String canary) throws IOException {
        ObjectNode workflow = messageWorkflow(name + "-workflow", canary);
        return writeBundle(name, name + "-root",
                List.of(new WorkflowEntry(name + "-workflow", null, workflow, "Default", "Terminal")), true);
    }

    private static Path writeTelemetryBundle(String name) throws IOException {
        String workflowId = name + "-workflow";
        ObjectNode workflow = workflowBase(workflowId);
        ArrayNode components = workflow.putArray("components");
        components.add(startComponent());
        ObjectNode probe = MAPPER.createObjectNode();
        probe.put("id", "node_probe");
        probe.put("name", "probe");
        probe.put("type", "jiuwen.code");
        ObjectNode configs = probe.putObject("configs");
        configs.put("code", "import json\n\n"
                + "def main(args: dict) -> dict:\n"
                + "    query = args.get('query', '')\n"
                + "    payload = {'agent_id': 'main', 'echo': query, 'phase': 'fresh',\n"
                + "               'pre_interrupt_actions': 0, 'resume_actions': 0}\n"
                + "    return {'result': json.dumps(payload, ensure_ascii=False)}\n");
        ObjectNode inputs = configs.putObject("userFields").putArray("inputs").addObject();
        inputs.put("id", "query");
        inputs.put("type", "string");
        inputs.put("sourceType", "ref");
        inputs.put("required", false);
        inputs.put("value", "${node_start.systemFields.query}");
        configs.withObject("userFields").putArray("outputs").addObject()
                .put("id", "result")
                .put("type", "string")
                .put("sourceType", "null")
                .put("required", true);
        configs.put("enable_sandbox", false);
        probe.putObject("inputs").putObject("userFields").put("query", "${node_start.systemFields.query}");
        probe.putObject("outputs").putObject("userFields").put("result", "");
        components.add(probe);
        components.add(endComponent("${node_probe.userFields.result}"));
        connect(workflow, "node_start", "node_probe", null);
        connect(workflow, "node_probe", "node_end", null);
        return writeBundle(name, name + "-root",
                List.of(new WorkflowEntry(workflowId, null, workflow, "Default", "Terminal")), true);
    }

    private static Path writeBundle(
            String name, String rootAgentId, List<WorkflowEntry> workflows, boolean defaultOnly) throws IOException {
        Path bundle = tempRoot.resolve(name);
        Path irDir = bundle.resolve("ir");
        Files.createDirectories(irDir);
        ObjectNode manifest = MAPPER.createObjectNode();
        ObjectNode paths = manifest.putObject("irPathToLocal");
        manifest.put("modelMapping", "model-mapping.json");

        ObjectNode root = controllerWorkflowJson();
        root.put("agentId", rootAgentId);
        root.put("agentName", name + "-controller");
        ObjectNode configs = root.withObject("configs");
        ArrayNode workflowConfigs = configs.putArray("workflows");
        for (int i = 0; i < workflows.size(); i++) {
            WorkflowEntry entry = workflows.get(i);
            String irPath = "workflow/ir/" + entry.id() + "/" + entry.id() + "_" + (1780000000000L + i) + ".json";
            String local = "ir/" + entry.id() + ".json";
            Files.writeString(irDir.resolve(entry.id() + ".json"),
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(entry.ir()));
            paths.put(irPath, local);
            ObjectNode workflowConfig = workflowConfigs.addObject();
            workflowConfig.put("id", entry.id());
            workflowConfig.put("name", entry.name() == null ? entry.id() : entry.name());
            workflowConfig.put("ir_path", irPath);
            workflowConfig.put("workflow_type", defaultOnly ? "Default" : entry.type());
            workflowConfig.put("action_after_completion", defaultOnly ? "Terminal" : entry.action());
        }
        Files.writeString(bundle.resolve("manifest.json"),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
        Files.writeString(bundle.resolve("model-mapping.json"),
                "{\"schemaVersion\":\"1\",\"models\":{\"deepseek-flash\":{\"endpoint\":\"\",\"apiKey\":\"\"}}}");
        Files.writeString(irDir.resolve("root.json"),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        paths.put("agent/ir/" + rootAgentId + "/" + rootAgentId + ".json", "ir/root.json");
        Files.writeString(bundle.resolve("manifest.json"),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
        return bundle;
    }

    private static ObjectNode controllerWorkflowJson() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", "0.6.0");
        root.put("agentId", "placeholder");
        root.put("agentVersion", "0.6.0");
        root.put("agentName", "placeholder");
        root.put("description", "FEAT-031 §7 controlled E2E bundle");
        ObjectNode configs = root.putObject("configs");
        configs.put("mode", "Controller");
        configs.put("maxIteration", 9);
        configs.put("chatHistoryMaxTurn", 10);
        configs.put("specify_workflow_order", true);
        configs.put("sysPromptTemplate", "FEAT-031 §7 controlled E2E controller");
        configs.putArray("agents");
        configs.putArray("global_intents");
        configs.putArray("global_variables");
        return root;
    }

    private static ObjectNode workflowBase(String workflowId) {
        ObjectNode workflow = MAPPER.createObjectNode();
        workflow.put("schemaVersion", "0.6.0");
        workflow.put("workflowId", workflowId);
        workflow.put("workflowName", workflowId);
        workflow.put("workflowVersion", "0.6.0");
        workflow.putObject("metadata");
        return workflow;
    }

    private static ObjectNode startComponent() {
        ObjectNode component = MAPPER.createObjectNode();
        component.put("id", "node_start");
        component.put("name", "start");
        component.put("type", "jiuwen.start");
        ObjectNode configs = component.putObject("configs");
        ObjectNode systemFields = configs.putObject("systemFields");
        systemFields.putArray("inputs");
        systemFields.putArray("outputs");
        component.putObject("inputs").putObject("systemFields").put("query", "");
        component.putObject("outputs").putObject("systemFields").put("query", "");
        return component;
    }

    private static ObjectNode llmComponent(String prompt) {
        ObjectNode component = MAPPER.createObjectNode();
        component.put("id", "node_llm");
        component.put("name", "llm");
        component.put("type", "jiuwen.LLMComponent");
        ObjectNode configs = component.putObject("configs");
        ObjectNode userFields = configs.putObject("userFields");
        userFields.putArray("inputs").addObject()
                .put("id", "query")
                .put("type", "string")
                .put("sourceType", "ref")
                .put("required", false)
                .put("value", "${node_start.systemFields.query}");
        userFields.putArray("outputs").addObject()
                .put("id", "raw_output")
                .put("type", "string")
                .put("sourceType", "null")
                .put("required", false);
        configs.putObject("memory").put("enable", false);
        configs.put("deployMode", "workflow");
        configs.put("enableHistory", false);
        ArrayNode messages = configs.putArray("templateContent");
        messages.addObject().put("role", "user").put("content", prompt);
        configs.putObject("responseFormat").put("type", "text");
        ObjectNode model = configs.putObject("model");
        model.put("modelName", "deepseek-flash");
        model.put("modelType", "LLM");
        model.putObject("hyperParameters").put("temperature", 0.0);
        component.putObject("outputs").putObject("userFields").put("raw_output", "");
        component.putObject("inputs").putObject("userFields").put("query", "${node_start.systemFields.query}");
        return component;
    }

    private static ObjectNode messageWorkflow(String workflowId, String canary) {
        ObjectNode workflow = workflowBase(workflowId);
        ArrayNode components = workflow.putArray("components");
        components.add(startComponent());
        ObjectNode message = MAPPER.createObjectNode();
        message.put("id", "node_probe");
        message.put("name", "probe");
        message.put("type", "jiuwen.message");
        ObjectNode configs = message.putObject("configs");
        configs.put("template", canary);
        configs.putObject("userFields").putArray("inputs");
        configs.putObject("userFields").putArray("outputs");
        configs.putObject("systemFields").putArray("inputs");
        configs.putObject("systemFields").putArray("outputs");
        message.putObject("outputs").putObject("userFields").put("results", "");
        components.add(message);
        components.add(endComponent(null, canary));
        connect(workflow, "node_start", "node_probe", null);
        connect(workflow, "node_probe", "node_end", null);
        return workflow;
    }

    private static ObjectNode endComponent(String valueExpression) {
        return endComponent(valueExpression, "{{result}}");
    }

    private static ObjectNode endComponent(String valueExpression, String responseTemplate) {
        ObjectNode component = MAPPER.createObjectNode();
        component.put("id", "node_end");
        component.put("name", "end");
        component.put("type", "jiuwen.end");
        ObjectNode configs = component.putObject("configs");
        configs.put("responseMode", "directResponse");
        configs.put("responseTemplate", responseTemplate);
        configs.put("isStreamOut", true);
        configs.put("enable_history", true);
        configs.put("isStructMessage", false);
        if (valueExpression != null) {
            ObjectNode inputs = configs.putObject("userFields").putArray("inputs").addObject()
                    .put("id", "result")
                    .put("type", "string")
                    .put("sourceType", "ref")
                    .put("required", false);
            inputs.put("value", valueExpression);
            component.putObject("inputs").putObject("userFields").put("result", valueExpression);
        }
        component.putObject("outputs").putObject("userFields").put("responseContent", "");
        return component;
    }

    private static void connect(ObjectNode workflow, String source, String target, String branchId) {
        ObjectNode connection = workflow.withArray("connections").addObject();
        ObjectNode sourceNode = connection.putObject("source");
        sourceNode.put("componentId", source);
        if (branchId != null) {
            sourceNode.put("branchId", branchId);
        }
        connection.putObject("target").put("componentId", target);
    }

    private static void assertEndCanary(List<InboundEvent> events, String workflowId, String canary) {
        Allure.addAttachment("controller-ir events", events.stream()
                .map(event -> event.kind() + " " + event.text())
                .collect(Collectors.joining("\n")));
        assertThat(events)
                .as("A2A stream must carry the target End artifact; canary=%s workflow=%s", canary, workflowId)
                .anySatisfy(event -> assertThat(endPayload(event, canary))
                        .as("event must be an End payload containing the target canary: %s", event.text())
                        .isTrue());
    }

    private static boolean endPayload(InboundEvent event, String canary) {
        JsonNode root = parse(event.text());
        if (root == null) {
            return false;
        }
        return containsEndPayload(root, false, canary);
    }

    private static JsonNode directTelemetry(List<InboundEvent> events) {
        for (InboundEvent event : events) {
            JsonNode root = parse(event.text());
            if (root == null) {
                continue;
            }
            JsonNode data = root.path("data").path("data");
            if ("stream".equals(root.path("data").path("msg").asText())
                    && data.isObject()
                    && "main".equals(data.path("agent_id").asText())) {
                return data;
            }
        }
        throw new AssertionError("direct SUT telemetry event not found");
    }

    private static String endResultText(List<InboundEvent> events) {
        for (InboundEvent event : events) {
            JsonNode root = parse(event.text());
            if (root == null) {
                continue;
            }
            JsonNode end = findEndNode(root, false);
            if (end != null) {
                String result = firstText(end,
                        "/data/userFields/result",
                        "/userFields/result",
                        "/data/result",
                        "/result",
                        "/answer");
                if (result != null && !result.isBlank()) {
                    return result;
                }
            }
        }
        throw new AssertionError("End result not found");
    }

    private static JsonNode findEndNode(JsonNode node, boolean insideEnd) {
        boolean endHere = insideEnd || hasNodeType(node, "jiuwen.end");
        if (endHere && (node.path("data").path("userFields").has("result")
                || node.path("userFields").has("result")
                || node.path("data").has("result")
                || node.has("result"))) {
            return node;
        }
        for (JsonNode child : node) {
            JsonNode found = findEndNode(child, endHere);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String firstText(JsonNode node, String... pointers) {
        for (String pointer : pointers) {
            JsonNode value = node.at(pointer);
            if (!value.isMissingNode() && !value.isNull()) {
                return value.asText();
            }
        }
        return null;
    }

    private static boolean containsEndPayload(JsonNode node, boolean insideEnd, String canary) {
        if (node == null) {
            return false;
        }
        boolean endHere = insideEnd || hasNodeType(node, "jiuwen.end");
        if (endHere && node.toString().contains(canary)) {
            return true;
        }
        for (JsonNode child : node) {
            if (containsEndPayload(child, endHere, canary)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNodeType(JsonNode node, String expected) {
        return node.findValues("node_type").stream()
                .anyMatch(value -> expected.equals(value.asText()));
    }

    private static int endEventCount(List<InboundEvent> events) {
        return (int) events.stream()
                .filter(event -> event.text().contains("\"type\":\"workflow_end\""))
                .count();
    }

    private static JsonNode parse(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String requiredAny(String... names) {
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        org.junit.jupiter.api.Assumptions.abort("missing required environment variable: " + String.join("/", names));
        return null;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        org.junit.jupiter.api.Assumptions.assumeTrue(value != null && !value.isBlank(),
                () -> "missing required environment variable: " + name);
        return value;
    }

    private static String context(String suffix) {
        return "feat031-s7-" + suffix + '-' + UUID.randomUUID().toString().substring(0, 8);
    }

    private record WorkflowEntry(String id, String name, ObjectNode ir, String type, String action) {
    }

    private record StackHandle(SutStack stack, RecordingOpenAiEndpointFixture model,
                               RecordingPluginMcpEndpointFixture endpoint) implements AutoCloseable {
        @Override
        public void close() {
            if (stack != null) {
                stack.close();
            }
        }
    }
}

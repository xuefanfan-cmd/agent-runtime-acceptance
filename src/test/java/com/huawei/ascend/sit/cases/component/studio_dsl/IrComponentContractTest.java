/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.studio_dsl;

import com.huawei.ascend.sit.fixtures.studio_dsl.StudioDslContractTestBase;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioDslTestResources;
import com.huawei.ascend.sit.fixtures.studio_dsl.WorkflowAssemblyProbe;
import com.huawei.ascend.sit.fixtures.studio_dsl.RecordingPluginMcpEndpointFixture;
import com.huawei.ascend.sit.fixtures.studio_dsl.TokenEndpointStubFixture;
import com.openjiuwen.core.workflow.WorkflowComponent;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.ir.IrAssembleContext;
import com.openjiuwen.studio.dsl.ir.IrAssembleException;
import com.openjiuwen.studio.dsl.ir.IrComponentFactory;
import com.openjiuwen.studio.dsl.ir.LazySubWorkflowComponent;
import com.openjiuwen.studio.dsl.ir.StudioIrSdk;
import com.openjiuwen.studio.dsl.nodes.FlowLoopNode;
import com.openjiuwen.studio.dsl.nodes.FlowMcpNode;
import com.openjiuwen.studio.dsl.nodes.FlowPluginNode;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("component")
@Tag("contract")
@Tag("feat-031")
@Feature("FEAT-031: Studio DSL Java 承载")
class IrComponentContractTest extends StudioDslContractTestBase {
    private static final IrAssembleContext CONTEXT =
            IrAssembleContext.builder().workflowId("wf-component-contract").build();

    @Test
    @DisplayName("别名和大小写变体归一到同一公开组件类型")
    @Story("RT-031-04-20: 组件类型归一与别名匹配")
    void normalizeAliasesWithoutRewritingInput() {
        assertSameTarget("jiuwen.LLMComponent", "JIUWEN.LLMCHAIN");
        assertSameTarget("jiuwen.LLMComponent", " jiuwen.llm ");
        assertSameTarget("jiuwen.LLMComponent", "jiuwen.llm_chain");
        assertSameTarget("jiuwen.plugin", "jiuwen.flowApi");
        assertSameTarget("jiuwen.plugin", "JIUWEN.API");
        assertSameTarget("jiuwen.mcp", "JIUWEN.FLOWMCP");
        assertSameTarget("jiuwen.aggregate", "jiuwen.aggregation");
        assertSameTarget("jiuwen.aggregate", "JIUWEN.FLOWAGGREGATE");
        assertSameTarget("jiuwen.extractor", "jiuwen.infoExtraction");
        assertSameTarget("jiuwen.input", "JIUWEN.FLOWINPUT");
        assertSameTarget("jiuwen.card", "jiuwen.flowCard");
        assertSameTarget("jiuwen.agent", "JIUWEN.FLOWAGENT");
        assertThat(assembleSubWorkflow("jiuwen.subWorkflow"))
                .isExactlyInstanceOf(LazySubWorkflowComponent.class);
        assertThat(assembleSubWorkflow(" JIUWEN.WORKFLOWCOMPOSITE "))
                .isExactlyInstanceOf(LazySubWorkflowComponent.class);

        assertThat(IrComponentFactory.isSupportedType("jiuwen.notRegistered")).isFalse();
        assertThatThrownBy(() -> IrComponentFactory.createSimple(
                "unknown", "jiuwen.notRegistered", Map.of(), CONTEXT))
                .isInstanceOfSatisfying(IrAssembleException.class, exception ->
                        assertThat(exception.code()).isEqualTo("IR_COMPONENT_UNKNOWN"));
    }

    @Test
    @DisplayName("插件与 MCP 内联调用配置保留在公开节点配置中")
    @Story("RT-031-04-23: 插件/MCP 调用配置装配")
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void exposePluginAndMcpCallConfiguration() throws Exception {
        String property = "studio.dsl.outbound.allowPrivate";
        String previous = System.getProperty(property);
        System.setProperty(property, "true");
        try (RecordingPluginMcpEndpointFixture endpoint = new RecordingPluginMcpEndpointFixture();
                TokenEndpointStubFixture tokenEndpoint = new TokenEndpointStubFixture()) {
            Map<String, Object> pluginConfig = new LinkedHashMap<>();
            pluginConfig.put("url", endpoint.pluginUrl());
            pluginConfig.put("method", "POST");
            pluginConfig.put("headers", Map.of(
                    "Content-Type", "application/json", "X-Contract", "plugin-canary"));
            pluginConfig.put("arguments", List.of(Map.of(
                    "name", "city", "description", "city", "type", "string",
                    "required", true, "method", "Body")));
            pluginConfig.put("response", List.of(Map.of("name", "temperature")));
            pluginConfig.put("auth", Map.of(
                    "scope", "API_KEY", "headers", Map.of("X-Inline-Auth", "inline-plugin-token")));

            Map<String, Object> mcpConfig = new LinkedHashMap<>();
            mcpConfig.put("type", "streamable_http");
            mcpConfig.put("url", endpoint.mcpUrl());
            mcpConfig.put("name", "feat031-mcp");
            mcpConfig.put("tool_name", "weather");
            mcpConfig.put("headers", Map.of(
                    "Authorization", "Bearer inline-mcp-token", "X-Contract", "mcp-canary"));
            mcpConfig.put("auth", Map.of("scope", "SERVICE"));
            mcpConfig.put("arguments", List.of(Map.of(
                    "name", "query", "description", "query", "type", "string",
                    "required", true, "method", "Body")));

            FlowPluginNode plugin = (FlowPluginNode) IrComponentFactory.createSimple(
                    "node_plugin", "jiuwen.flowApi", pluginConfig, CONTEXT);
            FlowMcpNode mcp = (FlowMcpNode) IrComponentFactory.createSimple(
                    "node_mcp", "jiuwen.flowMcp", mcpConfig, CONTEXT);
            int pluginBaseline = endpoint.requestCount();
            plugin.invoke(Map.of("userFields", Map.of("city", "Shenzhen")), null, null);
            List<RecordingPluginMcpEndpointFixture.RequestRecord> pluginRequests =
                    endpoint.requestsSince(pluginBaseline);
            int mcpBaseline = endpoint.requestCount();
            mcp.invoke(Map.of("userFields", Map.of("query", "rt-031-04-23-canary")), null, null);
            List<RecordingPluginMcpEndpointFixture.RequestRecord> mcpRequests =
                    endpoint.requestsSince(mcpBaseline);

            assertThat(plugin.configs()).containsAllEntriesOf(pluginConfig);
            assertThat(mcp.configs()).containsAllEntriesOf(mcpConfig);
            assertThat(pluginRequests).singleElement().satisfies(request -> {
                assertThat(request.method()).isEqualTo("POST");
                assertThat(request.path()).startsWith("/plugin/weather");
                assertThat(request.header("X-Contract")).isEqualTo("plugin-canary");
                assertThat(request.body().path("city").asText()).isEqualTo("Shenzhen");
            });
            assertThat(mcpRequests).anySatisfy(request -> {
                assertThat(request.path()).startsWith("/mcp");
                assertThat(request.header("Authorization")).isEqualTo("Bearer inline-mcp-token");
                assertThat(request.header("X-Contract")).isEqualTo("mcp-canary");
                assertThat(request.body().path("method").asText()).isEqualTo("tools/call");
                assertThat(request.body().path("params").path("name").asText()).isEqualTo("weather");
                assertThat(request.body().path("params").path("arguments")
                        .path("query").asText()).isEqualTo("rt-031-04-23-canary");
            });
            assertThat(tokenEndpoint.requestCount()).isZero();
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @Test
    @DisplayName("插件节点内联调用配置保留并在执行期直调")
    @Story("RT-031-04-23/plugin: 插件调用配置装配")
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void exposePluginCallConfiguration() throws Exception {
        String property = "studio.dsl.outbound.allowPrivate";
        String previous = System.getProperty(property);
        System.setProperty(property, "true");
        try (RecordingPluginMcpEndpointFixture endpoint = new RecordingPluginMcpEndpointFixture();
                TokenEndpointStubFixture tokenEndpoint = new TokenEndpointStubFixture()) {
            Map<String, Object> pluginConfig = new LinkedHashMap<>();
            pluginConfig.put("url", endpoint.pluginUrl());
            pluginConfig.put("method", "POST");
            pluginConfig.put("headers", Map.of(
                    "Content-Type", "application/json", "X-Contract", "plugin-canary"));
            pluginConfig.put("arguments", List.of(Map.of(
                    "name", "city", "description", "city", "type", "string",
                    "required", true, "method", "Body")));
            pluginConfig.put("response", List.of(Map.of("name", "temperature")));
            pluginConfig.put("auth", Map.of(
                    "scope", "API_KEY", "headers", Map.of("X-Inline-Auth", "inline-plugin-token")));

            FlowPluginNode plugin = (FlowPluginNode) IrComponentFactory.createSimple(
                    "node_plugin", "jiuwen.flowApi", pluginConfig, CONTEXT);
            int baseline = endpoint.requestCount();
            plugin.invoke(Map.of("userFields", Map.of("city", "Shenzhen")), null, null);
            List<RecordingPluginMcpEndpointFixture.RequestRecord> pluginRequests =
                    endpoint.requestsSince(baseline);

            assertThat(plugin.configs()).containsAllEntriesOf(pluginConfig);
            assertThat(pluginRequests).singleElement().satisfies(request -> {
                assertThat(request.method()).isEqualTo("POST");
                assertThat(request.path()).startsWith("/plugin/weather");
                assertThat(request.header("X-Contract")).isEqualTo("plugin-canary");
                assertThat(request.body().path("city").asText()).isEqualTo("Shenzhen");
            });
            assertThat(tokenEndpoint.requestCount()).isZero();
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @Test
    @DisplayName("L2 canonical 节点类型映射到指定公开组件")
    @Story("RT-031-04-24: 全量节点类型装配覆盖")
    void mapAllCanonicalTypesThroughPublicDispatch() {
        for (ComponentCase componentCase : canonicalSimpleTypes()) {
            WorkflowComponent component = IrComponentFactory.createSimple(
                    "node_" + componentCase.type().replaceAll("[^a-zA-Z0-9]", "_"),
                    componentCase.type(),
                    minimalConfigsFor(componentCase.type()),
                    CONTEXT);
            assertThat(component).as(componentCase.type()).isInstanceOf(componentCase.expectedType());
            if (component instanceof AbstractStudioNode studioNode) {
                assertThat(studioNode.nodeType()).as(componentCase.type()).isNotBlank();
            }
        }
        assertThat(canonicalSimpleTypes()).hasSize(22);

        WorkflowAssemblyProbe.Snapshot loop = WorkflowAssemblyProbe.inspect(
                StudioIrSdk.loadWorkflow(loopIr()));
        assertThat(loop.nodes().get("node_loop")).isExactlyInstanceOf(FlowLoopNode.class);
        assertThat(loop.studioTypes().get("node_loop")).isEqualTo("jiuwen.loop");

        ExecutableCase subWorkflow = new ExecutableCase(
                "jiuwen.subWorkflow", assembleSubWorkflow("jiuwen.subWorkflow"));
        assertThat(subWorkflow.component()).as(subWorkflow.type())
                .isExactlyInstanceOf(LazySubWorkflowComponent.class);

        assertThatThrownBy(() -> IrComponentFactory.createSimple(
                "unknown", "jiuwen.notRegistered", Map.of(), CONTEXT))
                .isInstanceOfSatisfying(IrAssembleException.class, exception ->
                        assertThat(exception.code()).isEqualTo("IR_COMPONENT_UNKNOWN"));
    }

    private static void assertSameTarget(String canonical, String alias) {
        Map<String, Object> configs = minimalConfigsFor(canonical);
        WorkflowComponent canonicalComponent =
                IrComponentFactory.createSimple("canonical", canonical, configs, CONTEXT);
        WorkflowComponent aliasComponent =
                IrComponentFactory.createSimple("alias", alias, configs, CONTEXT);
        assertThat(aliasComponent).isExactlyInstanceOf(canonicalComponent.getClass());
        if (canonicalComponent instanceof AbstractStudioNode canonicalNode
                && aliasComponent instanceof AbstractStudioNode aliasNode) {
            assertThat(aliasNode.nodeType()).isEqualTo(canonicalNode.nodeType());
        }
    }

    private static Map<String, Object> minimalConfigsFor(String type) {
        return switch (type) {
            case "jiuwen.aggregate" -> Map.of("groups", Map.of());
            case "jiuwen.message" -> Map.of("template", "component-contract");
            case "EI.ComplexIntentDetection" -> Map.of(
                    "branches", List.of(Map.of("id", "intent-contract", "catalog", "contract")),
                    "groups", Map.of("result", List.of("${intent-contract.result}")));
            default -> Map.of();
        };
    }

    private static List<ComponentCase> canonicalSimpleTypes() {
        return List.of(
                new ComponentCase("jiuwen.start", com.openjiuwen.studio.dsl.nodes.FlowStartNode.class),
                new ComponentCase("jiuwen.end", com.openjiuwen.studio.dsl.nodes.FlowEndNode.class),
                new ComponentCase("jiuwen.branch", com.openjiuwen.studio.dsl.nodes.FlowBranchNode.class),
                new ComponentCase("jiuwen.aggregate", com.openjiuwen.studio.dsl.nodes.FlowAggregateNode.class),
                new ComponentCase("jiuwen.setVariable", com.openjiuwen.studio.dsl.nodes.FlowSetVariableNode.class),
                new ComponentCase("jiuwen.exception", com.openjiuwen.studio.dsl.nodes.FlowExceptionNode.class),
                new ComponentCase("jiuwen.LLMComponent", com.openjiuwen.studio.dsl.nodes.FlowLlmNode.class),
                new ComponentCase("jiuwen.intentDetection", com.openjiuwen.studio.dsl.nodes.FlowIntentDetectionNode.class),
                new ComponentCase("jiuwen.extractor", com.openjiuwen.studio.dsl.nodes.FlowExtractorNode.class),
                new ComponentCase("jiuwen.knowledgeRetrieval", com.openjiuwen.studio.dsl.nodes.FlowKnowledgeRetrievalNode.class),
                new ComponentCase("jiuwen.input", com.openjiuwen.studio.dsl.nodes.FlowInputNode.class),
                new ComponentCase("jiuwen.message", com.openjiuwen.studio.dsl.nodes.FlowMessageNode.class),
                new ComponentCase("jiuwen.card", com.openjiuwen.studio.dsl.nodes.FlowCardNode.class),
                new ComponentCase("jiuwen.questioner", com.openjiuwen.studio.dsl.nodes.FlowQuestionerNode.class),
                new ComponentCase("jiuwen.code", com.openjiuwen.studio.dsl.nodes.FlowCodeNode.class),
                new ComponentCase("jiuwen.plugin", com.openjiuwen.studio.dsl.nodes.FlowPluginNode.class),
                new ComponentCase("jiuwen.mcp", com.openjiuwen.studio.dsl.nodes.FlowMcpNode.class),
                new ComponentCase("jiuwen.agent", com.openjiuwen.studio.dsl.nodes.FlowAgentNode.class),
                new ComponentCase("jiuwen.streamTransform", com.openjiuwen.studio.dsl.nodes.FlowStreamTransformNode.class),
                new ComponentCase("EI.qa", com.openjiuwen.studio.dsl.nodes.FlowQaNode.class),
                new ComponentCase("EI.ParamOutput", com.openjiuwen.studio.dsl.nodes.FlowParamOutputNode.class),
                new ComponentCase("EI.ComplexIntentDetection", com.openjiuwen.studio.dsl.nodes.FlowComplexIntentDetectionNode.class));
    }

    private static Object assembleSubWorkflow(String type) {
        Map<String, Object> parent = new LinkedHashMap<>(
                StudioDslTestResources.map("parent-missing-child.json"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> components = (List<Map<String, Object>>) parent.get("components");
        components.get(1).put("type", type);
        return WorkflowAssemblyProbe.inspect(StudioIrSdk.loadWorkflow(
                        parent,
                        IrAssembleContext.builder()
                                .workflowId("wf-component-contract")
                                .childIrLoader(path -> StudioDslTestResources.map("minimal-start-end.json"))
                                .build()))
                .nodes()
                .get("node_child");
    }

    private static Map<String, Object> loopIr() {
        Map<String, Object> ir = new LinkedHashMap<>();
        ir.put("workflowId", "wf-loop-contract");
        ir.put("schemaVersion", "0.6.0");
        ir.put("components", List.of(
                component("node_start", "jiuwen.start", Map.of()),
                component("node_loop", "jiuwen.loop", Map.of(
                        "loopType", "arrayLoop",
                        "loopBody", List.of("node_body"),
                        "breakCondition", "(${node_loop.index} > 0)")),
                component("node_body", "jiuwen.message", Map.of("template", "body")),
                component("node_end", "jiuwen.end", Map.of())));
        ir.put("connections", List.of(
                edge("node_start", "node_loop"),
                edge("node_loop", "node_end")));
        return ir;
    }

    private static Map<String, Object> component(
            String id, String type, Map<String, Object> configs) {
        Map<String, Object> component = new LinkedHashMap<>();
        component.put("id", id);
        component.put("type", type);
        component.put("configs", configs);
        component.put("inputs", Map.of());
        component.put("outputs", Map.of());
        return component;
    }

    private static Map<String, Object> edge(String source, String target) {
        return Map.of("source", Map.of("componentId", source),
                "target", Map.of("componentId", target));
    }

    private record ComponentCase(String type, Class<? extends WorkflowComponent> expectedType) {}

    private record ExecutableCase(String type, Object component) {}
}

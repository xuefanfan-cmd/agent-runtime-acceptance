/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.feat056;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentCallbackEvent;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;
import com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreAgentExtHandler;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.AgentFactory;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.AgentInstanceManager;
import com.openjiuwen.service.adapters.agentcore.ext.extension.AgentInstanceCustomizer;
import com.openjiuwen.service.adapters.agentcore.ext.external.ClientToolRail;
import com.openjiuwen.service.spec.dto.ServeRequest;

import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FEAT-056 private/shared tool ownership and release isolation.
 */
@Tag("component")
@Tag("feat-056")
@Feature("FEAT-056: 业务 Agent（Lib JAR）二次开发扩展点")
class AgentInstanceCustomizerCoreOwnershipTest {
    @TempDir
    Path workspace;

    @Test
    @Stories({
        @Story("FEAT-056-C14: 共享 Tool 所有权释放"),
        @Story("FEAT-056-C15: 对象释放互不影响")
    })
    void privateAndSharedToolsFollowOwnershipAcrossInstanceRelease() throws Exception {
        List<CountingRail> rails = new ArrayList<>();
        String sharedId = "shared-" + UUID.randomUUID();
        AgentInstanceManager manager = new AgentInstanceManager(agentFactory());
        JiuwenCoreAgentExtHandler handler = new JiuwenCoreAgentExtHandler(new Object());
        handler.setAgentManager(manager);
        handler.configureInstanceCustomization("feat056-agent", List.of(capabilities(rails, sharedId)));
        handler.start();
        var firstToken = handler.prepareTask(request("one"));
        var secondToken = handler.prepareTask(request("two"));
        DeepAgent first = assertInstanceOf(DeepAgent.class, manager.get("one"));
        DeepAgent second = assertInstanceOf(DeepAgent.class, manager.get("two"));
        String firstId = first.getCard().getId();
        String secondId = second.getCard().getId();
        try {
            assertThat(rails).hasSize(2);
            assertThat(rails.get(0)).isNotSameAs(rails.get(1));
            fire(first, "other");
            fire(first, "search");
            fire(second, "search");
            assertThat(rails.get(0).calls).isEqualTo(1);
            assertThat(rails.get(1).calls).isEqualTo(1);

            assertThat(call("private-" + firstId)).isEqualTo(firstId);
            assertThat(call("private-" + secondId)).isEqualTo(secondId);
            assertThatThrownBy(() -> second.registerHarnessTool(tool(sharedId, "conflicting_name", "other")))
                    .isInstanceOf(RuntimeException.class);

            handler.completeTask(firstToken);
            assertThat(rails.get(0).uninits).isEqualTo(1);
            assertThat(Runner.callbackFramework().listCallbacks(callbackName(first))).isEmpty();
            assertThat(Runner.callbackFramework().listCallbacks(callbackName(second))).isNotEmpty();
            fire(second, "search");
            assertThat(rails.get(1).calls).isEqualTo(2);
            assertThat(call(sharedId)).isEqualTo("shared-result");

            handler.completeTask(secondToken);
            assertThat(rails.get(1).uninits).isEqualTo(1);
            assertThat(Runner.resourceMgr().getTool(sharedId)).isNull();
            assertThat(Runner.resourceMgr().getTool("private-" + firstId)).isNull();
            assertThat(Runner.resourceMgr().getTool("private-" + secondId)).isNull();
        } finally {
            handler.completeTask(firstToken);
            handler.completeTask(secondToken);
            handler.stop();
        }
    }

    @Test
    @Story("FEAT-056-C26: Rail 能观察初始化后动态注册的 Tool")
    void railRegisteredBeforeDynamicToolStillObservesLateTool() {
        AtomicInteger dynamicToolHits = new AtomicInteger();
        AgentInstanceManager manager = new AgentInstanceManager(agentFactory());
        JiuwenCoreAgentExtHandler handler = new JiuwenCoreAgentExtHandler(new Object());
        handler.setAgentManager(manager);
        handler.configureInstanceCustomization("feat056-agent", List.of((id, actual) -> {
            DeepAgent agent = assertInstanceOf(DeepAgent.class, actual);
            agent.getAgent().registerRail(new AgentRail() {
                @Override
                public void beforeToolCall(AgentCallbackContext context) {
                    if (context.getInputs() instanceof ToolCallInputs input
                            && "dynamic_tool".equals(input.getToolName())) {
                        dynamicToolHits.incrementAndGet();
                    }
                }
            });
        }));
        handler.start();
        var token = handler.prepareTask(request("dynamic-tool-task"));
        DeepAgent agent = assertInstanceOf(DeepAgent.class, manager.get("dynamic-tool-task"));
        try {
            agent.registerHarnessTool(tool("dynamic-" + agent.getCard().getId(), "dynamic_tool", "dynamic-result"));
            fire(agent, "dynamic_tool");
            assertThat(dynamicToolHits).hasValue(1);
        } finally {
            handler.completeTask(token);
            handler.stop();
        }
    }

    @Test
    @Story("FEAT-056-C28: 对象级 Rail 与请求级 Rail 共存")
    void objectRailSurvivesRequestScopedRailBindingAndClose() {
        AtomicInteger objectRailHits = new AtomicInteger();
        AgentInstanceManager manager = new AgentInstanceManager(agentFactory());
        JiuwenCoreAgentExtHandler handler = new JiuwenCoreAgentExtHandler(new Object());
        handler.setAgentManager(manager);
        handler.configureInstanceCustomization("feat056-agent", List.of((id, actual) -> {
            DeepAgent agent = assertInstanceOf(DeepAgent.class, actual);
            agent.getAgent().registerRail(new AgentRail() {
                @Override
                public void beforeToolCall(AgentCallbackContext context) {
                    objectRailHits.incrementAndGet();
                }
            });
        }));
        handler.start();
        var token = handler.prepareTask(request("request-rail-task"));
        DeepAgent agent = assertInstanceOf(DeepAgent.class, manager.get("request-rail-task"));
        String callbackName = callbackName(agent);
        int beforeBinding = Runner.callbackFramework().listCallbacks(callbackName).size();
        ServeRequest request = request("request-rail-task");
        request.setMetadata(Map.of("clientTools", List.of(Map.of(
                "name", "client_tool",
                "description", "request scoped test tool",
                "inputSchema", Map.of("type", "object", "properties", Map.of())))));
        try {
            try (ClientToolRail.Binding ignored = ClientToolRail.bind(agent, request)) {
                assertThat(Runner.callbackFramework().listCallbacks(callbackName))
                        .hasSizeGreaterThan(beforeBinding);
            }
            assertThat(Runner.callbackFramework().listCallbacks(callbackName)).hasSize(beforeBinding);
            fire(agent, "anything");
            assertThat(objectRailHits).hasValue(1);
        } finally {
            handler.completeTask(token);
            handler.stop();
        }
    }

    private AgentFactory agentFactory() {
        return new AgentFactory() {
            @Override
            public Object create() {
                String id = UUID.randomUUID().toString();
                return new DeepAgent(AgentCard.builder().id(id).name("CustomizerOwnershipTest").build(),
                        DeepAgentConfig.builder().enableTaskLoop(false)
                                .backend(Map.of("provider", "OpenAI", "api_key", "test-placeholder",
                                        "api_base", "https://example.invalid"))
                                .model(Map.of("model", "unused-local-model")).build(),
                        Workspace.builder().rootPath(workspace.resolve(id).toString()).build());
            }

            @Override
            public void destroy(Object agent) {
                assertInstanceOf(DeepAgent.class, agent).destroy();
            }
        };
    }

    private static AgentInstanceCustomizer capabilities(List<CountingRail> rails, String sharedId) {
        return (id, actual) -> {
            assertThat(id).isEqualTo("feat056-agent");
            DeepAgent agent = assertInstanceOf(DeepAgent.class, actual);
            assertThat(agent.isInitialized()).isTrue();
            CountingRail rail = new CountingRail();
            rails.add(rail);
            agent.getAgent().registerRail(rail);
            agent.registerHarnessTool(tool("private-" + agent.getCard().getId(),
                    "private_tool", agent.getCard().getId()));
            agent.registerHarnessTool(tool(sharedId, "shared_tool", "shared-result"));
        };
    }

    private static LocalFunction tool(String id, String name, String result) {
        return new LocalFunction(ToolCard.builder().id(id).name(name).description("test tool")
                .inputParams(Map.of("type", "object", "properties", Map.of())).build(), inputs -> result);
    }

    private static Object call(String id) throws Exception {
        return assertInstanceOf(Tool.class, Runner.resourceMgr().getTool(id)).invoke(Map.of(), Map.of());
    }

    private static String callbackName(DeepAgent agent) {
        // core 0.1.17 keys the callback registry through AgentCallbackManager
        // ("<agentId>_AgentCallbackEvent.<EVENT>"); the old "<cardId>_<eventValue>" literal
        // matches nothing, so the lookup must go through the manager.
        return agent.getAgent().getAgentCallbackManager().getAgentEvent(AgentCallbackEvent.BEFORE_TOOL_CALL);
    }

    private static void fire(DeepAgent agent, String name) {
        ToolCallInputs inputs = new ToolCallInputs();
        inputs.setToolName(name);
        AgentCallbackContext.builder().agent(agent.getAgent())
                .inputs(inputs).build()
                .fire(AgentCallbackEvent.BEFORE_TOOL_CALL);
    }

    private static ServeRequest request(String id) {
        ServeRequest request = new ServeRequest();
        request.setConversationId(id);
        return request;
    }

    private static final class CountingRail extends AgentRail {
        private int uninits;
        private int calls;

        @Override
        public void beforeToolCall(AgentCallbackContext context) {
            if (context.getInputs() instanceof ToolCallInputs input && "search".equals(input.getToolName())) {
                calls++;
            }
        }

        @Override
        public void uninit(Object agent) {
            uninits++;
        }
    }
}

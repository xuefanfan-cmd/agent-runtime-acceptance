/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.feat056;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.openjiuwen.agents.toolguardrail.ToolGuardrailOptions;
import com.openjiuwen.agents.toolguardrail.ToolGuardrailPriorities;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.rails.DeepAgentRail;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;
import com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreAgentExtHandler;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.AgentFactory;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.AgentInstanceManager;
import com.openjiuwen.service.adapters.agentcore.ext.extension.AgentInstanceCustomizer;
import com.openjiuwen.service.adapters.agentcore.ext.guardrail.GuardrailDeepAgentInstaller;
import com.openjiuwen.service.spec.dto.ServeRequest;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FEAT-056-C45: request-phase guardrail installation failure.
 *
 * <p>L2 C11 / FEAT §4「请求期护栏失败」：对象初始化成功后，既有护栏在请求期安装点实际抛错时，
 * 该请求失败、不降级为无护栏执行。安装器自身的失败传播契约见
 * GuardrailDeepAgentInstaller（"Installation failure propagates"）；handler.query/streamQuery
 * 在 installBeforeRun 处不捕获异常（源码 354/378 行），因此 installBeforeRun 抛错即请求失败。
 * 保留区自动检查只发生在护栏安装点、只读外层注册清单（L2 §2.3），所以自定义器在外层注册
 * 越界 Rail 不会在注册时被拒——这是本用例能真实走到"安装抛错"的原因。</p>
 *
 * @since 0.1.2
 */
@Tag("component")
@Tag("feat-056")
@Feature("FEAT-056: 业务 Agent（Lib JAR）二次开发扩展点")
class AgentInstanceCustomizerGuardrailFailureTest {
    @TempDir
    Path workspace;

    @Test
    @Story("FEAT-056-C45: 请求期护栏安装失败 → 请求失败、不降级、业务工具零执行")
    void requestPhaseGuardrailInstallFailureFailsWithoutDowngrade() throws Exception {
        AtomicInteger toolHits = new AtomicInteger();
        AgentInstanceManager manager = new AgentInstanceManager(agentFactory());
        ExposedHandler handler = new ExposedHandler(new Object());
        handler.setAgentManager(manager);
        setGuardrailInstaller(handler, new GuardrailDeepAgentInstaller(options()));
        handler.configureInstanceCustomization("feat056-agent", List.of((id, actual) -> {
            DeepAgent agent = assertInstanceOf(DeepAgent.class, actual);
            // 扩展方违规形态：外层注册进护栏保留区。注册本身不被拒绝（自动检查只在安装点）。
            DeepAgentRail violating = new DeepAgentRail() { };
            violating.setPriority(ToolGuardrailPriorities.RESERVED_FLOOR + 10);
            agent.registerRail(violating).join();
            // 业务工具：请求失败时必须零执行，且对象不被安装失败破坏。
            agent.registerHarnessTool(tool("canary-" + agent.getCard().getId(), "search",
                    () -> toolHits.incrementAndGet()));
        }));
        handler.start();
        var token = handler.prepareTask(request("guardrail-fail-task"));
        DeepAgent agent = assertInstanceOf(DeepAgent.class, manager.get("guardrail-fail-task"));
        try {
            assertThat(agent.isInitialized()).as("对象初始化成功，失败发生在请求期安装点").isTrue();
            // 请求期安装点：安装实际抛错且向上传播（= 请求失败，不降级）。
            assertThatThrownBy(() -> handler.installNow(agent))
                    .as("保留区冲突必须在护栏安装点失败并传播为请求失败")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("reserved band");
            // 安装失败不回滚、不重置对象状态（产品行为：三条 Rail 先注册、wiring 断言后执行）；
            // 设计承诺是"该请求失败、不降级"，由上方 installBeforeRun 异常传播证明。
            // 本次请求没有执行任何业务工具；对象自身的扩展能力保持完好（可直接解析执行）。
            assertThat(toolHits).as("请求失败的请求内业务工具零执行").hasValue(0);
            assertThat(Runner.resourceMgr().getTool("canary-" + agent.getCard().getId()))
                    .as("安装失败不破坏对象自身的扩展")
                    .isNotNull();
        } finally {
            handler.completeTask(token);
            handler.stop();
        }
    }

    /**
     * Exposes the protected request-phase install hook; {@code query}/{@code streamQuery}
     * call it without catching, so a throw here is exactly "the request fails".
     */
    private static final class ExposedHandler extends JiuwenCoreAgentExtHandler {
        ExposedHandler(Object root) {
            super(root);
        }

        void installNow(Object agent) {
            installBeforeRun(agent);
        }
    }

    private static void setGuardrailInstaller(JiuwenCoreAgentExtHandler handler,
            GuardrailDeepAgentInstaller installer) throws Exception {
        Method setter = JiuwenCoreAgentExtHandler.class
                .getDeclaredMethod("setGuardrailDeepAgentInstaller", GuardrailDeepAgentInstaller.class);
        setter.setAccessible(true);
        setter.invoke(handler, installer);
    }

    private AgentFactory agentFactory() {
        return new AgentFactory() {
            @Override
            public Object create() {
                String id = UUID.randomUUID().toString();
                return new DeepAgent(AgentCard.builder().id(id).name("CustomizerGuardrailFailureTest").build(),
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

    private static LocalFunction tool(String id, String name, Runnable handler_) {
        return new LocalFunction(ToolCard.builder().id(id).name(name).description("test tool")
                .inputParams(Map.of("type", "object", "properties", Map.of())).build(),
                inputs -> {
                    handler_.run();
                    return "tool-result";
                });
    }

    private static ServeRequest request(String id) {
        ServeRequest request = new ServeRequest();
        request.setConversationId(id);
        return request;
    }

    private static ToolGuardrailOptions options() {
        return ToolGuardrailOptions.builder(com.openjiuwen.agents.toolguardrail.config.ToolGuardrailConfig
                .builder().enabled(true).build()).build();
    }
}

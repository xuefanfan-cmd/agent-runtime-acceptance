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
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;
import com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreAgentExtHandler;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.AgentFactory;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.AgentInstanceManager;
import com.openjiuwen.service.adapters.agentcore.ext.extension.AgentInstanceCustomizer;
import com.openjiuwen.service.spec.dto.ServeRequest;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * FEAT-056-C46/C47: Tool 身份与同名/同资源 ID 冲突行为。
 *
 * <p>C46 对应 FEAT §2「Tool 定制」MUST（不替换已有同名 Tool）与 AC-12a「不隐式覆盖同名 Tool」；
 * C47 对应 Core 资源身份/归属契约（DFX-005 复用路径）。判定依据：</p>
 * <ul>
 * <li>ResourceMgr 按 {@code ToolCard.id} 键控资源，同名不同 id 的注册各自独立；</li>
 * <li>AbilityManager 按 {@code name} 键控模型可见能力：同名不同 id 的 add 返回
 * {@code duplicate_tool}、不替换原条目（AbilityManager#addToolCard），即原工具不被顶掉；</li>
 * <li>同 id 不一致声明在 ResourceMgr 上冲突失败（fail-fast），原工具仍可解析执行。</li>
 * </ul>
 *
 * @since 0.1.2
 */
@Tag("component")
@Tag("feat-056")
@Feature("FEAT-056: 业务 Agent（Lib JAR）二次开发扩展点")
class AgentInstanceCustomizerToolIdentityTest {
    @TempDir
    Path workspace;

    @Test
    @Story("FEAT-056-C46: 同一 Agent 新增同名 Tool 不替换原 Tool")
    void sameNameToolDoesNotReplaceOriginalTool() throws Exception {
        String originalId = "orig-" + UUID.randomUUID();
        String addedId = "added-" + UUID.randomUUID();
        AgentInstanceManager manager = new AgentInstanceManager(agentFactory());
        JiuwenCoreAgentExtHandler handler = new JiuwenCoreAgentExtHandler(new Object());
        handler.setAgentManager(manager);
        handler.configureInstanceCustomization("feat056-agent", List.of((id, actual) -> {
            DeepAgent agent = assertInstanceOf(DeepAgent.class, actual);
            agent.registerHarnessTool(tool(originalId, "search", "original-canary"));
        }));
        handler.start();
        var token = handler.prepareTask(request("same-name-task"));
        DeepAgent agent = assertInstanceOf(DeepAgent.class, manager.get("same-name-task"));
        try {
            // 对象初始化成功后新增同名 Tool（不同资源 id）；注册本身允许成功或被拒，
            // 被测承诺是"原工具不被替换"。
            try {
                agent.registerHarnessTool(tool(addedId, "search", "added-canary"));
            } catch (RuntimeException ex) {
                // 若产品选择在注册期直接拒绝同名，同样满足"不替换"，记录即可。
            }
            // 模型可见名字表仍指向原卡：不替换的唯一定位证据。
            Optional<Object> named = agent.getAgent().getAbilityManager().get("search");
            assertThat(named).as("同名注册后 search 能力仍存在").isPresent();
            ToolCard namedCard = assertInstanceOf(ToolCard.class, named.get());
            assertThat(namedCard.getId())
                    .as("search 名字必须仍解析到原工具，不能被同名新增顶掉")
                    .isEqualTo(originalId);
            // 原工具仍按原声明解析执行。
            assertThat(call(originalId)).isEqualTo("original-canary");
            // 名字未串用：若新工具成功成为资源，它只能以自己的 id 解析。
            if (Runner.resourceMgr().getTool(addedId) != null) {
                assertThat(call(addedId)).isEqualTo("added-canary");
            }
            // 原工具的身份与归属未变：释放语义仍由原所有者承担。
            assertThat(Runner.resourceMgr().getTool(originalId)).isNotNull();
        } finally {
            handler.completeTask(token);
            handler.stop();
        }
    }

    @Test
    @Story("FEAT-056-C47: 同资源 ID 声明不一致注册失败且原工具仍可用")
    void conflictingResourceIdRegistrationFailsAndOriginalRemainsUsable() throws Exception {
        String sharedId = "shared-" + UUID.randomUUID();
        AgentInstanceManager manager = new AgentInstanceManager(agentFactory());
        JiuwenCoreAgentExtHandler handler = new JiuwenCoreAgentExtHandler(new Object());
        handler.setAgentManager(manager);
        handler.configureInstanceCustomization("feat056-agent", List.of((id, actual) -> {
            DeepAgent agent = assertInstanceOf(DeepAgent.class, actual);
            agent.registerHarnessTool(tool(sharedId, "audit_tool", "original-canary"));
        }));
        handler.start();
        var token = handler.prepareTask(request("resource-conflict-task"));
        DeepAgent agent = assertInstanceOf(DeepAgent.class, manager.get("resource-conflict-task"));
        try {
            // 同 id、不一致声明（不同处理器）：注册失败且失败可见。
            assertThatThrownBy(() -> agent.registerHarnessTool(tool(sharedId, "conflicting_name", "other")))
                    .as("同资源 ID 冲突声明必须 fail-fast，不能静默覆盖")
                    .isInstanceOf(RuntimeException.class);
            // 失败后原工具仍按原声明解析执行。
            assertThat(call(sharedId)).isEqualTo("original-canary");
            // 名字表未被冲突注册改写。
            Optional<Object> named = agent.getAgent().getAbilityManager().get("audit_tool");
            assertThat(named).isPresent();
            assertThat(assertInstanceOf(ToolCard.class, named.get()).getId()).isEqualTo(sharedId);
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
                return new DeepAgent(AgentCard.builder().id(id).name("CustomizerToolIdentityTest").build(),
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

    private static LocalFunction tool(String id, String name, String result) {
        return new LocalFunction(ToolCard.builder().id(id).name(name).description("test tool")
                .inputParams(Map.of("type", "object", "properties", Map.of())).build(), inputs -> result);
    }

    private static Object call(String id) throws Exception {
        return assertInstanceOf(Tool.class, Runner.resourceMgr().getTool(id)).invoke(Map.of(), Map.of());
    }

    private static ServeRequest request(String id) {
        ServeRequest request = new ServeRequest();
        request.setConversationId(id);
        return request;
    }
}

/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.feat056;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.singleagent.BaseAgent;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentCallbackEvent;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;
import com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreAgentExtHandler;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.AgentFactory;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.AgentInstanceManager;
import com.openjiuwen.service.adapters.agentcore.ext.extension.AgentInstanceCustomizer;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.hosting.HostedAgentDefinitions;
import com.openjiuwen.service.spec.spi.AgentHandler;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * FEAT-056 反证控制（mutation controls）：逐行为族证明"撤掉目标行为后主断言必须失败"。
 *
 * <p>对应 FEAT-031 教训 031-02/031-04：断言不得挂在中间证据上——把扩展/注册/清理撤掉后，
 * 基础用例的主判据必须不再成立。每条用例都成对给出：正向（判据成立）与 mutant（判据不成立）。
 *
 * @since 0.1.2
 */
@Tag("component")
@Tag("feat-056")
@Feature("FEAT-056: 业务 Agent（Lib JAR）二次开发扩展点")
class AgentInstanceCustomizerMutationControlTest {
    @TempDir
    Path workspace;

    @Test
    @Story("FEAT-056-C40: 反证——撤掉自定义器注册后“自定义器调用一次”的判据必须失败")
    void customizerCallOracleFailsWhenNoCustomizerIsRegistered() {
        assertThat(customizerCallsAfterAcquire(List.of(oneCountingCustomizer()))).isEqualTo(1);
        assertThat(customizerCallsAfterAcquire(List.of())).isZero();
    }

    @Test
    @Story("FEAT-056-C41: 反证——撤掉 Rail 注册后“Rail 回调触发”的判据必须失败")
    void railCanaryOracleFailsWhenRailIsNotRegistered() {
        assertThat(railHitsAfterInvoke(true)).isPositive();
        assertThat(railHitsAfterInvoke(false)).isZero();
    }

    @Test
    @Story("FEAT-056-C42: 反证——撤掉 Tool 注册后“新增 Tool 可解析”的判据必须失败")
    void toolRegistrationOracleFailsWhenToolIsNotRegistered() {
        assertThat(registeredToolCount(true)).isEqualTo(1);
        assertThat(registeredToolCount(false)).isZero();
    }

    @Test
    @Story("FEAT-056-C43: 反证——托管成员注册 ID 串用时“各自注册名”的判据必须失败")
    void hostedEntryIdOracleFailsWhenMembersShareOneRegistrationId() {
        assertThat(hostedEntryIds("agent-a", "agent-b")).containsExactly("agent-a", "agent-b");
        // 同一注册 ID 登记两个成员：产品直接拒绝（Duplicate hosted agent ID），
        // 于是"两个成员各自拿到互异注册名"的判据必然不成立。
        assertThatThrownBy(() -> hostedEntryIds("agent-a", "agent-a"))
                .as("两条注册名相同的登记必须使“各自注册名互异”的判据不成立")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate hosted agent ID");
    }

    @Test
    @Story("FEAT-056-C44: 反证——撤掉销毁调用后“失败对象清理一次”的判据必须失败")
    void destroyOracleFailsWhenCleanupIsNotInvoked() {
        assertThat(destroyCallsAfterFailedInit(true)).isEqualTo(1);
        assertThat(destroyCallsAfterFailedInit(false)).isZero();
    }

    private int customizerCallsAfterAcquire(List<AgentInstanceCustomizer> customizers) {
        AtomicInteger calls = new AtomicInteger();
        List<AgentInstanceCustomizer> effective = customizers.isEmpty() ? List.of()
                : List.of((agentId, actual) -> calls.incrementAndGet());
        AgentInstanceManager manager = new AgentInstanceManager(factory());
        JiuwenCoreAgentExtHandler handler = new JiuwenCoreAgentExtHandler(new ProbeAgent("initial"));
        handler.setAgentManager(manager);
        handler.configureInstanceCustomization("feat056-agent", effective);
        handler.start();
        Object agent = manager.acquire("feat056-c40");
        manager.release("feat056-c40", agent);
        return calls.get();
    }

    private int railHitsAfterInvoke(boolean registerRail) {
        AtomicInteger railHits = new AtomicInteger();
        ProbeAgent agent = new ProbeAgent("rail-probe");
        RecordingHandler handler = new RecordingHandler(agent);
        handler.configureInstanceCustomization("feat056-agent", List.of((agentId, actual) -> {
            if (registerRail) {
                registerRailIfSupported(actual, new AgentRail() {
                    @Override
                    public void beforeInvoke(AgentCallbackContext ctx) {
                        railHits.incrementAndGet();
                    }
                });
            }
        }));
        handler.start();
        handler.query(request("feat056-c41"));
        handler.stop();
        return railHits.get();
    }

    private int registeredToolCount(boolean registerTool) {
        DeepAgent agent = deepAgent();
        if (registerTool) {
            agent.registerHarnessTool(new LocalFunction(
                    ToolCard.builder().id("feat056-c42-tool").name("feat056_c42_tool")
                            .description("mutation control tool").build(),
                    inputs -> Map.of("ok", true)));
        }
        return agent.getRegisteredTools().size();
    }

    private List<String> hostedEntryIds(String firstId, String secondId) {
        AgentHandler first = new JiuwenCoreAgentExtHandler(new ProbeAgent("hosted-first"));
        AgentHandler second = new JiuwenCoreAgentExtHandler(new ProbeAgent("hosted-second"));
        HostedAgentDefinitions definitions = HostedAgentDefinitions.builder()
                .add(firstId, first)
                .add(secondId, second)
                .build();
        List<String> ids = new ArrayList<>();
        definitions.entries().forEach(entry -> ids.add(entry.agentId()));
        if (ids.size() != new java.util.HashSet<>(ids).size()) {
            throw new IllegalStateException("duplicate hosted registration ids: " + ids);
        }
        return ids;
    }

    private int destroyCallsAfterFailedInit(boolean cleanupEnabled) {
        AtomicInteger destroys = new AtomicInteger();
        AgentFactory factory = new AgentFactory() {
            @Override
            public Object create() {
                return new ProbeAgent("c44-task");
            }

            @Override
            public void destroy(Object agent) {
                if (cleanupEnabled) {
                    destroys.incrementAndGet();
                }
            }
        };
        AgentInstanceManager manager = new AgentInstanceManager(factory);
        JiuwenCoreAgentExtHandler handler = new JiuwenCoreAgentExtHandler(new ProbeAgent("initial"));
        handler.setAgentManager(manager);
        handler.configureInstanceCustomization("feat056-agent", List.of((agentId, actual) -> {
            throw new IllegalStateException("feat056-c44-injected-failure");
        }));
        handler.start();
        try {
            manager.acquire("feat056-c44");
        } catch (IllegalStateException expected) {
            // 预期失败；本用例只关心清理是否被调用。
        }
        return destroys.get();
    }

    private static AgentInstanceCustomizer oneCountingCustomizer() {
        return (agentId, actual) -> {
        };
    }

    private AgentFactory factory() {
        return new AgentFactory() {
            private final AtomicInteger created = new AtomicInteger();

            @Override
            public Object create() {
                return new ProbeAgent("task-" + created.incrementAndGet());
            }

            @Override
            public void destroy(Object agent) {
                // 本探针不持有资源。
            }
        };
    }

    private DeepAgent deepAgent() {
        String id = "feat056-c42-" + System.nanoTime();
        return new DeepAgent(AgentCard.builder().id(id).name("MutationControl").build(),
                DeepAgentConfig.builder().enableTaskLoop(false)
                        .backend(Map.of("provider", "OpenAI", "api_key", "test-placeholder",
                                "api_base", "https://example.invalid"))
                        .model(Map.of("model", "unused-local-model")).build(),
                Workspace.builder().rootPath(workspace.resolve(id).toString()).build());
    }

    private static ServeRequest request(String conversationId) {
        ServeRequest request = new ServeRequest();
        request.setConversationId(conversationId);
        return request;
    }

    /**
     * 只在对象确实是 BaseAgent 时注册 Rail，避免未经 instanceof 的向下转型。
     *
     * @param actual 自定义器收到的实际对象
     * @param rail 需要注册到对象上的 Rail
     */
    private static void registerRailIfSupported(Object actual, AgentRail rail) {
        if (actual instanceof BaseAgent baseAgent) {
            baseAgent.registerRail(rail);
        }
    }
    private static final class ProbeAgent extends BaseAgent {
        private ProbeAgent(String id) {
            super(AgentCard.builder().id(id).name(id).description("FEAT-056 mutation probe").build());
        }

        @Override
        public BaseAgent configure(Object config) {
            return this;
        }

        @Override
        public Object getConfig() {
            return Map.of();
        }

        @Override
        public Object invoke(Object inputs, Session session) {
            return Map.of("content", "ok");
        }

        @Override
        public Iterator<Object> stream(Object inputs, Session session, List<StreamMode> streamModes) {
            return List.of().iterator();
        }
    }

    private static final class RecordingHandler extends JiuwenCoreAgentExtHandler {
        private final ProbeAgent agent;

        private RecordingHandler(ProbeAgent agent) {
            super(agent);
            this.agent = agent;
        }

        @Override
        protected void installBeforeRun(Object target) {
            // 本控制用例不需要请求期安装器。
        }

        @Override
        protected Object executeAgent(Map<String, Object> inputs, Object session) {
            agent.fireCallbackEvent(AgentCallbackEvent.BEFORE_INVOKE,
                    AgentCallbackContext.builder().agent(agent).build());
            return Map.of("content", "ok");
        }

        @Override
        protected Iterator<Object> executeAgentStreaming(Map<String, Object> inputs, Object session,
                List<StreamMode> streamModes) {
            agent.fireCallbackEvent(AgentCallbackEvent.BEFORE_INVOKE,
                    AgentCallbackContext.builder().agent(agent).build());
            return List.<Object>of(new OutputSchema("llm_output", 0, Map.of("content", "ok"))).iterator();
        }
    }
}

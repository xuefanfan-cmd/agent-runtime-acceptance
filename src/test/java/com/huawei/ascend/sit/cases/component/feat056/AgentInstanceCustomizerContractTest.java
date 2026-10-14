/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.feat056;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.singleagent.BaseAgent;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentCallbackEvent;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreAgentExtHandler;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.AgentFactory;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.AgentInstanceManager;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.ConversationBusyException;
import com.openjiuwen.service.adapters.agentcore.ext.extension.AgentInstanceCustomizer;
import com.openjiuwen.service.spec.dto.ServeRequest;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * FEAT-056 representative component paths.
 *
 * <p>The test uses the real Core callback chain and the real Runtime-ext
 * customization classes. It does not mock an LLM and does not reimplement the
 * extension mechanism.
 */
@Tag("component")
@Tag("feat-056")
@Feature("FEAT-056: 业务 Agent（Lib JAR）二次开发扩展点")
class AgentInstanceCustomizerContractTest {
    @Test
    @Story("FEAT-056-C09: singleton 外部 Rail 注册一次并持续触发")
    void externalRailIsRegisteredOnceAndFiresOnRepeatedRequests() {
        CanaryAgent agent = new CanaryAgent("agent-a");
        AtomicInteger customizerCalls = new AtomicInteger();
        AtomicInteger railHits = new AtomicInteger();
        RecordingHandler handler = new RecordingHandler(agent);
        handler.configureInstanceCustomization("feat056-agent", List.of((agentId, actualAgent) -> {
            customizerCalls.incrementAndGet();
            assertThat(agentId).isEqualTo("feat056-agent");
            assertThat(actualAgent).isSameAs(agent);
            ((BaseAgent) actualAgent).registerRail(canaryRail(railHits));
        }));

        handler.start();
        handler.query(request("task-1"));
        handler.query(request("task-2"));
        handler.stop();

        assertThat(customizerCalls).hasValue(1);
        assertThat(railHits).hasValue(2);
    }

    @Test
    @Story("FEAT-056-C25: 撤掉外部 Rail 注册后主断言不可能满足")
    void missingRegistrationCannotSatisfyTheRailCanaryOracle() {
        ScenarioResult withoutRail = runScenario(false);
        ScenarioResult withRail = runScenario(true);

        assertThat(withoutRail.customizerCalls()).isEqualTo(1);
        assertThat(withoutRail.railHits()).isZero();
        assertThat(withRail.customizerCalls()).isEqualTo(1);
        assertThat(withRail.railHits()).isEqualTo(2);
    }

    @Test
    @Story("FEAT-056-C10: per-task 对象提交前初始化")
    void perTaskObjectsAreInitializedBeforePublication() {
        AtomicInteger creates = new AtomicInteger();
        AtomicInteger destroys = new AtomicInteger();
        List<Object> initialized = new ArrayList<>();
        AgentFactory factory = new AgentFactory() {
            @Override
            public Object create() {
                return new CanaryAgent("task-agent-" + creates.incrementAndGet());
            }

            @Override
            public void destroy(Object agent) {
                destroys.incrementAndGet();
            }
        };
        AgentInstanceManager manager = new AgentInstanceManager(factory);
        manager.setAgentInitializer(initialized::add);

        Object first = manager.acquire("task-a");
        Object second = manager.acquire("task-b");

        assertThat(initialized).containsExactly(first, second);
        assertThat(creates).hasValue(2);
        manager.release("task-a", first);
        assertThat(destroys).hasValue(1);
        manager.release("task-b", second);
        assertThat(destroys).hasValue(2);
    }

    @Test
    @Story("FEAT-056-C19: 自定义器失败清理未交付对象")
    void failedCustomizationStopsTheChainAndDestroysTheUnpublishedObject() {
        AtomicInteger creates = new AtomicInteger();
        AtomicInteger destroys = new AtomicInteger();
        AgentFactory factory = new AgentFactory() {
            @Override
            public Object create() {
                return new CanaryAgent("failed-agent-" + creates.incrementAndGet());
            }

            @Override
            public void destroy(Object agent) {
                destroys.incrementAndGet();
            }
        };
        AgentInstanceManager manager = new AgentInstanceManager(factory);
        manager.setAgentInitializer(agent -> {
            throw new IllegalArgumentException("customizer failed");
        });

        assertThatThrownBy(() -> manager.acquire("task-failure"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("customizer failed");
        assertThat(creates).hasValue(1);
        assertThat(destroys).hasValue(1);
        assertThat(manager.get("task-failure")).isNull();
    }

    @Test
    @Story("FEAT-056-C04: Spring Ordered 自定义器按顺序收集并只初始化一次")
    void orderedCustomizersRunInOrderAndInitializeTheSingletonOnce() {
        new ApplicationContextRunner()
                .withUserConfiguration(OrderedCustomizerConfiguration.class)
                .withPropertyValues("openjiuwen.service.agent-id=feat056-agent")
                .run(context -> {
                    RecordingHandler handler = context.getBean(RecordingHandler.class);
                    OrderProbe probe = context.getBean(OrderProbe.class);

                    handler.start();
                    handler.query(request("ordered-task"));
                    handler.stop();

                    assertThat(probe.calls()).containsExactly("first", "second");
                    assertThat(probe.railHits()).hasValue(1);
                });
    }

    @Test
    @Story("FEAT-056-C05: 前置自定义器失败后不调用后续自定义器")
    void laterCustomizerIsNotCalledWhenEarlierCustomizerFails() {
        new ApplicationContextRunner()
                .withUserConfiguration(FailingCustomizerConfiguration.class)
                .withPropertyValues("openjiuwen.service.agent-id=feat056-agent")
                .run(context -> {
                    RecordingHandler handler = context.getBean(RecordingHandler.class);
                    FailureProbe probe = context.getBean(FailureProbe.class);

                    assertThatThrownBy(handler::start)
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("first customizer failed");
                    handler.stop();

                    assertThat(probe.firstCalls()).hasValue(1);
                    assertThat(probe.laterCalls()).hasValue(0);
                });
    }

    @Test
    @Story("FEAT-056-C27: per-task 部分注册失败后调用原 destroy 并清除 Rail")
    void failedPerTaskCustomizationDestroysTheObjectAndRemovesThePartialRail() {
        AtomicInteger creates = new AtomicInteger();
        AtomicInteger destroys = new AtomicInteger();
        AtomicInteger partialRailHits = new AtomicInteger();
        AtomicReference<CanaryAgent> failedAgent = new AtomicReference<>();
        AgentFactory factory = new AgentFactory() {
            @Override
            public Object create() {
                CanaryAgent agent = new CanaryAgent("partial-failure-" + creates.incrementAndGet());
                failedAgent.set(agent);
                return agent;
            }

            @Override
            public void destroy(Object agent) {
                destroys.incrementAndGet();
                CanaryAgent canaryAgent = (CanaryAgent) agent;
                canaryAgent.getAgentCallbackManager().unregisterAllRails(canaryAgent);
            }
        };
        AgentInstanceManager manager = new AgentInstanceManager(factory);
        RecordingHandler handler = new RecordingHandler(new CanaryAgent("initial-agent"));
        handler.setAgentManager(manager);
        handler.configureInstanceCustomization("feat056-agent", List.of(
                (agentId, actualAgent) -> ((BaseAgent) actualAgent).registerRail(canaryRail(partialRailHits)),
                (agentId, actualAgent) -> {
                    throw new IllegalArgumentException("second customizer failed");
                }));

        handler.start();
        assertThatThrownBy(() -> handler.prepareTask(request("partial-failure-task")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("second customizer failed");
        handler.stop();

        assertThat(creates).hasValue(1);
        assertThat(destroys).hasValue(1);
        failedAgent.get().fireCallbackEvent(AgentCallbackEvent.BEFORE_INVOKE,
                AgentCallbackContext.builder().agent(failedAgent.get()).build());
        assertThat(partialRailHits).hasValue(0);
        assertThat(manager.get("partial-failure-task")).isNull();
    }

    @Test
    @Story("FEAT-056-C03: 零自定义器不要求业务 ID")
    void zeroCustomizersDoNotRequireBusinessAgentId() {
        CanaryAgent agent = new CanaryAgent("no-customizer-agent");
        RecordingHandler handler = new RecordingHandler(agent);

        handler.start();
        handler.query(request("no-customizer-task"));
        handler.stop();
    }

    @Test
    @Story("FEAT-056-C06: 请求 metadata 不能覆盖业务 Agent ID")
    void requestMetadataCannotOverrideBusinessAgentId() {
        CanaryAgent agent = new CanaryAgent("metadata-agent");
        AtomicReference<String> observedId = new AtomicReference<>();
        RecordingHandler handler = new RecordingHandler(agent);
        handler.configureInstanceCustomization("feat056-agent",
                List.of((agentId, actualAgent) -> observedId.set(agentId)));

        ServeRequest request = request("metadata-task");
        request.setMetadata(Map.of("agentId", "request-supplied-id", "conversationId", "request-supplied-conversation"));
        handler.start();
        handler.query(request);
        handler.stop();

        assertThat(observedId).hasValue("feat056-agent");
    }

    @Test
    @Story("FEAT-056-C16: 不支持类型在业务回调前失败")
    void unsupportedAgentFailsBeforeAnyCustomizerRuns() {
        AtomicInteger customizerCalls = new AtomicInteger();
        JiuwenCoreAgentExtHandler handler = new JiuwenCoreAgentExtHandler(new Object());
        handler.configureInstanceCustomization("feat056-agent",
                List.of((agentId, actualAgent) -> customizerCalls.incrementAndGet()));

        assertThatThrownBy(handler::start)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feat056-agent")
                .hasMessageContaining("java.lang.Object");
        handler.stop();

        assertThat(customizerCalls).hasValue(0);
    }

    @Test
    @Story("FEAT-056-C17: singleton 启动失败后入口拒绝执行且不重试")
    void singletonFailureRejectsExecutionAndDoesNotRetryInitialization() {
        CanaryAgent agent = new CanaryAgent("failing-singleton");
        AtomicInteger customizerCalls = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("singleton initialization failed");
        RecordingHandler handler = new RecordingHandler(agent);
        handler.configureInstanceCustomization("feat056-agent", List.of((agentId, actualAgent) -> {
            customizerCalls.incrementAndGet();
            throw failure;
        }));

        assertThatThrownBy(handler::start).isSameAs(failure);
        assertThatThrownBy(() -> handler.query(request("singleton-failure-task")))
                .hasCauseReference(failure);
        handler.stop();

        assertThat(customizerCalls).hasValue(1);
    }

    @Test
    @Story("FEAT-056-C20: 清理失败不覆盖初始化主错误")
    void cleanupFailureDoesNotOverrideThePrimaryInitializationFailure() {
        IllegalStateException primary = new IllegalStateException("primary initialization failure");
        IllegalArgumentException cleanup = new IllegalArgumentException("cleanup failure");
        AtomicInteger destroys = new AtomicInteger();
        AgentFactory factory = new AgentFactory() {
            @Override
            public Object create() {
                return new CanaryAgent("cleanup-failure-agent");
            }

            @Override
            public void destroy(Object agent) {
                destroys.incrementAndGet();
                throw cleanup;
            }
        };
        AgentInstanceManager manager = new AgentInstanceManager(factory);
        manager.setAgentInitializer(agent -> {
            throw primary;
        });

        assertThatThrownBy(() -> manager.acquire("cleanup-failure-task"))
                .isSameAs(primary)
                .hasSuppressedException(cleanup);
        assertThat(destroys).hasValue(1);
        assertThat(manager.get("cleanup-failure-task")).isNull();
    }

    @Test
    @Story("FEAT-056-C29: 同 conversation 并发首建只允许一个")
    void concurrentFirstAcquireRejectsTheSameConversationAndAllowsAnother() throws Exception {
        CountDownLatch enteredInitializer = new CountDownLatch(1);
        CountDownLatch releaseInitializer = new CountDownLatch(1);
        AtomicReference<CanaryAgent> firstAgent = new AtomicReference<>();
        AgentFactory factory = new AgentFactory() {
            @Override
            public Object create() {
                CanaryAgent agent = new CanaryAgent("concurrent-agent-" + System.nanoTime());
                firstAgent.compareAndSet(null, agent);
                return agent;
            }

            @Override
            public void destroy(Object agent) {
                // No resources in this probe.
            }
        };
        AgentInstanceManager manager = new AgentInstanceManager(factory);
        manager.setAgentInitializer(agent -> {
            if (agent == firstAgent.get()) {
                enteredInitializer.countDown();
                try {
                    releaseInitializer.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interruption) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interruption);
                }
            }
        });
        ExecutorService executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1));
        try {
            var firstAcquire = executor.submit(() -> manager.acquire("same-conversation"));
            assertThat(enteredInitializer.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> manager.acquire("same-conversation"))
                    .isInstanceOf(ConversationBusyException.class);
            Object other = manager.acquire("other-conversation");
            assertThat(other).isNotNull();
            releaseInitializer.countDown();
            assertThat(firstAcquire.get(5, TimeUnit.SECONDS)).isSameAs(firstAgent.get());
            manager.release("same-conversation", firstAgent.get());
            manager.release("other-conversation", other);
        } finally {
            releaseInitializer.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @Story("FEAT-056-C24: 合规 AgentFactory 保持过渡期串行创建")
    void compliantAgentFactorySerializesTransitionPeriodCreation() throws Exception {
        AtomicInteger activeCreates = new AtomicInteger();
        AtomicInteger maxActiveCreates = new AtomicInteger();
        ReentrantLock creationLock = new ReentrantLock();
        AgentFactory factory = new AgentFactory() {
            @Override
            public Object create() {
                creationLock.lock();
                try {
                    int active = activeCreates.incrementAndGet();
                    maxActiveCreates.accumulateAndGet(active, Math::max);
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException interruption) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interruption);
                    }
                    return new CanaryAgent("serialized-agent-" + System.nanoTime());
                } finally {
                    activeCreates.decrementAndGet();
                    creationLock.unlock();
                }
            }

            @Override
            public void destroy(Object agent) {
                // No resources in this probe.
            }
        };
        AgentInstanceManager manager = new AgentInstanceManager(factory);
        manager.setAgentInitializer(agent -> {
        });
        ExecutorService executor = new java.util.concurrent.ThreadPoolExecutor(2, 2, 0L,
                java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>());
        try {
            var first = executor.submit(() -> manager.acquire("serialized-a"));
            var second = executor.submit(() -> manager.acquire("serialized-b"));
            Object firstAgent = first.get(5, TimeUnit.SECONDS);
            Object secondAgent = second.get(5, TimeUnit.SECONDS);
            assertThat(maxActiveCreates).hasValue(1);
            manager.release("serialized-a", firstAgent);
            manager.release("serialized-b", secondAgent);
        } finally {
            executor.shutdownNow();
        }
    }

    private ScenarioResult runScenario(boolean registerRail) {
        CanaryAgent agent = new CanaryAgent("agent-a");
        AtomicInteger customizerCalls = new AtomicInteger();
        AtomicInteger railHits = new AtomicInteger();
        RecordingHandler handler = new RecordingHandler(agent);
        handler.configureInstanceCustomization("feat056-agent", List.of((agentId, actualAgent) -> {
            customizerCalls.incrementAndGet();
            if (registerRail) {
                ((BaseAgent) actualAgent).registerRail(canaryRail(railHits));
            }
        }));

        handler.start();
        handler.query(request("mutation-task-1"));
        handler.query(request("mutation-task-2"));
        handler.stop();
        return new ScenarioResult(customizerCalls.get(), railHits.get());
    }

    private static AgentRail canaryRail(AtomicInteger railHits) {
        return new AgentRail() {
            @Override
            public void beforeInvoke(AgentCallbackContext context) {
                railHits.incrementAndGet();
            }
        };
    }

    private static ServeRequest request(String conversationId) {
        ServeRequest request = new ServeRequest();
        request.setConversationId(conversationId);
        return request;
    }

    private record ScenarioResult(int customizerCalls, int railHits) {
    }

    private static final class RecordingHandler extends JiuwenCoreAgentExtHandler {
        private final CanaryAgent canaryAgent;

        private RecordingHandler(CanaryAgent canaryAgent) {
            super(canaryAgent);
            this.canaryAgent = canaryAgent;
        }

        @Override
        protected void installBeforeRun(Object agent) {
            // No request-time installers are needed for this contract case.
        }

        @Override
        protected Object executeAgent(Map<String, Object> inputs, Object session) {
            canaryAgent.fireCallbackEvent(AgentCallbackEvent.BEFORE_INVOKE,
                    AgentCallbackContext.builder().agent(canaryAgent).build());
            return Map.of("content", "ok");
        }

        @Override
        protected Iterator<Object> executeAgentStreaming(Map<String, Object> inputs, Object session,
                List<StreamMode> streamModes) {
            canaryAgent.fireCallbackEvent(AgentCallbackEvent.BEFORE_INVOKE,
                    AgentCallbackContext.builder().agent(canaryAgent).build());
            return List.<Object>of(new OutputSchema("llm_output", 0, Map.of("content", "ok"))).iterator();
        }
    }

    private static final class CanaryAgent extends BaseAgent {
        private CanaryAgent(String id) {
            super(AgentCard.builder().id(id).name(id).description("FEAT-056 contract agent").build());
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

    private static final class OrderProbe {
        private final AtomicInteger railHits = new AtomicInteger();
        private final List<String> calls = new ArrayList<>();

        private AtomicInteger railHits() {
            return railHits;
        }

        private List<String> calls() {
            return calls;
        }
    }

    private static final class FailureProbe {
        private final AtomicInteger firstCalls = new AtomicInteger();
        private final AtomicInteger laterCalls = new AtomicInteger();

        private AtomicInteger firstCalls() {
            return firstCalls;
        }

        private AtomicInteger laterCalls() {
            return laterCalls;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OrderedCustomizerConfiguration {
        @Bean
        OrderProbe orderProbe() {
            return new OrderProbe();
        }

        @Bean
        CanaryAgent orderedCanaryAgent() {
            return new CanaryAgent("ordered-agent");
        }

        @Bean
        RecordingHandler orderedHandler(CanaryAgent orderedCanaryAgent) {
            return new RecordingHandler(orderedCanaryAgent);
        }

        @Bean
        @Order(1)
        AgentInstanceCustomizer firstOrderedCustomizer(OrderProbe probe) {
            return (agentId, actualAgent) -> {
                probe.calls().add("first");
                ((BaseAgent) actualAgent).registerRail(canaryRail(probe.railHits()));
            };
        }

        @Bean
        @Order(2)
        AgentInstanceCustomizer secondOrderedCustomizer(OrderProbe probe) {
            return (agentId, actualAgent) -> probe.calls().add("second");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FailingCustomizerConfiguration {
        @Bean
        FailureProbe failureProbe() {
            return new FailureProbe();
        }

        @Bean
        CanaryAgent failingCanaryAgent() {
            return new CanaryAgent("failing-agent");
        }

        @Bean
        RecordingHandler failingHandler(CanaryAgent failingCanaryAgent) {
            return new RecordingHandler(failingCanaryAgent);
        }

        @Bean
        @Order(1)
        AgentInstanceCustomizer firstFailingCustomizer(FailureProbe probe) {
            return (agentId, actualAgent) -> {
                probe.firstCalls().incrementAndGet();
                throw new IllegalArgumentException("first customizer failed");
            };
        }

        @Bean
        @Order(2)
        AgentInstanceCustomizer laterFailingCustomizer(FailureProbe probe) {
            return (agentId, actualAgent) -> probe.laterCalls().incrementAndGet();
        }
    }
}

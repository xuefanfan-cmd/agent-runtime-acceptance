/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.feat056;

import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.singleagent.BaseAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.AgentFactory;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.AgentInstanceManager;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-056：按任务对象的初始化并发口径。
 *
 * <p>对应 PR 292 后的 AC-09a：不同 conversation 的任务对象可以并发创建和初始化，
 * Runtime-ext 不额外增加跨对象串行锁。判据用"脚手架栅栏"——只有两个 create()
 * 真正同时进行才可能双双过栅栏；若 manager 把不同对象串行化，先到的那个会超时，
 * 用例直接失败，而不是用"两个请求最后都成功"当并发证据。
 *
 * @since 0.1.2
 */
@Tag("component")
@Tag("feat-056")
@Feature("FEAT-056: 业务 Agent（Lib JAR）二次开发扩展点")
class AgentInstanceCustomizerConcurrentInitTest {

    @Test
    @Story("FEAT-056-C32: 不同 conversation 的任务对象可并发初始化，不加跨对象串行锁")
    void differentConversationsCreateAndInitializeConcurrently() throws Exception {
        CyclicBarrier bothCreatesInsideFactory = new CyclicBarrier(2);
        AtomicInteger activeCreates = new AtomicInteger();
        AtomicInteger maxActiveCreates = new AtomicInteger();
        AtomicInteger nextId = new AtomicInteger();

        AgentFactory factory = new AgentFactory() {
            @Override
            public Object create() {
                int active = activeCreates.incrementAndGet();
                maxActiveCreates.accumulateAndGet(active, Math::max);
                try {
                    // 两个不同任务对象的 create() 必须能同时在工厂里；否则说明存在跨对象串行锁。
                    bothCreatesInsideFactory.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException | java.util.concurrent.BrokenBarrierException
                        | java.util.concurrent.TimeoutException failure) {
                    throw new IllegalStateException(
                            "create() was serialized across different task objects", failure);
                } finally {
                    activeCreates.decrementAndGet();
                }
                return new ProbeAgent("feat056-concurrent-" + nextId.incrementAndGet());
            }

            @Override
            public void destroy(Object agent) {
                // 本探针不持有资源。
            }
        };

        AgentInstanceManager manager = new AgentInstanceManager(factory);
        List<String> customized = Collections.synchronizedList(new ArrayList<>());
        manager.setAgentInitializer(agent -> customized.add(((ProbeAgent) agent).id()));

        ExecutorService executor = new java.util.concurrent.ThreadPoolExecutor(2, 2, 0L,
                java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>());
        try {
            var firstAcquire = executor.submit(() -> manager.acquire("feat056-concurrent-conv-a"));
            var secondAcquire = executor.submit(() -> manager.acquire("feat056-concurrent-conv-b"));

            Object agentA = firstAcquire.get(30, TimeUnit.SECONDS);
            Object agentB = secondAcquire.get(30, TimeUnit.SECONDS);

            assertThat(maxActiveCreates.get())
                    .as("两个不同任务对象必须能同时创建，证明未引入跨对象串行锁")
                    .isEqualTo(2);
            assertThat(agentA).isNotSameAs(agentB);
            assertThat(customized).hasSize(2);
            assertThat(customized).contains(((ProbeAgent) agentA).id(), ((ProbeAgent) agentB).id());

            manager.release("feat056-concurrent-conv-a", agentA);
            manager.release("feat056-concurrent-conv-b", agentB);
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class ProbeAgent extends BaseAgent {
        private final String id;

        private ProbeAgent(String id) {
            super(AgentCard.builder().id(id).name(id).description("FEAT-056 concurrent probe").build());
            this.id = id;
        }

        private String id() {
            return id;
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
}

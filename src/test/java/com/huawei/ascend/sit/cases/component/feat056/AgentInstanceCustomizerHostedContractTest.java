/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.feat056;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.BaseAgent;
import com.openjiuwen.service.adapters.agentcore.agentfw.CoreRunnerLifecycleCoordinator;
import com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreAgentExtHandler;
import com.openjiuwen.service.adapters.agentcore.ext.autoconfigure.AgentCoreExtAutoConfiguration;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.AgentFactory;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.AgentInstanceManager;
import com.openjiuwen.service.adapters.agentcore.ext.extension.AgentInstanceCustomizer;
import com.openjiuwen.service.app.autoconfigure.A2AAutoConfiguration;
import com.openjiuwen.service.app.autoconfigure.HostedRuntimeAutoConfiguration;
import com.openjiuwen.service.app.config.DefaultAgentServiceIdentity;
import com.openjiuwen.service.app.hosting.HostedLifecycleCoordinator;
import com.openjiuwen.service.app.hosting.HostedRuntimeCatalog;
import com.openjiuwen.service.app.lifecycle.ActiveStreamInterruptor;
import com.openjiuwen.service.app.lifecycle.AgentLifecycleHooks;
import com.openjiuwen.service.app.lifecycle.DefaultAgentReadiness;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.hosting.HostedAgentDefinitions;
import com.openjiuwen.service.spec.lifecycle.AgentServiceIdentity;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * FEAT-056 Hosted representative paths.
 */
@Tag("component")
@Tag("feat-056")
@Feature("FEAT-056: 业务 Agent（Lib JAR）二次开发扩展点")
class AgentInstanceCustomizerHostedContractTest {
    private static final String SENSITIVE_LOG_CANARY = "SENSITIVE-LOG-CANARY-056";

    @Test
    @Story("FEAT-056-C07: 单成员托管使用注册名")
    void singleHostedMemberUsesEntryIdInsteadOfGlobalServiceId() {
        try (var ignored = mockStatic(Runner.class)) {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(HostedRuntimeAutoConfiguration.class,
                            A2AAutoConfiguration.class, AgentCoreExtAutoConfiguration.class))
                    .withUserConfiguration(OrderedExtensions.class, SingleHostedHandler.class)
                    .withPropertyValues("spring.application.name=instance-customization")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        HostedLifecycleCoordinator lifecycle = context.getBean(HostedLifecycleCoordinator.class);
                        try {
                            lifecycle.runInitPhase();
                            assertThat(context.getBean(Events.class).entries)
                                    .containsExactly("first:solo", "second:solo");
                        } finally {
                            lifecycle.runShutdownPhase();
                        }
                    });
        }
    }

    @Test
    @Story("FEAT-056-C07: 托管注册名与全局服务 ID 冲突时拒绝启动")
    void hostedDefinitionsRejectGlobalIdOverride() {
        try (var ignored = mockStatic(Runner.class)) {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(HostedRuntimeAutoConfiguration.class,
                            A2AAutoConfiguration.class, AgentCoreExtAutoConfiguration.class))
                    .withUserConfiguration(OrderedExtensions.class, SingleHostedHandler.class)
                    .withPropertyValues("spring.application.name=instance-customization",
                            "openjiuwen.service.agent-id=global-id")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        String expectedConflict = "openjiuwen.service.agent-id conflicts with "
                                + "HostedAgentDefinitions";
                        assertThat(context.getStartupFailure()).hasRootCauseMessage(expectedConflict);
                    });
        }
    }

    @Test
    @Story("FEAT-056-C08: 双成员托管各用注册名且不串用")
    void dualHostedMembersReceiveTheirOwnEntryIds() {
        try (var ignored = mockStatic(Runner.class)) {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(HostedRuntimeAutoConfiguration.class,
                            A2AAutoConfiguration.class, AgentCoreExtAutoConfiguration.class))
                    .withUserConfiguration(OrderedExtensions.class, DualHostedHandlers.class)
                    .withPropertyValues("spring.application.name=instance-customization")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        HostedLifecycleCoordinator lifecycle = context.getBean(HostedLifecycleCoordinator.class);
                        try {
                            lifecycle.runInitPhase();
                            HostedRuntimeCatalog catalog = context.getBean(HostedRuntimeCatalog.class);
                            assertThat(catalog.resolve("a").handler()).isNotSameAs(catalog.resolve("b").handler());
                            assertThat(context.getBean(Events.class).entries)
                                    .containsExactly("first:a", "second:a", "first:b", "second:b");
                        } finally {
                            lifecycle.runShutdownPhase();
                        }
                    });
        }
    }

    @Test
    @Story("FEAT-056-C11: 托管默认 Agent 显式 per-task")
    void hostedDefaultAgentUsesItsExplicitTaskFactory() {
        try (var ignored = mockStatic(Runner.class)) {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(HostedRuntimeAutoConfiguration.class,
                            A2AAutoConfiguration.class, AgentCoreExtAutoConfiguration.class))
                    .withUserConfiguration(OrderedExtensions.class, HostedDefaultPerTaskHandlers.class)
                    .withPropertyValues("spring.application.name=instance-customization")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        HostedLifecycleCoordinator lifecycle = context.getBean(HostedLifecycleCoordinator.class);
                        try {
                            lifecycle.runInitPhase();
                            HostedRuntimeCatalog catalog = context.getBean(HostedRuntimeCatalog.class);
                            Events events = context.getBean(Events.class);
                            assertThat(events.entries).containsExactly("first:b", "second:b");

                            Object resolvedHandler = catalog.resolve("a").handler();
                            if (!(resolvedHandler instanceof JiuwenCoreAgentExtHandler defaultHandler)) {
                                throw new IllegalStateException("hosted handler must be JiuwenCoreAgentExtHandler");
                            }
                            var token = defaultHandler.prepareTask(request("same-conversation"));
                            assertThat(events.entries)
                                    .containsExactly("first:b", "second:b", "first:a", "second:a");
                            defaultHandler.completeTask(token);
                        } finally {
                            lifecycle.runShutdownPhase();
                        }
                    });
        }
    }

    @Test
    @Story("FEAT-056-C18: Hosted 启动失败回滚已启动成员")
    void hostedStartFailureRollsBackPreviouslyStartedHandler() {
        try (var ignored = mockStatic(Runner.class)) {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(HostedRuntimeAutoConfiguration.class,
                            A2AAutoConfiguration.class, AgentCoreExtAutoConfiguration.class))
                    .withUserConfiguration(FailingHostedHandlers.class)
                    .withPropertyValues("spring.application.name=instance-customization")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        HostedLifecycleCoordinator lifecycle = context.getBean(HostedLifecycleCoordinator.class);
                        assertThatThrownBy(lifecycle::runInitPhase)
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("Hosted runtime startup failed")
                                .hasRootCauseMessage("hosted start b " + SENSITIVE_LOG_CANARY);
                        assertThat(context.getBean(Events.class).entries)
                                .contains("start:a", "start:b", "stop:b", "stop:a");
                    });
        }
    }

    @Test
    @Story("FEAT-056-C22: Hosted 启停日志字段完整、回滚可识别且不泄露敏感信息")
    void hostedLifecycleLogsExposeRequiredFieldsAndHideSensitiveMessage() {
        org.slf4j.Logger slf4jLogger = LoggerFactory.getLogger(HostedLifecycleCoordinator.class);
        if (!(slf4jLogger instanceof Logger logbackLogger)) {
            throw new IllegalStateException("expected a logback Logger");
        }
        Logger logger = logbackLogger;
        Level originalLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertNormalHostedLifecycleLogs(appender);
            assertStartFailureHostedLifecycleLogs(appender);
            assertRollbackStopFailureHostedLifecycleLogs(appender);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(originalLevel);
        }
    }

    private static void assertNormalHostedLifecycleLogs(ListAppender<ILoggingEvent> appender) {
        appender.list.clear();
        hostedRunner(DualHostedHandlers.class).run(context -> {
            HostedLifecycleCoordinator lifecycle = context.getBean(HostedLifecycleCoordinator.class);
            lifecycle.runInitPhase();
            lifecycle.runShutdownPhase();
        });

        List<ILoggingEvent> events = lifecycleEvents(appender);
        assertThat(messagesAtLevel(events, Level.INFO)).containsExactly(
                "Hosted agent agentId=a operation=start result=begin",
                "Hosted agent agentId=a operation=start result=success",
                "Hosted agent agentId=b operation=start result=begin",
                "Hosted agent agentId=b operation=start result=success",
                "Hosted agent agentId=b operation=stop result=begin rollback=false",
                "Hosted agent agentId=b operation=stop result=success rollback=false",
                "Hosted agent agentId=a operation=stop result=begin rollback=false",
                "Hosted agent agentId=a operation=stop result=success rollback=false");
        assertThat(messagesAtLevel(events, Level.ERROR)).isEmpty();
        assertNoSensitiveLogEvidence(events);
    }

    private static void assertStartFailureHostedLifecycleLogs(ListAppender<ILoggingEvent> appender) {
        appender.list.clear();
        hostedRunner(FailingHostedHandlers.class).run(context ->
                assertThatThrownBy(() -> context.getBean(HostedLifecycleCoordinator.class).runInitPhase())
                        .isInstanceOf(IllegalStateException.class));

        List<ILoggingEvent> events = lifecycleEvents(appender);
        assertThat(messagesAtLevel(events, Level.INFO)).containsExactly(
                "Hosted agent agentId=a operation=start result=begin",
                "Hosted agent agentId=a operation=start result=success",
                "Hosted agent agentId=b operation=start result=begin",
                "Hosted agent agentId=b operation=stop result=begin rollback=true",
                "Hosted agent agentId=b operation=stop result=success rollback=true",
                "Hosted agent agentId=a operation=stop result=begin rollback=true",
                "Hosted agent agentId=a operation=stop result=success rollback=true");
        assertThat(messagesAtLevel(events, Level.ERROR)).containsExactly(
                "Hosted agent agentId=b operation=start result=failure type=IllegalStateException");
        assertNoSensitiveLogEvidence(events);
    }

    private static void assertRollbackStopFailureHostedLifecycleLogs(ListAppender<ILoggingEvent> appender) {
        appender.list.clear();
        hostedRunner(RollbackStopFailureHostedHandlers.class).run(context ->
                assertThatThrownBy(() -> context.getBean(HostedLifecycleCoordinator.class).runInitPhase())
                        .isInstanceOf(IllegalStateException.class));

        List<ILoggingEvent> events = lifecycleEvents(appender);
        assertThat(messagesAtLevel(events, Level.INFO)).containsExactly(
                "Hosted agent agentId=a operation=start result=begin",
                "Hosted agent agentId=a operation=start result=success",
                "Hosted agent agentId=b operation=start result=begin",
                "Hosted agent agentId=b operation=stop result=begin rollback=true",
                "Hosted agent agentId=b operation=stop result=success rollback=true",
                "Hosted agent agentId=a operation=stop result=begin rollback=true");
        assertThat(messagesAtLevel(events, Level.ERROR)).containsExactly(
                "Hosted agent agentId=b operation=start result=failure type=IllegalStateException",
                "Hosted agent agentId=a operation=stop result=failure rollback=true type=IllegalStateException");
        assertNoSensitiveLogEvidence(events);
    }

    private static ApplicationContextRunner hostedRunner(Class<?> handlersConfiguration) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HostedRuntimeAutoConfiguration.class,
                        A2AAutoConfiguration.class, AgentCoreExtAutoConfiguration.class))
                .withUserConfiguration(handlersConfiguration)
                .withPropertyValues("spring.application.name=instance-customization");
    }

    private static List<ILoggingEvent> lifecycleEvents(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(event -> HostedLifecycleCoordinator.class.getName().equals(event.getLoggerName()))
                .filter(event -> event.getFormattedMessage().startsWith("Hosted agent "))
                .toList();
    }

    private static List<String> messagesAtLevel(List<ILoggingEvent> events, Level level) {
        return events.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static void assertNoSensitiveLogEvidence(List<ILoggingEvent> events) {
        assertThat(events).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(SENSITIVE_LOG_CANARY);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    private static ServeRequest request(String conversationId) {
        ServeRequest request = new ServeRequest();
        request.setConversationId(conversationId);
        return request;
    }

    static class Events {
        final List<String> entries = new ArrayList<>();
    }

    @Configuration(proxyBeanMethods = false)
    static class OrderedExtensions {
        @Bean
        Events events() {
            return new Events();
        }

        @Bean
        @Order(20)
        AgentInstanceCustomizer second(Events events) {
            return (id, agent) -> events.entries.add("second:" + id);
        }

        @Bean
        @Order(10)
        AgentInstanceCustomizer first(Events events) {
            return (id, agent) -> events.entries.add("first:" + id);
        }
    }

    abstract static class HostedSupport {
        @Bean
        CoreRunnerLifecycleCoordinator coreLifecycle() {
            return new CoreRunnerLifecycleCoordinator(null, null);
        }

        @Bean
        AgentServiceIdentity identity(Environment environment) {
            return new DefaultAgentServiceIdentity(environment);
        }

        @Bean
        DefaultAgentReadiness readiness() {
            return new DefaultAgentReadiness();
        }

        @Bean
        AgentLifecycleHooks hooks() {
            return new AgentLifecycleHooks(List.of(), List.of(), List.of());
        }

        @Bean
        ActiveStreamInterruptor interruptor(ObjectProvider<ServeOrchestrator> orchestrator) {
            return new ActiveStreamInterruptor(orchestrator, List.of());
        }

        static JiuwenCoreAgentExtHandler handler() {
            return new JiuwenCoreAgentExtHandler(mock(BaseAgent.class));
        }

        static AgentInstanceManager manager() {
            return new AgentInstanceManager(new AgentFactory() {
                @Override
                public Object create() {
                    return mock(BaseAgent.class);
                }

                @Override
                public void destroy(Object agent) {
                    // Mock agents have no resources.
                }
            });
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SingleHostedHandler extends HostedSupport {
        @Bean
        HostedAgentDefinitions definitions() {
            return HostedAgentDefinitions.builder().add("solo", handler()).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DualHostedHandlers extends HostedSupport {
        @Bean
        HostedAgentDefinitions definitions() {
            return HostedAgentDefinitions.builder().add("a", handler()).add("b", handler()).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class HostedDefaultPerTaskHandlers extends HostedSupport {
        @Bean
        HostedAgentDefinitions definitions() {
            JiuwenCoreAgentExtHandler perTask = handler();
            perTask.setAgentManager(manager());
            return HostedAgentDefinitions.builder().add("a", perTask).add("b", handler()).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FailingHostedHandlers extends HostedSupport {
        @Bean
        Events events() {
            return new Events();
        }

        @Bean
        AgentInstanceCustomizer failingB(Events events) {
            return (id, agent) -> {
                events.entries.add("customize:" + id);
                if ("b".equals(id)) {
                    throw new IllegalStateException("hosted start b " + SENSITIVE_LOG_CANARY);
                }
            };
        }

        @Bean
        HostedAgentDefinitions definitions(Events events) {
            return HostedAgentDefinitions.builder()
                    .add("a", new LifecycleRecordingHandler("a", events, List.of()))
                    .add("b", new LifecycleRecordingHandler("b", events, List.of()))
                    .build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RollbackStopFailureHostedHandlers extends HostedSupport {
        @Bean
        Events events() {
            return new Events();
        }

        @Bean
        AgentInstanceCustomizer failingB(Events events) {
            return (id, agent) -> {
                events.entries.add("customize:" + id);
                if ("b".equals(id)) {
                    throw new IllegalStateException("hosted start b " + SENSITIVE_LOG_CANARY);
                }
            };
        }

        @Bean
        HostedAgentDefinitions definitions(Events events) {
            return HostedAgentDefinitions.builder()
                    .add("a", new LifecycleRecordingHandler("a", events, List.of(), true))
                    .add("b", new LifecycleRecordingHandler("b", events, List.of(), false))
                    .build();
        }
    }

    static final class LifecycleRecordingHandler extends JiuwenCoreAgentExtHandler {
        private final String id;
        private final Events events;
        private final boolean failStop;

        private LifecycleRecordingHandler(String id, Events events, List<AgentInstanceCustomizer> customizers) {
            this(id, events, customizers, false);
        }

        private LifecycleRecordingHandler(String id, Events events, List<AgentInstanceCustomizer> customizers,
                boolean failStop) {
            super(mock(BaseAgent.class));
            this.id = id;
            this.events = events;
            this.failStop = failStop;
            configureInstanceCustomization(id, customizers);
        }

        @Override
        public synchronized void start() {
            events.entries.add("start:" + id);
            super.start();
        }

        @Override
        public void stop() {
            events.entries.add("stop:" + id);
            if (failStop) {
                throw new IllegalStateException("hosted stop " + id + " " + SENSITIVE_LOG_CANARY);
            }
            super.stop();
        }
    }
}

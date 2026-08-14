package com.huawei.ascend.sit.cases.integration.agent_bus;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Feature("FEAT-014: 总线支持 A2A 调用事件转发")
@Tag("feat-014")
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class A2aEventForwardingBlackboxTest {
    private AgentBusExternalFixture fixture;

    @BeforeAll
    void registerCallerAndCallee() throws Exception {
        fixture = AgentBusExternalFixture.requireBus();
        fixture.registerRuntime(AgentBusExternalFixture.SOURCE_AGENT, AgentBusExternalFixture.SOURCE_SERVICE,
                AgentBusExternalFixture.requireUrl("agent.bus.runtime.source-url", "AGENT_BUS_SOURCE_RUNTIME_URL"));
        fixture.registerRuntime(AgentBusExternalFixture.TARGET_AGENT, AgentBusExternalFixture.TARGET_SERVICE,
                AgentBusExternalFixture.requireUrl("agent.bus.runtime.target-url", "AGENT_BUS_TARGET_RUNTIME_URL"));
    }

    @Test
    @Tag("blackbox")
    @Story("FEAT-014.a2a.two-hop-round-trip: 两跳 A2A 事件调用")
    @Tag("story-feat-014-a2a-two-hop-round-trip")
    @DisplayName("Feat-014 两跳 A2A 事件把远端结果返回调用 Runtime")
    void feat014TwoHopA2aEventsReturnRemoteResultsToCallingRuntime() throws Exception {
        String canary = "a2a-two-hop-" + UUID.randomUUID();
        try (RocketMqBlackboxProbe probe = new RocketMqBlackboxProbe()) {
            var response = fixture.bus(AgentBusExternalFixture.SOURCE_AGENT, canary);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(response.body()).contains("source runtime received remote result", canary);
            RocketMqBlackboxProbe.ObservedMessage requested = probe.awaitAtLeast(1,
                    message -> "ascend_bus_a2a_req".equals(message.topic())
                            && "A2A_CALL_REQUESTED".equals(message.eventType())
                            && String.valueOf(message.inlinePayload()).contains(canary),
                    Duration.ofSeconds(60)).get(0);
            String correlation = requested.correlationId();
            probe.awaitAtLeast(1, message -> correlation.equals(message.correlationId())
                            && "A2A_CALL_ACCEPTED".equals(message.eventType()),
                    Duration.ofSeconds(60));
            probe.awaitAtLeast(1, message -> correlation.equals(message.correlationId())
                            && ("A2A_CALL_RESPONSE".equals(message.eventType())
                            || "A2A_CALL_TERMINAL".equals(message.eventType())),
                    Duration.ofSeconds(60));
        }
    }

    @Test
    @Tag("contract")
    @Story("FEAT-014.a2a.delivery-and-isolation: 远端投递与隔离")
    @Tag("story-feat-014-a2a-delivery-and-isolation")
    @DisplayName("Feat-014 远端投递按租户和目标 Runtime 隔离且不泄漏路由")
    void feat014RemoteDeliveryIsTenantScopedAndRouteSafe() throws Exception {
        String canary = "route-isolation-" + UUID.randomUUID();
        try (RocketMqBlackboxProbe probe = new RocketMqBlackboxProbe()) {
            var response = fixture.bus(AgentBusExternalFixture.SOURCE_AGENT, canary);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            List<RocketMqBlackboxProbe.ObservedMessage> requests = probe.awaitAtLeast(1,
                    message -> "ascend_bus_a2a_req".equals(message.topic())
                            && "A2A_CALL_REQUESTED".equals(message.eventType())
                            && String.valueOf(message.inlinePayload()).contains(canary),
                    Duration.ofSeconds(60));
            assertThat(requests).allSatisfy(message -> {
                assertThat(message.tenantId()).isEqualTo(AgentBusExternalFixture.TENANT);
                assertThat(message.sourceServiceId()).isEqualTo(AgentBusExternalFixture.SOURCE_SERVICE);
                assertThat(message.targetServiceId()).isEqualTo(AgentBusExternalFixture.TARGET_SERVICE);
                assertThat(message.body()).isEqualTo("target=" + AgentBusExternalFixture.TARGET_SERVICE)
                        .doesNotContain("routeHandle", "endpointUrl", "http://");
            });
        }
    }

    @Test
    @Tag("blackbox")
    @Story("FEAT-014.a2a.stream-boundary: 远端流准备与实时数据分离")
    @Tag("story-feat-014-a2a-stream-boundary")
    @DisplayName("Feat-014 远端流仅通过 BUS 传递准备和终态事实")
    void feat014RemoteStreamUsesBusOnlyForReadinessAndTerminalFacts() throws Exception {
        String canary = "remote-stream-" + UUID.randomUUID();
        try (RocketMqBlackboxProbe probe = new RocketMqBlackboxProbe()) {
            var response = fixture.busStreaming(AgentBusExternalFixture.SOURCE_AGENT, canary);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(response.body()).contains("source runtime received remote result");
            RocketMqBlackboxProbe.ObservedMessage requested = probe.awaitAtLeast(1,
                    message -> "ascend_bus_a2a_req".equals(message.topic())
                            && "A2A_CALL_REQUESTED".equals(message.eventType())
                            && String.valueOf(message.inlinePayload()).contains(canary),
                    Duration.ofSeconds(60)).get(0);
            List<RocketMqBlackboxProbe.ObservedMessage> streamReady = probe.awaitAtLeast(1,
                    message -> requested.correlationId().equals(message.correlationId())
                            && "A2A_STREAM_READY".equals(message.eventType()),
                    Duration.ofSeconds(60));
            List<RocketMqBlackboxProbe.ObservedMessage> terminal = probe.awaitAtLeast(1,
                    message -> requested.correlationId().equals(message.correlationId())
                            && "A2A_CALL_TERMINAL".equals(message.eventType()),
                    Duration.ofSeconds(60));
            assertThat(streamReady)
                    .allSatisfy(message -> assertThat(String.valueOf(message.inlinePayload()))
                            .doesNotContain("target stream chunk"));
            assertThat(terminal)
                    .allSatisfy(message -> assertThat(String.valueOf(message.inlinePayload()))
                            .doesNotContain("target stream chunk"));
        }
    }
}

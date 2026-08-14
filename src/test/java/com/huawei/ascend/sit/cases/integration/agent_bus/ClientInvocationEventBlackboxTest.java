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

@Feature("FEAT-013: 总线支持客户端调用事件转发")
@Tag("feat-013")
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClientInvocationEventBlackboxTest {
    private AgentBusExternalFixture fixture;

    @BeforeAll
    void registerRuntime() throws Exception {
        fixture = AgentBusExternalFixture.requireBus();
        fixture.registerRuntime(AgentBusExternalFixture.TARGET_AGENT, AgentBusExternalFixture.TARGET_SERVICE,
                AgentBusExternalFixture.requireUrl("agent.bus.runtime.target-url", "AGENT_BUS_TARGET_RUNTIME_URL"));
    }

    @Test
    @Tag("blackbox")
    @Story("FEAT-013.event.round-trip: 客户端调用事件往返")
    @Tag("story-feat-013-event-round-trip")
    @DisplayName("Feat-013 客户端调用事件经 BUS 往返真实 Agent")
    void feat013ClientInvocationEventsRoundTripThroughBusToRealAgent() throws Exception {
        String canary = "feat013-" + UUID.randomUUID();
        try (RocketMqBlackboxProbe probe = new RocketMqBlackboxProbe()) {
            var response = fixture.bus(AgentBusExternalFixture.TARGET_AGENT, canary);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(response.body()).contains(canary);

            RocketMqBlackboxProbe.ObservedMessage requested = probe.awaitAtLeast(1,
                    message -> "ascend_bus_invocation_req".equals(message.topic())
                            && "CLIENT_INVOCATION_REQUESTED".equals(message.eventType())
                            && String.valueOf(message.inlinePayload()).contains(canary),
                    Duration.ofSeconds(45)).get(0);
            String correlation = requested.correlationId();
            List<RocketMqBlackboxProbe.ObservedMessage> events = probe.awaitAtLeast(3,
                    message -> correlation.equals(message.correlationId())
                            && message.eventType() != null
                            && (message.eventType().startsWith("CLIENT_INVOCATION")
                            || message.eventType().startsWith("INVOCATION_")),
                    Duration.ofSeconds(45));
            assertThat(events).extracting(RocketMqBlackboxProbe.ObservedMessage::eventType)
                    .contains("CLIENT_INVOCATION_REQUESTED", "INVOCATION_ACCEPTED");
            assertThat(events).allSatisfy(event -> {
                assertThat(event.messageId()).isNotBlank();
                assertThat(event.correlationId()).isNotBlank();
            });
        }
    }

    @Test
    @Tag("contract")
    @Story("FEAT-013.event.delivery-safety: 投递安全与载荷边界")
    @Tag("story-feat-013-event-delivery-safety")
    @DisplayName("Feat-013 投递按租户隔离且大载荷使用引用")
    void feat013DeliveryIsTenantScopedAndUsesPayloadReferences() throws Exception {
        String canary = "large-" + UUID.randomUUID();
        String large = canary + "x".repeat(96 * 1024);
        try (RocketMqBlackboxProbe probe = new RocketMqBlackboxProbe()) {
            var response = fixture.bus(AgentBusExternalFixture.TARGET_AGENT, large);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            List<RocketMqBlackboxProbe.ObservedMessage> requests = probe.awaitAtLeast(1,
                    message -> "ascend_bus_invocation_req".equals(message.topic())
                            && "CLIENT_INVOCATION_REQUESTED".equals(message.eventType())
                            && message.payloadRef() != null,
                    Duration.ofSeconds(45));
            RocketMqBlackboxProbe.ObservedMessage request = requests.get(0);
            assertThat(request.tenantId()).isEqualTo(AgentBusExternalFixture.TENANT);
            assertThat(request.payloadRef()).isNotBlank();
            assertThat(request.inlinePayload()).doesNotContain(canary);
            assertThat(request.body()).doesNotContain(canary, "http://", "routeHandle");
        }
    }

    @Test
    @Tag("blackbox")
    @Story("FEAT-013.event.stream-boundary: 流控制与实时数据分离")
    @Tag("story-feat-013-event-stream-boundary")
    @DisplayName("Feat-013 BUS 只承载流准备事实而不承载实时 token")
    void feat013BusCarriesStreamReadinessButNeverRealtimeTokens() throws Exception {
        String canary = "stream-control-" + UUID.randomUUID();
        try (RocketMqBlackboxProbe probe = new RocketMqBlackboxProbe()) {
            var response = fixture.busStreaming(AgentBusExternalFixture.TARGET_AGENT, canary);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(response.body()).contains("target stream chunk");
            RocketMqBlackboxProbe.ObservedMessage requested = probe.awaitAtLeast(1,
                    message -> "ascend_bus_invocation_req".equals(message.topic())
                            && "CLIENT_INVOCATION_REQUESTED".equals(message.eventType())
                            && String.valueOf(message.inlinePayload()).contains(canary),
                    Duration.ofSeconds(45)).get(0);
            List<RocketMqBlackboxProbe.ObservedMessage> events = probe.awaitAtLeast(1,
                    message -> requested.correlationId().equals(message.correlationId())
                            && "INVOCATION_STREAM_READY".equals(message.eventType()),
                    Duration.ofSeconds(45));
            assertThat(events).anyMatch(message -> "INVOCATION_STREAM_READY".equals(message.eventType()));
            assertThat(events).filteredOn(message -> !"CLIENT_INVOCATION_REQUESTED".equals(message.eventType()))
                    .allSatisfy(message -> assertThat(String.valueOf(message.inlinePayload()))
                            .doesNotContain("target stream chunk"));
        }
    }
}

package com.huawei.ascend.sit.cases.integration.agent_bus;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

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

    @Test
    @Tag("blackbox")
    @Story("FEAT-013.event.relay-multi-instance: relay 多实例负载分担与不丢不重")
    @Tag("story-feat-013-event-relay-multi-instance")
    @DisplayName("Feat-013 多 relay 实例竞争消费调用事件且不丢不重")
    void feat013MultiInstanceRelaySharesInvocationLoadWithoutLossOrDuplication() throws Exception {
        assumeMultiInstanceRelay();
        List<String> canaries = IntStream.range(0, 6)
                .mapToObj(index -> "multi-relay-" + index + "-" + UUID.randomUUID())
                .toList();
        try (RocketMqBlackboxProbe probe = new RocketMqBlackboxProbe()) {
            List<HttpResponse<String>> responses = invokeConcurrently(canaries);
            for (int index = 0; index < canaries.size(); index++) {
                assertThat(responses.get(index).statusCode()).as(responses.get(index).body()).isEqualTo(200);
                assertThat(responses.get(index).body()).contains(canaries.get(index));
            }
            probe.drainUntilQuiet(Duration.ofSeconds(3), Duration.ofSeconds(45));
            for (String canary : canaries) {
                assertSingleVisibleInvocationFact(probe, canary);
            }
        }
    }

    @Test
    @Tag("blackbox")
    @Story("FEAT-013.event.relay-failure-tolerance: 单 relay 实例故障不中断")
    @Tag("story-feat-013-event-relay-failure-tolerance")
    @DisplayName("Feat-013 单 relay 实例故障后幸存实例接手且无重复副作用")
    void feat013InvocationSurvivesSingleRelayInstanceFailure() throws Exception {
        assumeMultiInstanceRelay();
        String faultUrl = AgentBusExternalFixture.relayFaultTriggerUrl();
        Assumptions.assumeTrue(faultUrl != null,
                "relay failure story needs AGENT_BUS_RELAY_FAULT_URL (endpoint terminating one relay instance)");
        List<String> baseline = List.of("relay-fail-baseline-" + UUID.randomUUID());
        List<String> afterFailure = IntStream.range(0, 4)
                .mapToObj(index -> "relay-fail-after-" + index + "-" + UUID.randomUUID())
                .toList();
        try (RocketMqBlackboxProbe probe = new RocketMqBlackboxProbe()) {
            List<HttpResponse<String>> baselineResponses = invokeConcurrently(baseline);
            for (HttpResponse<String> response : baselineResponses) {
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            }
            fixture.triggerRelayInstanceFailure(faultUrl);
            List<HttpResponse<String>> responses = invokeConcurrently(afterFailure);
            for (int index = 0; index < afterFailure.size(); index++) {
                assertThat(responses.get(index).statusCode()).as(responses.get(index).body()).isEqualTo(200);
                assertThat(responses.get(index).body()).contains(afterFailure.get(index));
            }
            probe.drainUntilQuiet(Duration.ofSeconds(3), Duration.ofSeconds(45));
            for (String canary : baseline) {
                assertSingleVisibleInvocationFact(probe, canary);
            }
            for (String canary : afterFailure) {
                assertSingleVisibleInvocationFact(probe, canary);
            }
        }
    }

    private static void assumeMultiInstanceRelay() {
        Assumptions.assumeTrue(AgentBusExternalFixture.relayInstanceCount() >= 2,
                "relay multi-instance stories need AGENT_BUS_RELAY_INSTANCES>=2 (shared consumer group, same PG)");
    }

    private List<HttpResponse<String>> invokeConcurrently(List<String> canaries) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(canaries.size());
        try {
            List<Future<HttpResponse<String>>> futures = canaries.stream()
                    .map(canary -> executor.submit(() -> fixture.bus(AgentBusExternalFixture.TARGET_AGENT, canary)))
                    .toList();
            List<HttpResponse<String>> responses = new ArrayList<>();
            for (Future<HttpResponse<String>> future : futures) {
                responses.add(future.get(3, TimeUnit.MINUTES));
            }
            return responses;
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Multi-instance relay oracle: exactly one request fact per canary, unique relay-produced
     * hop2 message ids per correlation (deterministic {@code eb-} ids deduplicate concurrent
     * consumption), and exactly one visible response projection.
     */
    private void assertSingleVisibleInvocationFact(RocketMqBlackboxProbe probe, String canary) {
        List<RocketMqBlackboxProbe.ObservedMessage> requested = probe.matching(message ->
                "ascend_bus_invocation_req".equals(message.topic())
                        && "CLIENT_INVOCATION_REQUESTED".equals(message.eventType())
                        && String.valueOf(message.inlinePayload()).contains(canary));
        assertThat(requested).as("CLIENT_INVOCATION_REQUESTED facts for " + canary).hasSize(1);
        String correlation = requested.get(0).correlationId();
        List<RocketMqBlackboxProbe.ObservedMessage> relayed = probe.matching(message ->
                correlation.equals(message.correlationId())
                        && (message.topic().endsWith("_deliver") || message.topic().endsWith("_resp_out")));
        assertThat(relayed).as("relay hop2 facts for " + canary).isNotEmpty();
        assertThat(relayed).extracting(RocketMqBlackboxProbe.ObservedMessage::messageId)
                .as("relay hop2 message ids for " + canary).doesNotHaveDuplicates();
        List<RocketMqBlackboxProbe.ObservedMessage> responses = probe.matching(message ->
                "ascend_bus_invocation_resp_out".equals(message.topic())
                        && correlation.equals(message.correlationId())
                        && "INVOCATION_RESPONSE".equals(message.eventType()));
        assertThat(responses).as("visible response projections for " + canary).hasSize(1);
    }
}

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

    @Test
    @Tag("blackbox")
    @Story("FEAT-014.a2a.relay-multi-instance: A2A 两跳随 relay 多实例负载分担且不丢不重")
    @Tag("story-feat-014-a2a-relay-multi-instance")
    @DisplayName("Feat-014 多 relay 实例竞争消费 A2A 调用事件且不丢不重")
    void feat014A2aTwoHopRelayMultiInstanceLoadSharing() throws Exception {
        assumeMultiInstanceRelay();
        List<String> canaries = IntStream.range(0, 6)
                .mapToObj(index -> "a2a-multi-relay-" + index + "-" + UUID.randomUUID())
                .toList();
        try (RocketMqBlackboxProbe probe = new RocketMqBlackboxProbe()) {
            List<HttpResponse<String>> responses = invokeTwoHopConcurrently(canaries);
            for (int index = 0; index < canaries.size(); index++) {
                assertThat(responses.get(index).statusCode()).as(responses.get(index).body()).isEqualTo(200);
                assertThat(responses.get(index).body())
                        .contains("source runtime received remote result", canaries.get(index));
            }
            probe.drainUntilQuiet(Duration.ofSeconds(3), Duration.ofSeconds(45));
            for (String canary : canaries) {
                assertSingleVisibleA2aCallFact(probe, canary);
            }
        }
    }

    @Test
    @Tag("blackbox")
    @Story("FEAT-014.a2a.relay-failure-tolerance: 单 relay 实例故障不中断 A2A 两跳")
    @Tag("story-feat-014-a2a-relay-failure-tolerance")
    @DisplayName("Feat-014 单 relay 实例故障后幸存实例接手 A2A 调用且无重复副作用")
    void feat014A2aTwoHopSurvivesSingleRelayInstanceFailure() throws Exception {
        assumeMultiInstanceRelay();
        String faultUrl = AgentBusExternalFixture.relayFaultTriggerUrl();
        Assumptions.assumeTrue(faultUrl != null,
                "relay failure story needs AGENT_BUS_RELAY_FAULT_URL (endpoint terminating one relay instance)");
        List<String> baseline = List.of("a2a-relay-fail-baseline-" + UUID.randomUUID());
        List<String> afterFailure = IntStream.range(0, 4)
                .mapToObj(index -> "a2a-relay-fail-after-" + index + "-" + UUID.randomUUID())
                .toList();
        try (RocketMqBlackboxProbe probe = new RocketMqBlackboxProbe()) {
            List<HttpResponse<String>> baselineResponses = invokeTwoHopConcurrently(baseline);
            for (HttpResponse<String> response : baselineResponses) {
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            }
            fixture.triggerRelayInstanceFailure(faultUrl);
            List<HttpResponse<String>> responses = invokeTwoHopConcurrently(afterFailure);
            for (int index = 0; index < afterFailure.size(); index++) {
                assertThat(responses.get(index).statusCode()).as(responses.get(index).body()).isEqualTo(200);
                assertThat(responses.get(index).body())
                        .contains("source runtime received remote result", afterFailure.get(index));
            }
            probe.drainUntilQuiet(Duration.ofSeconds(3), Duration.ofSeconds(45));
            for (String canary : baseline) {
                assertSingleVisibleA2aCallFact(probe, canary);
            }
            for (String canary : afterFailure) {
                assertSingleVisibleA2aCallFact(probe, canary);
            }
        }
    }

    private static void assumeMultiInstanceRelay() {
        Assumptions.assumeTrue(AgentBusExternalFixture.relayInstanceCount() >= 2,
                "relay multi-instance stories need AGENT_BUS_RELAY_INSTANCES>=2 (shared consumer group, same PG)");
    }

    private List<HttpResponse<String>> invokeTwoHopConcurrently(List<String> canaries) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(canaries.size());
        try {
            List<Future<HttpResponse<String>>> futures = canaries.stream()
                    .map(canary -> executor.submit(() -> fixture.bus(AgentBusExternalFixture.SOURCE_AGENT, canary)))
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
     * Multi-instance relay oracle for the A2A family: exactly one request fact per canary, unique
     * relay-produced hop2 message ids per correlation, and exactly one visible response projection
     * — competing relay instances must not create a second remote call or visible result.
     */
    private void assertSingleVisibleA2aCallFact(RocketMqBlackboxProbe probe, String canary) {
        List<RocketMqBlackboxProbe.ObservedMessage> requested = probe.matching(message ->
                "ascend_bus_a2a_req".equals(message.topic())
                        && "A2A_CALL_REQUESTED".equals(message.eventType())
                        && String.valueOf(message.inlinePayload()).contains(canary));
        assertThat(requested).as("A2A_CALL_REQUESTED facts for " + canary).hasSize(1);
        String correlation = requested.get(0).correlationId();
        List<RocketMqBlackboxProbe.ObservedMessage> relayed = probe.matching(message ->
                correlation.equals(message.correlationId())
                        && (message.topic().equals("ascend_bus_a2a_deliver")
                        || message.topic().equals("ascend_bus_a2a_resp_out")));
        assertThat(relayed).as("relay hop2 facts for " + canary).isNotEmpty();
        assertThat(relayed).extracting(RocketMqBlackboxProbe.ObservedMessage::messageId)
                .as("relay hop2 message ids for " + canary).doesNotHaveDuplicates();
        List<RocketMqBlackboxProbe.ObservedMessage> responses = probe.matching(message ->
                "ascend_bus_a2a_resp_out".equals(message.topic())
                        && correlation.equals(message.correlationId())
                        && "A2A_CALL_RESPONSE".equals(message.eventType()));
        assertThat(responses).as("visible A2A response projections for " + canary).hasSize(1);
    }
}

package com.huawei.ascend.sit.cases.integration.agent_bus;

import com.huawei.ascend.sit.cases.integration.agent_bus.AgentBusExternalFixture;
import com.huawei.ascend.sit.cases.integration.agent_bus.RocketMqBlackboxProbe;
import com.openjiuwen.client.api.AgentClient;
import com.openjiuwen.client.api.AgentClients;
import com.openjiuwen.client.api.InvocationMode;
import com.openjiuwen.client.api.InvocationRequest;
import com.openjiuwen.client.api.InvocationSnapshot;
import com.openjiuwen.client.api.TaskState;
import com.openjiuwen.client.transport.a2a.A2aHttpTransportProvider;
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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Feature("FEAT-E2E: 标准客户端到多 Agent 的路由与事件转发")
@Tag("feat-e2e")
@Tag("e2e")
@Tag("integration")
@Tag("fixture-e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ClientGatewayBusA2aRouteEndToEndIT {
    private AgentBusExternalFixture fixture;

    @BeforeAll
    void registerCallerAndCallee() throws Exception {
        fixture = AgentBusExternalFixture.requireBoth();
        fixture.registerRuntime(AgentBusExternalFixture.SOURCE_AGENT, AgentBusExternalFixture.SOURCE_SERVICE,
                AgentBusExternalFixture.requireUrl("agent.bus.runtime.source-url", "AGENT_BUS_SOURCE_RUNTIME_URL"));
        fixture.registerRuntime(AgentBusExternalFixture.TARGET_AGENT, AgentBusExternalFixture.TARGET_SERVICE,
                AgentBusExternalFixture.requireUrl("agent.bus.runtime.target-url", "AGENT_BUS_TARGET_RUNTIME_URL"));
    }

    @Test
    @Story("FEAT-E2E.path-parity-and-failure-boundary: DIRECT/BUS 等价与故障边界")
    @Tag("story-feat-e2e-path-parity-and-failure-boundary")
    @Tag("feat-006")
    @Tag("feat-011")
    @Tag("feat-012")
    @Tag("feat-013")
    @Tag("feat-016")
    @DisplayName("E2E DIRECT/BUS 保持客户端语义并隔离失败边界")
    void featE2eDirectAndBusPathsPreserveClientSemanticsAndFailureBoundaries() throws Exception {
        String canary = "path-parity-" + UUID.randomUUID();
        try (AgentClient directClient = client(fixture.directUrl());
             AgentClient busClient = client(fixture.busUrl())) {
            InvocationSnapshot direct = invoke(directClient, AgentBusExternalFixture.SOURCE_AGENT,
                    canary, "direct");
            assertCompletedWithoutTopology(direct, canary);

            try (RocketMqBlackboxProbe probe = new RocketMqBlackboxProbe()) {
                InvocationSnapshot bus = invoke(busClient, AgentBusExternalFixture.SOURCE_AGENT,
                        canary, "bus");
                assertCompletedWithoutTopology(bus, canary);
                List<RocketMqBlackboxProbe.ObservedMessage> events = probe.awaitAtLeast(2,
                        message -> message.eventType() != null
                                && (message.eventType().startsWith("CLIENT_INVOCATION")
                                || message.eventType().startsWith("INVOCATION_")),
                        Duration.ofSeconds(60));
                assertThat(events).extracting(RocketMqBlackboxProbe.ObservedMessage::eventType)
                        .contains("CLIENT_INVOCATION_REQUESTED");
            }

            InvocationSnapshot directFailure = invoke(directClient,
                    "missing-" + UUID.randomUUID(), "x", "direct-failure");
            InvocationSnapshot busFailure = invoke(busClient,
                    "missing-" + UUID.randomUUID(), "x", "bus-failure");
            assertRouteFailure(directFailure);
            assertRouteFailure(busFailure);
        }
    }

    private static AgentClient client(String gatewayUrl) {
        return AgentClients.builder()
                .transport(new A2aHttpTransportProvider(gatewayUrl, AgentBusExternalFixture.JSON,
                        Duration.ofSeconds(30)))
                .credentialProvider(conversationId -> AgentBusExternalFixture.TOKEN)
                .build();
    }

    private static InvocationSnapshot invoke(AgentClient client, String agentId, String input,
                                             String marker) throws Exception {
        InvocationRequest request = InvocationRequest.builder()
                .agentId(agentId)
                .conversationId("e2e-conversation-" + marker + "-" + UUID.randomUUID())
                .invocationId("e2e-invocation-" + marker + "-" + UUID.randomUUID())
                .mode(InvocationMode.STREAMING)
                .input(input)
                .build();
        return client.invoke(request).completion().toCompletableFuture().get(90, TimeUnit.SECONDS);
    }

    private static void assertCompletedWithoutTopology(InvocationSnapshot snapshot, String canary) {
        assertThat(snapshot.state()).isEqualTo(TaskState.COMPLETED);
        assertThat(snapshot.outputText()).contains("source runtime received remote result", canary)
                .doesNotContain("routeHandle", "endpointUrl");
    }

    private static void assertRouteFailure(InvocationSnapshot snapshot) {
        assertThat(snapshot.state()).isEqualTo(TaskState.FAILED);
        assertThat(snapshot.errorCode()).isEqualTo("ROUTE_NO_CANDIDATES");
        assertThat(String.valueOf(snapshot.message()))
                .doesNotContain("routeHandle", "endpointUrl", "127.0.0.1", "localhost");
    }
}

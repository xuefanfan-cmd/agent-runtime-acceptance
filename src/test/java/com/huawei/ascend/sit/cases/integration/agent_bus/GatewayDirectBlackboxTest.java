package com.huawei.ascend.sit.cases.integration.agent_bus;

import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Feature("FEAT-011: 网关组件客户端调用路由转发")
@Tag("feat-011")
@Tag("integration")
@Tag("blackbox")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GatewayDirectBlackboxTest {
    private AgentBusExternalFixture fixture;

    @BeforeAll
    void registerRuntime() throws Exception {
        fixture = AgentBusExternalFixture.requireDirect();
        fixture.registerRuntime(AgentBusExternalFixture.SOURCE_AGENT, AgentBusExternalFixture.SOURCE_SERVICE,
                AgentBusExternalFixture.requireUrl("agent.bus.runtime.source-url", "AGENT_BUS_SOURCE_RUNTIME_URL"));
    }

    @Test
    @Story("FEAT-011.direct.create-and-stream: 直连创建与 SSE 桥接")
    @Tag("story-feat-011-direct-create-and-stream")
    @DisplayName("Feat-011 Gateway 按显式或默认 agentId 直连并桥接 SSE")
    void feat011DirectGatewayRoutesExplicitAndDefaultAgentForSyncAndStreaming() throws Exception {
        String canary = "direct-" + UUID.randomUUID();
        var sync = fixture.direct(AgentBusExternalFixture.SOURCE_AGENT, canary);
        assertThat(sync.statusCode()).as(sync.body()).isEqualTo(200);
        assertThat(sync.body()).contains("result").doesNotContain("routeHandle", "endpointUrl");

        var stream = fixture.directStreaming(null, canary + "-stream");
        assertThat(stream.statusCode()).as(stream.body()).isEqualTo(200);
        assertThat(stream.headers().firstValue("content-type").orElse(""))
                .containsIgnoringCase("text/event-stream");
        assertThat(stream.body()).contains("data:").doesNotContain("routeHandle", "endpointUrl");
    }

    @Test
    @Story("FEAT-011.direct.governance-and-routing-failure: 治理与选路失败")
    @Tag("story-feat-011-direct-governance-and-routing-failure")
    @DisplayName("Feat-011 治理或选路失败不触达 Agent 且不泄漏拓扑")
    void feat011GatewayRejectsBeforeForwardingAndReturnsSanitizedRouteFailures() throws Exception {
        String valid = AgentBusExternalFixture.create(AgentBusExternalFixture.SOURCE_AGENT, "x", false);
        var missingAuth = fixture.postRaw(false, valid, null);
        assertThat(missingAuth.statusCode()).isEqualTo(401);
        assertSanitized(missingAuth.body(), "AUTH_MISSING");

        var invalidAuth = fixture.postRaw(false, valid, "wrong-token");
        assertThat(invalidAuth.statusCode()).isEqualTo(401);
        assertSanitized(invalidAuth.body(), "AUTH_INVALID");

        var unknown = fixture.direct("missing-agent-" + UUID.randomUUID(), "x");
        assertThat(unknown.statusCode()).isEqualTo(503);
        assertSanitized(unknown.body(), "ROUTE_NO_CANDIDATES");
    }

    @Test
    @Story("FEAT-011.direct.sticky-continuation: 同 Task 粘滞续跑")
    @Tag("story-feat-011-direct-sticky-continuation")
    @DisplayName("Feat-011 带 taskId 的续跑回到原 Task owner")
    void feat011ContinuationReturnsToOriginalTaskOwnerAndRejectsUnknownTask() throws Exception {
        var created = fixture.direct(AgentBusExternalFixture.SOURCE_AGENT, "create sticky task");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        JsonNode result = AgentBusExternalFixture.JSON.readTree(created.body()).path("result");
        String taskId = result.path("task").path("id").asText(result.path("id").asText());
        assertThat(taskId).isNotBlank();

        String resume = "{\"jsonrpc\":\"2.0\",\"id\":\"resume\",\"method\":\"SendMessage\","
                + "\"params\":{\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"msg-"
                + UUID.randomUUID() + "\",\"taskId\":\"" + taskId
                + "\",\"parts\":[{\"text\":\"continue\"}]}}}";
        assertThat(fixture.postRaw(false, resume, AgentBusExternalFixture.TOKEN).statusCode()).isEqualTo(200);

        String unknown = resume.replace(taskId, "task-never-owned-" + UUID.randomUUID());
        var miss = fixture.postRaw(false, unknown, AgentBusExternalFixture.TOKEN);
        assertThat(miss.statusCode()).isEqualTo(404);
        assertSanitized(miss.body(), "RESUME_OWNER_UNKNOWN");
    }

    private static void assertSanitized(String body, String code) {
        assertThat(body).contains(code)
                .doesNotContain("routeHandle", "endpointUrl", "127.0.0.1", "localhost");
    }
}

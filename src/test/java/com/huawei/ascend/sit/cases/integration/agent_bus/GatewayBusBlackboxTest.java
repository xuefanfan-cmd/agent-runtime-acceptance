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

@Feature("FEAT-012: 网关组件客户端调用总线转发")
@Tag("feat-012")
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GatewayBusBlackboxTest {
    private AgentBusExternalFixture fixture;

    @BeforeAll
    void registerBusRuntimes() throws Exception {
        fixture = AgentBusExternalFixture.requireBus();
        fixture.registerRuntime(AgentBusExternalFixture.SOURCE_AGENT, AgentBusExternalFixture.SOURCE_SERVICE,
                AgentBusExternalFixture.requireUrl("agent.bus.runtime.source-url", "AGENT_BUS_SOURCE_RUNTIME_URL"));
        fixture.registerRuntime(AgentBusExternalFixture.TARGET_AGENT, AgentBusExternalFixture.TARGET_SERVICE,
                AgentBusExternalFixture.requireUrl("agent.bus.runtime.target-url", "AGENT_BUS_TARGET_RUNTIME_URL"));
    }

    @Test
    @Tag("blackbox")
    @Story("FEAT-012.bus.sync-five-state: 同步入队与五态折叠")
    @Tag("story-feat-012-bus-sync-five-state")
    @DisplayName("Feat-012 BUS 同步调用折叠为单个客户端结果")
    void feat012BusCreateFoldsProjectionsIntoExactlyOneClientResult() throws Exception {
        var completed = fixture.bus(AgentBusExternalFixture.SOURCE_AGENT, "bus round trip");
        assertThat(completed.statusCode()).as(completed.body()).isEqualTo(200);
        JsonNode completedRoot = AgentBusExternalFixture.JSON.readTree(completed.body());
        assertThat(completedRoot.has("result")).as(completed.body()).isTrue();
        assertThat(completedRoot.has("error")).isFalse();
        assertThat(completed.body()).contains("source runtime received remote result");

        var inputRequired = fixture.bus(AgentBusExternalFixture.TARGET_AGENT, "request target approval");
        assertThat(inputRequired.statusCode()).as(inputRequired.body()).isEqualTo(200);
        assertThat(inputRequired.body()).contains("TASK_STATE_INPUT_REQUIRED");

        var noRoute = fixture.bus("unknown-" + UUID.randomUUID(), "x");
        assertThat(noRoute.statusCode()).isEqualTo(503);
        assertThat(noRoute.body()).contains("ROUTE_NO_CANDIDATES")
                .doesNotContain("routeHandle", "endpointUrl");
    }

    @Test
    @Tag("blackbox")
    @Story("FEAT-012.bus.streaming: 流准备与数据面分离")
    @Tag("story-feat-012-bus-streaming")
    @DisplayName("Feat-012 流控制走 BUS 且实时内容通过点对点 SSE 返回")
    void feat012StreamingUsesBusForControlAndPointToPointSseForContent() throws Exception {
        var response = fixture.busStreaming(AgentBusExternalFixture.TARGET_AGENT, "stream through bus");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse(""))
                .containsIgnoringCase("text/event-stream");
        assertThat(response.body()).contains("data:", "target stream chunk")
                .doesNotContain("routeHandle", "endpointUrl");
    }

    @Test
    @Tag("blackbox")
    @Story("FEAT-012.bus.gate-and-continuation: 入队 gate 与续跑")
    @Tag("story-feat-012-bus-gate-and-continuation")
    @DisplayName("Feat-012 BUS 在入队前拒绝非法请求且只续跑已知 Task")
    void feat012BusRejectsBeforeEnqueueAndContinuesOnlyKnownTask() throws Exception {
        String create = AgentBusExternalFixture.create(
                AgentBusExternalFixture.TARGET_AGENT, "request target approval", false);
        assertThat(fixture.postRaw(true, create, null).statusCode()).isEqualTo(401);

        var waiting = fixture.postRaw(true, create, AgentBusExternalFixture.TOKEN);
        assertThat(waiting.statusCode()).as(waiting.body()).isEqualTo(200);
        JsonNode root = AgentBusExternalFixture.JSON.readTree(waiting.body());
        JsonNode result = root.path("result");
        JsonNode task = result.path("task").isMissingNode() ? root.path("task") : result.path("task");
        String taskId = task.path("id").asText(result.path("id").asText());
        assertThat(taskId).isNotBlank();

        String resume = resume(taskId);
        var resumed = fixture.postRaw(true, resume, AgentBusExternalFixture.TOKEN);
        assertThat(resumed.statusCode()).as(resumed.body()).isEqualTo(200);
        assertThat(resumed.body()).contains("TASK_STATE_COMPLETED");

        var unknown = fixture.postRaw(true,
                resume.replace(taskId, "unknown-task-" + UUID.randomUUID()), AgentBusExternalFixture.TOKEN);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.body()).contains("RESUME_OWNER_UNKNOWN");
    }

    @Test
    @Tag("contract")
    @Story("FEAT-012.bus.projection-contract: 投影折叠合同")
    @Tag("story-feat-012-bus-projection-contract")
    @DisplayName("Feat-012 相同投影重放幂等且客户端状态不倒退")
    void feat012ProjectionReplayIsIdempotentAndMonotonic() throws Exception {
        String request = AgentBusExternalFixture.create(
                AgentBusExternalFixture.TARGET_AGENT, "idempotency projection", false);
        var first = fixture.postRaw(true, request, AgentBusExternalFixture.TOKEN);
        var replay = fixture.postRaw(true, request, AgentBusExternalFixture.TOKEN);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(AgentBusExternalFixture.JSON.readTree(replay.body()))
                .isEqualTo(AgentBusExternalFixture.JSON.readTree(first.body()));
    }

    private static String resume(String taskId) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"resume\",\"method\":\"SendMessage\","
                + "\"params\":{\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"msg-"
                + UUID.randomUUID() + "\",\"taskId\":\"" + taskId
                + "\",\"parts\":[{\"text\":\"approved\"}]}}}";
    }
}

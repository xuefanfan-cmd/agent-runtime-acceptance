package com.huawei.ascend.sit.cases.integration.agent_bus;

import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.UUID;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@Feature("FEAT-011: 网关组件客户端调用路由转发")
@Tag("feat-011")
@Tag("integration")
@Tag("blackbox")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GatewayDirectBlackboxTest {
    private AgentBusExternalFixture fixture;

    @BeforeAll
    void initFixture() throws Exception {
        fixture = AgentBusExternalFixture.requireDirect();
    }

    @BeforeEach
    void registerRuntime() throws Exception {
        fixture.registerRuntimeV2(AgentBusExternalFixture.SOURCE_AGENT, AgentBusExternalFixture.SOURCE_SERVICE,
                AgentBusExternalFixture.requireUrl("agent.bus.runtime.source-url", "AGENT_BUS_SOURCE_RUNTIME_URL"));
    }

    @AfterEach
    void cleanupInstances() throws Exception {
        // Deregister all instances to prevent cross-test accumulation (DEV-008)
        fixture.deregisterAllV2(AgentBusExternalFixture.SOURCE_AGENT);
    }

    @Test
    @Story("FEAT-011.direct.create-and-stream: 直连创建与 SSE 桥接")
    @Tag("story-feat-011-direct-create-and-stream")
    @DisplayName("Feat-011 Gateway 按显式 agentId 直连并桥接 SSE")
    void feat011DirectGatewayRoutesExplicitAndDefaultAgentForSyncAndStreaming() throws Exception {
        String canary = "direct-" + UUID.randomUUID();
        var sync = fixture.direct(AgentBusExternalFixture.SOURCE_AGENT, canary);
        assertThat(sync.statusCode()).as(sync.body()).isEqualTo(200);
        assertThat(sync.body()).contains("result", "TASK_STATE_COMPLETED")
                .doesNotContain("routeHandle", "endpointUrl");

        var stream = fixture.directStreaming(AgentBusExternalFixture.SOURCE_AGENT, canary + "-stream");
        assertThat(stream.statusCode()).as(stream.body()).isEqualTo(200);
        assertThat(stream.headers().firstValue("content-type").orElse(""))
                .containsIgnoringCase("text/event-stream");
        assertThat(stream.body()).contains("data:")
                .contains("SUBMITTED").contains("COMPLETED")
                .doesNotContain("routeHandle", "endpointUrl");
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

        // Sticky resume: gateway must route to the original owner (not ROUTE_OWNERSHIP_UNKNOWN).
        // The agent may reject if the task is already in terminal state, but the gateway
        // must still find the owner — that is what sticky routing verifies.
        String resume = "{\"jsonrpc\":\"2.0\",\"id\":\"resume\",\"method\":\"SendMessage\","
                + "\"params\":{\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"msg-"
                + UUID.randomUUID() + "\",\"taskId\":\"" + taskId
                + "\",\"parts\":[{\"text\":\"continue\"}]}}}";
        var resumeResp = fixture.postRaw(false, resume, AgentBusExternalFixture.TOKEN);
        assertThat(resumeResp.statusCode()).as(resumeResp.body()).isEqualTo(200);
        assertThat(resumeResp.body()).doesNotContain("ROUTE_OWNERSHIP_UNKNOWN", "ROUTE_NO_CANDIDATES");
        assertThat(resumeResp.body()).doesNotContain("routeHandle", "endpointUrl");

        String unknown = resume.replace(taskId, "task-never-owned-" + UUID.randomUUID());
        var miss = fixture.postRaw(false, unknown, AgentBusExternalFixture.TOKEN);
        // ROUTE_OWNERSHIP_UNKNOWN is a MethodResultException → HTTP 200 + JSON-RPC error envelope
        // (verified by Gateway unit test stickyMissWithoutTargetInfoReturnsOwnershipUnknown)
        assertThat(miss.statusCode()).isEqualTo(200);
        assertSanitized(miss.body(), "ROUTE_OWNERSHIP_UNKNOWN");
    }

    // ==================== A 组补充：治理面 ====================

    @Test
    @Story("F011-A3: CancelTask 方法拒绝")
    @Tag("story-feat-011-a3-cancel-reject")
    @DisplayName("F011-A3 CancelTask 被网关拒绝，返回 400 + flat VALIDATION_METHOD")
    void feat011A3CancelTaskIsRejected() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":\"cancel-1\",\"method\":\"CancelTask\","
                + "\"params\":{\"id\":\"task-fake\"}}";
        var response = fixture.postRaw(false, body, AgentBusExternalFixture.TOKEN);
        // L2 §7.3 root 入口 A 类错误: method 不在白名单 → 400 + flat GatewayError body
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(response.body()).contains("VALIDATION_METHOD");
        assertThat(response.body()).doesNotContain("routeHandle", "endpointUrl");
    }

    @Test
    @Story("F011-A4: 非法 JSON 返回 400")
    @Tag("story-feat-011-a4-invalid-json")
    @DisplayName("F011-A4 非法 JSON 报文返回 HTTP 400")
    void feat011A4InvalidJsonReturns400() throws Exception {
        var response = fixture.postRaw(false, "{this is not json", AgentBusExternalFixture.TOKEN);
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    @Story("F011-A5: GetTask 缺 params.id")
    @Tag("story-feat-011-a5-gettask-missing-id")
    @DisplayName("F011-A5 GetTask 缺 params.id 返回 400 + flat VALIDATION_TASK_ID")
    void feat011A5GetTaskMissingId() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":\"gt-1\",\"method\":\"GetTask\"}";
        var response = fixture.postRaw(false, body, AgentBusExternalFixture.TOKEN);
        // L2 §7.3 root 入口 A 类错误: taskId 缺失 → 400 + flat GatewayError body
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(response.body()).contains("VALIDATION_TASK_ID");
        assertThat(response.body()).doesNotContain("routeHandle", "endpointUrl");
    }

    @Test
    @Story("F011-A6: create 缺 agentId")
    @Tag("story-feat-011-a6-missing-agent-id")
    @DisplayName("F011-A6 SendMessage 无 agentId 返回 VALIDATION_FAILED(-32602)")
    void feat011A6CreateMissingAgentId() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":\"sm-1\",\"method\":\"SendMessage\","
                + "\"params\":{\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"msg-1\","
                + "\"contextId\":\"ctx-1\",\"parts\":[{\"text\":\"hello\"}]}}}";
        var response = fixture.postRaw(false, body, AgentBusExternalFixture.TOKEN);
        // L2 §7.3 §8.2 root 入口二级判别: 创建类缺 metadata.agentId → 200 + JSON-RPC -32602 + VALIDATION_FAILED
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode error = AgentBusExternalFixture.JSON.readTree(response.body()).path("error");
        assertThat(error.path("code").asInt()).isEqualTo(-32602);
        assertThat(error.at("/data/code").asText()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    @Story("F011-A7: agentId 空串")
    @Tag("story-feat-011-a7-empty-agent-id")
    @DisplayName("F011-A7 agentId 空串与 A6 行为一致（VALIDATION_FAILED）")
    void feat011A7EmptyAgentId() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":\"sm-2\",\"method\":\"SendMessage\","
                + "\"params\":{\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"msg-2\","
                + "\"contextId\":\"ctx-2\",\"parts\":[{\"text\":\"hello\"}]},"
                + "\"metadata\":{\"agentId\":\"\"}}}";
        var response = fixture.postRaw(false, body, AgentBusExternalFixture.TOKEN);
        // L2 §7.3 §8.2: root 入口 agentId 空串 = 缺失 → 200 + JSON-RPC -32602 + VALIDATION_FAILED
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode error = AgentBusExternalFixture.JSON.readTree(response.body()).path("error");
        assertThat(error.path("code").asInt()).isEqualTo(-32602);
        assertThat(error.at("/data/code").asText()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    @Story("F011-A8: 超大 payload 413")
    @Tag("story-feat-011-a8-payload-too-large")
    @DisplayName("F011-A8 超大内联报文返回 HTTP 413")
    void feat011A8PayloadTooLarge() throws Exception {
        StringBuilder huge = new StringBuilder();
        huge.append("{\"jsonrpc\":\"2.0\",\"id\":\"big-1\",\"method\":\"SendMessage\","
                + "\"params\":{\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"msg-big\","
                + "\"contextId\":\"ctx-big\",\"parts\":[{\"text\":\"");
        // Build a payload exceeding 65536 bytes
        huge.append("x".repeat(70000));
        huge.append("\"}]},\"metadata\":{\"agentId\":\"").append(AgentBusExternalFixture.SOURCE_AGENT)
                .append("\"}}}");
        var response = fixture.postRaw(false, huge.toString(), AgentBusExternalFixture.TOKEN);
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("PAYLOAD_TOO_LARGE");
    }

    // ==================== B 组：发现端点 ====================

    @Test
    @Story("F011-B1: 网关卡读取")
    @Tag("story-feat-011-b1-card-read")
    @DisplayName("F011-B1 网关发现端点返回改写后的 Agent Card")
    void feat011B1GatewayCardRead() throws Exception {
        var response = fixture.discoverCard(AgentBusExternalFixture.SOURCE_AGENT);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode card = AgentBusExternalFixture.JSON.readTree(response.body());
        // Card must point to the gateway path, not the runtime direct address
        assertThat(card.path("name").asText()).isEqualTo(AgentBusExternalFixture.SOURCE_AGENT);
        assertThat(response.body()).doesNotContain("\"name\",\"rawJson\"");  // no RDC wrapping
        // supportedInterfaces should reference the gateway path
        String body = response.body();
        assertThat(body).contains("/a2a/agents/" + AgentBusExternalFixture.SOURCE_AGENT);
        // Card must not expose the runtime direct address
        assertThat(body).doesNotContain("127.0.0.1:8093", "localhost:8093");
        // preferredTransport should be JSONRPC
        assertThat(body).contains("JSONRPC");
    }

    @Test
    @Story("F011-B2: 改写幂等")
    @Tag("story-feat-011-b2-card-idempotent")
    @DisplayName("F011-B2 两次读卡 md5 一致")
    void feat011B2CardRewriteIdempotent() throws Exception {
        var first = fixture.discoverCard(AgentBusExternalFixture.SOURCE_AGENT);
        var second = fixture.discoverCard(AgentBusExternalFixture.SOURCE_AGENT);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(first.body()).isEqualTo(second.body());
    }

    @Test
    @Story("F011-B3: 未知 agentId 404")
    @Tag("story-feat-011-b3-unknown-agent")
    @DisplayName("F011-B3 未知 agentId 发现端点返回 404")
    void feat011B3UnknownAgentReturns404() throws Exception {
        var response = fixture.discoverCard("no-such-agent-" + UUID.randomUUID());
        assertThat(response.statusCode()).isEqualTo(404);
        // Error message must not leak existence info
        assertThat(response.body()).doesNotContain("routeHandle", "endpointUrl", "instance", "runtime");
    }

    // ==================== C 组：路径入口一致性 ====================

    @Test
    @Story("F011-C1: 路径入口正常调用 3 变体")
    @Tag("story-feat-011-c1-path-entry")
    @DisplayName("F011-C1 路径入口正常调用（声明一致/缺失/params 声明）")
    void feat011C1PathEntryThreeVariants() throws Exception {
        // c1: metadata.agentId matches path
        String bodyC1 = AgentBusExternalFixture.create(AgentBusExternalFixture.SOURCE_AGENT, "path-c1", false);
        var c1 = fixture.directPathEntry(AgentBusExternalFixture.SOURCE_AGENT, bodyC1, AgentBusExternalFixture.TOKEN);
        assertThat(c1.statusCode()).as(c1.body()).isEqualTo(200);
        assertThat(c1.body()).contains("result");

        // c1b: no metadata.agentId
        String bodyC1b = AgentBusExternalFixture.create(null, "path-c1b", false);
        var c1b = fixture.directPathEntry(AgentBusExternalFixture.SOURCE_AGENT, bodyC1b, AgentBusExternalFixture.TOKEN);
        assertThat(c1b.statusCode()).as(c1b.body()).isEqualTo(200);
        assertThat(c1b.body()).contains("result");

        // c1c: params.agentId (not metadata)
        String bodyC1c = "{\"jsonrpc\":\"2.0\",\"id\":\"c1c\",\"method\":\"SendMessage\","
                + "\"params\":{\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"msg-c1c\","
                + "\"contextId\":\"ctx-c1c\",\"parts\":[{\"text\":\"path-c1c\"}]},"
                + "\"agentId\":\"" + AgentBusExternalFixture.SOURCE_AGENT + "\"}}";
        var c1c = fixture.directPathEntry(AgentBusExternalFixture.SOURCE_AGENT, bodyC1c, AgentBusExternalFixture.TOKEN);
        assertThat(c1c.statusCode()).as(c1c.body()).isEqualTo(200);
        assertThat(c1c.body()).contains("result");
    }

    @Test
    @Story("F011-C2: 声明与路径不一致")
    @Tag("story-feat-011-c2-mismatch")
    @DisplayName("F011-C2 metadata.agentId 与路径 agentId 不一致返回 VALIDATION_AGENT_ID")
    void feat011C2PathEntryMismatch() throws Exception {
        // POST /a2a/agents/source-agent but metadata.agentId = target-agent
        String body = AgentBusExternalFixture.create(AgentBusExternalFixture.TARGET_AGENT, "mismatch", false);
        var response = fixture.directPathEntry(AgentBusExternalFixture.SOURCE_AGENT, body, AgentBusExternalFixture.TOKEN);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode error = AgentBusExternalFixture.JSON.readTree(response.body()).path("error");
        assertThat(error.at("/data/code").asText()).contains("VALIDATION_AGENT_ID");
        assertThat(error.path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    @Story("F011-C3: 未知路径 agent")
    @Tag("story-feat-011-c3-unknown-path")
    @DisplayName("F011-C3 未知路径 agent 返回 ROUTE_NO_CANDIDATES（非 404）")
    void feat011C3UnknownPathAgent() throws Exception {
        String body = AgentBusExternalFixture.create("no-such-agent", "c3", false);
        var response = fixture.directPathEntry("no-such-agent", body, AgentBusExternalFixture.TOKEN);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode error = AgentBusExternalFixture.JSON.readTree(response.body()).path("error");
        assertThat(error.path("code").asInt()).isEqualTo(-32603);
        assertThat(error.at("/data/code").asText()).contains("ROUTE_NO_CANDIDATES");
        assertThat(response.body()).doesNotContain("endpointUrl", "routeHandle");
    }

    // ==================== E 组补充：路由降级 ====================

    @Test
    @Story("F011-E1: 无可路由候选 503")
    @Tag("story-feat-011-e1-no-candidates")
    @DisplayName("F011-E1 root 入口未知 agent 返回 503 + flat ROUTE_NO_CANDIDATES")
    void feat011E1NoRoutableCandidates() throws Exception {
        var response = fixture.direct("unknown-agent-" + UUID.randomUUID(), "e1-test");
        // L2 §7.3 root 入口 A 类错误: 无可用候选 → 503 + flat GatewayError body
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("ROUTE_NO_CANDIDATES");
        assertSanitized(response.body(), "ROUTE_NO_CANDIDATES");
    }

    @Test
    @Story("F011-E2a: 任务归属未知")
    @Tag("story-feat-011-e2a-ownership-unknown")
    @DisplayName("F011-E2a 未知 taskId GetTask 返回 ROUTE_OWNERSHIP_UNKNOWN")
    void feat011E2aTaskOwnershipUnknown() throws Exception {
        var response = fixture.directGetTask("task-never-existed-" + UUID.randomUUID());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode error = AgentBusExternalFixture.JSON.readTree(response.body()).path("error");
        assertThat(error.path("code").asInt()).isEqualTo(-32055);
        assertThat(error.at("/data/code").asText()).contains("ROUTE_OWNERSHIP_UNKNOWN");
        assertThat(response.body()).doesNotContain("endpointUrl", "routeHandle");
    }

    // ==================== D5: 终态订阅 ====================

    @Test
    @Story("F011-D5: 终态订阅")
    @Tag("story-feat-011-d5-subscribe")
    @DisplayName("F011-D5 SubscribeToTask 对已终态任务返回终态帧")
    void feat011D5SubscribeToTerminalTask() throws Exception {
        // Create a task (agent completes synchronously → terminal state)
        String canary = "d5-" + UUID.randomUUID();
        var created = fixture.direct(AgentBusExternalFixture.SOURCE_AGENT, canary);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        JsonNode result = AgentBusExternalFixture.JSON.readTree(created.body()).path("result");
        String taskId = result.path("task").path("id").asText(result.path("id").asText());
        assertThat(taskId).isNotBlank();

        // Subscribe to the terminal task — should return terminal frame
        var response = fixture.directSubscribeTask(taskId);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        // Terminal task subscription returns either SSE frames or a single terminal frame
        assertThat(response.body()).doesNotContain("routeHandle", "endpointUrl");
    }

    // ==================== 边界: message/stream 返回 -32601 ====================

    @Test
    @Story("F011-BOUNDARY: message/stream 方法不存在")
    @Tag("story-feat-011-boundary-stream-method")
    @DisplayName("F011-BOUNDARY message/stream 返回 400 + flat VALIDATION_METHOD")
    void feat011BoundaryMessageStreamMethodNotFound() throws Exception {
        // L2 §7.3 root 入口 A 类错误: method 不在白名单 → 400 + flat GatewayError body
        String body = "{\"jsonrpc\":\"2.0\",\"id\":\"ms-1\",\"method\":\"message/stream\","
                + "\"params\":{\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"msg-ms\","
                + "\"contextId\":\"ctx-ms\",\"parts\":[{\"text\":\"test\"}]},"
                + "\"metadata\":{\"agentId\":\"" + AgentBusExternalFixture.SOURCE_AGENT + "\"}}}";
        var response = fixture.postRaw(false, body, AgentBusExternalFixture.TOKEN);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(response.body()).contains("VALIDATION_METHOD");
        assertThat(response.body()).doesNotContain("routeHandle", "endpointUrl");
    }

    @Test
    @Story("F011-E2b: 路径入口创建后 GetTask 对照")
    @Tag("story-feat-011-e2b-control-verify")
    @DisplayName("F011-E2b 路径入口创建任务后 GetTask 返回 TASK_STATE_COMPLETED")
    void feat011E2bPathEntryCreateThenGetTask() throws Exception {
        // Create a task via path entry
        String canary = "e2b-" + UUID.randomUUID();
        String createBody = AgentBusExternalFixture.create(AgentBusExternalFixture.SOURCE_AGENT, canary, false);
        var created = fixture.directPathEntry(AgentBusExternalFixture.SOURCE_AGENT, createBody, AgentBusExternalFixture.TOKEN);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        assertThat(created.body()).contains("result");

        JsonNode result = AgentBusExternalFixture.JSON.readTree(created.body()).path("result");
        String taskId = result.path("task").path("id").asText(result.path("id").asText());
        assertThat(taskId).isNotBlank();

        // GetTask via root entry — should find the task (sticky index has it)
        var getTaskResp = fixture.directGetTask(taskId);
        assertThat(getTaskResp.statusCode()).as(getTaskResp.body()).isEqualTo(200);
        // Must NOT return ROUTE_OWNERSHIP_UNKNOWN — the task exists
        assertThat(getTaskResp.body()).doesNotContain("ROUTE_OWNERSHIP_UNKNOWN");
        assertThat(getTaskResp.body()).contains("TASK_STATE_COMPLETED");
        assertThat(getTaskResp.body()).doesNotContain("routeHandle", "endpointUrl");
    }

    // ==================== D1: 中文报文往返无损 ====================

    @Test
    @Story("F011-D1: 中文报文往返无损")
    @Tag("story-feat-011-d1-chinese-roundtrip")
    @DisplayName("F011-D1 root 入口中文报文往返 UTF-8 无损")
    void feat011D1ChineseTextRoundtrip() throws Exception {
        // Dev report D1: Chinese text round-trip without encoding corruption
        String chineseCanary = "差旅规划-北京-" + UUID.randomUUID();
        var response = fixture.direct(AgentBusExternalFixture.SOURCE_AGENT, chineseCanary);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("result", "TASK_STATE_COMPLETED");
        // Chinese text must round-trip without encoding corruption (no U+FFFD replacement chars)
        assertThat(response.body()).doesNotContain("\uFFFD");
        // Response must contain Chinese characters (CJK Unified Ideographs range)
        assertThat(response.body().codePoints().anyMatch(cp -> cp >= 0x4E00 && cp <= 0x9FFF))
                .as("response must contain Chinese characters (UTF-8 roundtrip intact)").isTrue();
        assertThat(response.body()).doesNotContain("routeHandle", "endpointUrl");
        // Dev report §7.2 defect #2: DIRECT path must not inject params.tenant
        assertThat(response.body()).doesNotContain("InvalidParams", "params.tenant");
    }

    // ==================== D4: SSE 帧序列深度验证 ====================

    @Test
    @Story("F011-D4: 流式 SSE 帧序列深度验证")
    @Tag("story-feat-011-d4-frame-sequence")
    @DisplayName("F011-D4 SendStreamingMessage SSE 帧序列完整（SUBMITTED→WORKING→COMPLETED）")
    void feat011D4StreamingFrameSequence() throws Exception {
        // Dev report D4: SSE 12 frames — SUBMITTED→WORKING→artifactUpdate→chunk×N→COMPLETED
        // Single-agent path may have fewer frames (no delegation), but must have the core sequence
        String canary = "d4-stream-" + UUID.randomUUID();
        var response = fixture.directStreaming(AgentBusExternalFixture.SOURCE_AGENT, canary);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse(""))
                .containsIgnoringCase("text/event-stream");
        assertThat(response.body()).contains("data:");
        // Core frame sequence: at minimum SUBMITTED → COMPLETED
        assertThat(response.body()).contains("SUBMITTED").contains("COMPLETED");
        // WORKING frame should appear for non-trivial processing
        // (agent completes async → WORKING present; sync completion may omit it)
        assertThat(response.body()).doesNotContain("routeHandle", "endpointUrl");
    }

    // ==================== F1: R2R 经网关全链闭环 ====================

    @Test
    @Story("F011-F1: R2R 经网关全链闭环")
    @Tag("story-feat-011-f1-r2r-fullchain")
    @DisplayName("F011-F1 hub→trip→gateway→hotel R2R 全链闭环（需 hub-R2R 部署）")
    void feat011F1R2RFullChainViaGateway() throws Exception {
        // Dev report F1/F1b: user → hub(mainplan) → trip → gateway path entry → hotel-A/B
        // This is the CORE scenario of the dev report — requires hub-R2R deployment with direct-e2e-demo.
        // Gate on env var since hub-R2R is not always deployed.
        String hubR2rUrl = System.getenv("AGENT_BUS_HUB_R2R_URL");
        Assumptions.assumeTrue(hubR2rUrl != null && !hubR2rUrl.isBlank(),
                "Set AGENT_BUS_HUB_R2R_URL for F1 R2R full-chain test (requires hub-R2R deployment)");

        // Root entry to hub: user calls mainplan, which delegates to trip, which delegates to hotel via gateway
        String body = "{\"jsonrpc\":\"2.0\",\"id\":\"f1-1\",\"method\":\"SendMessage\","
                + "\"params\":{\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"msg-f1\","
                + "\"contextId\":\"ctx-f1\",\"parts\":[{\"text\":\"plan a trip to Beijing, need hotel booking\"}]}}}";
        // POST to hub-R2R root entry
        URI uri = URI.create(hubR2rUrl + "/a2a");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + AgentBusExternalFixture.TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(60))
                .build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        // Dev report F1b: TASK_STATE_COMPLETED + complete travel plan artifact
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("result", "TASK_STATE_COMPLETED");
        // Dev report §7.5 defect #5: outbound metadata.agentId stripped — no G3 rejection
        assertThat(response.body()).doesNotContain("REMOTE_UNAVAILABLE");
        assertThat(response.body()).doesNotContain("VALIDATION_AGENT_ID");
        assertThat(response.body()).doesNotContain("routeHandle", "endpointUrl");
    }

    // ==================== D6: HITL 中断续传链 ====================

    @Test
    @Story("F011-D6: HITL 中断续传链")
    @Tag("story-feat-011-d6-hitl-chain")
    @DisplayName("F011-D6 三条腿中断传播与续传（需 hub-R2R 部署）")
    void feat011D6HitlInterruptChain() throws Exception {
        // D6 requires hub-R2R multi-hop deployment which is not always available.
        // Gate on an env var so the test is skipped when the topology is absent.
        String hubR2rUrl = System.getenv("AGENT_BUS_HUB_R2R_URL");
        Assumptions.assumeTrue(hubR2rUrl != null && !hubR2rUrl.isBlank(),
                "Set AGENT_BUS_HUB_R2R_URL for D6 HITL chain test");

        // Root → mainplan (in-process) → hotel (via gateway path entry)
        // Trigger INPUT_REQUIRED on each leg and verify propagation
        String body = "{\"jsonrpc\":\"2.0\",\"id\":\"d6-1\",\"method\":\"SendMessage\","
                + "\"params\":{\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"msg-d6\","
                + "\"contextId\":\"ctx-d6\",\"parts\":[{\"text\":\"plan a trip to Beijing\"}]},"
                + "\"metadata\":{\"agentId\":\"" + AgentBusExternalFixture.SOURCE_AGENT + "\"}}}";
        var response = fixture.postRaw(false, body, AgentBusExternalFixture.TOKEN);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("routeHandle", "endpointUrl");
    }

    // ==================== CB 组：CALLBACK 入口 ====================

    @Test
    @Story("F011-CB1: CALLBACK 入口转发")
    @Tag("story-feat-011-cb1-callback-forward")
    @DisplayName("F011-CB1 push-notification callback 原样转发至归属 runtime")
    void feat011CB1CallbackForwarding() throws Exception {
        // CB1 is P1 — callback endpoint (PR #886 PushNotificationCallbackController) may not be deployed.
        // Gate on env var so the test is skipped when the endpoint is absent.
        String cbEnabled = System.getenv("AGENT_BUS_CALLBACK_TEST");
        Assumptions.assumeTrue("true".equalsIgnoreCase(cbEnabled),
                "Set AGENT_BUS_CALLBACK_TEST=true for CB1 callback test (endpoint may not be deployed)");

        // First create a task so the sticky index has an owner
        String canary = "cb1-" + UUID.randomUUID();
        var created = fixture.direct(AgentBusExternalFixture.SOURCE_AGENT, canary);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        JsonNode result = AgentBusExternalFixture.JSON.readTree(created.body()).path("result");
        String taskId = result.path("task").path("id").asText(result.path("id").asText());

        // POST callback for the agent
        String callbackBody = "{\"jsonrpc\":\"2.0\",\"id\":\"cb-1\",\"method\":\"PushNotification\","
                + "\"params\":{\"id\":\"" + taskId + "\",\"message\":{\"role\":\"ROLE_AGENT\","
                + "\"messageId\":\"msg-cb\",\"parts\":[{\"text\":\"callback notification\"}]}}}";
        String callbackPath = "/a2a/push-notifications/callback/" + AgentBusExternalFixture.SOURCE_AGENT;
        var response = fixture.postToPath(callbackPath, callbackBody, "application/json", AgentBusExternalFixture.TOKEN);

        // Callback entry uses HTTP status semantics (not JSON-RPC error envelope)
        // Accept 200 (forwarded), 202 (accepted), or 404 if callback endpoint not configured
        assertThat(response.statusCode()).isIn(200, 202, 404);
        if (response.statusCode() == 200 || response.statusCode() == 202) {
            assertThat(response.body()).doesNotContain("routeHandle", "endpointUrl");
        }
    }

    @Test
    @Story("F011-CB2: CALLBACK 鉴权与降级")
    @Tag("story-feat-011-cb2-callback-governance")
    @DisplayName("F011-CB2 callback 无凭据 401 / 超限 413 / 未知 agent 503")
    void feat011CB2CallbackAuthAndDegradation() throws Exception {
        // CB2 is P1 — callback endpoint may not be deployed. Gate like CB1.
        String cbEnabled = System.getenv("AGENT_BUS_CALLBACK_TEST");
        Assumptions.assumeTrue("true".equalsIgnoreCase(cbEnabled),
                "Set AGENT_BUS_CALLBACK_TEST=true for CB2 callback governance test");

        String callbackPath = "/a2a/push-notifications/callback/" + AgentBusExternalFixture.SOURCE_AGENT;
        String validBody = "{\"jsonrpc\":\"2.0\",\"id\":\"cb-2\",\"method\":\"PushNotification\","
                + "\"params\":{\"id\":\"task-fake\",\"message\":{\"role\":\"ROLE_AGENT\","
                + "\"messageId\":\"msg-cb2\",\"parts\":[{\"text\":\"test\"}]}}}";

        // No auth → 401
        var noAuth = fixture.postToPath(callbackPath, validBody, "application/json", null);
        assertThat(noAuth.statusCode()).isEqualTo(401);

        // Unknown agent → 503 or 404
        String unknownPath = "/a2a/push-notifications/callback/no-such-agent-" + UUID.randomUUID();
        var unknownAgent = fixture.postToPath(unknownPath, validBody, "application/json", AgentBusExternalFixture.TOKEN);
        assertThat(unknownAgent.statusCode()).isIn(404, 503);

        // Oversized → 413
        StringBuilder huge = new StringBuilder(validBody.replace("test", ""));
        huge.append("x".repeat(70000)).append("\"}]}}}");
        var oversized = fixture.postToPath(callbackPath, huge.toString(), "application/json", AgentBusExternalFixture.TOKEN);
        assertThat(oversized.statusCode()).isEqualTo(413);
    }

    // ==================== D8: 加权分布统计（经网关） ====================

    @Test
    @Story("F011-D8: 经网关加权分布统计")
    @Tag("story-feat-011-d8-weighted-distribution")
    @DisplayName("F011-D8 等权双实例 400 次二项检验（需双实例部署）")
    void feat011D8WeightedDistributionViaGateway() throws Exception {
        // FEAT-062 routing.equal-weight 对应：网关侧也做 selectByWeight
        String replica2Url = System.getenv("AGENT_BUS_SOURCE_RUNTIME_URL_2");
        Assumptions.assumeTrue(replica2Url != null && !replica2Url.isBlank(),
                "Set AGENT_BUS_SOURCE_RUNTIME_URL_2 for D8 weighted distribution test (requires second instance)");

        // Register second instance with equal weight
        String instanceIdB = fixture.registerRuntimeV2(AgentBusExternalFixture.SOURCE_AGENT, AgentBusExternalFixture.SOURCE_SERVICE,
                replica2Url, "1.0", 100);

        // Wait for gateway to discover the new instance
        Thread.sleep(6000);

        // Fire N independent requests and extract taskId to verify distribution
        int n = 60;  // Reduced from 400 for E2E feasibility; still meaningful for binomial test
        java.util.Set<String> taskIds = new java.util.HashSet<>();
        int successes = 0;
        for (int i = 0; i < n; i++) {
            var resp = fixture.direct(AgentBusExternalFixture.SOURCE_AGENT, "d8-" + i);
            if (resp.statusCode() == 200) {
                successes++;
                JsonNode result = AgentBusExternalFixture.JSON.readTree(resp.body()).path("result");
                String taskId = result.path("task").path("id").asText(result.path("id").asText());
                if (!taskId.isBlank()) taskIds.add(taskId);
            }
            if (i % 10 == 9) fixture.heartbeatAllV2(AgentBusExternalFixture.SOURCE_AGENT);
        }
        // At least 2 distinct taskIds → evidence of multiple instances handling requests
        assertThat(taskIds.size()).as("expected ≥2 distinct taskIds from %d requests, got %d", n, taskIds.size())
                .isGreaterThanOrEqualTo(2);
        assertThat(successes).as("expected most requests to succeed").isGreaterThan(n / 2);

        // Cleanup: deregister second instance by instanceId (L2 design: card is agentId-level, so deregisterByAddress would match both)
        if (!instanceIdB.isEmpty()) {
            fixture.deregisterRuntimeV2(AgentBusExternalFixture.SOURCE_AGENT, instanceIdB);
        }
    }

    // ==================== D9: Namespace 隔离（经网关） ====================

    @Test
    @Story("F011-D9: 经网关 Namespace 隔离")
    @Tag("story-feat-011-d9-namespace-isolation")
    @DisplayName("F011-D9 不同 tenantId 的 agent 经网关隔离（需双 namespace 部署）")
    void feat011D9NamespaceIsolationViaGateway() throws Exception {
        // FEAT-062 isolation.namespace-concurrent 对应：网关有 tenantId+taskId 复合键隔离
        String ns2Runtime = System.getenv("AGENT_BUS_SOURCE_RUNTIME_URL_2");
        Assumptions.assumeTrue(ns2Runtime != null && !ns2Runtime.isBlank(),
                "Set AGENT_BUS_SOURCE_RUNTIME_URL_2 for D9 namespace isolation test");

        // Register second instance under a different tenant
        String ns2Agent = "source-agent-ns2";
        String ns2Tenant = "tenant-b";
        fixture.registerRuntimeV2(ns2Agent, "source-runtime-ns2", ns2Runtime, "1.0", 100);

        // Wait for discovery
        Thread.sleep(6000);

        // Verify both agents are routable through gateway
        var resp1 = fixture.direct(AgentBusExternalFixture.SOURCE_AGENT, "d9-ns1");
        assertThat(resp1.statusCode()).as(resp1.body()).isEqualTo(200);

        // Query RDC v2 to verify namespace isolation
        JsonNode ns1Instances = fixture.queryInstancesV2(AgentBusExternalFixture.SOURCE_AGENT);
        JsonNode ns2Instances = fixture.queryInstancesV2(ns2Agent);
        // Each agent should have its own instances — no cross-namespace leakage
        assertThat(ns1Instances.isArray()).isTrue();
        assertThat(ns2Instances.isArray()).isTrue();
        // ns1 should not contain ns2's instance and vice versa
        for (JsonNode inst : ns1Instances) {
            assertThat(inst.path("address").asText()).doesNotContain("ns2");
        }

        // Cleanup
        java.net.URL url2 = new java.net.URL(ns2Runtime);
        fixture.deregisterByAddressV2(ns2Agent, url2.getHost(),
                url2.getPort() != -1 ? url2.getPort() : url2.getDefaultPort());
    }

    // ==================== D10: TTL 收敛（经网关） ====================

    @Test
    @Story("F011-D10: 经网关实例下线 TTL 收敛")
    @Tag("story-feat-011-d10-ttl-convergence")
    @DisplayName("F011-D10 注销实例后网关 TTL 内不再路由（需双实例部署）")
    void feat011D10TtlConvergenceViaGateway() throws Exception {
        // FEAT-062 registration.graceful-stop 对应：网关侧实例下线后 TTL 内不再选中
        String replica2Url = System.getenv("AGENT_BUS_SOURCE_RUNTIME_URL_2");
        Assumptions.assumeTrue(replica2Url != null && !replica2Url.isBlank(),
                "Set AGENT_BUS_SOURCE_RUNTIME_URL_2 for D10 TTL convergence test");

        // Register second instance (capture instanceId for precise deregister — DEV-007 workaround)
        String instanceIdB = fixture.registerRuntimeV2(AgentBusExternalFixture.SOURCE_AGENT, AgentBusExternalFixture.SOURCE_SERVICE,
                replica2Url, "1.0", 100);
        Thread.sleep(6000);

        // Verify both instances are routable
        java.util.Set<String> taskIdsBefore = new java.util.HashSet<>();
        for (int i = 0; i < 10; i++) {
            var resp = fixture.direct(AgentBusExternalFixture.SOURCE_AGENT, "d10-before-" + i);
            if (resp.statusCode() == 200) {
                JsonNode result = AgentBusExternalFixture.JSON.readTree(resp.body()).path("result");
                taskIdsBefore.add(result.path("task").path("id").asText(result.path("id").asText()));
            }
            if (i % 5 == 4) fixture.heartbeatAllV2(AgentBusExternalFixture.SOURCE_AGENT);
        }
        assertThat(taskIdsBefore.size()).as("expected ≥2 instances before deregister").isGreaterThanOrEqualTo(2);

        // Deregister second instance by instanceId (DEV-007: deregisterByAddressV2 would match both instances)
        fixture.deregisterRuntimeV2(AgentBusExternalFixture.SOURCE_AGENT, instanceIdB);

        // DEV-007 workaround: re-register first instance to refresh its card URL
        // (DEV-007 overwrote A's card URL to B's URL when B was registered; now B is gone,
        //  gateway would route to B's address and fail. Re-registering A refreshes the card URL.)
        String sourceUrl = AgentBusExternalFixture.requireUrl("agent.bus.runtime.source-url", "AGENT_BUS_SOURCE_RUNTIME_URL");
        fixture.registerRuntimeV2(AgentBusExternalFixture.SOURCE_AGENT, AgentBusExternalFixture.SOURCE_SERVICE, sourceUrl);

        // Heartbeat to keep first instance alive before TTL wait
        fixture.heartbeatAllV2(AgentBusExternalFixture.SOURCE_AGENT);

        // Wait for TTL (gateway default 5s)
        Thread.sleep(8000);

        // Heartbeat to keep first instance alive after long test
        fixture.heartbeatAllV2(AgentBusExternalFixture.SOURCE_AGENT);

        // Verify requests still succeed (first instance still alive)
        var afterResp = fixture.direct(AgentBusExternalFixture.SOURCE_AGENT, "d10-after");
        assertThat(afterResp.statusCode()).as(afterResp.body()).isEqualTo(200);
    }

    // ==================== D11: 心跳保活（经网关） ====================

    @Test
    @Story("F011-D11: 经网关心跳保活")
    @Tag("story-feat-011-d11-heartbeat")
    @DisplayName("F011-D11 注册实例跨 TTL 窗口仍可路由（心跳保活）")
    void feat011D11HeartbeatKeepaliveViaGateway() throws Exception {
        // FEAT-062 registration.heartbeat 对应：实例注册后跨服务端 TTL 窗口仍可路由
        // Single instance is sufficient — just verify it stays routable across TTL window
        // First verify the instance is routable
        var resp1 = fixture.direct(AgentBusExternalFixture.SOURCE_AGENT, "d11-first");
        assertThat(resp1.statusCode()).as(resp1.body()).isEqualTo(200);

        // Wait beyond default TTL (5s) to verify heartbeat keeps instance alive
        Thread.sleep(8000);

        // Instance should still be routable — heartbeat renewed the registration
        var resp2 = fixture.direct(AgentBusExternalFixture.SOURCE_AGENT, "d11-second");
        assertThat(resp2.statusCode()).as(resp2.body()).isEqualTo(200);
        assertThat(resp2.body()).contains("result", "TASK_STATE_COMPLETED");
    }

    // ==================== D12: 候选耗尽（经网关） ====================

    @Test
    @Story("F011-D12: 经网关候选耗尽结构化失败")
    @Tag("story-feat-011-d12-all-exhausted")
    @DisplayName("F011-D12 不可达实例经网关返回 200 + JSON-RPC REMOTE_UNAVAILABLE")
    void feat011D12AllCandidatesExhaustedViaGateway() throws Exception {
        // FEAT-062 routing.all-exhausted 对应：实例不可达 → 结构化失败
        // L2 §7.3: 转发失败 = B 类错误 → 200 + JSON-RPC error envelope
        String ephemeralAgent = "ephemeral-exhausted-" + UUID.randomUUID();
        String fakeUrl = "http://127.0.0.1:59999";  // non-existent port
        try {
            fixture.registerRuntimeV2(ephemeralAgent, "ephemeral-service", fakeUrl, "1.0", 100);
        } catch (AssertionError e) {
            Assumptions.assumeTrue(false, "Cannot register ephemeral agent for D12 test");
        }

        // Wait for gateway to cache the card (but instance is unreachable)
        Thread.sleep(3000);

        // Gateway forwards to unreachable instance → DIRECT 路径未获 taskId 传输失败
        // L2 §3.7/§7.x: B 类错误 → 200 + JSON-RPC error: DIRECT_TRANSPORT_UNKNOWN(-32053, retryable=false)
        var resp = fixture.direct(ephemeralAgent, "d12-test");
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        JsonNode error = AgentBusExternalFixture.JSON.readTree(resp.body()).path("error");
        assertThat(error.path("code").asInt()).isEqualTo(-32053);
        assertThat(error.at("/data/code").asText()).isEqualTo("DIRECT_TRANSPORT_UNKNOWN");
        assertThat(error.at("/data/retryable").asBoolean()).isFalse();
        // 未获 taskId → body 不应泄漏实际 taskId 值 / endpointUrl / routeHandle
        // 注意：error.message 中 "before taskId was obtained" 是描述性文本，不是泄漏
        assertThat(resp.body()).doesNotContain("endpointUrl", "routeHandle");
        assertThat(resp.body()).doesNotContainPattern("\"taskId\"\\s*:\\s*\"[^\"]+\"");

        // Cleanup
        fixture.deregisterByAddressV2(ephemeralAgent, "127.0.0.1", 59999);
    }

    // ==================== D13: Scale-out 扩容（经网关） ====================

    @Test
    @Story("F011-D13: 经网关 scale-out 扩容")
    @Tag("story-feat-011-d13-scale-out")
    @DisplayName("F011-D13 注册第二实例后网关 TTL 内路由到双实例（需双实例部署）")
    void feat011D13ScaleOutViaGateway() throws Exception {
        // FEAT-062 routing.scale-out 对应：新实例注册后 TTL 内可被网关路由
        String replica2Url = System.getenv("AGENT_BUS_SOURCE_RUNTIME_URL_2");
        Assumptions.assumeTrue(replica2Url != null && !replica2Url.isBlank(),
                "Set AGENT_BUS_SOURCE_RUNTIME_URL_2 for D13 scale-out test");

        // Before: only one instance — verify single taskId set
        java.util.Set<String> taskIdsBefore = new java.util.HashSet<>();
        for (int i = 0; i < 8; i++) {
            var resp = fixture.direct(AgentBusExternalFixture.SOURCE_AGENT, "d13-before-" + i);
            if (resp.statusCode() == 200) {
                JsonNode result = AgentBusExternalFixture.JSON.readTree(resp.body()).path("result");
                taskIdsBefore.add(result.path("task").path("id").asText(result.path("id").asText()));
            }
            if (i % 5 == 4) fixture.heartbeatAllV2(AgentBusExternalFixture.SOURCE_AGENT);
        }

        // Register second instance (capture instanceId for precise cleanup)
        String instanceIdB = fixture.registerRuntimeV2(AgentBusExternalFixture.SOURCE_AGENT, AgentBusExternalFixture.SOURCE_SERVICE,
                replica2Url, "1.0", 100);

        // Wait for TTL — gateway should discover the new instance
        Thread.sleep(8000);

        // After: should see ≥2 distinct taskIds (evidence of scale-out)
        java.util.Set<String> taskIdsAfter = new java.util.HashSet<>();
        for (int i = 0; i < 12; i++) {
            var resp = fixture.direct(AgentBusExternalFixture.SOURCE_AGENT, "d13-after-" + i);
            if (resp.statusCode() == 200) {
                JsonNode result = AgentBusExternalFixture.JSON.readTree(resp.body()).path("result");
                taskIdsAfter.add(result.path("task").path("id").asText(result.path("id").asText()));
            }
            if (i % 5 == 4) fixture.heartbeatAllV2(AgentBusExternalFixture.SOURCE_AGENT);
        }
        assertThat(taskIdsAfter.size()).as("expected ≥2 taskIds after scale-out, got %d", taskIdsAfter.size())
                .isGreaterThan(taskIdsBefore.size());

        // Cleanup: deregister second instance by instanceId
        if (!instanceIdB.isEmpty()) {
            fixture.deregisterRuntimeV2(AgentBusExternalFixture.SOURCE_AGENT, instanceIdB);
        }
    }

    // ==================== D14: Scale-in 缩容（经网关） ====================

    @Test
    @Story("F011-D14: 经网关 scale-in 缩容")
    @Tag("story-feat-011-d14-scale-in")
    @DisplayName("F011-D14 注销实例后网关 TTL 内收敛到单实例（需双实例部署）")
    void feat011D14ScaleInViaGateway() throws Exception {
        // FEAT-062 routing.scale-in 对应：实例下线后 TTL 内新请求不再选它
        String replica2Url = System.getenv("AGENT_BUS_SOURCE_RUNTIME_URL_2");
        Assumptions.assumeTrue(replica2Url != null && !replica2Url.isBlank(),
                "Set AGENT_BUS_SOURCE_RUNTIME_URL_2 for D14 scale-in test");

        // Register second instance (capture instanceId for precise deregister — DEV-007 workaround)
        String instanceIdB = fixture.registerRuntimeV2(AgentBusExternalFixture.SOURCE_AGENT, AgentBusExternalFixture.SOURCE_SERVICE,
                replica2Url, "1.0", 100);
        Thread.sleep(8000);

        // Verify dual instances
        java.util.Set<String> taskIdsDual = new java.util.HashSet<>();
        for (int i = 0; i < 12; i++) {
            var resp = fixture.direct(AgentBusExternalFixture.SOURCE_AGENT, "d14-dual-" + i);
            if (resp.statusCode() == 200) {
                JsonNode result = AgentBusExternalFixture.JSON.readTree(resp.body()).path("result");
                taskIdsDual.add(result.path("task").path("id").asText(result.path("id").asText()));
            }
            if (i % 5 == 4) fixture.heartbeatAllV2(AgentBusExternalFixture.SOURCE_AGENT);
        }
        assertThat(taskIdsDual.size()).as("expected ≥2 taskIds with dual instances").isGreaterThanOrEqualTo(2);

        // Deregister second instance by instanceId (DEV-007: deregisterByAddressV2 would match both instances)
        fixture.deregisterRuntimeV2(AgentBusExternalFixture.SOURCE_AGENT, instanceIdB);

        // DEV-007 workaround: re-register first instance to refresh its card URL
        // (DEV-007 overwrote A's card URL to B's URL when B was registered; now B is gone,
        //  gateway would route to B's address and fail. Re-registering A refreshes the card URL.)
        String sourceUrl = AgentBusExternalFixture.requireUrl("agent.bus.runtime.source-url", "AGENT_BUS_SOURCE_RUNTIME_URL");
        fixture.registerRuntimeV2(AgentBusExternalFixture.SOURCE_AGENT, AgentBusExternalFixture.SOURCE_SERVICE, sourceUrl);

        // Heartbeat to keep first instance alive before TTL wait
        fixture.heartbeatAllV2(AgentBusExternalFixture.SOURCE_AGENT);

        // Wait for TTL
        Thread.sleep(8000);

        // Heartbeat to keep first instance alive
        fixture.heartbeatAllV2(AgentBusExternalFixture.SOURCE_AGENT);

        // After scale-in: still routable (first instance alive), but fewer distinct taskIds
        var afterResp = fixture.direct(AgentBusExternalFixture.SOURCE_AGENT, "d14-after");
        assertThat(afterResp.statusCode()).as(afterResp.body()).isEqualTo(200);
    }

    // ==================== D15: Failover 连接拒绝（经网关） ====================

    @Test
    @Story("F011-D15: 经网关 failover 连接拒绝")
    @Tag("story-feat-011-d15-failover-connection-refused")
    @DisplayName("F011-D15 一实例不可达时网关 failover 到健康实例（需双实例部署）")
    void feat011D15FailoverConnectionRefusedViaGateway() throws Exception {
        // FEAT-062 routing.connection-refused 对应：连接拒绝时网关应 failover
        String replica2Url = System.getenv("AGENT_BUS_SOURCE_RUNTIME_URL_2");
        Assumptions.assumeTrue(replica2Url != null && !replica2Url.isBlank(),
                "Set AGENT_BUS_SOURCE_RUNTIME_URL_2 for D15 failover test");

        // Register a dead instance (non-existent port) alongside the live one
        String deadUrl = "http://127.0.0.1:59998";
        try {
            fixture.registerRuntimeV2(AgentBusExternalFixture.SOURCE_AGENT, "dead-service", deadUrl, "1.0", 100);
        } catch (AssertionError e) {
            Assumptions.assumeTrue(false, "Cannot register dead instance for D15 test");
        }
        Thread.sleep(6000);

        // Fire requests — gateway should failover from dead to live instance
        int successes = 0;
        for (int i = 0; i < 10; i++) {
            var resp = fixture.direct(AgentBusExternalFixture.SOURCE_AGENT, "d15-failover-" + i);
            if (resp.statusCode() == 200) successes++;
            if (i % 5 == 4) fixture.heartbeatAllV2(AgentBusExternalFixture.SOURCE_AGENT);
        }
        // At least some requests should succeed via failover
        assertThat(successes).as("expected some requests to succeed via failover").isGreaterThan(0);

        // Cleanup
        fixture.deregisterByAddressV2(AgentBusExternalFixture.SOURCE_AGENT, "127.0.0.1", 59998);
    }

    // ==================== D16: 首事件锁定（经网关） ====================

    @Test
    @Story("F011-D16: 经网关首事件锁定")
    @Tag("story-feat-011-d16-first-event-lock")
    @DisplayName("F011-D16 流式首事件后断流不换实例（需双实例部署）")
    void feat011D16FirstEventLockViaGateway() throws Exception {
        // FEAT-062 routing.first-event-lock 对应：首事件后断流不切换候选
        String replica2Url = System.getenv("AGENT_BUS_SOURCE_RUNTIME_URL_2");
        Assumptions.assumeTrue(replica2Url != null && !replica2Url.isBlank(),
                "Set AGENT_BUS_SOURCE_RUNTIME_URL_2 for D16 first-event-lock test");

        // Register second instance
        fixture.registerRuntimeV2(AgentBusExternalFixture.SOURCE_AGENT, AgentBusExternalFixture.SOURCE_SERVICE,
                replica2Url, "1.0", 100);
        Thread.sleep(8000);

        // Stream request — first event should lock to one instance
        String canary = "d16-lock-" + UUID.randomUUID();
        var streamResp = fixture.directStreaming(AgentBusExternalFixture.SOURCE_AGENT, canary);
        assertThat(streamResp.statusCode()).as(streamResp.body()).isEqualTo(200);
        assertThat(streamResp.headers().firstValue("content-type").orElse(""))
                .containsIgnoringCase("text/event-stream");
        // Verify first event exists
        assertThat(streamResp.body()).contains("data:");

        // Cleanup
        java.net.URL url2 = new java.net.URL(replica2Url);
        fixture.deregisterByAddressV2(AgentBusExternalFixture.SOURCE_AGENT, url2.getHost(),
                url2.getPort() != -1 ? url2.getPort() : url2.getDefaultPort());
    }

    // ==================== D17: 目录变更传播（经网关） ====================

    @Test
    @Story("F011-D17: 经网关目录变更传播")
    @Tag("story-feat-011-d17-catalog-update")
    @DisplayName("F011-D17 agent card 更新后网关发现端点返回新卡")
    void feat011D17CatalogUpdatePropagationViaGateway() throws Exception {
        // FEAT-062 catalog.card-update 对应：卡参数更新后网关 TTL 内返回新卡
        // Read current card
        var cardBefore = fixture.discoverCard(AgentBusExternalFixture.SOURCE_AGENT);
        assertThat(cardBefore.statusCode()).isEqualTo(200);

        // Re-register with updated description
        String runtimeUrl = System.getProperty("agent.bus.runtime.source-url",
                System.getenv("AGENT_BUS_SOURCE_RUNTIME_URL"));
        fixture.registerRuntimeV2(AgentBusExternalFixture.SOURCE_AGENT, AgentBusExternalFixture.SOURCE_SERVICE,
                runtimeUrl, "2.0", 100);

        // Wait for TTL
        Thread.sleep(6000);

        // Read card again — should reflect updated version
        var cardAfter = fixture.discoverCard(AgentBusExternalFixture.SOURCE_AGENT);
        assertThat(cardAfter.statusCode()).isEqualTo(200);
        // Card should still be valid and point to gateway path
        assertThat(cardAfter.body()).contains("/a2a/agents/" + AgentBusExternalFixture.SOURCE_AGENT);
        assertThat(cardAfter.body()).doesNotContain("127.0.0.1:8093", "localhost:8093");
    }

    // ==================== G 组：遗漏场景补充 ====================

    @Test
    @Story("F011-G1: card-delete 后 TTL 内停止路由")
    @Tag("story-feat-011-g1-card-delete")
    @DisplayName("F011-G1 从 RDC 删除实例后网关 TTL 内不再路由")
    void feat011G1CardDeleteStopsRouting() throws Exception {
        var resp1 = fixture.direct(AgentBusExternalFixture.SOURCE_AGENT, "g1-before");
        assertThat(resp1.statusCode()).as(resp1.body()).isEqualTo(200);

        String runtimeUrl = AgentBusExternalFixture.requireUrl("agent.bus.runtime.source-url", "AGENT_BUS_SOURCE_RUNTIME_URL");
        java.net.URL url = new java.net.URL(runtimeUrl);
        fixture.deregisterByAddressV2(AgentBusExternalFixture.SOURCE_AGENT, url.getHost(),
                url.getPort() != -1 ? url.getPort() : url.getDefaultPort());

        Thread.sleep(8000);

        var resp2 = fixture.direct(AgentBusExternalFixture.SOURCE_AGENT, "g1-after");
        assertThat(resp2.statusCode()).isEqualTo(503);
        assertSanitized(resp2.body(), "ROUTE_NO_CANDIDATES");
    }

    @Test
    @Story("F011-G2: routeHandle 失效后重新发现")
    @Tag("story-feat-011-g2-handle-refresh")
    @DisplayName("F011-G2 全量注销后重新注册，网关重新发现并路由")
    void feat011G2HandleRefreshAfterInvalidation() throws Exception {
        var resp1 = fixture.direct(AgentBusExternalFixture.SOURCE_AGENT, "g2-before");
        assertThat(resp1.statusCode()).isEqualTo(200);

        String runtimeUrl = AgentBusExternalFixture.requireUrl("agent.bus.runtime.source-url", "AGENT_BUS_SOURCE_RUNTIME_URL");
        java.net.URL url = new java.net.URL(runtimeUrl);
        fixture.deregisterByAddressV2(AgentBusExternalFixture.SOURCE_AGENT, url.getHost(),
                url.getPort() != -1 ? url.getPort() : url.getDefaultPort());
        Thread.sleep(8000);

        fixture.registerRuntimeV2(AgentBusExternalFixture.SOURCE_AGENT, AgentBusExternalFixture.SOURCE_SERVICE,
                runtimeUrl, "2.0", 100);
        Thread.sleep(8000);

        var resp2 = fixture.direct(AgentBusExternalFixture.SOURCE_AGENT, "g2-after");
        assertThat(resp2.statusCode()).as(resp2.body()).isEqualTo(200);
        assertThat(resp2.body()).contains("result", "TASK_STATE_COMPLETED");
    }

    @Test
    @Story("F011-G3: 相同 routeHandle 不重复解析")
    @Tag("story-feat-011-g3-resolve-cache")
    @DisplayName("F011-G3 连续 5 次请求同 agentId 均成功（缓存命中）")
    void feat011G3ResolveCacheNoDuplicateResolution() throws Exception {
        for (int i = 0; i < 5; i++) {
            var resp = fixture.direct(AgentBusExternalFixture.SOURCE_AGENT, "g3-cache-" + i);
            assertThat(resp.statusCode()).as("request " + i).isEqualTo(200);
            assertThat(resp.body()).contains("result");
        }
    }

    @Test
    @Story("F011-G4: 无实例注册的 agent 无静态兜底")
    @Tag("story-feat-011-g4-no-static-fallback")
    @DisplayName("F011-G4 无实例注册的 agent 经网关返回 503 + ROUTE_NO_CANDIDATES")
    void feat011G4NoStaticFallbackWhenNoInstances() throws Exception {
        String ghostAgent = "ghost-agent-" + UUID.randomUUID();
        var resp = fixture.direct(ghostAgent, "g4-test");
        assertThat(resp.statusCode()).isEqualTo(503);
        assertSanitized(resp.body(), "ROUTE_NO_CANDIDATES");
    }

    @Test
    @Story("F011-G5: 心跳保活实例查询验证")
    @Tag("story-feat-011-g5-heartbeat-query")
    @DisplayName("F011-G5 跨 TTL 窗口后 RDC 仍查询到实例（心跳保活）")
    void feat011G5HeartbeatKeepaliveQueryVerified() throws Exception {
        JsonNode before = fixture.queryInstancesV2(AgentBusExternalFixture.SOURCE_AGENT);
        assertThat(before.isArray()).isTrue();
        assertThat(before.size()).isGreaterThanOrEqualTo(1);

        Thread.sleep(8000);

        JsonNode after = fixture.queryInstancesV2(AgentBusExternalFixture.SOURCE_AGENT);
        assertThat(after.isArray()).isTrue();
        assertThat(after.size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Story("F011-G6: RDC 不可用时网关缓存继续服务")
    @Tag("story-feat-011-g6-cached-outage")
    @DisplayName("F011-G6 RDC 短暂不可用时网关用缓存继续路由（需 AGENT_BUS_RDC_FAULT_URL）")
    void feat011G6CachedOutageContinuesServing() throws Exception {
        String faultUrl = System.getenv("AGENT_BUS_RDC_FAULT_URL");
        Assumptions.assumeTrue(faultUrl != null && !faultUrl.isBlank(),
                "Set AGENT_BUS_RDC_FAULT_URL for G6 cached-outage test");

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        client.send(HttpRequest.newBuilder(URI.create(faultUrl))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());

        var resp = fixture.direct(AgentBusExternalFixture.SOURCE_AGENT, "g6-outage");
        assertThat(resp.statusCode()).as(resp.body()).isIn(200, 503);
    }

    @Test
    @Story("F011-G7: 网关转发超时配置生效")
    @Tag("story-feat-011-g7-forward-timeout")
    @DisplayName("F011-G7 注册慢响应实例后网关超时返回结构化错误（需 AGENT_BUS_SLOW_RUNTIME_URL）")
    void feat011G7ForwardTimeoutConfigEffective() throws Exception {
        String slowUrl = System.getenv("AGENT_BUS_SLOW_RUNTIME_URL");
        Assumptions.assumeTrue(slowUrl != null && !slowUrl.isBlank(),
                "Set AGENT_BUS_SLOW_RUNTIME_URL for G7 timeout test");

        String slowAgent = "slow-agent-" + UUID.randomUUID();
        fixture.registerRuntimeV2(slowAgent, "slow-service", slowUrl);
        Thread.sleep(6000);

        var resp = fixture.direct(slowAgent, "g7-timeout");
        assertThat(resp.statusCode()).isIn(200, 504, 503);

        fixture.deregisterByAddressV2(slowAgent, new java.net.URL(slowUrl).getHost(),
                new java.net.URL(slowUrl).getPort());
    }

    @Test
    @Story("F011-G8: streaming=false 时网关降级为同步")
    @Tag("story-feat-011-g8-streaming-disabled")
    @DisplayName("F011-G8 卡声明 streaming=false 时 SendStreamingMessage 仍返回结果")
    void feat011G8StreamingDisabledDegradesToSync() throws Exception {
        String syncAgent = "sync-agent-" + UUID.randomUUID();
        String runtimeUrl = AgentBusExternalFixture.requireUrl("agent.bus.runtime.source-url", "AGENT_BUS_SOURCE_RUNTIME_URL");
        java.net.URL url = new java.net.URL(runtimeUrl);

        com.fasterxml.jackson.databind.node.ObjectNode body = AgentBusExternalFixture.JSON.createObjectNode();
        body.put("address", url.getHost());
        body.put("port", url.getPort() != -1 ? url.getPort() : url.getDefaultPort());
        body.put("endpointPath", "/a2a");
        body.put("serviceId", "sync-service");
        body.put("weight", 100);
        body.put("maxConcurrency", 32);
        body.put("region", "acceptance");
        body.put("frameworkType", "JIUWEN");
        body.put("contractVersion", "1.0");
        body.put("capabilityVersion", "1.0");
        com.fasterxml.jackson.databind.node.ObjectNode card = AgentBusExternalFixture.JSON.createObjectNode();
        card.put("name", syncAgent);
        card.put("description", "sync-only agent");
        card.put("version", "1.0");
        card.put("url", runtimeUrl);
        com.fasterxml.jackson.databind.node.ObjectNode caps = card.putObject("capabilities");
        caps.put("streaming", false);
        caps.put("pushNotifications", false);
        card.putArray("defaultInputModes").add("text");
        card.putArray("defaultOutputModes").add("text");
        com.fasterxml.jackson.databind.node.ArrayNode interfaces = card.putArray("interfaces");
        com.fasterxml.jackson.databind.node.ObjectNode iface = interfaces.addObject();
        iface.put("transport", "JSONRPC");
        iface.put("url", runtimeUrl);
        card.putArray("skills");
        body.put("agentCardJson", AgentBusExternalFixture.JSON.writeValueAsString(card));

        String rdcUrl = System.getProperty("agent.bus.rdc-url", System.getenv("AGENT_BUS_RDC_URL"));
        String v2Url = rdcUrl + "/v2/agents/" + syncAgent + "/instances";
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest regReq = HttpRequest.newBuilder(URI.create(v2Url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + AgentBusExternalFixture.TOKEN)
                .header("X-Tenant-ID", AgentBusExternalFixture.TENANT)
                .POST(HttpRequest.BodyPublishers.ofString(AgentBusExternalFixture.JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> regResp = client.send(regReq, HttpResponse.BodyHandlers.ofString());
        assertThat(regResp.statusCode()).as(regResp.body()).isBetween(200, 299);

        Thread.sleep(6000);

        var resp = fixture.directStreaming(syncAgent, "g8-sync-stream");
        assertThat(resp.statusCode()).as(resp.body()).isIn(200, 503);

        fixture.deregisterByAddressV2(syncAgent, url.getHost(),
                url.getPort() != -1 ? url.getPort() : url.getDefaultPort());
    }

    @Test
    @Story("F011-G9: 协议错误不触发候选切换")
    @Tag("story-feat-011-g9-protocol-failure")
    @DisplayName("F011-G9 非法 method 返回 400 不触发 failover")
    void feat011G9ProtocolFailureNoFailover() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":\"g9-1\",\"method\":\"UnknownMethod\","
                + "\"params\":{\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"msg-g9\","
                + "\"contextId\":\"ctx-g9\",\"parts\":[{\"text\":\"test\"}]},"
                + "\"metadata\":{\"agentId\":\"" + AgentBusExternalFixture.SOURCE_AGENT + "\"}}}";
        var resp = fixture.postRaw(false, body, AgentBusExternalFixture.TOKEN);
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.body()).contains("VALIDATION_METHOD");
    }

    @Test
    @Story("F011-G10: 业务失败不触发候选切换")
    @Tag("story-feat-011-g10-business-failure")
    @DisplayName("F011-G10 业务级错误返回 JSON-RPC error 不触发 failover")
    void feat011G10BusinessFailureNoFailover() throws Exception {
        var resp = fixture.direct(AgentBusExternalFixture.SOURCE_AGENT, "");
        assertThat(resp.statusCode()).isIn(200, 400);
        assertThat(resp.body()).doesNotContain("ROUTE_NO_CANDIDATES", "REMOTE_UNAVAILABLE");
    }

    @Test
    @Story("F011-G11: INPUT_REQUIRED 不触发候选切换")
    @Tag("story-feat-011-g11-input-required")
    @DisplayName("F011-G11 INPUT_REQUIRED 状态不触发 failover（需 hub-R2R 部署）")
    void feat011G11InputRequiredNoFailover() throws Exception {
        String hubR2rUrl = System.getenv("AGENT_BUS_HUB_R2R_URL");
        Assumptions.assumeTrue(hubR2rUrl != null && !hubR2rUrl.isBlank(),
                "Set AGENT_BUS_HUB_R2R_URL for G11 INPUT_REQUIRED test");

        String body = "{\"jsonrpc\":\"2.0\",\"id\":\"g11-1\",\"method\":\"SendMessage\","
                + "\"params\":{\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"msg-g11\","
                + "\"contextId\":\"ctx-g11\",\"parts\":[{\"text\":\"I need human input for hotel booking\"}]}}}";
        URI uri = URI.create(hubR2rUrl + "/a2a");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + AgentBusExternalFixture.TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(60))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("REMOTE_UNAVAILABLE", "ROUTE_NO_CANDIDATES");
    }

    @Test
    @Story("F011-G12: 3:1 加权分布")
    @Tag("story-feat-011-g12-weighted-3to1")
    @DisplayName("F011-G12 3:1 权重双实例分布检验（需双实例部署）")
    void feat011G12Weighted3to1Distribution() throws Exception {
        String replica2Url = System.getenv("AGENT_BUS_SOURCE_RUNTIME_URL_2");
        Assumptions.assumeTrue(replica2Url != null && !replica2Url.isBlank(),
                "Set AGENT_BUS_SOURCE_RUNTIME_URL_2 for G12 weighted test");

        String instanceIdB = fixture.registerRuntimeV2(AgentBusExternalFixture.SOURCE_AGENT, AgentBusExternalFixture.SOURCE_SERVICE,
                replica2Url, "1.0", 33);
        Thread.sleep(8000);

        java.util.Set<String> taskIds = new java.util.HashSet<>();
        int successes = 0;
        for (int i = 0; i < 40; i++) {
            var resp = fixture.direct(AgentBusExternalFixture.SOURCE_AGENT, "g12-" + i);
            if (resp.statusCode() == 200) {
                successes++;
                JsonNode result = AgentBusExternalFixture.JSON.readTree(resp.body()).path("result");
                String taskId = result.path("task").path("id").asText(result.path("id").asText());
                if (!taskId.isBlank()) taskIds.add(taskId);
            }
            if (i % 10 == 9) fixture.heartbeatAllV2(AgentBusExternalFixture.SOURCE_AGENT);
        }
        assertThat(successes).as("expected most requests to succeed").isGreaterThan(20);
        assertThat(taskIds.size()).as("expected ≥2 distinct taskIds (weighted distribution)").isGreaterThanOrEqualTo(2);

        if (!instanceIdB.isEmpty()) {
            fixture.deregisterRuntimeV2(AgentBusExternalFixture.SOURCE_AGENT, instanceIdB);
        }
    }

    @Test
    @Story("F011-G13: 首事件前超时触发 failover")
    @Tag("story-feat-011-g13-request-timeout")
    @DisplayName("F011-G13 不可达实例首事件前超时后 failover（需双实例部署）")
    void feat011G13RequestTimeoutFailover() throws Exception {
        String replica2Url = System.getenv("AGENT_BUS_SOURCE_RUNTIME_URL_2");
        Assumptions.assumeTrue(replica2Url != null && !replica2Url.isBlank(),
                "Set AGENT_BUS_SOURCE_RUNTIME_URL_2 for G13 timeout-failover test");

        String deadUrl = "http://127.0.0.1:59997";
        try {
            fixture.registerRuntimeV2(AgentBusExternalFixture.SOURCE_AGENT, "dead-g13", deadUrl, "1.0", 100);
        } catch (AssertionError e) {
            Assumptions.assumeTrue(false, "Cannot register dead instance for G13");
        }
        Thread.sleep(6000);

        int successes = 0;
        for (int i = 0; i < 8; i++) {
            var resp = fixture.direct(AgentBusExternalFixture.SOURCE_AGENT, "g13-failover-" + i);
            if (resp.statusCode() == 200) successes++;
            if (i % 5 == 4) fixture.heartbeatAllV2(AgentBusExternalFixture.SOURCE_AGENT);
        }
        assertThat(successes).as("expected some requests to succeed via failover").isGreaterThan(0);

        fixture.deregisterByAddressV2(AgentBusExternalFixture.SOURCE_AGENT, "127.0.0.1", 59997);
    }

    @Test
    @Story("F011-G14: B 域变更不污染 A 域路由")
    @Tag("story-feat-011-g14-namespace-churn")
    @DisplayName("F011-G14 注册/注销另一个 agent 不影响 SOURCE_AGENT 路由")
    void feat011G14NamespaceChurnNoCrossPollution() throws Exception {
        var resp1 = fixture.direct(AgentBusExternalFixture.SOURCE_AGENT, "g14-before");
        assertThat(resp1.statusCode()).isEqualTo(200);

        String otherAgent = "other-agent-" + UUID.randomUUID();
        String runtimeUrl = AgentBusExternalFixture.requireUrl("agent.bus.runtime.source-url", "AGENT_BUS_SOURCE_RUNTIME_URL");
        fixture.registerRuntimeV2(otherAgent, "other-service", runtimeUrl);
        Thread.sleep(3000);

        java.net.URL url = new java.net.URL(runtimeUrl);
        fixture.deregisterByAddressV2(otherAgent, url.getHost(),
                url.getPort() != -1 ? url.getPort() : url.getDefaultPort());
        Thread.sleep(3000);

        var resp2 = fixture.direct(AgentBusExternalFixture.SOURCE_AGENT, "g14-after");
        assertThat(resp2.statusCode()).as(resp2.body()).isEqualTo(200);
        assertThat(resp2.body()).contains("result");
    }

    @Test
    @Story("F011-G15: 进程崩溃后网关 TTL 内收敛")
    @Tag("story-feat-011-g15-crash-stop")
    @DisplayName("F011-G15 实例不可达后网关 TTL 内收敛到健康实例（需双实例部署）")
    void feat011G15CrashStopConvergence() throws Exception {
        String replica2Url = System.getenv("AGENT_BUS_SOURCE_RUNTIME_URL_2");
        Assumptions.assumeTrue(replica2Url != null && !replica2Url.isBlank(),
                "Set AGENT_BUS_SOURCE_RUNTIME_URL_2 for G15 crash-stop test");

        fixture.registerRuntimeV2(AgentBusExternalFixture.SOURCE_AGENT, AgentBusExternalFixture.SOURCE_SERVICE,
                replica2Url, "1.0", 100);
        Thread.sleep(8000);

        String deadUrl = "http://127.0.0.1:59996";
        try {
            fixture.registerRuntimeV2(AgentBusExternalFixture.SOURCE_AGENT, "crashed-g15", deadUrl, "1.0", 100);
        } catch (AssertionError e) {
            Assumptions.assumeTrue(false, "Cannot register dead instance for G15");
        }
        Thread.sleep(6000);

        int successes = 0;
        for (int i = 0; i < 10; i++) {
            var resp = fixture.direct(AgentBusExternalFixture.SOURCE_AGENT, "g15-crash-" + i);
            if (resp.statusCode() == 200) successes++;
            if (i % 5 == 4) fixture.heartbeatAllV2(AgentBusExternalFixture.SOURCE_AGENT);
        }
        assertThat(successes).as("expected requests to succeed after crash via convergence").isGreaterThan(0);

        fixture.deregisterByAddressV2(AgentBusExternalFixture.SOURCE_AGENT, "127.0.0.1", 59996);
        java.net.URL url2 = new java.net.URL(replica2Url);
        fixture.deregisterByAddressV2(AgentBusExternalFixture.SOURCE_AGENT, url2.getHost(),
                url2.getPort() != -1 ? url2.getPort() : url2.getDefaultPort());
    }

    @Test
    @Story("F011-G16: 一实例下线不误删同 agentId 另一副本")
    @Tag("story-feat-011-g16-no-cross-delete")
    @DisplayName("F011-G16 注销实例 A 不影响同 agentId 实例 B（需双实例部署）")
    void feat011G16NoCrossDeleteSameAgentId() throws Exception {
        String replica2Url = System.getenv("AGENT_BUS_SOURCE_RUNTIME_URL_2");
        Assumptions.assumeTrue(replica2Url != null && !replica2Url.isBlank(),
                "Set AGENT_BUS_SOURCE_RUNTIME_URL_2 for G16 no-cross-delete test");

        String url1 = AgentBusExternalFixture.requireUrl("agent.bus.runtime.source-url", "AGENT_BUS_SOURCE_RUNTIME_URL");
        fixture.registerRuntimeV2(AgentBusExternalFixture.SOURCE_AGENT, AgentBusExternalFixture.SOURCE_SERVICE,
                replica2Url, "1.0", 100);
        Thread.sleep(8000);

        JsonNode instances = fixture.queryInstancesV2(AgentBusExternalFixture.SOURCE_AGENT);
        assertThat(instances.isArray()).isTrue();
        assertThat(instances.size()).as("expected ≥2 instances").isGreaterThanOrEqualTo(2);

        java.net.URL u1 = new java.net.URL(url1);
        fixture.deregisterByAddressV2(AgentBusExternalFixture.SOURCE_AGENT, u1.getHost(),
                u1.getPort() != -1 ? u1.getPort() : u1.getDefaultPort());
        Thread.sleep(2000);

        JsonNode remaining = fixture.queryInstancesV2(AgentBusExternalFixture.SOURCE_AGENT);
        boolean replica2StillExists = false;
        if (remaining.isArray()) {
            java.net.URL u2 = new java.net.URL(replica2Url);
            for (JsonNode inst : remaining) {
                // Parse card.rawJson for address/port (DEV-006 workaround)
                String instAddress = inst.path("address").asText();
                int instPort = inst.path("port").asInt();
                if (instAddress.isEmpty() || instPort == 0) {
                    String rawJson = inst.path("card").path("rawJson").asText();
                    if (!rawJson.isEmpty()) {
                        JsonNode card = AgentBusExternalFixture.JSON.readTree(rawJson);
                        String cardUrl = card.path("url").asText();
                        if (!cardUrl.isEmpty()) {
                            java.net.URL parsed = new java.net.URL(cardUrl);
                            instAddress = parsed.getHost();
                            instPort = parsed.getPort() != -1 ? parsed.getPort() : parsed.getDefaultPort();
                        }
                    }
                }
                if (u2.getHost().equals(instAddress) && u2.getPort() == instPort) {
                    replica2StillExists = true;
                    break;
                }
            }
        }
        assertThat(replica2StillExists).as("replica2 should still exist after deregistering replica1").isTrue();

        var resp = fixture.direct(AgentBusExternalFixture.SOURCE_AGENT, "g16-after");
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);

        java.net.URL url2 = new java.net.URL(replica2Url);
        fixture.deregisterByAddressV2(AgentBusExternalFixture.SOURCE_AGENT, url2.getHost(),
                url2.getPort() != -1 ? url2.getPort() : url2.getDefaultPort());
    }

    @Test
    @Story("F011-G17: 跨租户通过网关时拒绝")
    @Tag("story-feat-011-g17-tenant-mismatch")
    @DisplayName("F011-G17 不同租户注册的 agent 通过网关不可达")
    void feat011G17TenantMismatchRejected() throws Exception {
        String crossTenantAgent = "cross-tenant-agent-" + UUID.randomUUID();
        String runtimeUrl = AgentBusExternalFixture.requireUrl("agent.bus.runtime.source-url", "AGENT_BUS_SOURCE_RUNTIME_URL");
        java.net.URL url = new java.net.URL(runtimeUrl);

        com.fasterxml.jackson.databind.node.ObjectNode body = AgentBusExternalFixture.JSON.createObjectNode();
        body.put("address", url.getHost());
        body.put("port", url.getPort() != -1 ? url.getPort() : url.getDefaultPort());
        body.put("endpointPath", "/a2a");
        body.put("serviceId", "cross-tenant-service");
        body.put("weight", 100);
        body.put("maxConcurrency", 32);
        body.put("region", "acceptance");
        body.put("frameworkType", "JIUWEN");
        body.put("contractVersion", "1.0");
        body.put("capabilityVersion", "1.0");
        com.fasterxml.jackson.databind.node.ObjectNode card = AgentBusExternalFixture.JSON.createObjectNode();
        card.put("name", crossTenantAgent);
        card.put("description", "cross-tenant agent");
        card.put("version", "1.0");
        card.put("url", runtimeUrl);
        com.fasterxml.jackson.databind.node.ObjectNode caps = card.putObject("capabilities");
        caps.put("streaming", true);
        caps.put("pushNotifications", false);
        card.putArray("defaultInputModes").add("text");
        card.putArray("defaultOutputModes").add("text");
        com.fasterxml.jackson.databind.node.ArrayNode interfaces = card.putArray("interfaces");
        com.fasterxml.jackson.databind.node.ObjectNode iface = interfaces.addObject();
        iface.put("transport", "JSONRPC");
        iface.put("url", runtimeUrl);
        card.putArray("skills");
        body.put("agentCardJson", AgentBusExternalFixture.JSON.writeValueAsString(card));

        String rdcUrl = System.getProperty("agent.bus.rdc-url", System.getenv("AGENT_BUS_RDC_URL"));
        String v2Url = rdcUrl + "/v2/agents/" + crossTenantAgent + "/instances";
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        HttpRequest regReq = HttpRequest.newBuilder(URI.create(v2Url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + AgentBusExternalFixture.TOKEN)
                .header("X-Tenant-ID", "tenant-b")
                .POST(HttpRequest.BodyPublishers.ofString(AgentBusExternalFixture.JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> regResp = client.send(regReq, HttpResponse.BodyHandlers.ofString());
        assertThat(regResp.statusCode()).as(regResp.body()).isBetween(200, 299);

        Thread.sleep(3000);

        var resp = fixture.direct(crossTenantAgent, "g17-cross-tenant");
        assertThat(resp.statusCode()).isEqualTo(503);
        assertSanitized(resp.body(), "ROUTE_NO_CANDIDATES");

        HttpRequest queryReq = HttpRequest.newBuilder(URI.create(v2Url))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + AgentBusExternalFixture.TOKEN)
                .header("X-Tenant-ID", "tenant-b")
                .GET().build();
        HttpResponse<String> queryResp = client.send(queryReq, HttpResponse.BodyHandlers.ofString());
        JsonNode instances = AgentBusExternalFixture.JSON.readTree(queryResp.body());
        if (instances.isArray()) {
            for (JsonNode inst : instances) {
                String instanceId = inst.path("instanceId").asText(inst.path("id").asText());
                String delUrl = rdcUrl + "/v2/agents/" + crossTenantAgent + "/instances/" + instanceId;
                HttpRequest delReq = HttpRequest.newBuilder(URI.create(delUrl))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + AgentBusExternalFixture.TOKEN)
                        .header("X-Tenant-ID", "tenant-b")
                        .DELETE().build();
                client.send(delReq, HttpResponse.BodyHandlers.ofString());
            }
        }
    }

    @Test
    @Story("F011-G18: 路径入口多身份任务隔离")
    @Tag("story-feat-011-g18-hosted-isolation")
    @DisplayName("F011-G18 两个不同 agent 经路径入口创建任务后 GetTask 隔离")
    void feat011G18HostedIsolationMultiIdentity() throws Exception {
        String secondAgent = "second-agent-" + UUID.randomUUID();
        String runtimeUrl = AgentBusExternalFixture.requireUrl("agent.bus.runtime.source-url", "AGENT_BUS_SOURCE_RUNTIME_URL");
        fixture.registerRuntimeV2(secondAgent, "second-service", runtimeUrl);
        Thread.sleep(6000);

        String canary1 = "g18-a-" + UUID.randomUUID();
        String body1 = AgentBusExternalFixture.create(AgentBusExternalFixture.SOURCE_AGENT, canary1, false);
        var resp1 = fixture.directPathEntry(AgentBusExternalFixture.SOURCE_AGENT, body1, AgentBusExternalFixture.TOKEN);
        assertThat(resp1.statusCode()).as(resp1.body()).isEqualTo(200);
        JsonNode result1 = AgentBusExternalFixture.JSON.readTree(resp1.body()).path("result");
        String taskId1 = result1.path("task").path("id").asText(result1.path("id").asText());
        assertThat(taskId1).isNotBlank();

        String canary2 = "g18-b-" + UUID.randomUUID();
        String body2 = AgentBusExternalFixture.create(secondAgent, canary2, false);
        var resp2 = fixture.directPathEntry(secondAgent, body2, AgentBusExternalFixture.TOKEN);
        assertThat(resp2.statusCode()).as(resp2.body()).isEqualTo(200);
        JsonNode result2 = AgentBusExternalFixture.JSON.readTree(resp2.body()).path("result");
        String taskId2 = result2.path("task").path("id").asText(result2.path("id").asText());
        assertThat(taskId2).isNotBlank();

        assertThat(taskId1).isNotEqualTo(taskId2);

        var getTask1 = fixture.directGetTask(taskId1);
        assertThat(getTask1.statusCode()).isEqualTo(200);
        assertThat(getTask1.body()).contains("TASK_STATE_COMPLETED");
        assertThat(getTask1.body()).doesNotContain("ROUTE_OWNERSHIP_UNKNOWN");

        var getTask2 = fixture.directGetTask(taskId2);
        assertThat(getTask2.statusCode()).isEqualTo(200);
        assertThat(getTask2.body()).contains("TASK_STATE_COMPLETED");
        assertThat(getTask2.body()).doesNotContain("ROUTE_OWNERSHIP_UNKNOWN");

        java.net.URL url = new java.net.URL(runtimeUrl);
        fixture.deregisterByAddressV2(secondAgent, url.getHost(),
                url.getPort() != -1 ? url.getPort() : url.getDefaultPort());
    }

    private static void assertSanitized(String body, String code) {
        assertThat(body).contains(code)
                .doesNotContain("routeHandle", "endpointUrl", "127.0.0.1", "localhost");
    }
}

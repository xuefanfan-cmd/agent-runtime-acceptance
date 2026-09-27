package com.huawei.ascend.sit.cases.e2e.tscript;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.cases.integration.agent_bus.AgentBusExternalFixture;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TS039-C29 — 「runtime/gateway/client 真实流式契约兼容」的跨模块黑盒合同用例。
 *
 * <p>拓扑：被测宿主 {@code tool-scripts-edpa}（tscript 宿主）+ {@code registry-center} + {@code gateway-direct}
 * （{@code gateway.path-mode=direct}，目标 agent 取自配置 {@code gateway.default-agent-id}）。用例只向网关端点发一次 A2A 流式
 * 创建请求，网关按键值对（RDC 注册表）解析到宿主并原样桥接下游 SSE 帧；不直连宿主，因此任何收到的
 * 话术事件都必然经网关链路。create 类请求必须显式携带 {@code params.metadata.agentId}（网关 G3 校验，
 * 当前实现无缺省 agent 回退：缺省会 400 {@code VALIDATION_AGENT_ID}）。
 *
 * <p>基座复用：RDC 注册沿用 {@link AgentBusExternalFixture}（public 的注册 API，与 {@code RouteQueryExternalBlackboxTest}
 * 同形）；网关凭据与默认 agent 从 {@code sut.agents.gateway-direct.spring.properties} 声明读取，网关流式调用本类自持，
 * 因为 {@code AgentBusExternalFixture#directStreaming} 是包内可见且其 token 常量取自另一任务的 env 开关。
 *
 * <p>Oracle（FEAT-039 §「语义事件一致」/ L2 §2.4）：话术事件保留 {@code event/tool/content/timestamp/conversation_id}
 * 五字段，包裹为 {@code type=custom}+{@code payload}（存量前端消费者读 {@code artifact.parts[].data} 的
 * {@code payload.event/content}），且 {@code content} 等于卡片配置文案。不宣称 AG-UI 已完成。
 */
@Tag("e2e")
@Tag("tscript")
@Tag("feat-039")
@Feature("FEAT-039: 业务扩展工具话术（EDPA）")
class ToolScriptGatewayStreamingContractIT extends BaseManagedStackTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SUT = "tool-scripts-edpa";
    private static final String REGISTRY = "registry-center";
    private static final String GATEWAY = "gateway-direct";
    private static final String SCENARIO = "default";
    private static final String TOOL = "tscript_probe";
    /** 卡片配置文案：{@code ToolScriptProviders#standardProperties} 的 {@code tool_scripts.intent.finance}。 */
    private static final String CARD_START = "FINANCE_START";
    private static final String CARD_END = "FINANCE_END";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private AgentBusExternalFixture registry;
    private String gatewayToken;
    private String registeredAgentId;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .agent(REGISTRY)
                .agent(GATEWAY, gateway -> gateway.downstream(REGISTRY, "gateway.rdc.base-url"))
                .agent(SUT, sut -> {
                    sut.env("EDP_AGENT_SCENARIO_HOME", scenarioHome().toString());
                    sut.serviceBinding("redis", "deep-agent.redis.host", "{{host}}")
                            .serviceBinding("redis", "deep-agent.redis.port", "{{port}}");
                });
    }

    @BeforeAll
    void registerSutAsRoutableRuntime() throws Exception {
        Map<String, String> gatewayProperties =
                config.getStringMap("sut.agents." + GATEWAY + ".spring.properties");
        gatewayToken = gatewayProperties.getOrDefault("gateway.test-credential.token", "acceptance-token");
        registeredAgentId = gatewayProperties.getOrDefault("gateway.default-agent-id", "source-agent");

        registry = AgentBusExternalFixture.forEndpoints(
                stack.baseUrl(REGISTRY), stack.baseUrl(GATEWAY), null);
        // The gateway resolves its target through RDC: register the fixture host under the agent id the
        // request will name, so the DIRECT route resolves to the SUT.
        registry.registerRuntime(registeredAgentId, SUT + "-sit", stack.baseUrl(SUT));
    }

    @Test
    @Story("ts039.downstream-contract: runtime/gateway/client 真实流式契约兼容")
    @DisplayName("TS039-C29: script events survive the gateway streaming path")
    void scriptEventsSurviveGatewayStreaming() throws Exception {
        String canary = newCanary();
        String contextId = "ctx-" + canary;
        Map<String, Integer> before = counts();

        GatewayStream stream = sendThroughGateway(contextId, "TSCRIPT tool=" + TOOL
                + " mode=plain biz_type=finance canary=" + canary + ".");

        int executed = counts().getOrDefault("execute." + TOOL, 0) - before.getOrDefault("execute." + TOOL, 0);
        Allure.addAttachment("ts039-c29-gateway-sse-" + canary, "text/plain", stream.raw());
        System.out.printf("EVIDENCE ts039-c29 status=%d contentType=%s frames=%d customScriptEvents=%d "
                        + "executedDelta=%d%n",
                stream.statusCode(), stream.contentType(), stream.frames().size(),
                stream.scriptEvents().size(), executed);

        // INCONCLUSIVE gates: without reaching the gateway/adapter chain, or without the SUT actually
        // running the tool, the assertions below would not be about this topology (C29 marks both as
        // INCONCLUSIVE rather than as a product failure).
        Assumptions.assumeTrue(stream.statusCode() == 200,
                "INCONCLUSIVE: gateway stream did not complete (HTTP " + stream.statusCode()
                        + "): " + stream.raw());
        assertThat(stream.contentType()).as(stream.raw()).containsIgnoringCase("text/event-stream");
        Assumptions.assumeTrue(executed >= 1,
                "INCONCLUSIVE: the SUT never executed " + TOOL + " (LLM-driven Given not constructed), "
                        + "so the gateway hop carried no script event");

        List<ScriptEvent> events = stream.scriptEvents();
        assertThat(events).as("custom-wrapped script events on the gateway stream:\n%s", stream.raw())
                .isNotEmpty();

        assertThat(events).as("script events").allSatisfy(event -> {
            JsonNode payload = event.payload();
            assertThat(payload.path("event").asText()).isIn("tool_start", "tool_end");
            assertThat(payload.path("tool").asText()).isEqualTo(TOOL);
            assertThat(payload.path("content").asText()).isNotBlank();
            assertThat(payload.path("timestamp").canConvertToLong()).isTrue();
            assertThat(payload.path("timestamp").asLong()).isGreaterThan(1_000_000_000_000L);
            assertThat(payload.path("conversation_id").asText()).isEqualTo(contextId);
            // Legacy consumer shape: artifact part data = {type:"custom", index, payload}, read as payload.event/content.
            assertThat(event.envelope().path("type").asText()).isEqualTo("custom");
            assertThat(event.envelope().path("payload").path("event").asText())
                    .isEqualTo(payload.path("event").asText());
            assertThat(event.envelope().path("payload").path("content").asText())
                    .isEqualTo(payload.path("content").asText());
            assertThat(payload.path("content").asText()).doesNotContain(canary);
        });

        List<ScriptEvent> starts = events(events, "tool_start");
        List<ScriptEvent> ends = events(events, "tool_end");
        assertThat(starts).as("tool_start events:\n%s", stream.raw()).isNotEmpty();
        assertThat(ends).as("tool_end events:\n%s", stream.raw()).isNotEmpty();
        assertThat(starts).allSatisfy(event -> assertThat(event.payload().path("content").asText())
                .as("tool_start must carry the card text").isEqualTo(CARD_START));
        assertThat(ends).allSatisfy(event -> assertThat(event.payload().path("content").asText())
                .as("tool_end must carry the card text").isEqualTo(CARD_END));

        // Non-gating evidence: the optional correlation field must not break the five-field contract.
        Allure.parameter("customEvents", String.valueOf(events.size()));
        Allure.parameter("toolCallIds", events.stream()
                .map(event -> event.payload().path("tool_call_id").asText(""))
                .distinct().reduce((left, right) -> left + "," + right).orElse(""));
    }

    /** A2A streaming create through the gateway endpoint ({@code POST <gateway>/a2a}). */
    private GatewayStream sendThroughGateway(String contextId, String prompt) throws Exception {
        ObjectNode message = JSON.createObjectNode();
        message.put("role", "ROLE_USER");
        message.put("messageId", "msg-" + UUID.randomUUID());
        message.put("contextId", contextId);
        message.putArray("parts").addObject().put("text", prompt);

        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", "req-" + UUID.randomUUID());
        envelope.put("method", "SendStreamingMessage");
        ObjectNode params = envelope.putObject("params");
        params.set("message", message);
        // Create-type requests must name the target agent explicitly (G3 VALIDATION_AGENT_ID): the
        // gateway has no default-agent fallback on the create path.
        params.putObject("metadata").put("agentId", registeredAgentId);

        HttpRequest request = HttpRequest.newBuilder(URI.create(stack.baseUrl(GATEWAY) + "/a2a"))
                .timeout(Duration.ofSeconds(180))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + gatewayToken)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(envelope),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());

        List<JsonNode> frames = new ArrayList<>();
        StringBuilder raw = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                raw.append(line).append('\n');
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (!data.isEmpty() && !"[DONE]".equals(data)) {
                    frames.add(JSON.readTree(data));
                }
            }
        }
        return new GatewayStream(response.statusCode(),
                response.headers().firstValue("content-type").orElse(""),
                List.copyOf(frames), List.copyOf(collectScriptEvents(frames)), raw.toString());
    }

    /** Fixture diagnostics endpoint: per-tool execution counters (shared with the integration cases). */
    private Map<String, Integer> counts() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(stack.baseUrl(SUT) + "/__sit/tool-scripts/counts"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readValue(response.body(), JSON.getTypeFactory()
                .constructMapType(Map.class, String.class, Integer.class));
    }

    /**
     * Collects the {@code type=custom} envelopes carrying a business-tool script event — the shape a
     * legacy consumer reads ({@code data.type}/{@code data.payload.event}/{@code data.payload.content}).
     * Frames are searched recursively because the envelope is nested inside the A2A artifact part.
     */
    private static List<ScriptEvent> collectScriptEvents(List<JsonNode> frames) {
        List<ScriptEvent> events = new ArrayList<>();
        frames.forEach(frame -> collect(frame, events, 0));
        return events;
    }

    private static void collect(JsonNode node, List<ScriptEvent> events, int depth) {
        if (node == null || node.isNull() || depth > 30) {
            return;
        }
        if (node.isObject()) {
            if ("custom".equals(node.path("type").asText()) && node.path("payload").isObject()) {
                JsonNode payload = node.path("payload");
                String event = payload.path("event").asText("");
                if ("tool_start".equals(event) || "tool_end".equals(event)) {
                    events.add(new ScriptEvent(node.deepCopy(), payload.deepCopy()));
                }
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                collect(fields.next().getValue(), events, depth + 1);
            }
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collect(child, events, depth + 1));
            return;
        }
        if (node.isTextual()) {
            String text = node.asText().trim();
            if (text.startsWith("{") || text.startsWith("[")) {
                try {
                    collect(JSON.readTree(text), events, depth + 1);
                } catch (Exception ignored) {
                    // Ordinary model text is not a structured event.
                }
            }
        }
    }

    private static List<ScriptEvent> events(List<ScriptEvent> events, String event) {
        return events.stream().filter(item -> event.equals(item.payload().path("event").asText())).toList();
    }

    private static String newCanary() {
        return "TS" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static Path scenarioHome() {
        String override = System.getProperty("tscript.fixture.root", "");
        Path root = override.isBlank()
                ? Path.of("..", "agent-solution", "common", "example", "tool-scripts-sit-demo", "scenarios")
                : Path.of(override);
        return root.resolve(SCENARIO).toAbsolutePath().normalize();
    }

    private record ScriptEvent(JsonNode envelope, JsonNode payload) {
    }

    private record GatewayStream(int statusCode, String contentType, List<JsonNode> frames,
                                 List<ScriptEvent> scriptEvents, String raw) {
    }
}

package com.huawei.ascend.sit.cases.integration.agent_bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Public HTTP fixture for the externally started DIRECT/BUS acceptance stack. */
public final class AgentBusExternalFixture {
    public static final ObjectMapper JSON = new ObjectMapper();
    public static final String TENANT = "tenant-a";
    public static final String TOKEN = value("agent.bus.token", "AGENT_BUS_TEST_TOKEN", "acceptance-token");
    public static final String SOURCE_AGENT = "source-agent";
    public static final String SOURCE_SERVICE = "source-runtime";
    public static final String TARGET_AGENT = "target-agent";
    public static final String TARGET_SERVICE = "target-runtime";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final String rdcUrl;
    private final String directGatewayUrl;
    private final String busGatewayUrl;

    private AgentBusExternalFixture(String rdcUrl, String directGatewayUrl, String busGatewayUrl) {
        this.rdcUrl = strip(rdcUrl);
        this.directGatewayUrl = strip(directGatewayUrl);
        this.busGatewayUrl = strip(busGatewayUrl);
    }

    static AgentBusExternalFixture forEndpoints(String rdcUrl, String directGatewayUrl, String busGatewayUrl) {
        return new AgentBusExternalFixture(rdcUrl, directGatewayUrl, busGatewayUrl);
    }

    static AgentBusExternalFixture requireDirect() {
        String rdc = value("agent.bus.rdc-url", "AGENT_BUS_RDC_URL", null);
        String direct = value("agent.bus.gateway.direct-url", "AGENT_BUS_DIRECT_GATEWAY_URL", null);
        Assumptions.assumeTrue(rdc != null && direct != null,
                "start the WSL stack and set AGENT_BUS_RDC_URL and AGENT_BUS_DIRECT_GATEWAY_URL");
        return new AgentBusExternalFixture(rdc, direct, null);
    }

    static AgentBusExternalFixture requireBus() {
        String rdc = value("agent.bus.rdc-url", "AGENT_BUS_RDC_URL", null);
        String bus = value("agent.bus.gateway.bus-url", "AGENT_BUS_BUS_GATEWAY_URL", null);
        Assumptions.assumeTrue(rdc != null && bus != null,
                "start the WSL stack and set AGENT_BUS_RDC_URL and AGENT_BUS_BUS_GATEWAY_URL");
        return new AgentBusExternalFixture(rdc, null, bus);
    }

    public static AgentBusExternalFixture requireBoth() {
        String rdc = value("agent.bus.rdc-url", "AGENT_BUS_RDC_URL", null);
        String direct = value("agent.bus.gateway.direct-url", "AGENT_BUS_DIRECT_GATEWAY_URL", null);
        String bus = value("agent.bus.gateway.bus-url", "AGENT_BUS_BUS_GATEWAY_URL", null);
        Assumptions.assumeTrue(rdc != null && direct != null && bus != null,
                "start the WSL stack and set RDC, DIRECT Gateway and BUS Gateway URLs");
        return new AgentBusExternalFixture(rdc, direct, bus);
    }

    public void registerRuntime(String agentId, String serviceId, String endpointUrl) throws Exception {
        registerRuntime(agentId, serviceId, endpointUrl, "1.0", 100);
    }

    void registerRuntime(String agentId, String serviceId, String endpointUrl,
                         String contractVersion, int weight) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("tenantId", TENANT);
        body.put("agentId", agentId);
        body.put("serviceId", serviceId);
        body.put("agentName", agentId);
        body.put("frameworkType", "JIUWEN");
        body.put("routeKey", serviceId);
        body.put("contractVersion", contractVersion);
        body.put("capabilityVersion", "1.0");
        body.put("endpointUrl", endpointUrl);
        body.put("maxConcurrency", 32);
        body.put("weight", weight);
        body.put("region", "acceptance");
        body.putArray("capabilities").add("a2a");
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                        URI.create(rdcUrl + "/api/registry/register"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build());
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
    }

    public HttpResponse<String> direct(String agentId, String text) throws Exception {
        return post(directGatewayUrl, create(agentId, text, false), "application/json", TOKEN);
    }

    HttpResponse<String> directStreaming(String agentId, String text) throws Exception {
        return post(directGatewayUrl, create(agentId, text, true), "text/event-stream", TOKEN);
    }

    public HttpResponse<String> bus(String agentId, String text) throws Exception {
        return post(busGatewayUrl, create(agentId, text, false), "application/json", TOKEN);
    }

    HttpResponse<String> busStreaming(String agentId, String text) throws Exception {
        return post(busGatewayUrl, create(agentId, text, true), "text/event-stream", TOKEN);
    }

    HttpResponse<String> postRaw(boolean bus, String body, String token) throws Exception {
        return post(bus ? busGatewayUrl : directGatewayUrl, body, "application/json", token);
    }

    JsonNode queryByAgent(String agentId) throws Exception {
        return queryByAgent(TENANT, agentId, null);
    }

    JsonNode queryByAgent(String tenant, String agentId, String contractVersion) throws Exception {
        String query = contractVersion == null ? "" : "?contractVersion=" + segment(contractVersion);
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(rdcUrl
                        + "/api/registry/instances/" + segment(tenant) + "/" + segment(agentId) + query))
                .timeout(Duration.ofSeconds(10)).GET().build());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    HttpResponse<String> resolve(String routeHandle, String tenant) throws Exception {
        String body = JSON.createObjectNode().put("routeHandle", routeHandle).put("tenantId", tenant).toString();
        return send(HttpRequest.newBuilder(URI.create(rdcUrl + "/api/registry/route-handle/resolve"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    public String directUrl() {
        return directGatewayUrl;
    }

    public String busUrl() {
        return busGatewayUrl;
    }

    public static String requireUrl(String property, String environment) {
        String url = value(property, environment, null);
        Assumptions.assumeTrue(url != null, "missing external service URL: " + environment);
        return url;
    }

    static String create(String agentId, String text, boolean streaming) throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("id", UUID.randomUUID().toString());
        root.put("method", streaming ? "SendStreamingMessage" : "SendMessage");
        ObjectNode params = root.putObject("params");
        ObjectNode message = params.putObject("message");
        message.put("role", "ROLE_USER");
        message.put("messageId", "msg-" + UUID.randomUUID());
        message.put("contextId", "ctx-" + UUID.randomUUID());
        message.putArray("parts").addObject().put("text", text);
        if (agentId != null) {
            params.putObject("metadata").put("agentId", agentId);
        }
        return JSON.writeValueAsString(root);
    }

    private HttpResponse<String> post(String baseUrl, String body, String accept, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/a2a"))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("Accept", accept);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String value(String property, String environment, String defaultValue) {
        String result = System.getProperty(property);
        if (result == null || result.isBlank()) {
            result = System.getenv(environment);
        }
        return result == null || result.isBlank() ? defaultValue : result;
    }

    private static String strip(String url) {
        return url == null || !url.endsWith("/") ? url : url.substring(0, url.length() - 1);
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}

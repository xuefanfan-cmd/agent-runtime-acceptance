package com.huawei.ascend.sit.cases.integration.agent_bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;

import java.io.InputStream;
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

    public static AgentBusExternalFixture forEndpoints(String rdcUrl, String directGatewayUrl, String busGatewayUrl) {
        return new AgentBusExternalFixture(rdcUrl, directGatewayUrl, busGatewayUrl);
    }

    public static AgentBusExternalFixture requireDirect() {
        String rdc = value("agent.bus.rdc-url", "AGENT_BUS_RDC_URL", null);
        String direct = value("agent.bus.gateway.direct-url", "AGENT_BUS_DIRECT_GATEWAY_URL", null);
        Assumptions.assumeTrue(rdc != null && direct != null,
                "start the WSL stack and set AGENT_BUS_RDC_URL and AGENT_BUS_DIRECT_GATEWAY_URL");
        return new AgentBusExternalFixture(rdc, direct, null);
    }

    public static AgentBusExternalFixture requireBus() {
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

    /**
     * Register an agent instance via the RDC v2 unified API (POST /v2/agents/{agentId}/instances).
     * This is required because the Gateway queries RDC via v2 API, and v1-registered instances
     * are invisible to v2 consumers (no card snapshot in registry_v2_card_snapshot table).
     */
    public String registerRuntimeV2(String agentId, String serviceId, String endpointUrl,
                                  String contractVersion, int weight) throws Exception {
        // Parse endpointUrl to extract address and port
        java.net.URL url = new java.net.URL(endpointUrl);
        String address = url.getHost();
        int port = url.getPort() != -1 ? url.getPort() : url.getDefaultPort();
        String endpointPath = url.getPath().isEmpty() ? "/a2a" : url.getPath();

        ObjectNode body = JSON.createObjectNode();
        body.put("address", address);
        body.put("port", port);
        body.put("endpointPath", endpointPath);
        body.put("serviceId", serviceId);
        body.put("weight", weight);
        body.put("maxConcurrency", 32);
        body.put("region", "acceptance");
        body.put("frameworkType", "JIUWEN");
        body.put("contractVersion", contractVersion);
        body.put("capabilityVersion", "1.0");

        // Agent card JSON — name must equal agentId (v2 identity invariant)
        // Include url and interfaces so Gateway's additive-free rewriter can rewrite them
        ObjectNode card = JSON.createObjectNode();
        card.put("name", agentId);
        card.put("description", "E2E test agent: " + agentId);
        card.put("version", "1.0");
        card.put("url", endpointUrl);  // Gateway rewrites to {public-base-url}/a2a/agents/{agentId}
        ObjectNode caps = card.putObject("capabilities");
        caps.put("streaming", true);
        caps.put("pushNotifications", false);
        card.putArray("defaultInputModes").add("text");
        card.putArray("defaultOutputModes").add("text");
        // interfaces with JSONRPC transport — Gateway rewrites url to gateway path
        ArrayNode interfaces = card.putArray("interfaces");
        ObjectNode iface = interfaces.addObject();
        iface.put("transport", "JSONRPC");
        iface.put("url", endpointUrl);
        card.putArray("skills");
        body.put("agentCardJson", JSON.writeValueAsString(card));

        String v2Url = rdcUrl + "/v2/agents/" + segment(agentId) + "/instances";
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(v2Url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + TOKEN)
                .header("X-Tenant-ID", TENANT)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                .build());
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        // Parse instanceId from response (needed for precise deregister when DEV-007 corrupts card URLs)
        try {
            JsonNode root = JSON.readTree(response.body());
            return root.path("instanceId").asText(root.path("id").asText());
        } catch (Exception e) {
            return "";
        }
    }

    public String registerRuntimeV2(String agentId, String serviceId, String endpointUrl) throws Exception {
        return registerRuntimeV2(agentId, serviceId, endpointUrl, "1.0", 100);
    }

    public void registerRuntime(String agentId, String serviceId, String endpointUrl,
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

    public void deregisterRuntime(String agentId) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(rdcUrl
                        + "/api/registry/deregister/" + segment(TENANT) + "/" + segment(agentId)))
                .timeout(Duration.ofSeconds(10)).DELETE().build());
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
    }

    /** Deregister an instance via RDC v2 API (DELETE /v2/agents/{agentId}/instances/{instanceId}). */
    public HttpResponse<String> deregisterRuntimeV2(String agentId, String instanceId) throws Exception {
        String url = rdcUrl + "/v2/agents/" + segment(agentId) + "/instances/" + segment(instanceId);
        return send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + TOKEN)
                .header("X-Tenant-ID", TENANT)
                .DELETE().build());
    }

    /** Deregister by address:port via v2 query + delete.
     *  RDC v2 query response does not return address/port at top level (DEV-006),
     *  so we parse card.rawJson to extract the URL for matching. */
    public void deregisterByAddressV2(String agentId, String address, int port) throws Exception {
        JsonNode instances = queryInstancesV2(agentId);
        if (instances.isArray()) {
            for (JsonNode inst : instances) {
                String instanceId = inst.path("instanceId").asText(inst.path("id").asText());
                // Try top-level fields first (in case DEV-006 is fixed in future)
                String instAddress = inst.path("address").asText();
                int instPort = inst.path("port").asInt();
                // If top-level fields missing (DEV-006), parse card.rawJson
                if (instAddress.isEmpty() || instPort == 0) {
                    String rawJson = inst.path("card").path("rawJson").asText();
                    if (!rawJson.isEmpty()) {
                        JsonNode card = JSON.readTree(rawJson);
                        String url = card.path("url").asText();
                        if (!url.isEmpty()) {
                            java.net.URL parsed = new java.net.URL(url);
                            instAddress = parsed.getHost();
                            instPort = parsed.getPort() != -1 ? parsed.getPort() : parsed.getDefaultPort();
                        }
                    }
                }
                if (address.equals(instAddress) && port == instPort) {
                    deregisterRuntimeV2(agentId, instanceId);
                }
            }
        }
    }

    /** Send heartbeat to all instances of an agent via v2 API (PUT /v2/agents/{agentId}/instances/{instanceId}/heartbeat).
     *  Use this in long-running tests to prevent TTL expiration (default 30s). */
    public void heartbeatAllV2(String agentId) throws Exception {
        JsonNode instances = queryInstancesV2(agentId);
        if (instances.isArray()) {
            for (JsonNode inst : instances) {
                String instanceId = inst.path("instanceId").asText(inst.path("id").asText());
                if (!instanceId.isEmpty()) {
                    String url = rdcUrl + "/v2/agents/" + segment(agentId) + "/instances/" + segment(instanceId) + "/heartbeat";
                    send(HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(10))
                            .header("Authorization", "Bearer " + TOKEN)
                            .header("X-Tenant-ID", TENANT)
                            .PUT(HttpRequest.BodyPublishers.noBody())
                            .build());
                }
            }
        }
    }

    /** Deregister all instances of an agent via v2 API (by instanceId, not address:port).
     *  Use this in @AfterEach to prevent instance accumulation across tests (DEV-008). */
    public void deregisterAllV2(String agentId) throws Exception {
        JsonNode instances = queryInstancesV2(agentId);
        if (instances.isArray()) {
            for (JsonNode inst : instances) {
                String instanceId = inst.path("instanceId").asText(inst.path("id").asText());
                if (!instanceId.isEmpty()) {
                    deregisterRuntimeV2(agentId, instanceId);
                }
            }
        }
    }

    /** Query instances via RDC v2 API (GET /v2/agents/{agentId}/instances). */
    public JsonNode queryInstancesV2(String agentId) throws Exception {
        String url = rdcUrl + "/v2/agents/" + segment(agentId) + "/instances";
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + TOKEN)
                .header("X-Tenant-ID", TENANT)
                .GET().build());
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        JsonNode root = JSON.readTree(response.body());
        return root.isArray() ? root : root.path("instances").isArray() ? root.path("instances") : root;
    }

    public HttpResponse<String> direct(String agentId, String text) throws Exception {
        return post(directGatewayUrl, create(agentId, text, false), "application/json", TOKEN);
    }

    public HttpResponse<String> directStreaming(String agentId, String text) throws Exception {
        return post(directGatewayUrl, create(agentId, text, true), "text/event-stream", TOKEN);
    }

    HttpResponse<String> directGetTask(String taskId) throws Exception {
        return post(directGatewayUrl, taskOperation("GetTask", taskId), "application/json", TOKEN);
    }

    HttpResponse<String> directSubscribeTask(String taskId) throws Exception {
        return post(directGatewayUrl, taskOperation("SubscribeToTask", taskId),
                "text/event-stream", TOKEN);
    }

    HttpResponse<InputStream> directSubscribeTaskStream(String taskId) throws Exception {
        HttpRequest request = postRequest(directGatewayUrl,
                taskOperation("SubscribeToTask", taskId), "text/event-stream", TOKEN);
        return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
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

    /** POST to path-entry URL: /a2a/agents/{agentId} (path-mode routing). */
    public HttpResponse<String> directPathEntry(String agentId, String body, String token) throws Exception {
        String url = directGatewayUrl + "/a2a/agents/" + segment(agentId);
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (token != null) req.header("Authorization", "Bearer " + token);
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** GET discovery endpoint: /a2a/agents/{agentId}/.well-known/agent-card.json */
    public HttpResponse<String> discoverCard(String agentId) throws Exception {
        String url = directGatewayUrl + "/a2a/agents/" + segment(agentId) + "/.well-known/agent-card.json";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** POST raw body to a specific path under the direct gateway (e.g. /a2a/agents/{agentId}). */
    public HttpResponse<String> postToPath(String path, String body, String accept, String token) throws Exception {
        String url = directGatewayUrl + path;
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (token != null) req.header("Authorization", "Bearer " + token);
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    public JsonNode queryByAgent(String agentId) throws Exception {
        return queryByAgent(TENANT, agentId, null);
    }

    public JsonNode queryByAgent(String tenant, String agentId, String contractVersion) throws Exception {
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

    /** Declared relay instance count of the externally deployed Event Bus (default 1 = single instance). */
    static int relayInstanceCount() {
        String raw = value("agent.bus.relay.instances", "AGENT_BUS_RELAY_INSTANCES", "1");
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    /** Optional externally operated endpoint that terminates one relay instance mid-flight. */
    static String relayFaultTriggerUrl() {
        return value("agent.bus.relay.fault-url", "AGENT_BUS_RELAY_FAULT_URL", null);
    }

    void triggerRelayInstanceFailure(String url) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.noBody()).build());
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
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

    private static String taskOperation(String method, String taskId) throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("id", UUID.randomUUID().toString());
        root.put("method", method);
        root.putObject("params").put("id", taskId);
        return JSON.writeValueAsString(root);
    }

    private HttpResponse<String> post(String baseUrl, String body, String accept, String token) throws Exception {
        return send(postRequest(baseUrl, body, accept, token));
    }

    private static HttpRequest postRequest(String baseUrl, String body, String accept, String token) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/a2a"))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("Accept", accept);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return request.POST(HttpRequest.BodyPublishers.ofString(body)).build();
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

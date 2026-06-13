package com.huawei.ascend.sit.cases.component.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-01 — Agent Card 发现 (特性 4-1).
 *
 * <p>The first case against the lifecycle layer's minimal closed loop:
 * bring up mainplan (no LLM, no chain) → readiness probe →
 * {@code client("mainplan").getAgentCard()}. It validates A2A card discovery
 * two ways — via the A2A SDK (parsed {@link AgentCard}) and via raw HTTP
 * (status, content-type, alias parity, and the data-driven contract in
 * {@code a01-agent-card-assertions.json}).
 *
 * <p>See {@code docs/cases/A-01-agent-card-discovery.md}.
 */
@Tag("component")
@Tag("smoke")
class AgentCardDiscoveryTest extends BaseManagedStackTest {

    private static final String DISCOVERY = "/.well-known/agent.json";
    private static final String ALIAS = "/.well-known/agent-card.json";
    private static final String CONTRACT_RESOURCE =
            "testdata/component/protocol/a01-agent-card-assertions.json";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // A-01 needs only mainplan: single agent, no chain wiring, no LLM.
        return SutStack.builder(config).agent("mainplan");
    }

    // ---- A-01.A — reachability & media type (raw HTTP) ----

    @Test
    @DisplayName("A-01.A: both discovery endpoints return 200 application/json with equivalent bodies")
    void discoveryEndpointsAreReachableWithJsonMediaType() throws Exception {
        HttpResponse<String> main = httpGet(DISCOVERY);
        HttpResponse<String> alias = httpGet(ALIAS);

        assertThat(main.statusCode()).as("GET " + DISCOVERY + " status").isEqualTo(200);
        assertThat(main.body()).as("GET " + DISCOVERY + " body").isNotBlank();
        assertThat(mediaType(main))
                .as("GET " + DISCOVERY + " Content-Type").isEqualTo("application/json");

        assertThat(alias.statusCode()).as("GET " + ALIAS + " status").isEqualTo(200);
        assertThat(mediaType(alias))
                .as("GET " + ALIAS + " Content-Type").isEqualTo("application/json");

        // Alias endpoint must serve the same card (A2A compatibility).
        assertThat(parseJson(alias.body()))
                .as("alias card equals primary card")
                .isEqualTo(parseJson(main.body()));
    }

    // ---- A-01.B — identity field via SDK discovery path ----

    @Test
    @DisplayName("A-01.B: SDK discovery resolves a non-null card named main-plan-agent")
    void sdkDiscoveryReturnsCardWithName() {
        AgentCard card = client("mainplan").getAgentCard();

        assertThat(card).as("SDK getAgentCard()").isNotNull();
        assertThat(card.name()).isEqualTo("main-plan-agent");
    }

    // ---- A-01.C — capability declarations ----

    @Test
    @DisplayName("A-01.C: card declares streaming and pushNotifications capabilities")
    void cardDeclaresStreamingAndPushCapabilities() {
        AgentCard card = client("mainplan").getAgentCard();

        assertThat(card.capabilities()).as("capabilities").isNotNull();
        assertThat(card.capabilities().streaming()).as("capabilities.streaming").isTrue();
        assertThat(card.capabilities().pushNotifications()).as("capabilities.pushNotifications").isTrue();
    }

    // ---- A-01.D — interface contract (transport, url, modes) ----

    @Test
    @DisplayName("A-01.D: card exposes a JSONRPC interface, /a2a endpoint url, JSONRPC transport and text modes")
    void cardExposesJsonRpcInterfaceContract() throws Exception {
        AgentCard card = client("mainplan").getAgentCard();

        // SDK-parsed contract. Note: the SDK surfaces the agent endpoint via
        // supportedInterfaces[0].url, not the top-level card.url() (which is null here).
        assertThat(card.preferredTransport()).isEqualTo("JSONRPC");

        List<AgentInterface> jsonrpcInterfaces = card.supportedInterfaces().stream()
                .filter(i -> "JSONRPC".equals(i.protocolBinding()))
                .toList();
        assertThat(jsonrpcInterfaces)
                .as("supportedInterfaces contains a JSONRPC binding")
                .isNotEmpty();
        assertThat(jsonrpcInterfaces.get(0).url())
                .as("JSONRPC interface url").endsWith("/a2a");

        assertThat(card.defaultInputModes()).as("defaultInputModes").containsExactly("text");
        assertThat(card.defaultOutputModes())
                .as("defaultOutputModes").containsExactly("text", "artifact");

        // Raw-JSON contract: the top-level agent endpoint and the JSONRPC interface url
        // are both rewritten to the actual serving origin and must agree on /a2a.
        JsonNode cardNode = parseJson(httpGet(DISCOVERY).body());
        assertThat(readPath(cardNode, "url").asText())
                .as("$.url").endsWith("/a2a");
        assertThat(readPath(cardNode, "supportedInterfaces.0.url").asText())
                .as("$.supportedInterfaces[0].url").endsWith("/a2a");
    }

    // ---- A-01.D (data-driven) — full contract from the discovery endpoint ----

    @Test
    @DisplayName("A-01.D: served card matches the data-driven contract (a01-agent-card-assertions.json)")
    void servedCardMatchesContract() throws Exception {
        JsonNode cardNode = parseJson(httpGet(DISCOVERY).body());

        for (Map.Entry<String, Object> entry : loadContract().entrySet()) {
            String path = entry.getKey();
            if (path.startsWith("_")) {
                continue; // documentation keys
            }
            JsonNode expected = mapper.valueToTree(entry.getValue());
            JsonNode actual = readPath(cardNode, path);
            assertThat(actual)
                    .as("contract field $.%s", path)
                    .isNotNull()
                    .isEqualTo(expected);
        }
    }

    // ---- helpers ----

    private HttpResponse<String> httpGet(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(stack.baseUrl("mainplan") + path))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String mediaType(HttpResponse<String> response) {
        return response.headers().firstValue("Content-Type").orElse("").split(";")[0].trim();
    }

    private JsonNode parseJson(String body) throws IOException {
        return mapper.readTree(body);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadContract() throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(CONTRACT_RESOURCE)) {
            assertThat(is).as("contract resource %s on classpath", CONTRACT_RESOURCE).isNotNull();
            return mapper.readValue(is, Map.class);
        }
    }

    private static JsonNode readPath(JsonNode root, String dotted) {
        JsonNode current = root;
        for (String part : dotted.split("\\.")) {
            if (current.isArray() && isNumeric(part)) {
                current = current.get(Integer.parseInt(part));
            } else {
                current = current.get(part);
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static boolean isNumeric(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}

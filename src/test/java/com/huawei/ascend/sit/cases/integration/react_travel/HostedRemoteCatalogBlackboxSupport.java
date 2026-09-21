package com.huawei.ascend.sit.cases.integration.react_travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** Black-box driver for FEAT-037 instance-scoped remote-agent catalogs. */
public final class HostedRemoteCatalogBlackboxSupport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(180);
    private static final String GLOBAL_PREFIX = "openjiuwen.service.a2a.remote-agents";
    private static final String LOCAL_PREFIX = "openjiuwen.service.a2a.agents.%s.remote-agents";

    private final TestConfig config;
    private final String hostedName;
    private final boolean deepAgent;
    private final ToolCallingOpenAiFixture model;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public HostedRemoteCatalogBlackboxSupport(TestConfig config, String hostedName, boolean deepAgent,
            ToolCallingOpenAiFixture model) {
        this.config = config;
        this.hostedName = hostedName;
        this.deepAgent = deepAgent;
        this.model = model;
    }

    public enum InvalidRemoteVariant {
        DUPLICATE_NAME,
        BLANK_NAME,
        BLANK_URL
    }

    public static void configureHosted(SutStack.AgentBuilder agent, ToolCallingOpenAiFixture model) {
        agent.property("openjiuwen.demo.hosted.api-key", "feat037-fixture-key")
                .property("openjiuwen.demo.hosted.api-base", model.apiBase())
                .property("openjiuwen.demo.hosted.model-name", "feat037-tool-fixture")
                .property("openjiuwen.demo.hosted.provider", "OpenAI")
                .property("openjiuwen.demo.hosted.ssl-verify", "false")
                .property("openjiuwen.demo.hosted.max-iterations", "6");
    }

    public void t36InheritanceAppendAndCompleteOverride() throws Exception {
        try (RemoteA2aCatalogFixture global = RemoteA2aCatalogFixture.start(Map.of(
                "shared", new RouteSpec("GLOBAL_SHARED_T36", 0),
                "global-only", new RouteSpec("GLOBAL_ONLY_T36", 0)));
                RemoteA2aCatalogFixture local = RemoteA2aCatalogFixture.start(Map.of(
                        "shared", new RouteSpec("LOCAL_SHARED_T36", 1500),
                        "local-only", new RouteSpec("LOCAL_ONLY_T36", 0)))) {
            Map<String, String> properties = new LinkedHashMap<>();
            remote(properties, GLOBAL_PREFIX, 0, "shared", global.baseUrl("shared"));
            properties.put(GLOBAL_PREFIX + "[0].timeout-seconds", "1");
            properties.put(GLOBAL_PREFIX + "[0].streaming", "true");
            remote(properties, GLOBAL_PREFIX, 1, "global-only", global.baseUrl("global-only"));
            remote(properties, localPrefix("agent-a"), 0, "shared", local.baseUrl("shared"));
            remote(properties, localPrefix("agent-a"), 1, "local-only", local.baseUrl("local-only"));

            try (SutStack stack = start(properties)) {
                assertCall(stack, "agent-a", "shared", "T36_A_SHARED", true, "LOCAL_SHARED_T36");
                assertCall(stack, "agent-a", "local-only", "T36_A_LOCAL", true, "LOCAL_ONLY_T36");
                assertCall(stack, "agent-a", "global-only", "T36_A_GLOBAL", true, "GLOBAL_ONLY_T36");
                assertCall(stack, "agent-b", "shared", "T36_B_SHARED", true, "GLOBAL_SHARED_T36");
                assertCall(stack, "agent-b", "global-only", "T36_B_GLOBAL", true, "GLOBAL_ONLY_T36");
            }

            assertThat(local.methods("shared")).containsExactly("SendMessage");
            assertThat(global.methods("shared")).containsExactly("SendStreamingMessage");
            assertThat(local.a2aRequests("shared")).isEqualTo(1);
            assertThat(global.a2aRequests("shared")).isEqualTo(1);
            assertThat(local.a2aRequests("local-only")).isEqualTo(1);
            assertThat(global.a2aRequests("global-only")).isEqualTo(2);
        }
    }

    public void t37FailedLocalDiscoveryDoesNotFallBackAndRecoversIndependently() throws Exception {
        int localPort = reservePort();
        try (RemoteA2aCatalogFixture global = RemoteA2aCatalogFixture.start(Map.of(
                "shared", new RouteSpec("GLOBAL_SHARED_T37", 0)));
                RemoteA2aCatalogFixture local = RemoteA2aCatalogFixture.stopped(localPort, Map.of(
                        "shared", new RouteSpec("LOCAL_SHARED_T37", 0)))) {
            Map<String, String> properties = new LinkedHashMap<>();
            remote(properties, GLOBAL_PREFIX, 0, "shared", global.baseUrl("shared"));
            remote(properties, localPrefix("agent-a"), 0, "shared", local.baseUrl("shared"));

            try (SutStack stack = start(properties)) {
                assertCall(stack, "agent-b", "shared", "T37_B_GLOBAL", false, "GLOBAL_SHARED_T37");
                String missing = call(stack, "agent-a", "shared", "T37_A_MISSING", false);
                assertThat(missing).contains("MISSING_TOOL:shared").doesNotContain("GLOBAL_SHARED_T37");
                assertThat(global.a2aRequests("shared")).isEqualTo(1);

                local.start();
                assertCall(stack, "agent-a", "shared", "T37_A_RECOVERED", false, "LOCAL_SHARED_T37");

                assertThat(global.a2aRequests("shared")).isEqualTo(1);
                assertThat(local.a2aRequests("shared")).isEqualTo(1);
                assertCall(stack, "agent-b", "shared", "T37_B_STILL_GLOBAL", false, "GLOBAL_SHARED_T37");
            }
        }
    }

    public void t38LateGlobalDiscoveryPropagatesWithoutReplacingOverride() throws Exception {
        int globalPort = reservePort();
        try (RemoteA2aCatalogFixture global = RemoteA2aCatalogFixture.stopped(globalPort, Map.of(
                "shared", new RouteSpec("GLOBAL_SHARED_T38", 0),
                "global-late", new RouteSpec("GLOBAL_LATE_T38", 0)));
                RemoteA2aCatalogFixture local = RemoteA2aCatalogFixture.start(Map.of(
                        "shared", new RouteSpec("LOCAL_SHARED_T38", 0)))) {
            Map<String, String> properties = new LinkedHashMap<>();
            remote(properties, GLOBAL_PREFIX, 0, "shared", global.baseUrl("shared"));
            remote(properties, GLOBAL_PREFIX, 1, "global-late", global.baseUrl("global-late"));
            remote(properties, localPrefix("agent-a"), 0, "shared", local.baseUrl("shared"));

            try (SutStack stack = start(properties)) {
                assertCall(stack, "agent-a", "shared", "T38_A_LOCAL_INITIAL", false, "LOCAL_SHARED_T38");
                global.start();
                assertCall(stack, "agent-a", "global-late", "T38_A_LATE", false, "GLOBAL_LATE_T38");

                assertCall(stack, "agent-b", "global-late", "T38_B_LATE", false, "GLOBAL_LATE_T38");
                assertCall(stack, "agent-a", "shared", "T38_A_LOCAL_FINAL", false, "LOCAL_SHARED_T38");
                assertCall(stack, "agent-b", "shared", "T38_B_GLOBAL", false, "GLOBAL_SHARED_T38");
            }

            assertThat(local.a2aRequests("shared")).isEqualTo(2);
            assertThat(global.a2aRequests("shared")).isEqualTo(1);
            assertThat(global.a2aRequests("global-late")).isEqualTo(2);
        }
    }

    public void t39IntentUsesInstanceCatalogAndRecoversIndependently() throws Exception {
        assertThat(deepAgent).as("T39 applies only to DeepAgent").isTrue();
        try (RemoteA2aCatalogFixture localA = RemoteA2aCatalogFixture.start(Map.of(
                "intent", new RouteSpec("INTENT_LOCAL_A_T39", 0)));
                RemoteA2aCatalogFixture localB = RemoteA2aCatalogFixture.start(Map.of(
                        "intent", new RouteSpec("INTENT_LOCAL_B_T39", 0)))) {
            Map<String, String> properties = new LinkedHashMap<>();
            remote(properties, localPrefix("agent-a"), 0, "intent-remote", localA.baseUrl("intent"));
            remote(properties, localPrefix("agent-b"), 0, "intent-remote", localB.baseUrl("intent"));
            properties.put("openjiuwen.service.intent.enabled", "true");
            properties.put("openjiuwen.service.intent.expose-agent-card-tools", "false");
            properties.put("openjiuwen.service.intent.match.threshold", "0.5");
            properties.put("openjiuwen.service.intent.disambiguation.strategy", "MODEL");
            properties.put("openjiuwen.demo.hosted.reranker-model-name", "feat037-reranker");

            try (SutStack stack = start(properties)) {
                assertCall(stack, "agent-a", "intent_match", "T39_A_INTENT", false, "INTENT_LOCAL_A_T39");
                assertCall(stack, "agent-b", "intent_match", "T39_B_INTENT", false, "INTENT_LOCAL_B_T39");
                assertThat(model.toolsObserved("T39_A_INTENT")).contains("intent_match")
                        .doesNotContain("intent-remote");
                assertThat(model.toolsObserved("T39_B_INTENT")).contains("intent_match")
                        .doesNotContain("intent-remote");

                localA.stop();
                String failed = call(stack, "agent-a", "intent_match", "T39_A_DOWN", false);
                assertThat(failed).doesNotContain("INTENT_LOCAL_A_T39", "INTENT_LOCAL_B_T39");
                assertCall(stack, "agent-b", "intent_match", "T39_B_UNAFFECTED", false, "INTENT_LOCAL_B_T39");
                localA.start();
                assertCall(stack, "agent-a", "intent_match", "T39_A_RECOVERED", false, "INTENT_LOCAL_A_T39");
            }

            assertThat(localA.a2aRequests("intent")).isEqualTo(2);
            assertThat(localB.a2aRequests("intent")).isEqualTo(2);
            assertThat(model.rerankRequests()).isGreaterThanOrEqualTo(5);
        }
    }

    public void t40TlsClientCacheIsCatalogScoped() throws Exception {
        try (TlsRemoteA2aFixture remote = TlsRemoteA2aFixture.start("TLS_REMOTE_T40")) {
            Map<String, String> properties = new LinkedHashMap<>();
            remote(properties, localPrefix("agent-a"), 0, "secure-remote", remote.cardBaseUrl());
            remote(properties, localPrefix("agent-b"), 0, "secure-remote", remote.cardBaseUrl());
            String tlsPrefix = localPrefix("agent-a") + "[0].tls";
            properties.put(tlsPrefix + ".enabled", "true");
            properties.put(tlsPrefix + ".trust-store", remote.trustStoreUri());
            properties.put(tlsPrefix + ".trust-store-password", TlsRemoteA2aFixture.PASSWORD);
            properties.put(tlsPrefix + ".trust-store-type", "PKCS12");
            properties.put(tlsPrefix + ".verify-hostname", "false");

            try (SutStack stack = start(properties)) {
                assertCall(stack, "agent-a", "secure-remote", "T40_A_TRUSTED", false, "TLS_REMOTE_T40");
                assertThat(remote.httpsRequests()).isEqualTo(1);
                String rejected = call(stack, "agent-b", "secure-remote", "T40_B_UNTRUSTED", false);
                assertThat(rejected).doesNotContain("TLS_REMOTE_T40");
                assertThat(remote.httpsRequests()).isEqualTo(1);
                assertThat(remote.cardRequests()).isEqualTo(2);
            }
        }
    }

    public void t41InvalidLocalConfigurationFailsBeforeReady(InvalidRemoteVariant variant) {
        Map<String, String> properties = new LinkedHashMap<>();
        String local = localPrefix("agent-a");
        switch (variant) {
            case DUPLICATE_NAME -> {
                remote(properties, local, 0, "duplicate", "http://127.0.0.1:1/first");
                remote(properties, local, 1, "duplicate", "http://127.0.0.1:1/second");
            }
            case BLANK_NAME -> remote(properties, local, 0, " ", "http://127.0.0.1:1/blank-name");
            case BLANK_URL -> remote(properties, local, 0, "blank-url", " ");
        }
        assertThatThrownBy(() -> start(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("process exited before becoming ready");
    }

    public void t42LegacyCallerRejectsLocalCatalog() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("openjiuwen.demo.hosted.remote-caller-mode", "legacy-global");
        remote(properties, localPrefix("agent-a"), 0, "local", "http://127.0.0.1:1/local");
        assertThatThrownBy(() -> start(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("process exited before becoming ready");
    }

    public void t43LegacyCallerRetainsGlobalCompatibility() throws Exception {
        try (RemoteA2aCatalogFixture remote = RemoteA2aCatalogFixture.start(Map.of(
                "global", new RouteSpec("LEGACY_GLOBAL_T43", 0)))) {
            Map<String, String> properties = new LinkedHashMap<>();
            properties.put("openjiuwen.demo.hosted.remote-caller-mode", "legacy-global");
            remote(properties, GLOBAL_PREFIX, 0, "legacy-global", remote.baseUrl("global"));
            try (SutStack stack = start(properties)) {
                assertCall(stack, "agent-a", "legacy-global", "T43_A_GLOBAL", false, "LEGACY_GLOBAL_T43");
                assertCall(stack, "agent-b", "legacy-global", "T43_B_GLOBAL", false, "LEGACY_GLOBAL_T43");
            }
            assertThat(remote.a2aRequests("global")).isEqualTo(2);
        }
    }

    private SutStack start(Map<String, String> properties) {
        return SutStack.builder(config).agent(hostedName, agent -> {
            configureHosted(agent, model);
            properties.forEach(agent::property);
        }).start();
    }

    private void assertCall(SutStack stack, String agentId, String tool, String marker, boolean streaming,
            String expectedCanary) throws Exception {
        await("agent=" + agentId + " remote=" + tool + " available")
                .atMost(75, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String response = call(stack, agentId, tool, marker, streaming);
                    assertThat(response).as("agent=%s tool=%s response=%s", agentId, tool, response)
                            .contains(expectedCanary)
                            .doesNotContain("MISSING_TOOL:" + tool);
                });
    }

    private String call(SutStack stack, String agentId, String tool, String marker, boolean streaming)
            throws Exception {
        String text = "CALL_TOOL:" + tool + " REMOTE_INPUT:" + marker;
        String method = streaming ? "SendStreamingMessage" : "SendMessage";
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "ROLE_USER");
        message.put("messageId", UUID.randomUUID().toString());
        message.put("contextId", "ctx-" + UUID.randomUUID());
        message.put("parts", List.of(Map.of("text", text)));
        Map<String, Object> payload = Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", method,
                "params", Map.of("message", message));
        HttpRequest request = HttpRequest.newBuilder(URI.create(stack.baseUrl(hostedName)
                        + "/a2a/agents/" + agentId))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", streaming ? "text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)))
                .build();
        HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        return response.body();
    }

    private static void remote(Map<String, String> properties, String prefix, int index,
            String name, String url) {
        properties.put(prefix + "[" + index + "].name", name);
        properties.put(prefix + "[" + index + "].url", url);
    }

    private static String localPrefix(String agentId) {
        return LOCAL_PREFIX.formatted(agentId);
    }

    private static int reservePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    public record RouteSpec(String canary, long delayMillis) {
    }

    /** Deterministic OpenAI transport that can request exposed tools and score Intent candidates. */
    public static final class ToolCallingOpenAiFixture implements AutoCloseable {
        private static final Pattern TOOL_MARKER = Pattern.compile("CALL_TOOL:([A-Za-z0-9_-]+)");
        private static final Pattern INPUT_MARKER = Pattern.compile("REMOTE_INPUT:([A-Za-z0-9_-]+)");

        private final HttpServer server;
        private final ExecutorService executor;
        private final Map<String, Set<String>> observedTools = new ConcurrentHashMap<>();
        private final AtomicInteger rerankRequests = new AtomicInteger();

        private ToolCallingOpenAiFixture(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
        }

        public static ToolCallingOpenAiFixture start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                ExecutorService executor = Executors.newCachedThreadPool();
                ToolCallingOpenAiFixture fixture = new ToolCallingOpenAiFixture(server, executor);
                server.createContext("/", fixture::handle);
                server.setExecutor(executor);
                server.start();
                return fixture;
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot start FEAT-037 tool model fixture", failure);
            }
        }

        public String apiBase() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        public Set<String> toolsObserved(String marker) {
            return Set.copyOf(observedTools.getOrDefault(marker, Set.of()));
        }

        public int rerankRequests() {
            return rerankRequests.get();
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                JsonNode request = JSON.readTree(exchange.getRequestBody());
                if (exchange.getRequestURI().getPath().endsWith("/rerank")) {
                    replyRerank(exchange, request);
                    return;
                }
                replyChat(exchange, request);
            }
        }

        private void replyRerank(HttpExchange exchange, JsonNode request) throws IOException {
            rerankRequests.incrementAndGet();
            ArrayNode results = JSON.createArrayNode();
            for (int index = 0; index < request.path("documents").size(); index++) {
                results.addObject().put("index", index).put("relevance_score", index == 0 ? 1.0 : 0.1);
            }
            writeJson(exchange, JSON.createObjectNode().set("results", results));
        }

        private void replyChat(HttpExchange exchange, JsonNode request) throws IOException {
            String raw = request.path("messages").toString();
            String tool = marker(TOOL_MARKER, raw);
            String input = marker(INPUT_MARKER, raw);
            Set<String> tools = new LinkedHashSet<>();
            request.path("tools").forEach(node -> tools.add(node.path("function").path("name").asText()));
            if (!input.isBlank()) {
                observedTools.computeIfAbsent(input, ignored -> ConcurrentHashMap.newKeySet()).addAll(tools);
            }

            JsonNode answer;
            if (hasToolResult(request.path("messages"))) {
                answer = finalAnswer(toolResults(request.path("messages")));
            } else if (tool.isBlank() || !tools.contains(tool)) {
                answer = finalAnswer("MISSING_TOOL:" + tool);
            } else {
                String arguments = "intent_match".equals(tool)
                        ? JSON.writeValueAsString(Map.of("semantic", input))
                        : JSON.writeValueAsString(Map.of("remoteInput", input));
                answer = toolAnswer(tool, arguments, input);
            }
            if (request.path("stream").asBoolean()) {
                writeSse(exchange, answer);
            } else {
                writeJson(exchange, completion(answer));
            }
        }

        private static boolean hasToolResult(JsonNode messages) {
            for (JsonNode message : messages) {
                if ("tool".equalsIgnoreCase(message.path("role").asText())) {
                    return true;
                }
            }
            return false;
        }

        private static String toolResults(JsonNode messages) {
            List<String> values = new ArrayList<>();
            for (JsonNode message : messages) {
                if ("tool".equalsIgnoreCase(message.path("role").asText())) {
                    values.add(message.path("content").asText(message.path("content").toString()));
                }
            }
            return String.join(" ", values);
        }

        private static JsonNode toolAnswer(String tool, String arguments, String marker) {
            ObjectNode message = JSON.createObjectNode().put("role", "assistant").putNull("content");
            ObjectNode call = message.putArray("tool_calls").addObject();
            call.put("id", "feat037-" + marker + "-call");
            call.put("type", "function");
            call.putObject("function").put("name", tool).put("arguments", arguments);
            return message;
        }

        private static JsonNode finalAnswer(String content) {
            return JSON.createObjectNode().put("role", "assistant").put("content", "MODEL_FINAL " + content);
        }

        private static ObjectNode completion(JsonNode message) {
            ObjectNode response = base("chat.completion");
            ObjectNode choice = response.putArray("choices").addObject();
            choice.put("index", 0);
            choice.set("message", message);
            choice.put("finish_reason", message.has("tool_calls") ? "tool_calls" : "stop");
            response.putObject("usage").put("prompt_tokens", 1).put("completion_tokens", 1).put("total_tokens", 2);
            return response;
        }

        private static void writeSse(HttpExchange exchange, JsonNode message) throws IOException {
            ObjectNode delta = message.deepCopy();
            String finish = delta.has("tool_calls") ? "tool_calls" : "stop";
            ObjectNode first = base("chat.completion.chunk");
            ObjectNode firstChoice = first.putArray("choices").addObject();
            firstChoice.put("index", 0);
            firstChoice.set("delta", delta);
            firstChoice.putNull("finish_reason");
            ObjectNode last = base("chat.completion.chunk");
            ObjectNode lastChoice = last.putArray("choices").addObject();
            lastChoice.put("index", 0);
            lastChoice.set("delta", JSON.createObjectNode());
            lastChoice.put("finish_reason", finish);
            byte[] body = ("data: " + JSON.writeValueAsString(first) + "\n\n"
                    + "data: " + JSON.writeValueAsString(last) + "\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }

        private static ObjectNode base(String object) {
            ObjectNode response = JSON.createObjectNode();
            response.put("id", "chatcmpl-feat037");
            response.put("object", object);
            response.put("created", 1);
            response.put("model", "feat037-tool-fixture");
            return response;
        }

        private static String marker(Pattern pattern, String raw) {
            Matcher matcher = pattern.matcher(raw);
            return matcher.find() ? matcher.group(1) : "";
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    /** HTTP Agent Card and A2A endpoint with per-route counters and restart support. */
    public static final class RemoteA2aCatalogFixture implements AutoCloseable {
        private final int port;
        private final Map<String, RouteSpec> routes;
        private final Map<String, AtomicInteger> cardRequests = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> a2aRequests = new ConcurrentHashMap<>();
        private final Map<String, List<String>> methods = new ConcurrentHashMap<>();
        private HttpServer server;
        private ExecutorService executor;

        private RemoteA2aCatalogFixture(int port, Map<String, RouteSpec> routes) {
            this.port = port;
            this.routes = Map.copyOf(routes);
            routes.keySet().forEach(route -> {
                cardRequests.put(route, new AtomicInteger());
                a2aRequests.put(route, new AtomicInteger());
                methods.put(route, new CopyOnWriteArrayList<>());
            });
        }

        public static RemoteA2aCatalogFixture start(Map<String, RouteSpec> routes) throws IOException {
            RemoteA2aCatalogFixture fixture = stopped(reservePort(), routes);
            fixture.start();
            return fixture;
        }

        public static RemoteA2aCatalogFixture stopped(int port, Map<String, RouteSpec> routes) {
            return new RemoteA2aCatalogFixture(port, routes);
        }

        public synchronized void start() throws IOException {
            if (server != null) {
                return;
            }
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            executor = Executors.newCachedThreadPool();
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
        }

        public synchronized void stop() {
            if (server != null) {
                server.stop(0);
                server = null;
            }
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
        }

        public String baseUrl(String route) {
            requireRoute(route);
            return "http://127.0.0.1:" + port + "/" + route;
        }

        public int cardRequests(String route) {
            return cardRequests.get(requireRoute(route)).get();
        }

        public int a2aRequests(String route) {
            return a2aRequests.get(requireRoute(route)).get();
        }

        public List<String> methods(String route) {
            return List.copyOf(methods.get(requireRoute(route)));
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                String route = route(path);
                RouteSpec spec = routes.get(route);
                if (spec == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                if (path.endsWith("/.well-known/agent-card.json")) {
                    cardRequests.get(route).incrementAndGet();
                    writeJson(exchange, card(route, spec.canary(), baseUrl(route) + "/a2a"));
                    return;
                }
                if (path.equals("/" + route + "/a2a")) {
                    JsonNode request = JSON.readTree(exchange.getRequestBody());
                    a2aRequests.get(route).incrementAndGet();
                    methods.get(route).add(request.path("method").asText());
                    delay(spec.delayMillis());
                    writeA2a(exchange, request, spec.canary());
                    return;
                }
                exchange.sendResponseHeaders(404, -1);
            }
        }

        private String requireRoute(String route) {
            if (!routes.containsKey(route)) {
                throw new IllegalArgumentException("Unknown fixture route: " + route);
            }
            return route;
        }

        private static String route(String path) {
            String normalized = path.startsWith("/") ? path.substring(1) : path;
            int slash = normalized.indexOf('/');
            return slash < 0 ? normalized : normalized.substring(0, slash);
        }

        @Override
        public void close() {
            stop();
        }
    }

    /** HTTP Card endpoint advertising a self-signed HTTPS A2A target. */
    public static final class TlsRemoteA2aFixture implements AutoCloseable {
        public static final String PASSWORD = "changeit";

        private final Path directory;
        private final Path trustStore;
        private final HttpServer cardServer;
        private final HttpsServer a2aServer;
        private final ExecutorService executor;
        private final String canary;
        private final AtomicInteger cardRequests = new AtomicInteger();
        private final AtomicInteger httpsRequests = new AtomicInteger();

        private TlsRemoteA2aFixture(Path directory, Path trustStore, HttpServer cardServer,
                HttpsServer a2aServer, ExecutorService executor, String canary) {
            this.directory = directory;
            this.trustStore = trustStore;
            this.cardServer = cardServer;
            this.a2aServer = a2aServer;
            this.executor = executor;
            this.canary = canary;
        }

        public static TlsRemoteA2aFixture start(String canary) throws Exception {
            Path directory = Files.createTempDirectory("feat037-tls-");
            Path serverStore = directory.resolve("server.p12");
            Path certificate = directory.resolve("server.crt");
            Path trustStore = directory.resolve("trust.p12");
            keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-storetype", "PKCS12",
                    "-keystore", serverStore.toString(), "-storepass", PASSWORD, "-keypass", PASSWORD,
                    "-dname", "CN=localhost", "-ext", "SAN=ip:127.0.0.1,dns:localhost", "-validity", "2");
            keytool("-exportcert", "-alias", "server", "-keystore", serverStore.toString(),
                    "-storepass", PASSWORD, "-rfc", "-file", certificate.toString());
            keytool("-importcert", "-noprompt", "-alias", "server", "-file", certificate.toString(),
                    "-keystore", trustStore.toString(), "-storetype", "PKCS12", "-storepass", PASSWORD);

            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(serverStore)) {
                keys.load(input, PASSWORD.toCharArray());
            }
            KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            managers.init(keys, PASSWORD.toCharArray());
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(managers.getKeyManagers(), null, null);

            ExecutorService executor = Executors.newCachedThreadPool();
            HttpsServer a2a = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            a2a.setHttpsConfigurator(new HttpsConfigurator(context));
            a2a.setExecutor(executor);
            HttpServer cards = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            cards.setExecutor(executor);
            TlsRemoteA2aFixture fixture = new TlsRemoteA2aFixture(directory, trustStore, cards, a2a,
                    executor, canary);
            a2a.createContext("/a2a", fixture::handleA2a);
            cards.createContext("/secure/.well-known/agent-card.json", fixture::handleCard);
            a2a.start();
            cards.start();
            return fixture;
        }

        public String cardBaseUrl() {
            return "http://127.0.0.1:" + cardServer.getAddress().getPort() + "/secure";
        }

        public String trustStoreUri() {
            return trustStore.toUri().toString();
        }

        public int cardRequests() {
            return cardRequests.get();
        }

        public int httpsRequests() {
            return httpsRequests.get();
        }

        private void handleCard(HttpExchange exchange) throws IOException {
            try (exchange) {
                cardRequests.incrementAndGet();
                String endpoint = "https://127.0.0.1:" + a2aServer.getAddress().getPort() + "/a2a";
                writeJson(exchange, card("secure", canary, endpoint));
            }
        }

        private void handleA2a(HttpExchange exchange) throws IOException {
            try (exchange) {
                JsonNode request = JSON.readTree(exchange.getRequestBody());
                httpsRequests.incrementAndGet();
                writeA2a(exchange, request, canary);
            }
        }

        @Override
        public void close() throws IOException {
            cardServer.stop(0);
            a2aServer.stop(0);
            executor.shutdownNow();
            try (var files = Files.walk(directory)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }

        private static void keytool(String... arguments) throws Exception {
            String executable = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool")
                    .toString();
            List<String> command = new ArrayList<>();
            command.add(executable);
            command.addAll(List.of(arguments));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                throw new IllegalStateException("keytool failed: " + output);
            }
        }
    }

    private static ObjectNode card(String route, String canary, String endpoint) {
        ObjectNode card = JSON.createObjectNode();
        card.put("name", route);
        card.put("description", "FEAT-037 remote " + canary);
        card.put("version", "1.0");
        card.put("url", endpoint);
        card.put("preferredTransport", "JSONRPC");
        card.putObject("capabilities")
                .put("streaming", true)
                .put("pushNotifications", false)
                .put("extendedAgentCard", false)
                .putArray("extensions");
        card.putArray("defaultInputModes").add("text");
        card.putArray("defaultOutputModes").add("text");
        ObjectNode skill = card.putArray("skills").addObject();
        skill.put("id", route + "-skill");
        skill.put("name", route + " skill");
        skill.put("description", "Route " + canary + " requests");
        skill.putArray("tags").add("feat-037");
        skill.putArray("examples");
        skill.putArray("inputModes").add("text");
        skill.putArray("outputModes").add("text");
        skill.putArray("securityRequirements");
        card.putObject("securitySchemes");
        card.putArray("securityRequirements");
        ObjectNode target = card.putArray("supportedInterfaces").addObject();
        target.put("protocolBinding", "JSONRPC");
        target.put("url", endpoint);
        target.put("protocolVersion", "1.0");
        card.putArray("signatures");
        card.putArray("additionalInterfaces");
        return card;
    }

    private static void writeA2a(HttpExchange exchange, JsonNode request, String canary) throws IOException {
        ObjectNode response = JSON.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", request.path("id"));
        ObjectNode message = response.putObject("result").putObject("message");
        message.put("messageId", "reply-" + UUID.randomUUID());
        message.put("role", "ROLE_AGENT");
        message.putArray("parts").addObject().put("text", canary);
        boolean streaming = "SendStreamingMessage".equals(request.path("method").asText());
        byte[] body = (streaming ? "data: " + JSON.writeValueAsString(response) + "\n\n"
                : JSON.writeValueAsString(response)).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
    }

    private static void writeJson(HttpExchange exchange, JsonNode response) throws IOException {
        byte[] body = JSON.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
    }

    private static void delay(long millis) throws IOException {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted fixture response delay", interrupted);
        }
    }
}

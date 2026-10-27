package com.huawei.ascend.sit.cases.integration.react_travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.AgentCard;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Input-only peer: serves documented A2A wire events; never implements Runtime projection or extraction. */
final class Feat086A2aFixture implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper();
    final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    final List<String> errors = new CopyOnWriteArrayList<>();
    final HttpServer server;
    final ExecutorService executor = Executors.newCachedThreadPool();
    final Path evidence;
    final java.util.concurrent.atomic.AtomicInteger evidenceSequence = new java.util.concurrent.atomic.AtomicInteger();
    volatile Function<JsonNode, List<Map<String, Object>>> replies;

    Feat086A2aFixture(Path evidence, List<Map<String, Object>> replies) throws Exception {
        this.evidence = evidence;
        Files.createDirectories(evidence);
        this.replies = request -> replies;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try (exchange) {
                boolean get = exchange.getRequestMethod().equals("GET");
                String content;
                boolean streaming = false;
                if (get) content = JSON.writeValueAsString(cardData());
                else {
                    JsonNode request = JSON.readTree(exchange.getRequestBody());
                    requests.add(request);
                    int index = evidenceSequence.incrementAndGet();
                    Files.writeString(evidence.resolve("request-" + index + ".json"), request.toString());
                    streaming = request.path("method").asText().equals("SendStreamingMessage");
                    List<Map<String, Object>> results = this.replies.apply(request);
                    StringBuilder body = new StringBuilder();
                    for (Map<String, Object> result : results) {
                        Map<String, Object> response = new LinkedHashMap<>();
                        response.put("jsonrpc", "2.0"); response.put("id", request.get("id")); response.put("result", result);
                        body.append(streaming ? "data: " : "").append(JSON.writeValueAsString(response));
                        if (streaming) body.append("\n\n");
                    }
                    content = body.toString();
                    Files.writeString(evidence.resolve("response-" + index + (streaming ? ".sse" : ".json")), content);
                }
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Exception error) {
                errors.add(error.getClass().getName() + ": " + error.getMessage());
            }
        });
        server.start();
    }
    String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    AgentCard card() throws Exception { return JsonUtil.fromJson(JSON.writeValueAsString(cardData()), AgentCard.class); }
    Map<String, Object> cardData() {
        Map<String,Object> card=new LinkedHashMap<>(Map.of("name", "travel-trip", "description", "Controlled travel peer for FEAT086", "version", "1.0",
                "capabilities", Map.of("streaming", true, "pushNotifications", true,"extendedAgentCard",false,"extensions",List.of()),
                "defaultInputModes", List.of("text"), "defaultOutputModes", List.of("text"),
                "skills", List.of(Map.of("id", "trip", "name", "travel-trip", "description", "Plan the requested business trip", "tags", List.of("travel"),"examples",List.of(),"inputModes",List.of("text"),"outputModes",List.of("text"),"securityRequirements",List.of())),
                "supportedInterfaces", List.of(Map.of("url", baseUrl() + "/a2a", "protocolBinding", "JSONRPC", "protocolVersion", "1.0"))));
        card.put("provider",Map.of("organization","acceptance","url",""));card.put("securitySchemes",Map.of());card.put("securityRequirements",List.of());card.put("signatures",List.of());
        return card;
    }
    static Map<String, Object> text(String text) { return Map.of("text", text); }
    static Map<String, Object> data(Object data) { return Map.of("data", data); }
    static Map<String, Object> message(List<Map<String, Object>> parts) {
        return Map.of("messageId", UUID.randomUUID().toString(), "role", "ROLE_AGENT", "parts", parts);
    }
    static Map<String, Object> artifact(String id, List<Map<String, Object>> parts, Map<String, Object> metadata) {
        return Map.of("artifactId", id, "parts", parts, "metadata", metadata);
    }
    static Map<String, Object> task(String state, List<Map<String, Object>> artifacts, List<Map<String, Object>> parts) {
        Map<String,Object> status = new LinkedHashMap<>(); status.put("state", "TASK_STATE_" + state);
        if (!parts.isEmpty()) status.put("message", message(parts));
        return new LinkedHashMap<>(Map.of("id", "remote-task", "contextId", "remote-context", "status",
                status, "artifacts", artifacts));
    }
    static Map<String, Object> status(String state, String text) {
        return Map.of("statusUpdate", Map.of("taskId", "remote-task", "contextId", "remote-context", "status",
                Map.of("state", "TASK_STATE_" + state, "message", message(List.of(text(text))))));
    }
    static Map<String, Object> update(String id, String text, boolean append) {
        return Map.of("artifactUpdate", Map.of("taskId", "remote-task", "contextId", "remote-context",
                "artifact", artifact(id, List.of(text(text)), Map.of()), "append", append, "lastChunk", false));
    }
    public void close() { server.stop(0); executor.shutdownNow(); }
}

package com.huawei.ascend.sit.cases.integration.tscript;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ToolScriptWireClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    Run send(String baseUrl, String contextId, String prompt) throws Exception {
        ObjectNode message = JSON.createObjectNode();
        message.put("role", "ROLE_USER");
        message.put("messageId", "msg-" + UUID.randomUUID());
        message.put("contextId", contextId);
        message.putArray("parts").addObject().put("text", prompt);

        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", "req-" + UUID.randomUUID());
        envelope.put("method", "SendStreamingMessage");
        envelope.putObject("params").set("message", message);

        HttpRequest request = HttpRequest.newBuilder(URI.create(stripSlash(baseUrl) + "/a2a"))
                .timeout(Duration.ofSeconds(180))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(envelope)))
                .build();
        HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
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
        List<JsonNode> scripts = new ArrayList<>();
        frames.forEach(frame -> collectScriptEvents(frame, scripts, 0));
        return new Run(response.statusCode(), List.copyOf(frames), List.copyOf(scripts), raw.toString());
    }

    Map<String, Integer> counts(String baseUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(stripSlash(baseUrl) + "/__sit/tool-scripts/counts"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Fixture count endpoint returned " + response.statusCode());
        }
        return JSON.readValue(response.body(), JSON.getTypeFactory()
                .constructMapType(Map.class, String.class, Integer.class));
    }

    void clearCounts(String baseUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(stripSlash(baseUrl) + "/__sit/tool-scripts/counts"))
                .timeout(Duration.ofSeconds(10))
                .DELETE()
                .build();
        HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Fixture count reset returned " + response.statusCode());
        }
    }

    private static void collectScriptEvents(JsonNode node, List<JsonNode> out, int depth) {
        if (node == null || node.isNull() || depth > 30) {
            return;
        }
        if (node.isObject()) {
            String event = node.path("event").asText("");
            if (("tool_start".equals(event) || "tool_end".equals(event))
                    && node.has("tool") && node.has("content")) {
                out.add(node.deepCopy());
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                collectScriptEvents(fields.next().getValue(), out, depth + 1);
            }
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectScriptEvents(child, out, depth + 1));
            return;
        }
        if (node.isTextual()) {
            String text = node.asText().trim();
            if (text.startsWith("{") || text.startsWith("[")) {
                try {
                    collectScriptEvents(JSON.readTree(text), out, depth + 1);
                } catch (Exception ignored) {
                    // Ordinary model text is not a structured event.
                }
            }
        }
    }

    private static String stripSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    record Run(int statusCode, List<JsonNode> frames, List<JsonNode> scriptEvents, String raw) {
        List<JsonNode> events(String tool, String event) {
            return scriptEvents.stream()
                    .filter(node -> tool.equals(node.path("tool").asText()))
                    .filter(node -> event.equals(node.path("event").asText()))
                    .toList();
        }
    }
}

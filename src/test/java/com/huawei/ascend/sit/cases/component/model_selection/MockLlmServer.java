package com.huawei.ascend.sit.cases.component.model_selection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Lightweight OpenAI-compatible mock LLM server for FEAT-050/051 acceptance tests.
 *
 * <p>Starts on a random port, returns a fixed deterministic content per endpoint, and records
 * every received request (path, Authorization header, body model field, full body) so tests can
 * assert which model was actually invoked. Supports an error mode (200/401/500) that can be
 * switched at runtime for failure-injection scenarios (SC-FAIL-01~03, SC-CORE-13~15).</p>
 */
final class MockLlmServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final ExecutorService executor;
    private final String identityContent;
    private final String mockModelName;
    private final List<RecordedRequest> calls = new CopyOnWriteArrayList<>();
    private final AtomicReference<ErrorMode> errorMode = new AtomicReference<>(ErrorMode.OK);

    enum ErrorMode {
        OK,
        UNAUTHORIZED_401,
        SERVER_ERROR_500
    }

    /** Immutable snapshot of one received request. */
    record RecordedRequest(String path, String authorization, String model, String body) {}

    private MockLlmServer(String identityContent, String mockModelName) throws IOException {
        this.identityContent = identityContent;
        this.mockModelName = mockModelName;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    static MockLlmServer start(String identityContent, String mockModelName) throws IOException {
        return new MockLlmServer(identityContent, mockModelName);
    }

    /** Base URL in OpenAI-compatible form, e.g. {@code http://127.0.0.1:38211/v1}. */
    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** Switch the error mode at runtime (for failure-injection scenarios). */
    void setErrorMode(ErrorMode mode) {
        errorMode.set(mode);
    }

    /** All requests received so far. */
    List<RecordedRequest> calls() {
        return new ArrayList<>(calls);
    }

    /** Count of requests received. */
    int callCount() {
        return calls.size();
    }

    /** The first recorded request, or null if none. */
    RecordedRequest firstCall() {
        return calls.isEmpty() ? null : calls.get(0);
    }

    /** Await at least {@code n} recorded calls within the given timeout (ms). */
    void awaitCalls(int n, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (calls.size() < n && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode request = JSON.readTree(body);
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            String model = request.path("model").asText("");
            calls.add(new RecordedRequest(exchange.getRequestURI().getPath(), 
                    authorization == null ? "" : authorization, model, body));

            ErrorMode mode = errorMode.get();
            if (mode == ErrorMode.UNAUTHORIZED_401) {
                byte[] error = JSON.writeValueAsBytes(Map.of("error", Map.of(
                        "message", "controlled model rejection", "type", "authentication_error",
                        "code", "controlled_rejection")));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(401, error.length);
                exchange.getResponseBody().write(error);
                return;
            }
            if (mode == ErrorMode.SERVER_ERROR_500) {
                byte[] error = JSON.writeValueAsBytes(Map.of("error", Map.of(
                        "message", "controlled server error", "type", "server_error")));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, error.length);
                exchange.getResponseBody().write(error);
                return;
            }

            boolean stream = request.path("stream").asBoolean(false);
            String content = identityContent;
            if (stream) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                String first = JSON.writeValueAsString(envelope("chat.completion.chunk",
                        Map.of("index", 0, "delta", Map.of("role", "assistant", "content", content))));
                String last = JSON.writeValueAsString(envelope("chat.completion.chunk",
                        Map.of("index", 0, "delta", Map.of(), "finish_reason", "stop")));
                byte[] response = ("data: " + first + "\n\ndata: " + last + "\n\ndata: [DONE]\n\n")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } else {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                byte[] response = JSON.writeValueAsBytes(envelope("chat.completion",
                        Map.of("index", 0,
                                "message", Map.of("role", "assistant", "content", content),
                                "finish_reason", "stop")));
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        } catch (RuntimeException e) {
            try (exchange) {
                byte[] error = ("{\"error\":\"" + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, error.length);
                exchange.getResponseBody().write(error);
            }
        }
    }

    private Map<String, Object> envelope(String object, Map<String, Object> choice) {
        return Map.of("id", "mock-llm-" + mockModelName, "object", object, "created", 1,
                "model", mockModelName, "choices", List.of(choice));
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}

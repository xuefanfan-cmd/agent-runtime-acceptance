package com.huawei.ascend.sit.cases.integration.tscript;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local model-call counter used by the TS038-C16 probe.
 *
 * <p>It forwards every request to the real LLM endpoint recorded in {@code LLM_API_BASE} and streams the
 * response back byte for byte. It never rewrites the request or the response; the only side effect is
 * counting forwarded calls in the test JVM.
 */
final class LlmCountingProxy implements AutoCloseable {
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "host", "content-length", "connection", "keep-alive", "transfer-encoding", "upgrade",
            "proxy-connection", "expect");
    private static final Set<String> STRIPPED_RESPONSE_HEADERS = Set.of(
            "content-length", "transfer-encoding", "connection", "keep-alive");

    private final HttpServer server;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final String upstreamBase;
    private final AtomicInteger calls = new AtomicInteger();
    private final Map<String, AtomicInteger> callsByPath = new ConcurrentHashMap<>();

    private LlmCountingProxy(String upstreamBase) throws IOException {
        this.upstreamBase = upstreamBase.endsWith("/")
                ? upstreamBase.substring(0, upstreamBase.length() - 1)
                : upstreamBase;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", this::forward);
        this.server.setExecutor(Executors.newCachedThreadPool());
        this.server.start();
    }

    static LlmCountingProxy start() throws IOException {
        String upstream = System.getenv("LLM_API_BASE");
        if (upstream == null || upstream.isBlank()) {
            throw new IllegalStateException("LLM_API_BASE is not available to the test JVM");
        }
        return new LlmCountingProxy(upstream);
    }

    /** Base URL to hand to the SUT; the SUT appends {@code /chat/completions} itself. */
    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    int calls() {
        return calls.get();
    }

    Map<String, Integer> callsByPath() {
        Map<String, Integer> snapshot = new ConcurrentHashMap<>();
        callsByPath.forEach((key, value) -> snapshot.put(key, value.get()));
        return snapshot;
    }

    void reset() {
        calls.set(0);
        callsByPath.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void forward(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            calls.incrementAndGet();
            callsByPath.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
            byte[] body = exchange.getRequestBody().readAllBytes();
            HttpRequest request = upstreamRequest(exchange, path, body);
            HttpResponse<InputStream> response = client.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            response.headers().map().forEach((name, values) -> copyResponseHeader(exchange, name, values));
            exchange.sendResponseHeaders(response.statusCode(), 0);
            try (InputStream in = response.body(); OutputStream out = exchange.getResponseBody()) {
                byte[] buffer = new byte[8192];
                int read = in.read(buffer);
                while (read >= 0) {
                    out.write(buffer, 0, read);
                    out.flush();
                    read = in.read(buffer);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            try {
                exchange.sendResponseHeaders(502, -1);
            } catch (RuntimeException ignored) {
                // The response already started; the caller observes the interrupted stream.
            }
        } finally {
            exchange.close();
        }
    }

    private HttpRequest upstreamRequest(HttpExchange exchange, String path, byte[] body) {
        String relative = path;
        if (upstreamBase.endsWith("/v1") && relative.startsWith("/v1/")) {
            relative = relative.substring("/v1".length());
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(upstreamBase + relative));
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (values == null || values.isEmpty() || HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                return;
            }
            try {
                builder.header(name, values.get(0));
            } catch (IllegalArgumentException ignored) {
                // Restricted header names are dropped; the upstream call still carries the payload.
            }
        });
        HttpRequest.BodyPublisher publisher = body.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);
        return builder.method(exchange.getRequestMethod(), publisher).build();
    }

    private void copyResponseHeader(HttpExchange exchange, String name, List<String> values) {
        if (name == null || values == null || values.isEmpty()
                || STRIPPED_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
            return;
        }
        try {
            exchange.getResponseHeaders().put(name, new ArrayList<>(values));
        } catch (RuntimeException ignored) {
            // Header relay is best effort; the status code and body are authoritative.
        }
    }
}

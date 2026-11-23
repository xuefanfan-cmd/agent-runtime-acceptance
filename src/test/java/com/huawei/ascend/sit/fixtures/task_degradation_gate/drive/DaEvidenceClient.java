package com.huawei.ascend.sit.fixtures.task_degradation_gate.drive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DaEvidenceClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final URI base;
    private final String secret;
    private final Path output;
    private final java.util.concurrent.atomic.AtomicInteger sequence = new java.util.concurrent.atomic.AtomicInteger();

    public DaEvidenceClient(String baseUrl, String secret, String caseId) throws Exception {
        base = URI.create(baseUrl);
        if (!List.of("http", "https").contains(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
            throw new IllegalArgumentException("baseUrl must be an HTTP origin without credentials/query/fragment");
        }
        if (!caseId.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("invalid case ID");
        this.secret = secret;
        output = Path.of("target", "feat112-evidence", caseId + "-" + UUID.randomUUID());
        Files.createDirectories(output);
    }

    public record Exchange(int status, Map<String, List<String>> headers, String body, String evidenceRef) {
        public JsonNode json() throws Exception { return JSON.readTree(body); }
    }

    public Exchange get(String path) throws Exception { return send("GET", path, null, Map.of()); }
    public Exchange post(String path, Object body) throws Exception {
        return send("POST", path, JSON.writeValueAsString(body), Map.of());
    }
    /** POST with extra request headers (for example {@code Idempotency-Key}); header values are not persisted. */
    public Exchange post(String path, Object body, Map<String, String> headers) throws Exception {
        return send("POST", path, JSON.writeValueAsString(body), headers);
    }

    private Exchange send(String method, String path, String body, Map<String, String> headers) throws Exception {
        if (!path.startsWith("/") || path.startsWith("//")) throw new IllegalArgumentException("absolute path required");
        URI uri = base.resolve(path);
        if (!base.getAuthority().equals(uri.getAuthority())) throw new IllegalArgumentException("origin changed");
        // Upper bound only: a held task's run request stays open while the test builds other gate states, which
        // takes minutes on a DA slowed by libfaketime (clock-control forms).
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(600));
        if (secret != null && !secret.isBlank()) builder.header("X-Engine-Secret", secret);
        headers.forEach(builder::header);
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        Path file = output.resolve(String.format("%03d.json", sequence.incrementAndGet()));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("method", method);
        evidence.put("path", path);
        evidence.put("startedAt", Instant.now().toString());
        // Request content and authorization are deliberately not persisted.
        Exception primary = null;
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            evidence.put("status", response.statusCode());
            evidence.put("contentType", response.headers().firstValue("Content-Type").orElse(""));
            evidence.put("retryAfter", response.headers().firstValue("Retry-After").orElse(""));
            evidence.put("body", redact(response.body()));
            evidence.put("collection", "complete");
            return new Exchange(response.statusCode(), response.headers().map(), response.body(), file.toString());
        } catch (Exception failure) {
            primary = failure;
            evidence.put("collection", "failed");
            evidence.put("failureType", failure.getClass().getName());
            throw failure;
        } finally {
            evidence.put("endedAt", Instant.now().toString());
            try {
                Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(evidence), StandardCharsets.UTF_8);
            } catch (Exception persistenceFailure) {
                if (primary != null) primary.addSuppressed(persistenceFailure);
                else throw persistenceFailure;
            }
        }
    }

    /** What an SSE request produced; judging it belongs to the caller. */
    public record SseCapture(int status, String contentType, boolean startObserved, String evidenceRef) {
        public boolean servedWithStart() {
            return status == 200 && contentType.startsWith("text/event-stream") && startObserved;
        }
    }

    /** Capture replay through a complete start frame, then deliberately disconnect the observer. */
    public SseCapture disconnectAfterStart(String path) throws Exception {
        return disconnectAfterStart(path, null);
    }
    /** Submit the ORIGINAL run-stream and disconnect only after its complete start frame. */
    public SseCapture disconnectRunStreamAfterStart(Object request) throws Exception {
        return disconnectAfterStart("/api/agents/run-stream", JSON.writeValueAsString(request));
    }
    private SseCapture disconnectAfterStart(String path, String request) throws Exception {
        if (!path.startsWith("/") || path.startsWith("//")) throw new IllegalArgumentException("absolute path required");
        URI uri = base.resolve(path);
        if (!base.getAuthority().equals(uri.getAuthority())) throw new IllegalArgumentException("origin changed");
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).header("Accept", "text/event-stream");
        if (request == null) builder.GET();
        else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(request));
        if (secret != null && !secret.isBlank()) builder.header("X-Engine-Secret", secret);
        Path file = output.resolve(String.format("%03d-sse.json", sequence.incrementAndGet()));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("path", path); evidence.put("startedAt", Instant.now().toString());
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        evidence.put("status", response.statusCode()); evidence.put("contentType", contentType);
        java.util.concurrent.ExecutorService reader = java.util.concurrent.Executors.newSingleThreadExecutor();
        Throwable primary = null;
        try (var body = response.body()) {
            if (response.statusCode() != 200 || !contentType.startsWith("text/event-stream")) {
                evidence.put("body", redact(new String(body.readNBytes(65536), StandardCharsets.UTF_8)));
                evidence.put("collection", "not_sse");
                return new SseCapture(response.statusCode(), contentType, false, file.toString());
            }
            var captured = reader.submit(() -> {
                var lines = new java.io.BufferedReader(new java.io.InputStreamReader(body, StandardCharsets.UTF_8));
                StringBuilder raw = new StringBuilder(); boolean start = false;
                for (String line; (line = lines.readLine()) != null;) {
                    raw.append(line).append('\n');
                    if (raw.length() > 262144) throw new IllegalStateException("SSE collection limit before start");
                    if (line.startsWith("event:") && "start".equals(line.substring(6).trim())) start = true;
                    if (line.isEmpty() && start) return raw.toString();
                }
                evidence.put("frames", redact(raw.toString()));
                return null;
            });
            String raw = captured.get(15, java.util.concurrent.TimeUnit.SECONDS);
            if (raw == null) {
                evidence.put("collection", "eof_before_start");
                return new SseCapture(response.statusCode(), contentType, false, file.toString());
            }
            evidence.put("frames", redact(raw)); evidence.put("collection", "intentional_disconnect_after_start");
            return new SseCapture(response.statusCode(), contentType, true, file.toString());
        } catch (Exception | Error failure) {
            primary = failure;
            evidence.put("collection", "failed"); evidence.put("failureType", failure.getClass().getName());
            throw failure;
        } finally {
            reader.shutdownNow(); evidence.put("endedAt", Instant.now().toString());
            try {
                Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(evidence), StandardCharsets.UTF_8);
            } catch (Exception persistenceFailure) {
                if (primary != null) primary.addSuppressed(persistenceFailure);
                else throw persistenceFailure;
            }
        }
    }

    private String redact(String text) {
        return secret == null || secret.isBlank() ? text : text.replace(secret, "[REDACTED]");
    }
}

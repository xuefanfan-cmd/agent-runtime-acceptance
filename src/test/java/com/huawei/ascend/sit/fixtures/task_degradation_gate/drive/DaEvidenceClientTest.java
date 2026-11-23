package com.huawei.ascend.sit.fixtures.task_degradation_gate.drive;


import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DaEvidenceClientTest {
    @Test void sseDistinguishesObservedStartFromPrematureEof() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/start", e -> {
            byte[] data = "id: 1\nevent: start\ndata: {\"taskId\":\"fixture\"}\n\n".getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "text/event-stream");
            e.sendResponseHeaders(200, data.length); e.getResponseBody().write(data); e.close();
        });
        server.createContext("/eof", e -> {
            byte[] data = "event: other\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "text/event-stream");
            e.sendResponseHeaders(200, data.length); e.getResponseBody().write(data); e.close();
        });
        server.createContext("/json", e -> {
            byte[] data = "{\"code\":\"ENGINE_DEGRADED\"}".getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(503, data.length); e.getResponseBody().write(data); e.close();
        });
        server.start();
        try {
            var client = new DaEvidenceClient("http://127.0.0.1:" + server.getAddress().getPort(), null, "collector-sse-selftest");
            var started = client.disconnectAfterStart("/start");
            assertTrue(started.servedWithStart());
            assertTrue(Files.readString(Path.of(started.evidenceRef())).contains("intentional_disconnect_after_start"));
            var premature = client.disconnectAfterStart("/eof");
            assertFalse(premature.servedWithStart(), "EOF before the start frame is evidence, not a served stream");
            assertTrue(Files.readString(Path.of(premature.evidenceRef())).contains("eof_before_start"));
            var json = client.disconnectAfterStart("/json");
            assertEquals(503, json.status());
            assertFalse(json.servedWithStart());
            assertTrue(Files.readString(Path.of(json.evidenceRef())).contains("not_sse"));
            // With no collection failure, a persistence failure is reported rather than lost.
            Path output = Path.of(started.evidenceRef()).getParent();
            try (var paths = Files.list(output)) { for (Path p : paths.toList()) Files.delete(p); }
            Files.delete(output); Files.writeString(output, "not a directory");
            try {
                assertThrows(java.io.IOException.class, () -> client.disconnectAfterStart("/eof"));
            } finally { Files.delete(output); }
        } finally { server.stop(0); }
    }
    @Test void collectsConcurrentResponsesWithoutSerializingRequestsAndRedactsSecret() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService serverThreads = Executors.newCachedThreadPool();
        ExecutorService callers = Executors.newFixedThreadPool(2);
        CountDownLatch arrived = new CountDownLatch(2);
        server.setExecutor(serverThreads);
        server.createContext("/collect", exchange -> {
            arrived.countDown();
            try {
                boolean concurrent = arrived.await(3, TimeUnit.SECONDS);
                byte[] body = ("{\"concurrent\":" + concurrent + ",\"echo\":\"sentinel-secret\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var client = new DaEvidenceClient("http://127.0.0.1:" + server.getAddress().getPort(), "sentinel-secret", "collector-selftest");
            var one = callers.submit(() -> client.get("/collect"));
            var two = callers.submit(() -> client.get("/collect"));
            var a = one.get(10, TimeUnit.SECONDS); var b = two.get(10, TimeUnit.SECONDS);
            assertTrue(a.json().path("concurrent").asBoolean()); assertTrue(b.json().path("concurrent").asBoolean());
            assertNotEquals(a.evidenceRef(), b.evidenceRef());
            for (var response : List.of(a, b)) {
                String persisted = Files.readString(Path.of(response.evidenceRef()));
                assertFalse(persisted.contains("sentinel-secret")); assertTrue(persisted.contains("[REDACTED]"));
                assertTrue(persisted.contains("complete")); assertTrue(persisted.contains("startedAt")); assertTrue(persisted.contains("endedAt"));
            }
            assertThrows(IllegalArgumentException.class, () -> client.get("//other.example/path"));
        } finally { server.stop(0); serverThreads.shutdownNow(); callers.shutdownNow(); }
    }
}

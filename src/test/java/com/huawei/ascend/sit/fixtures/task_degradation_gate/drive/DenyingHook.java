package com.huawei.ascend.sit.fixtures.task_degradation_gate.drive;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A test-owned http hook target that denies every call it receives ({@code {"allowed":false}}, the hook protocol's
 * explicit refusal) and counts the calls, so a scenario can prove each tool attempt reached the hook and was denied.
 */
public final class DenyingHook implements AutoCloseable {
    private static final byte[] DENY = "{\"allowed\":false,\"error\":\"feat112 guardrail denial\"}"
            .getBytes(StandardCharsets.UTF_8);
    private final HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();

    public DenyingHook() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/hook", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                if ("POST".equals(exchange.getRequestMethod())) calls.incrementAndGet();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, DENY.length);
                exchange.getResponseBody().write(DENY);
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
    }

    /** Hook endpoint (loopback address, ephemeral port). */
    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    /** POSTs received so far. */
    public int calls() {
        return calls.get();
    }

    @Override
    public void close() {
        server.stop(0);
        if (server.getExecutor() instanceof java.util.concurrent.ExecutorService executor) executor.shutdownNow();
    }
}

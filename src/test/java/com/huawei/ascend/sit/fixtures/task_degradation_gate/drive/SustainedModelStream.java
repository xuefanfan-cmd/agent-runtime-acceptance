package com.huawei.ascend.sit.fixtures.task_degradation_gate.drive;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Sustained OpenAI-compatible delta source. Counters describe producer pressure, NOT DA queue overflow. */
public final class SustainedModelStream implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final AtomicLong frames = new AtomicLong(), bytes = new AtomicLong(), blockedWrites = new AtomicLong();
    private volatile boolean closed;
    public SustainedModelStream(int frameCount, int contentBytes) throws IOException {
        if (frameCount < 1 || frameCount > 100000 || contentBytes < 1 || contentBytes > 65536)
            throw new IllegalArgumentException("bounded stream parameters required");
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(workers);
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                // Drain bounded request; never store prompts or authorization.
                if (exchange.getRequestBody().readNBytes(1048577).length > 1048576) { exchange.sendResponseHeaders(413, -1); return; }
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                String content = "x".repeat(contentBytes);
                byte[] delta = ("data: {\"id\":\"pressure\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + content + "\"},\"finish_reason\":null}]}\n\n").getBytes(StandardCharsets.UTF_8);
                var out = exchange.getResponseBody();
                for (int i = 0; i < frameCount && !closed; i++) {
                    long start = System.nanoTime(); out.write(delta); out.flush();
                    if (System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(100)) blockedWrites.incrementAndGet();
                    frames.incrementAndGet(); bytes.addAndGet(delta.length);
                }
                out.write("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            } catch (IOException disconnected) { /* intentional slow-client disconnect is not a DA outcome */ }
        });
        server.start();
    }
    public String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }
    public long emittedFrames() { return frames.get(); }
    public long emittedBytes() { return bytes.get(); }
    public long blockedWrites() { return blockedWrites.get(); }
    @Override public void close() throws InterruptedException {
        closed = true; server.stop(0); workers.shutdownNow();
        if (!workers.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("stream workers did not stop");
    }
}

package com.huawei.ascend.sit.fixtures.task_degradation_gate.drive;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Deliberately tiny TCP receive buffer; bounded slow reads avoid HttpClient prefetch hiding pressure. */
public final class SlowSseConsumer implements AutoCloseable {
    private final Socket socket = new Socket();
    private long bytes;
    public SlowSseConsumer(URI endpoint, String jsonBody, String secret) throws IOException {
        if (!"http".equals(endpoint.getScheme()) || !InetAddress.getByName(endpoint.getHost()).isLoopbackAddress())
            throw new IllegalArgumentException("Slow consumer is restricted to owned local HTTP services");
        if (secret != null && (secret.contains("\r") || secret.contains("\n"))) throw new IllegalArgumentException("invalid credential");
        try {
            socket.setReceiveBufferSize(1024);
            socket.connect(new InetSocketAddress(endpoint.getHost(), endpoint.getPort()), 5000);
            socket.setSoTimeout(1000);
            byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
            String request = "POST " + endpoint.getRawPath() + " HTTP/1.1\r\nHost: " + endpoint.getAuthority()
                    + "\r\nContent-Type: application/json\r\nAccept: text/event-stream\r\nConnection: close\r\n"
                    + (secret == null || secret.isBlank() ? "" : "X-Engine-Secret: " + secret + "\r\n")
                    + "Content-Length: " + body.length + "\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().write(body); socket.getOutputStream().flush();
        } catch (IOException | RuntimeException failure) { socket.close(); throw failure; }
    }
    public long consume(Duration duration, Duration pause) throws IOException, InterruptedException {
        if (duration.isNegative() || pause.isNegative() || pause.isZero()) throw new IllegalArgumentException("positive pace required");
        long end = System.nanoTime() + duration.toNanos(); byte[] chunk = new byte[128];
        while (System.nanoTime() < end) {
            try { int n = socket.getInputStream().read(chunk); if (n < 0) break; bytes += n; }
            catch (SocketTimeoutException wait) { /* deadline remains authoritative */ }
            Thread.sleep(pause.toMillis());
        }
        return bytes;
    }
    public long bytesRead() { return bytes; }
    @Override public void close() throws IOException { socket.close(); }
}

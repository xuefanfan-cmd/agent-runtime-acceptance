package com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy;

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * The demo-owned {@code LlmFaultInjectionStub} in its own process, started from the provisioned carrier jar.
 * The stub is developed on the demo side; the test only owns its lifecycle.
 */
public final class ModelStubProcess implements AutoCloseable {
    public static final String MAIN = "com.openjiuwen.example.degradation.LlmFaultInjectionStub";
    private static final String LAUNCHER = "org.springframework.boot.loader.launch.PropertiesLauncher";

    private final IsolatedDefaultProcess process;
    private final int port;

    private ModelStubProcess(IsolatedDefaultProcess process, int port) {
        this.process = process;
        this.port = port;
    }

    public static ModelStubProcess start(Path carrierJar) throws Exception {
        if (!Files.isRegularFile(carrierJar)) throw new IllegalArgumentException("carrier jar missing: " + carrierJar);
        int port;
        try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        Path directory = Files.createTempDirectory("feat112-stub-");
        var process = new IsolatedDefaultProcess(List.of(IsolatedDefaultProcess.javaExecutable(), "-Dloader.main=" + MAIN,
                "-cp", carrierJar.toAbsolutePath().toString(), LAUNCHER, Integer.toString(port)), directory, List.of());
        var stub = new ModelStubProcess(process, port);
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!stub.ready()) {
                if (!process.isAlive()) throw new IllegalStateException("model stub exited: " + process.diagnostics());
                if (System.nanoTime() > deadline) throw new IllegalStateException("model stub not ready: " + process.diagnostics());
                Thread.sleep(100);
            }
            return stub;
        } catch (Exception | Error failure) {
            try { stub.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private boolean ready() {
        try {
            var connection = (HttpURLConnection) URI.create(url() + "/__test/stats").toURL().openConnection();
            connection.setConnectTimeout(500);
            connection.setReadTimeout(1000);
            try {
                return connection.getResponseCode() == 200;
            } finally {
                connection.disconnect();
            }
        } catch (Exception notYet) {
            return false;
        }
    }

    public String url() {
        return "http://127.0.0.1:" + port;
    }

    public long pid() {
        return process.pid();
    }

    @Override
    public void close() throws Exception {
        process.close();
    }
}

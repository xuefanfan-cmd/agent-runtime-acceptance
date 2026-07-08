package com.huawei.ascend.sit.cases.openjiuwen;

import com.huawei.ascend.sit.config.TestConfig;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Managed subprocess for {@code travel-openjiuwen-test-support} Mock MCP ({@code demo_echo}).
 *
 * <p>Resolves the executable jar from {@code ~/.m2} and binds an ephemeral port when the preferred
 * port is unavailable.</p>
 */
public final class MockMcpServerProcess implements AutoCloseable {

    private static final String MCP_HOST = "127.0.0.1";
    private static final String GROUP = "com.huawei.ascend.examples";
    private static final String ARTIFACT = "travel-openjiuwen-test-support";
    private static final String VERSION = "0.1.0";

    private final Process process;
    private final int port;
    private final Path logFile;

    private MockMcpServerProcess(Process process, int port, Path logFile) {
        this.process = process;
        this.port = port;
        this.logFile = logFile;
    }

    public static MockMcpServerProcess start(TestConfig config, int preferredPort) throws IOException, InterruptedException {
        int port = resolvePort(preferredPort);
        Path jar = resolveJar(config);
        Path logFile = resolveLogFile(config);
        Files.createDirectories(logFile.getParent());
        if (Files.exists(logFile)) {
            Files.delete(logFile);
        }

        List<String> command = new ArrayList<>();
        command.add(javaBin());
        command.add("-cp");
        command.add(resolveRuntimeClasspath(jar));
        command.add("com.openjiuwen.service.travel.testsupport.mcp.MockMcpServerExample");
        command.add("--port=" + port);

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                .start();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(startupSeconds(config));
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException(
                        "Mock MCP process exited before becoming ready (port=" + port + "). See " + logFile);
            }
            try {
                if (OpenjiuwenMcpToolCallGate.toolsListContainsDemoEcho(MCP_HOST, port)) {
                    return new MockMcpServerProcess(process, port, logFile);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            } catch (IOException ignored) {
                // Mock MCP still starting
            }
            Thread.sleep(200);
        }
        process.destroyForcibly();
        throw new IllegalStateException(
                "Mock MCP server not ready on " + MCP_HOST + ":" + port + " within "
                        + startupSeconds(config) + "s. See " + logFile);
    }

    public String host() {
        return MCP_HOST;
    }

    public int port() {
        return port;
    }

    Path logFile() {
        return logFile;
    }

    @Override
    public void close() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    private static int resolvePort(int preferredPort) throws IOException {
        if (preferredPort > 0) {
            try (ServerSocket probe = new ServerSocket(preferredPort, 1, java.net.InetAddress.getByName(MCP_HOST))) {
                return preferredPort;
            } catch (IOException ignored) {
                // fall through to ephemeral
            }
        }
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getByName(MCP_HOST))) {
            return socket.getLocalPort();
        }
    }

    private static String resolveRuntimeClasspath(Path testSupportJar) {
        String surefireClasspath = System.getProperty("java.class.path");
        if (surefireClasspath != null && !surefireClasspath.isBlank()) {
            return surefireClasspath;
        }
        Set<String> entries = new LinkedHashSet<>();
        entries.add(testSupportJar.toString());
        entries.add(classpathEntry(com.fasterxml.jackson.core.type.TypeReference.class));
        entries.add(classpathEntry(com.fasterxml.jackson.databind.ObjectMapper.class));
        entries.add(classpathEntry(com.fasterxml.jackson.annotation.JsonProperty.class));
        entries.add(classpathEntry(org.slf4j.Logger.class));
        entries.add(classpathEntry(org.slf4j.LoggerFactory.class));
        return String.join(java.io.File.pathSeparator, entries);
    }

    private static String classpathEntry(Class<?> type) {
        try {
            var source = type.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                throw new IllegalStateException("No code source for " + type.getName());
            }
            return Path.of(source.getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Failed to resolve classpath for " + type.getName(), e);
        }
    }

    private static Path resolveJar(TestConfig config) {
        String m2Root = config.getString("sut.m2.repo", System.getProperty("user.home") + "/.m2/repository");
        Path jar = Path.of(m2Root, GROUP.replace('.', '/'), ARTIFACT, VERSION,
                ARTIFACT + "-" + VERSION + ".jar");
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException(
                    "Mock MCP jar not found at " + jar
                            + ". Build travel-openjiuwen test-support first, e.g.:"
                            + " mvn -pl :travel-openjiuwen-test-support -am package");
        }
        return jar;
    }

    private static Path resolveLogFile(TestConfig config) {
        String logDir = config.getString("sut.logging.dir", "target/sit-logs");
        return Path.of(logDir, "mock-mcp", "stdout.log");
    }

    private static int startupSeconds(TestConfig config) {
        return config.getInt("sut.timeout.startup-seconds", 60);
    }

    private static String javaBin() {
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            return Path.of(javaHome, "bin", "java").toString();
        }
        return "java";
    }
}

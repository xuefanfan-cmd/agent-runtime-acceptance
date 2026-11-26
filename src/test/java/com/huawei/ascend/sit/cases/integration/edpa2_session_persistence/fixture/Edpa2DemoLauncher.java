/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.edpa2_session_persistence.fixture;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Launches the moa-agent-demo fat jar as a subprocess for FEAT-089 SIT.
 *
 * <p>The demo jar is a single fat jar that runs three Spring profiles:
 * <ul>
 *   <li><b>mock</b> — port 3001, mock downstream A2A agent ({@code /a2a/mock})</li>
 *   <li><b>l2</b> — port 18229, L2 hotel EDPA2.0 agent</li>
 *   <li><b>l1</b> — port 18219, L1 travel EDPA2.0 agent (primary SUT)</li>
 * </ul>
 *
 * <p>Start order: mock → l2 → l1. Each process is a separate launcher instance
 * pointing at the same jar with a different profile.
 *
 * <p>The jar defaults to the dev-side build location:
 * <pre>
 * D:/openjiuwen/agent-solution/common/agents/moa-agent-demo/target/moa-agent-demo-0.1.3.jar
 * </pre>
 * Override via {@code -Dfeat089.demo-jar=...}.
 *
 * <p>The openGauss JDBC driver is externalised via LOADER_PATH:
 * <pre>
 * D:/openjiuwen/agent-solution/common/agents/moa-agent-demo/target/loader-path/opengauss-jdbc-7.0.0-og.jar
 * </pre>
 * Override via {@code -Dfeat089.opengauss-driver=...}.
 *
 * <p>stdout + stderr are redirected to a per-launch log file consumed by
 * {@link SutLogTailer}.
 *
 * <p>This launcher does NOT inherit the parent JVM env; callers pass the exact
 * env map they want (LLM_*, OPENGAUSS_*, etc.). Pattern mirrored from
 * {@code run_budget_react.fixture.DemoFatJarLauncher} (FEAT-057).
 */
public final class Edpa2DemoLauncher implements AutoCloseable {

    public static final String DEMO_JAR_PROP = "feat089.demo-jar";
    public static final String OG_DRIVER_PROP = "feat089.opengauss-driver";

    public static final Path DEFAULT_DEMO_JAR = Path.of(
            "D:/openjiuwen/agent-solution/common/agents/moa-agent-demo",
            "target", "moa-agent-demo-0.1.3.jar");

    public static final Path DEFAULT_OG_DRIVER = Path.of(
            "D:/openjiuwen/agent-solution/common/agents/moa-agent-demo",
            "target", "loader-path", "opengauss-jdbc-7.0.0-og.jar");

    public static final int MOCK_PORT = 3001;
    public static final int L2_PORT = 18229;
    public static final int L1_PORT = 18219;

    private final String name;
    private final Path jar;
    private final int port;
    private final String profile;
    private final Map<String, String> env;
    private final List<String> extraArgs;
    private final Duration startupTimeout;
    private final String readinessPath;

    private Process process;
    private Path logFile;
    private Instant startedAt;

    private Edpa2DemoLauncher(Builder b) {
        this.name = Objects.requireNonNull(b.name, "name");
        this.jar = Objects.requireNonNull(b.jar, "jar");
        this.port = b.port;
        this.profile = b.profile;
        this.env = Map.copyOf(b.env);
        this.extraArgs = List.copyOf(b.extraArgs);
        this.startupTimeout = b.startupTimeout;
        this.readinessPath = b.readinessPath;
    }

    /**
     * Builder for the mock downstream agent (port 3001, profile=mock).
     */
    public static Builder mockAgent() {
        return new Builder()
                .name("moa-mock")
                .jar(resolveJar())
                .port(MOCK_PORT)
                .profile("mock")
                .readinessPath("/a2a/mock/.well-known/agent-card.json")
                .startupTimeout(Duration.ofSeconds(60));
    }

    /**
     * Builder for the L2 hotel EDPA2.0 agent (port 18229, profile=l2).
     */
    public static Builder l2Agent() {
        return new Builder()
                .name("moa-l2")
                .jar(resolveJar())
                .port(L2_PORT)
                .profile("l2")
                .readinessPath("/.well-known/agent-card.json")
                .startupTimeout(Duration.ofSeconds(120));
    }

    /**
     * Builder for the L1 travel EDPA2.0 agent (port 18219, profile=l1).
     */
    public static Builder l1Agent() {
        return new Builder()
                .name("moa-l1")
                .jar(resolveJar())
                .port(L1_PORT)
                .profile("l1")
                .readinessPath("/.well-known/agent-card.json")
                .startupTimeout(Duration.ofSeconds(120));
    }

    private static Path resolveJar() {
        String override = System.getProperty(DEMO_JAR_PROP);
        return override != null && !override.isBlank() ? Path.of(override) : DEFAULT_DEMO_JAR;
    }

    private static Path resolveDriver() {
        String override = System.getProperty(OG_DRIVER_PROP);
        return override != null && !override.isBlank() ? Path.of(override) : DEFAULT_OG_DRIVER;
    }

    /**
     * Extracts org/opengauss/ class files from the openGauss JDBC driver jar
     * into a temporary directory. Only the driver classes are extracted (not
     * the full jar) because the driver jar contains SLF4J bindings that
     * conflict with Logback when loaded via PropertiesLauncher's loader.path.
     *
     * <p>The extracted classes directory is cached and only recreated if the
     * source driver jar is newer.
     */
    private static Path ensureDriverClasses() {
        try {
            Path driver = resolveDriver();
            if (!Files.isRegularFile(driver)) {
                throw new IllegalStateException("openGauss JDBC driver not found at " + driver);
            }
            Path tmpDir = Path.of(System.getProperty("java.io.tmpdir", "/tmp"));
            Path classesDir = tmpDir.resolve("opengauss-driver-classes");
            if (Files.isDirectory(classesDir)
                    && Files.getLastModifiedTime(classesDir).compareTo(
                            Files.getLastModifiedTime(driver)) > 0) {
                return classesDir;
            }
            if (Files.exists(classesDir)) {
                try (var stream = Files.walk(classesDir)) {
                    stream.sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> p.toFile().delete());
                }
            }
            Files.createDirectories(classesDir);
            runCommand(classesDir,
                    Path.of(System.getProperty("java.home"), "bin", "jar").toString(),
                    "xf", driver.toAbsolutePath().toString(),
                    "org/opengauss");
            classesDir.toFile().setLastModified(System.currentTimeMillis());
            return classesDir;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to extract openGauss driver classes", e);
        }
    }

    private static void runCommand(Path workDir, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(workDir.toFile())
                .redirectErrorStream(true);
        Process p = pb.start();
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("Command failed: " + String.join(" ", cmd)
                    + "\nOutput: " + new String(p.getInputStream().readAllBytes()));
        }
    }

    public Edpa2DemoLauncher start() throws IOException {
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException(name + " jar not found at " + jar
                    + " (override via -D" + DEMO_JAR_PROP + ")");
        }
        this.logFile = allocateLogFile();
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());

        // openGauss JDBC driver is loaded via PropertiesLauncher's loader.path.
        // We extract only org/opengauss/ classes (not the full driver jar which
        // contains SLF4J bindings that conflict with Logback).
        Path driverClassesDir = ensureDriverClasses();
        cmd.add("-Dloader.path=" + driverClassesDir.toAbsolutePath());
        cmd.add("-cp");
        cmd.add(jar.toAbsolutePath().toString());
        cmd.add("org.springframework.boot.loader.launch.PropertiesLauncher");
        if (profile != null && !profile.isBlank()) {
            cmd.add("--spring.profiles.active=" + profile);
        }
        cmd.add("--server.port=" + port);
        cmd.addAll(extraArgs);

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        pb.environment().clear();
        pb.environment().putAll(env);

        this.startedAt = Instant.now();
        this.process = pb.start();
        awaitReady();
        // Post-readiness stability check: some processes become ready (HTTP 200)
        // but then crash immediately due to late initialization failures (e.g.
        // openGauss driver load). Wait briefly and verify the process is still alive.
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!process.isAlive()) {
            throw new IllegalStateException(name + " became ready but exited shortly after (exit="
                    + process.exitValue() + ")\n" + tail(logFile, 80));
        }
        return this;
    }

    public Path logFile() {
        return logFile;
    }

    public int port() {
        return port;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    /** Returns the OS-level PID of the launched process, or -1 if not started. */
    public long pid() {
        return process != null ? process.pid() : -1L;
    }

    @Override
    public void close() {
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        // Give the OS a brief moment to release the socket
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Path allocateLogFile() throws IOException {
        String basedir = System.getProperty("basedir", System.getProperty("user.dir"));
        String stamp = Long.toString(System.currentTimeMillis(), 36);
        Path dir = Path.of(basedir, "target", "sit-logs", name + "-" + stamp);
        Files.createDirectories(dir);
        return dir.resolve("stdout.log");
    }

    private void awaitReady() {
        long deadline = System.nanoTime() + startupTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException(name + " exited before readiness (exit="
                        + process.exitValue() + ")\n" + tail(logFile, 60));
            }
            if (probeReady()) {
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IllegalStateException(name + " interrupted during readiness probe", e);
            }
        }
        process.destroyForcibly();
        throw new IllegalStateException(name + " did not become ready on port " + port
                + " within " + startupTimeout + "\n" + tail(logFile, 80));
    }

    private boolean probeReady() {
        if (readinessPath != null) {
            try {
                HttpURLConnection conn = (HttpURLConnection) URI.create(
                        "http://127.0.0.1:" + port + readinessPath).toURL().openConnection();
                conn.setConnectTimeout(500);
                conn.setReadTimeout(1000);
                int code = conn.getResponseCode();
                conn.disconnect();
                return code == 200;
            } catch (IOException notYet) {
                return false;
            }
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException notYet) {
            return false;
        }
    }

    public static String tail(Path log, int lines) {
        if (log == null || !Files.isRegularFile(log)) {
            return "(log unavailable: " + log + ")";
        }
        try {
            List<String> all = Files.readAllLines(log);
            int from = Math.max(0, all.size() - lines);
            return String.join("\n", all.subList(from, all.size()));
        } catch (IOException e) {
            return "(cannot read " + log + ": " + e.getMessage() + ")";
        }
    }

    public Instant startedAt() {
        return startedAt;
    }

    public static final class Builder {
        private String name;
        private Path jar;
        private int port;
        private String profile;
        private final Map<String, String> env = new LinkedHashMap<>();
        private final List<String> extraArgs = new ArrayList<>();
        private Duration startupTimeout = Duration.ofSeconds(120);
        private String readinessPath = "/.well-known/agent-card.json";

        public Builder name(String name) { this.name = name; return this; }
        public Builder jar(Path jar) { this.jar = jar; return this; }
        public Builder port(int port) { this.port = port; return this; }
        public Builder profile(String profile) { this.profile = profile; return this; }
        public Builder env(String key, String value) { this.env.put(key, value); return this; }
        public Builder envAll(Map<String, String> m) { this.env.putAll(m); return this; }
        public Builder arg(String arg) { this.extraArgs.add(arg); return this; }
        public Builder startupTimeout(Duration d) { this.startupTimeout = d; return this; }
        public Builder readinessPath(String p) { this.readinessPath = p; return this; }

        public Edpa2DemoLauncher build() { return new Edpa2DemoLauncher(this); }
    }
}

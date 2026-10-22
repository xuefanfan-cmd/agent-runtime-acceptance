/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.model_timeout_tiering.fixture;

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
 * Launches a Spring Boot fat jar as a subprocess for FEAT-058 SIT. Used for
 * both the timeout-probe-agent (port 18092) and llm-stub-server (port 18094).
 *
 * <p>Jar paths default to the dev-side build location
 * ({@code agent-solution/common/example/timeout-tiering-model-call-demo/...}),
 * overridable via {@code -Dfeat058.probe-jar=...} /
 * {@code -Dfeat058.stub-jar=...} or by passing explicit paths to the Builder.
 *
 * <p>stdout + stderr are tee'd into a per-launch log file at
 * {@code <basedir>/target/sit-logs/<name>-<timestamp>/stdout.log}; this is the
 * file that {@link SutLogTailer} consumes.
 *
 * <p>This launcher does NOT inherit the parent JVM env; callers pass the exact
 * env map they want (LLM_PROVIDER, LLM_API_BASE, LLM_STUB_DEFAULT_MODE,
 * TIMEOUT_PROBE_PORT, LLM_STUB_PORT, etc.). Corporate proxy must be unset by
 * the caller before Maven launches — see memory
 * [[local-dev-clear-corporate-proxy-before-sit]]; FEAT-058 stub lives on
 * 127.0.0.1 loopback so proxy env would break fixture entirely.
 *
 * <p>Pattern mirrored from {@code run_budget_react.fixture.DemoFatJarLauncher}
 * (FEAT-057) — same probe + readiness contract.
 */
public final class TimeoutDemoLauncher implements AutoCloseable {

    public static final String PROBE_JAR_PROP = "feat058.probe-jar";
    public static final String STUB_JAR_PROP = "feat058.stub-jar";

    public static final Path DEFAULT_PROBE_JAR = Path.of(
            System.getProperty("user.home").replace('\\', '/'),
            "openjiuwen-java", "2012", "agent-solution", "common", "example",
            "timeout-tiering-model-call-demo",
            "timeout-probe-agent", "target", "timeout-probe-agent-0.1.0.jar");

    public static final Path DEFAULT_STUB_JAR = Path.of(
            System.getProperty("user.home").replace('\\', '/'),
            "openjiuwen-java", "2012", "agent-solution", "common", "example",
            "timeout-tiering-model-call-demo",
            "llm-stub-server", "target", "llm-stub-server-0.1.0.jar");

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

    private TimeoutDemoLauncher(Builder b) {
        this.name = Objects.requireNonNull(b.name, "name");
        this.jar = Objects.requireNonNull(b.jar, "jar");
        this.port = b.port;
        this.profile = b.profile;
        this.env = Map.copyOf(b.env);
        this.extraArgs = List.copyOf(b.extraArgs);
        this.startupTimeout = b.startupTimeout;
        this.readinessPath = b.readinessPath;
    }

    public static Builder probeAgent() {
        Path jar = resolveJar(PROBE_JAR_PROP, DEFAULT_PROBE_JAR);
        return new Builder()
                .name("timeout-probe-agent")
                .jar(jar)
                .port(18092)
                .readinessPath("/.well-known/agent.json")
                .startupTimeout(Duration.ofSeconds(120));
    }

    public static Builder llmStubServer() {
        Path jar = resolveJar(STUB_JAR_PROP, DEFAULT_STUB_JAR);
        return new Builder()
                .name("llm-stub-server")
                .jar(jar)
                .port(18094)
                // stub exposes POST /v1/chat/completions; no .well-known. TCP probe only.
                .readinessPath(null)
                .startupTimeout(Duration.ofSeconds(60));
    }

    private static Path resolveJar(String sysProp, Path defaultPath) {
        String override = System.getProperty(sysProp);
        return override != null && !override.isBlank() ? Path.of(override) : defaultPath;
    }

    public TimeoutDemoLauncher start() throws IOException {
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException(name + " jar not found at " + jar
                    + " (override via -D"
                    + (name.equals("timeout-probe-agent") ? PROBE_JAR_PROP : STUB_JAR_PROP) + ")");
        }
        this.logFile = allocateLogFile();
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-jar");
        cmd.add(jar.toAbsolutePath().toString());
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

    /**
     * Non-throwing exit-code probe for the TC-RANGE-VALIDATION path, where the
     * launcher is expected to fail readiness; the caller inspects {@link #logFile}
     * for the expected {@code ModelClientConfig$Builder.build()} range exception
     * instead of swallowing {@link IllegalStateException} from {@link #start()}.
     */
    public TimeoutDemoLauncher startExpectingFailure() throws IOException {
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException(name + " jar not found at " + jar);
        }
        this.logFile = allocateLogFile();
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-jar");
        cmd.add(jar.toAbsolutePath().toString());
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
        try {
            process.waitFor(startupTimeout.toSeconds(), java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException(name + " interrupted waiting for expected failure exit", e);
        }
        return this;
    }

    /**
     * Returns the exit code of a terminated process (only meaningful after
     * {@link #startExpectingFailure()} completes or the process exits during
     * readiness). Throws if the process is still alive.
     */
    public int exitValue() {
        if (process == null) {
            throw new IllegalStateException("process not started: " + name);
        }
        if (process.isAlive()) {
            throw new IllegalStateException("process still alive: " + name);
        }
        return process.exitValue();
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
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
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
        private Map<String, String> env = new LinkedHashMap<>();
        private List<String> extraArgs = new ArrayList<>();
        private Duration startupTimeout = Duration.ofSeconds(120);
        private String readinessPath = "/.well-known/agent.json";

        public Builder name(String name) { this.name = name; return this; }
        public Builder jar(Path jar) { this.jar = jar; return this; }
        public Builder port(int port) { this.port = port; return this; }
        public Builder profile(String profile) { this.profile = profile; return this; }
        public Builder env(String key, String value) { this.env.put(key, value); return this; }
        public Builder envAll(Map<String, String> m) { this.env.putAll(m); return this; }
        public Builder arg(String arg) { this.extraArgs.add(arg); return this; }
        public Builder startupTimeout(Duration d) { this.startupTimeout = d; return this; }
        public Builder readinessPath(String p) { this.readinessPath = p; return this; }

        public TimeoutDemoLauncher build() { return new TimeoutDemoLauncher(this); }
    }
}

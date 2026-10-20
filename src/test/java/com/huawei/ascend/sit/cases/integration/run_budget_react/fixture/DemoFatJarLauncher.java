/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.run_budget_react.fixture;

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
 * Launches a Spring Boot fat jar as a subprocess for FEAT-057 SIT. Used for
 * both the researcher-agent (port 18092) and mock-knowledge-base (port 18093).
 *
 * <p>Jar paths default to the dev-side build location:
 * <pre>
 * D:/openjiuwen-java/2012/agent-solution/common/example/run-budget-elastic-research-demo/
 *   researcher-agent/target/researcher-agent-0.1.0.jar
 *   mock-knowledge-base/target/mock-knowledge-base-0.1.0.jar
 * </pre>
 * Override via {@code -Dfeat057.researcher-jar=...} / {@code -Dfeat057.mock-kb-jar=...}
 * or by passing explicit paths to the constructor.
 *
 * <p>stdout + stderr are tee'd into a per-launch log file at
 * {@code <basedir>/target/sit-logs/<name>-<timestamp>/stdout.log}; this is the
 * file that {@link SutLogTailer} consumes.
 *
 * <p>This launcher does NOT inherit the parent JVM env; callers pass the exact
 * env map they want (LLM_*, TAVILY_API_KEY, OPENJIUWEN_SERVICE_RUN_BUDGET_*,
 * MOCK_KB_* overrides). Corporate proxy must be unset by the caller before
 * JUnit launches the Maven process — see memory
 * [[local-dev-clear-corporate-proxy-before-sit]].
 */
public final class DemoFatJarLauncher implements AutoCloseable {

    public static final String RESEARCHER_JAR_PROP = "feat057.researcher-jar";
    public static final String MOCK_KB_JAR_PROP = "feat057.mock-kb-jar";

    public static final Path DEFAULT_RESEARCHER_JAR = Path.of(
            "D:/openjiuwen-java/2012/agent-solution/common/example/run-budget-elastic-research-demo",
            "researcher-agent", "target", "researcher-agent-0.1.0.jar");

    public static final Path DEFAULT_MOCK_KB_JAR = Path.of(
            "D:/openjiuwen-java/2012/agent-solution/common/example/run-budget-elastic-research-demo",
            "mock-knowledge-base", "target", "mock-knowledge-base-0.1.0.jar");

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

    private DemoFatJarLauncher(Builder b) {
        this.name = Objects.requireNonNull(b.name, "name");
        this.jar = Objects.requireNonNull(b.jar, "jar");
        this.port = b.port;
        this.profile = b.profile;
        this.env = Map.copyOf(b.env);
        this.extraArgs = List.copyOf(b.extraArgs);
        this.startupTimeout = b.startupTimeout;
        this.readinessPath = b.readinessPath;
    }

    public static Builder researcher() {
        Path jar = resolveJar(RESEARCHER_JAR_PROP, DEFAULT_RESEARCHER_JAR);
        return new Builder()
                .name("researcher-agent")
                .jar(jar)
                .port(18092)
                .readinessPath("/.well-known/agent.json")
                .startupTimeout(Duration.ofSeconds(120));
    }

    public static Builder mockKb() {
        Path jar = resolveJar(MOCK_KB_JAR_PROP, DEFAULT_MOCK_KB_JAR);
        return new Builder()
                .name("mock-knowledge-base")
                .jar(jar)
                .port(18093)
                // mock-kb is a plain MCP tool server, no agent.json; TCP probe only.
                .readinessPath(null)
                .startupTimeout(Duration.ofSeconds(60));
    }

    private static Path resolveJar(String sysProp, Path defaultPath) {
        String override = System.getProperty(sysProp);
        return override != null && !override.isBlank() ? Path.of(override) : defaultPath;
    }

    public DemoFatJarLauncher start() throws IOException {
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException(name + " jar not found at " + jar
                    + " (override via -D" + (name.equals("researcher-agent") ? RESEARCHER_JAR_PROP : MOCK_KB_JAR_PROP) + ")");
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
                        + process.exitValue() + ")\n" + tail(logFile, 50));
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

        public DemoFatJarLauncher build() { return new DemoFatJarLauncher(this); }
    }
}

package com.huawei.ascend.sit.lifecycle;

import com.huawei.ascend.sit.config.TestConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Default {@link SutLauncher}: runs an agent as a black-box {@code java -jar}
 * process resolved from the local Maven repository.
 *
 * <p>Launch sequence:
 * <ol>
 *   <li>resolve the fat jar by Maven coordinates from {@code ~/.m2/repository}
 *       (overridable via {@code sut.m2.repo});</li>
 *   <li>redirect stdout/stderr to a temp log file for diagnosability;</li>
 *   <li>launch with {@code --server.port} = {@link AgentConfig#port()} — {@code 0} lets the
 *       OS pick a random port atomically (no pick-then-bind race);</li>
 *   <li>capture the {@link Process#pid() PID};</li>
 *   <li>when the port is random, resolve the actual listening port from the PID via
 *       {@link ListeningPorts} and confirm readiness by probing {@code /.well-known/agent.json}
 *       (also disambiguates the server port if the process opens more than one listener);</li>
 *   <li>for a fixed port, probe {@code /.well-known/agent.json} directly.</li>
 * </ol>
 *
 * <p>No SUT classes are required on the test classpath — the agent is observed
 * solely through HTTP, matching the black-box testing philosophy.
 */
public final class ProcessLauncher implements SutLauncher {

    private static final String WELL_KNOWN = "/.well-known/agent.json";

    private final String m2RepoRoot;
    private final int startupTimeoutSeconds;
    private final String javaBin;
    private final Map<String, String> jvmSystemProperties;
    private final HttpClient http = HttpClient.newHttpClient();
    /** Per-agent log root, from {@code sut.logging.dir} (default {@code ${basedir}/target/sit-logs}). */
    private final Path logDir;

    /**
     * Merge global and per-agent JVM system properties: the global map provides defaults, the
     * per-agent map replaces same-named keys and appends its own. Iteration order is the global
     * order (replaced values keep their original position) followed by per-agent-only keys. The
     * result contains exactly one entry per key.
     *
     * <p>Package-private so {@link ProcessLauncher} can emit one {@code -D} per key, and directly
     * unit-testable without launching a process.
     */
    static Map<String, String> mergeJvmSystemProperties(Map<String, String> global,
                                                        Map<String, String> perAgent) {
        Map<String, String> merged = new LinkedHashMap<>(global);
        merged.putAll(perAgent);
        return merged;
    }

    public ProcessLauncher(TestConfig config) {
        this.m2RepoRoot = config.getString("sut.m2.repo",
                System.getProperty("user.home") + "/.m2/repository");
        this.startupTimeoutSeconds = config.getInt("sut.timeout.startup-seconds", 60);
        this.javaBin = javaBin();
        // JVM -D system properties applied to every launched agent (e.g. http.proxyHost,
        // https.nonProxyHosts; Spring also resolves the agents' ${LLM_*} from these). Set via
        // sut.java.system-properties in application-<env>.yml.
        this.jvmSystemProperties = config.getStringMap("sut.java.system-properties");
        this.logDir = resolveLogDir(config);
    }

    /** Resolved per-agent log root (package-private for unit testing the constructor wiring). */
    Path logDir() {
        return logDir;
    }

    @Override
    public ManagedSutInstance start(SutAgent agent, AgentConfig config) {
        Path jar = resolveJar(agent);
        Path logFile = logFile(logDir, agent.name());
        try {
            Files.createDirectories(logFile.getParent());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to create log directory " + logFile.getParent()
                            + " for agent '" + agent.name() + "'", e);
        }

        List<String> command = new ArrayList<>();
        command.add(javaBin);
        // JVM -D system properties MUST precede -jar. Global sut.java.system-properties provide
        // defaults; this agent's per-agent overrides (sut.agents.<name>.java.system-properties)
        // replace same-named keys and append their own — merged so each key emits exactly one -D.
        Map<String, String> mergedJvm = withLogHome(
                mergeJvmSystemProperties(jvmSystemProperties, config.jvmSystemProperties()),
                logFile.getParent());
        mergedJvm.forEach((key, value) -> command.add("-D" + key + "=" + value));
        command.add("-jar");
        command.add(jar.toString());
        // config.port() drives --server.port (0 = OS-assigned random port).
        command.addAll(config.toProgramArgs());

        ProcessBuilder pb = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        config.environment().forEach(pb.environment()::put);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to start " + agent.name() + " from " + jar, e);
        }
        long pid = process.pid();

        int port = config.port() > 0
                ? awaitFixedPort(agent, config.port(), process, logFile)
                : resolveRandomPort(agent, pid, process, logFile);

        String baseUrl = "http://localhost:" + port;
        return new ManagedSutInstance(agent.name(), pid, port, baseUrl, process, logFile);
    }

    private Path resolveJar(SutAgent agent) {
        Path jar = agent.artifact().jarPath(m2RepoRoot);
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException(
                    "SUT jar not found for agent '" + agent.name() + "' at " + jar
                            + ". Build it first, e.g.:"
                            + " mvn -pl :" + agent.artifact().artifactId() + " -am package");
        }
        return jar;
    }

    /**
     * Fixed-port path: the port is known a priori, so just wait until it serves the agent card.
     */
    private int awaitFixedPort(SutAgent agent, int port, Process process, Path logFile) {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(startupTimeoutSeconds);
        while (System.nanoTime() < deadlineNanos) {
            failFastIfExited(agent, process, logFile);
            if (probeReady(port)) {
                return port;
            }
            sleep500ms();
        }
        throw notReady(agent, "http://localhost:" + port, logFile);
    }

    /**
     * Random-port path: poll the PID's listening ports (via {@link ListeningPorts}) and return the
     * first that serves the agent card. This both recovers the OS-assigned port and confirms
     * readiness, disambiguating the server port if the process opens extra listeners.
     */
    private int resolveRandomPort(SutAgent agent, long pid, Process process, Path logFile) {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(startupTimeoutSeconds);
        while (System.nanoTime() < deadlineNanos) {
            failFastIfExited(agent, process, logFile);
            Set<Integer> candidates = ListeningPorts.of(pid);
            for (int port : candidates) {
                if (probeReady(port)) {
                    return port;
                }
            }
            sleep500ms();
        }
        throw notReady(agent, "PID " + pid + " (random port, not yet resolved)", logFile);
    }

    /** {@code true} iff the agent card endpoint responds 200 on {@code localhost:port}. */
    private boolean probeReady(int port) {
        try {
            HttpResponse<Void> response = http.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + WELL_KNOWN))
                            .timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (Exception notReadyYet) {
            return false; // connection refused / not bound yet / not ready
        }
    }

    private void failFastIfExited(SutAgent agent, Process process, Path logFile) {
        if (!process.isAlive()) {
            throw new IllegalStateException(agent.name()
                    + " process exited before becoming ready.\n--- log tail ---\n"
                    + tailLog(logFile));
        }
    }

    private IllegalStateException notReady(SutAgent agent, String where, Path logFile) {
        return new IllegalStateException(agent.name() + " did not become ready within "
                + startupTimeoutSeconds + "s (probed " + where + ").\n--- log tail ---\n"
                + tailLog(logFile));
    }

    /**
     * Per-agent stdout redirect target: {@code <logDir>/<agentName>/stdout.log}.
     * Package-private static so the path expression is unit-testable without launching a process.
     */
    static Path logFile(Path logDir, String agentName) {
        return logDir.resolve(agentName).resolve("stdout.log");
    }

    /**
     * Inject {@code -DLOG_HOME=<agentDir>} into the already-merged JVM system properties, unless the
     * user explicitly set {@code LOG_HOME} (global {@code sut.java.system-properties} or per-agent
     * {@code sut.agents.<name>.java.system-properties}). if-absent: the framework never overrides an
     * explicit value. Package-private static for unit testing.
     */
    static Map<String, String> withLogHome(Map<String, String> mergedJvm, Path agentDir) {
        if (mergedJvm.containsKey("LOG_HOME")) {
            return mergedJvm;
        }
        Map<String, String> copy = new LinkedHashMap<>(mergedJvm);
        copy.put("LOG_HOME", agentDir.toString());
        return copy;
    }

    /**
     * Resolve the per-agent log root from {@code sut.logging.dir}, defaulting to
     * {@code ${basedir}/target/sit-logs} ({@code basedir} = the surefire system property, fallback
     * {@code user.dir}). Package-private static for unit testing.
     */
    static Path resolveLogDir(TestConfig config) {
        String configured = config.getString("sut.logging.dir");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        String basedir = System.getProperty("basedir", System.getProperty("user.dir"));
        return Path.of(basedir, "target", "sit-logs");
    }

    private static String tailLog(Path logFile) {
        try {
            List<String> lines = Files.readAllLines(logFile);
            int from = Math.max(0, lines.size() - 40);
            return String.join("\n", lines.subList(from, lines.size()));
        } catch (Exception e) {
            return "(could not read log " + logFile + ": " + e.getMessage() + ")";
        }
    }

    private static void sleep500ms() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for SUT readiness", e);
        }
    }

    private static String javaBin() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}

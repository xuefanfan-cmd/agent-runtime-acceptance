package com.huawei.ascend.sit.lifecycle;

import com.huawei.ascend.sit.config.TestConfig;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Default {@link SutLauncher}: runs an agent as a black-box {@code java -jar}
 * process resolved from the local Maven repository.
 *
 * <p>Launch sequence:
 * <ol>
 *   <li>resolve the fat jar by Maven coordinates from {@code ~/.m2/repository}
 *       (overridable via {@code sut.m2.repo});</li>
 *   <li>select a free port if {@link AgentConfig#port()} is 0 and record it on
 *       the config so callers (chain wiring) can read it back;</li>
 *   <li>redirect stdout/stderr to a temp log file for diagnosability;</li>
 *   <li>poll {@code GET /.well-known/agent.json} until 200, failing fast if the
 *       process exits early.</li>
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

    public ProcessLauncher(TestConfig config) {
        this.m2RepoRoot = config.getString("sut.m2.repo",
                System.getProperty("user.home") + "/.m2/repository");
        this.startupTimeoutSeconds = config.getInt("sut.timeout.startup-seconds", 60);
        this.javaBin = javaBin();
        // JVM -D system properties applied to every launched agent (e.g. http.proxyHost,
        // https.nonProxyHosts; Spring also resolves the agents' ${LLM_*} from these). Set via
        // sut.java.system-properties in application-<env>.yml.
        this.jvmSystemProperties = config.getStringMap("sut.java.system-properties");
    }

    @Override
    public SutInstance start(SutAgent agent, AgentConfig config) {
        Path jar = resolveJar(agent);
        int port = config.port() > 0 ? config.port() : pickFreePort();
        config.port(port); // record the resolved port for chain wiring

        Path logFile = logFile(agent.name(), port);
        List<String> command = new ArrayList<>();
        command.add(javaBin);
        // JVM -D system properties MUST precede -jar. Rendered from sut.java.system-properties
        // (e.g. -Dhttp.proxyHost=... -Dhttp.nonProxyHosts=... ; also -DLLM_API_KEY=... works,
        // since Spring resolves the agents' ${LLM_*} from system properties).
        jvmSystemProperties.forEach((key, value) -> command.add("-D" + key + "=" + value));
        command.add("-jar");
        command.add(jar.toString());
        command.addAll(config.toProgramArgs());

        ProcessBuilder pb = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile());
        config.environment().forEach(pb.environment()::put);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to start " + agent.name() + " from " + jar, e);
        }

        String baseUrl = "http://localhost:" + port;
        SutInstance instance = new SutInstance(agent.name(), port, baseUrl, process, logFile);
        awaitReady(agent, baseUrl, instance);
        return instance;
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

    private void awaitReady(SutAgent agent, String baseUrl, SutInstance instance) {
        URI uri = URI.create(baseUrl + WELL_KNOWN);
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(startupTimeoutSeconds);
        while (System.nanoTime() < deadlineNanos) {
            if (!instance.isAlive()) {
                throw new IllegalStateException(agent.name()
                        + " process exited before becoming ready.\n--- log tail ---\n"
                        + tailLog(instance));
            }
            try {
                HttpResponse<Void> response = http.send(
                        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (Exception notReadyYet) {
                // connection refused / not bound yet — keep polling
            }
            sleep500ms();
        }
        throw new IllegalStateException(agent.name() + " did not become ready within "
                + startupTimeoutSeconds + "s (probed " + uri + ").\n--- log tail ---\n"
                + tailLog(instance));
    }

    private static int pickFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Could not allocate a free port", e);
        }
    }

    private static Path logFile(String agentName, int port) {
        String tmp = System.getProperty("java.io.tmpdir");
        return Path.of(tmp, "sit-sut-" + agentName + "-" + port + ".log");
    }

    private static String tailLog(SutInstance instance) {
        try {
            List<String> lines = Files.readAllLines(instance.logFile());
            int from = Math.max(0, lines.size() - 40);
            return String.join("\n", lines.subList(from, lines.size()));
        } catch (Exception e) {
            return "(could not read log " + instance.logFile() + ": " + e.getMessage() + ")";
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

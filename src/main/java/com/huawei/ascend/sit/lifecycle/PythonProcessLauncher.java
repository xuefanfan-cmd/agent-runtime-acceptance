package com.huawei.ascend.sit.lifecycle;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.transport.HttpClients;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@link SutLauncher} for a managed Python-hosted SUT agent — the counterpart of
 * {@link ProcessLauncher} for a {@code java -jar} agent.
 *
 * <p>Launch sequence:
 * <ol>
 *   <li>pick a free TCP port ourselves ({@code ServerSocket(0)}) — the Python demo modules
 *       read their bind port from an env var, not a program arg, so we cannot use the
 *       "OS-assigns-atomically at bind time" random-port path;</li>
 *   <li>build the child env: parent env + {@code PYTHONPATH} prepended with the runtime's
 *       {@code pythonpath} entries + {@code <portEnvKey>=<port>} + LLM_* keys inherited from
 *       {@code sut.java.system-properties} (so the same YAML config drives both Java and
 *       Python agents) + {@code NO_PROXY=127.0.0.1,localhost} (so loopback A2A calls between
 *       demo agents bypass any corporate proxy — {@code HTTP(S)_PROXY} is left intact so
 *       outbound LLM traffic can still route through it) + any per-agent {@code env(...)};</li>
 *   <li>redirect stdout/stderr to {@code <logDir>/<name>/stdout.log};</li>
 *   <li>spawn {@code python -u -m <module>} with cwd = the runtime's {@code workingDirectory};</li>
 *   <li>poll {@code http://127.0.0.1:<port>/.well-known/agent-card.json} until 200 (Python A2A
 *       server convention — not the Java runtime's {@code /agent.json}).</li>
 * </ol>
 *
 * <p>Random port picking has a small pick-then-bind race window (the OS may reassign that port
 * between {@link ServerSocket#close()} and the Python child binding). In practice this is not
 * an issue for SIT dev/CI use — the ephemeral port range is large enough that reuse is rare
 * within the same second — and there is no alternative: the demo modules don't expose a random
 * "server.port=0" style option to bind atomically.
 */
public final class PythonProcessLauncher implements SutLauncher {

    /** Python A2A server publishes its card under this well-known path (not the Java {@code /agent.json}). */
    private static final String WELL_KNOWN = "/.well-known/agent-card.json";

    private static final String[] LLM_ENV_KEYS = {
            "LLM_API_BASE", "LLM_API_KEY", "LLM_MODEL", "LLM_PROVIDER", "LLM_VERIFY_SSL", "LLM_SSL_VERIFY"
    };

    private final int startupTimeoutSeconds;
    private final Map<String, String> globalJvmSystemProperties;
    private final HttpClient http = HttpClients.newHttp1Client();
    private final Path logDir;

    public PythonProcessLauncher(TestConfig config) {
        this.startupTimeoutSeconds = config.getInt("sut.timeout.startup-seconds", 60);
        // The global sut.java.system-properties block ships LLM_* defaults so the same YAML
        // config drives Java and Python agents. Shell env still wins per key when set.
        this.globalJvmSystemProperties = config.getStringMap("sut.java.system-properties");
        this.logDir = ProcessLauncher.resolveLogDir(config);
    }

    /** Resolved log root — package-private for unit testing. */
    Path logDir() {
        return logDir;
    }

    @Override
    public ManagedSutInstance start(SutAgent agent, AgentConfig config) {
        PythonRuntime runtime = agent.pythonRuntime();
        if (runtime == null) {
            throw new IllegalStateException(
                    "PythonProcessLauncher invoked for '" + agent.name() + "' but the agent has no "
                            + "PythonRuntime — check the SutStack dispatch wiring.");
        }
        Path workingDir = runtime.workingDirectory();
        if (!Files.isDirectory(workingDir)) {
            throw new IllegalStateException("Python agent '" + agent.name()
                    + "' workingDirectory does not exist or is not a directory: " + workingDir
                    + ". Check sut.agents." + agent.name() + ".python.cwd in application-*.yml.");
        }
        Path logFile = ProcessLauncher.logFile(logDir, agent.name());
        try {
            Files.createDirectories(logFile.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create log directory " + logFile.getParent()
                    + " for Python agent '" + agent.name() + "'", e);
        }

        int port = config.port() > 0 ? config.port() : allocateFreePort(agent);

        List<String> command = new ArrayList<>();
        command.add(runtime.pythonBin());
        command.add("-u");
        command.add("-m");
        command.add(runtime.module());
        // Python modules read the port from env, not argv. Any --key=value the agent's YAML
        // wants to influence has to come as env too — we surface config.properties() as
        // SPRING-style args ONLY if a user explicitly opts in; the demo modules don't parse them.

        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

        Map<String, String> env = pb.environment();
        // PYTHONPATH: prepend runtime's entries (typically <demo>/<agent>/src + shared runtime root)
        // so `python -m` resolves the demo module without a global pip install.
        if (!runtime.pythonpath().isEmpty()) {
            String existing = env.getOrDefault("PYTHONPATH", "");
            String prepended = String.join(java.io.File.pathSeparator, runtime.pythonpath());
            env.put("PYTHONPATH", existing.isEmpty() ? prepended : prepended + java.io.File.pathSeparator + existing);
        }
        // LLM_* from sut.java.system-properties (YAML defaults) — shell env overrides per key.
        Map<String, String> llmProps = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : globalJvmSystemProperties.entrySet()) {
            for (String k : LLM_ENV_KEYS) {
                if (e.getKey().equalsIgnoreCase(k)) {
                    llmProps.put(k, e.getValue());
                    break;
                }
            }
        }
        for (String key : LLM_ENV_KEYS) {
            String shellValue = System.getenv(key);
            if (shellValue != null && !shellValue.isBlank()) {
                llmProps.put(key, shellValue);
            }
        }
        env.putAll(llmProps);
        // Framework-picked port → the env var the demo module reads.
        env.put(runtime.portEnvKey(), Integer.toString(port));
        // Loopback A2A between the demo processes must bypass any corporate proxy — matches
        // smoke_real_llm.py:_NO_PROXY_HOSTS. HTTP(S)_PROXY is inherited unchanged, so outbound
        // LLM calls still route through the proxy when the shell had one exported.
        env.putIfAbsent("NO_PROXY", "127.0.0.1,localhost,0.0.0.0");
        env.putIfAbsent("no_proxy", "127.0.0.1,localhost,0.0.0.0");
        // Per-agent env overrides (highest precedence in this launcher — mirrors ProcessLauncher).
        config.environment().forEach(env::put);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to start Python agent '" + agent.name() + "' (module=" + runtime.module()
                            + ", cwd=" + workingDir + ")", e);
        }
        long pid = process.pid();

        awaitReady(agent, port, process, logFile);

        String baseUrl = "http://127.0.0.1:" + port;
        return new ManagedSutInstance(agent.name(), pid, port, baseUrl, process, logFile);
    }

    /**
     * Pick a free ephemeral TCP port via {@code ServerSocket(0)}. Small pick-then-bind race: the
     * OS may reassign that port between our close and the child's bind. Acceptable for SIT.
     */
    private int allocateFreePort(SutAgent agent) {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to allocate a free port for Python agent '" + agent.name() + "'", e);
        }
    }

    /** Poll the agent-card endpoint until 200, honoring the startup timeout. */
    private void awaitReady(SutAgent agent, int port, Process process, Path logFile) {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(startupTimeoutSeconds);
        while (System.nanoTime() < deadlineNanos) {
            if (!process.isAlive()) {
                throw new IllegalStateException("Python agent '" + agent.name()
                        + "' process exited before becoming ready.\n--- log tail ---\n"
                        + tailLog(logFile));
            }
            if (probeReady(port)) {
                return;
            }
            sleep500ms();
        }
        throw new IllegalStateException("Python agent '" + agent.name()
                + "' did not become ready within " + startupTimeoutSeconds + "s (probed "
                + "http://127.0.0.1:" + port + WELL_KNOWN + ").\n--- log tail ---\n"
                + tailLog(logFile));
    }

    private boolean probeReady(int port) {
        try {
            HttpResponse<Void> response = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + WELL_KNOWN))
                            .timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (Exception notReadyYet) {
            return false;
        }
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
            throw new RuntimeException("Interrupted while waiting for Python SUT readiness", e);
        }
    }
}

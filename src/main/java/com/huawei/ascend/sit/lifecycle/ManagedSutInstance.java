package com.huawei.ascend.sit.lifecycle;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * A SUT agent the framework launched as a {@code java -jar} process.
 *
 * <p>Created once the agent is ready (its {@code /.well-known/agent.json} responds 200). The
 * process was started with {@code --server.port=0} (random port); {@link #pid()} is the OS
 * process id, and {@link #port()} is the actual listening port resolved from that PID. Holds
 * everything needed to talk to the agent and to tear it down.
 */
public final class ManagedSutInstance implements SutInstance {

    private final String name;
    private final long pid;
    private final int port;
    private final String baseUrl;
    private final Process process;
    private final Path logFile;

    public ManagedSutInstance(String name, long pid, int port, String baseUrl,
                              Process process, Path logFile) {
        this.name = name;
        this.pid = pid;
        this.port = port;
        this.baseUrl = baseUrl;
        this.process = process;
        this.logFile = logFile;
    }

    @Override
    public String name() {
        return name;
    }

    /** OS process id of the launched JVM (used to resolve the random listening port). */
    public long pid() {
        return pid;
    }

    /** Actual listening port (resolved from the PID, since {@code server.port=0} was used). */
    public int port() {
        return port;
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    public Path logFile() {
        return logFile;
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /**
     * Stop the agent: SIGTERM and wait up to 10s, then SIGKILL if still alive.
     * Idempotent — safe to call on an already-terminated instance.
     */
    @Override
    public void close() {
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}

package com.huawei.ascend.sit.lifecycle;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * A running SUT agent process.
 *
 * <p>Created by a {@link SutLauncher} once the agent is ready (its
 * {@code /.well-known/agent.json} endpoint responds 200). Holds everything
 * needed to talk to it and to tear it down: resolved port, base URL, the live
 * {@link Process}, and the log file the launcher redirected output to.
 */
public final class SutInstance implements AutoCloseable {

    private final String name;
    private final int port;
    private final String baseUrl;
    private final Process process;
    private final Path logFile;

    public SutInstance(String name, int port, String baseUrl, Process process, Path logFile) {
        this.name = name;
        this.port = port;
        this.baseUrl = baseUrl;
        this.process = process;
        this.logFile = logFile;
    }

    public String name() {
        return name;
    }

    public int port() {
        return port;
    }

    /** Base URL without trailing slash, e.g. {@code http://localhost:38211}. */
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
    public void stop() {
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

    @Override
    public void close() {
        stop();
    }
}

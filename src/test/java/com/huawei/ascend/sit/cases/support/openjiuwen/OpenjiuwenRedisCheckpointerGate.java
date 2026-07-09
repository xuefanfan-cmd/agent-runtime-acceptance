package com.huawei.ascend.sit.cases.support.openjiuwen;

import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import com.huawei.ascend.sit.lifecycle.SutStack;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hard gates for openjiuwen Redis checkpointer tests (OJ-06+).
 *
 * <p>Verifies SUT startup logs and optional Redis key presence — not just profile/env meta.</p>
 */
public final class OpenjiuwenRedisCheckpointerGate {

    private static final String CHECKPOINTER_BEGIN_MARKER =
            "Begin to initializing checkpointer with type: ";

    private OpenjiuwenRedisCheckpointerGate() {
    }

    /**
     * OJ-07.P1 — managed agent must initialize checkpointer as {@code in_memory} (no redis profile).
     */
    public static void assertAgentsUseInMemoryCheckpointer(SutStack stack, String... agentNames) throws IOException {
        List<String> failures = new ArrayList<>();
        for (String agent : agentNames) {
            Path log = agentStdoutLog(stack, agent);
            String lastType = lastCheckpointerType(Files.readString(log, StandardCharsets.UTF_8));
            if (lastType == null) {
                failures.add(agent + ": no '" + CHECKPOINTER_BEGIN_MARKER + "' line in " + log);
            } else if (!"in_memory".equals(lastType)) {
                failures.add(agent + ": latest checkpointer type is '" + lastType + "', expected in_memory (see " + log + ")");
            }
        }
        assertThat(failures)
                .as("In-memory checkpointer startup gate (OJ-07.P1)")
                .isEmpty();
    }

    /**
     * OJ-06.0 — every managed agent must initialize AgentCore checkpointer as {@code redis}.
     *
     * <p>{@code stdout.log} is append-only across runs; only the <em>latest</em> startup line is checked.</p>
     */
    public static void assertAgentsUseRedisCheckpointer(SutStack stack, String... agentNames) throws IOException {
        List<String> failures = new ArrayList<>();
        for (String agent : agentNames) {
            Path log = agentStdoutLog(stack, agent);
            String content = Files.readString(log, StandardCharsets.UTF_8);
            String lastType = lastCheckpointerType(content);
            if (lastType == null) {
                failures.add(agent + ": no '" + CHECKPOINTER_BEGIN_MARKER + "' line in " + log);
            } else if (!"redis".equals(lastType)) {
                failures.add(agent + ": latest checkpointer type is '" + lastType + "', expected redis (see " + log + ")");
            }
        }
        assertThat(failures)
                .as("Redis checkpointer startup gate (OJ-06.0)")
                .isEmpty();
    }

    /**
     * Returns the checkpointer type from the last {@code Begin to initializing checkpointer...} line,
     * or {@code null} if the log has no such line.
     */
    static String lastCheckpointerType(String logContent) {
        String lastType = null;
        for (String line : logContent.split("\n")) {
            int idx = line.indexOf(CHECKPOINTER_BEGIN_MARKER);
            if (idx >= 0) {
                String tail = line.substring(idx + CHECKPOINTER_BEGIN_MARKER.length());
                lastType = tail.split(",", 2)[0].trim();
            }
        }
        return lastType;
    }

    /**
     * After Turn1, Redis must contain session/checkpoint keys when checkpointer backend is Redis.
     * Uses RESP {@code DBSIZE} over a plain TCP socket (no extra client dependency).
     */
    public static void assertRedisHasCheckpointData(String redisHost, int redisPort, String label) throws IOException {
        assertThat(isRedisReachable(redisHost, redisPort))
                .as(label + " Redis reachable at " + redisHost + ":" + redisPort)
                .isTrue();
        long keyCount = redisDbSize(redisHost, redisPort);
        assertThat(keyCount)
                .as(label + " Redis DBSIZE after Turn1 (expect checkpoint keys when checkpointer=redis)")
                .isGreaterThan(0);
    }

    private static Path agentStdoutLog(SutStack stack, String agentName) {
        var instance = stack.managedInstance(agentName);
        assertThat(instance)
                .as("managed agent '" + agentName + "' for log gate")
                .isNotNull()
                .isInstanceOf(ManagedSutInstance.class);
        return ((ManagedSutInstance) instance).logFile();
    }

    private static boolean isRedisReachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2_000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Minimal RESP client: {@code DBSIZE} command, parses integer reply. */
    private static long redisDbSize(String host, int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5_000);
            socket.getOutputStream().write("*1\r\n$6\r\nDBSIZE\r\n".getBytes(StandardCharsets.UTF_8));
            byte[] buf = new byte[256];
            int read = socket.getInputStream().read(buf);
            if (read <= 0) {
                throw new IOException("Empty DBSIZE response from Redis");
            }
            String reply = new String(buf, 0, read, StandardCharsets.UTF_8).trim();
            if (reply.startsWith("-")) {
                throw new IOException("Redis DBSIZE error: " + reply);
            }
            if (!reply.startsWith(":")) {
                throw new IOException("Unexpected DBSIZE reply: " + reply);
            }
            return Long.parseLong(reply.substring(1).split("\r\n")[0].trim());
        }
    }

    public static int parsePort(String port) {
        return Integer.parseInt(port.trim());
    }
}

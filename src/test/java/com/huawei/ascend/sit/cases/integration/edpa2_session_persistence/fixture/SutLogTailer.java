/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.edpa2_session_persistence.fixture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reads FEAT-089 persistence/recovery log lines from the moa-agent-demo
 * stdout log file.
 *
 * <p>The moa-agent and agent-core emit log lines with identifiable markers
 * when persistence events occur. This tailer scans for:
 * <ul>
 *   <li><b>Save events</b> — checkpoint save to openGauss (e.g. "saveCheckpoint",
 *       "persistContext", "KVStore put", "OpenGaussKVStore")</li>
 *   <li><b>Recovery events</b> — context restore from openGauss (e.g.
 *       "restoreCheckpoint", "loadContext", "KVStore get", "recovery")</li>
 *   <li><b>Startup events</b> — "Started" banner for each profile, openGauss
 *       validation/init messages</li>
 *   <li><b>Error events</b> — persistence failures, recovery errors</li>
 * </ul>
 *
 * <p>This is a one-shot scan of the entire log file (no streaming tail).
 * The test calls {@code collectAll()} after the SUT interaction completes.
 */
public final class SutLogTailer {

    // Save-related markers
    private static final String SAVE_MARKER = "saveCheckpoint";
    private static final String PERSIST_MARKER = "persistContext";
    private static final String KVSTORE_PUT_MARKER = "OpenGaussKVStore";
    private static final String CHECKPOINT_SAVE_MARKER = "checkpoint.*save";

    // Recovery-related markers
    private static final String RESTORE_MARKER = "restoreCheckpoint";
    private static final String LOAD_CONTEXT_MARKER = "loadContext";
    private static final String RECOVERY_MARKER = "recovery";
    private static final String KVSTORE_GET_MARKER = "OpenGaussKVStore.*get";

    // Startup markers
    private static final String STARTED_MARKER = "Started ";
    private static final String OG_INIT_MARKER = "moa-opengauss";
    private static final String OG_VALIDATE_MARKER = "validate";

    // Error markers
    private static final String PERSIST_ERROR_MARKER = "persistence.*error|save.*failed|persist.*fail";
    private static final String RECOVERY_ERROR_MARKER = "recovery.*error|restore.*fail";

    private final Path logFile;

    public SutLogTailer(Path logFile) {
        this.logFile = Objects.requireNonNull(logFile, "logFile");
    }

    public Path logFile() {
        return logFile;
    }

    /**
     * One-shot scan of the entire log file, returning all persistence-related
     * log lines in order.
     */
    public List<LogEntry> collectAll() throws IOException {
        List<LogEntry> out = new ArrayList<>();
        if (!Files.isRegularFile(logFile)) {
            return out;
        }
        List<String> lines = Files.readAllLines(logFile);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            LogEventType type = classify(line);
            if (type != null) {
                out.add(new LogEntry(type, line, i + 1));
            }
        }
        return out;
    }

    /**
     * Returns all lines containing save/checkpoint persistence events.
     */
    public List<LogEntry> collectSaveEvents() throws IOException {
        return collectAll().stream()
                .filter(e -> e.type() == LogEventType.SAVE || e.type() == LogEventType.PERSIST)
                .toList();
    }

    /**
     * Returns all lines containing recovery/restore events.
     */
    public List<LogEntry> collectRecoveryEvents() throws IOException {
        return collectAll().stream()
                .filter(e -> e.type() == LogEventType.RESTORE
                        || e.type() == LogEventType.RECOVERY)
                .toList();
    }

    /**
     * Returns all lines containing persistence or recovery errors.
     */
    public List<LogEntry> collectErrorEvents() throws IOException {
        return collectAll().stream()
                .filter(e -> e.type() == LogEventType.PERSIST_ERROR
                        || e.type() == LogEventType.RECOVERY_ERROR)
                .toList();
    }

    /**
     * Convenience: count of save events.
     */
    public int saveEventCount() throws IOException {
        return collectSaveEvents().size();
    }

    /**
     * Convenience: count of recovery events.
     */
    public int recoveryEventCount() throws IOException {
        return collectRecoveryEvents().size();
    }

    /**
     * Convenience: count of error events.
     */
    public int errorEventCount() throws IOException {
        return collectErrorEvents().size();
    }

    /**
     * Convenience: whether the agent started successfully.
     */
    public boolean agentStarted() throws IOException {
        if (!Files.isRegularFile(logFile)) {
            return false;
        }
        for (String line : Files.readAllLines(logFile)) {
            if (line.contains(STARTED_MARKER) && line.contains("Application")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Convenience: whether openGauss initialization appeared in the log.
     */
    public boolean hasOpenGaussInit() throws IOException {
        if (!Files.isRegularFile(logFile)) {
            return false;
        }
        for (String line : Files.readAllLines(logFile)) {
            if (line.contains(OG_INIT_MARKER) || line.contains(OG_VALIDATE_MARKER)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether a specific keyword appears anywhere in the log.
     */
    public boolean containsKeyword(String keyword) throws IOException {
        Objects.requireNonNull(keyword, "keyword");
        if (!Files.isRegularFile(logFile)) {
            return false;
        }
        for (String line : Files.readAllLines(logFile)) {
            if (line.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the raw log tail (last N lines) for diagnostics.
     */
    public String tail(int lines) throws IOException {
        if (!Files.isRegularFile(logFile)) {
            return "(log unavailable: " + logFile + ")";
        }
        List<String> all = Files.readAllLines(logFile);
        int from = Math.max(0, all.size() - lines);
        return String.join("\n", all.subList(from, all.size()));
    }

    private LogEventType classify(String line) {
        String lower = line.toLowerCase();
        // Errors first (they may contain save/recovery keywords)
        if (lower.matches(".*(" + PERSIST_ERROR_MARKER + ").*")) {
            return LogEventType.PERSIST_ERROR;
        }
        if (lower.matches(".*(" + RECOVERY_ERROR_MARKER + ").*")) {
            return LogEventType.RECOVERY_ERROR;
        }
        // Save events
        if (lower.contains(SAVE_MARKER.toLowerCase()) || lower.contains(PERSIST_MARKER.toLowerCase())) {
            return LogEventType.SAVE;
        }
        if (lower.contains(KVSTORE_PUT_MARKER.toLowerCase()) && lower.contains("put")) {
            return LogEventType.PERSIST;
        }
        // Recovery events
        if (lower.contains(RESTORE_MARKER.toLowerCase()) || lower.contains(LOAD_CONTEXT_MARKER.toLowerCase())) {
            return LogEventType.RESTORE;
        }
        if (lower.contains(RECOVERY_MARKER.toLowerCase())) {
            return LogEventType.RECOVERY;
        }
        if (lower.contains(KVSTORE_GET_MARKER.toLowerCase()) && lower.contains("get")) {
            return LogEventType.RECOVERY;
        }
        // Startup
        if (lower.contains(OG_INIT_MARKER.toLowerCase()) || lower.contains(OG_VALIDATE_MARKER.toLowerCase())) {
            return LogEventType.OG_INIT;
        }
        return null;
    }

    public enum LogEventType {
        SAVE,
        PERSIST,
        RESTORE,
        RECOVERY,
        OG_INIT,
        PERSIST_ERROR,
        RECOVERY_ERROR
    }

    public record LogEntry(LogEventType type, String line, int lineNumber) {
    }
}

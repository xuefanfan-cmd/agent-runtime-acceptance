/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.model_timeout_tiering.fixture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads FEAT-058 timeout error log lines from the probe-agent stdout log file.
 *
 * <p>The agent-core-java {@code StatusCode.MODEL_CALL_TIMEOUT(181008)} template
 * is: {@code "model call timeout at {phase}, effective timeout {timeout}s, reason: {error_msg}"}.
 * Spring Boot logback wraps every logger line with a timestamp + level +
 * logger prefix. So a complete log line looks like:
 *
 * <pre>
 * 2026-10-22T15:39:21.456+08:00 ERROR 123 --- [thread] c.o.c.c.m.OpenAIModelClient : [181008] model call timeout at BEFORE_CONNECT, effective timeout 5s, reason: Connection timed out
 * </pre>
 *
 * <p>This tailer regex-matches each line against the 181008 template and
 * yields {@link TimeoutErrorLogRecord} entries with parsed phase + effective
 * timeout seconds + raw reason text.
 *
 * <p>Note: the FEAT-058 four-field error contract ({@code phase},
 * {@code timeout_seconds}, {@code layer}, {@code stream}) is only fully
 * visible on the HTTP response body error payload — the structured
 * {@code layer} + {@code stream} fields are NOT embedded in the log template.
 * SIT uses this tailer for the "was 181008 emitted at all / how many times /
 * which phase" presence+count assertions, and uses
 * {@link TimeoutProbeHttpClient.QueryResponse#errorDetails} for the layer/stream
 * assertions. (See cases §2.2 fixture responsibility table.)
 */
public final class SutLogTailer {

    private static final Pattern TIMEOUT_LINE = Pattern.compile(
            "\\[181008\\]\\s*model call timeout at\\s+(?<phase>BEFORE_CONNECT|BEFORE_FIRST_SEGMENT|AFTER_FIRST_SEGMENT)"
                    + ",\\s*effective timeout\\s+(?<seconds>[0-9]+(?:\\.[0-9]+)?)s"
                    + ",\\s*reason:\\s*(?<reason>.*)$");

    /** FEAT-064: retry decision log emitted by {@code ModelCallRetrier} once per retry attempt. */
    private static final Pattern RETRY_LINE = Pattern.compile("Model call will retry\\.");

    /** FEAT-064: {@code category=<CAT>} field embedded in classified error log lines. */
    private static final Pattern CATEGORY_FIELD = Pattern.compile(
            "category=(?<category>RATE_LIMITED|QUOTA_EXCEEDED|AUTH_FAILED|TEMPORARY|USER_CANCELLED|CONTEXT_LENGTH_EXCEEDED)");

    private final Path logFile;

    public SutLogTailer(Path logFile) {
        this.logFile = Objects.requireNonNull(logFile, "logFile");
    }

    public Path logFile() {
        return logFile;
    }

    /**
     * One-shot scan of the entire log file. Returns the timeout error records
     * in the order they appear.
     */
    public List<TimeoutErrorLogRecord> collectAllTimeouts() throws IOException {
        List<TimeoutErrorLogRecord> out = new ArrayList<>();
        if (!Files.isRegularFile(logFile)) {
            return out;
        }
        List<String> lines = Files.readAllLines(logFile);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.contains("[181008]")) {
                continue;
            }
            Matcher m = TIMEOUT_LINE.matcher(line);
            if (m.find()) {
                TimeoutPhase phase = TimeoutPhase.valueOf(m.group("phase"));
                double seconds = Double.parseDouble(m.group("seconds"));
                String reason = m.group("reason");
                out.add(new TimeoutErrorLogRecord(phase, seconds, reason, i + 1));
            }
        }
        return out;
    }

    /**
     * Convenience: count of 181008 timeout log lines of a given phase.
     */
    public long countOfPhase(TimeoutPhase phase) throws IOException {
        return collectAllTimeouts().stream().filter(r -> r.phase() == phase).count();
    }

    /**
     * Convenience: total count of 181008 timeout log lines (any phase).
     */
    public int totalTimeoutCount() throws IOException {
        return collectAllTimeouts().size();
    }

    /**
     * Convenience: predicate for TC-DEFAULT-BASELINE / TC-CANCEL-NOT-TIMEOUT
     * ("no 181008 in trace").
     */
    public boolean hasNoTimeout() throws IOException {
        return totalTimeoutCount() == 0;
    }

    /**
     * Convenience: predicate for S0/S6 — demo asserter REQUIRES the
     * Spring Boot startup banner line "Started TimeoutProbeAgent" so SIT
     * aligns with that marker as the "probe-agent came up cleanly" witness.
     */
    public boolean probeStarted() throws IOException {
        if (!Files.isRegularFile(logFile)) {
            return false;
        }
        for (String line : Files.readAllLines(logFile)) {
            if (line.contains("Started TimeoutProbeAgent")) {
                return true;
            }
        }
        return false;
    }

    /**
     * FEAT-064: count of {@code "Model call will retry."} lines emitted by
     * {@code ModelCallRetrier}. Used for:
     * <ul>
     *   <li>TC-RATE-LIMITED-PERSISTENT — expect {@code 2} (default maxRetries);
     *   <li>TC-RATE-LIMITED-RECOVER — expect {@code 1};
     *   <li>TC-TEMPORARY-EXHAUSTED — expect {@code 2} (both proves retry path
     *       is taken for TEMPORARY and proves default maxRetries=2);
     *   <li>TC-QUOTA/AUTH/CONTEXT-LENGTH/STREAM-ERROR-FRAME — expect {@code 0}
     *       (permanent short-circuit or first-segment boundary).
     * </ul>
     */
    public long countOfRetryLog() throws IOException {
        if (!Files.isRegularFile(logFile)) {
            return 0L;
        }
        long count = 0;
        for (String line : Files.readAllLines(logFile)) {
            if (RETRY_LINE.matcher(line).find()) {
                count++;
            }
        }
        return count;
    }

    /**
     * FEAT-064: count of log lines containing {@code [NNN]} status code tag.
     * Used to assert the four new error codes 181009/181010/181011/181012
     * and the legacy 181001 (generic MODEL_CALL_ERROR) + 181008 (timeout).
     */
    public long countOfStatusCode(int statusCode) throws IOException {
        if (!Files.isRegularFile(logFile)) {
            return 0L;
        }
        String needle = "[" + statusCode + "]";
        long count = 0;
        for (String line : Files.readAllLines(logFile)) {
            if (line.contains(needle)) {
                count++;
            }
        }
        return count;
    }

    /**
     * FEAT-064: parse the {@code category=<CAT>} field from the last log line
     * carrying a classification tag. Returns {@code null} if no such line is
     * present. Used by log-side category assertions.
     */
    public String categoryOfLastError() throws IOException {
        if (!Files.isRegularFile(logFile)) {
            return null;
        }
        String last = null;
        for (String line : Files.readAllLines(logFile)) {
            Matcher m = CATEGORY_FIELD.matcher(line);
            if (m.find()) {
                last = m.group("category");
            }
        }
        return last;
    }

    /**
     * Phase enumeration mirrors agent-core-java {@code TimeoutPhase}
     * (upper SNAKE_CASE). Used as the parsed-phase type for log records and
     * as the API input type for {@link ErrorEnvelopeAsserter}.
     */
    public enum TimeoutPhase {
        BEFORE_CONNECT,
        BEFORE_FIRST_SEGMENT,
        AFTER_FIRST_SEGMENT
    }

    public record TimeoutErrorLogRecord(
            TimeoutPhase phase,
            double effectiveTimeoutSeconds,
            String reason,
            int logLineOffset) {
    }
}

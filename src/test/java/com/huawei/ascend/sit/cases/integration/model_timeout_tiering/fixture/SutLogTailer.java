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

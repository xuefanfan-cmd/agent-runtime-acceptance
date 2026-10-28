/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.context_compression.fixture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Reads probe-agent stdout log and matches {@code StructuredCompactProcessor}
 * phase lines ({@code phase=start / partition / budget / attempt / retry /
 * fallback / complete}). The log templates come straight from
 * {@code StructuredCompactProcessor$Operation} — e.g.:
 *
 * <pre>
 * Structured compaction operation=<id> phase=complete outcome=LLM_COMPACTED reason=SUCCESS \
 *   before_tokens=1234 after_tokens=567 middle_retention=0.42 above_trigger_tokens=234 \
 *   identifiers_repaired=0 attempts=1 llm_calls=1 duration_ms=321
 * </pre>
 *
 * <p>The scenario-trace demo asserts against the same phase lines via extended
 * regex grep — this tailer offers Java-side counterparts.
 */
public final class ProbeLogTailer {

    private final Path logFile;

    public ProbeLogTailer(Path logFile) {
        this.logFile = Objects.requireNonNull(logFile, "logFile");
    }

    public Path logFile() {
        return logFile;
    }

    public List<String> readAllLines() throws IOException {
        if (!Files.isRegularFile(logFile)) {
            return List.of();
        }
        return Files.readAllLines(logFile);
    }

    /** True iff at least one log line matches the extended-regex pattern. */
    public boolean matches(String regex) throws IOException {
        Pattern p = Pattern.compile(regex);
        for (String line : readAllLines()) {
            if (p.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    public int countMatches(String regex) throws IOException {
        Pattern p = Pattern.compile(regex);
        int n = 0;
        for (String line : readAllLines()) {
            if (p.matcher(line).find()) {
                n++;
            }
        }
        return n;
    }

    /**
     * Scenario-trace demo helper: assert the required pattern appears at least
     * once in the log. Throws {@link AssertionError} with the log tail on miss.
     */
    public void assertRequired(String regex) throws IOException {
        if (!matches(regex)) {
            throw new AssertionError("required log pattern not found: /" + regex + "/\n"
                    + "--- log tail (last 80 lines of " + logFile + ") ---\n"
                    + tail(80));
        }
    }

    /**
     * Scenario-trace demo helper: assert the forbidden pattern never appears
     * in the log. Throws {@link AssertionError} with the log tail on leak.
     */
    public void assertForbidden(String regex) throws IOException {
        if (matches(regex)) {
            throw new AssertionError("forbidden log pattern leaked: /" + regex + "/\n"
                    + "--- log tail (last 80 lines of " + logFile + ") ---\n"
                    + tail(80));
        }
    }

    /** Return the last {@code lines} lines of the log, or a diagnostic marker. */
    public String tail(int lines) {
        if (!Files.isRegularFile(logFile)) {
            return "(log unavailable: " + logFile + ")";
        }
        try {
            List<String> all = Files.readAllLines(logFile);
            int from = Math.max(0, all.size() - lines);
            return String.join("\n", all.subList(from, all.size()));
        } catch (IOException e) {
            return "(cannot read " + logFile + ": " + e.getMessage() + ")";
        }
    }

    /** Convenience: did the processor emit a {@code phase=complete} line? */
    public boolean completeEmitted() throws IOException {
        return matches("phase=complete");
    }

    /**
     * Convenience: how many distinct {@code phase=complete} lines have been
     * emitted. Scenarios that drive repeat compactions (s17 identifier repair /
     * s18 save-load round trip) rely on this to assert the processor finished
     * the second round before the test asserts.
     */
    public int completeCount() throws IOException {
        return countMatches("phase=complete");
    }

    /**
     * Convenience: did the probe emit a {@code rail_dispatch_begin} line? The
     * 4-level assembly chain emits this via {@code CompactProbeController}
     * when {@code POST /trigger-compact-via-rail} is invoked and the registered
     * {@code ContextProcessorRail} forwards to the {@code StructuredCompactProcessor}.
     */
    public boolean railDispatchEmitted() throws IOException {
        return matches("rail_dispatch_begin")
                && matches("rail_dispatch_end status=ok");
    }
}

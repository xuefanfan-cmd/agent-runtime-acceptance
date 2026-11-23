package com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy;

import com.huawei.ascend.sit.config.TestConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * libfaketime on the DA process: the process wall clock becomes real time plus an offset read from a file the test
 * rewrites while the DA runs. Monotonic clocks stay real (the JVM's timers and timeouts are unaffected), so only
 * wall-clock readings such as {@code Instant.now()} move. Linux only; the library path comes from
 * {@code sut.infra.libfaketime}. Whether a shift took effect is checked against times the DA itself reports.
 */
public final class ClockControl implements AutoCloseable {
    /** libfaketime re-reads the offset file at most this often. */
    public static final Duration CACHE = Duration.ofSeconds(1);
    private final Path library;
    private final Path offsetFile;
    private volatile Duration offset = Duration.ZERO;

    private ClockControl(Path library, Path offsetFile) {
        this.library = library;
        this.offsetFile = offsetFile;
    }

    static ClockControl prepare(TestConfig config, String formName) throws IOException {
        String os = System.getProperty("os.name", "");
        String configured = config.getString("sut.infra.libfaketime", "/usr/lib/x86_64-linux-gnu/faketime/libfaketime.so.1");
        if (!os.toLowerCase(Locale.ROOT).contains("linux")) {
            throw new IllegalStateException("deployment form " + formName + " needs clock control (libfaketime on Linux); this host runs " + os);
        }
        Path library = Path.of(configured);
        if (!Files.isRegularFile(library)) {
            throw new IllegalStateException("deployment form " + formName + " needs libfaketime; not found at " + library
                    + " (install faketime or set sut.infra.libfaketime)");
        }
        Path file = Files.createTempFile("feat112-" + formName + "-faketime-", ".rc");
        Files.writeString(file, format(Duration.ZERO), StandardCharsets.US_ASCII);
        return new ClockControl(library, file);
    }

    /** Environment of the DA process that puts it under this control. */
    public Map<String, String> environment() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("LD_PRELOAD", library.toString());
        env.put("FAKETIME_TIMESTAMP_FILE", offsetFile.toString());
        env.put("FAKETIME_CACHE_DURATION", Long.toString(CACHE.toSeconds()));
        env.put("FAKETIME_DONT_FAKE_MONOTONIC", "1");
        env.put("NO_FAKE_STAT", "1"); // file timestamps need no faking; spares the JVM a faked stat() per file access
        return env;
    }

    /**
     * Sets the DA wall clock to real time plus {@code offset} (negative moves it back) and waits past the re-read
     * interval. The shift must still be confirmed from a time the DA reports.
     */
    public void set(Duration newOffset) throws IOException, InterruptedException {
        Files.writeString(offsetFile, format(newOffset), StandardCharsets.US_ASCII);
        offset = newOffset;
        Thread.sleep(CACHE.toMillis() * 2 + 200);
    }

    public Duration offset() {
        return offset;
    }

    /** libfaketime relative offset: sign followed by seconds. */
    static String format(Duration offset) {
        long seconds = offset.toSeconds();
        return (seconds < 0 ? "-" : "+") + Math.abs(seconds);
    }

    /**
     * How far the DA clock is behind the test clock according to a response {@code Date} header the DA wrote
     * (seconds; positive = DA behind). {@code null} when the response carries no parsable Date header.
     */
    public static Long reportedLagSeconds(Map<String, java.util.List<String>> headers, java.time.Instant received) {
        for (var header : headers.entrySet()) {
            if (header.getKey() == null || !header.getKey().equalsIgnoreCase("date") || header.getValue().isEmpty()) continue;
            try {
                var reported = java.time.ZonedDateTime.parse(header.getValue().get(0),
                        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Duration.between(reported, received).toSeconds();
            } catch (java.time.format.DateTimeParseException unparsable) {
                return null;
            }
        }
        return null;
    }

    public String describe() {
        return "libfaketime " + library + " offset " + format(offset) + "s via " + offsetFile;
    }

    @Override
    public void close() {
        try {
            Files.deleteIfExists(offsetFile);
        } catch (IOException ignored) {
            // temp file only
        }
    }
}

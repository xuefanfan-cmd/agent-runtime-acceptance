package com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured gate events ({@code event=<name> key=value ...}) in the owned process output. Events are matched by
 * name and keys, never by the wording of the line; reading starts at an offset taken before the scenario acts, so
 * events of earlier scenarios on the same process are not mistaken for this one's.
 */
public final class GateEventLog {
    private static final int MAX_READ = 8 * 1024 * 1024;

    private GateEventLog() {}

    /** One event line: its name, its {@code key=value} pairs in order, and the raw line for the evidence record. */
    public record Event(String name, Map<String, String> keys, String line) {
        public String key(String key) {
            return keys.get(key);
        }

        public long number(String key) {
            String value = keys.get(key);
            if (value == null) return -1;
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException notNumeric) {
                return -1;
            }
        }
    }

    /** Current end of the log: the offset to read a scenario's events from. */
    public static long mark(Path log) throws IOException {
        return Files.isRegularFile(log) ? Files.size(log) : 0;
    }

    /** Events named {@code name} written after {@code offset}, in log order. */
    public static List<Event> since(Path log, long offset, String name) throws IOException {
        List<Event> events = new ArrayList<>();
        for (String line : read(log, offset).split("\\R")) {
            Event event = parse(line);
            if (event != null && event.name().equals(name)) events.add(event);
        }
        return List.copyOf(events);
    }

    static Event parse(String line) {
        int start = line.indexOf("event=");
        if (start < 0) return null;
        Map<String, String> keys = new LinkedHashMap<>();
        for (String token : line.substring(start).trim().split("\\s+")) {
            int eq = token.indexOf('=');
            if (eq > 0) keys.putIfAbsent(token.substring(0, eq), token.substring(eq + 1));
        }
        String name = keys.get("event");
        return name == null || name.isEmpty() ? null : new Event(name, Map.copyOf(keys), line.strip());
    }

    private static String read(Path log, long offset) throws IOException {
        if (!Files.isRegularFile(log)) return "";
        try (var file = new RandomAccessFile(log.toFile(), "r")) {
            long length = file.length();
            if (length <= offset) return "";
            byte[] bytes = new byte[(int) Math.min(length - offset, MAX_READ)];
            file.seek(offset);
            file.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}

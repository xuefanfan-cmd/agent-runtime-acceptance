/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.run_budget_react.fixture;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads structured FEAT-057 budget events from a SUT stdout log file.
 *
 * <p>The agent-core-java {@code DefaultLogger.logEvent} path serializes each
 * {@code BaseLogEvent.toMap()} to JSON via Jackson and emits one SLF4J info
 * line. Spring Boot's logback prepends a timestamp + level + logger prefix, so
 * each log line looks like:
 *
 * <pre>
 * 2026-10-19T15:39:21.456+08:00 INFO 123 --- [thread] c.o.c.c.l.d.DefaultLogger : {"event_type":"turn_budget_...", ...}
 * </pre>
 *
 * <p>This tailer scans every line, locates the first {@code '{'} and tries to
 * parse the suffix as JSON. If the parsed object has an {@code event_type}
 * matching one of the 11 budget event names in {@link BudgetEventName}, it is
 * collected as a {@link RunBudgetEvent}.
 */
public final class SutLogTailer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path logFile;

    public SutLogTailer(Path logFile) {
        this.logFile = Objects.requireNonNull(logFile, "logFile");
    }

    public Path logFile() {
        return logFile;
    }

    /**
     * One-shot scan of the entire log file. Returns the budget events in the
     * order they appear.
     */
    public List<RunBudgetEvent> collectAll() throws IOException {
        List<RunBudgetEvent> out = new ArrayList<>();
        if (!Files.isRegularFile(logFile)) {
            return out;
        }
        List<String> lines = Files.readAllLines(logFile);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int braceIdx = line.indexOf('{');
            if (braceIdx < 0) {
                continue;
            }
            String maybeJson = line.substring(braceIdx);
            RunBudgetEvent event = tryParse(maybeJson, i + 1);
            if (event != null) {
                out.add(event);
            }
        }
        return out;
    }

    private RunBudgetEvent tryParse(String json, int lineOffset) {
        try {
            Map<String, Object> map = MAPPER.readValue(json, new TypeReference<>() {});
            Object wire = map.get("event_type");
            if (wire == null) {
                return null;
            }
            BudgetEventName name = BudgetEventName.fromWire(wire.toString());
            if (name == null) {
                return null;
            }
            return new RunBudgetEvent(name, map, lineOffset);
        } catch (Exception notBudgetJson) {
            return null;
        }
    }
}

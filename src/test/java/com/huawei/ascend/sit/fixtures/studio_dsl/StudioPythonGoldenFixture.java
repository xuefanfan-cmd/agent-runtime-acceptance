/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.fixtures.studio_dsl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Invokes a separately supplied Studio Python runtime and captures its JSON topology summary. */
public final class StudioPythonGoldenFixture {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<String> commandPrefix;

    public StudioPythonGoldenFixture(List<String> commandPrefix) {
        if (commandPrefix == null || commandPrefix.isEmpty()
                || commandPrefix.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("an explicit Python runtime command is required");
        }
        this.commandPrefix = List.copyOf(commandPrefix);
    }

    /**
     * Runs {@code <commandPrefix> --ir <ir> --out <out>} against the supplied Studio Python runtime and returns the
     * summary file it writes, matching {@code tools/dump_ir_assemble_golden.py}.
     */
    public GoldenResult summarize(Path ir, Duration timeout) throws IOException, InterruptedException {
        Path out = Files.createTempFile("studio-python-golden-", ".json");
        try {
            List<String> command = new ArrayList<>(commandPrefix);
            command.add("--ir");
            command.add(ir.toAbsolutePath().normalize().toString());
            command.add("--out");
            command.add(out.toAbsolutePath().normalize().toString());
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("Studio Python golden command timed out after " + timeout);
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                throw new IOException("Studio Python golden command failed with exit "
                        + process.exitValue() + ": " + output);
            }
            if (!Files.exists(out)) {
                throw new IOException("Studio Python golden command did not write the --out summary file");
            }
            String summaryText = Files.readString(out, StandardCharsets.UTF_8).trim();
            JsonNode summary = MAPPER.readTree(summaryText);
            if (summary == null || !summary.isObject()) {
                throw new IOException("Studio Python golden command did not return a JSON object");
            }
            return new GoldenResult(List.copyOf(command), summaryText, summary);
        } finally {
            Files.deleteIfExists(out);
        }
    }

    public record GoldenResult(List<String> command, String rawOutput, JsonNode summary) {
    }
}

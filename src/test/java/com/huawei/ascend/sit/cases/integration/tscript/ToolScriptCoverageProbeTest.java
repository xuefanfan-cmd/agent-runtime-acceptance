package com.huawei.ascend.sit.cases.integration.tscript;

import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage backfill probes for FEAT-038 scenarios that need input shapes the earlier scenarios
 * could not produce: non-object tool arguments.
 *
 * <p>The probes only observe the SUT; no product rule is implemented here.
 *
 * <p>TS038-C12 (a tool call without any card) is not implemented here: the model refuses to emit a
 * function name outside its tool list, so the case is recorded as {@code blocked} in the test design
 * with that transcript as evidence.
 */
@Tag("integration")
@Tag("tscript")
@Tag("feat-038")
@Feature("FEAT-038: 业务扩展工具话术")
class ToolScriptCoverageProbeTest extends AbstractToolScriptSitTest {
    private static final String ARRAY_TOOL = "tscript_arg_array";
    private static final String SCALAR_TOOL = "tscript_arg_scalar";

    @Override
    protected String scenarioName() {
        return "coverage-probe";
    }

    @Test
    @Story("ts038.arg-shape-fallback: 入参形态非法按无意图处理")
    @DisplayName("TS038-C26: non-object tool arguments fall back to the default scripts")
    void nonObjectArgumentsFallBackToDefaultScripts() throws Exception {
        String arrayCanary = newCanary();
        ToolScriptWireClient.Run arrayRun = send("TSCRIPT tool=" + ARRAY_TOOL
                + " mode=plain raw_args_mode=array canary=" + arrayCanary + ". Call exactly " + ARRAY_TOOL
                + " once and copy mode, raw_args_mode and canary verbatim into the JSON arguments object.");
        assertPair(arrayRun, ARRAY_TOOL, "DEFAULT_START", "DEFAULT_END");
        assertThat(nonObjectShapes(ARRAY_TOOL))
                .as("the fixture must have received a non-object argument shape for " + ARRAY_TOOL)
                .isPositive();
        assertThat(counts().getOrDefault("execute." + ARRAY_TOOL, 0))
                .as("the tool facts must still be produced for " + ARRAY_TOOL)
                .isPositive();

        String scalarCanary = newCanary();
        ToolScriptWireClient.Run scalarRun = send("TSCRIPT tool=" + SCALAR_TOOL
                + " mode=plain raw_args_mode=scalar canary=" + scalarCanary + ". Call exactly " + SCALAR_TOOL
                + " once and copy mode, raw_args_mode and canary verbatim into the JSON arguments object.");
        assertPair(scalarRun, SCALAR_TOOL, "DEFAULT_START", "DEFAULT_END");
        assertThat(nonObjectShapes(SCALAR_TOOL))
                .as("the fixture must have received a non-object argument shape for " + SCALAR_TOOL)
                .isPositive();
        assertThat(counts().getOrDefault("execute." + SCALAR_TOOL, 0))
                .as("the tool facts must still be produced for " + SCALAR_TOOL)
                .isPositive();

        Allure.parameter("argumentShapeCounters", describedShape(SCALAR_TOOL));
    }

    private int nonObjectShapes(String tool) throws Exception {
        Map<String, Integer> counters = counts();
        return counters.getOrDefault("toolargs.applied." + tool + ".json_array", 0)
                + counters.getOrDefault("toolargs.applied." + tool + ".json_scalar", 0)
                + counters.getOrDefault("toolargs.applied." + tool + ".invalid_json", 0);
    }

    private String describedShape(String tool) throws Exception {
        Map<String, Integer> counters = counts();
        String prefix = "toolargs.applied." + tool + ".";
        String shapes = counters.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .map(entry -> entry.getKey().substring(prefix.length()) + "=" + entry.getValue())
                .sorted()
                .collect(Collectors.joining(","));
        return (shapes.isBlank() ? "not-observed" : shapes)
                + ";injected=" + counters.getOrDefault("inject.non_object_arguments.scalar", 0)
                + ";emptyArgsExecutions=" + counters.getOrDefault("args." + tool + ".empty", 0);
    }
}

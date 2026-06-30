package com.huawei.ascend.sit.model.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.huawei.ascend.sit.utils.TestDataLoader;

import java.util.List;

/**
 * Data model for A-04 message/stream primary scenario.
 *
 * <p>Loaded from {@code testdata/component/protocol/a04-stream-hello.json}.
 * Keeps user input, timing budget, and state expectations out of test methods.</p>
 *
 * <p>See {@code docs/cases/A-04-message-stream.md} §7.</p>
 *
 * @param inputText                 user message sent via {@code SendStreamingMessage}
 * @param streamTimeoutMs           maximum wait for the SSE stream to finish (milliseconds)
 * @param expectedTerminalState     required final task state name (e.g. {@code TASK_STATE_COMPLETED})
 * @param requiredStates            states that must appear in the extracted sequence
 * @param optionalObservationStates states recorded for coverage only; absence does not fail A-04
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MessageStreamScenarioData(
        String inputText,
        long streamTimeoutMs,
        String expectedTerminalState,
        List<String> requiredStates,
        List<String> optionalObservationStates
) {

    /** Classpath path relative to {@code testdata/}. */
    public static final String DEFAULT_TESTDATA_PATH = "component/protocol/a04-stream-hello.json";

    /** Load the default A-04 scenario from test resources. */
    public static MessageStreamScenarioData loadDefault() {
        return TestDataLoader.load(DEFAULT_TESTDATA_PATH, MessageStreamScenarioData.class);
    }
}

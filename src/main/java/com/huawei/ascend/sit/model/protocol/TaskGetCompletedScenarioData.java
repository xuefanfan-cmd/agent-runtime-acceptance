package com.huawei.ascend.sit.model.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.huawei.ascend.sit.utils.TestDataLoader;

/**
 * Data model for A-05 tasks/get primary scenario.
 *
 * <p>Loaded from {@code testdata/component/protocol/a05-get-completed-hello.json}.
 * Keeps user input, timing budget, and terminal state expectations out of test methods.</p>
 *
 * <p>See {@code docs/cases/A-05-task-get-completed.md} §7.</p>
 *
 * @param inputText             user message sent via synchronous {@code message/send}
 * @param sendTimeoutMs         maximum wait for send to reach a terminal state (milliseconds)
 * @param expectedTerminalState required final task state name (e.g. {@code TASK_STATE_COMPLETED})
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskGetCompletedScenarioData(
        String inputText,
        long sendTimeoutMs,
        String expectedTerminalState
) {

    /** Classpath path relative to {@code testdata/}. */
    public static final String DEFAULT_TESTDATA_PATH = "component/protocol/a05-get-completed-hello.json";

    /** Load the default A-05 scenario from test resources. */
    public static TaskGetCompletedScenarioData loadDefault() {
        return TestDataLoader.load(DEFAULT_TESTDATA_PATH, TaskGetCompletedScenarioData.class);
    }
}

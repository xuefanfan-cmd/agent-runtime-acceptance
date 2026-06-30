package com.huawei.ascend.sit.model.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.huawei.ascend.sit.utils.TestDataLoader;
import org.a2aproject.sdk.spec.TaskState;

/**
 * Data model for A-06 tasks/cancel scenarios (stream + sync).
 *
 * <p>Loaded from {@code testdata/component/protocol/a06-cancel-long-prompt.json}.
 * Shared by {@code AgentTaskCancelStreamTest} and {@code AgentTaskCancelSyncTest}.</p>
 *
 * <p>See {@code docs/cases/A-06-task-cancel.md} §7.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskCancelScenarioData(
        String inputText,
        long taskIdWaitMs,
        long cancelWaitMs,
        long cancelPollMs,
        long streamTimeoutMs,
        String expectedTerminalStateAfterCancel
) {

    public static final String DEFAULT_TESTDATA_PATH = "component/protocol/a06-cancel-long-prompt.json";

    public static TaskCancelScenarioData loadDefault() {
        return TestDataLoader.load(DEFAULT_TESTDATA_PATH, TaskCancelScenarioData.class);
    }

    public TaskState expectedCanceledState() {
        return TaskState.valueOf(expectedTerminalStateAfterCancel);
    }
}

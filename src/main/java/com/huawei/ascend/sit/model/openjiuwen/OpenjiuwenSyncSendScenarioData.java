package com.huawei.ascend.sit.model.openjiuwen;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.huawei.ascend.sit.utils.TestDataLoader;
import org.a2aproject.sdk.spec.TaskState;

/**
 * Data model for OJ-01 synchronous SendMessage scenario.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenjiuwenSyncSendScenarioData(
        String inputText,
        long sendTimeoutMs,
        String expectedTerminalState,
        int minResponseLength
) {

    public static final String DEFAULT_TESTDATA_PATH = "component/protocol/oj-01-sync-send.json";

    public static OpenjiuwenSyncSendScenarioData loadDefault() {
        return TestDataLoader.load(DEFAULT_TESTDATA_PATH, OpenjiuwenSyncSendScenarioData.class);
    }

    public TaskState resolvedExpectedTerminalState() {
        return TaskState.valueOf(expectedTerminalState);
    }
}

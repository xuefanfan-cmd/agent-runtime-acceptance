package com.huawei.ascend.sit.model.component.boundary;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.huawei.ascend.sit.utils.TestDataLoader;
import org.a2aproject.sdk.spec.TaskState;

import java.util.List;

/**
 * Data model for C-06 empty message boundary scenarios.
 *
 * <p>See {@code docs/cases/C-06-empty-message.md} §7.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmptyMessageScenarioData(
        String inputText,
        String healthProbeText,
        long emptyMessageTimeoutMs,
        long healthProbeTimeoutMs,
        String expectedTerminalState,
        String expectedErrorCode,
        List<String> expectedErrorDetailFragments,
        String healthProbeExpectedTerminalState
) {

    public static final String DEFAULT_TESTDATA_PATH = "component/boundary/c06-empty-message.json";

    public static EmptyMessageScenarioData loadDefault() {
        return TestDataLoader.load(DEFAULT_TESTDATA_PATH, EmptyMessageScenarioData.class);
    }

    public TaskState resolvedExpectedTerminalState() {
        return TaskState.valueOf(expectedTerminalState);
    }

    public TaskState resolvedHealthProbeTerminalState() {
        return TaskState.valueOf(healthProbeExpectedTerminalState);
    }
}

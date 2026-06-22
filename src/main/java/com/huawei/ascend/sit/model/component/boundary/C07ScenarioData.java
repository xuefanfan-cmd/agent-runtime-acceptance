package com.huawei.ascend.sit.model.component.boundary;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.huawei.ascend.sit.utils.TestDataLoader;
import org.a2aproject.sdk.spec.TaskState;

/**
 * Data model for C-07 long message boundary scenarios.
 *
 * <p>See {@code docs/cases/C-07-long-message.md} §7.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record C07ScenarioData(
        int minInputChars,
        String travelTemplatePrefix,
        String paddingSentence,
        String healthProbeText,
        long longMessageTimeoutMs,
        long healthProbeTimeoutMs,
        String healthProbeExpectedTerminalState
) {

    public static final String DEFAULT_TESTDATA_PATH = "component/boundary/c07-long-travel-message.json";

    public static C07ScenarioData loadDefault() {
        return TestDataLoader.load(DEFAULT_TESTDATA_PATH, C07ScenarioData.class);
    }

    public TaskState resolvedHealthProbeTerminalState() {
        return TaskState.valueOf(healthProbeExpectedTerminalState);
    }
}

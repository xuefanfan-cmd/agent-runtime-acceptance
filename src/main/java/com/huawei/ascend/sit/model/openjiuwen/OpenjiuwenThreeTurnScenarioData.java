package com.huawei.ascend.sit.model.openjiuwen;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.huawei.ascend.sit.utils.TestDataLoader;
import org.a2aproject.sdk.spec.TaskState;

import java.util.List;

/**
 * Data model for OJ-05 three-turn INPUT_REQUIRED collection scenario.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenjiuwenThreeTurnScenarioData(
        String sessionId,
        long timeoutMs,
        List<TurnSpec> turns
) {

    public static final String DEFAULT_TESTDATA_PATH = "openjiuwen/integration/oj-05-three-turn-collection.json";

    public static OpenjiuwenThreeTurnScenarioData loadDefault() {
        return TestDataLoader.load(DEFAULT_TESTDATA_PATH, OpenjiuwenThreeTurnScenarioData.class);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TurnSpec(
            String text,
            String expectedTerminal,
            List<String> allowedTerminalStates,
            Long timeoutMs,
            Integer minResponseLength
    ) {
        public TaskState resolvedExpectedTerminal() {
            return TaskState.valueOf(expectedTerminal);
        }

        public List<TaskState> resolvedAllowedStates() {
            if (allowedTerminalStates != null && !allowedTerminalStates.isEmpty()) {
                return allowedTerminalStates.stream().map(TaskState::valueOf).toList();
            }
            return List.of(resolvedExpectedTerminal());
        }
    }
}

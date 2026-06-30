package com.huawei.ascend.sit.model.component.boundary;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.huawei.ascend.sit.utils.TestDataLoader;
import org.a2aproject.sdk.spec.TaskState;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Data model for C-09 LLM-unavailable boundary scenarios.
 *
 * <p>See {@code docs/cases/C-09-llm-unavailable.md} §7.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmUnavailableScenarioData(
        String inputText,
        long llmFailureTimeoutMs,
        List<String> disallowedTerminalStates
) {

    public static final String DEFAULT_TESTDATA_PATH = "component/boundary/c09-llm-unavailable.json";

    public static LlmUnavailableScenarioData loadDefault() {
        return TestDataLoader.load(DEFAULT_TESTDATA_PATH, LlmUnavailableScenarioData.class);
    }

    public Set<TaskState> resolvedDisallowedTerminalStates() {
        return disallowedTerminalStates.stream()
                .map(TaskState::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }
}

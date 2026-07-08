package com.huawei.ascend.sit.model.openjiuwen;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.huawei.ascend.sit.utils.TestDataLoader;
import org.a2aproject.sdk.spec.TaskState;

import java.util.List;

/**
 * Data model for OJ-11 hotel skill-hub {@code hotel_ranking} trigger (streaming).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenjiuwenSkillHubScenarioData(
        String inputText,
        String expectedTerminalState,
        List<String> mustMatchAny,
        int mustMatchMinCount,
        String mustMentionCity,
        List<String> businessContextKeywords,
        boolean mustNotBeOnlyCapabilityBlurb,
        long timeoutMs
) {

    public static OpenjiuwenSkillHubScenarioData loadDefault() {
        return TestDataLoader.load("openjiuwen/integration/oj-11-skill-hotel-ranking.json",
                OpenjiuwenSkillHubScenarioData.class);
    }

    public TaskState resolvedExpectedTerminalState() {
        return TaskState.valueOf(expectedTerminalState);
    }
}

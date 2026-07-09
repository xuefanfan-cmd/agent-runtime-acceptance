package com.huawei.ascend.sit.model.openjiuwen;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.huawei.ascend.sit.utils.TestDataLoader;

import java.util.List;

/**
 * Data model for OJ-04 session isolation scenario.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenjiuwenSessionIsolationScenarioData(
        String turn2Text,
        long timeoutMs,
        List<String> turn2MustMatchAny,
        List<SessionCase> sessions
) {

    private static final List<String> DEFAULT_TURN2_MUST_MATCH_ANY =
            List.of("住宿", "800", "出差");

    public static final String DEFAULT_TESTDATA_PATH = "component/singleagent/oj-04-session-isolation.json";

    public static OpenjiuwenSessionIsolationScenarioData loadDefault() {
        return TestDataLoader.load(DEFAULT_TESTDATA_PATH, OpenjiuwenSessionIsolationScenarioData.class);
    }

    public List<String> resolvedTurn2MustMatchAny() {
        return turn2MustMatchAny == null || turn2MustMatchAny.isEmpty()
                ? DEFAULT_TURN2_MUST_MATCH_ANY
                : turn2MustMatchAny;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SessionCase(
            String contextId,
            String turn1,
            String expectedCity,
            String forbiddenDominantCity
    ) {
    }
}

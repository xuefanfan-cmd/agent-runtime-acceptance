package com.huawei.ascend.sit.model.openjiuwen;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.huawei.ascend.sit.utils.TestDataLoader;
import org.a2aproject.sdk.spec.TaskState;

import java.util.List;

/**
 * Data model for OJ-03 two-turn short-term memory scenario.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenjiuwenTwoTurnScenarioData(
        String turn1Text,
        String turn2Text,
        long turn1TimeoutMs,
        long turn2TimeoutMs,
        List<String> turn1AllowedTerminalStates,
        List<String> turn2AllowedTerminalStates,
        String turn2ExpectedTerminalState,
        List<String> turn2MustMatchAny,
        List<String> turn2MustNotMatchAny
) {

    public static final String DEFAULT_TESTDATA_PATH = "component/singleagent/oj-03-two-turn-dialogue.json";

    public static OpenjiuwenTwoTurnScenarioData loadDefault() {
        return TestDataLoader.load(DEFAULT_TESTDATA_PATH, OpenjiuwenTwoTurnScenarioData.class);
    }

    /** OJ-06 — Redis checkpointer full-chain streaming two-turn dialogue. */
    public static OpenjiuwenTwoTurnScenarioData loadOj06() {
        return TestDataLoader.load("integration/react_travel/oj-06-redis-multi-turn.json",
                OpenjiuwenTwoTurnScenarioData.class);
    }

    public List<TaskState> resolvedTurn1AllowedStates() {
        return turn1AllowedTerminalStates.stream().map(TaskState::valueOf).toList();
    }

    public List<TaskState> resolvedTurn2AllowedStates() {
        if (turn2AllowedTerminalStates != null && !turn2AllowedTerminalStates.isEmpty()) {
            return turn2AllowedTerminalStates.stream().map(TaskState::valueOf).toList();
        }
        return List.of(TaskState.valueOf(turn2ExpectedTerminalState));
    }

    public TaskState resolvedTurn2ExpectedState() {
        return TaskState.valueOf(turn2ExpectedTerminalState);
    }
}

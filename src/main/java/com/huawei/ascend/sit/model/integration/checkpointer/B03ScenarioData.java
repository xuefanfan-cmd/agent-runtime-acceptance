package com.huawei.ascend.sit.model.integration.checkpointer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.huawei.ascend.sit.utils.TestDataLoader;
import org.a2aproject.sdk.spec.TaskState;

import java.util.List;

/**
 * Data model for B-03 Redis checkpointer multi-turn scenario.
 *
 * <p>See {@code docs/cases/B-03-redis-checkpointer-multi-turn.md} §7.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record B03ScenarioData(
        String turn1Text,
        String turn2Text,
        long turn1TimeoutMs,
        long turn2TimeoutMs,
        List<String> turn1AllowedTerminalStates,
        String turn2ExpectedTerminalState,
        List<String> turn2MustMatchAny,
        List<String> turn2MustNotMatchAny,
        long redisStartupMs
) {

    public static final String DEFAULT_TESTDATA_PATH = "integration/checkpointer/b03-redis-multi-turn.json";

    public static B03ScenarioData loadDefault() {
        return TestDataLoader.load(DEFAULT_TESTDATA_PATH, B03ScenarioData.class);
    }

    public List<TaskState> resolvedTurn1AllowedStates() {
        return turn1AllowedTerminalStates.stream().map(TaskState::valueOf).toList();
    }

    public TaskState resolvedTurn2ExpectedState() {
        return TaskState.valueOf(turn2ExpectedTerminalState);
    }
}

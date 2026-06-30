package com.huawei.ascend.sit.cases.integration.checkpointer;

import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.model.integration.checkpointer.RedisMultiTurnScenarioData;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.awaitility.Awaitility;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Waits for Turn1 to reach an allowed outcome ({@code COMPLETED} or {@code INPUT_REQUIRED}).
 */
final class TwoTurnDialogueAwait {

    private TwoTurnDialogueAwait() {
    }

    static TaskState awaitTurn1Outcome(A2aEventCollector collector, RedisMultiTurnScenarioData scenario) {
        List<TaskState> allowed = scenario.resolvedTurn1AllowedStates();
        return Awaitility.await("turn1 outcome")
                .atMost(scenario.turn1TimeoutMs(), TimeUnit.MILLISECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> resolveTurn1State(collector, allowed), Objects::nonNull);
    }

    private static TaskState resolveTurn1State(A2aEventCollector collector, List<TaskState> allowed) {
        if (collector.findInputRequiredEvent().isPresent()) {
            TaskState state = TaskState.TASK_STATE_INPUT_REQUIRED;
            return allowed.contains(state) ? state : null;
        }
        TaskState terminal = collector.findTerminalEvent()
                .flatMap(TwoTurnDialogueAwait::extractState)
                .orElse(null);
        if (terminal == null) {
            return null;
        }
        if (terminal == TaskState.TASK_STATE_FAILED || terminal == TaskState.TASK_STATE_CANCELED) {
            throw new AssertionError("Turn1 ended with " + terminal);
        }
        if (allowed.contains(terminal)) {
            return terminal;
        }
        if (terminal.isFinal()) {
            throw new AssertionError("Turn1 terminal " + terminal + " not in allowed " + allowed);
        }
        return null;
    }

    private static Optional<TaskState> extractState(ClientEvent event) {
        if (event instanceof TaskEvent taskEvent) {
            return Optional.of(taskEvent.getTask().status().state());
        }
        if (event instanceof TaskUpdateEvent updateEvent) {
            return Optional.of(updateEvent.getTask().status().state());
        }
        return Optional.empty();
    }
}

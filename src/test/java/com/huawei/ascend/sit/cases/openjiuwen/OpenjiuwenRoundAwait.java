package com.huawei.ascend.sit.cases.openjiuwen;

import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.awaitility.Awaitility;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared A2A round waiting / continuation helpers for openjiuwen multi-turn tests.
 *
 * <p>Sync {@code message/send} on long full-chain turns may finish server-side without pushing a
 * terminal client event; {@link #awaitAllowedOutcome} therefore also polls {@code getTask}.</p>
 */
final class OpenjiuwenRoundAwait {

    static final List<TaskState> COMPLETED_OR_INPUT_REQUIRED = List.of(
            TaskState.TASK_STATE_COMPLETED,
            TaskState.TASK_STATE_INPUT_REQUIRED);

    private OpenjiuwenRoundAwait() {
    }

    static TaskState awaitAllowedOutcome(A2aEventCollector collector, A2aServiceClient a2a,
                                         long timeoutMs, List<TaskState> allowed, String label) {
        return awaitAllowedOutcome(collector, a2a, null, timeoutMs, allowed, label);
    }

    static TaskState awaitAllowedOutcome(A2aEventCollector collector, A2aServiceClient a2a,
                                         String knownTaskId, long timeoutMs,
                                         List<TaskState> allowed, String label) {
        return Awaitility.await(label + " outcome")
                .atMost(timeoutMs, TimeUnit.MILLISECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> resolveAllowedState(collector, a2a, knownTaskId, allowed, label), Objects::nonNull);
    }

    static Message buildContinuationMessage(String text, String contextId,
                                            TaskState priorState, String priorTaskId) {
        Message.Builder builder = Message.builder(A2A.toUserMessage(text))
                .contextId(contextId);
        if (!priorState.isFinal()) {
            assertThat(priorTaskId).as("prior taskId for INPUT_REQUIRED continuation").isNotBlank();
            builder.taskId(priorTaskId);
        }
        return builder.build();
    }

    private static TaskState resolveAllowedState(A2aEventCollector collector, A2aServiceClient a2a,
                                                 String knownTaskId, List<TaskState> allowed,
                                                 String label) {
        TaskState fromEvents = resolveAllowedStateFromEvents(collector, allowed, label);
        if (fromEvents != null) {
            return fromEvents;
        }
        return resolveAllowedStateFromTaskSnapshot(collector, a2a, knownTaskId, allowed);
    }

    private static TaskState resolveAllowedStateFromEvents(A2aEventCollector collector,
                                                           List<TaskState> allowed, String label) {
        if (collector.findInputRequiredEvent().isPresent()) {
            TaskState state = TaskState.TASK_STATE_INPUT_REQUIRED;
            return allowed.contains(state) ? state : null;
        }
        TaskState terminal = collector.findTerminalEvent()
                .flatMap(OpenjiuwenRoundAwait::extractState)
                .orElse(null);
        if (terminal == null) {
            return null;
        }
        if (terminal == TaskState.TASK_STATE_FAILED || terminal == TaskState.TASK_STATE_CANCELED) {
            throw new AssertionError("Round ended with " + terminal);
        }
        if (allowed.contains(terminal)) {
            return terminal;
        }
        if (terminal.isFinal()) {
            String hint = terminal == TaskState.TASK_STATE_COMPLETED
                    && allowed.size() == 1
                    && allowed.get(0) == TaskState.TASK_STATE_INPUT_REQUIRED
                    ? " (agent likely replied in plain text instead of calling request_user_input)"
                    : "";
            throw new AssertionError(label + ": terminal " + terminal + " not in allowed " + allowed + hint);
        }
        return null;
    }

    /**
     * Fallback for sync send paths where the server completes the task but omits the terminal event.
     */
    private static TaskState resolveAllowedStateFromTaskSnapshot(A2aEventCollector collector,
                                                                 A2aServiceClient a2a,
                                                                 String knownTaskId,
                                                                 List<TaskState> allowed) {
        if (a2a == null) {
            return null;
        }
        String taskId = collector.findFirstTaskId();
        if (taskId == null || taskId.isBlank()) {
            taskId = knownTaskId;
        }
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        Task task = a2a.getTask(taskId);
        if (task == null || task.status() == null || task.status().state() == null) {
            return null;
        }
        TaskState state = task.status().state();
        if (state == TaskState.TASK_STATE_FAILED || state == TaskState.TASK_STATE_CANCELED) {
            throw new AssertionError("Round ended with " + state);
        }
        // Only trust getTask for terminal states — INPUT_REQUIRED must come from live events
        // (avoids stale INPUT_REQUIRED at continuation round start).
        if (state.isFinal() && allowed.contains(state)) {
            return state;
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

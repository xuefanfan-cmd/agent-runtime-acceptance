package com.huawei.ascend.sit.utils;

import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.awaitility.Awaitility;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Async wait utility built on Awaitility.
 *
 * <p>Replaces {@code Thread.sleep} with deterministic polling for
 * asynchronous task state transitions in the A2A protocol.</p>
 */
public final class WaitUtils {

    private WaitUtils() {}

    /**
     * Terminal states in the A2A protocol task state machine.
     * Uses TaskState.isFinal() for the authoritative check.
     */
    private static final java.util.Set<TaskState> TERMINAL_STATES = java.util.Set.of(
            TaskState.TASK_STATE_COMPLETED, TaskState.TASK_STATE_CANCELED,
            TaskState.TASK_STATE_FAILED, TaskState.TASK_STATE_REJECTED
    );

    /**
     * Poll until the supplied Task reaches a terminal state.
     *
     * @param pollAction     action that returns the current Task
     * @param timeoutSeconds maximum wait time
     * @param pollIntervalMs polling interval in milliseconds
     * @return the terminal Task
     */
    public static Task pollUntilTerminal(Supplier<Task> pollAction,
                                          int timeoutSeconds,
                                          long pollIntervalMs) {
        return Awaitility.await()
                .atMost(timeoutSeconds, TimeUnit.SECONDS)
                .pollInterval(pollIntervalMs, TimeUnit.MILLISECONDS)
                .pollInSameThread()
                .until(pollAction::get, WaitUtils::isTerminal);
    }

    /**
     * Poll until the given callable returns true.
     */
    public static void awaitCondition(Callable<Boolean> condition,
                                       int timeoutSeconds,
                                       long pollIntervalMs) {
        Awaitility.await()
                .atMost(timeoutSeconds, TimeUnit.SECONDS)
                .pollInterval(pollIntervalMs, TimeUnit.MILLISECONDS)
                .pollInSameThread()
                .until(condition);
    }

    /**
     * Poll until a callable returns a non-null value.
     */
    public static <T> T awaitNotNull(Callable<T> supplier,
                                      int timeoutSeconds,
                                      long pollIntervalMs) {
        return Awaitility.await()
                .atMost(timeoutSeconds, TimeUnit.SECONDS)
                .pollInterval(pollIntervalMs, TimeUnit.MILLISECONDS)
                .pollInSameThread()
                .until(supplier, result -> result != null);
    }

    /**
     * Wait for the SUT agent card to be resolvable (health check).
     *
     * @param healthAction   action that returns true if agent is reachable
     * @param timeoutSeconds maximum wait time
     */
    public static void awaitHealthy(Callable<Boolean> healthAction, int timeoutSeconds) {
        Awaitility.await("SUT agent card resolution")
                .atMost(timeoutSeconds, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .pollInSameThread()
                .until(healthAction);
    }

    /**
     * Check if a Task is in a terminal state.
     */
    public static boolean isTerminal(Task task) {
        return task != null
                && task.status() != null
                && TERMINAL_STATES.contains(task.status().state());
    }

    /**
     * Check if a TaskState is terminal.
     */
    public static boolean isTerminalState(TaskState state) {
        return state != null && TERMINAL_STATES.contains(state);
    }
}

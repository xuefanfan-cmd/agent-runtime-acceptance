package com.huawei.ascend.sit.client;

import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.awaitility.Awaitility;

import java.util.concurrent.TimeUnit;

/**
 * Waits for a cancel window on an in-flight A2A task before {@code tasks/cancel}.
 *
 * <p>Implements {@code docs/cases/reactagent/A-06-task-cancel.md} §5.</p>
 */
public final class CancelWindow {

    private static final long POLL_INTERVAL_MS = 200L;

    private CancelWindow() {
    }

    /**
     * @param taskIdWaitMs maximum wait for the first non-blank task id
     * @param cancelWaitMs maximum wait for {@code WORKING} before falling back to {@code SUBMITTED}
     */
    public static Result await(A2aEventCollector collector, long taskIdWaitMs, long cancelWaitMs) {
        String taskId = Awaitility.await("first taskId")
                .atMost(taskIdWaitMs, TimeUnit.MILLISECONDS)
                .pollInterval(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .until(collector::findFirstTaskId, id -> id != null && !id.isBlank());

        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(cancelWaitMs);
        while (System.nanoTime() < deadlineNanos) {
            TaskState latest = latestState(collector);
            if (latest != null) {
                if (latest == TaskState.TASK_STATE_WORKING) {
                    return new Result(taskId, TaskState.TASK_STATE_WORKING);
                }
                if (isMissedTerminal(latest)) {
                    throw missedWindow(latest);
                }
            }
            sleepPollInterval();
        }

        TaskState latest = latestState(collector);
        if (latest != null && isMissedTerminal(latest)) {
            throw missedWindow(latest);
        }
        return new Result(taskId, TaskState.TASK_STATE_SUBMITTED);
    }

    public record Result(String taskId, TaskState cancelAtState) {
    }

    private static boolean isMissedTerminal(TaskState state) {
        return state == TaskState.TASK_STATE_COMPLETED
                || state == TaskState.TASK_STATE_FAILED
                || state == TaskState.TASK_STATE_CANCELED;
    }

    private static AssertionError missedWindow(TaskState state) {
        return new AssertionError("cancel_window_missed=true: task reached " + state + " before cancel");
    }

    private static TaskState latestState(A2aEventCollector collector) {
        TaskState latest = null;
        for (ClientEvent event : collector.snapshotAllEvents()) {
            TaskState state = taskFrom(event).map(task -> task.status().state()).orElse(null);
            if (state != null) {
                latest = state;
            }
        }
        return latest;
    }

    private static java.util.Optional<Task> taskFrom(ClientEvent event) {
        if (event instanceof TaskEvent taskEvent) {
            return java.util.Optional.of(taskEvent.getTask());
        }
        if (event instanceof TaskUpdateEvent updateEvent) {
            return java.util.Optional.of(updateEvent.getTask());
        }
        return java.util.Optional.empty();
    }

    private static void sleepPollInterval() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for cancel window", e);
        }
    }
}

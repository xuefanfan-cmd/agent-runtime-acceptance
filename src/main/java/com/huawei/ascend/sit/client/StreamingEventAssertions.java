package com.huawei.ascend.sit.client;

import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Extraction and inspection helpers for {@link SendStreamingMessage} event streams.
 *
 * <p>Centralises the rules in {@code docs/cases/A-04-message-stream.md} §5 so protocol
 * tests do not scatter parsing logic. Reusable by A-04, A-09, and A-10.</p>
 */
public final class StreamingEventAssertions {

    private StreamingEventAssertions() {}

    /**
     * Whether a streaming event marks the end of the SSE stream for wait purposes.
     */
    public static boolean isTerminal(StreamingEventKind event) {
        TaskState state = stateFrom(event);
        if (state == null) {
            return false;
        }
        return state == TaskState.TASK_STATE_COMPLETED
                || state == TaskState.TASK_STATE_FAILED
                || state == TaskState.TASK_STATE_CANCELED
                || state == TaskState.TASK_STATE_REJECTED
                || state == TaskState.TASK_STATE_INPUT_REQUIRED;
    }

    /**
     * Extract task states in arrival order, collapsing consecutive duplicates.
     */
    public static List<TaskState> extractStates(List<StreamingEventKind> events) {
        List<TaskState> raw = new ArrayList<>();
        for (StreamingEventKind event : events) {
            TaskState state = stateFrom(event);
            if (state != null) {
                raw.add(state);
            }
        }
        return collapseConsecutive(raw);
    }

    /**
     * Return the single task id observed across the stream.
     *
     * @throws IllegalStateException if multiple distinct non-blank ids appear
     */
    public static String requireConsistentTaskId(List<StreamingEventKind> events) {
        String taskId = null;
        for (StreamingEventKind event : events) {
            String candidate = taskIdFrom(event);
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            if (taskId == null) {
                taskId = candidate;
            } else if (!taskId.equals(candidate)) {
                throw new IllegalStateException(
                        "Inconsistent taskId in stream: '" + taskId + "' vs '" + candidate + "'");
            }
        }
        return taskId != null ? taskId : "";
    }

    /**
     * Last terminal {@link TaskState} in the stream, if any.
     */
    public static Optional<TaskState> terminalState(List<StreamingEventKind> events) {
        TaskState terminal = null;
        for (StreamingEventKind event : events) {
            TaskState state = stateFrom(event);
            if (state != null && state.isFinal()) {
                terminal = state;
            }
        }
        return Optional.ofNullable(terminal);
    }

    /**
     * Concatenate readable text from message, status-update, and artifact events.
     */
    public static String extractText(List<StreamingEventKind> events) {
        StringBuilder text = new StringBuilder();
        for (StreamingEventKind event : events) {
            if (event instanceof Message message) {
                text.append(textFromParts(message.parts()));
            } else if (event instanceof TaskStatusUpdateEvent statusEvent
                    && statusEvent.status() != null
                    && statusEvent.status().message() != null) {
                text.append(textFromParts(statusEvent.status().message().parts()));
            } else if (event instanceof TaskArtifactUpdateEvent artifactEvent
                    && artifactEvent.artifact() != null) {
                text.append(textFromParts(artifactEvent.artifact().parts()));
            } else if (event instanceof Task task) {
                text.append(textFromTask(task));
            }
        }
        return text.toString().trim();
    }

    /**
     * Lower-cased diagnostic text from the stream for upstream failure classification.
     */
    public static String failureDiagnosticText(List<StreamingEventKind> events) {
        return extractText(events).toLowerCase(Locale.ROOT);
    }

    public static boolean indicatesUpstreamLlmFailure(String diagnosticText) {
        if (diagnosticText == null || diagnosticText.isBlank()) {
            return false;
        }
        return diagnosticText.contains("upstream_unavailable")
                || diagnosticText.contains("authentication")
                || diagnosticText.contains("unauthorized")
                || diagnosticText.contains("timeout")
                || diagnosticText.contains("api key")
                || diagnosticText.contains("rate limit");
    }

    public static TaskState parseStateName(String stateName) {
        Objects.requireNonNull(stateName, "stateName");
        return TaskState.valueOf(stateName);
    }

    private static TaskState stateFrom(StreamingEventKind event) {
        if (event instanceof TaskStatusUpdateEvent statusEvent && statusEvent.status() != null) {
            return statusEvent.status().state();
        }
        if (event instanceof Task task && task.status() != null) {
            return task.status().state();
        }
        return null;
    }

    private static String taskIdFrom(StreamingEventKind event) {
        if (event instanceof TaskStatusUpdateEvent statusEvent && statusEvent.taskId() != null) {
            return statusEvent.taskId();
        }
        if (event instanceof Task task) {
            return task.id();
        }
        if (event instanceof TaskArtifactUpdateEvent artifactEvent) {
            return artifactEvent.taskId();
        }
        return null;
    }

    private static List<TaskState> collapseConsecutive(List<TaskState> states) {
        if (states.isEmpty()) {
            return List.of();
        }
        List<TaskState> collapsed = new ArrayList<>();
        TaskState previous = null;
        for (TaskState state : states) {
            if (!state.equals(previous)) {
                collapsed.add(state);
                previous = state;
            }
        }
        return List.copyOf(collapsed);
    }

    private static String textFromTask(Task task) {
        StringBuilder text = new StringBuilder();
        if (task.history() != null) {
            task.history().forEach(message -> text.append(textFromParts(message.parts())));
        }
        if (task.artifacts() != null) {
            task.artifacts().forEach(artifact -> text.append(textFromParts(artifact.parts())));
        }
        if (task.status() != null && task.status().message() != null) {
            text.append(textFromParts(task.status().message().parts()));
        }
        return text.toString();
    }

    private static String textFromParts(List<Part<?>> parts) {
        if (parts == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Part<?> part : parts) {
            if (part instanceof TextPart textPart && textPart.text() != null) {
                text.append(textPart.text());
            }
        }
        return text.toString();
    }
}

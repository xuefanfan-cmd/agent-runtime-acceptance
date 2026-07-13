package com.huawei.ascend.sit.client;

import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.a2aproject.sdk.spec.UpdateEvent;
import org.awaitility.Awaitility;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/**
 * Thread-safe async event collector for A2A client events.
 *
 * <p>Registers as a {@link BiConsumer} with the A2A SDK's {@code sendMessage}
 * and collects all events into internal {@link ConcurrentLinkedQueue}s.
 * Provides Awaitility-based await methods for synchronous-style assertions
 * on asynchronous events.</p>
 *
 * <h3>Internal queue structure:</h3>
 * <ul>
 *   <li>{@code allEvents} — every {@link ClientEvent} received (unfiltered)</li>
 *   <li>{@code taskBearingEvents} — <b>task-state</b> events: {@link TaskEvent} plus
 *       {@link TaskUpdateEvent}s whose update is a {@link TaskStatusUpdateEvent}.
 *       These are the events that carry a meaningful state transition, so the state
 *       scanners ({@code findTerminal}/{@code findInputRequired}/await*) see a clean
 *       trajectory without artifact-update noise.</li>
 *   <li>{@code artifactEvents} — the <b>unified task-product queue</b>:
 *       {@link TaskUpdateEvent}s whose update is a {@link TaskArtifactUpdateEvent}
 *       <em>and</em> {@link MessageEvent}s (the agent's immediate textual replies).
 *       Both represent "things the agent produced", so they share one queue.</li>
 * </ul>
 *
 * <h3>Method categories:</h3>
 * <ul>
 *   <li><b>Find methods</b> ({@code findXxx}) — non-destructive peek; return
 *       {@link Optional} of the matching event. Queues are <em>unmodified</em>.</li>
 *   <li><b>Await methods</b> ({@code awaitXxx}) — blocking convenience wrappers;
 *       return extracted primitives (TaskState, boolean) for assertion ease.</li>
 *   <li><b>Snapshot methods</b> ({@code snapshotXxx}) — non-destructive defensive copies.</li>
 * </ul>
 *
 * <h3>Usage pattern:</h3>
 * <pre>{@code
 * A2aEventCollector collector = new A2aEventCollector();
 * a2aClient.sendMessage(message, List.of(collector.createConsumer()), errorHandler);
 *
 * TaskState finalState = collector.awaitTerminalState(30_000);
 * String taskId = collector.findFirstTaskId();
 * List<ClientEvent> events = collector.snapshotAllEvents();
 * }</pre>
 */
public class A2aEventCollector {

    private final ConcurrentLinkedQueue<ClientEvent> allEvents = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ClientEvent> taskBearingEvents = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ClientEvent> artifactEvents = new ConcurrentLinkedQueue<>();

    /**
     * Create a consumer suitable for registering with {@code Client.sendMessage}.
     *
     * <p>Events are dispatched by <em>semantics</em>, not just type:
     * <ul>
     *   <li>{@link TaskEvent} and a {@link TaskUpdateEvent} carrying a
     *       {@link TaskStatusUpdateEvent} → {@code taskBearingEvents} (state trajectory).</li>
     *   <li>A {@link TaskUpdateEvent} carrying a {@link TaskArtifactUpdateEvent} (or any
     *       non-status update), and any {@link MessageEvent} → {@code artifactEvents}
     *       (the unified task-product queue).</li>
     * </ul>
     */
    public BiConsumer<ClientEvent, AgentCard> createConsumer() {
        return (event, card) -> {
            allEvents.add(event);
            if (event instanceof TaskEvent) {
                taskBearingEvents.add(event);
            } else if (event instanceof TaskUpdateEvent tue) {
                if (isStatusUpdate(tue)) {
                    taskBearingEvents.add(event);
                } else {
                    // artifact update (or any non-status update) → unified product queue
                    artifactEvents.add(event);
                }
            } else if (event instanceof MessageEvent) {
                artifactEvents.add(event);
            }
        };
    }

    /** A {@link TaskUpdateEvent} is state-bearing iff its update is a {@link TaskStatusUpdateEvent}. */
    private static boolean isStatusUpdate(TaskUpdateEvent ue) {
        UpdateEvent update = ue.getUpdateEvent();
        return update instanceof TaskStatusUpdateEvent;
    }

    // ===== Internal: unified Task extraction =====

    /**
     * Extract the {@link Task} from a task-bearing event.
     *
     * @return the Task if the event is TaskEvent or a status-update TaskUpdateEvent, otherwise empty
     */
    private static Optional<Task> extractTask(ClientEvent event) {
        if (event instanceof TaskEvent te) return Optional.of(te.getTask());
        if (event instanceof TaskUpdateEvent ue) return Optional.of(ue.getTask());
        return Optional.empty();
    }

    /** Stream of Tasks extracted from the task-bearing (state) events queue. */
    private Stream<Task> taskStream() {
        return taskBearingEvents.stream()
                .map(A2aEventCollector::extractTask)
                .flatMap(Optional::stream);
    }

    // ===== Non-destructive find methods (return event elements) =====

    /**
     * Find the first task-state event ({@link TaskEvent} or status-update {@link TaskUpdateEvent})
     * with a terminal (final) state.
     *
     * <p>Non-destructive: the queue remains unmodified.</p>
     *
     * @return the first event whose task state {@link TaskState#isFinal()}, or empty
     */
    public Optional<ClientEvent> findTerminalEvent() {
        return taskBearingEvents.stream()
                .filter(e -> extractTask(e)
                        .map(t -> t.status().state().isFinal())
                        .orElse(false))
                .findFirst();
    }

    /**
     * Find the first task-state event with {@code INPUT_REQUIRED} state.
     *
     * <p>Non-destructive.</p>
     *
     * @return the first event whose task state is INPUT_REQUIRED, or empty
     */
    public Optional<ClientEvent> findInputRequiredEvent() {
        return taskBearingEvents.stream()
                .filter(e -> extractTask(e)
                        .map(t -> t.status().state() == TaskState.TASK_STATE_INPUT_REQUIRED)
                        .orElse(false))
                .findFirst();
    }

    /**
     * Find the first task-state event (any state).
     *
     * <p>Non-destructive. Returns the first {@link TaskEvent} or status-update
     * {@link TaskUpdateEvent}.</p>
     *
     * @return the first state-bearing event, or empty
     */
    public Optional<ClientEvent> findFirstTaskBearingEvent() {
        return taskBearingEvents.stream().findFirst();
    }

    /**
     * Find the first {@link MessageEvent} in the unified artifact queue.
     *
     * <p>Non-destructive.</p>
     */
    public Optional<MessageEvent> findFirstMessageEvent() {
        return artifactEvents.stream()
                .filter(MessageEvent.class::isInstance)
                .map(MessageEvent.class::cast)
                .findFirst();
    }

    /**
     * Find the first artifact update — a {@link TaskUpdateEvent} carrying a
     * {@link TaskArtifactUpdateEvent} — in the unified artifact queue.
     *
     * <p>Non-destructive.</p>
     */
    public Optional<TaskUpdateEvent> findFirstArtifactUpdate() {
        return artifactEvents.stream()
                .filter(TaskUpdateEvent.class::isInstance)
                .map(TaskUpdateEvent.class::cast)
                .findFirst();
    }

    /**
     * Convenience: extract the task ID from the first task-state event.
     *
     * <p>Non-destructive.</p>
     *
     * @return the task ID, or empty string if no task-state event received
     */
    public String findFirstTaskId() {
        return taskStream()
                .map(Task::id)
                .findFirst()
                .orElse("");
    }

    /**
     * Convenience: extract the context ID from the first task-state event.
     *
     * <p>Non-destructive. Used by {@link InteractionFlow} to continue a multi-turn
     * conversation: each subsequent round carries the previous round's contextId so
     * the agent sees the full conversation lineage (e.g. a follow-up turn that only
     * makes sense in the prior context).
     *
     * @return the context ID, or empty string if no task-state event received
     */
    public String findFirstContextId() {
        return taskStream()
                .map(Task::contextId)
                .findFirst()
                .orElse("");
    }

    /** Number of events collected so far. */
    public int eventCount() {
        return allEvents.size();
    }

    // ===== Awaitility-based await methods (non-destructive convenience wrappers) =====

    /**
     * Block until a task event with a terminal (final) state is observed.
     *
     * <p>Non-destructive.</p>
     *
     * @param timeoutMs maximum wait time in milliseconds
     * @return the terminal TaskState
     * @throws AssertionError if timeout expires without a terminal state
     */
    public TaskState awaitTerminalState(long timeoutMs) {
        return Awaitility.await("terminal task state")
                .atMost(timeoutMs, TimeUnit.MILLISECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> findTerminalEvent()
                                .flatMap(e -> extractTask(e).map(t -> t.status().state()))
                                .orElse(null),
                        Objects::nonNull);
    }

    /**
     * Block until a task event with any non-null state is observed.
     *
     * <p>Non-destructive.</p>
     *
     * @param timeoutMs maximum wait time in milliseconds
     * @return the first observed TaskState
     */
    public TaskState awaitAnyTaskState(long timeoutMs) {
        return Awaitility.await("any task state")
                .atMost(timeoutMs, TimeUnit.MILLISECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> findFirstTaskBearingEvent()
                                .flatMap(e -> extractTask(e).map(t -> t.status().state()))
                                .orElse(null),
                        Objects::nonNull);
    }

    /**
     * Block until the task reaches INPUT_REQUIRED state (interrupt).
     *
     * <p>Non-destructive. Returns {@code false} early if a terminal state
     * is observed instead (no more events expected).</p>
     *
     * @param timeoutMs maximum wait time in milliseconds
     * @return true if INPUT_REQUIRED was observed, false if a terminal state arrived first
     */
    public boolean awaitInputRequired(long timeoutMs) {
        // Predicate must accept Boolean.FALSE: a hard terminal (COMPLETED/FAILED/...) means
        // INPUT_REQUIRED will not arrive; returning false (not timing out) matches the javadoc.
        return Awaitility.await("INPUT_REQUIRED state")
                .atMost(timeoutMs, TimeUnit.MILLISECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> {
                    if (findInputRequiredEvent().isPresent()) {
                        return Boolean.TRUE;
                    }
                    if (findTerminalEvent().isPresent()) {
                        return Boolean.FALSE;
                    }
                    return null;
                }, Objects::nonNull);
    }

    /**
     * Block until at least one MessageEvent is received.
     *
     * <p>Non-destructive.</p>
     *
     * @param timeoutMs maximum wait time in milliseconds
     * @return the first MessageEvent
     */
    public MessageEvent awaitMessageEvent(long timeoutMs) {
        return Awaitility.await("message event")
                .atMost(timeoutMs, TimeUnit.MILLISECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> findFirstMessageEvent().orElse(null), Objects::nonNull);
    }

    // ===== Snapshot accessors (non-destructive, return defensive copies) =====

    /** Snapshot all collected events (defensive copy, queue unmodified). */
    public List<ClientEvent> snapshotAllEvents() {
        return new ArrayList<>(allEvents);
    }

    /**
     * Snapshot the unified artifact queue — {@link MessageEvent}s and artifact-update
     * ({@link TaskArtifactUpdateEvent}) {@link TaskUpdateEvent}s, in arrival order.
     *
     * <p>Non-destructive defensive copy.</p>
     */
    public List<ClientEvent> snapshotArtifactEvents() {
        return new ArrayList<>(artifactEvents);
    }

    // ===== Diagnostic helpers (non-destructive derivations) =====

    /**
     * Merge the text content of every artifact-update event into a single string,
     * reconstructing the agent's streamed output for diagnostics/debugging.
     *
     * <p>Walks the unified artifact queue, keeping only the {@link TaskUpdateEvent}s whose
     * update is a {@link TaskArtifactUpdateEvent}, and concatenates the {@link TextPart}
     * fragments of each {@link Artifact}. Streaming chunk semantics are honoured per
     * artifact so the result is the <em>final</em> text, not a duplicated chunk dump: an
     * update with {@code append = true} appends its text to that artifact's running buffer,
     * while {@code append = false/null} replaces it (servers commonly stream cumulative
     * content this way). Distinct artifacts (by {@code artifactId}) are emitted in
     * first-seen order, joined by a blank line.
     *
     * <p>Non-destructive. Intended for diagnostics — to read the unified text after a
     * streaming run rather than inspecting individual chunk events, e.g.
     * {@code System.out.println(collector.collectArtifactText())}.
     *
     * @return the merged artifact text, or an empty string if no text artifact was received
     */
    public String collectArtifactText() {
        // artifactId → reconstructed text, preserving first-seen order across artifacts.
        Map<String, StringBuilder> byArtifact = new LinkedHashMap<>();
        for (ClientEvent event : artifactEvents) {
            if (!(event instanceof TaskUpdateEvent tue)) {
                continue;
            }
            if (!(tue.getUpdateEvent() instanceof TaskArtifactUpdateEvent aue)) {
                continue;
            }
            Artifact artifact = aue.artifact();
            if (artifact == null) {
                continue;
            }
            String chunk = concatTextParts(artifact.parts());
            if (chunk.isEmpty()) {
                // Skip non-textual / empty updates so they don't clobber prior text in replace mode.
                continue;
            }
            StringBuilder buf = byArtifact.computeIfAbsent(artifactKey(artifact), k -> new StringBuilder());
            if (Boolean.TRUE.equals(aue.append())) {
                buf.append(chunk);
            } else {
                // replace: this update carries the artifact's full current content
                buf.setLength(0);
                buf.append(chunk);
            }
        }
        return String.join("\n\n", byArtifact.values().stream()
                .map(StringBuilder::toString)
                .toList());
    }

    /** Stable key for grouping an artifact's chunks: {@code artifactId}, falling back to name. */
    private static String artifactKey(Artifact artifact) {
        if (artifact.artifactId() != null && !artifact.artifactId().isBlank()) {
            return artifact.artifactId();
        }
        return (artifact.name() != null && !artifact.name().isBlank()) ? "name=" + artifact.name() : "anonymous";
    }

    /** Concatenate the textual payload of every {@link TextPart} in {@code parts}, in order. */
    private static String concatTextParts(List<Part<?>> parts) {
        StringBuilder sb = new StringBuilder();
        if (parts == null) {
            return "";
        }
        for (Part<?> part : parts) {
            if (part instanceof TextPart tp && tp.text() != null) {
                sb.append(tp.text());
            }
        }
        return sb.toString();
    }

    /** Clear all collected events from every queue. */
    public void clear() {
        allEvents.clear();
        taskBearingEvents.clear();
        artifactEvents.clear();
    }
}

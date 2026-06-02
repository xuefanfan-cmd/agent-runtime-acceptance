package com.huawei.ascend.sit.client;

import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.awaitility.Awaitility;

import java.util.ArrayList;
import java.util.List;
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
 *   <li>{@code taskBearingEvents} — {@link TaskEvent} and {@link TaskUpdateEvent}
 *       merged into a single queue, since both carry a {@link Task} and are
 *       processed with identical logic (extract task → check state → get ID).
 *       Merging avoids the dual-queue scan bug and reduces per-method complexity.</li>
 *   <li>{@code messageEvents} — {@link MessageEvent} instances (distinct processing)</li>
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
    private final ConcurrentLinkedQueue<MessageEvent> messageEvents = new ConcurrentLinkedQueue<>();

    /**
     * Create a consumer suitable for registering with {@code Client.sendMessage}.
     *
     * <p>Events are dispatched by type: {@link TaskEvent} and {@link TaskUpdateEvent}
     * share the {@code taskBearingEvents} queue; {@link MessageEvent} gets its own queue.</p>
     */
    public BiConsumer<ClientEvent, AgentCard> createConsumer() {
        return (event, card) -> {
            allEvents.add(event);
            if (event instanceof TaskEvent || event instanceof TaskUpdateEvent) {
                taskBearingEvents.add(event);
            } else if (event instanceof MessageEvent me) {
                messageEvents.add(me);
            }
        };
    }

    // ===== Internal: unified Task extraction =====

    /**
     * Extract the {@link Task} from a task-bearing event.
     *
     * @return the Task if the event is TaskEvent or TaskUpdateEvent, otherwise empty
     */
    private static Optional<Task> extractTask(ClientEvent event) {
        if (event instanceof TaskEvent te) return Optional.of(te.getTask());
        if (event instanceof TaskUpdateEvent ue) return Optional.of(ue.getTask());
        return Optional.empty();
    }

    /** Stream of Tasks extracted from the task-bearing events queue. */
    private Stream<Task> taskStream() {
        return taskBearingEvents.stream()
                .map(A2aEventCollector::extractTask)
                .flatMap(Optional::stream);
    }

    // ===== Non-destructive find methods (return event elements) =====

    /**
     * Find the first task-bearing event ({@link TaskEvent} or {@link TaskUpdateEvent})
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
     * Find the first task-bearing event with {@code INPUT_REQUIRED} state.
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
     * Find the first task-bearing event (any state).
     *
     * <p>Non-destructive.</p>
     *
     * @return the first TaskEvent or TaskUpdateEvent, or empty
     */
    public Optional<ClientEvent> findFirstTaskBearingEvent() {
        return taskBearingEvents.stream().findFirst();
    }

    /**
     * Find the first {@link MessageEvent}.
     *
     * <p>Non-destructive.</p>
     */
    public Optional<MessageEvent> findFirstMessageEvent() {
        return messageEvents.stream().findFirst();
    }

    /**
     * Convenience: extract the task ID from the first task-bearing event.
     *
     * <p>Non-destructive. Checks both {@link TaskEvent} and {@link TaskUpdateEvent}.</p>
     *
     * @return the task ID, or empty string if no task-bearing event received
     */
    public String findFirstTaskId() {
        return taskStream()
                .map(Task::id)
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
        return Awaitility.await("INPUT_REQUIRED state")
                .atMost(timeoutMs, TimeUnit.MILLISECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> {
                    if (findInputRequiredEvent().isPresent()) return true;
                    if (findTerminalEvent().isPresent()) return false;
                    return null;
                }, Boolean.TRUE::equals);
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

    /** Clear all collected events from every queue. */
    public void clear() {
        allEvents.clear();
        taskBearingEvents.clear();
        messageEvents.clear();
    }
}

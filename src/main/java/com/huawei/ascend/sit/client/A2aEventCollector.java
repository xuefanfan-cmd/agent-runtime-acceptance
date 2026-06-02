package com.huawei.ascend.sit.client;

import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.TaskState;
import org.awaitility.Awaitility;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Thread-safe async event collector for A2A client events.
 *
 * <p>Registers as a {@link BiConsumer} with the A2A SDK's {@code sendMessage}
 * and collects all events into internal {@link ConcurrentLinkedQueue}s.
 * Provides Awaitility-based await methods for synchronous-style assertions
 * on asynchronous events.</p>
 *
 * <h3>Method categories:</h3>
 * <ul>
 *   <li><b>Find methods</b> ({@code findXxx}) — non-destructive peek; return
 *       {@link Optional} of the matching event element. Queues are <em>unmodified</em>.
 *       Used by Awaitility polling and callers that only need to <em>observe</em> state.</li>
 *   <li><b>Await methods</b> ({@code awaitXxx}) — blocking convenience wrappers over
 *       find methods; return extracted primitives (TaskState, boolean) for assertion ease.</li>
 *   <li><b>Snapshot methods</b> ({@code snapshotXxx}) — non-destructive; return a
 *       defensive copy of all elements. Queues are <em>unmodified</em>.</li>
 * </ul>
 *
 * <h3>Why no {@code pollInSameThread()}?</h3>
 * <p>All internal collections are {@link ConcurrentLinkedQueue} — inherently thread-safe.
 * Awaitility's default condition executor runs the polling callable in a separate thread,
 * which is both safe and avoids blocking the test thread during polling intervals.</p>
 *
 * <h3>Usage pattern:</h3>
 * <pre>{@code
 * A2aEventCollector collector = new A2aEventCollector();
 * a2aClient.sendMessage(message, List.of(collector.createConsumer()), errorHandler);
 *
 * // Block until a terminal state is observed
 * TaskState finalState = collector.awaitTerminalState(30_000);
 * assertThat(finalState).isEqualTo(TaskState.TASK_STATE_COMPLETED);
 *
 * // Inspect the event directly (non-destructive)
 * Optional<TaskEvent> terminal = collector.findTerminalTaskEvent();
 *
 * }</pre>
 */
public class A2aEventCollector {

    private final ConcurrentLinkedQueue<ClientEvent> allEvents = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<TaskEvent> taskEvents = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<TaskUpdateEvent> updateEvents = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<MessageEvent> messageEvents = new ConcurrentLinkedQueue<>();

    /**
     * Create a consumer suitable for registering with {@code Client.sendMessage}.
     *
     * <p>Events are dispatched by type into separate typed queues
     * for convenient filtering during assertions.</p>
     */
    public BiConsumer<ClientEvent, AgentCard> createConsumer() {
        return (event, card) -> {
            allEvents.add(event);
            if (event instanceof TaskEvent te) taskEvents.add(te);
            else if (event instanceof TaskUpdateEvent ue) updateEvents.add(ue);
            else if (event instanceof MessageEvent me) messageEvents.add(me);
        };
    }

    // ===== Non-destructive find methods (return event elements) =====

    /**
     * Find the first {@link TaskEvent} with a terminal (final) state.
     *
     * <p>Non-destructive: scans via {@code stream().filter().findFirst()} —
     * the queue remains unmodified.</p>
     *
     * @return the first TaskEvent whose task state {@link TaskState#isFinal()},
     *         or empty if none found
     */
    public Optional<TaskEvent> findTerminalTaskEvent() {
        return taskEvents.stream()
                .filter(te -> te.getTask().status().state().isFinal())
                .findFirst();
    }

    /**
     * Find the first {@link TaskUpdateEvent} with a terminal (final) state.
     *
     * <p>Non-destructive: scans via {@code stream().filter().findFirst()}.</p>
     *
     * @return the first TaskUpdateEvent whose task state is final, or empty if none found
     */
    public Optional<TaskUpdateEvent> findTerminalUpdateEvent() {
        return updateEvents.stream()
                .filter(ue -> ue.getTask().status().state().isFinal())
                .findFirst();
    }

    /**
     * Find the first {@link TaskUpdateEvent} with {@code INPUT_REQUIRED} state.
     *
     * <p>Non-destructive: scans without consuming. In the A2A protocol,
     * INPUT_REQUIRED is only signaled via {@link TaskUpdateEvent}, never
     * via {@link TaskEvent}.</p>
     *
     * @return the first TaskUpdateEvent whose state is INPUT_REQUIRED, or empty if none found
     */
    public Optional<TaskUpdateEvent> findInputRequiredEvent() {
        return updateEvents.stream()
                .filter(ue -> ue.getTask().status().state() == TaskState.TASK_STATE_INPUT_REQUIRED)
                .findFirst();
    }

    /**
     * Find the first {@link TaskEvent} (any state).
     *
     * <p>Non-destructive: returns the head element without consuming.</p>
     *
     * @return the first TaskEvent, or empty if queue is empty
     */
    public Optional<TaskEvent> findFirstTaskEvent() {
        return taskEvents.stream().findFirst();
    }

    /**
     * Find the first {@link TaskUpdateEvent} (any state).
     *
     * <p>Non-destructive.</p>
     *
     * @return the first TaskUpdateEvent, or empty if queue is empty
     */
    public Optional<TaskUpdateEvent> findFirstUpdateEvent() {
        return updateEvents.stream().findFirst();
    }

    /**
     * Find the first {@link MessageEvent}.
     *
     * <p>Non-destructive.</p>
     *
     * @return the first MessageEvent, or empty if queue is empty
     */
    public Optional<MessageEvent> findFirstMessageEvent() {
        return messageEvents.stream().findFirst();
    }

    /**
     * Convenience: extract the task ID from the first TaskEvent.
     *
     * <p>Non-destructive.</p>
     *
     * @return the task ID, or empty string if no TaskEvent received
     */
    public String findFirstTaskId() {
        return findFirstTaskEvent()
                .map(te -> te.getTask().id())
                .orElse("");
    }

    /**
     * Number of events collected so far.
     */
    public int eventCount() {
        return allEvents.size();
    }

    // ===== Awaitility-based await methods (non-destructive convenience wrappers) =====

    /**
     * Block until a task event with a terminal (final) state is observed.
     *
     * <p>Non-destructive: composes {@link #findTerminalTaskEvent()} and
     * {@link #findTerminalUpdateEvent()} to search both queues.</p>
     *
     * @param timeoutMs maximum wait time in milliseconds
     * @return the terminal TaskState
     * @throws AssertionError if timeout expires without a terminal state
     */
    public TaskState awaitTerminalState(long timeoutMs) {
        return Awaitility.await("terminal task state")
                .atMost(timeoutMs, TimeUnit.MILLISECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> findTerminalTaskEvent()
                                .<TaskState>map(te -> te.getTask().status().state())
                                .orElseGet(() -> findTerminalUpdateEvent()
                                        .map(ue -> ue.getTask().status().state())
                                        .orElse(null)),
                        Objects::nonNull);
    }

    /**
     * Block until a task event with any non-null state is observed.
     *
     * <p>Non-destructive: composes {@link #findFirstTaskEvent()} and
     * {@link #findFirstUpdateEvent()}.</p>
     *
     * @param timeoutMs maximum wait time in milliseconds
     * @return the first observed TaskState
     */
    public TaskState awaitAnyTaskState(long timeoutMs) {
        return Awaitility.await("any task state")
                .atMost(timeoutMs, TimeUnit.MILLISECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> findFirstTaskEvent()
                                .<TaskState>map(te -> te.getTask().status().state())
                                .orElseGet(() -> findFirstUpdateEvent()
                                        .map(ue -> ue.getTask().status().state())
                                        .orElse(null)),
                        Objects::nonNull);
    }

    /**
     * Block until the task reaches INPUT_REQUIRED state (interrupt).
     *
     * <p>Non-destructive: checks {@link #findInputRequiredEvent()} on the
     * update-events queue (INPUT_REQUIRED is only signaled via TaskUpdateEvent).
     * Returns {@code false} early if a terminal state is observed instead.</p>
     *
     * @param timeoutMs maximum wait time in milliseconds
     * @return true if INPUT_REQUIRED was observed, false if a different terminal state arrived first
     */
    public boolean awaitInputRequired(long timeoutMs) {
        return Awaitility.await("INPUT_REQUIRED state")
                .atMost(timeoutMs, TimeUnit.MILLISECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> {
                    if (findInputRequiredEvent().isPresent()) return true;
                    boolean hasTerminal = findTerminalTaskEvent().isPresent()
                            || findTerminalUpdateEvent().isPresent();
                    return hasTerminal ? false : null;
                }, Boolean.TRUE::equals);
    }

    /**
     * Block until at least one MessageEvent is received.
     *
     * <p>Non-destructive: delegates to {@link #findFirstMessageEvent()}.</p>
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

    /**
     * Snapshot all collected events.
     *
     * <p>Non-destructive: returns a defensive copy; internal queues are unmodified.</p>
     *
     * @return unmodifiable copy of all events collected so far
     */
    public List<ClientEvent> snapshotAllEvents() {
        return new ArrayList<>(allEvents);
    }

    /** Clear all collected events from every queue. */
    public void clear() {
        allEvents.clear();
        taskEvents.clear();
        updateEvents.clear();
        messageEvents.clear();
    }

}

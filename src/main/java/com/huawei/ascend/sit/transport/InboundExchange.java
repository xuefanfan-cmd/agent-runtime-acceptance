package com.huawei.ascend.sit.transport;

import org.a2aproject.sdk.spec.TaskState;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Live, awaitable holder of one round's inbound {@link InboundEvent} stream + derived views.
 * Families use it by composition: {@link #add(InboundEvent)} events as native frames arrive,
 * {@link #markStreamEnd()} when the stream is exhausted. All buffering, Awaitility-based awaiting,
 * and derivation (taskId / contextId / stateTrajectory / answerText) live here, identical for every
 * protocol — adapters only inject events.
 */
public final class InboundExchange {

    private final ConcurrentLinkedQueue<InboundEvent> events = new ConcurrentLinkedQueue<>();
    private volatile boolean streamEnded;

    /** Adapters/wires call this as native frames arrive. Thread-safe. */
    public void add(InboundEvent e) {
        events.add(Objects.requireNonNull(e));
    }

    /** Signal that no more events will arrive. */
    public void markStreamEnd() {
        this.streamEnded = true;
    }

    /** Defensive snapshot of all events so far, in arrival order. */
    public List<InboundEvent> events() {
        return new ArrayList<>(events);
    }

    public int eventCount() {
        return events.size();
    }

    /** Block until a STATE event with a final state appears; return it. */
    public TaskState awaitTerminalState(long timeoutMs) {
        try {
            return Awaitility.await("terminal state")
                    .atMost(timeoutMs, TimeUnit.MILLISECONDS)
                    .pollInterval(200, TimeUnit.MILLISECONDS)
                    .until(this::terminalState, Objects::nonNull);
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("No terminal state within " + timeoutMs + "ms", e);
        }
    }

    /** Block until INPUT_REQUIRED; return false early if a terminal state arrives first. */
    public boolean awaitInputRequired(long timeoutMs) {
        try {
            // Wait for a definitive answer — INPUT_REQUIRED *or* a terminal — whichever arrives first.
            // A terminal (e.g. the agent completed instead of asking for input) precludes a later
            // INPUT_REQUIRED, so the moment one appears we can stop waiting and resolve.
            Awaitility.await("INPUT_REQUIRED or terminal state")
                    .atMost(timeoutMs, TimeUnit.MILLISECONDS)
                    .pollInterval(200, TimeUnit.MILLISECONDS)
                    .until(() -> anyState(t -> t == TaskState.TASK_STATE_INPUT_REQUIRED)
                            || terminalState() != null);
            // INPUT_REQUIRED wins if it was seen at all; otherwise the definitive answer was a terminal.
            return anyState(t -> t == TaskState.TASK_STATE_INPUT_REQUIRED);
        } catch (ConditionTimeoutException e) {
            return false;
        }
    }

    /** Block until any STATE event appears; return its state. */
    public TaskState awaitAnyState(long timeoutMs) {
        try {
            return Awaitility.await("any state")
                    .atMost(timeoutMs, TimeUnit.MILLISECONDS)
                    .pollInterval(200, TimeUnit.MILLISECONDS)
                    .until(this::firstState, Objects::nonNull);
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("No state observed within " + timeoutMs + "ms", e);
        }
    }

    /** First STATE event's taskId, or {@code ""}. */
    public String taskId() {
        return events.stream()
                .filter(e -> e.kind() == InboundEvent.Kind.STATE)
                .map(InboundEvent::taskId)
                .filter(id -> !id.isEmpty())
                .findFirst().orElse("");
    }

    /** First STATE event's contextId, or {@code ""}. */
    public String contextId() {
        return events.stream()
                .filter(e -> e.kind() == InboundEvent.Kind.STATE)
                .map(InboundEvent::contextId)
                .filter(id -> !id.isEmpty())
                .findFirst().orElse("");
    }

    /** STATE events' states, in arrival order. */
    public List<TaskState> stateTrajectory() {
        List<TaskState> out = new ArrayList<>();
        for (InboundEvent e : events) {
            if (e.kind() == InboundEvent.Kind.STATE) {
                out.add(e.state());
            }
        }
        return out;
    }

    /**
     * Ordered concatenation of {@link InboundEvent.Kind#ANSWER} events' text — strictly the agent's
     * final processed result(s) ({@code payload.output}). Deliberately excludes streaming output
     * tokens, reasoning, and plain content: a turn that streams only {@code llm_output} and never
     * emits a discrete {@code answer} yields {@code ""} here, which is the debug signal that no
     * discrete answer was produced. Use {@link #llmOutputText()} / {@link #reasoningText()} for the
     * other text-bearing subtypes.
     */
    public String answerText() {
        return concatKind(InboundEvent.Kind.ANSWER);
    }

    /** Ordered concatenation of {@link InboundEvent.Kind#LLM_OUTPUT} events' text. */
    public String llmOutputText() {
        return concatKind(InboundEvent.Kind.LLM_OUTPUT);
    }

    /** Ordered concatenation of {@link InboundEvent.Kind#LLM_REASONING} events' text. */
    public String reasoningText() {
        return concatKind(InboundEvent.Kind.LLM_REASONING);
    }

    /**
     * Everything the model generated this round — the ordered concatenation of
     * {@link InboundEvent.Kind#ANSWER}/{@code LLM_OUTPUT}/{@code LLM_REASONING}/{@code CONTENT}/
     * {@code INTERACTION} text.
     * Use this for rounds that legitimately carry <em>no</em> discrete {@code ANSWER}: e.g. an
     * {@code INPUT_REQUIRED} round whose reply is the agent's clarifying question, streamed as
     * {@code llm_output} (or reasoning) while the model is still mid-thought — there is no final
     * processed result, but the round was not silent. {@link #answerText()} stays strict for the
     * discrete-final-answer case; this view is the "did the agent reply at all" superset.
     */
    public String generatedText() {
        StringBuilder sb = new StringBuilder();
        for (InboundEvent e : events) {
            InboundEvent.Kind k = e.kind();
            if (k == InboundEvent.Kind.ANSWER || k == InboundEvent.Kind.LLM_OUTPUT
                    || k == InboundEvent.Kind.LLM_REASONING || k == InboundEvent.Kind.CONTENT
                    || k == InboundEvent.Kind.INTERACTION) {
                sb.append(e.text());
            }
        }
        return sb.toString();
    }

    private String concatKind(InboundEvent.Kind kind) {
        StringBuilder sb = new StringBuilder();
        for (InboundEvent e : events) {
            if (e.kind() == kind) {
                sb.append(e.text());
            }
        }
        return sb.toString();
    }

    private TaskState terminalState() {
        TaskState terminal = null;
        for (InboundEvent e : events) {
            if (e.kind() == InboundEvent.Kind.STATE && e.state() != null && e.state().isFinal()) {
                terminal = e.state();
            }
        }
        return terminal;
    }

    private TaskState firstState() {
        return events.stream()
                .filter(e -> e.kind() == InboundEvent.Kind.STATE)
                .map(InboundEvent::state)
                .findFirst().orElse(null);
    }

    private boolean anyState(Predicate<TaskState> test) {
        return events.stream()
                .anyMatch(e -> e.kind() == InboundEvent.Kind.STATE && test.test(e.state()));
    }
}

package com.huawei.ascend.sit.transport;

import org.a2aproject.sdk.spec.TaskState;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Derives a terminal {@link TaskState} from a REST inbound event stream, where state is not
 * carried natively. Used by the REST Interaction adapter at stream-end to synthesise one
 * {@link InboundEvent#state(TaskState)} event.
 *
 * <p>Rules: an {@link InboundEvent.Kind#ERROR} event ⇒ {@code FAILED}; otherwise an
 * {@link InboundEvent.Kind#INTERACTION} event (the REST SUT's {@code __interaction__} envelope)
 * ⇒ {@code INPUT_REQUIRED}; otherwise a clean stream-end ⇒ {@code COMPLETED}. The default marker
 * matches {@link InboundEvent.Kind#INTERACTION}; supply a custom predicate only to override that.
 */
public final class SseStateClassifier {

    private final Predicate<InboundEvent> inputRequiredMarker;

    public SseStateClassifier() {
        this(e -> e.kind() == InboundEvent.Kind.INTERACTION);
    }

    public SseStateClassifier(Predicate<InboundEvent> inputRequiredMarker) {
        this.inputRequiredMarker = inputRequiredMarker;
    }

    /**
     * Derive the terminal state from the accumulated events.
     *
     * @param events       the REST stream so far
     * @param streamEnded  true if the SSE stream has completed
     * @return the terminal state, or empty if not yet determinable
     */
    public Optional<TaskState> derive(List<InboundEvent> events, boolean streamEnded) {
        for (InboundEvent e : events) {
            if (e.kind() == InboundEvent.Kind.ERROR) {
                return Optional.of(TaskState.TASK_STATE_FAILED);
            }
            if (inputRequiredMarker.test(e)) {
                return Optional.of(TaskState.TASK_STATE_INPUT_REQUIRED);
            }
        }
        if (streamEnded) {
            return Optional.of(TaskState.TASK_STATE_COMPLETED);
        }
        return Optional.empty();
    }

    /** Convenience static using the default (no-marker) classifier. */
    public static Optional<TaskState> deriveTerminal(List<InboundEvent> events, boolean streamEnded) {
        return new SseStateClassifier().derive(events, streamEnded);
    }
}

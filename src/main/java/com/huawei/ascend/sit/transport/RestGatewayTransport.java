package com.huawei.ascend.sit.transport;

import org.a2aproject.sdk.spec.TaskState;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * The EDPA high-code-adapter wire — the concrete leaf of {@link GatewayStreamingTransport} for the
 * adapter endpoint. A peer of {@link RestQueryTransport} that differs only in endpoint URL, request
 * body, and SSE extraction: this leaf classifies via {@link GatewayEventMapping} (the adapter projects
 * {@code custom_rsp_data}/{@code think_chunk}), while the gateway leaf uses
 * {@link VersatileEventMapping}; see the base for the wire loop.
 *
 * <p>The round's terminal state is answer-gated (see {@link #deriveTerminalState}): the real plan-agent
 * gateway wire has no {@code end} frame, so {@code COMPLETED} is derived from a discrete
 * {@code answer} frame, not from the stream ending.
 */
public final class RestGatewayTransport extends GatewayStreamingTransport {

    public RestGatewayTransport(RestIo io, URI endpoint) {
        super(io, endpoint);
    }

    @Override
    protected InboundEvent map(String line) {
        return GatewayEventMapping.toEvent(line);
    }

    /**
     * The gateway's terminal signal is the discrete {@code answer} frame (→ {@link InboundEvent.Kind#ANSWER}
     * via {@link GatewayEventMapping}/{@link LlmPayload}), NOT the stream ending (the real plan-agent
     * gateway wire has no {@code end} frame). A round with an {@code ERROR} ⇒ {@code FAILED}; otherwise a
     * round with an {@code ANSWER} ⇒ {@code COMPLETED}; otherwise ⇒ {@code INPUT_REQUIRED} (an
     * intermediate / manual-step round, interrupted — the same answer-gated contract as
     * {@link RestVersatileTransport}, but driven off the {@code ANSWER} kind directly since the gateway
     * keeps the finer {@link LlmPayload} classification).
     */
    @Override
    protected Optional<TaskState> deriveTerminalState(List<InboundEvent> events) {
        boolean failed = events.stream().anyMatch(e -> e.kind() == InboundEvent.Kind.ERROR);
        if (failed) {
            return Optional.of(TaskState.TASK_STATE_FAILED);
        }
        boolean answered = events.stream().anyMatch(e -> e.kind() == InboundEvent.Kind.ANSWER);
        return Optional.of(answered
                ? TaskState.TASK_STATE_COMPLETED
                : TaskState.TASK_STATE_INPUT_REQUIRED);
    }
}

package com.huawei.ascend.sit.transport;

import org.a2aproject.sdk.spec.TaskState;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * The low-code-gateway wire — the concrete leaf of {@link GatewayStreamingTransport} for the gateway
 * conversation endpoint ({@code /v1/{pid}/agents/{aid}/conversations/{cid}}). The deployed versatile
 * gateway streams bare SSE frames — each line is a JSON object with an {@code event} field and a
 * nested {@code data} object carrying the typed envelope ({@code type}/{@code index}/{@code payload})
 * — with no {@code custom_rsp_data} wrapper and no {@code think_chunk} rewrite. That is a different
 * shape than the high-code adapter, so this leaf classifies via its own {@link VersatileEventMapping}
 * (not the adapter's {@link GatewayEventMapping}, which silently dropped every {@code llm_reasoning}/
 * {@code llm_output}/{@code llm_usage} frame on this wire). Every frame is surfaced as CONTENT
 * (including {@code end}); the round's terminal state is derived from the discrete {@code answer}
 * frame, not from {@code end} — see {@link #deriveTerminalState}. Wire-log comes from the base. See
 * the base for the wire loop.
 */
public final class RestVersatileTransport extends GatewayStreamingTransport {

    public RestVersatileTransport(RestIo io, URI endpoint) {
        super(io, endpoint);
    }

    @Override
    protected InboundEvent map(String line) {
        return VersatileEventMapping.toEvent(line);
    }

    /**
     * The gateway's terminal signal is the discrete {@code answer} frame
     * ({@code event="answer"} + {@code data.type="answer"}), NOT the stream ending. A round that
     * emitted one is {@code COMPLETED}; every other round (intermediate / manual-step) is
     * {@code INPUT_REQUIRED}. This lets {@code event="end"} be surfaced as plain CONTENT instead of
     * being consumed into the terminal state.
     */
    @Override
    protected Optional<TaskState> deriveTerminalState(List<InboundEvent> events) {
        boolean answered = events.stream().anyMatch(e -> VersatileEventMapping.isAnswerFrame(e.raw()));
        return Optional.of(answered
                ? TaskState.TASK_STATE_COMPLETED
                : TaskState.TASK_STATE_INPUT_REQUIRED);
    }
}

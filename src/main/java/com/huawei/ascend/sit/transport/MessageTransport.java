package com.huawei.ascend.sit.transport;

/**
 * Outbound transport SPI for the {@code InteractionFlow} family (business logic lives in
 * {@code client/}). A transport sends one {@link OutboundMessage} and returns a live
 * {@link InboundExchange} that the driving loop awaits on. The flow is agnostic of the wire
 * shape — {@code A2aStreamingTransport} (A2A-SSE), {@code A2aSyncTransport} (A2A-sync),
 * {@code RestQueryTransport} (REST) — and the await semantics (terminal / input-required /
 * any-state) are defined by the exchange, not the transport.
 */
public interface MessageTransport {

    InboundExchange send(OutboundMessage message);

    /**
     * Resolve any transport-specific request identity onto the outbound BEFORE send, so the wire-log's
     * paste-ready request reflects the exact bytes that will go out — not a representative placeholder
     * invented by the transport-package renderer, which sees only the {@link OutboundMessage}.
     *
     * <p>Default: return the message unchanged. {@code RestQueryTransport} overrides this to stamp the
     * resolved {@code conversation_id} onto {@link OutboundMessage#contextId()}. That id is otherwise
     * minted lazily inside {@code send}, AFTER the wire-log renderer runs, so without this hook the
     * logged request body carried a freshly-invented UUID unrelated to what was actually sent (and to
     * the server's echoed response) — the logged send/response conversation_id never matched.
     *
     * <p>Called once per round by {@code InteractionFlow.executeRound} after building the outbound and
     * before rendering the wire request / sending. Implementations must be idempotent.
     *
     * @param message the outbound the flow is about to send
     * @return the same message, or a copy with transport identity stamped on
     */
    default OutboundMessage resolveOutbound(OutboundMessage message) {
        return message;
    }
}

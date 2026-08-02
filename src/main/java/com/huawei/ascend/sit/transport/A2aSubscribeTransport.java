package com.huawei.ascend.sit.transport;

/**
 * The A2A-SSE {@code SubscribeToTask} {@link MessageTransport}: observe an <em>existing, non-terminal</em>
 * task by id. A thin adapter over the shared {@link A2aStreamingWire} subscribe capability: it requires
 * an {@code OutboundMessage.taskId} (subscribe does not send text — it attaches to a task a prior round
 * created), drives the wire with a consumer that maps each {@code ClientEvent} via {@link A2aEventMapping}
 * into the exchange, and returns the exchange.
 *
 * <p>Subscribe delivers an initial task <em>snapshot</em> frame, then status/artifact updates until the
 * task reaches a terminal state — the same {@code ClientEvent} stream shape as
 * {@link A2aStreamingTransport}/{@code SendStreamingMessage}, so {@link A2aEventMapping} is reused
 * unchanged. No {@code markStreamEnd()} is called: like streaming send, the exchange is settled by the
 * terminal {@code STATE} event the server pushes when the observed task goes final.
 */
public final class A2aSubscribeTransport implements MessageTransport {

    private final A2aStreamingWire wire;

    public A2aSubscribeTransport(A2aStreamingWire wire) {
        this.wire = wire;
    }

    @Override
    public InboundExchange send(OutboundMessage message) {
        if (message.taskId() == null || message.taskId().isBlank()) {
            throw new IllegalStateException(
                    "A2A_SUBSCRIBE requires a taskId — subscribe observes an existing task, it does not send text");
        }
        InboundExchange exchange = new InboundExchange();
        wire.subscribe(message.taskId(), (clientEvent, card) -> {
            for (InboundEvent e : A2aEventMapping.toEventList(clientEvent)) {
                exchange.add(e);
            }
        });
        return exchange;
    }
}

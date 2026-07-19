package com.huawei.ascend.sit.transport;

import org.a2aproject.sdk.spec.Message;

/**
 * The A2A-SSE {@code MessageTransport}: a thin family adapter over the shared
 * {@link A2aStreamingWire}. It creates a fresh {@link InboundExchange}, unpacks the
 * {@link OutboundMessage} into the wire's primitives (text + continuation hints), drives the
 * wire with a consumer that maps each {@code ClientEvent} via {@link A2aEventMapping} into the
 * exchange, and returns the exchange.
 */
public final class A2aStreamingTransport implements MessageTransport {

    private final A2aStreamingWire wire;

    public A2aStreamingTransport(A2aStreamingWire wire) {
        this.wire = wire;
    }

    @Override
    public InboundExchange send(OutboundMessage message) {
        InboundExchange exchange = new InboundExchange();
        Message sdkMessage = A2aStreamingWire.buildMessage(
                message.text(), message.taskId(), message.contextId());
        wire.send(sdkMessage, message.metadata(), (clientEvent, card) -> {
            // An artifact/message event may carry several parts → several events (one per typed
            // envelope or plain-text chunk); STATE events yield one. Add them all in order.
            for (InboundEvent e : A2aEventMapping.toEventList(clientEvent)) {
                exchange.add(e);
            }
        });
        return exchange;
    }
}

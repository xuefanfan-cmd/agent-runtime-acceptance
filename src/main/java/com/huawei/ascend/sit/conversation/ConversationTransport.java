package com.huawei.ascend.sit.conversation;

/**
 * Outbound transport SPI for the {@code Conversation} family. A transport POSTs one
 * {@link ConversationOutbound} to the SUT, feeds the parsed SSE frames into {@code collector}, and
 * marks the stream ended. The family's driving loop ({@code Turn}) is agnostic of the wire shape —
 * {@link RestVersatileTransport} (low-code gateway) today; REST/A2A-direct variants later.
 *
 * <p>Stream-end bookkeeping lives here (not in the caller): the transport owns the full
 * POST → feed → mark-end cycle so every implementation honours the same await contract.
 */
public interface ConversationTransport {

    /**
     * POST {@code out} and feed the reply into {@code collector}, marking the stream ended
     * (success or failure) before returning.
     */
    void send(ConversationOutbound out, ConversationEventCollector collector);
}

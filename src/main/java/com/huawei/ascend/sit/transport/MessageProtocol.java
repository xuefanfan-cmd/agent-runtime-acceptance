package com.huawei.ascend.sit.transport;

import java.util.Locale;
import java.util.Set;

/**
 * The wire protocols a flow can use to talk to the SUT. Flows select one via {@link ProtocolResolver}
 * (sys-prop/env {@code MESSAGE_PROTOCOL}, or a builder override); only the
 * {@link #isImplemented() implemented} values are selectable today.
 *
 * <p>Implemented:
 * <ul>
 *   <li>{@link #A2A_STREAM} — JSON-RPC {@code SendStreamingMessage} SSE (InteractionFlow default).</li>
 *   <li>{@link #A2A_SYNC} — JSON-RPC {@code message/send} blocking.</li>
 *   <li>{@link #REST_QUERY} — REST {@code POST /v1/query} with {@code stream:true} (SSE).</li>
 *   <li>{@link #REST_QUERY_SYNC} — REST {@code POST /v1/query} with {@code stream:false} (JSON).</li>
 *   <li>{@link #REST_VERSATILE} — low-code gateway
 *       {@code /v1/{pid}/agents/{aid}/conversations/{cid}} (Conversation default).</li>
 *   <li>{@link #REST_REACTIVE} — REST {@code POST /v1/query/reactive} with {@code stream:true} (SSE, WebFlux).</li>
 *   <li>{@link #REST_REACTIVE_SYNC} — REST {@code POST /v1/query/reactive} with {@code stream:false} (JSON, WebFlux).</li>
 *   <li>{@link #REST_GATEWAY} — high-code adapter EDPA wire (MessageTransport peer of
 *       {@link #REST_QUERY}; Conversation-driven via the adapter, endpoint URL per the adapter spec).</li>
 * </ul>
 * Placeholders (selectable once their transport adapter lands): {@link #DIRECT_A2A},
 * {@link #DIRECT_REST}.
 */
public enum MessageProtocol {
    A2A_STREAM,
    A2A_SYNC,
    REST_QUERY,
    REST_QUERY_SYNC,
    REST_VERSATILE,
    REST_REACTIVE,
    REST_REACTIVE_SYNC,
    REST_GATEWAY,
    DIRECT_A2A,
    DIRECT_REST;

    private static final Set<MessageProtocol> IMPLEMENTED = Set.of(
            A2A_STREAM, A2A_SYNC, REST_QUERY, REST_QUERY_SYNC, REST_VERSATILE,
            REST_REACTIVE, REST_REACTIVE_SYNC, REST_GATEWAY);

    /** True once the transport adapter for this protocol exists. */
    public boolean isImplemented() {
        return IMPLEMENTED.contains(this);
    }

    /** Throw if this protocol has no transport yet (called by selection sites). */
    public MessageProtocol requireImplemented() {
        if (!isImplemented()) {
            throw new IllegalStateException("MessageProtocol not implemented yet: " + this);
        }
        return this;
    }

    /** Case-insensitive value-of for sys-prop/env strings (e.g. {@code "rest_query"}). */
    public static MessageProtocol parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("protocol value is null");
        }
        return MessageProtocol.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}

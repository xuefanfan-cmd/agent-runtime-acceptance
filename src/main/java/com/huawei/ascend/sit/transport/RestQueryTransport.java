package com.huawei.ascend.sit.transport;

import com.huawei.ascend.sit.utils.JsonUtils;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The REST {@code /v1/query} {@code MessageTransport}. One class branches on stream mode:
 * streaming ({@code stream:true}) POSTs and consumes the SSE reply line-by-line into CONTENT events,
 * then synthesises a terminal STATE via {@link SseStateClassifier}; non-streaming ({@code stream:false})
 * POSTs and decodes the single JSON into a CONTENT + COMPLETED pair.
 */
public final class RestQueryTransport implements MessageTransport {

    private final RestIo io;
    private final URI endpoint;
    private final boolean stream;
    /**
     * Lazily minted client-side conversation id, reused across every round of this flow so a
     * multi-turn REST conversation stays coherent. The {@code /v1/query} server does not assign one
     * (unlike A2A, whose server allocates a contextId on the first round) — it requires a valid
     * client value, so when the flow provides none we mint a UUID and remember it. One transport
     * instance serves one flow (= one conversation), so this is the conversation's identity, not
     * arbitrary mutable state.
     */
    private String conversationId;

    /** Streaming transport (REST_QUERY). */
    public RestQueryTransport(RestIo io, URI endpoint) {
        this(io, endpoint, true);
    }

    /** Full constructor: {@code stream=false} ⇒ REST_QUERY_SYNC. */
    public RestQueryTransport(RestIo io, URI endpoint, boolean stream) {
        this.io = io;
        this.endpoint = endpoint;
        this.stream = stream;
    }

    @Override
    public OutboundMessage resolveOutbound(OutboundMessage message) {
        // Stamp the resolved conversation_id onto the outbound BEFORE send so the wire-log renderer
        // (which runs off the OutboundMessage alone, before send) emits the exact id that will go out.
        // Without this, a fresh REST round (contextId blank) logged a representative UUID unrelated to
        // the id actually sent (and to the server's echoed response) — the logged send/response
        // conversation_id never matched. A pre-rendered body (the Conversation adapter path) already
        // bakes conversation_id in, so it is left untouched.
        if (message.body() != null) {
            return message;
        }
        String resolved = resolveConversationId(message.contextId());
        if ((message.contextId() == null || message.contextId().isBlank())
                && resolved != null && !resolved.isBlank()) {
            return message.withContextId(resolved);
        }
        return message;
    }

    @Override
    public InboundExchange send(OutboundMessage message) {
        InboundExchange ex = new InboundExchange();
        String body = body(message);
        if (stream) {
            io.postSse(endpoint, body, line -> {
                InboundEvent e = RestEventMapping.toEvent(line);
                if (e != null) ex.add(e);
            });
            SseStateClassifier.deriveTerminal(ex.events(), true).ifPresent(s ->
                    ex.add(InboundEvent.state(s)));
        } else {
            String json = io.postJson(endpoint, body);
            for (InboundEvent e : RestEventMapping.fromJson(json)) {
                ex.add(e);
            }
        }
        ex.markStreamEnd();
        return ex;
    }

    private String body(OutboundMessage m) {
        // Pre-rendered EDPA body (Conversation direct adapter bypasses the gateway, so it rebuilds
        // the full EDPA REST envelope itself). Post verbatim — no reshaping.
        if (m.body() != null) {
            return m.body();
        }
        String cid = resolveConversationId(m.contextId());
        // Serialize via Jackson so both `message` and `conversation_id` are RFC 8259-correctly
        // escaped (control chars incl. newline/tab, quotes, backslashes). LinkedHashMap preserves
        // the canonical key order.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversation_id", cid);
        body.put("message", m.text() == null ? "" : m.text());
        body.put("stream", stream);
        return JsonUtils.toJsonCompact(body);
    }

    /**
     * Resolve the conversation id for this send: honour an explicit value from the flow (a pinned or
     * server-threaded context id); otherwise mint a UUID on first use and reuse it for every
     * subsequent send so one flow's rounds share one conversation.
     */
    private String resolveConversationId(String provided) {
        if (provided != null && !provided.isBlank()) {
            return provided;
        }
        if (conversationId == null) {
            conversationId = UUID.randomUUID().toString();
        }
        return conversationId;
    }
}

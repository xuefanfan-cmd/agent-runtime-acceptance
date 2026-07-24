package com.huawei.ascend.sit.transport;

import com.huawei.ascend.sit.utils.JsonUtils;
import org.a2aproject.sdk.spec.TaskState;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared streaming-REST transport for the two EDPA-style wires — the high-code adapter
 * ({@link RestGatewayTransport}) and the low-code gateway ({@link RestVersatileTransport}). Both stream
 * an SSE reply, so they share everything but their endpoint + body (caller-supplied) and their SSE
 * classifier (per-leaf, see {@link #map(String)}): this base owns the wire loop (POST the body,
 * classify each {@code data:} line via the leaf's mapping, synthesise a terminal STATE through
 * {@link SseStateClassifier}). Streaming-only (both wires' known response shape is an SSE stream).
 *
 * <p>The endpoint is a constructor parameter (the adapter/gateway URL — left to the caller's
 * {@code transportFor}). The body is the pre-rendered request when {@link OutboundMessage#body()} is
 * present (the {@code ConversationInteractionAdapter} path), else a minimal build (the
 * {@code InteractionFlow} bare-text path) — exactly mirroring {@link RestQueryTransport#send}.
 *
 * <p>One instance serves one flow (= one conversation): a lazily-minted conversation id is reused
 * across every round so a multi-turn EDPA conversation stays coherent (mirrors
 * {@code RestQueryTransport}).
 */
abstract class GatewayStreamingTransport implements MessageTransport {

    private final RestIo io;
    private final URI endpoint;
    /**
     * Lazily minted client-side conversation id, reused across every round of one flow so a
     * multi-turn EDPA conversation stays coherent (mirrors {@code RestQueryTransport}).
     */
    private String conversationId;

    GatewayStreamingTransport(RestIo io, URI endpoint) {
        this.io = io;
        this.endpoint = endpoint;
    }

    @Override
    public OutboundMessage resolveOutbound(OutboundMessage message) {
        // Pre-rendered body already bakes conversation_id; leave it untouched (mirrors RestQueryTransport).
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
        io.postSse(endpoint, body, line -> {
            InboundEvent e = map(line);
            if (e != null) ex.add(e);
        });
        deriveTerminalState(ex.events())
                .ifPresent(s -> ex.add(InboundEvent.state(s)));
        ex.markStreamEnd();
        return ex;
    }

    /**
     * Map one SSE {@code data:} line to an {@link InboundEvent}, or {@code null} to drop it. Each
     * concrete leaf brings its own wire's mapping: the high-code adapter ({@link RestGatewayTransport})
     * uses {@link GatewayEventMapping}; the low-code gateway ({@link RestVersatileTransport}) uses
     * {@link VersatileEventMapping}. The two wires emit different SSE shapes — the adapter projects
     * {@code custom_rsp_data}/{@code think_chunk}, the gateway passes bare {@code {event,data}}
     * frames through with the typed envelope under {@code data} — so they no longer share a
     * classifier.
     */
    protected abstract InboundEvent map(String line);

    /**
     * Derive the round's terminal state from the streamed events at stream-end. Default: the shared
     * {@link SseStateClassifier} (ERROR→FAILED, INTERACTION→INPUT_REQUIRED, clean stream-end→
     * COMPLETED). A leaf whose terminal signal lives elsewhere overrides this — e.g. the versatile
     * gateway derives COMPLETED from a discrete {@code answer} frame (else INPUT_REQUIRED), not from
     * the stream ending, so {@code event="end"} can be surfaced as plain CONTENT.
     */
    protected Optional<TaskState> deriveTerminalState(List<InboundEvent> events) {
        return SseStateClassifier.deriveTerminal(events, true);
    }

    private String body(OutboundMessage m) {
        // Pre-rendered EDPA body (Conversation direct adapter/gateway path) — post verbatim, no reshaping.
        if (m.body() != null) {
            return m.body();
        }
        // Serialize via Jackson so input.query / conversation_id are RFC 8259-correctly escaped
        // (control chars incl. newline/tab, quotes, backslashes) — same guarantee as RestQueryTransport.
        String cid = resolveConversationId(m.contextId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversation_id", cid);
        body.put("input", Map.of("query", m.text() == null ? "" : m.text()));
        body.put("stream", true);
        return JsonUtils.toJsonCompact(body);
    }

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

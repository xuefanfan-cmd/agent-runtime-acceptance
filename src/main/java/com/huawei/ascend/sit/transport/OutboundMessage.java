package com.huawei.ascend.sit.transport;

import java.util.Map;

/**
 * One outbound message: the user text, per-request metadata, and the continuation hints a
 * transport needs to build the wire payload.
 *
 * @param text       the user's natural-language input. InteractionFlow passes bare text; the
 *                   Conversation direct adapter passes the JSON string {@code {"query":...,"intent":...}}
 *                   (A2A {@code parts[0].text}). Ignored by the REST transports when {@code body} is set.
 * @param metadata   per-request A2A metadata (may be null). The Conversation direct adapter fills the
 *                   EDPA envelope here ({@code body}/{@code headers}/{@code query}); ignored by REST.
 * @param taskId     A2A continuation task id — non-empty only when resuming a prior non-terminal
 *                   round; ignored by the REST transports
 * @param contextId  continuation-or-pinned context id — the transport sets it whenever non-blank
 *                   (the flow resolves continuation-vs-flow-pinned before building the message)
 * @param body       pre-rendered wire body (may be null). The Conversation direct adapter sets the full
 *                   EDPA REST body here; {@code RestQueryTransport} posts it verbatim when non-null,
 *                   else builds its minimal bare-text body. Unused by the A2A transports.
 */
public record OutboundMessage(
        String text,
        Map<String, Object> metadata,
        String taskId,
        String contextId,
        String body) {

    /** Convenience for the InteractionFlow bare-text path (no pre-rendered body). */
    public OutboundMessage(String text, Map<String, Object> metadata, String taskId, String contextId) {
        this(text, metadata, taskId, contextId, null);
    }

    /** Copy with a resolved context id (transports use this to stamp the real conversation_id pre-send). */
    public OutboundMessage withContextId(String contextId) {
        return new OutboundMessage(text, metadata, taskId, contextId, body);
    }
}

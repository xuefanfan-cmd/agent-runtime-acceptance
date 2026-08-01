package com.huawei.ascend.sit.transport;

import java.util.List;
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
 * @param partMetadata part-level A2A metadata stamped onto {@code params.message.parts[0]} (may be null).
 *                   The Conversation direct adapter sets {@code {toolCallId: <child>}} on a per-child
 *                   parallel resume so the runtime's {@code RemoteInvocationBatchCoordinator} routes the
 *                   round's input to that child; the serial path and REST leave it null. Only the A2A
 *                   transport consumes it — REST ignores it.
 * @param parts      multi-part (batch) resume payload (may be null). When non-null and non-empty, the
 *                   A2A transports build one {@code TextPart} per {@link OutboundPart} — each carrying its
 *                   own {@code metadata.toolCallId} — instead of the single-part {@code text}/{@code partMetadata}
 *                   path. This is the batch-resume channel: a single POST carrying N children's inputs.
 *                   Null/empty on every existing single-part path, so dispatch is purely additive.
 */
public record OutboundMessage(
        String text,
        Map<String, Object> metadata,
        String taskId,
        String contextId,
        String body,
        Map<String, Object> partMetadata,
        List<OutboundPart> parts) {

    /** Convenience for the InteractionFlow bare-text path (no pre-rendered body, no part metadata). */
    public OutboundMessage(String text, Map<String, Object> metadata, String taskId, String contextId) {
        this(text, metadata, taskId, contextId, null, null, null);
    }

    /** Convenience with a pre-rendered body but no part metadata (the REST family / serial A2A path). */
    public OutboundMessage(String text, Map<String, Object> metadata, String taskId, String contextId, String body) {
        this(text, metadata, taskId, contextId, body, null, null);
    }

    /**
     * Convenience with a pre-rendered body and part-level metadata but no multi-part payload — the
     * single-part A2A path with a per-child routing channel (used by the Conversation direct adapter
     * and the wire-log tests).
     */
    public OutboundMessage(String text, Map<String, Object> metadata, String taskId, String contextId,
                           String body, Map<String, Object> partMetadata) {
        this(text, metadata, taskId, contextId, body, partMetadata, null);
    }

    /** Copy with a resolved context id (transports use this to stamp the real conversation_id pre-send). */
    public OutboundMessage withContextId(String contextId) {
        return new OutboundMessage(text, metadata, taskId, contextId, body, partMetadata, parts);
    }
}

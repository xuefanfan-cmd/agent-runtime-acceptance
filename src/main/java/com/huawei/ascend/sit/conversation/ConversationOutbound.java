package com.huawei.ascend.sit.conversation;

import java.util.List;

/**
 * One outbound conversation message: where to send it (gateway base + caller identity + cid) and the
 * rendered EDPA request body ({@code {"inputs":{...},"headers":{...}}}). Carries exactly what the
 * low-code gateway URL needs — the transport builds
 * {@code /v1/{pid}/agents/{aid}/conversations/{cid}}.
 *
 * <p>The direct (gateway-bypassing) adapter also rebuilds the EDPA envelope from these fields (the
 * gateway's inbound job), so the caller-identity scalars ({@code roleName}/{@code roleId}/
 * {@code timeout}) are carried here in addition to the URL segments.
 *
 * <p>{@code toolCallId} was the per-child resume routing key on the legacy single-child parallel resume
 * path; under the batch model it is null on every live path. The batch resume path
 * ({@link Conversation#sendBatchResume}) sets {@code resumeParts} (one entry per child) and routes each
 * child via its part's {@code metadata.toolCallId}; the serial path ({@link Turn}) passes null for both
 * {@code toolCallId} and {@code resumeParts}. The field is retained for the single-part A2A wire shape
 * (the adapter still stamps it onto {@code parts[0].metadata.toolCallId} should a caller set it).
 * {@code metadata.body.conversation_id} stays the <em>parent</em> cid (children share it), and NO
 * {@code metadata.runtime.remoteToolInputs} is carried on the wire.
 *
 * @param baseUrl        gateway base URL (no trailing path)
 * @param projectId      caller project id (URL path segment)
 * @param agentId        caller agent id (URL path segment; also mirrored into the EDPA {@code body.agent_id})
 * @param conversationId conversation id (URL path segment, echoed in body; the PARENT cid even on a
 *                       per-child resume — children share the parent conversation)
 * @param workspaceId    caller workspace id (URL query param; also {@code metadata.query.workspace_id})
 * @param jsonBody       rendered request body (the {@code ConversationRequest.toJson()} value —
 *                       {@code {"inputs":{...},"headers":{...}}})
 * @param roleName       caller role name (EDPA {@code body.role_name}); from {@code ConversationIdentity}
 * @param roleId         caller role id (EDPA {@code body.role_id}); from {@code ConversationIdentity}
 * @param timeout        timeout in seconds as a string (EDPA {@code body.timeout}); from {@code Conversation.timeout()}
 * @param toolCallId     per-child resume routing key — the child member's remote-invocation toolCallId.
 *                       Null on both the serial path ({@link Turn}) and the batch resume path
 *                       ({@link Conversation#sendBatchResume}) — the batch path routes per child via
 *                       {@code resumeParts}/{@code metadata.toolCallId}, not this field. Retained for the
 *                       single-part A2A wire shape.
 * @param resumeParts    non-null only on the batch resume path — one {@link ResumePart} per parallel child
 *                       in this batch round. The adapter renders each part onto its own A2A
 *                       {@code Message.parts} entry with {@code metadata.toolCallId} so the runtime routes
 *                       each child's resume input. Null on the serial path ({@link Turn}); non-null on the
 *                       batch resume path ({@link Conversation#sendBatchResume}).
 */
public record ConversationOutbound(
        String baseUrl,
        String projectId,
        String agentId,
        String conversationId,
        int workspaceId,
        String jsonBody,
        String roleName,
        String roleId,
        String timeout,
        String toolCallId,
        List<ResumePart> resumeParts) {
}

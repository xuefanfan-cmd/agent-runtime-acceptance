package com.huawei.ascend.sit.conversation;

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
 * <p>{@code toolCallId} is the per-child resume routing key for parallel driving: when non-null, the
 * resume is a parallel child continuation — the adapter stamps it onto the A2A
 * {@code params.message.parts[0].metadata.toolCallId} so the runtime's
 * {@code RemoteInvocationBatchCoordinator.resumeWaitingBatch} routes this round's input to that specific
 * child member. {@code metadata.body.conversation_id} stays the <em>parent</em> cid (children share it),
 * and NO {@code metadata.runtime.remoteToolInputs} is carried on the wire. Only the parallel resume path
 * ({@link Conversation#postResume}) sets {@code toolCallId}; the serial {@link Turn} driving path passes
 * {@code null} (no part metadata). The part-level {@code metadata.toolCallId} is the runtime's resume
 * contract — flagged verify-against-Phase-0 in the plan.
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
 *                       Non-null only on the parallel resume path ({@link Conversation#postResume}); the
 *                       adapter stamps it onto {@code parts[0].metadata.toolCallId}. Null on the serial
 *                       path ({@link Turn}) — no part metadata, no {@code runtime} key on the wire.
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
        String toolCallId) {
}

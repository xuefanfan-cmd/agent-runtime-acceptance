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
 * @param baseUrl         gateway base URL (no trailing path)
 * @param projectId       caller project id (URL path segment)
 * @param agentId         caller agent id (URL path segment; also mirrored into the EDPA {@code body.agent_id})
 * @param conversationId  conversation id (URL path segment, echoed in body)
 * @param workspaceId     caller workspace id (URL query param; also {@code metadata.query.workspace_id})
 * @param jsonBody        rendered request body (the {@code ConversationRequest.toJson()} value —
 *                       {@code {"inputs":{...},"headers":{...}}})
 * @param roleName        caller role name (EDPA {@code body.role_name}); from {@code ConversationIdentity}
 * @param roleId          caller role id (EDPA {@code body.role_id}); from {@code ConversationIdentity}
 * @param timeout         timeout in seconds as a string (EDPA {@code body.timeout}); from {@code Conversation.timeout()}
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
        String timeout) {}

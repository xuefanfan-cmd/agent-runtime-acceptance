package com.huawei.ascend.sit.client;

import com.huawei.ascend.sit.utils.TestDataLoader;

import java.net.URI;

/**
 * The fixed front-end identity a gateway/adapter REST wire needs in its conversation endpoint URL —
 * {@code projectId}/{@code agentId}/{@code workspaceId}. Loaded from the same
 * {@code conversation-identity.json} the {@code Conversation} path uses (so a direct
 * {@code InteractionFlow} drive of a gateway/adapter wire shares the primary path's identity), read
 * here in the {@code client} package via {@link TestDataLoader} to avoid a {@code client}→
 * {@code conversation} dependency cycle — {@code ConversationIdentity} lives in {@code conversation},
 * which already depends on {@code client}.
 */
public record GatewayIdentity(String projectId, String agentId, int workspaceId) {

    /** Shared with {@code ConversationIdentity.DEFAULT_TESTDATA_PATH}. */
    public static final String DEFAULT_TESTDATA_PATH = "component/conversation/conversation-identity.json";

    /** Load the default gateway identity from {@code conversation-identity.json} (classpath testdata). */
    public static GatewayIdentity loadDefault() {
        return TestDataLoader.load(DEFAULT_TESTDATA_PATH, GatewayIdentity.class);
    }

    /**
     * The gateway/adapter conversation endpoint: {@code <base>/v1/{pid}/agents/{aid}/conversations/
     * {cid}?type=controller&workspace_id=<N>} — the same shape
     * {@code conversation.RestVersatileTransport.endpoint} / {@code ConversationInteractionAdapter}
     * build on the primary path.
     */
    public URI conversationEndpoint(String baseUrl, String conversationId) {
        return URI.create(baseUrl + "/v1/" + projectId + "/agents/" + agentId
                + "/conversations/" + conversationId + "?type=controller&workspace_id=" + workspaceId);
    }
}

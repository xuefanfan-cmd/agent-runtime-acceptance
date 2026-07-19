package com.huawei.ascend.sit.conversation;

import com.huawei.ascend.sit.transport.RestExchange;

import java.net.URI;

/**
 * The low-code-gateway {@link ConversationTransport}: POST the rendered EDPA body to
 * {@code /v1/{pid}/agents/{aid}/conversations/{cid}?type=controller&workspace_id=N}, consume the
 * {@code text/event-stream} reply into the collector, and mark the stream ended.
 *
 * <p>Built on the shared {@link RestExchange} wire — this adapter only shapes the gateway URL +
 * request body and decodes each SSE line into a {@link SseEvent}; the {@code finally markStreamEnd()}
 * in {@link #send(ConversationOutbound, ConversationEventCollector)} honours the collector's
 * {@link ConversationEventCollector#awaitStreamEnd(long)} await (success or failure).
 */
public final class RestVersatileTransport implements ConversationTransport {

    private final RestExchange exchange;

    public RestVersatileTransport() {
        this(new RestExchange());
    }

    /** Test seam: inject a pre-built {@link RestExchange}. */
    RestVersatileTransport(RestExchange exchange) {
        this.exchange = exchange;
    }

    @Override
    public void send(ConversationOutbound out, ConversationEventCollector collector) {
        try {
            exchange.postSse(endpoint(out), out.jsonBody(), line -> {
                SseEvent e = SseEvent.parse(line);
                if (e != null) {
                    collector.add(e);
                }
            });
        } finally {
            collector.markStreamEnd();
        }
    }

    /** Build the gateway conversation URL from the outbound identity. */
    static URI endpoint(ConversationOutbound out) {
        String url = out.baseUrl() + "/v1/" + out.projectId() + "/agents/" + out.agentId()
                + "/conversations/" + out.conversationId()
                + "?type=controller&workspace_id=" + out.workspaceId();
        return URI.create(url);
    }
}

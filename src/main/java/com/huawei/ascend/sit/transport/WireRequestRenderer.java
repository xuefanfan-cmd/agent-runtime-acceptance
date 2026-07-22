package com.huawei.ascend.sit.transport;

import com.huawei.ascend.sit.utils.JsonUtils;
import org.a2aproject.sdk.spec.A2AMethods;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Render an {@link OutboundMessage} as a complete, paste-ready wire request — the HTTP request-line,
 * headers and body a human can copy into Postman / an HTTP client / {@code curl} to replay the round by
 * hand. This is the request-side counterpart to the response-side
 * {@link A2aEventMapping#wireEnvelopeOf(Object)}: unlike the response (where the SDK parses each SSE
 * frame and discards the bytes), the request is fully under sit's control, so the rendered body is a
 * faithful reconstruction of what goes on the wire.
 *
 * <p><b>A2A</b> ({@link MessageProtocol#A2A_STREAM}/{@code A2A_SYNC}): a JSON-RPC envelope
 * {@code {"jsonrpc":"2.0","id":<gen>,"method":<SendStreamingMessage|SendMessage>,"params":{message,metadata}}}.
 * It is <em>hand-built from plain maps</em>, not by serializing the SDK records: the A2A SDK serializes
 * the wire body with a custom Gson {@code JsonUtil}, so a plain Jackson dump of its records would
 * diverge — {@code Message.Role} wires as the enum name verbatim ({@code "ROLE_USER"}, not
 * {@code "user"}), and a text part is {@code {"text":...}} keyed by discriminator with no separate
 * {@code "type"} field. Method names come from {@link A2AMethods} (A2A 1.0 PascalCase —
 * {@code SendStreamingMessage}/{@code SendMessage}), NOT the pre-1.0 {@code message/stream} slash form.
 * {@code id}/{@code messageId} are freshly-minted UUIDs (representative; the server does not echo them).
 *
 * <p><b>REST</b> ({@code REST_QUERY}/{@code REST_QUERY_SYNC}/{@code REST_REACTIVE}/{@code REST_REACTIVE_SYNC}):
 * {@link OutboundMessage#body()} verbatim when present (the adapter pre-renders the full EDPA envelope),
 * else a minimal {@code {conversation_id,message,stream}} body. The endpoint URL — including query params such as
 * {@code ?type=controller&workspace_id=N} — is caller-supplied: it lives on the transport / client, which
 * this transport-package renderer must not depend on (direction invariant). When the caller has none
 * (the {@code using(transport)} unit-test seam, where there is no client to read the agent card URL
 * from), the request-line shows {@code <endpoint unknown>}.
 */
public final class WireRequestRenderer {

    /** A2A user-message role, matching the SDK's wire serialization of {@code Message.Role.ROLE_USER}. */
    private static final String ROLE_USER = "ROLE_USER";

    private WireRequestRenderer() {}

    /**
     * @param protocol    the wire protocol (selects the A2A method / REST body shape)
     * @param message     the outbound message
     * @param endpointUrl the full request URL (REST: incl. query params; A2A: the agent JSON-RPC URL),
     *                    or {@code null} when unknown
     * @return a paste-ready HTTP request block: {@code POST <url>} + {@code Content-Type} + blank + body
     */
    public static String render(MessageProtocol protocol, OutboundMessage message, String endpointUrl) {
        return switch (protocol) {
            case A2A_STREAM, A2A_SYNC -> httpBlock(endpointUrl, a2aBody(protocol, message));
            case REST_QUERY, REST_QUERY_SYNC, REST_REACTIVE, REST_REACTIVE_SYNC ->
                    httpBlock(endpointUrl, restBody(protocol, message));
            default -> throw new IllegalArgumentException("Unsupported protocol: " + protocol);
        };
    }

    /**
     * The A2A JSON-RPC envelope, hand-built to match the SDK's Gson wire shape (see class javadoc).
     * Continuation hints ({@code taskId}/{@code contextId}) and {@code metadata} are included only when
     * present, mirroring {@link A2aStreamingWire#buildMessage}'s own conditional setting.
     */
    private static String a2aBody(MessageProtocol protocol, OutboundMessage message) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("text", message.text() == null ? "" : message.text());

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", ROLE_USER);
        msg.put("messageId", UUID.randomUUID().toString());
        if (message.contextId() != null && !message.contextId().isBlank()) {
            msg.put("contextId", message.contextId());
        }
        if (message.taskId() != null && !message.taskId().isBlank()) {
            msg.put("taskId", message.taskId());
        }
        msg.put("parts", List.of(part));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("message", msg);
        if (message.metadata() != null) {
            params.put("metadata", message.metadata());
        }

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", UUID.randomUUID().toString());
        envelope.put("method", protocol == MessageProtocol.A2A_SYNC
                ? A2AMethods.SEND_MESSAGE_METHOD          // "SendMessage"
                : A2AMethods.SEND_STREAMING_MESSAGE_METHOD); // "SendStreamingMessage"
        envelope.put("params", params);
        return JsonUtils.toPrettyJson(envelope);
    }

    /** REST body: the pre-rendered EDPA envelope verbatim (pretty-printed), else a minimal body. */
    private static String restBody(MessageProtocol protocol, OutboundMessage message) {
        if (message.body() != null) {
            return prettyJsonOrRaw(message.body());
        }
        boolean stream = protocol == MessageProtocol.REST_QUERY
                || protocol == MessageProtocol.REST_REACTIVE;
        String conversationId = (message.contextId() != null && !message.contextId().isBlank())
                ? message.contextId() : UUID.randomUUID().toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversation_id", conversationId);
        body.put("message", message.text() == null ? "" : message.text());
        body.put("stream", stream);
        return JsonUtils.toPrettyJson(body);
    }

    /** A raw HTTP request block: request-line + Content-Type + blank line + body. */
    private static String httpBlock(String endpointUrl, String body) {
        String url = (endpointUrl == null || endpointUrl.isBlank()) ? "<endpoint unknown>" : endpointUrl;
        return "POST " + url + "\n"
                + "Content-Type: application/json\n"
                + "\n"
                + body;
    }

    /** Pretty-print a JSON body string for readability; fall back to the raw string when not valid JSON. */
    private static String prettyJsonOrRaw(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        try {
            return JsonUtils.mapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(JsonUtils.mapper().readTree(json));
        } catch (Exception e) {
            return json;
        }
    }
}

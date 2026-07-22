package com.huawei.ascend.sit.transport;

import com.huawei.ascend.sit.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Maps {@code /v1/query} REST frames to {@link InboundEvent}s, classifying the typed LLM envelopes
 * both protocols carry. SSE: one {@code data:<json>} line → one event; non-{@code data:} lines →
 * {@code null}. Non-stream: the single JSON response → one event + a synthesised COMPLETED STATE.
 *
 * <p>Real frames are typed envelopes wrapped as
 * {@code {"event":"…","data":{"type":"…","index":N,"payload":{…}}}} (or a bare
 * {@code {"type":"…",...,"payload":{…}}} for the answer result); {@link LlmPayload} maps these to
 * {@link InboundEvent.Kind#ANSWER}/{@code LLM_OUTPUT}/{@code LLM_REASONING}/{@code LLM_USAGE}. The
 * non-stream {@code /v1/query} JSON body carries the answer as
 * {@code {"result":{"role":"assistant","content":"…"},"conversation_id":"…"}} — its
 * {@code result.content} is the agent's final reply and is mapped to {@link InboundEvent.Kind#ANSWER}.
 * A legacy {@code {"data":{"text":"…"}}} frame or any other non-typed JSON falls back to a plain
 * {@link InboundEvent.Kind#CONTENT} event.
 */
public final class RestEventMapping {

    /**
     * The gateway/workflow input-required sentinel — a bare {@code {"message":"Remote agent requires
     * input"}} frame (streaming — no {@code result}/{@code type} wrapper) or the same text nested under
     * {@code result._interrupt.message} (non-stream) — recognised when no typed {@code __interaction__}
     * envelope is present. Matched exactly (see {@link #remoteInputRequiredMessage}) so other
     * {@code message} values don't falsely synthesise INPUT_REQUIRED.
     */
    private static final String REMOTE_INPUT_REQUIRED_MESSAGE = "Remote agent requires input";

    private RestEventMapping() {}

    /** Parse one SSE line; {@code null} for non-{@code data:} lines. */
    public static InboundEvent toEvent(String line) {
        if (line == null) {
            return null;
        }
        String s = line.strip();
        if (!s.startsWith("data:")) {
            return null;
        }
        String payload = s.substring("data:".length());
        if (payload.startsWith(" ")) {
            payload = payload.substring(1);
        }
        if (payload.isEmpty()) {
            return null;
        }
        return classifyPayload(payload);
    }

    /**
     * Non-stream JSON response → typed/content event + a synthesised terminal STATE: INPUT_REQUIRED
     * when the body is a {@code __interaction__} envelope (the REST SUT's mid-turn "needs more info"
     * signal), otherwise COMPLETED.
     */
    public static InboundEvent[] fromJson(String json) {
        InboundEvent ev = classifyPayload(json);
        boolean inputRequired = ev != null && ev.kind() == InboundEvent.Kind.INTERACTION;
        org.a2aproject.sdk.spec.TaskState state = inputRequired
                ? org.a2aproject.sdk.spec.TaskState.TASK_STATE_INPUT_REQUIRED
                : org.a2aproject.sdk.spec.TaskState.TASK_STATE_COMPLETED;
        return new InboundEvent[] { ev, InboundEvent.state(state, "", "", json) };
    }

    /**
     * Classify one REST payload. Typed envelopes win; then a bare {@code {"message":"Remote agent
     * requires input"}} frame (streaming) or the same under {@code result._interrupt.message}
     * (non-stream) becomes an INTERACTION (INPUT_REQUIRED); then the non-stream
     * {@code {result:{content}}} body becomes an ANSWER; legacy {@code {data:{text}}} and other
     * non-typed JSON fall back to a CONTENT event carrying the (legacy) text or the raw payload.
     */
    private static InboundEvent classifyPayload(String payload) {
        JsonNode root;
        try {
            root = JsonUtils.mapper().readTree(payload);
        } catch (Exception ex) {
            return InboundEvent.content(payload, payload);   // non-JSON data line → raw content
        }
        JsonNode envelope = typedEnvelope(root);
        if (envelope != null) {
            InboundEvent typed = LlmPayload.classify(envelope, payload);
            if (typed != null) {
                return typed;
            }
        }
        // Non-stream REST body with an embedded interaction: the __interaction__ envelope nested
        // under result._interrupt is the mid-turn "needs more info" signal — it wins over content.
        JsonNode interrupt = resultInterrupt(root);
        if (interrupt != null) {
            InboundEvent typed = LlmPayload.classify(interrupt, payload);
            if (typed != null) {
                return typed;
            }
        }
        // The gateway/workflow input-required sentinel: a bare {"message":"Remote agent requires
        // input"} frame (streaming — no result/type wrapper) or the same message nested under
        // result._interrupt.message (non-stream). Matched EXACTLY so other messages don't falsely
        // synthesise INPUT_REQUIRED.
        String sentinel = remoteInputRequiredMessage(root);
        if (sentinel != null) {
            return InboundEvent.interaction(sentinel, payload);
        }
        // Non-stream REST body: {"result":{"role":"assistant","content":"…"},"conversation_id":"…"}.
        String answer = resultContent(root);
        if (answer != null) {
            return InboundEvent.answer(answer, payload);
        }
        return InboundEvent.content(legacyText(root, payload), payload);
    }

    /**
     * The assistant's reply in a non-stream REST body — {@code result.content} when {@code result} is
     * an object with a {@code content} field; {@code null} when the body has no such shape (so the
     * caller falls through to the legacy CONTENT fallback).
     */
    private static String resultContent(JsonNode root) {
        JsonNode result = root.has("result") ? root.get("result") : null;
        if (result == null || !result.isObject() || !result.has("content")) {
            return null;
        }
        JsonNode content = result.get("content");
        return (content == null || content.isNull()) ? null : content.asText("");
    }

    /**
     * The typed {@code __interaction__} envelope nested under {@code result._interrupt} in a non-stream
     * REST body (the gateway carries the mid-turn interrupt there alongside {@code result.content});
     * {@code null} when the body has no such embedded interrupt. Returns the {@code _interrupt} node
     * only when it is a typed envelope (has {@code type}); a bare message-only {@code _interrupt} is
     * handled by {@link #remoteInputRequiredMessage} (the exact-sentinel contract), not here.
     */
    private static JsonNode resultInterrupt(JsonNode root) {
        JsonNode result = root.has("result") ? root.get("result") : null;
        if (result == null || !result.isObject() || !result.has("_interrupt")) {
            return null;
        }
        JsonNode interrupt = result.get("_interrupt");
        if (interrupt == null || !interrupt.isObject() || !interrupt.has("type")) {
            return null;
        }
        return interrupt;
    }

    /**
     * The exact {@link #REMOTE_INPUT_REQUIRED_MESSAGE} sentinel if this frame carries it — either as a
     * bare top-level {@code {"message":"…"}} (streaming — no {@code result}/{@code type} wrapper) or
     * nested under {@code result._interrupt.message} (non-stream); otherwise {@code null}. Any other
     * {@code message} value does not match, so it falls through to {@code result.content} / the legacy
     * CONTENT fallback instead of falsely synthesising INPUT_REQUIRED.
     */
    private static String remoteInputRequiredMessage(JsonNode root) {
        String message = messageText(root);
        if (REMOTE_INPUT_REQUIRED_MESSAGE.equals(message)) {
            return message;
        }
        JsonNode result = root.has("result") ? root.get("result") : null;
        if (result != null && result.isObject() && result.has("_interrupt")) {
            message = messageText(result.get("_interrupt"));
            if (REMOTE_INPUT_REQUIRED_MESSAGE.equals(message)) {
                return message;
            }
        }
        return null;
    }

    /** The {@code message} text of a JSON object, or {@code null} when absent/null/non-textual. */
    private static String messageText(JsonNode node) {
        if (node == null || !node.isObject() || !node.has("message")) {
            return null;
        }
        JsonNode message = node.get("message");
        if (message == null || message.isNull() || !message.isTextual()) {
            return null;
        }
        return message.asText();
    }

    /**
     * The {@code {type,index,payload}} envelope node if the frame carries one: the {@code data} node
     * when wrapped ({@code {event,data:{type,…}}}), else the root when bare ({@code {type,…}});
     * otherwise {@code null}.
     */
    private static JsonNode typedEnvelope(JsonNode root) {
        JsonNode data = root.has("data") ? root.get("data") : null;
        if (data != null && data.has("type")) {
            return data;
        }
        return root.has("type") ? root : null;
    }

    /** Legacy {@code {data:{text:X}}} text extraction; falls back to the raw payload string. */
    private static String legacyText(JsonNode root, String payload) {
        JsonNode data = root.has("data") ? root.get("data") : root;
        JsonNode text = data.has("text") ? data.get("text") : null;
        return text == null ? payload : text.asText("");
    }
}

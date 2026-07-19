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
     * Classify one REST payload. Typed envelopes win; then the non-stream {@code {result:{content}}}
     * body becomes an ANSWER — unless it embeds a {@code __interaction__} under
     * {@code result._interrupt}, in which case that interaction wins (INPUT_REQUIRED); legacy
     * {@code {data:{text}}} and other non-typed JSON fall back to a CONTENT event carrying the
     * (legacy) text or the raw payload.
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
     * The {@code __interaction__} envelope nested under {@code result._interrupt} in a non-stream
     * REST body (the gateway carries the mid-turn interrupt there alongside {@code result.content});
     * {@code null} when the body has no such embedded interrupt.
     */
    private static JsonNode resultInterrupt(JsonNode root) {
        JsonNode result = root.has("result") ? root.get("result") : null;
        if (result == null || !result.isObject() || !result.has("_interrupt")) {
            return null;
        }
        JsonNode interrupt = result.get("_interrupt");
        return (interrupt != null && interrupt.isObject() && interrupt.has("type")) ? interrupt : null;
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

package com.huawei.ascend.sit.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.ascend.sit.utils.JsonUtils;

/**
 * Classifies the typed LLM-interaction envelopes both protocols carry into {@link InboundEvent}s of
 * the right {@link InboundEvent.Kind}. The envelope is the same on the wire —
 * {@code {"type":"…","index":N,"payload":{…}}} — only the wrapping differs:
 * <ul>
 *   <li><b>A2A</b>: the envelope is a JSON <em>string</em> inside an artifact part's {@code text};</li>
 *   <li><b>REST</b>: the envelope is the {@code data} node of an SSE frame
 *       ({@code {"event":"…","data":{"type":"…",...,"payload":{…}}}}).</li>
 * </ul>
 *
 * <p>Recognised {@code type} values and their payload text field:
 * <ul>
 *   <li>{@code answer} → {@link InboundEvent.Kind#ANSWER}, text = {@code payload.output}
 *       (the agent's final processed result);</li>
 *   <li>{@code workflow_final} → {@link InboundEvent.Kind#ANSWER}, text = {@code payload.output}
 *       (same as {@code answer}: a custom terminal-result envelope some workflow SUTs emit instead of
 *       the standard {@code answer} type — e.g. the expense-review workflow's final report. The output
 *       is normally a string; a non-string (object/array) output is JSON-stringified so the result
 *       carries verbatim into {@code answerText()});</li>
 *   <li>{@code llm_output} → {@link InboundEvent.Kind#LLM_OUTPUT}, text = {@code payload.content}
 *       (streaming output tokens);</li>
 *   <li>{@code llm_reasoning} → {@link InboundEvent.Kind#LLM_REASONING}, text = {@code payload.content}
 *       (chain-of-thought);</li>
 *   <li>{@code llm_usage} → {@link InboundEvent.Kind#LLM_USAGE}, no text — the payload node (which
 *       carries {@code usage_metadata}) is retained in {@code raw}.</li>
 *   <li>{@code __interaction__} → {@link InboundEvent.Kind#INTERACTION}, text = the agent's
 *       clarifying {@code message}; the REST SUT's mid-turn "needs more info" signal, equivalent to
 *       A2A {@code INPUT_REQUIRED}. Any {@code __interaction__} envelope qualifies — no tool gating.</li>
 * </ul>
 *
 * <p>This mapping is <em>shared</em> across both protocols by design: {@code workflow_final}→ANSWER is a
 * semantic classification, applied wherever a clean envelope is seen. If a REST run does not surface it,
 * that is a data-quality issue in that run's frame (the gateway sometimes carries the envelope
 * double-encoded/escaped, so its outer char is {@code "} not {@code { and the pre-filter skips it) —
 * not a scoping policy.
 *
 * <p>Any other {@code type}, a non-JSON string, or a node without {@code type} yields {@code null}
 * — callers fall back to a plain {@link InboundEvent#content(String, Object)} event.
 */
public final class LlmPayload {

    public static final String TYPE_ANSWER = "answer";
    public static final String TYPE_LLM_OUTPUT = "llm_output";
    public static final String TYPE_LLM_REASONING = "llm_reasoning";
    public static final String TYPE_LLM_USAGE = "llm_usage";
    /** REST SUT interaction envelope (mid-turn user-input request) → INPUT_REQUIRED-equivalent. */
    public static final String TYPE_INTERACTION = "__interaction__";
    /** Custom terminal-result envelope some workflow SUTs emit instead of the standard {@code answer} → ANSWER. */
    public static final String TYPE_WORKFLOW_FINAL = "workflow_final";

    private LlmPayload() {}

    /**
     * Classify a JSON string that should be a {@code {type,index,payload}} envelope.
     *
     * @return the typed event, or {@code null} if {@code json} is not a JSON object or has an
     *         unrecognised {@code type} (caller falls back to plain content).
     */
    public static InboundEvent classify(String json, Object raw) {
        if (json == null) {
            return null;
        }
        String s = json.strip();
        // Cheap pre-filter: only an object can be a typed envelope; this avoids parsing every plain
        // text chunk (the common non-typed case) as JSON.
        if (s.isEmpty() || s.charAt(0) != '{') {
            return null;
        }
        try {
            return classify(JsonUtils.mapper().readTree(s), raw);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Classify a {@code {type,index,payload}} node.
     *
     * @return the typed event, or {@code null} if {@code node} lacks a recognised {@code type}.
     */
    public static InboundEvent classify(JsonNode node, Object raw) {
        if (node == null || !node.has("type")) {
            return null;
        }
        String type = node.get("type").asText("");
        JsonNode payload = node.has("payload") ? node.get("payload") : null;
        switch (type) {
            case TYPE_ANSWER:
                return InboundEvent.answer(payloadText(payload, "output"), raw);
            case TYPE_WORKFLOW_FINAL:
                // Same as answer — a terminal-result envelope a workflow SUT emits under a custom type.
                // The output is usually a string; a non-string (object/array, e.g. {"auto_result":…})
                // is JSON-stringified so the result carries verbatim into answerText().
                return InboundEvent.answer(payloadOutput(payload, "output"), raw);
            case TYPE_LLM_OUTPUT:
                return InboundEvent.llmOutput(payloadText(payload, "content"), raw);
            case TYPE_LLM_REASONING:
                return InboundEvent.llmReasoning(payloadText(payload, "content"), raw);
            case TYPE_LLM_USAGE:
                // Usage carries metadata, not text — keep the payload node (with usage_metadata) in raw.
                return InboundEvent.llmUsage(payload);
            case TYPE_INTERACTION:
                // Any __interaction__ envelope is the REST SUT's mid-turn "needs more info" signal,
                // equivalent to A2A INPUT_REQUIRED. Carry the agent's clarifying message so
                // generatedText()/assertGenerated can see it.
                return InboundEvent.interaction(interactionMessage(node, payload), raw);
            default:
                return null;
        }
    }

    private static String payloadText(JsonNode payload, String field) {
        if (payload == null || !payload.has(field)) {
            return "";
        }
        JsonNode n = payload.get(field);
        return (n == null || n.isNull()) ? "" : n.asText("");
    }

    /**
     * Like {@link #payloadText} but object/array-aware: a textual field is read verbatim, while an
     * object/array field (e.g. {@code workflow_final}'s {@code {"auto_result":…}}) is JSON-stringified
     * so the whole result carries into {@code answerText()}. Missing/null → "".
     */
    private static String payloadOutput(JsonNode payload, String field) {
        if (payload == null || !payload.has(field)) {
            return "";
        }
        JsonNode n = payload.get(field);
        if (n == null || n.isNull()) {
            return "";
        }
        return n.isTextual() ? n.asText("") : n.toString();
    }

    /** The clarifying question text: {@code payload.value.message} → {@code payload.message} → {@code node.message} → "". */
    private static String interactionMessage(JsonNode node, JsonNode payload) {
        JsonNode value = (payload != null && payload.has("value")) ? payload.get("value") : null;
        String m = textField(value, "message");
        if (m != null) return m;
        m = textField(payload, "message");
        if (m != null) return m;
        m = textField(node, "message");
        return m != null ? m : "";
    }

    private static String textField(JsonNode n, String field) {
        if (n == null || !n.has(field)) return null;
        JsonNode v = n.get(field);
        return (v == null || v.isNull()) ? null : v.asText("");
    }
}

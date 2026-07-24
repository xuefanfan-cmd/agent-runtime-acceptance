package com.huawei.ascend.sit.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.ascend.sit.utils.JsonUtils;

/**
 * Maps EDPA gateway/workflow SSE frames ({@code custom_rsp_data}-wrapped) to {@link InboundEvent}s —
 * the gateway peer of {@link RestEventMapping}. One {@code data:<json>} line → one event; non-
 * {@code data:} lines → {@code null}.
 *
 * <p>Wire shape (the high-code adapter and low-code gateway share it): each SSE data line is a JSON
 * object whose payload sits under {@code custom_rsp_data}. The payload shapes (authoritative against the
 * gateway's {@code GatewayStreamProjector}, which projects each A2A streaming event to one
 * {@code custom_rsp_data}):
 * <ul>
 *   <li><b>EDPA-native</b> ({@code event}-named frames): {@code event="think_chunk"} + {@code content}
 *       → {@link InboundEvent.Kind#LLM_REASONING} (the projector rewrites {@code type=llm_reasoning}
 *       into this); {@code event="end"} → a legacy high-code-adapter relic (the real plan-agent gateway
 *       wire has no end frame — {@code GatewayProtocolAdapter} always emits {@code SseEvent(null,json)});
 *       surfaced as a no-text {@link InboundEvent.Kind#CONTENT} carrying the raw frame, never dropped;
 *       {@code event="message"} +
 *       {@code data.text} → {@link InboundEvent.Kind#CONTENT} (the versatile-simulated / legacy
 *       gateway frame);</li>
 *   <li><b>typed-envelope passthrough</b> ({@code {type,index,payload}}): the gateway/adapter
 *       ({@code GatewayStreamProjector} / {@code A2aArtifactNormalizer}) passes through any
 *       {@code type} other than {@code llm_reasoning}/{@code llm_usage} verbatim. These are classified
 *       by the <em>same</em> {@link LlmPayload} table as {@link RestEventMapping} ({@code answer} /
 *       {@code workflow_final} → {@link InboundEvent.Kind#ANSWER}, {@code llm_output} →
 *       {@link InboundEvent.Kind#LLM_OUTPUT}, {@code llm_reasoning} →
 *       {@link InboundEvent.Kind#LLM_REASONING}, {@code __interaction__} →
 *       {@link InboundEvent.Kind#INTERACTION}), so an answer/interaction carried by the EDPA wire is
 *       never silently demoted to {@code CONTENT};</li>
 *   <li><b>non-JSON text fallback</b> ({@code {text:<raw>}}): the projector emits this for an artifact
 *       text part that is not a typed envelope (plain text); read at {@code wrapper.text} →
 *       {@link InboundEvent.Kind#CONTENT}, so plain text is never lost;</li>
 *   <li><b>ignorable</b> (empty {@code {}} / no-text frames): the projector emits this for
 *       {@code type=llm_usage} / status updates / no-text artifacts → a no-text
 *       {@link InboundEvent.Kind#CONTENT} carrying the raw payload (never dropped — the round's terminal
 *       state is derived from the discrete {@code answer} frame downstream).</li>
 * </ul>
 * {@code success:false} → {@link InboundEvent.Kind#ERROR} (→ {@code FAILED} via the leaf's
 * {@code deriveTerminalState}; checked before the event name / type — the gateway's error frames always
 * pair {@code event="error"} with {@code success:false}). A frame without the {@code custom_rsp_data}
 * wrapper (the bare shape a mock serves) is read the same way. Non-JSON data lines fall back to a raw
 * {@code CONTENT} event.
 */
public final class GatewayEventMapping {

    /** The {@code custom_rsp_data.event} value that marks a chain-of-thought chunk. */
    static final String THINK_CHUNK = "think_chunk";
    /** The {@code custom_rsp_data.event} value for the versatile-simulated / legacy text frame. */
    static final String MESSAGE = "message";

    private GatewayEventMapping() {}

    /**
     * Parse one SSE line; {@code null} only for non-{@code data:} lines and empty {@code data:} lines
     * (SSE framing noise). Every actual frame — including the legacy {@code end} marker and ignorable
     * no-text frames — is surfaced as a {@link InboundEvent} (CONTENT when untyped/no-text); the round's
     * terminal state is derived from the discrete {@code answer} frame by
     * {@link RestGatewayTransport#deriveTerminalState}, not from {@code end}.
     */
    public static InboundEvent toEvent(String line) {
        if (line == null) return null;
        String s = line.strip();
        if (!s.startsWith("data:")) return null;
        String payload = s.substring("data:".length());
        if (payload.startsWith(" ")) payload = payload.substring(1);
        if (payload.isEmpty()) return null;
        return classify(payload);
    }

    private static InboundEvent classify(String payload) {
        JsonNode root;
        try {
            root = JsonUtils.mapper().readTree(payload);
        } catch (Exception ex) {
            return InboundEvent.content(payload, payload);   // non-JSON data line → raw content
        }
        JsonNode wrapper = root.has("custom_rsp_data") ? root.get("custom_rsp_data") : root;
        // success:false is the error signal — it wins over the event name / type.
        boolean success = !root.has("success") || root.get("success").asBoolean(true);
        if (!success) {
            JsonNode err = root.get("error");
            String msg = (err != null && !err.asText("").isEmpty())
                    ? err.asText() : "EDPA gateway reported success:false";
            return InboundEvent.error(msg, payload);
        }
        // Typed-envelope passthrough: a {type,index,payload} envelope (the gateway/adapter passes
        // answer/llm_output/llm_reasoning/__interaction__/workflow_final through verbatim). Classify
        // via the SAME LlmPayload table as RestEventMapping so an answer/interaction is never demoted
        // to CONTENT. (think_chunk is rewritten by the projector from llm_reasoning, so an explicit
        // llm_reasoning envelope is rare on this wire — but classify it the same way when seen.)
        if (wrapper.has("type")) {
            InboundEvent typed = LlmPayload.classify(wrapper, payload);
            if (typed != null) {
                return typed;
            }
        }
        String event = wrapper.has("event") ? wrapper.get("event").asText() : "";
        if (THINK_CHUNK.equals(event)) {
            return InboundEvent.llmReasoning(textOf(wrapper, "content"), payload);
        }
        // event="message" (or any named event — including the legacy "end" relic — or none): surface the
        // carried text wherever the gateway/adapter puts it — content (think_chunk's field), output,
        // data.text (the versatile / legacy message frame), or wrapper.text (GatewayStreamProjector's
        // non-JSON-text fallback {text:<raw>}, whose contract is "content is never lost").
        // No-drop: a frame with no readable text (e.g. the legacy "end" marker, an ignorable {}) is
        // surfaced as a no-text CONTENT carrying the raw payload — never dropped — so the round's
        // terminal state is derived from the discrete answer frame (see RestGatewayTransport), not from
        // end. (Empty text is a no-op in generatedText()'s concatenation.)
        String text = textOf(wrapper, "content");
        if (text == null || text.isEmpty()) {
            text = textOf(wrapper, "output");
        }
        if (text == null || text.isEmpty()) {
            text = textOf(wrapper.get("data"), "text");
        }
        if (text == null || text.isEmpty()) {
            text = textOf(wrapper, "text");
        }
        return InboundEvent.content(text == null ? "" : text, payload);
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode n = (node != null && node.has(field)) ? node.get(field) : null;
        if (n == null || n.isNull()) return null;
        return n.isTextual() ? n.asText() : n.toString();
    }
}

package com.huawei.ascend.sit.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.ascend.sit.utils.JsonUtils;

/**
 * Maps the low-code-gateway (REST_VERSATILE) SSE frames to {@link InboundEvent}s as a faithful,
 * kind-flat stream: <b>every</b> {@code data:<json>} line becomes one
 * {@link InboundEvent.Kind#CONTENT} event carrying the frame's raw payload (and best-effort decoded
 * text). Nothing is classified into LLM_x/ANSWER/ERROR kinds, and nothing is dropped — the terminal
 * {@code event="end"} frame is surfaced as CONTENT just like the rest, rather than being consumed
 * into a STATE.
 *
 * <p>Rationale: the versatile gateway streams the model's full turn verbatim — a burst of
 * {@code llm_usage}/{@code llm_reasoning}/{@code llm_output} frames, then {@code message} frame(s),
 * then {@code end}. Each is a bare {@code {"event":..,"data":{..}}} object (no
 * {@code custom_rsp_data} wrapper). For REST_VERSATILE we surface this stream <em>as-is</em>,
 * uniformly tagged CONTENT, and derive the round's terminal state <em>separately</em> — from the
 * discrete {@code answer} frame, not from {@code end} (see
 * {@link RestVersatileTransport#deriveTerminalState}).
 *
 * <p>The {@code text} is a best-effort decode for readability ({@code data.text} →
 * {@code data.summary} → {@code data.payload.content} → {@code data.payload.output}); a frame that
 * carries none (e.g. {@code end}, {@code llm_usage}, the bare {@code answer} marker) yields empty
 * text but is still emitted, with its raw payload, so no frame is lost. {@code raw} is always the
 * verbatim frame.
 **/
public final class VersatileEventMapping {

    /** The {@code event} / {@code data.type} value that marks the round's discrete terminal answer. */
    static final String ANSWER = "answer";

    private VersatileEventMapping() {}

    /**
     * Parse one SSE line into a single CONTENT event. {@code null} only for non-{@code data:} lines
     * (SSE framing) — every actual {@code data:} frame is surfaced, including {@code end}.
     */
    public static InboundEvent toEvent(String line) {
        if (line == null) return null;
        String s = line.strip();
        if (!s.startsWith("data:")) return null;
        String payload = s.substring("data:".length());
        if (payload.startsWith(" ")) payload = payload.substring(1);
        if (payload.isEmpty()) return null;
        return InboundEvent.content(textOfFrame(payload), payload);
    }

    /**
     * True if the frame is the gateway's discrete terminal-answer marker — {@code event="answer"}
     * with {@code data.type="answer"} (e.g. {@code {"event":"answer","data":{"type":"answer"}}}).
     * This is the signal that the round is {@code COMPLETED}; a round with no such frame resolves
     * {@code INPUT_REQUIRED}. Used by {@link RestVersatileTransport#deriveTerminalState}; the frame
     * itself is still emitted as CONTENT by {@link #toEvent}.
     */
    static boolean isAnswerFrame(Object raw) {
        if (!(raw instanceof String p) || p.isEmpty()) return false;
        try {
            JsonNode root = JsonUtils.mapper().readTree(p);
            JsonNode wrapper = root.has("custom_rsp_data") ? root.get("custom_rsp_data") : root;
            JsonNode data = wrapper.get("data");
            return ANSWER.equals(textOf(wrapper, "event"))
                    && data != null && ANSWER.equals(textOf(data, "type"));
        } catch (Exception ignore) {
            return false;
        }
    }

    /** Best-effort decode of the carried text; {@code ""} when the frame carries none. */
    private static String textOfFrame(String payload) {
        try {
            JsonNode root = JsonUtils.mapper().readTree(payload);
            JsonNode wrapper = root.has("custom_rsp_data") ? root.get("custom_rsp_data") : root;
            JsonNode data = wrapper.get("data");
            JsonNode p = (data != null) ? data.get("payload") : null;
            String t;
            if ((t = textOf(data, "text")) != null && !t.isEmpty()) return t;
            if ((t = textOf(data, "summary")) != null && !t.isEmpty()) return t;
            if ((t = textOf(p, "content")) != null && !t.isEmpty()) return t;
            if ((t = textOf(p, "output")) != null && !t.isEmpty()) return t;
        } catch (Exception ignore) {
            // non-JSON data line — fall through to empty text; the raw payload still carries it.
        }
        return "";
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode n = (node != null && node.has(field)) ? node.get(field) : null;
        if (n == null || n.isNull()) return null;
        return n.isTextual() ? n.asText() : n.toString();
    }
}

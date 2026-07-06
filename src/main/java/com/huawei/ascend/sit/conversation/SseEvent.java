package com.huawei.ascend.sit.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.ascend.sit.utils.JsonUtils;

import java.io.IOException;
import java.util.Map;

/**
 * One parsed SSE event from the edpa gateway stream.
 *
 * <p>Wire format is {@code data:{"event":...,"data":{...}}\n\n} (mock serves the bare shape; the
 * edpa runtime wraps payload under {@code custom_rsp_data}). {@link #parse(String)} accepts a single
 * {@code data:} line and tolerates both shapes, stripping an optional space after the prefix.
 * Non-{@code data:} lines (event:, id:, comments, blanks) return {@code null}.
 */
public record SseEvent(String event, Map<String, Object> data) {

    /** Parse one SSE line; return null for non-{@code data:} lines. */
    public static SseEvent parse(String line) {
        if (line == null) return null;
        String s = line.strip();
        if (!s.startsWith("data:")) return null;
        String payload = s.substring("data:".length());
        if (payload.startsWith(" ")) payload = payload.substring(1);
        if (payload.isEmpty()) return null;
        try {
            JsonNode root = JsonUtils.mapper().readTree(payload);
            JsonNode wrapper = root.has("custom_rsp_data") ? root.get("custom_rsp_data") : root;
            String event = wrapper.has("event") ? wrapper.get("event").asText() : "";
            Map<String, Object> data = toMap(wrapper.get("data"));
            return new SseEvent(event, data);
        } catch (IOException ioe) {
            throw new RuntimeException("SSE parse failed: " + payload, ioe);
        }
    }

    /** Convenience: the {@code data.text} field, or null if absent. */
    public String text() {
        Object t = data == null ? null : data.get("text");
        return t == null ? null : t.toString();
    }

    /** Accessor for the {@code data} map (alias for the record's {@link #data()}). */
    public Map<String, Object> dataAsMap() {
        return data;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) return Map.of();
        return JsonUtils.mapper().convertValue(node, Map.class);
    }
}

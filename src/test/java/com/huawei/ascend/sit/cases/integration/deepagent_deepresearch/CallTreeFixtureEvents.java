package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import java.util.List;
import java.util.Map;

/**
 * Builder for A2A JSON-RPC SSE fixture events used by FEAT-026 call-tree tests.
 *
 * <p>Each method returns a complete SSE {@code data:} payload string (JSON-RPC 2.0 response
 * wrapping a {@link org.a2aproject.sdk.spec.StreamingEventKind}). The mock serves these as
 * {@code data: <payload>\n\n} lines in a {@code text/event-stream} response.
 *
 * <p>Wire shape (confirmed by反编译 SDK 1.0.0.Final):
 * <pre>{@code
 * {"jsonrpc":"2.0","id":"<id>","result":{
 *   "kind":"artifactUpdate",   // or "statusUpdate"
 *   "taskId":"<tid>","contextId":"<ctx>",
 *   "artifact":{"artifactId":"...","parts":[{"type":"text","text":"..."}],
 *               "metadata":{"agentEvent":{"type":"delegation","source":{...},"target":{...}}}},
 *   "append":false,"lastChunk":false,"metadata":{}
 * }}
 * }</pre>
 */
final class CallTreeFixtureEvents {

    private CallTreeFixtureEvents() {}

    private static final String JSONRPC = "2.0";

    /** Build a status-update SSE event payload. */
    static String statusUpdate(String id, String taskId, String contextId, String state) {
        return statusUpdate(id, taskId, contextId, state, false);
    }

    /** Build a status-update SSE event payload with explicit final flag. */
    static String statusUpdate(String id, String taskId, String contextId, String state, boolean isFinal) {
        return jsonrpcResponse(id, "{\"kind\":\"status-update\",\"taskId\":\"" + esc(taskId)
                + "\",\"contextId\":\"" + esc(contextId)
                + "\",\"status\":{\"state\":\"" + esc(state) + "\"}"
                + ",\"final\":" + isFinal
                + ",\"id\":\"" + esc(taskId) + "\"}");
    }

    /** Build a status-update with a message (for input_required prompts). */
    static String statusUpdateWithMessage(String id, String taskId, String contextId,
                                           String state, String messageText) {
        return jsonrpcResponse(id, "{\"kind\":\"status-update\",\"taskId\":\"" + esc(taskId)
                + "\",\"contextId\":\"" + esc(contextId)
                + "\",\"status\":{\"state\":\"" + esc(state) + "\",\"message\":{\"role\":\"agent\","
                + "\"parts\":[{\"type\":\"text\",\"text\":\"" + esc(messageText) + "\"}]}}"
                + ",\"final\":true"
                + ",\"id\":\"" + esc(taskId) + "\"}");
    }

    /** Build an artifact-update SSE event with an agentEvent in artifact.metadata. */
    static String artifactWithAgentEvent(String id, String taskId, String contextId,
                                          String artifactId, String text,
                                          String agentEventType,
                                          String sourceAgentId, String sourceTaskId,
                                          String targetAgentId, String targetTaskId,
                                          boolean append, boolean lastChunk) {
        String agentEvent = buildAgentEvent(agentEventType, sourceAgentId, sourceTaskId,
                targetAgentId, targetTaskId);
        String artifact = "{\"artifactId\":\"" + esc(artifactId) + "\","
                + "\"parts\":[{\"type\":\"text\",\"text\":\"" + esc(text) + "\"}],"
                + "\"metadata\":{\"agentEvent\":" + agentEvent + "}}";
        return artifactUpdateResult(id, taskId, contextId, artifact, append, lastChunk, null);
    }

    /** Build an artifact-update with custom metadata (for malformed/conflict fixtures). */
    static String artifactWithMetadata(String id, String taskId, String contextId,
                                        String artifactId, String text,
                                        String metadataJson,
                                        boolean append, boolean lastChunk) {
        String artifact = "{\"artifactId\":\"" + esc(artifactId) + "\","
                + "\"parts\":[{\"type\":\"text\",\"text\":\"" + esc(text) + "\"}],"
                + "\"metadata\":" + metadataJson + "}";
        return artifactUpdateResult(id, taskId, contextId, artifact, append, lastChunk, null);
    }

    /** Build an artifact-update with raw parts JSON (for DataPart / oversized fixtures). */
    static String artifactWithRawParts(String id, String taskId, String contextId,
                                        String artifactId, String partsJson,
                                        String metadataJson,
                                        boolean append, boolean lastChunk) {
        String artifact = "{\"artifactId\":\"" + esc(artifactId) + "\","
                + "\"parts\":" + partsJson + ","
                + "\"metadata\":" + (metadataJson != null ? metadataJson : "{}") + "}";
        return artifactUpdateResult(id, taskId, contextId, artifact, append, lastChunk, null);
    }

    /** Build an artifact-update with agentEvent and extra metadata fields. */
    static String artifactWithAgentEventAndMetadata(String id, String taskId, String contextId,
                                                      String artifactId, String text,
                                                      String agentEventType,
                                                      String sourceAgentId, String sourceTaskId,
                                                      String targetAgentId, String targetTaskId,
                                                      boolean append, boolean lastChunk,
                                                      String extraMetadataJson) {
        String agentEvent = buildAgentEvent(agentEventType, sourceAgentId, sourceTaskId,
                targetAgentId, targetTaskId);
        String metadata = mergeJson("{\"agentEvent\":" + agentEvent + "}",
                extraMetadataJson != null ? extraMetadataJson : "{}");
        String artifact = "{\"artifactId\":\"" + esc(artifactId) + "\","
                + "\"parts\":[{\"type\":\"text\",\"text\":\"" + esc(text) + "\"}],"
                + "\"metadata\":" + metadata + "}";
        return artifactUpdateResult(id, taskId, contextId, artifact, append, lastChunk, null);
    }

    /** Build a simple text artifact-update without agentEvent (root output). */
    static String textArtifact(String id, String taskId, String contextId,
                                String artifactId, String text,
                                boolean append, boolean lastChunk) {
        String artifact = "{\"artifactId\":\"" + esc(artifactId) + "\","
                + "\"parts\":[{\"type\":\"text\",\"text\":\"" + esc(text) + "\"}],"
                + "\"metadata\":{}}";
        return artifactUpdateResult(id, taskId, contextId, artifact, append, lastChunk, null);
    }

    /** Build a raw JSON-RPC response from a pre-built result string. */
    static String rawEvent(String id, String resultJson) {
        return jsonrpcResponse(id, resultJson);
    }

    // ---- internal helpers ----

    private static String jsonrpcResponse(String id, String resultJson) {
        return "{\"jsonrpc\":\"" + JSONRPC + "\",\"id\":\"" + esc(id) + "\",\"result\":"
                + resultJson + "}";
    }

    private static String artifactUpdateResult(String id, String taskId, String contextId,
                                                String artifactJson,
                                                boolean append, boolean lastChunk,
                                                String metadataJson) {
        String result = "{\"kind\":\"artifact-update\",\"taskId\":\"" + esc(taskId) + "\","
                + "\"contextId\":\"" + esc(contextId) + "\","
                + "\"artifact\":" + artifactJson + ","
                + "\"append\":" + append + ","
                + "\"lastChunk\":" + lastChunk
                + (metadataJson != null ? ",\"metadata\":" + metadataJson : "")
                + "}";
        return jsonrpcResponse(id, result);
    }

    private static String buildAgentEvent(String type, String sourceAgentId, String sourceTaskId,
                                           String targetAgentId, String targetTaskId) {
        StringBuilder sb = new StringBuilder("{\"type\":\"").append(esc(type)).append("\"");
        if (sourceAgentId != null || sourceTaskId != null) {
            sb.append(",\"source\":{");
            boolean first = true;
            if (sourceAgentId != null) {
                sb.append("\"agentId\":\"").append(esc(sourceAgentId)).append("\"");
                first = false;
            }
            if (sourceTaskId != null) {
                if (!first) sb.append(",");
                sb.append("\"taskId\":\"").append(esc(sourceTaskId)).append("\"");
            }
            sb.append("}");
        }
        if (targetAgentId != null || targetTaskId != null) {
            sb.append(",\"target\":{");
            boolean first = true;
            if (targetAgentId != null) {
                sb.append("\"agentId\":\"").append(esc(targetAgentId)).append("\"");
                first = false;
            }
            if (targetTaskId != null) {
                if (!first) sb.append(",");
                sb.append("\"taskId\":\"").append(esc(targetTaskId)).append("\"");
            }
            sb.append("}");
        }
        sb.append("}");
        return sb.toString();
    }

    /** Naive merge of two JSON objects (key-level, first wins for duplicate keys). */
    @SuppressWarnings("unchecked")
    private static String mergeJson(String json1, String json2) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> m1 = mapper.readValue(json1, Map.class);
            Map<String, Object> m2 = mapper.readValue(json2, Map.class);
            for (Map.Entry<String, Object> e : m2.entrySet()) {
                m1.putIfAbsent(e.getKey(), e.getValue());
            }
            return mapper.writeValueAsString(m1);
        } catch (Exception e) {
            return json1;
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}

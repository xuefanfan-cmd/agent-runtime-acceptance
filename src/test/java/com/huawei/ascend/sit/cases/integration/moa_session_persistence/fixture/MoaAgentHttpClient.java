/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.moa_session_persistence.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * HTTP client for the moa-agent-demo L1 SUT. Issues A2A JSON-RPC
 * {@code SendStreamingMessage} requests via {@code POST /a2a/}.
 *
 * <p>The L1 agent exposes the standard agent-service-app A2A endpoint at
 * {@code POST /a2a/} which accepts JSON-RPC 2.0 envelopes. The response is
 * SSE ({@code text/event-stream}) with {@code event:jsonrpc} frames.
 *
 * <p>The client supports:
 * <ul>
 *   <li>Reusing a {@code contextId} across requests to simulate returning to
 *       the same session (recovery scenario).</li>
 *   <li>Generating a new {@code contextId} for fresh-session scenarios.</li>
 *   <li>Extracting the final task state, artifact text, and contextId from
 *       the SSE stream.</li>
 * </ul>
 *
 * <p>This client intentionally uses raw HTTP rather than the A2A SDK —
 * the test needs fine-grained control over contextId and message content.
 */
public final class MoaAgentHttpClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String baseUrl;

    public MoaAgentHttpClient(String baseUrl) {
        this.baseUrl = stripTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
    }

    /**
     * Sends a streaming message and returns the aggregated response.
     *
     * @param prompt     the user message text
     * @param contextId  the session context ID — reuse across calls to stay in
     *                   the same session; pass {@code null} for a fresh session
     * @param timeout    overall request timeout
     */
    public A2aResponse sendStreamingMessage(String prompt, String contextId, Duration timeout) throws Exception {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(timeout, "timeout");

        String ctx = (contextId != null && !contextId.isBlank())
                ? contextId
                : "sit-" + UUID.randomUUID();

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "ROLE_USER");
        message.put("parts", List.of(Map.of("kind", "text", "text", prompt)));
        message.put("contextId", ctx);
        message.put("messageId", "sit-msg-" + UUID.randomUUID());

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("message", message);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", "sit-" + UUID.randomUUID());
        body.put("method", "SendStreamingMessage");
        body.put("params", params);

        String json = MAPPER.writeValueAsString(body);

        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/a2a/"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream, application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        // Use sendAsync with orTimeout for reliable timeout on SSE streams.
        // HttpRequest.timeout() does not reliably abort a streaming body read
        // in JDK 21 — the connection is established quickly but the SSE body
        // may stay open indefinitely if the server doesn't close it.
        // sendAsync().orTimeout() cancels the future after the deadline.
        try {
            HttpResponse<String> resp = http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .orTimeout(timeout.toSeconds(), TimeUnit.SECONDS)
                    .join();
            return parse(resp, ctx);
        } catch (CompletionException e) {
            if (e.getCause() instanceof TimeoutException) {
                return new A2aResponse(0, ctx, "TIMEOUT", null, null, "");
            }
            if (e.getCause() instanceof ConnectException ce) {
                // Retry once after a short delay — the SUT process may still be binding
                try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                try {
                    HttpResponse<String> resp = http.sendAsync(
                                    HttpRequest.newBuilder(URI.create(baseUrl + "/a2a/"))
                                            .timeout(timeout)
                                            .header("Content-Type", "application/json")
                                            .header("Accept", "text/event-stream, application/json")
                                            .POST(HttpRequest.BodyPublishers.ofString(json))
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .orTimeout(timeout.toSeconds(), TimeUnit.SECONDS)
                            .join();
                    return parse(resp, ctx);
                } catch (CompletionException e2) {
                    if (e2.getCause() instanceof TimeoutException) {
                        return new A2aResponse(0, ctx, "TIMEOUT", null, null, "");
                    }
                    throw e2;
                }
            }
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    /**
     * Convenience: send a "继续" (continue) message in the same session.
     */
    public A2aResponse continueSession(String contextId, Duration timeout) throws Exception {
        return sendStreamingMessage("继续", contextId, timeout);
    }

    private A2aResponse parse(HttpResponse<String> resp, String contextId) {
        int status = resp.statusCode();
        String raw = resp.body();
        if (raw == null || raw.isBlank()) {
            return new A2aResponse(status, contextId, null, null, null, raw);
        }
        return parseSse(status, raw, contextId);
    }

    private A2aResponse parseSse(int status, String raw, String contextId) {
        String taskState = null;
        String artifactText = null;
        String effectiveContextId = contextId;

        for (String line : raw.split("\n")) {
            String stripped = line.strip();
            if (!stripped.startsWith("data:")) {
                continue;
            }
            String payload = stripped.substring(5).strip();
            if (payload.isEmpty()) {
                continue;
            }
            JsonNode evt;
            try {
                evt = MAPPER.readTree(payload);
            } catch (Exception notJson) {
                continue;
            }

            // Check for error responses
            JsonNode errorNode = evt.path("error");
            if (!errorNode.isMissingNode()) {
                taskState = "ERROR:" + errorNode.path("code").asInt(-1);
                continue;
            }

            // Extract task state from status update events
            JsonNode resultNode = evt.path("result");
            if (!resultNode.isMissingNode()) {
                // A2A SSE format: result.statusUpdate.status.state
                JsonNode statusNode = resultNode.path("statusUpdate").path("status");
                if (!statusNode.isMissingNode()) {
                    JsonNode stateNode = statusNode.path("state");
                    if (!stateNode.isMissingNode() && !stateNode.asText("").isBlank()) {
                        taskState = stateNode.asText();
                    }
                }
                // Extract contextId from statusUpdate or artifactUpdate
                JsonNode ctxInUpdate = resultNode.path("statusUpdate").path("contextId");
                if (!ctxInUpdate.isMissingNode() && !ctxInUpdate.asText("").isBlank()) {
                    effectiveContextId = ctxInUpdate.asText();
                }
                JsonNode ctxInArtifact = resultNode.path("artifactUpdate").path("contextId");
                if (!ctxInArtifact.isMissingNode() && !ctxInArtifact.asText("").isBlank()) {
                    effectiveContextId = ctxInArtifact.asText();
                }
                // Extract artifact text from artifactUpdate (not "artifacts")
                JsonNode artifactUpdateNode = resultNode.path("artifactUpdate");
                if (!artifactUpdateNode.isMissingNode()) {
                    JsonNode artifactNode = artifactUpdateNode.path("artifact");
                    if (!artifactNode.isMissingNode()) {
                        JsonNode partsNode = artifactNode.path("parts");
                        if (partsNode.isArray() && !partsNode.isEmpty()) {
                            // Look for text part
                            for (JsonNode part : partsNode) {
                                JsonNode textNode = part.path("text");
                                if (!textNode.isMissingNode() && !textNode.asText("").isBlank()) {
                                    artifactText = textNode.asText();
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }

        return new A2aResponse(status, effectiveContextId, taskState, artifactText, null, raw);
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * Aggregated A2A response from the SSE stream.
     *
     * @param statusCode       HTTP status code
     * @param contextId        effective context ID (from request or response)
     * @param finalTaskState   final task state (e.g. COMPLETED, WORKING, ERROR:code)
     * @param artifactText     text from the first artifact (if any)
     * @param errorJson        error JSON if response was an error (currently null)
     * @param rawBody          full raw response body for diagnostics
     */
    public record A2aResponse(
            int statusCode,
            String contextId,
            String finalTaskState,
            String artifactText,
            String errorJson,
            String rawBody) {
    }
}

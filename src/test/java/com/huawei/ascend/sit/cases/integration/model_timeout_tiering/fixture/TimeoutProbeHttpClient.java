/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.model_timeout_tiering.fixture;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * HTTP client for the FEAT-058 timeout-tiering-model-call-demo
 * {@code timeout-probe-agent} SUT. Issues {@code POST /v1/query} with body
 * {@code {"message":"<prompt>"}} per demo README.
 *
 * <p>The returned {@link QueryResponse} exposes:
 * <ul>
 *   <li>{@code resultType} / {@code output} — the two fields SIT asserts on
 *       success (unchanged from FEAT-057 ReActAgent surface);
 *   <li>{@code errorStatusCode} — the SDK status code embedded in the
 *       error envelope (SIT asserts {@code 181008} for timeout TCs per
 *       {@code StatusCode.MODEL_CALL_TIMEOUT});
 *   <li>{@code errorDetails} — the structured {@code details} object (map)
 *       carrying the four-field FEAT-058 contract: {@code phase},
 *       {@code timeout_seconds}, {@code layer}, {@code stream};
 *   <li>{@code rawBody} — full response for diagnostic dumping.
 * </ul>
 *
 * <p>This client intentionally does NOT go through the A2A SDK — probe-agent
 * HTTP entry is a plain POST surface, not JSON-RPC. (Same contract as the
 * FEAT-057 ReactAgentHttpClient.)
 *
 * <p>The optional {@code requestTimeout} on {@link #query} doubles as a
 * caller-side cancel knob for TC-CANCEL-NOT-TIMEOUT: pass a short duration
 * (e.g. 3s) to force the driver to abort the request mid-flight; the
 * resulting {@link java.net.http.HttpTimeoutException} is wrapped as
 * {@link QueryResponse#callerAborted}.
 */
public final class TimeoutProbeHttpClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String baseUrl;

    public TimeoutProbeHttpClient(String baseUrl) {
        this.baseUrl = stripTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
    }

    /**
     * One-shot blocking query. {@code requestTimeout} must cover the full
     * invoke; for timeout TCs set it to 2x the maximum expected SDK timeout.
     * If the caller intentionally cancels mid-flight, the returned response
     * has {@link QueryResponse#callerAborted} set to {@code true} and all
     * other fields are null.
     */
    public QueryResponse query(String prompt, Duration requestTimeout) throws Exception {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(requestTimeout, "requestTimeout");

        Map<String, Object> body = Map.of("message", prompt);
        String json = MAPPER.writeValueAsString(body);

        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/query"))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return parse(resp);
        } catch (java.net.http.HttpTimeoutException callerAborted) {
            return QueryResponse.aborted(callerAborted.getMessage());
        }
    }

    private QueryResponse parse(HttpResponse<String> resp) throws Exception {
        int status = resp.statusCode();
        String raw = resp.body();
        String output = null;
        String resultType = null;
        Integer errorStatusCode = null;
        Map<String, Object> errorDetails = null;
        Map<String, Object> parsed = null;
        if (raw != null && !raw.isBlank()) {
            try {
                JsonNode root = MAPPER.readTree(raw);
                if (root.has("output") && !root.get("output").isNull()) {
                    output = root.get("output").asText();
                }
                if (root.has("result_type") && !root.get("result_type").isNull()) {
                    resultType = root.get("result_type").asText();
                }
                JsonNode errorNode = root.get("error");
                if (errorNode == null || errorNode.isNull()) {
                    errorNode = root;
                }
                if (errorNode.has("status_code") && errorNode.get("status_code").isNumber()) {
                    errorStatusCode = errorNode.get("status_code").asInt();
                } else if (errorNode.has("code") && errorNode.get("code").isNumber()) {
                    errorStatusCode = errorNode.get("code").asInt();
                }
                JsonNode detailsNode = errorNode.get("details");
                if (detailsNode != null && detailsNode.isObject()) {
                    errorDetails = MAPPER.convertValue(detailsNode, new TypeReference<>() {});
                }
                parsed = MAPPER.convertValue(root, new TypeReference<>() {});
            } catch (Exception notJson) {
                // leave fields null; caller inspects rawBody for diagnostics.
            }
        }
        return new QueryResponse(status, output, resultType, errorStatusCode, errorDetails,
                raw, parsed, false, null);
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    public record QueryResponse(
            int statusCode,
            String output,
            String resultType,
            Integer errorStatusCode,
            Map<String, Object> errorDetails,
            String rawBody,
            Map<String, Object> parsedBody,
            boolean callerAborted,
            String callerAbortReason) {

        static QueryResponse aborted(String reason) {
            return new QueryResponse(-1, null, null, null, null, null, null, true, reason);
        }

        public boolean isTimeoutError() {
            return errorStatusCode != null && errorStatusCode == 181008;
        }

        public String detailsString(String key) {
            if (errorDetails == null) {
                return null;
            }
            Object v = errorDetails.get(key);
            return v == null ? null : v.toString();
        }

        public Double detailsDouble(String key) {
            if (errorDetails == null) {
                return null;
            }
            Object v = errorDetails.get(key);
            if (v instanceof Number n) {
                return n.doubleValue();
            }
            if (v instanceof String s) {
                try {
                    return Double.parseDouble(s);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return null;
        }

        public Boolean detailsBoolean(String key) {
            if (errorDetails == null) {
                return null;
            }
            Object v = errorDetails.get(key);
            if (v instanceof Boolean b) {
                return b;
            }
            return null;
        }
    }
}

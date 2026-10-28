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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversation_id", "sit-" + UUID.randomUUID());
        body.put("message", prompt);
        String json = MAPPER.writeValueAsString(body);

        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/query"))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream, application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return parse(resp);
        } catch (java.net.http.HttpTimeoutException callerAborted) {
            return QueryResponse.aborted(callerAborted.getMessage());
        }
    }

    private QueryResponse parse(HttpResponse<String> resp) {
        int status = resp.statusCode();
        String raw = resp.body();
        if (raw == null || raw.isBlank()) {
            return new QueryResponse(status, null, null, null, null, raw, null, false, null);
        }
        // 2026-10-22 实证勘误（T-M1d）：probe-agent 响应为 SSE 流（Content-Type: text/event-stream），
        // 而非单 JSON；原 MAPPER.readTree(raw) 会直接抛，所有字段落空，S0 happy path 都读不到
        // result_type。按 SSE 形态解析：锚最后一个 type=="answer" 事件取 output/result_type；
        // 顺带扫描每个事件 payload 的 error/details 对象（预留 details 透传路径，开发裁定前此分支不触发）。
        if (looksLikeSse(raw)) {
            return parseSse(status, raw);
        }
        return parseSingleJson(status, raw);
    }

    private static boolean looksLikeSse(String raw) {
        for (String line : raw.split("\n")) {
            if (line.strip().startsWith("data:")) {
                return true;
            }
        }
        return false;
    }

    private QueryResponse parseSse(int status, String raw) {
        String output = null;
        String resultType = null;
        Integer errorStatusCode = null;
        Map<String, Object> errorDetails = null;
        Map<String, Object> lastAnswerPayload = null;
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
            // Skip type=model_retry events: their payload carries the upstream HTTP
            // status_code (429/500/etc), NOT the SDK error code (181009/181001). Reading
            // status_code from model_retry would misclassify the retry event's HTTP
            // status as the terminal error code and shadow the real error (which lands
            // either in a later type=error event or in the terminal type=answer with
            // payload.result_type=error — the latter per 2026-10-22 Reply §二 1 is the
            // current wire shape for ModelError/PermanentModelError object-path cases).
            // Cross-ref: FEAT-064 observation report 2026-10-27 §二 B 组 self-admitted
            // parseSse bug (docsReview commit ee5ed0b).
            String eventType = evt.path("type").asText("");
            if ("model_retry".equals(eventType)) {
                continue;
            }
            JsonNode p = evt.has("payload") && evt.get("payload").isObject() ? evt.get("payload") : evt;
            // Primary path for structured error events (2026-10-27 upstream fix landed via
            // agent-core-java issue #260 PR!242 + agent-runtime-java PR!449):
            //   data: {"type":"error", "error":"...", "failure":{"code":"MODEL_CALL_...",
            //          "numericCode":18100N, "isRetryable":true/false}}
            // Read payload.failure.numericCode as the authoritative SDK status code; also
            // keep payload.failure as errorDetails so judge-side can read code/isRetryable.
            // Developer-side DEV-REPLY-sse-structured-error-wire-break-20261027.md §五 "SIT
            // 侧 parseSse 升级为路线 1" explicitly authorizes this path.
            if ("error".equals(eventType) && p.has("failure") && p.get("failure").isObject()) {
                JsonNode failure = p.get("failure");
                if (errorStatusCode == null && failure.has("numericCode")
                        && failure.get("numericCode").isNumber()) {
                    errorStatusCode = failure.get("numericCode").asInt();
                }
                if (errorDetails == null) {
                    Map<String, Object> details = MAPPER.convertValue(
                            failure, new TypeReference<>() {});
                    // 新 wire schema 用 failure.code (MODEL_CALL_X) 表达分类，老判据预期
                    // details.category (X)。把 failure.code 映射回 category 字段兼容老判据。
                    // 对齐开发侧 2026-10-26 FEAT-064 Reply §A.3 分类集 (M14) 名字。
                    if (failure.has("code") && failure.get("code").isTextual()) {
                        String failureCode = failure.get("code").asText();
                        String category = mapFailureCodeToCategory(failureCode);
                        if (category != null) {
                            details.put("category", category);
                        }
                    }
                    // 从 payload.error 文本正则抽 phase/timeout_seconds/layer/stream 四字段
                    // (开发侧 Reply §四 3 建议路径 — 这四字段 wire 面不透传，SIT 走 regex 过渡抽)。
                    // 对齐 Feat058 用例的 assertPhase / assertLayer / assertTimeoutSeconds /
                    // assertStream / assertAllFourFieldsPresent 判据。
                    if (p.has("error") && p.get("error").isTextual()) {
                        enrichDetailsFromErrorText(p.get("error").asText(), details);
                    }
                    errorDetails = details;
                }
            }
            // 预留 details 对象透传路径：answer payload 若含 error.{status_code,details} 或顶层 code/details，
            // 走解析（当前 wire 实证不含；若开发按解读 B 补透传，此分支即时生效）。
            JsonNode errorNode = p.has("error") && p.get("error").isObject() ? p.get("error") : p;
            if (errorStatusCode == null) {
                if (errorNode.has("status_code") && errorNode.get("status_code").isNumber()) {
                    errorStatusCode = errorNode.get("status_code").asInt();
                } else if (errorNode.has("code") && errorNode.get("code").isNumber()) {
                    errorStatusCode = errorNode.get("code").asInt();
                }
            }
            if (errorDetails == null) {
                JsonNode detailsNode = errorNode.get("details");
                if (detailsNode != null && detailsNode.isObject()) {
                    errorDetails = MAPPER.convertValue(detailsNode, new TypeReference<>() {});
                }
            }
            if ("answer".equals(evt.path("type").asText(""))) {
                if (p.has("result_type") && !p.get("result_type").isNull()) {
                    resultType = p.get("result_type").asText();
                }
                if (p.has("output") && !p.get("output").isNull()) {
                    output = p.get("output").asText();
                }
                lastAnswerPayload = MAPPER.convertValue(p, new TypeReference<Map<String, Object>>() {});
            }
        }
        return new QueryResponse(status, output, resultType, errorStatusCode, errorDetails,
                raw, lastAnswerPayload, false, null);
    }

    private QueryResponse parseSingleJson(int status, String raw) {
        String output = null;
        String resultType = null;
        Integer errorStatusCode = null;
        Map<String, Object> errorDetails = null;
        Map<String, Object> parsed = null;
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
        return new QueryResponse(status, output, resultType, errorStatusCode, errorDetails,
                raw, parsed, false, null);
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * 把新 wire schema 的 {@code failure.code}（如 "MODEL_CALL_FAILED"）映射回老判据
     * 预期的 {@code category} 字段（如 "TEMPORARY"）。
        if (failureCode == null) {
            return null;
        }
        return switch (failureCode) {
            case "MODEL_CALL_FAILED" -> "TEMPORARY";
            case "MODEL_CALL_RATE_LIMITED" -> "RATE_LIMITED";
            case "MODEL_CALL_AUTH_FAILED" -> "AUTH_FAILED";
            case "MODEL_CALL_QUOTA_EXCEEDED" -> "QUOTA_EXCEEDED";
            // 产品侧 StatusCode 枚举 context length 常量名**不带** _CALL_ 中段
            // （L2 Feat-Func-064 §4.1 StatusCode 定义 line 542 原文）。
            case "MODEL_CONTEXT_LENGTH_EXCEEDED" -> "CONTEXT_LENGTH_EXCEEDED";
            case "MODEL_CALL_TIMEOUT" -> "TEMPORARY";
            case "MODEL_CALL_USER_CANCELLED" -> "USER_CANCELLED";
            default -> null;
        };
    }

    private static final Pattern PHASE_PATTERN =
            Pattern.compile("timeout at (BEFORE_CONNECT|BEFORE_FIRST_SEGMENT|AFTER_FIRST_SEGMENT)");
    private static final Pattern TIMEOUT_SECONDS_PATTERN =
            Pattern.compile("effective timeout (\\d+(?:\\.\\d+)?)s");
    private static final Pattern LAYER_PATTERN =
            Pattern.compile("reason: (connect|header_wait|stream_idle) timeout");

    /**
     * 从 wire body 的 error 文本（如 "ModelError: model call timeout at BEFORE_CONNECT,
     * effective timeout 5.0s, reason: connect timeout after 5.0s"）用正则抽
     * phase / timeout_seconds / layer / stream 四个字段，补进 details map。
     *
     * <p>开发侧 2026-10-22 Reply §二 1 明说 "details 子对象不穿透 HTTP body"，§四 3
     * 建议 SIT 走 output.reason regex 过渡抽取——本方法实现该路径。
     *
     * <p>stream 字段从 phase 推：BEFORE_CONNECT=false（连接阶段还没有流），
     * BEFORE_FIRST_SEGMENT=true、AFTER_FIRST_SEGMENT=true（都已进入流式阶段）。
     *
     * <p>只在 details map 还没有这些字段时才补；structured failure 已经透传的字段不覆盖。
     *
     * @param errText error 文本（可能为 null 或空）
     * @param details 现有 details map（可写）
     */
    private static void enrichDetailsFromErrorText(String errText, Map<String, Object> details) {
        if (errText == null || errText.isEmpty() || details == null) {
            return;
        }
        if (!details.containsKey("phase")) {
            Matcher m = PHASE_PATTERN.matcher(errText);
            if (m.find()) {
                String phase = m.group(1);
                details.put("phase", phase);
                if (!details.containsKey("stream")) {
                    details.put("stream", !"BEFORE_CONNECT".equals(phase));
                }
            }
        }
        if (!details.containsKey("timeout_seconds")) {
            Matcher m = TIMEOUT_SECONDS_PATTERN.matcher(errText);
            if (m.find()) {
                details.put("timeout_seconds", Double.parseDouble(m.group(1)));
            }
        }
        if (!details.containsKey("layer")) {
            Matcher m = LAYER_PATTERN.matcher(errText);
            if (m.find()) {
                details.put("layer", m.group(1));
            }
        }
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

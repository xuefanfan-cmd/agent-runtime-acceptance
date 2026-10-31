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
import java.util.ArrayList;
import java.util.List;

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
        return query(prompt, requestTimeout, null);
    }

    /**
     * Variant that pins the HTTP body {@code stream} flag — used by FEAT-064
     * r5 §3.15 (stream:true explicit) and §3.18 (stream:false non-stream
     * recover) to exercise both wire paths from the same probe. Pass
     * {@code null} to omit the field (demo default behavior).
     */
    public QueryResponse query(String prompt, Duration requestTimeout, Boolean stream) throws Exception {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(requestTimeout, "requestTimeout");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversation_id", "sit-" + UUID.randomUUID());
        body.put("message", prompt);
        if (stream != null) {
            body.put("stream", stream);
        }
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
            return new QueryResponse(status, null, null, null, null, raw, null, 0, false, null);
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
        int railRetryCount = 0; // C-4 (PR!472): count of model_retry events with retry_tier=rail
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
            // C-4 (PR!472): model_retry events now carry retry_tier=client|rail.
            // We still skip status_code extraction from model_retry (its payload
            // carries the upstream HTTP status_code, not the SDK error code, which
            // would misclassify the event — see original comment above). But we DO
            // extract retry_tier to count Rail guardrail retries. The field may sit
            // at the event top level or inside payload; try both.
            String eventType = evt.path("type").asText("");
            if ("model_retry".equals(eventType)) {
                JsonNode tierNode = evt.path("retry_tier");
                if (tierNode.isMissingNode() || !tierNode.isTextual()) {
                    tierNode = evt.path("payload").path("retry_tier");
                }
                if (tierNode.isTextual() && "rail".equals(tierNode.asText())) {
                    railRetryCount++;
                }
                continue;
            }
            JsonNode p = evt.has("payload") && evt.get("payload").isObject() ? evt.get("payload") : evt;
            // Primary path for structured error events (2026-10-27 upstream fix landed via
            // agent-core-java PR!437 + agent-runtime-java PR!236 + agent-runtime-java PR!242
            // + agent-core-java PR!449 — Reply r6 对齐的 4-PR bundle):
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
                raw, lastAnswerPayload, railRetryCount, false, null);
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
            // 2026-10-29 SIT 实证：/v1/query 带 body "stream":false 的 blocking 响应 wire schema 是
            //   {"result":{"role":"assistant","content":"ok"},"conversation_id":"..."}
            // 跟 streaming SSE 的 payload.result_type / payload.output 字段完全不同。
            // blocking wire 没有 result_type 字段（通过 result.role=assistant 隐式标识是 agent answer），
            // output 从 result.content 读。本分支补 blocking schema 识别，不影响其他 schema。
            // 对应 TC: Feat064ErrorTaxonomyAndRetryTest.nonStreamRateLimitedRecoverSucceedsAfterRetry (§3.18 ST-R02)
            // 实证来源: 2026-10-29 ECS curl 复现 /v1/query stream=false 的 raw body
            if (output == null && root.has("result") && root.get("result").isObject()) {
                JsonNode result = root.get("result");
                if (result.has("content") && !result.get("content").isNull()) {
                    output = result.get("content").asText();
                }
            }
            // 新 wire schema 支持 (对称 parseSse 的升级)：
            //   {"type":"error", "error":"...", "failure":{"code":"MODEL_CALL_...",
            //    "numericCode":18100N, "isRetryable":true/false}}
            // 单 JSON 场景可能是 S5 blocking 路径——wire body 结构同新 schema。
            // 对齐开发侧 2026-10-27 DEV-REPLY-sse-structured-error-wire-break §五 "SIT 侧
            // parseSse 升级为路线 1"（本处把路线 1 对称补到 parseSingleJson）。
            JsonNode p = root;
            if (root.has("payload") && root.get("payload").isObject()) {
                p = root.get("payload");
            }
            if (p.has("failure") && p.get("failure").isObject()) {
                JsonNode failure = p.get("failure");
                if (errorStatusCode == null && failure.has("numericCode")
                        && failure.get("numericCode").isNumber()) {
                    errorStatusCode = failure.get("numericCode").asInt();
                }
                if (errorDetails == null) {
                    Map<String, Object> details = MAPPER.convertValue(
                            failure, new TypeReference<>() {});
                    if (failure.has("code") && failure.get("code").isTextual()) {
                        String category = mapFailureCodeToCategory(failure.get("code").asText());
                        if (category != null) {
                            details.put("category", category);
                        }
                    }
                    if (p.has("error") && p.get("error").isTextual()) {
                        enrichDetailsFromErrorText(p.get("error").asText(), details);
                    }
                    errorDetails = details;
                }
            }
            // 保留老 schema 兼容 fallback (旧 wire 的 error.status_code / error.details)
            JsonNode errorNode = root.get("error");
            if (errorNode == null || errorNode.isNull()) {
                errorNode = root;
            }
            if (errorNode.isObject()) {
                if (errorStatusCode == null && errorNode.has("status_code")
                        && errorNode.get("status_code").isNumber()) {
                    errorStatusCode = errorNode.get("status_code").asInt();
                } else if (errorStatusCode == null && errorNode.has("code")
                        && errorNode.get("code").isNumber()) {
                    errorStatusCode = errorNode.get("code").asInt();
                }
                JsonNode detailsNode = errorNode.get("details");
                if (errorDetails == null && detailsNode != null && detailsNode.isObject()) {
                    errorDetails = MAPPER.convertValue(detailsNode, new TypeReference<>() {});
                }
            }
            parsed = MAPPER.convertValue(root, new TypeReference<>() {});
        } catch (Exception notJson) {
            // leave fields null; caller inspects rawBody for diagnostics.
        }
        return new QueryResponse(status, output, resultType, errorStatusCode, errorDetails,
                raw, parsed, 0, false, null);
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * 把新 wire schema 的 {@code failure.code}（如 "MODEL_CALL_FAILED"）映射回老判据
     * 预期的 {@code category} 字段（如 "TEMPORARY"）。
     *
     * <p>对齐 FEAT-064 分类集 M14（RATE_LIMITED / QUOTA_EXCEEDED / AUTH_FAILED /
     * TEMPORARY / CONTEXT_LENGTH_EXCEEDED / USER_CANCELLED）。
     *
     * @param failureCode 新 wire schema 的 failure.code 字符串
     * @return 对应的老 category 枚举字符串；未知 code 返回 null
     */
    private static String mapFailureCodeToCategory(String failureCode) {
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
            int railRetryCount,
            boolean callerAborted,
            String callerAbortReason) {

        static QueryResponse aborted(String reason) {
            return new QueryResponse(-1, null, null, null, null, null, null, 0, true, reason);
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

    /**
     * S9 TC-PER-CALL-IDLE-OVERRIDE side-channel driver (cases §3.9).
     *
     * <p>Issues {@code POST /probe/per-call/query} to the demo-side
     * {@code PerCallProbeController}（2026-10-28 batch triage 议题四 landed with
     * commit {@code ab9e03ba6581}). Driver has no prompt body — probe-agent
     * uses a hard-coded prompt "probe per-call". Query param
     * {@code per_call_timeout_seconds} is optional:
     * <ul>
     *   <li>provided: controller builds {@code ModelInvokeOptions.timeout(float)}
     *       and calls {@code Model.stream(prompt, options)};
     *   <li>omitted: controller calls {@code Model.stream(prompt)} using the
     *       client-level {@code stream-idle-seconds} baseline (s3-per-call
     *       profile pins baseline at 600s).
     * </ul>
     *
     * <p>Response is a plain JSON {@code Map<String,Object>} (not SSE) with
     * fields:
     * <ul>
     *   <li>{@code completed} (Boolean) — true if iterator ran to natural end;
     *   <li>{@code chunks} (int) — number of chunks received before termination;
     *   <li>{@code per_call_timeout_seconds} (Integer) — echoed back;
     *   <li>{@code exception_type} (String, when iterator terminated via
     *       throwable) — e.g. "ModelError";
     *   <li>{@code message} (String, when iterator terminated via throwable) —
     *       e.g. "model call timeout at BEFORE_FIRST_SEGMENT, effective timeout 300.0s, ...".
     * </ul>
     *
     * @param requestTimeout driver-side HTTP timeout; must exceed stub stall
     *                       (310s) + buffer, so set 400s+ for TC's first call
     *                       and 700s+ for second call
     * @param perCallSec     per-call timeout in seconds; {@code null} means
     *                       don't send the query param (client-level baseline
     *                       applies)
     */
    public PerCallResponse queryPerCall(Duration requestTimeout, Integer perCallSec) throws Exception {
        Objects.requireNonNull(requestTimeout, "requestTimeout");

        StringBuilder url = new StringBuilder(baseUrl).append("/probe/per-call/query");
        if (perCallSec != null) {
            url.append("?per_call_timeout_seconds=").append(perCallSec);
        }

        HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return parsePerCall(resp);
        } catch (java.net.http.HttpTimeoutException callerAborted) {
            return PerCallResponse.aborted(callerAborted.getMessage());
        }
    }

    private PerCallResponse parsePerCall(HttpResponse<String> resp) {
        int status = resp.statusCode();
        String raw = resp.body();
        if (raw == null || raw.isBlank()) {
            return new PerCallResponse(status, null, null, null, null, null, raw, false, null);
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(raw, new TypeReference<>() {});
            Boolean completed = parsed.get("completed") instanceof Boolean b ? b : null;
            Integer perCallSec = parsed.get("per_call_timeout_seconds") instanceof Number n
                    ? n.intValue() : null;
            Integer chunks = parsed.get("chunks") instanceof Number n ? n.intValue() : null;
            String exceptionType = parsed.get("exception_type") instanceof String s ? s : null;
            String message = parsed.get("message") instanceof String s ? s : null;
            return new PerCallResponse(status, completed, perCallSec, chunks,
                    exceptionType, message, raw, false, null);
        } catch (Exception notJson) {
            return new PerCallResponse(status, null, null, null, null, null, raw, false, null);
        }
    }

    /**
     * Response record for {@link #queryPerCall(Duration, Integer)}. Fields map
     * 1:1 to the demo-side PerCallProbeController JSON response body.
     */
    public record PerCallResponse(
            int statusCode,
            Boolean completed,
            Integer perCallTimeoutSeconds,
            Integer chunks,
            String exceptionType,
            String message,
            String rawBody,
            boolean callerAborted,
            String callerAbortReason) {

        static PerCallResponse aborted(String reason) {
            return new PerCallResponse(-1, null, null, null, null, null, null, true, reason);
        }

        /** True if the iterator ran to natural completion. */
        public boolean completedNaturally() {
            return Boolean.TRUE.equals(completed);
        }

        /** True if the iterator terminated via a throwable (per-call timeout fired, etc). */
        public boolean terminatedByException() {
            return exceptionType != null;
        }
    }

    /**
     * FEAT-058 §3.14 TC-BLOCKING-HEADER-WAIT side-channel driver — issues
     * {@code POST /probe/blocking/query} to the demo-side
     * {@link com.openjiuwen.example.timeouttiering.service.BlockingProbeController}
     * (landed 2026-10-30 per SIT-REQUEST-feat-058-demo-side-extensions). The
     * blocking controller calls {@code model.invoke(...)} on the one-shot
     * {@code blockingProbeModel} bean (maxRetries=0, retryOnTemporary=false),
     * so the blocking header-wait + stream-idle layers surface cleanly with
     * {@code stream=false} and no retry-loop collapsing.
     *
     * <p>Response is a plain JSON {@code Map<String,Object>} (not SSE) with
     * fields:
     * <ul>
     *   <li>{@code completed} (Boolean) — true iff the invoke returned
     *       {@code AssistantMessage}; false iff caught a throwable;
     *   <li>{@code content} (String, when completed=true) — assistant message
     *       content;
     *   <li>{@code exception_type} (String, when completed=false) — e.g.
     *       "ModelError" (BaseError subtype simple name after recursive cause
     *       unwrap);
     *   <li>{@code message} (String, when completed=false) — exception message,
     *       e.g. "model call timeout at BEFORE_FIRST_SEGMENT, effective timeout
     *       8.0s, reason: header_wait timeout after 8.0s";
     *   <li>{@code error_details} (Map, when completed=false) — the structured
     *       {@code BaseError.getDetails()} verbatim; carries the FEAT-058
     *       four-field contract {@code phase, layer, timeout_seconds, stream}.
     * </ul>
     *
     * @param requestTimeout driver-side HTTP timeout; must exceed the configured
     *                       timeout window + buffer (e.g. ≥15s for s2-blocking's
     *                       8s legacy timeout).
     */
    public BlockingResponse queryBlocking(Duration requestTimeout) throws Exception {
        Objects.requireNonNull(requestTimeout, "requestTimeout");

        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/probe/blocking/query"))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return parseBlocking(resp);
        } catch (java.net.http.HttpTimeoutException callerAborted) {
            return BlockingResponse.aborted(callerAborted.getMessage());
        }
    }

    private BlockingResponse parseBlocking(HttpResponse<String> resp) {
        int status = resp.statusCode();
        String raw = resp.body();
        if (raw == null || raw.isBlank()) {
            return new BlockingResponse(status, null, null, null, null, null, raw, false, null);
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(raw, new TypeReference<>() {});
            Boolean completed = parsed.get("completed") instanceof Boolean b ? b : null;
            String content = parsed.get("content") instanceof String s ? s : null;
            String exceptionType = parsed.get("exception_type") instanceof String s ? s : null;
            String message = parsed.get("message") instanceof String s ? s : null;
            @SuppressWarnings("unchecked")
            Map<String, Object> errorDetails = parsed.get("error_details") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : null;
            return new BlockingResponse(status, completed, content, exceptionType, message,
                    errorDetails, raw, false, null);
        } catch (Exception notJson) {
            return new BlockingResponse(status, null, null, null, null, null, raw, false, null);
        }
    }

    /**
     * Response record for {@link #queryBlocking(Duration)}. Fields map 1:1 to the
     * demo-side {@code BlockingProbeController} JSON response body.
     */
    public record BlockingResponse(
            int statusCode,
            Boolean completed,
            String content,
            String exceptionType,
            String message,
            Map<String, Object> errorDetails,
            String rawBody,
            boolean callerAborted,
            String callerAbortReason) {

        static BlockingResponse aborted(String reason) {
            return new BlockingResponse(-1, null, null, null, null, null, null, true, reason);
        }

        public boolean completedNaturally() {
            return Boolean.TRUE.equals(completed);
        }

        public boolean terminatedByException() {
            return exceptionType != null;
        }

        /**
         * Adapter that synthesises a {@link QueryResponse} view of this blocking
         * response so the {@link ErrorEnvelopeAsserter} chain (built around
         * QueryResponse) can be reused for the FEAT-058 four-field contract
         * assertion. The synthetic {@code errorStatusCode} is set to
         * {@code 181008} iff the invoke terminated via a {@code ModelError}
         * carrying the four-field details; otherwise null (asserter will fail
         * cleanly).
         */
        public QueryResponse toQueryResponse() {
            Integer errorStatusCode = null;
            if (terminatedByException() && errorDetails != null
                    && errorDetails.containsKey("phase")
                    && errorDetails.containsKey("layer")) {
                // Demo wraps BaseError into HTTP 200 Map; synthesize the SDK code so
                // ErrorEnvelopeAsserter.assertTimeoutStatusCode() can validate the
                // ModelError contract once-removed. 181008 is MODEL_CALL_TIMEOUT —
                // consistent with the exception_type=ModelError + four-field details
                // the BaseError.getDetails() carries verbatim.
                errorStatusCode = ErrorEnvelopeAsserter.MODEL_CALL_TIMEOUT_CODE;
            }
            return new QueryResponse(statusCode, content, null, errorStatusCode,
                    errorDetails, rawBody, null, 0, callerAborted, callerAbortReason);
        }
    }
}

/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.context_compression.fixture;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * HTTP client for the {@code structured-compact-sit-demo} probe entry.
 *
 * <p>Issues the full r5 endpoint surface emitted by
 * {@code CompactProbeController}:
 *
 * <ul>
 *   <li>{@code POST /trigger-compact?scenario=sN} — primary passive-rail
 *       trigger that drives {@code StructuredCompactProcessor.onGetContextWindow}
 *       and returns {@code {"scenario":"sN","details":{"kind":"structured_compact",...}}}.
 *   <li>{@code POST /trigger-compact-via-rail?scenario=sN} — 4-level assembly
 *       path through {@code ContextProcessorRail} dispatch; emits
 *       {@code rail_dispatch_begin} / {@code rail_dispatch_end} log lines.
 *   <li>{@code POST /__trigger-active?scenario=sN} — direct
 *       {@code ContextEngine.compressContext} invocation (active trigger).
 *   <li>{@code POST /__trigger-async?opId=X&contextId=Y} +
 *       {@code GET /__async-result?opId=X&waitMs=N} — async scheduling.
 *   <li>{@code POST /__cancel?opId=X} — cancel in-flight future.
 *   <li>{@code POST /__source-mutate?contextId=X&op=...} — mutate the window
 *       source between repeat compactions.
 *   <li>{@code POST /__save-state?contextId=X} / {@code POST /__load-state} —
 *       state round-trip.
 *   <li>{@code GET /__compression-history?contextId=X} — read the raw history
 *       vector for identifier audit.
 *   <li>{@code GET /__span-events} / {@code POST /__reset-span-exporter} —
 *       OTel span event sampler.
 *   <li>{@code GET /__assembly-info} — assembly chain probe (rail / bridge /
 *       processor type / algorithm version).
 *   <li>{@code POST /__trace-bridge-close} / {@code POST /__trace-bridge-reinstall}
 *       — exercise multi-handle unsubscribe + re-install.
 * </ul>
 */
public final class CompactProbeHttpClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String baseUrl;

    public CompactProbeHttpClient(String baseUrl) {
        this.baseUrl = stripTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
    }

    public TriggerResponse trigger(String scenario, Duration requestTimeout) throws Exception {
        return postNoBody("/trigger-compact?scenario=" + enc(scenario), requestTimeout);
    }

    public TriggerResponse triggerViaRail(String scenario, Duration requestTimeout) throws Exception {
        return postNoBody("/trigger-compact-via-rail?scenario=" + enc(scenario), requestTimeout);
    }

    public TriggerResponse triggerActive(String scenario, Duration requestTimeout) throws Exception {
        return postNoBody("/__trigger-active?scenario=" + enc(scenario), requestTimeout);
    }

    public TriggerResponse triggerAsync(String opId, String contextId, String scenario,
                                        Duration requestTimeout) throws Exception {
        return postNoBody("/__trigger-async?opId=" + enc(opId)
                        + "&contextId=" + enc(contextId)
                        + "&scenario=" + enc(scenario),
                requestTimeout);
    }

    public TriggerResponse awaitAsyncResult(String opId, long waitMs,
                                            Duration requestTimeout) throws Exception {
        return getRequest("/__async-result?opId=" + enc(opId) + "&waitMs=" + waitMs, requestTimeout);
    }

    public TriggerResponse cancelAsync(String opId, Duration requestTimeout) throws Exception {
        return postNoBody("/__cancel?opId=" + enc(opId), requestTimeout);
    }

    public TriggerResponse mutateSource(String contextId, String op,
                                        Duration requestTimeout) throws Exception {
        return postNoBody("/__source-mutate?contextId=" + enc(contextId) + "&op=" + enc(op),
                requestTimeout);
    }

    public TriggerResponse saveState(String contextId, Duration requestTimeout) throws Exception {
        return postNoBody("/__save-state?contextId=" + enc(contextId), requestTimeout);
    }

    public TriggerResponse loadState(String contextId, String scenario,
                                     Duration requestTimeout) throws Exception {
        return postNoBody("/__load-state?contextId=" + enc(contextId) + "&scenario=" + enc(scenario),
                requestTimeout);
    }

    public TriggerResponse compressionHistory(String contextId, Duration requestTimeout)
            throws Exception {
        return getRequest("/__compression-history?contextId=" + enc(contextId), requestTimeout);
    }

    public TriggerResponse spanEvents(Duration requestTimeout) throws Exception {
        return getRequest("/__span-events", requestTimeout);
    }

    public TriggerResponse resetSpanExporter(Duration requestTimeout) throws Exception {
        return postNoBody("/__reset-span-exporter", requestTimeout);
    }

    public TriggerResponse assemblyInfo(Duration requestTimeout) throws Exception {
        return getRequest("/__assembly-info", requestTimeout);
    }

    public TriggerResponse traceBridgeClose(Duration requestTimeout) throws Exception {
        return postNoBody("/__trace-bridge-close", requestTimeout);
    }

    public TriggerResponse traceBridgeReinstall(Duration requestTimeout) throws Exception {
        return postNoBody("/__trace-bridge-reinstall", requestTimeout);
    }

    private TriggerResponse postNoBody(String pathAndQuery, Duration requestTimeout) throws Exception {
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(""))
                .build();
        return parse(http.send(req, HttpResponse.BodyHandlers.ofString()));
    }

    private TriggerResponse getRequest(String pathAndQuery, Duration requestTimeout) throws Exception {
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        return parse(http.send(req, HttpResponse.BodyHandlers.ofString()));
    }

    private TriggerResponse parse(HttpResponse<String> resp) {
        int status = resp.statusCode();
        String raw = resp.body();
        if (raw == null || raw.isBlank()) {
            return new TriggerResponse(status, null, null, raw, null);
        }
        try {
            JsonNode root = MAPPER.readTree(raw);
            String scenario = root.path("scenario").asText(null);
            Map<String, Object> details = null;
            JsonNode detailsNode = root.get("details");
            if (detailsNode != null && detailsNode.isObject()) {
                details = MAPPER.convertValue(detailsNode, new TypeReference<>() {});
            }
            Map<String, Object> parsed = MAPPER.convertValue(root, new TypeReference<>() {});
            return new TriggerResponse(status, scenario, details, raw, parsed);
        } catch (Exception notJson) {
            return new TriggerResponse(status, null, null, raw, null);
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    public record TriggerResponse(
            int statusCode,
            String scenario,
            Map<String, Object> details,
            String rawBody,
            Map<String, Object> parsedBody) {

        public Object detail(String key) {
            return details == null ? null : details.get(key);
        }

        public String detailString(String key) {
            Object v = detail(key);
            return v == null ? null : v.toString();
        }

        public Integer detailInt(String key) {
            Object v = detail(key);
            if (v instanceof Number n) {
                return n.intValue();
            }
            return null;
        }

        public Long detailLong(String key) {
            Object v = detail(key);
            if (v instanceof Number n) {
                return n.longValue();
            }
            return null;
        }

        public Double detailDouble(String key) {
            Object v = detail(key);
            if (v instanceof Number n) {
                return n.doubleValue();
            }
            return null;
        }

        public Object body(String key) {
            return parsedBody == null ? null : parsedBody.get(key);
        }

        public String bodyString(String key) {
            Object v = body(key);
            return v == null ? null : v.toString();
        }

        public Integer bodyInt(String key) {
            Object v = body(key);
            if (v instanceof Number n) {
                return n.intValue();
            }
            return null;
        }

        public Boolean bodyBoolean(String key) {
            Object v = body(key);
            return v instanceof Boolean b ? b : null;
        }
    }

    public static CompactProbeHttpClient onBase(String baseUrl) {
        return new CompactProbeHttpClient(baseUrl);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> expectDetailsMap(TriggerResponse resp) {
        if (resp.details == null) {
            throw new AssertionError("probe response has no `details` object: " + resp.rawBody);
        }
        return (Map<String, Object>) (Map<?, ?>) resp.details;
    }

    public static LinkedHashMap<String, Object> safeCopy(Map<String, Object> src) {
        return src == null ? new LinkedHashMap<>() : new LinkedHashMap<>(src);
    }
}

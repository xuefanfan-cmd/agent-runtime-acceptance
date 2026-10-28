/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.model_timeout_tiering.fixture;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * HTTP client for the FEAT-064 llm-stub-server side-channel endpoints
 * {@code GET /__stats} and {@code POST /__stats/reset}.
 *
 * <p>The stub counts {@code /v1/chat/completions} hits per effective mode name
 * (keyed by the resolved mode string, which equals {@code LLM_STUB_DEFAULT_MODE}
 * when the request does not override via query param). Count is incremented at
 * request arrival regardless of streaming vs blocking branch — so this is the
 * authoritative wire-level call count for a given scenario.
 *
 * <p>Used by FEAT-064 TCs to distinguish inner Retrier retries (which emit
 * {@code "Model call will retry."} log lines) from outer Rail guardrail retries
 * (which do NOT emit that log line but still issue a fresh wire call). Example:
 * default-config persistent RATE_LIMITED should yield {@code 6 stub calls / 4
 * retry logs} per the two-layer {@code (2+1)×(1+1)=6} math.
 *
 * <p>Reused across TCs — call {@link #reset()} between scenarios to isolate
 * counts.
 */
public final class StubStatsClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String baseUrl;

    public StubStatsClient(String baseUrl) {
        this.baseUrl = stripTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
    }

    /**
     * {@code GET /__stats} → {@code Map<String, Integer>} keyed by stub mode name.
     * A mode absent from the response means zero calls.
     */
    public Map<String, Integer> snapshot() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/__stats"))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new AssertionError("GET /__stats returned HTTP " + resp.statusCode()
                    + "; body=" + resp.body());
        }
        return MAPPER.readValue(resp.body(), new TypeReference<>() {});
    }

    /**
     * Convenience: hit count for a specific mode. Returns 0 when the mode has
     * not been invoked (absent key in the snapshot).
     */
    public int callCount(String mode) throws Exception {
        Objects.requireNonNull(mode, "mode");
        Integer n = snapshot().get(mode);
        return n == null ? 0 : n;
    }

    /** {@code POST /__stats/reset} — zeros all mode counters. */
    public void reset() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/__stats/reset"))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
        if (resp.statusCode() != 200) {
            throw new AssertionError("POST /__stats/reset returned HTTP " + resp.statusCode());
        }
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}

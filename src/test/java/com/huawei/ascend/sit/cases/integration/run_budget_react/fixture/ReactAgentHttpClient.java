/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.run_budget_react.fixture;

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
 * HTTP client for the FEAT-057 run-budget-elastic-research-demo
 * researcher-agent SUT. Issues {@code POST /v1/query} with body
 * {@code {"message":"<prompt>"}} per demo README / run-scenarios.sh.
 *
 * <p>The returned {@link QueryResponse} exposes:
 * <ul>
 *   <li>{@code output} + {@code resultType} — the two fields SIT asserts for
 *       termination surface (per L2 §2.2);
 *   <li>{@code hasTurnBudgetField} / {@code hasTimeBudgetField} — presence
 *       checks for the {@code turn_budget} / {@code time_budget} top-level
 *       subobjects, which L2 §2.2 says must NOT leak into the HTTP body.
 *       SIT asserts these are {@code false} as a core contract.
 *   <li>{@code rawBody} — full response for diagnostic dumping.
 * </ul>
 *
 * <p>This client intentionally does NOT go through the A2A SDK — demo HTTP
 * entry is a plain POST surface, not JSON-RPC. (See memory
 * [[feat-057-scope-includes-react-agent]].)
 */
public final class ReactAgentHttpClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String baseUrl;

    public ReactAgentHttpClient(String baseUrl) {
        this.baseUrl = stripTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
    }

    /**
     * One-shot blocking query. {@code requestTimeout} must be long enough to
     * cover the full invoke — FEAT-057 scenarios run tens of model+tool calls
     * so 300s is a reasonable default for callers.
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

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return parse(resp);
    }

    private QueryResponse parse(HttpResponse<String> resp) throws Exception {
        int status = resp.statusCode();
        String raw = resp.body();
        String output = null;
        String resultType = null;
        boolean hasTurnBudget = false;
        boolean hasTimeBudget = false;
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
                hasTurnBudget = root.has("turn_budget");
                hasTimeBudget = root.has("time_budget");
                parsed = MAPPER.convertValue(root, new TypeReference<>() {});
            } catch (Exception notJson) {
                // leave fields null; caller inspects rawBody for diagnostics.
            }
        }
        return new QueryResponse(status, output, resultType, hasTurnBudget, hasTimeBudget, raw, parsed);
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    public record QueryResponse(
            int statusCode,
            String output,
            String resultType,
            boolean hasTurnBudgetField,
            boolean hasTimeBudgetField,
            String rawBody,
            Map<String, Object> parsedBody) {
    }
}

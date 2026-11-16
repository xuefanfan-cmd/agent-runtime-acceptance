/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.dynamic_model_config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Test-only client for the carrier's model-management surface.
 *
 * <p>FEAT-050-CORE has no product REST entry; this surface exists so the SIT can drive the model
 * management contract (add/update/remove/list/default) and observe the concrete return values.</p>
 */
final class DynamicModelControlClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String baseUrl;

    DynamicModelControlClient(String baseUrl) {
        this.baseUrl = baseUrl + "/__sit/dynamic-model";
    }

    JsonNode list() {
        return get("/list");
    }

    JsonNode has(String modelId) {
        return get("/has?modelId=" + modelId);
    }

    JsonNode defaultModel() {
        return get("/default");
    }

    JsonNode lastRequest() {
        return get("/last-request");
    }

    /** Test-only isolation: rebuild the pool from the fixture configuration. */
    JsonNode reset() {
        return post("/reset", "{}").body();
    }

    Response setDefault(String modelIdJson) {
        return post("/default", "{\"modelId\":" + modelIdJson + "}");
    }

    Response add(String modelId, String modelName, String apiBase) {
        return post("/add", body(modelId, modelName, apiBase));
    }

    Response update(String modelId, String modelName, String apiBase) {
        return post("/update", body(modelId, modelName, apiBase));
    }

    Response remove(String modelId) {
        return post("/remove", "{\"modelId\":\"" + modelId + "\"}");
    }

    /** HTTP status plus parsed body; both are recorded as evidence. */
    record Response(int status, JsonNode body, String rawBody) {
        boolean ok() {
            return status == 200;
        }

        /** 语义判定：门面把错误包在 Result 里，需按 accepted 字段判，而不是 HTTP 状态。 */
        boolean accepted() {
            return ok() && body != null && body.path("accepted").asBoolean(false);
        }

        String rawResult() {
            return body == null ? null : body.path("rawResult").asText(null);
        }

        String text(String field) {
            return body == null || body.get(field) == null ? null : body.get(field).asText();
        }
    }

    private static String body(String modelId, String modelName, String apiBase) {
        return "{\"modelId\":\"" + modelId + "\",\"modelName\":\"" + modelName
                + "\",\"apiBase\":\"" + apiBase + "\"}";
    }

    private JsonNode get(String path) {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + path))
                            .timeout(Duration.ofSeconds(30))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            return MAPPER.readTree(response.body());
        } catch (IOException exception) {
            throw new IllegalStateException("control call failed: GET " + path, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("control call interrupted: GET " + path, exception);
        }
    }

    private Response post(String path, String json) {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + path))
                            .timeout(Duration.ofSeconds(30))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode body = response.body() == null || response.body().isBlank()
                    ? null : MAPPER.readTree(response.body());
            return new Response(response.statusCode(), body, response.body());
        } catch (IOException exception) {
            throw new IllegalStateException("control call failed: POST " + path, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("control call interrupted: POST " + path, exception);
        }
    }
}

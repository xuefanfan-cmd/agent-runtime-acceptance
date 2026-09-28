/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.fixtures.studio_dsl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Client for the Studio DSL SIT host's test-only selection and audit surface. */
public final class StudioDslHostControlClient {
    // 宿主控制面会随版本增删字段（例如 nodeJavaTypes），测试侧只关心已知字段，避免版本漂移直接打断取证。
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String baseUrl;

    public StudioDslHostControlClient(String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
    }

    public HostState status() {
        return read(send(HttpRequest.newBuilder(uri("/status")).GET().build()));
    }

    public HostState reset() {
        return read(send(HttpRequest.newBuilder(uri("/reset"))
                .POST(HttpRequest.BodyPublishers.noBody()).build()));
    }

    public HostState replace(
            String source,
            Map<String, Object> ir,
            Map<String, Object> modelMapping,
            Map<String, Map<String, Object>> children) {
        return replace(source, ir, modelMapping, children, null);
    }

    /** Same as the four-argument form, with an optional {@code maxNestingDepth} override for the SIT fixture. */
    public HostState replace(
            String source,
            Map<String, Object> ir,
            Map<String, Object> modelMapping,
            Map<String, Map<String, Object>> children,
            Integer maxNestingDepth) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("source", source);
            body.put("ir", ir);
            if (modelMapping != null && !modelMapping.isEmpty()) {
                body.put("modelMapping", modelMapping);
            }
            if (children != null && !children.isEmpty()) {
                body.put("children", children);
            }
            if (maxNestingDepth != null) {
                body.put("maxNestingDepth", maxNestingDepth);
            }
            HttpRequest request = HttpRequest.newBuilder(uri("/replace"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();
            return read(send(request));
        } catch (Exception exception) {
            throw new IllegalStateException("cannot replace Studio DSL host workflow", exception);
        }
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception exception) {
            throw new IllegalStateException("Studio DSL host control request failed", exception);
        }
    }

    private static HostState read(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "Studio DSL host control returned " + response.statusCode() + ": " + response.body());
        }
        try {
            return MAPPER.readValue(response.body(), HostState.class);
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse Studio DSL host state: " + response.body(), exception);
        }
    }

    private URI uri(String path) {
        return URI.create(baseUrl + "/__sit/studio-dsl" + path);
    }

    public record HostState(
            String source,
            String workflowId,
            String workflowVersion,
            String irSha256,
            String loadedAt,
            long loadCount,
            long queryCount,
            long streamQueryCount,
            long childIrLoadCount,
            Integer maxNestingDepth,
            boolean ioLogActive,
            long ioTraceChunkCount,
            String ioTraceSample,
            List<String> componentIds,
            java.util.Set<String> modelIds) {
    }
}

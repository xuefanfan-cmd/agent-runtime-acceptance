/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.fixtures.studio_dsl;

import okhttp3.Headers;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Controlled IAM/OAUTH token endpoint that preserves the real JDK HTTP exchange. */
public final class TokenEndpointStubFixture implements AutoCloseable {
    private final MockWebServer server = new MockWebServer();

    public TokenEndpointStubFixture() throws IOException {
        server.start();
    }

    public String endpoint(String path) {
        return server.url(path).toString();
    }

    public int requestCount() {
        return server.getRequestCount();
    }

    public void enqueueIamSuccess(String token) {
        server.enqueue(new MockResponse()
                .setResponseCode(201)
                .setHeader("X-Subject-Token", token)
                .setHeader("Content-Type", "application/json")
                .setBody("{}"));
    }

    public void enqueueOauthSuccess(String token) {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\"}"));
    }

    public void enqueueFailure(int status) {
        server.enqueue(new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"acceptance-injected\"}"));
    }

    public void enqueueMissingToken() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{}"));
    }

    public RequestRecord takeRequest(Duration timeout) throws InterruptedException {
        RecordedRequest request = server.takeRequest(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (request == null) {
            throw new IllegalStateException("token request not received within " + timeout);
        }
        return new RequestRecord(
                request.getMethod(), request.getPath(), headers(request.getHeaders()), request.getBody().readUtf8());
    }

    private static Map<String, String> headers(Headers headers) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (String name : headers.names()) {
            result.put(name, headers.get(name));
        }
        return Map.copyOf(result);
    }

    @Override
    public void close() throws IOException {
        server.shutdown();
    }

    public record RequestRecord(String method, String path, Map<String, String> headers, String body) {
        public String header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }
    }
}

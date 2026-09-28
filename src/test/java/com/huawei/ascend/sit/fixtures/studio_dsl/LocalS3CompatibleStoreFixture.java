/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.fixtures.studio_dsl;

import com.openjiuwen.studio.dsl.fetch.store.ObsFetchConfig;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** S3-compatible, path-style endpoint used to observe the product's real AWS SDK client. */
public final class LocalS3CompatibleStoreFixture implements AutoCloseable {
    public static final String BUCKET = "acceptance-private";
    public static final String ACCESS_KEY = "acceptance-read-only";
    public static final String SECRET_KEY = "acceptance-secret-not-production";
    public static final String ALLOWED_PREFIX = "workflow/ir/";

    private final MockWebServer server = new MockWebServer();
    private final Map<String, byte[]> objects = new LinkedHashMap<>();
    private final Map<String, Integer> failures = new LinkedHashMap<>();
    private final List<RequestRecord> requests = new CopyOnWriteArrayList<>();

    public LocalS3CompatibleStoreFixture() throws IOException {
        server.setDispatcher(new StoreDispatcher());
        server.start();
    }

    public LocalS3CompatibleStoreFixture put(String key, byte[] value) {
        objects.put(key, value.clone());
        return this;
    }

    public LocalS3CompatibleStoreFixture fail(String key, int status) {
        failures.put(key, status);
        return this;
    }

    public ObsFetchConfig config() {
        return new ObsFetchConfig(endpoint(), "us-east-1", ACCESS_KEY, SECRET_KEY, BUCKET);
    }

    public String endpoint() {
        return server.url("/").toString();
    }

    public int requestCount() {
        return requests.size();
    }

    public List<RequestRecord> requestsSince(int baseline) {
        if (baseline < 0 || baseline > requests.size()) {
            throw new IllegalArgumentException("invalid request baseline: " + baseline);
        }
        return List.copyOf(new ArrayList<>(requests.subList(baseline, requests.size())));
    }

    public static String unavailableEndpoint() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "http://127.0.0.1:" + socket.getLocalPort() + "/";
        }
    }

    @Override
    public void close() throws IOException {
        server.shutdown();
    }

    public record RequestRecord(String method, String path, String objectKey, String authorization) {
    }

    private final class StoreDispatcher extends Dispatcher {
        @Override
        public MockResponse dispatch(RecordedRequest request) {
            String key = objectKey(request);
            requests.add(new RequestRecord(
                    request.getMethod(), request.getPath(), key, request.getHeader("Authorization")));
            if (!"GET".equals(request.getMethod())) {
                return error(405, "MethodNotAllowed", key);
            }
            if (key == null) {
                return error(404, "NoSuchBucket", "");
            }
            if (!key.startsWith(ALLOWED_PREFIX)) {
                return error(403, "AccessDenied", key);
            }
            Integer injected = failures.get(key);
            if (injected != null) {
                return error(injected, errorCode(injected), key);
            }
            byte[] value = objects.get(key);
            if (value == null) {
                return error(404, "NoSuchKey", key);
            }
            return new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/octet-stream")
                    .setBody(new Buffer().write(value));
        }

        private String objectKey(RecordedRequest request) {
            if (request.getRequestUrl() == null) {
                return null;
            }
            List<String> segments = request.getRequestUrl().pathSegments();
            if (segments.isEmpty() || !BUCKET.equals(segments.get(0))) {
                return null;
            }
            return String.join("/", segments.subList(1, segments.size()));
        }

        private MockResponse error(int status, String code, String key) {
            String body = "<Error><Code>" + code + "</Code><Message>acceptance injection</Message>"
                    + "<Key>" + key + "</Key></Error>";
            return new MockResponse()
                    .setResponseCode(status)
                    .setHeader("Content-Type", "application/xml")
                    .setBody(body);
        }

        private String errorCode(int status) {
            return switch (status) {
                case 401, 403 -> "AccessDenied";
                case 404 -> "NoSuchKey";
                default -> "InternalError";
            };
        }
    }
}

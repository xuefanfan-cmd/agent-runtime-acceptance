/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.fixtures.studio_dsl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Headers;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** OpenAI-compatible endpoint that records the product's real HTTP requests. */
public final class RecordingOpenAiEndpointFixture implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MockWebServer server = new MockWebServer();
    private final List<RequestRecord> requests = new CopyOnWriteArrayList<>();
    private volatile String responseCanary = "studio-model-canary";
    private volatile String responseContentOverride = null;
    private volatile boolean stallResponses = false;

    public RecordingOpenAiEndpointFixture() throws IOException {
        server.setDispatcher(new ModelDispatcher());
        server.start();
    }

    public String baseUrl() {
        return server.url("/v1").toString().replaceAll("/$", "");
    }

    public void responseCanary(String value) {
        responseCanary = value;
    }

    /**
     * 原样指定后续模型响应的 {@code message.content}。
     *
     * <p>用于 {@code response_format=json} 之类要求模型返回合法 JSON 的节点：{@link #responseCanary} 返回的是
     * 纯文本 canary，会被 JSON 解析路径拒绝，从而掩盖被测行为。设置后优先于 canary；传入 {@code null} 恢复默认。</p>
     */
    public void responseContent(String jsonOrText) {
        this.responseContentOverride = jsonOrText;
    }

    /**
     * 确定性超时触发件：对后续模型请求保持连接但不返回任何响应。
     *
     * <p>用于验证运行时“执行超时上限”的失败面——调用方必须得到有界、可诊断的失败，而不是悬挂或
     * 静默成功。请求仍会被记录，便于断言调用真的发生。</p>
     */
    public void stallModelResponses() {
        this.stallResponses = true;
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

    @Override
    public void close() throws IOException {
        try {
            server.shutdown();
        } catch (IOException exception) {
            if (!stallResponses) {
                throw exception;
            }
            // 触发件场景仍挂着“不响应”的连接，MockWebServer 排空等待可能超时；
            // 该异常只反映夹具清理，不影响已完成的被测结论。正常场景保持严格抛出。
        }
    }

    public record RequestRecord(
            String method, String path, Map<String, String> headers, JsonNode body) {
        public String header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }
    }

    private final class ModelDispatcher extends Dispatcher {
        @Override
        public MockResponse dispatch(RecordedRequest request) {
            try {
                String bodyText = request.getBody().readUtf8();
                JsonNode body = MAPPER.readTree(bodyText);
                requests.add(new RequestRecord(
                        request.getMethod(), request.getPath(), headers(request.getHeaders()), body));
                if (!request.getPath().endsWith("/chat/completions")) {
                    return json(404, "{\"error\":{\"message\":\"unexpected path\"}}");
                }
                if (stallResponses) {
                    return new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE);
                }
                MockResponse response;
                if (body.path("stream").asBoolean(false)) {
                    response = new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody(streamBody(contentToReturn()));
                } else {
                    response = json(200, completionBody(contentToReturn()));
                }
                return response;
            } catch (Exception exception) {
                return json(500, "{\"error\":{\"message\":\"fixture parse failure\"}}");
            }
        }
    }

    private String contentToReturn() {
        String override = responseContentOverride;
        return override == null ? responseCanary : override;
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private static String completionBody(String canary) throws Exception {
        var root = MAPPER.createObjectNode();
        root.put("id", "chatcmpl-feat031");
        root.put("object", "chat.completion");
        root.put("created", 1);
        root.put("model", "model-a");
        var choice = root.putArray("choices").addObject();
        choice.put("index", 0);
        choice.putObject("message").put("role", "assistant").put("content", canary);
        choice.put("finish_reason", "stop");
        root.putObject("usage").put("prompt_tokens", 1).put("completion_tokens", 1).put("total_tokens", 2);
        return MAPPER.writeValueAsString(root);
    }

    private static String streamBody(String canary) throws Exception {
        var first = MAPPER.createObjectNode();
        first.put("id", "chatcmpl-feat031");
        first.put("object", "chat.completion.chunk");
        first.put("created", 1);
        first.put("model", "model-a");
        var firstChoice = first.putArray("choices").addObject();
        firstChoice.put("index", 0);
        firstChoice.putObject("delta").put("role", "assistant").put("content", canary);
        firstChoice.putNull("finish_reason");

        var last = MAPPER.createObjectNode();
        last.put("id", "chatcmpl-feat031");
        last.put("object", "chat.completion.chunk");
        last.put("created", 1);
        last.put("model", "model-a");
        var lastChoice = last.putArray("choices").addObject();
        lastChoice.put("index", 0);
        lastChoice.putObject("delta");
        lastChoice.put("finish_reason", "stop");
        return "data: " + MAPPER.writeValueAsString(first) + "\n\n"
                + "data: " + MAPPER.writeValueAsString(last) + "\n\n"
                + "data: [DONE]\n\n";
    }

    private static Map<String, String> headers(Headers headers) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : headers.names()) {
            result.put(name, headers.get(name));
        }
        return Map.copyOf(result);
    }
}

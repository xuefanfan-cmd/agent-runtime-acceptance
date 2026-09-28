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

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Controlled HTTP plugin and MCP Streamable HTTP endpoint with request recording. */
public final class RecordingPluginMcpEndpointFixture implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * A real Studio export can only point at this controlled endpoint when the port is stable, so the
     * fixture binds a fixed port by default and fails loudly when that port is already taken.
     */
    private static final String PORT_PROPERTY = "studio.dsl.plugin.port";
    private static final int DEFAULT_PORT = 55207;

    private final MockWebServer server = new MockWebServer();
    private final List<RequestRecord> requests = new CopyOnWriteArrayList<>();

    public RecordingPluginMcpEndpointFixture() throws IOException {
        this(Integer.getInteger(PORT_PROPERTY, DEFAULT_PORT));
    }

    public RecordingPluginMcpEndpointFixture(int port) throws IOException {
        server.setDispatcher(new EndpointDispatcher());
        server.start(port);
    }

    public String pluginUrl() {
        return server.url("/plugin/weather").toString();
    }

    public String mcpUrl() {
        return server.url("/mcp").toString();
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
        server.shutdown();
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

    private final class EndpointDispatcher extends Dispatcher {
        @Override
        public MockResponse dispatch(RecordedRequest request) {
            try {
                String bodyText = request.getBody().readUtf8();
                JsonNode body = bodyText.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(bodyText);
                requests.add(new RequestRecord(
                        request.getMethod(), request.getPath(), headers(request.getHeaders()), body));
                if (request.getPath().startsWith("/plugin/weather")) {
                    // 2026-09-21 已判定：返回体形状**不是** E5 红的原因，故保持原始形状。
                    // 依据（FEAT-031 G1 文档 §十二）：给返回体加 `data` 键、并把结束节点引用改指
                    // 此处声明的响应字段 `temperature`（该字段值同为 canary）后，父 End 的
                    // `userFields.result` 仍为空；同轮插件节点 INVOKE 成功、受控端点请求增量=1、
                    // 任务 COMPLETED ⇒ 问题在"插件节点的输出没有到达下游节点"。
                    return json(200, "{\"temperature\":26,\"canary\":\"plugin-canary\"}");
                }
                if (!request.getPath().startsWith("/mcp")) {
                    return json(404, "{\"error\":\"unexpected path\"}");
                }
                String method = body.path("method").asText();
                if ("notifications/initialized".equals(method)) {
                    return new MockResponse().setResponseCode(202);
                }
                JsonNode id = body.get("id");
                if ("initialize".equals(method)) {
                    var result = MAPPER.createObjectNode();
                    result.put("protocolVersion", "2024-11-05");
                    result.putObject("capabilities").putObject("tools");
                    result.putObject("serverInfo").put("name", "feat031-mcp").put("version", "1.0");
                    return mcpResult(id, result);
                }
                if ("tools/call".equals(method)) {
                    var result = MAPPER.createObjectNode();
                    result.putArray("content").addObject()
                            .put("type", "text").put("text", "mcp-canary");
                    result.put("isError", false);
                    return mcpResult(id, result);
                }
                if ("tools/list".equals(method)) {
                    var result = MAPPER.createObjectNode();
                    result.putArray("tools").addObject()
                            .put("name", "weather")
                            .put("description", "acceptance weather")
                            .set("inputSchema", MAPPER.createObjectNode().put("type", "object"));
                    return mcpResult(id, result);
                }
                return json(400, "{\"error\":\"unexpected MCP method\"}");
            } catch (Exception exception) {
                return json(500, "{\"error\":\"fixture parse failure\"}");
            }
        }
    }

    private static MockResponse mcpResult(JsonNode id, JsonNode result) throws Exception {
        var root = MAPPER.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.set("id", id == null ? MAPPER.nullNode() : id);
        root.set("result", result);
        return json(200, MAPPER.writeValueAsString(root));
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private static Map<String, String> headers(Headers headers) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : headers.names()) {
            result.put(name, headers.get(name));
        }
        return Map.copyOf(result);
    }
}

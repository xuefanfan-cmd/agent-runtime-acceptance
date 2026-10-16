/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.concurrency.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 验收驱动器：向被测 runtime 的 {@code /a2a/} 入口发 {@code SendStreamingMessage}，收集 SSE
 * {@code data:} 事件，解析 Task 终态与 taskId。同时暴露 {@code sendMessage} 非流式入口，
 * 用于观察准入拒绝（503）时的 HTTP 状态码。
 *
 * @since 0.1.0
 */
public final class A2aServiceClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_EVENTS = 5000;
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    private final String a2aEndpoint;

    public A2aServiceClient(String baseUrl) {
        this.a2aEndpoint = baseUrl.replaceAll("/+$", "") + "/a2a/";
    }

    /**
     * 驱动一轮 streaming。
     *
     * @param contextId
     *            会话 id（多轮贯穿）
     * @param taskId
     *            续接时复用的 taskId；新任务传 {@code null}
     * @param userText
     *            用户本轮输入
     * @return 归一后的 Task 终态 + 原始事件 + taskId
     */
    public DriveResult send(String contextId, String taskId, String userText) {
        String body = buildRequest("SendStreamingMessage", contextId, taskId, userText);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(a2aEndpoint)).timeout(TIMEOUT)
                    .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            HttpResponse<InputStream> response = HttpClient.newHttpClient().send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            List<String> events = new ArrayList<>();
            String terminalState = null;
            String resolvedTaskId = null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && events.size() < MAX_EVENTS) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String payload = line.substring(5).trim();
                    if (payload.isBlank()) {
                        continue;
                    }
                    events.add(payload);
                    JsonNode event = MAPPER.readTree(payload);
                    JsonNode result = event.path("result");
                    JsonNode statusUpdate = result.path("statusUpdate");
                    JsonNode artifactUpdate = result.path("artifactUpdate");
                    if (!statusUpdate.isMissingNode()) {
                        JsonNode state = statusUpdate.path("status").path("state");
                        if (state.isTextual() && !state.asText().isBlank()) {
                            terminalState = state.asText();
                        }
                        JsonNode tid = statusUpdate.path("taskId");
                        if (tid.isTextual() && !tid.asText().isBlank()) {
                            resolvedTaskId = tid.asText();
                        }
                    }
                    if (!artifactUpdate.isMissingNode()) {
                        JsonNode tid = artifactUpdate.path("taskId");
                        if (tid.isTextual() && !tid.asText().isBlank()) {
                            resolvedTaskId = tid.asText();
                        }
                    }
                }
            }
            return new DriveResult(classify(terminalState), response.statusCode(), events, resolvedTaskId);
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("A2A drive failed for contextId=" + contextId, e);
        }
    }

    /**
     * 非流式 SendMessage，用于断言准入拒绝的 HTTP 状态码（503）与结构化 error payload。
     *
     * @param contextId
     *            会话 id
     * @param userText
     *            用户输入
     * @return HTTP 状态码 + 响应体
     */
    public HttpResponse<String> sendMessageRaw(String contextId, String userText) {
        String body = buildRequest("SendMessage", contextId, null, userText);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(a2aEndpoint)).timeout(TIMEOUT)
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("A2A SendMessage failed for contextId=" + contextId, e);
        }
    }

    /**
     * GET /v1/current_active_tasks，返回响应体 JSON 文本（DFX-002 合入后可用）。
     *
     * @param baseUrl
     *            runtime 根地址
     * @return 响应体 JSON 文本
     */
    public String currentActiveTasks(String baseUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "")
                    + "/v1/current_active_tasks")).timeout(TIMEOUT).GET().build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request,
                    HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("current_active_tasks query failed", e);
        }
    }

    /**
     * 发送 JSON-RPC GetTask 请求，返回 HTTP 状态码 + 响应体。
     *
     * @param contextId
     *            会话 id
     * @param taskId
     *            任务 id
     * @return HTTP 响应
     */
    public HttpResponse<String> getTask(String contextId, String taskId) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("id", UUID.randomUUID().toString());
        root.put("method", "GetTask");
        ObjectNode params = root.putObject("params");
        params.put("contextId", contextId);
        if (taskId != null && !taskId.isBlank()) {
            params.put("taskId", taskId);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(a2aEndpoint)).timeout(TIMEOUT)
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(root.toString(), StandardCharsets.UTF_8)).build();
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("A2A GetTask failed for contextId=" + contextId, e);
        }
    }

    private static DriveResult.TaskState classify(String stateToken) {
        if (stateToken == null) {
            return DriveResult.TaskState.UNKNOWN;
        }
        return switch (stateToken) {
        case "TASK_STATE_COMPLETED" -> DriveResult.TaskState.COMPLETED;
        case "TASK_STATE_INPUT_REQUIRED" -> DriveResult.TaskState.INPUT_REQUIRED;
        case "TASK_STATE_FAILED" -> DriveResult.TaskState.FAILED;
        case "TASK_STATE_CANCELED" -> DriveResult.TaskState.CANCELED;
        case "TASK_STATE_REJECTED" -> DriveResult.TaskState.REJECTED;
        default -> DriveResult.TaskState.UNKNOWN;
        };
    }

    private static String buildRequest(String method, String contextId, String taskId, String userText) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("id", UUID.randomUUID().toString());
        root.put("method", method);

        ObjectNode params = root.putObject("params");
        ObjectNode message = params.putObject("message");
        message.put("role", "ROLE_USER");
        message.put("messageId", "m-" + UUID.randomUUID());
        message.put("contextId", contextId);
        if (taskId != null && !taskId.isBlank()) {
            message.put("taskId", taskId);
        }
        ArrayNode parts = message.putArray("parts");
        parts.addObject().put("text", userText);

        return root.toString();
    }
}

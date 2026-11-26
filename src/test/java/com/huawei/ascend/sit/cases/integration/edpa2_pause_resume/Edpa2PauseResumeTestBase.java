/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.edpa2_pause_resume;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Tag;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Shared base for FEAT-088 pause/resume tests against the moa-agent-demo stack.
 *
 * <p>Brings up the three-process chain (mock → l2 → l1) via the standard
 * {@link BaseManagedStackTest} / {@link SutStack} framework, and provides
 * A2A send helpers plus REST control helpers for pause/resume testing.</p>
 */
@Tag("integration")
@Tag("feat-088")
@Feature("FEAT-088: 任务执行中断与接管")
public abstract class Edpa2PauseResumeTestBase extends BaseManagedStackTest {

    protected static final ObjectMapper JSON = new ObjectMapper();
    protected static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(4);

    protected static final String EDPA2_MOCK = "moa-mock";
    protected static final String EDPA2_L2 = "moa-l2";
    protected static final String EDPA2_L1 = "moa-l1";

    @Override
    protected SutStack.Builder buildStack(com.huawei.ascend.sit.config.TestConfig config) {
        // Leaf-first declaration; SutStack injects downstream URLs automatically.
        return SutStack.builder(config)
                .agent(EDPA2_MOCK)
                .agent(EDPA2_L2, a -> a.downstream(EDPA2_MOCK))
                .agent(EDPA2_L1, a -> a.downstream(EDPA2_L2));
    }

    // ── A2A helpers ───────────────────────────────────────────────────────

    /**
     * Send a text message to L1 and return the terminal task state + taskId.
     */
    protected A2aRound sendA2a(String text) {
        return sendA2a(text, null, null);
    }

    /**
     * Send a text message to L1 with an explicit contextId and optional taskId
     * (for continuation rounds — pause/resume).
     *
     * <p>Uses SDK {@code sendMessageSync} (streaming=false → blocking {@code message/send})
     * with {@link A2aEventCollector} — same pattern as
     * {@code DeepAgentInteractiveInterruptAcceptanceTest.sendOutcome} but with
     * {@code sendMessageSync} instead of {@code sendMessageStreaming}, because
     * EDPA2.0-L1's agent card does not advertise streaming and the SDK's streaming
     * fallback produces 0 consumer events.</p>
     *
     * <p>The call blocks until the server returns (INPUT_REQUIRED, COMPLETED,
     * FAILED, etc.). Events are dispatched through consumers before the method
     * returns, so the collector is fully populated when this returns.</p>
     */
    protected A2aRound sendA2a(String text, String contextId, String taskId) {
        A2aServiceClient a2aClient = client(EDPA2_L1);
        String cid = contextId != null ? contextId : UUID.randomUUID().toString();

        Message.Builder builder = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .contextId(cid)
                .parts(List.of(new TextPart(text)));
        if (taskId != null) {
            builder.taskId(taskId);
        }

        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> error = new AtomicReference<>();
        List<BiConsumer<ClientEvent, AgentCard>> consumers = List.of(collector.createConsumer());
        Consumer<Throwable> errorHandler = error::set;

        a2aClient.sendMessageSync(builder.build(), null, null, null, consumers, errorHandler);

        if (error.get() != null) {
            throw new IllegalStateException("A2A sendMessageSync failed: " + text, error.get());
        }

        TaskState state = observedState(collector);
        String observedTaskId = collector.findFirstTaskId();
        String observedContextId = collector.findFirstContextId();
        if (observedContextId == null || observedContextId.isBlank()) {
            observedContextId = cid;
        }
        System.out.println("[DEBUG sendA2a] text=" + text.substring(0, Math.min(50, text.length()))
                + " state=" + state + " taskId=" + observedTaskId
                + " contextId=" + observedContextId
                + " events=" + collector.eventCount());
        return new A2aRound(state, observedTaskId, observedContextId);
    }

    /**
     * Determine the observed task state from the collector — checks INPUT_REQUIRED
     * first (non-destructive), then terminal states. Same logic as
     * {@code DeepAgentInteractiveInterruptAcceptanceTest.observedState}.
     */
    private static TaskState observedState(A2aEventCollector collector) {
        if (collector.findInputRequiredEvent().isPresent()) {
            return TaskState.TASK_STATE_INPUT_REQUIRED;
        }
        return collector.findTerminalEvent()
                .flatMap(event -> {
                    if (event instanceof TaskEvent te) {
                        return Optional.of(te.getTask().status().state());
                    }
                    if (event instanceof TaskUpdateEvent ue) {
                        return Optional.of(ue.getTask().status().state());
                    }
                    return Optional.<TaskState>empty();
                })
                .orElse(TaskState.UNRECOGNIZED);
    }

    /**
     * Send a text message to L1 via SendMessage (non-streaming) — blocks until
     * the task reaches a terminal or INPUT_REQUIRED state, then returns taskId + state.
     */
    protected A2aRound sendA2aSync(String text, String contextId) {
        return sendA2aRaw(EDPA2_L1, text, contextId, null, false);
    }

    /**
     * Send an A2A message with a DataPart control (e.g. moa.control pause) and no text.
     */
    protected A2aRound sendControlDataPart(ObjectNode controlDataPart, String contextId, String taskId) {
        return sendA2aControlRaw(EDPA2_L1, controlDataPart, contextId, taskId, true);
    }

    /**
     * Send a text message to L2 directly (for testing L2 exclusion of pause control).
     */
    protected A2aRound sendA2aToL2(String text, String contextId, String taskId) {
        return sendA2aRaw(EDPA2_L2, text, contextId, taskId, true);
    }

    /**
     * Send a control DataPart via A2A SendMessage (non-streaming) and return the raw HTTP response.
     * Used for error/invalid-control testing where we need to inspect the error response.
     */
    protected HttpResponse<String> sendControlSync(ObjectNode controlDataPart, String contextId) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "ROLE_USER");
        message.put("messageId", UUID.randomUUID().toString());
        message.put("contextId", contextId);
        message.put("parts", List.of(controlDataPart));

        String id = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("jsonrpc", "2.0", "id", id,
                "method", "SendMessage", "params", Map.of("message", message));
        String url = stack.baseUrl(EDPA2_L1) + "/a2a";
        return sendHttpPost(url, buildJsonString(body), "application/json");
    }

    // ── Async A2A helpers (for pause-during-execution) ─────────────────────

    /**
     * Send an A2A message asynchronously — opens the SSE stream and returns as soon
     * as a taskId is observed (task is EXECUTING). The caller can then send a pause
     * control while the task is still running. The SSE stream continues reading in
     * a background thread and the final result is available via the returned future.
     *
     * @return AsyncA2aRound containing taskId, contextId, and a CompletableFuture
     *         for the final A2aRound
     */
    protected AsyncA2aRound sendA2aAsync(String text, String contextId) {
        return sendA2aAsyncRaw(EDPA2_L1, text, contextId, null);
    }

    /**
     * Send a control message (pause/resume) without waiting for the full response.
     * Returns the raw HTTP response body for inspection.
     */
    protected HttpResponse<String> sendControlRaw(ObjectNode controlDataPart,
                                                   String contextId, String taskId) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "ROLE_USER");
        message.put("messageId", UUID.randomUUID().toString());
        message.put("contextId", contextId);
        message.put("parts", List.of(controlDataPart));
        if (taskId != null) message.put("taskId", taskId);

        String id = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("jsonrpc", "2.0", "id", id,
                "method", "SendStreamingMessage", "params", Map.of("message", message));
        String url = stack.baseUrl(EDPA2_L1) + "/a2a";
        return sendHttpPost(url, buildJsonString(body),
                "text/event-stream");
    }

    /**
     * Send a pause control via A2A SendMessage (non-streaming) and return the full JSON response.
     * Per design doc suspended-tasks.md, this should trigger MoaPauseIngressHook which returns
     * an independent receipt Task with pause_requested/ACCEPTED code.
     *
     * @param contextId the existing business session contextId
     * @return the raw HTTP response (JSON, not SSE)
     */
    protected HttpResponse<String> sendPauseControlSync(String contextId) {
        return sendPauseControlSync(contextId, null);
    }

    /**
     * Send a pause control via A2A SendMessage (non-streaming) with a fixed messageId
     * (for duplicate-pause testing). Per 1100.patch: A2A retry reuses the saved messageId.
     *
     * @param contextId the existing business session contextId
     * @param fixedMessageId if non-null, use this messageId (for duplicate testing); else random
     * @return the raw HTTP response (JSON, not SSE)
     */
    protected HttpResponse<String> sendPauseControlSync(String contextId, String fixedMessageId) {
        ObjectNode controlPart = controlDataPart("pause");
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "ROLE_USER");
        message.put("messageId", fixedMessageId != null ? fixedMessageId : UUID.randomUUID().toString());
        message.put("contextId", contextId);
        message.put("parts", List.of(controlPart));

        String id = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("jsonrpc", "2.0", "id", id,
                "method", "SendMessage", "params", Map.of("message", message));
        String url = stack.baseUrl(EDPA2_L1) + "/a2a";
        return sendHttpPost(url, buildJsonString(body), "application/json");
    }

    /**
     * Send a resume text via A2A SendMessage (non-streaming) and return the full JSON response.
     */
    protected HttpResponse<String> sendResumeControlSync(String contextId) {
        return sendSemanticResumeSync("继续上一次任务", contextId);
    }

    /**
     * Send a semantic pause text via A2A SendMessage (non-streaming) and return the full JSON response.
     * Per design doc: semantic pause words (暂停, 请暂停, etc.) should also trigger MoaPauseIngressHook.
     */
    protected HttpResponse<String> sendSemanticPauseSync(String text, String contextId) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "ROLE_USER");
        message.put("messageId", UUID.randomUUID().toString());
        message.put("contextId", contextId);
        message.put("parts", List.of(Map.of("text", text)));

        String id = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("jsonrpc", "2.0", "id", id,
                "method", "SendMessage", "params", Map.of("message", message));
        String url = stack.baseUrl(EDPA2_L1) + "/a2a";
        return sendHttpPost(url, buildJsonString(body), "application/json");
    }

    /**
     * Send a semantic resume text via A2A SendMessage (non-streaming) and return the full JSON response.
     */
    protected HttpResponse<String> sendSemanticResumeSync(String text, String contextId) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "ROLE_USER");
        message.put("messageId", UUID.randomUUID().toString());
        message.put("contextId", contextId);
        message.put("parts", List.of(Map.of("text", text)));

        String id = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("jsonrpc", "2.0", "id", id,
                "method", "SendMessage", "params", Map.of("message", message));
        String url = stack.baseUrl(EDPA2_L1) + "/a2a";
        return sendHttpPost(url, buildJsonString(body), "application/json");
    }

    /**
     * Send a text control message (e.g. "暂停") without waiting for full response.
     */
    protected HttpResponse<String> sendTextControlRaw(String text, String contextId, String taskId) {
        return sendHttpPost(stack.baseUrl(EDPA2_L1) + "/a2a",
                buildA2aBody(text, contextId, taskId, null), "text/event-stream");
    }

    /**
     * Internal: async send that returns taskId early from the SSE stream.
     * Uses raw HTTP to read the SSE stream — the SDK's sendMessageStreaming
     * produces 0 events for EDPA2.0-L1 (agent card doesn't advertise streaming).
     */
    @SuppressWarnings("unchecked")
    private AsyncA2aRound sendA2aAsyncRaw(String agent, String text, String contextId, String taskId) {
        String cid = contextId != null ? contextId : UUID.randomUUID().toString();
        String body = buildA2aBody(text, cid, taskId, null);
        String url = stack.baseUrl(agent) + "/a2a";

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(8))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<java.io.InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            throw new IllegalStateException("Async A2A send failed: " + url, e);
        }

        if (response.statusCode() != 200) {
            throw new IllegalStateException("A2A returned HTTP " + response.statusCode());
        }

        AtomicReference<String> taskIdRef = new AtomicReference<>(null);
        AtomicReference<String> contextIdRef = new AtomicReference<>(cid);
        AtomicReference<TaskState> latestStateRef = new AtomicReference<>(null);
        List<JsonNode> frames = new ArrayList<>();

        CompletableFuture<A2aRound> future = CompletableFuture.supplyAsync(() -> {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || data.equals("[DONE]")) continue;
                    JsonNode frame = JSON.readTree(data);
                    frames.add(frame);
                    // Debug: print every frame
                    String frameStr = frame.toString();
                    System.out.println("[DEBUG SSE frame] "
                            + frameStr.substring(0, Math.min(400, frameStr.length())));
                    JsonNode result = frame.path("result");
                    JsonNode task = result.path("task");
                    if (task.isMissingNode()) task = result.path("statusUpdate");
                    if (!task.isMissingNode()) {
                        JsonNode tid = task.path("taskId");
                        if (tid.isTextual() && !tid.asText().isBlank() && taskIdRef.get() == null) {
                            taskIdRef.set(tid.asText());
                        }
                        JsonNode c = task.path("contextId");
                        if (c.isTextual() && !c.asText().isBlank()) contextIdRef.set(c.asText());
                        JsonNode stateNode = task.path("status").path("state");
                        if (stateNode.isTextual() && !stateNode.asText().isBlank()) {
                            latestStateRef.set(parseTaskState(stateNode.asText()));
                        }
                    }
                    if (taskIdRef.get() == null) {
                        JsonNode tid = result.path("id");
                        if (tid.isTextual() && !tid.asText().isBlank()) {
                            taskIdRef.set(tid.asText());
                        }
                    }
                }
            } catch (java.io.IOException e) {
                // Stream closed — expected when pause interrupts
            }
            return parseA2aFrames(response.statusCode(), frames, contextIdRef.get());
        });

        String resolvedTaskId = waitForTaskId(taskIdRef, Duration.ofSeconds(30));
        System.out.println("[DEBUG sendA2aAsyncRaw] resolvedTaskId=" + resolvedTaskId
                + " latestState=" + latestStateRef.get());
        return new AsyncA2aRound(resolvedTaskId, contextIdRef.get(), future, latestStateRef);
    }

    /**
     * Wait up to {@code timeout} for a taskId to appear in the AtomicReference.
     */
    private String waitForTaskId(AtomicReference<String> ref, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (ref.get() != null) return ref.get();
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
        return ref.get(); // may be null if task started very slowly
    }

    /**
     * Wait up to {@code timeout} for the async task to reach the given state.
     * Returns true if the state was observed, false on timeout.
     * Useful for detecting INPUT_REQUIRED without blocking on the SSE stream
     * (which stays open for INPUT_REQUIRED tasks).
     */
    protected boolean waitForState(AsyncA2aRound async, TaskState state, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            // 1. Check if the SSE stream already reported this state
            if (async.hasState(state)) return true;
            // 2. Fallback: poll GetTask — INPUT_REQUIRED is published as task_interaction,
            //    not always as a statusUpdate in the SSE stream
            if (async.taskId() != null) {
                try {
                    org.a2aproject.sdk.spec.Task snapshot = client(EDPA2_L1).getTask(async.taskId());
                    if (snapshot != null && snapshot.status() != null) {
                        TaskState snapState = snapshot.status().state();
                        System.out.println("[DEBUG waitForState] getTask state=" + snapState
                                + " taskId=" + async.taskId());
                        if (state.equals(snapState)) {
                            async.latestState().set(state);
                            return true;
                        }
                    }
                } catch (Exception e) {
                    // GetTask might fail if task hasn't been registered yet; keep polling
                }
            }
            try { Thread.sleep(500); } catch (InterruptedException e) { break; }
        }
        return async.hasState(state);
    }

    /**
     * Build an A2A JSON-RPC request body string.
     */
    @SuppressWarnings("unchecked")
    private String buildA2aBody(String text, String contextId, String taskId, ObjectNode controlPart) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "ROLE_USER");
        message.put("messageId", UUID.randomUUID().toString());
        message.put("contextId", contextId);
        if (text != null) {
            message.put("parts", List.of(Map.of("text", text)));
        } else if (controlPart != null) {
            message.put("parts", List.of(controlPart));
        }
        if (taskId != null) message.put("taskId", taskId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", UUID.randomUUID().toString());
        body.put("method", "SendStreamingMessage");
        body.put("params", Map.of("message", message));
        try {
            return JSON.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize A2A body", e);
        }
    }

    /**
     * Simple synchronous HTTP POST — returns the full response.
     */
    protected HttpResponse<String> sendHttpPost(String url, String jsonBody, String accept) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(4))
                    .header("Content-Type", "application/json")
                    .header("Accept", accept)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HTTP POST failed: " + url, e);
        }
    }

    /**
     * Serialize a Map to JSON string (wraps JsonProcessingException).
     */
    protected String buildJsonString(Map<String, Object> body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize JSON", e);
        }
    }

    /**
     * Raw HTTP A2A send — same pattern as Feat062Fixture.send().
     * Uses SendStreamingMessage (SSE) or SendMessage (JSON).
     */
    @SuppressWarnings("unchecked")
    protected A2aRound sendA2aRaw(String agent, String text, String contextId, String taskId, boolean streaming) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "ROLE_USER");
        message.put("messageId", UUID.randomUUID().toString());
        message.put("contextId", contextId != null ? contextId : UUID.randomUUID().toString());
        message.put("parts", List.of(Map.of("text", text)));
        if (taskId != null) message.put("taskId", taskId);
        return doRpc(agent, streaming ? "SendStreamingMessage" : "SendMessage",
                Map.of("message", message), contextId);
    }

    @SuppressWarnings("unchecked")
    protected A2aRound sendA2aControlRaw(String agent, ObjectNode controlDataPart,
                                          String contextId, String taskId, boolean streaming) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "ROLE_USER");
        message.put("messageId", UUID.randomUUID().toString());
        message.put("contextId", contextId);
        message.put("parts", List.of(controlDataPart));
        if (taskId != null) message.put("taskId", taskId);
        return doRpc(agent, streaming ? "SendStreamingMessage" : "SendMessage",
                Map.of("message", message), contextId);
    }

    /**
     * Core RPC: raw HTTP POST + SSE/JSON parsing (same as Feat062Fixture.rpc()).
     */
    private A2aRound doRpc(String agent, String method, Object params, String contextId) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
        boolean streaming = method.equals("SendStreamingMessage");
        String url = stack.baseUrl(agent) + "/a2a";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", streaming ? "text/event-stream" : "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            List<JsonNode> frames = new ArrayList<>();
            String responseBody = response.body();
            if (responseBody != null && !responseBody.isBlank()) {
                if (streaming) {
                    for (String line : responseBody.split("\\R")) {
                        if (line.startsWith("data:")) {
                            String data = line.substring(5).trim();
                            if (!data.isEmpty() && !data.equals("[DONE]")) {
                                frames.add(JSON.readTree(data));
                            }
                        }
                    }
                } else {
                    frames.add(JSON.readTree(responseBody.trim()));
                }
            }

            return parseA2aFrames(response.statusCode(), frames, contextId);
        } catch (Exception e) {
            throw new IllegalStateException("A2A RPC failed: " + method + " -> " + url, e);
        }
    }

    /**
     * Parse A2A frames into an A2aRound (state + taskId + contextId).
     */
    private A2aRound parseA2aFrames(int httpStatus, List<JsonNode> frames, String contextId) {
        TaskState state = TaskState.UNRECOGNIZED;
        String taskId = null;
        String resolvedContextId = contextId;

        // Debug: print first frame structure for diagnosis
        if (!frames.isEmpty() && taskId == null) {
            JsonNode first = frames.get(0);
            System.out.println("[DEBUG parseA2aFrames] frames count=" + frames.size()
                    + " firstFrame=" + first.toString().substring(0, Math.min(500, first.toString().length())));
        }

        for (JsonNode frame : frames) {
            JsonNode result = frame.path("result");
            // Try result.task (non-streaming) or result.statusUpdate (streaming)
            JsonNode task = result.path("task");
            if (task.isMissingNode()) {
                task = result.path("statusUpdate");
            }
            if (!task.isMissingNode()) {
                JsonNode stateNode = task.path("status").path("state");
                if (stateNode.isTextual() && !stateNode.asText().isBlank()) {
                    state = parseTaskState(stateNode.asText());
                }
                // A2A SDK uses "taskId" field (not "id")
                JsonNode tid = task.path("taskId");
                if (tid.isTextual() && !tid.asText().isBlank()) taskId = tid.asText();
                JsonNode cid = task.path("contextId");
                if (cid.isTextual() && !cid.asText().isBlank()) resolvedContextId = cid.asText();
            }
            // Also try result.id
            if (taskId == null) {
                JsonNode tid = result.path("id");
                if (tid.isTextual() && !tid.asText().isBlank()) taskId = tid.asText();
            }
            // Also try top-level taskId
            if (taskId == null) {
                JsonNode tid = frame.path("taskId");
                if (tid.isTextual() && !tid.asText().isBlank()) taskId = tid.asText();
            }
        }

        return new A2aRound(state, taskId, resolvedContextId);
    }

    private static TaskState parseTaskState(String value) {
        try {
            return TaskState.valueOf(value);
        } catch (IllegalArgumentException e) {
            return TaskState.UNRECOGNIZED;
        }
    }

    // ── REST helpers ──────────────────────────────────────────────────────

    /** REST path template for Custom REST (must match moa-l1 query-path config). */
    protected static final String REST_PROJECT_ID = "default";
    protected static final String REST_AGENT_ID = "moa-demo-l1";

    /**
     * Build the Custom REST conversation URL for a given contextId.
     * Per 1100.patch: POST /v1/{project_id}/agents/{agent_id}/conversations/{conversation_id}
     */
    protected String restConversationUrl(String conversationId) {
        return stack.baseUrl(EDPA2_L1) + "/v1/" + REST_PROJECT_ID
                + "/agents/" + REST_AGENT_ID
                + "/conversations/" + conversationId;
    }

    /**
     * Send a REST pause control to L1's Custom REST conversation URL.
     * Per 1100.patch: body is {"moa.control":{"action":"pause"},"stream":false}
     * conversation_id is in the URL path, NOT the body.
     * @param stream true for SSE response, false for JSON response
     */
    protected HttpResponse<String> sendRestPause(String conversationId, boolean stream) {
        ObjectNode body = JSON.createObjectNode();
        body.putObject("moa.control").put("action", "pause");
        body.put("stream", stream);
        return sendRestPost(restConversationUrl(conversationId),
                body.toString(), stream ? "text/event-stream" : "application/json");
    }

    /**
     * Send a raw REST POST to any path on L1.
     */
    protected HttpResponse<String> sendRestRaw(String path, String jsonBody, String accept) {
        return sendRestPost(stack.baseUrl(EDPA2_L1) + path, jsonBody,
                accept != null ? accept : "application/json");
    }

    /**
     * Send a raw REST control body to L1's conversation URL (for error testing).
     * The body is sent as-is; conversation_id is in the URL path.
     */
    protected HttpResponse<String> sendRestControlRaw(String conversationId, String jsonBody, boolean stream) {
        return sendRestPost(restConversationUrl(conversationId), jsonBody,
                stream ? "text/event-stream" : "application/json");
    }

    private HttpResponse<String> sendRestPost(String url, String body, String accept) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", accept)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .version(HttpClient.Version.HTTP_1_1)
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("REST request failed: " + url, e);
        }
    }

    // ── Build helpers ─────────────────────────────────────────────────────

    /**
     * Build a moa.control DataPart JSON for pause/resume actions.
     * The DataPart payload is: {"moa.control":{"action":"<action>"}}
     */
    protected static ObjectNode controlDataPart(String action) {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode edpa2Control = root.putObject("data");
        edpa2Control.putObject("moa.control").put("action", action);
        return root;
    }

    /**
     * Build an invalid control DataPart with extra fields (for error testing).
     * E.g. {"data":{"moa.control":{"action":"pause","extra":1}}}
     */
    protected static ObjectNode invalidControlDataPart(String action, String extraKey, int extraValue) {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode edpa2Control = root.putObject("data");
        ObjectNode control = edpa2Control.putObject("moa.control");
        control.put("action", action);
        control.put(extraKey, extraValue);
        return root;
    }

    /**
     * Build a control DataPart with a missing action field.
     * E.g. {"data":{"moa.control":{}}}
     */
    protected static ObjectNode missingActionControlDataPart() {
        ObjectNode root = JSON.createObjectNode();
        root.putObject("data").putObject("moa.control");
        return root;
    }

    /**
     * Build a mixed DataPart: control + text in same parts list (for error testing).
     */
    protected static List<Object> mixedControlAndTextParts(String action, String text) {
        ObjectNode controlPart = controlDataPart(action);
        List<Object> parts = new ArrayList<>();
        parts.add(controlPart);
        parts.add(Map.of("text", text));
        return parts;
    }

    /**
     * Extract the receipt code from a pause response body.
     * Looks for "code":"<VALUE>" in the response.
     */
    protected static String extractReceiptCode(String body) {
        if (body == null) return null;
        try {
            JsonNode root = JSON.readTree(body);
            // A2A: result.task.status.message.parts[].data.code
            JsonNode parts = root.path("result").path("task").path("status")
                    .path("message").path("parts");
            if (parts.isArray()) {
                for (JsonNode part : parts) {
                    JsonNode code = part.path("data").path("code");
                    if (code.isTextual()) return code.asText();
                }
            }
            // REST: custom_rsp_data.data.code
            JsonNode restCode = root.path("custom_rsp_data").path("data").path("code");
            if (restCode.isTextual()) return restCode.asText();
            // Fallback: search for "code":"..." anywhere
            if (body.contains("\"code\":\"")) {
                int idx = body.indexOf("\"code\":\"") + 8;
                int end = body.indexOf("\"", idx);
                if (end > idx) return body.substring(idx, end);
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    /**
     * Extract the delivery field from a pause response body.
     */
    protected static String extractDelivery(String body) {
        if (body == null) return null;
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode parts = root.path("result").path("task").path("status")
                    .path("message").path("parts");
            if (parts.isArray()) {
                for (JsonNode part : parts) {
                    JsonNode delivery = part.path("data").path("delivery");
                    if (delivery.isTextual()) return delivery.asText();
                }
            }
            JsonNode restDelivery = root.path("custom_rsp_data").path("data").path("delivery");
            if (restDelivery.isTextual()) return restDelivery.asText();
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    /**
     * Check if the response body contains an ACCEPTED or DUPLICATE code.
     */
    protected static boolean hasAcceptedReceipt(String body) {
        String code = extractReceiptCode(body);
        return "ACCEPTED".equals(code) || "DUPLICATE".equals(code);
    }

    /**
     * Check if the response body contains a specific code.
     */
    protected static boolean hasCode(String body, String expectedCode) {
        return expectedCode.equals(extractReceiptCode(body));
    }

    /**
     * Extract the receipt Task ID from a pause response body.
     * The receipt Task ID is in result.task.id (A2A) or custom_rsp_data.data.taskId (REST).
     */
    protected static String extractReceiptTaskId(String body) {
        if (body == null) return null;
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode a2aId = root.path("result").path("task").path("id");
            if (a2aId.isTextual()) return a2aId.asText();
            JsonNode restId = root.path("custom_rsp_data").path("data").path("taskId");
            if (restId.isTextual()) return restId.asText();
        } catch (Exception e) { /* ignore */ }
        return null;
    }

    protected static String ctx(String suffix) {
        return "ctx-f088-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Result of one A2A round: terminal state + taskId + contextId.
     */
    protected record A2aRound(TaskState state, String taskId, String contextId) {
        public boolean isInputRequired() {
            return state == TaskState.TASK_STATE_INPUT_REQUIRED;
        }
        public boolean isCompleted() {
            return state == TaskState.TASK_STATE_COMPLETED;
        }
        public boolean isFailed() {
            return state == TaskState.TASK_STATE_FAILED;
        }
    }

    /**
     * Async A2A round: taskId is available immediately (task is EXECUTING),
     * while the final state is delivered via a CompletableFuture when the
     * SSE stream completes (either normally or via pause interruption).
     * The latestStateRef tracks the most recent TaskState observed in the
     * SSE stream — useful for detecting INPUT_REQUIRED without waiting for
     * the stream to close (which may never happen for INPUT_REQUIRED tasks).
     */
    protected record AsyncA2aRound(String taskId, String contextId,
                                    CompletableFuture<A2aRound> finalResult,
                                    java.util.concurrent.atomic.AtomicReference<TaskState> latestState) {
        /**
         * Check if the task has reached the given state without blocking.
         */
        public boolean hasState(TaskState state) {
            return latestState.get() == state;
        }
    }
}

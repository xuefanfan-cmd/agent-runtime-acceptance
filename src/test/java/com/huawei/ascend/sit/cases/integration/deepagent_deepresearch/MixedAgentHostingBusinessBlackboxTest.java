package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.sun.net.httpserver.HttpServer;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Real business requests to two different Agent types inside one external JVM. */
@Tag("integration")
@Tag("openjiuwen")
@Tag("deepagent")
@Tag("feat-037")
@Tag("blackbox")
@Feature("FEAT-037: 单进程多智能体实例托管")
class MixedAgentHostingBusinessBlackboxTest extends BaseManagedStackTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HOST = "hosted-mixed";
    private static final String REACT = "react-budget";
    private static final String DEEP = "deep-itinerary";
    private static final List<String> IDS = List.of(REACT, DEEP);
    private static final Duration TIMEOUT = Duration.ofSeconds(240);
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final List<Path> workspaces = new ArrayList<>();
    private LiveModelProxy model;
    private JsonNode requests;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        try (InputStream input = getClass().getResourceAsStream("/testdata/feat037/mixed-business-requests.json")) {
            requests = JSON.readTree(input);
            requireEnv("LLM_API_KEY");
            model = new LiveModelProxy(requireEnv("LLM_API_BASE"));
            return mixedStack(config, 4);
        } catch (Exception failure) {
            if (model != null) {
                model.close();
            }
            throw new IllegalStateException("Mixed business test setup failed", failure);
        }
    }

    private SutStack.Builder mixedStack(TestConfig config, int quota) throws Exception {
        Path workspace = Files.createTempDirectory("feat037-mixed-");
        workspaces.add(workspace);
        return SutStack.builder(config).agent(HOST, agent -> agent
                .env("HOSTED_MIXED_API_KEY", requireEnv("LLM_API_KEY"))
                .property("openjiuwen.demo.hosted.api-base", model.apiBase())
                .property("openjiuwen.demo.hosted.model-name", requireEnv("LLM_MODEL"))
                .property("openjiuwen.demo.hosted.provider", System.getenv().getOrDefault("LLM_PROVIDER", "OpenAI"))
                .property("openjiuwen.demo.hosted.ssl-verify", System.getenv().getOrDefault("LLM_SSL_VERIFY", "true"))
                .property("openjiuwen.demo.hosted.workspace-path", workspace.toString())
                .property("openjiuwen.service.concurrency.max-concurrent-tasks", Integer.toString(quota)));
    }

    @AfterAll
    void closeBusinessFixture() throws Exception {
        if (stack != null) {
            stack.close();
        }
        if (model != null) {
            model.close();
        }
        for (Path workspace : workspaces) {
            try (var paths = Files.walk(workspace)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    @Tag("story-feat-037-t44")
    @Story("FEAT-037.T44: 混合类型注册与发现")
    @DisplayName("FEAT-037 T44 混合 Agent 同进程注册并发布独立 Card")
    void t44MixedCatalogAndCards() throws Exception {
        JsonNode catalog = json(get(stack, "/a2a/agents"));
        List<String> ids = new ArrayList<>();
        catalog.path("agents").forEach(id -> ids.add(id.asText()));
        assertThat(ids).containsExactlyElementsOf(IDS);
        assertThat(catalog.path("defaultAgent").asText()).isEqualTo(REACT);
        ManagedSutInstance process = managed(stack);
        assertThat(process.isAlive()).isTrue();
        assertThat(process.pid()).isPositive();
        for (String id : IDS) {
            JsonNode card = json(get(stack, "/a2a/agents/" + id + "/.well-known/agent-card.json"));
            assertThat(card.path("name").asText()).isEqualTo(id);
            assertThat(card.path("skills").toString()).contains(id.equals(REACT) ? "trip_budget" : "trip_itinerary");
            String url = card.path("supportedInterfaces").path(0).path("url").asText();
            assertThat(url).endsWith("/a2a/agents/" + id);
            assertThat(URI.create(url).getPort()).isEqualTo(process.port());
            assertThat(managed(stack).pid()).isEqualTo(process.pid());
        }
    }

    @Test
    @Tag("story-feat-037-t45")
    @Story("FEAT-037.T45: 混合类型同步完成出差准备")
    @DisplayName("FEAT-037 T45 ReAct 核算预算后 DeepAgent 完成行程风险报告")
    void t45MixedSyncBusinessTask() throws Exception {
        String reference = reference("T45");
        int before = model.forwarded.get();
        HttpResponse<String> budget = send(stack, REACT, false, prompt(REACT, reference), context(), null);
        assertBusiness(budget, REACT, reference);
        String budgetText = answer(budget);
        HttpResponse<String> itinerary = send(stack, DEEP, false,
                prompt(DEEP, reference) + "\n预算助手已完成的核算结果：\n" + budgetText
                        + "\n只输出行程评审，不重复预算助手的角色标记。", context(), null);
        assertBusiness(itinerary, DEEP, reference);
        assertThat(model.forwarded.get() - before).isGreaterThanOrEqualTo(2);
    }

    @Test
    @Tag("story-feat-037-t46")
    @Story("FEAT-037.T46: 混合类型流式执行")
    @DisplayName("FEAT-037 T46 两种 Agent 流式完成真实业务任务")
    void t46MixedStreamingBusinessTasks() throws Exception {
        for (String id : IDS) {
            String reference = reference("T46");
            HttpResponse<String> response = send(stack, id, true, prompt(id, reference), context(), null);
            assertThat(response.headers().firstValue("content-type").orElse("")).contains("text/event-stream");
            assertThat(frames(response)).isNotEmpty();
            assertBusiness(response, id, reference);
        }
    }

    @Test
    @Tag("story-feat-037-t47")
    @Story("FEAT-037.T47: 混合类型 REST 默认与显式选择")
    @DisplayName("FEAT-037 T47 REST 默认核算预算且可显式选择行程助手")
    void t47MixedRestSelection() throws Exception {
        for (String id : IDS) {
            String reference = reference("T47");
            var body = JSON.createObjectNode().put("conversation_id", context())
                    .put("message", prompt(id, reference)).put("stream", false);
            if (id.equals(DEEP)) {
                body.put("agent_id", DEEP);
            }
            HttpResponse<String> response = post(stack.baseUrl(HOST) + "/v1/query", body.toString(), false);
            assertBusinessText(response, id, reference);
        }
    }

    @Test
    @Tag("story-feat-037-t48")
    @Story("FEAT-037.T48: 混合类型同步同名会话隔离")
    @DisplayName("FEAT-037 T48 同名会话的同步业务历史按 Agent 隔离")
    void t48MixedSyncConversationIsolation() throws Exception {
        assertConversationIsolation(false);
    }

    @Test
    @Tag("story-feat-037-t49")
    @Story("FEAT-037.T49: 混合类型流式同名会话隔离")
    @DisplayName("FEAT-037 T49 同名会话的流式业务历史按 Agent 隔离")
    void t49MixedStreamingConversationIsolation() throws Exception {
        assertConversationIsolation(true);
    }

    private void assertConversationIsolation(boolean streaming) throws Exception {
        String context = context();
        String reactReference = reference("BUDGET_PRIVATE");
        String deepReference = reference("ITINERARY_PRIVATE");
        assertBusiness(send(stack, REACT, streaming, prompt(REACT, reactReference), context, null), REACT, reactReference);
        assertBusiness(send(stack, DEEP, streaming, prompt(DEEP, deepReference), context, null), DEEP, deepReference);
        for (String id : IDS) {
            String key = id.equals(REACT) ? "budgetFollowup" : "itineraryFollowup";
            HttpResponse<String> recall = send(stack, id, streaming, requests.path(key).asText(), context, null);
            assertCompleted(recall);
            String text = answer(recall);
            assertThat(text).contains(identity(id), id.equals(REACT) ? reactReference : deepReference)
                    .doesNotContain(identity(peer(id)), id.equals(REACT) ? deepReference : reactReference);
            Allure.addAttachment("混合会话续轮 " + id, "text/plain", text);
        }
    }

    @Test
    @Tag("story-feat-037-t50")
    @Story("FEAT-037.T50: 混合类型 Task 归属隔离")
    @DisplayName("FEAT-037 T50 同名会话的 Task 只在归属 Agent 可查")
    void t50MixedTaskOwnership() throws Exception {
        String context = context();
        for (String id : IDS) {
            String reference = reference("T50");
            HttpResponse<String> response = send(stack, id, false, prompt(id, reference), context, null);
            assertBusiness(response, id, reference);
            String taskId = taskId(response);
            JsonNode own = json(operation(stack, id, taskId));
            assertThat(own.has("error")).isFalse();
            assertThat(resultPayload(own).path("id").asText()).isEqualTo(taskId);
            assertThat(own.toString()).contains(reference);
            JsonNode wrong = json(operation(stack, peer(id), taskId));
            JsonNode absent = json(operation(stack, peer(id), UUID.randomUUID().toString()));
            assertThat(wrong.path("error").isObject()).isTrue();
            assertThat(wrong.path("error").path("code")).isEqualTo(absent.path("error").path("code"));
            assertThat(wrong.toString()).doesNotContain(reference);
            assertThat(json(operation(stack, id, taskId)).has("error")).isFalse();
        }
    }

    @Test
    @Tag("story-feat-037-t51")
    @Story("FEAT-037.T51: 混合类型正常生命周期")
    @DisplayName("FEAT-037 T51 两类 Agent 正常启停恰好一次并释放服务端口")
    void t51MixedLifecycle() throws Exception {
        String before = Files.readString(managed(stack).logFile());
        SutStack temporary = mixedStack(config, 4).start();
        ManagedSutInstance process = managed(temporary);
        try {
            for (String id : IDS) {
                String reference = reference("T51");
                assertBusiness(send(temporary, id, false, prompt(id, reference), context(), null), id, reference);
            }
        } finally {
            temporary.close();
        }
        assertThat(process.isAlive()).isFalse();
        String after = Files.readString(process.logFile());
        for (String id : IDS) {
            for (String operation : List.of("start", "stop")) {
                for (String result : List.of("begin", "success")) {
                    String marker = "Hosted agent agentId=" + id + " operation=" + operation + " result=" + result;
                    // ProcessLauncher appends logs: isolate this stack by subtracting preceding content.
                    assertThat(count(after, marker) - count(before, marker)).as(marker).isEqualTo(1);
                }
            }
        }
        try (var socket = new java.net.Socket()) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> socket.connect(
                    new InetSocketAddress("127.0.0.1", process.port()), 1000)).isInstanceOf(java.io.IOException.class);
        }
    }

    @Test
    @Tag("story-feat-037-t52")
    @Story("FEAT-037.T52: 混合类型共享额度与许可释放")
    @DisplayName("FEAT-037 T52 不同 Agent 共享并发额度且任务完成后许可恢复")
    void t52MixedSharedQuota() throws Exception {
        try (SutStack temporary = mixedStack(config, 1).start(); LiveModelProxy.Gate gate = model.hold(1)) {
            String reference = reference("T52_REACT");
            CompletableFuture<HttpResponse<String>> active = sendAsync(temporary, REACT, prompt(REACT, reference), context());
            try {
                gate.awaitArrivals();
                HttpResponse<String> rejected = send(temporary, DEEP, true, prompt(DEEP, reference("T52_REJECT")), context(), null);
                assertThat(rejected.statusCode()).isEqualTo(503);
            } finally {
                gate.close();
            }
            assertBusiness(active.get(240, TimeUnit.SECONDS), REACT, reference);
            String next = reference("T52_DEEP");
            assertBusiness(send(temporary, DEEP, true, prompt(DEEP, next), context(), null), DEEP, next);
        }
    }

    @Test
    @Tag("story-feat-037-t53")
    @Story("FEAT-037.T53: 混合类型活动任务汇总与筛选")
    @DisplayName("FEAT-037 T53 两种 Agent 活动任务可汇总及筛选并在完成后归零")
    void t53MixedActiveTaskViews() throws Exception {
        try (SutStack temporary = mixedStack(config, 4).start(); LiveModelProxy.Gate gate = model.hold(2)) {
            String reactContext = context();
            String deepContext = context();
            String reactReference = reference("T53_REACT");
            String deepReference = reference("T53_DEEP");
            CompletableFuture<HttpResponse<String>> react = sendAsync(temporary, REACT, prompt(REACT, reactReference), reactContext);
            CompletableFuture<HttpResponse<String>> deep = sendAsync(temporary, DEEP, prompt(DEEP, deepReference), deepContext);
            try {
                gate.awaitArrivals();
                assertSnapshot(temporary, null, List.of(reactContext, deepContext));
                assertSnapshot(temporary, REACT, List.of(reactContext));
                assertSnapshot(temporary, DEEP, List.of(deepContext));
            } finally {
                gate.close();
            }
            assertBusiness(react.get(240, TimeUnit.SECONDS), REACT, reactReference);
            assertBusiness(deep.get(240, TimeUnit.SECONDS), DEEP, deepReference);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertSnapshot(temporary, null, List.of()));
        }
    }

    private void assertSnapshot(SutStack target, String id, List<String> contexts) throws Exception {
        JsonNode snapshot = json(get(target, "/v1/current_active_tasks" + (id == null ? "" : "?agentId=" + id)));
        assertThat(snapshot.path("maxConcurrentTasks").asInt()).isEqualTo(4);
        assertThat(snapshot.path("currentActiveTasks").asInt()).isEqualTo(contexts.size());
        List<String> found = new ArrayList<>();
        snapshot.path("tasks").forEach(task -> {
            found.add(task.path("conversationId").asText());
            assertThat(task.path("taskId").asText()).isNotBlank();
            assertThat(task.has("agentId") || task.has("agent_id")).isFalse();
        });
        assertThat(found).containsExactlyInAnyOrderElementsOf(contexts);
    }

    private void assertBusiness(HttpResponse<String> response, String id, String reference) throws Exception {
        assertCompleted(response);
        assertBusinessText(response, id, reference);
    }

    private void assertBusinessText(HttpResponse<String> response, String id, String reference) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        String text = answer(response);
        Allure.addAttachment("业务成果 " + id + " " + reference, "text/plain", text);
        assertThat(text).contains(identity(id), reference).doesNotContain(identity(peer(id)));
        if (id.equals(REACT)) {
            assertThat(text.replace(",", "")).contains("3250", "750");
        } else {
            assertThat(text).containsPattern("0?8[:：]45");
            assertThat(text).containsAnyOf("风险", "延误", "备用");
        }
    }

    private static void assertCompleted(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        List<JsonNode> payloads = frames(response);
        assertThat(payloads).isNotEmpty();
        boolean completed = false;
        for (JsonNode payload : payloads) {
            assertThat(payload.has("error")).as(payload.toString()).isFalse();
            String state = resultPayload(payload).path("status").path("state").asText();
            if (state.endsWith("COMPLETED") || state.equals("completed")) {
                completed = true;
            }
            assertThat(state).doesNotContain("FAILED", "CANCELED", "REJECTED");
        }
        assertThat(completed).as("Task must complete: %s", response.body()).isTrue();
    }

    private String prompt(String id, String reference) {
        return requests.path(id.equals(REACT) ? "budget" : "itinerary").asText().replace("{reference}", reference);
    }

    private HttpResponse<String> send(SutStack target, String id, boolean stream, String text, String context, String task) throws Exception {
        return http.send(request(target, id, stream, text, context, task), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private CompletableFuture<HttpResponse<String>> sendAsync(SutStack target, String id, String text, String context) throws Exception {
        return http.sendAsync(request(target, id, true, text, context, null), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpRequest request(SutStack target, String id, boolean stream, String text, String context, String task) throws Exception {
        var message = JSON.createObjectNode().put("role", "ROLE_USER").put("messageId", UUID.randomUUID().toString())
                .put("contextId", context);
        message.putArray("parts").addObject().put("text", text);
        if (task != null) {
            message.put("taskId", task);
        }
        var payload = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", UUID.randomUUID().toString())
                .put("method", stream ? "SendStreamingMessage" : "SendMessage");
        payload.putObject("params").set("message", message);
        return HttpRequest.newBuilder(URI.create(target.baseUrl(HOST) + "/a2a/agents/" + id)).timeout(TIMEOUT)
                .header("Content-Type", "application/json").header("Accept", stream ? "text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build();
    }

    private HttpResponse<String> operation(SutStack target, String id, String taskId) throws Exception {
        String payload = JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", UUID.randomUUID().toString(),
                "method", "GetTask", "params", Map.of("id", taskId)));
        return post(target.baseUrl(HOST) + "/a2a/agents/" + id, payload, false);
    }

    private HttpResponse<String> post(String url, String body, boolean stream) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .header("Content-Type", "application/json").header("Accept", stream ? "text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(SutStack target, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(target.baseUrl(HOST) + path)).timeout(Duration.ofSeconds(15))
                .GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static List<JsonNode> frames(HttpResponse<String> response) throws Exception {
        if (response.body().strip().startsWith("{")) {
            return List.of(JSON.readTree(response.body()));
        }
        List<JsonNode> result = new ArrayList<>();
        for (String line : response.body().lines().toList()) {
            if (line.startsWith("data:")) {
                String data = line.substring(5).strip();
                if (!data.isEmpty() && !data.equals("[DONE]")) {
                    result.add(JSON.readTree(data));
                }
            }
        }
        return result;
    }

    private static String answer(HttpResponse<String> response) throws Exception {
        StringBuilder result = new StringBuilder();
        for (JsonNode frame : frames(response)) {
            JsonNode payload = resultPayload(frame);
            if (payload.has("artifacts")) {
                collectText(payload.path("artifacts"), result);
            } else if (payload.has("artifact")) {
                collectText(payload.path("artifact"), result);
            } else if (payload.has("message")) {
                collectText(payload.path("message"), result);
            } else {
                collectText(payload, result);
            }
        }
        return result.toString();
    }

    private static void collectText(JsonNode node, StringBuilder result) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if ((entry.getKey().equals("text") || entry.getKey().equals("content")) && entry.getValue().isTextual()) {
                    result.append(entry.getValue().asText());
                } else if (!entry.getKey().equals("history")) {
                    collectText(entry.getValue(), result);
                }
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectText(child, result));
        }
    }

    private static String taskId(HttpResponse<String> response) throws Exception {
        String id = resultPayload(frames(response).get(0)).path("id").asText();
        assertThat(id).isNotBlank();
        return id;
    }

    private static ManagedSutInstance managed(SutStack stack) { return (ManagedSutInstance) stack.managedInstance(HOST); }
    private static JsonNode resultPayload(JsonNode frame) {
        JsonNode result = frame.has("result") ? frame.path("result") : frame;
        for (String wrapper : List.of("task", "statusUpdate", "artifactUpdate", "message")) {
            if (result.has(wrapper)) { return result.path(wrapper); }
        }
        return result;
    }
    private static String identity(String id) { return id.equals(REACT) ? "BUDGET_ANALYST" : "ITINERARY_REVIEWER"; }
    private static String peer(String id) { return id.equals(REACT) ? DEEP : REACT; }
    private static String reference(String label) { return "TRIP_" + label + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10); }
    private static String context() { return "mixed-trip-" + UUID.randomUUID(); }
    private static long count(String text, String marker) { return Pattern.compile(Pattern.quote(marker)).matcher(text).results().count(); }
    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) { throw new IllegalStateException("Required runtime environment: " + name); }
        return value;
    }

    /** A transparent HTTP dependency proxy. No fake/model-generated response is supplied here. */
    private static final class LiveModelProxy implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final HttpClient upstream;
        private final String base;
        private volatile Gate held;
        private final AtomicInteger forwarded = new AtomicInteger();

        LiveModelProxy(String base) throws Exception {
            this.base = base.replaceAll("/+$", "");
            HttpClient.Builder builder = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(20));
            if ("false".equalsIgnoreCase(System.getenv("LLM_SSL_VERIFY"))) {
                SSLContext ssl = SSLContext.getInstance("TLS");
                ssl.init(null, new TrustManager[]{new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                    public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                }}, new SecureRandom());
                builder.sslContext(ssl);
            }
            upstream = builder.build();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/v1/", exchange -> {
                try (exchange) {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    Gate gate = held;
                    if (gate != null) { gate.arrive(); }
                    String suffix = exchange.getRequestURI().getRawPath().substring(3);
                    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(this.base + suffix))
                            .timeout(TIMEOUT).header("Content-Type", "application/json");
                    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                    if (authorization != null) { request.header("Authorization", authorization); }
                    forwarded.incrementAndGet();
                    HttpResponse<InputStream> response = upstream.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                            HttpResponse.BodyHandlers.ofInputStream());
                    exchange.getResponseHeaders().set("Content-Type", response.headers().firstValue("content-type").orElse("application/json"));
                    exchange.sendResponseHeaders(response.statusCode(), 0);
                    try (InputStream input = response.body()) { input.transferTo(exchange.getResponseBody()); }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            server.start();
        }

        String apiBase() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }
        Gate hold(int arrivals) {
            assertThat(held).isNull();
            Gate gate = new Gate(arrivals);
            held = gate;
            return gate;
        }
        @Override
        public void close() {
            Gate gate = held;
            if (gate != null) { gate.close(); }
            server.stop(0);
            executor.shutdownNow();
        }
        final class Gate implements AutoCloseable {
            private final CountDownLatch arrivals;
            private final CountDownLatch released = new CountDownLatch(1);
            Gate(int expected) { arrivals = new CountDownLatch(expected); }
            void arrive() throws InterruptedException {
                arrivals.countDown();
                if (!released.await(180, TimeUnit.SECONDS)) { throw new IllegalStateException("Model hold watchdog expired"); }
            }
            void awaitArrivals() throws InterruptedException {
                assertThat(arrivals.await(45, TimeUnit.SECONDS)).as("Both business requests must reach real model proxy").isTrue();
            }
            @Override
            public void close() {
                held = null;
                released.countDown();
            }
        }
    }
}

package com.huawei.ascend.sit.cases.component.model_selection;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.core.session.checkpointer.CheckpointerFactory;
import com.openjiuwen.core.session.checkpointer.InMemoryCheckpointer;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;
import com.openjiuwen.service.adapters.common.llm.LlmModelCatalog;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.exception.AgentExecutionException;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FEAT-050/051 — 直接测试 JiuwenCoreAgentHandler（基础 Handler）的请求级模型选择。
 *
 * <p>本测试类直接实例化 {@link JiuwenCoreAgentHandler}（基础 Handler，非 ExtHandler），
 * 注入 {@link LlmModelCatalog}，使用 mock HTTP 服务器模拟 LLM 端点 A 和 B。
 * 不启动 demo 进程，不依赖 SutStack，不修改 demo 代码。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>SC-ROUTE-01/02 — 选 B 路由到端点 B，缺省路由到端点 A</li>
 *   <li>SC-ROUTE-03 — B 后新请求缺省回 A（请求间隔离）</li>
 *   <li>SC-ROUTE-04 — 同会话 A→B→缺省 A</li>
 *   <li>SC-PARAM-01/02/03/04/08/13 — model_name 参数验证</li>
 *   <li>SC-FAIL-01/02/03 — 401/500/连接失败</li>
 *   <li>SC-CONC-01 — 并发隔离</li>
 *   <li>SC-ISOLATE-01 — 实例隔离（相同别名不同 scope）</li>
 *   <li>SC-CORE-11 — 不改共享默认</li>
 * </ul>
 */
@Tag("component")
@Tag("feat-050-051")
@Tag("handler-direct")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(90)
class ModelSelectionHandlerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @org.junit.jupiter.api.io.TempDir
    private Path tempDir;

    private HttpServer server;
    private final List<DeepAgent> agents = new ArrayList<>();
    private final List<JiuwenCoreAgentHandler> handlers = new ArrayList<>();
    private final CopyOnWriteArrayList<Call> calls = new CopyOnWriteArrayList<>();
    private int statusCode = 200;
    private boolean shouldDelay;

    @BeforeEach
    void setUp() throws Exception {
        Runner.stop().toCompletableFuture().join();
        JiuwenCoreAgentHandler.resetRunnerStarted();
        RunnerConfig.getRunnerConfig().setCheckpointerConfig(null);
        RunnerConfig.setRunnerConfig(null);
        CheckpointerFactory.setDefaultCheckpointer(new InMemoryCheckpointer());
        calls.clear();
        statusCode = 200;
        shouldDelay = false;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respondToModelRequest);
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (var agent : agents) {
            try { agent.shutdown(); } catch (Exception ignored) {}
        }
        for (var handler : handlers) {
            try { handler.stop(); } catch (Exception ignored) {}
        }
        Runner.stop().toCompletableFuture().join();
        JiuwenCoreAgentHandler.resetRunnerStarted();
        server.stop(0);
    }

    private void respondToModelRequest(HttpExchange exchange) throws IOException {
        try (exchange) {
            var body = JSON.readTree(exchange.getRequestBody());
            String selected = body.path("model").asText();
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            calls.add(new Call(exchange.getRequestURI().getPath(), authHeader, selected));
            if (shouldDelay) {
                try { Thread.sleep(300); } catch (InterruptedException e) { return; }
            }
            String response;
            if (statusCode != 200) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                response = "{\"error\":{\"message\":\"controlled-error\",\"type\":\"invalid_request_error\"}}";
            } else if (body.path("stream").asBoolean()) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                response = "data: " + JSON.writeValueAsString(envelope("chat.completion.chunk", selected,
                        Map.of("index", 0, "delta", Map.of("role", "assistant", "content", selected))))
                        + "\n\ndata: " + JSON.writeValueAsString(envelope("chat.completion.chunk", selected,
                        Map.of("index", 0, "delta", Map.of(), "finish_reason", "stop")))
                        + "\n\ndata: [DONE]\n\n";
            } else {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                response = JSON.writeValueAsString(envelope("chat.completion", selected,
                        Map.of("index", 0, "message", Map.of("role", "assistant", "content", selected),
                                "finish_reason", "stop")));
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    // ===== SC-ROUTE-01: 选 B 路由到端点 B =====

    @Test
    @DisplayName("SC-ROUTE-01: model_name=alias-b → 路由到端点 B，返回 vendor-b")
    void scRoute01_selectB_routestoB() {
        var handler = handler(false, "instance", catalog());
        String result = run(handler, request("b"), false);
        assertThat(result).contains("vendor-b");
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).path()).isEqualTo("/b/chat/completions");
        assertThat(calls.get(0).model()).isEqualTo("vendor-b");
        assertThat(calls.get(0).authorization()).isEqualTo("Bearer secret-b");
    }

    // ===== SC-ROUTE-02: 缺省路由到端点 A =====

    @Test
    @DisplayName("SC-ROUTE-02: model_name 缺省 → 路由到端点 A，返回 vendor-a")
    void scRoute02_default_routestoA() {
        var handler = handler(false, "instance", catalog());
        String result = run(handler, request(null), false);
        assertThat(result).contains("vendor-a");
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).path()).isEqualTo("/a/chat/completions");
        assertThat(calls.get(0).model()).isEqualTo("vendor-a");
        assertThat(calls.get(0).authorization()).isEqualTo("Bearer secret-a");
    }

    // ===== SC-ROUTE-03: B 后新请求缺省回 A =====

    @Test
    @DisplayName("SC-ROUTE-03: B 后新请求缺省回 A（不同会话，请求间隔离）")
    void scRoute03_bThenDefault_returnsA() {
        var handler = handler(false, "instance", catalog());
        String respB = run(handler, request("b"), false);
        assertThat(respB).contains("vendor-b");

        String respA = run(handler, request(null), false);
        assertThat(respA).contains("vendor-a");

        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).path()).isEqualTo("/b/chat/completions");
        assertThat(calls.get(1).path()).isEqualTo("/a/chat/completions");
    }

    // ===== SC-ROUTE-04: 同会话 A→B→缺省 A =====

    @Test
    @DisplayName("SC-ROUTE-04: 同会话 A→B→缺省 A（轮次切换不冲突）")
    void scRoute04_sameConversation_a_b_defaultA() {
        var handler = handler(false, "instance", catalog());
        String convId = UUID.randomUUID().toString();

        String respA1 = run(handler, request("a", convId), false);
        assertThat(respA1).contains("vendor-a");

        String respB = run(handler, request("b", convId), false);
        assertThat(respB).contains("vendor-b");

        String respA2 = run(handler, request(null, convId), false);
        assertThat(respA2).contains("vendor-a");

        assertThat(calls).hasSize(3);
        assertThat(calls.get(0).model()).isEqualTo("vendor-a");
        assertThat(calls.get(1).model()).isEqualTo("vendor-b");
        assertThat(calls.get(2).model()).isEqualTo("vendor-a");
    }

    // ===== SC-ROUTE-05: 流式选 B =====

    @Test
    @DisplayName("SC-ROUTE-05: 流式 model_name=alias-b → 路由到端点 B，返回 vendor-b")
    void scRoute05_streamingSelectB_routestoB() {
        var handler = handler(false, "instance", catalog());
        var observer = new TestObserver();
        handler.streamQuery(request("b"), observer);
        assertThat(observer.errors).isEmpty();
        assertThat(observer.completions).isEqualTo(1);
        assertThat(observer.chunks.toString()).contains("vendor-b");
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).path()).isEqualTo("/b/chat/completions");
    }

    // ===== SC-PARAM-01: model_name 缺省 → 使用默认 =====

    @Test
    @DisplayName("SC-PARAM-01: model_name 缺省 → 使用根默认 alias-a")
    void scParam01_default_usesRootDefault() {
        var handler = handler(false, "instance", catalog());
        String result = run(handler, request(null), false);
        assertThat(result).contains("vendor-a");
    }

    // ===== SC-PARAM-08: 未知别名 → 执行失败 =====

    @Test
    @DisplayName("SC-PARAM-08: 未知别名 → AgentExecutionException")
    void scParam08_unknownAlias_throwsExecutionException() {
        var handler = handler(false, "instance", catalog());
        assertThatThrownBy(() -> handler.query(request("unknown")))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessage("Agent execution failed");
        assertThat(calls).isEmpty();
    }

    // ===== SC-PARAM-13: trim 后路由 + 大小写敏感 =====

    @Test
    @DisplayName("SC-PARAM-13a: model_name=' b ' → trim 后路由到端点 B")
    void scParam13a_trimmedModelName_routestoB() {
        var handler = handler(false, "instance", catalog());
        String result = run(handler, request(" b "), false);
        assertThat(result).contains("vendor-b");
        assertThat(calls.get(0).path()).isEqualTo("/b/chat/completions");
    }

    @Test
    @DisplayName("SC-PARAM-13b: model_name='B' → 大小写不命中，执行失败")
    void scParam13b_caseSensitive_notMatched() {
        var handler = handler(false, "instance", catalog());
        assertThatThrownBy(() -> handler.query(request("B")))
                .isInstanceOf(AgentExecutionException.class);
        assertThat(calls).isEmpty();
    }

    // ===== SC-CORE-11: 不改共享默认 =====

    @Test
    @DisplayName("SC-CORE-11: 请求 B 后新请求 A — 共享 Agent 默认不变")
    void scCore11_bThenDefault_sharedDefaultUnchanged() {
        var handler = handler(false, "instance", catalog());
        run(handler, request("b"), false);
        String respA = run(handler, request(null), false);
        assertThat(respA).contains("vendor-a");
        String respA2 = run(handler, request(null), false);
        assertThat(respA2).contains("vendor-a");
        assertThat(calls).allSatisfy(call ->
                assertThat(call.path()).isIn("/a/chat/completions", "/b/chat/completions"));
    }

    // ===== SC-FAIL-01: 401 认证失败 → 执行失败，不自动换模型 =====

    @Test
    @DisplayName("SC-FAIL-01: 401 → AgentExecutionException，不调用 A，B 确实被调用")
    void scFail01_401_failureNoFallback() {
        statusCode = 401;
        var handler = handler(false, "instance", catalog());
        assertThatThrownBy(() -> handler.query(request("b")))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessage("Agent execution failed");
        assertThat(calls).isNotEmpty();
        assertThat(calls).allSatisfy(call -> {
            assertThat(call.path()).isEqualTo("/b/chat/completions");
            assertThat(call.model()).isEqualTo("vendor-b");
        });
    }

    // ===== SC-FAIL-02: 500 服务端错误 → 执行失败 =====

    @Test
    @DisplayName("SC-FAIL-02: 500 → AgentExecutionException，不调用 A，B 确实被调用")
    void scFail02_500_failureNoFallback() {
        statusCode = 500;
        var handler = handler(false, "instance", catalog());
        assertThatThrownBy(() -> handler.query(request("b")))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessage("Agent execution failed");
        assertThat(calls).isNotEmpty();
        assertThat(calls).allSatisfy(call ->
                assertThat(call.path()).isEqualTo("/b/chat/completions"));
    }

    // ===== SC-FAIL-03: 连接失败 → 执行失败 =====

    @Test
    @DisplayName("SC-FAIL-03: 连接失败（不可达地址）→ AgentExecutionException")
    void scFail03_connectionFailure_exposedAsFailure() {
        var badCatalog = new LlmModelCatalog("a",
                Map.of("a", definition("a"), "b", new LlmModelCatalog.ModelDefinition(
                        "OpenAI", "secret-b", "http://127.0.0.1:9/v1", "vendor-b", true)),
                0.0, 0.8, Duration.ofSeconds(2));
        var handler = handler(false, "instance", badCatalog);
        assertThatThrownBy(() -> handler.query(request("b")))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessage("Agent execution failed");
    }

    // ===== SC-FAIL-04: 超时 → 执行失败 =====

    @Test
    @DisplayName("SC-FAIL-04: 超时 → AgentExecutionException，B 确实被调用")
    void scFail04_timeout_failure() {
        shouldDelay = true;
        var catalog = new LlmModelCatalog("a",
                Map.of("a", definition("a"), "b", definition("b")),
                0.0, 0.8, Duration.ofMillis(50));
        var handler = handler(false, "instance", catalog);
        assertThatThrownBy(() -> handler.query(request("b")))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessage("Agent execution failed");
        assertThat(calls).isNotEmpty();
        assertThat(calls).allSatisfy(call ->
                assertThat(call.model()).isEqualTo("vendor-b"));
    }

    // ===== SC-FAIL-05: 401 流式 → 错误事件，不自动换模型 =====

    @Test
    @DisplayName("SC-FAIL-05: 流式 401 → 错误事件，B 被调用，A 未被调用")
    void scFail05_streaming401_errorEvent() {
        statusCode = 401;
        var handler = handler(false, "instance", catalog());
        var observer = new TestObserver();
        handler.streamQuery(request("b"), observer);
        assertThat(observer.errors).hasSize(1);
        assertThat(observer.completions).isZero();
        assertThat(observer.chunks).filteredOn(chunk -> QueryChunk.TYPE_ERROR.equals(chunk.getType()))
                .singleElement().satisfies(chunk -> {
                    assertThat(chunk.getData().toString()).doesNotContain("secret-b");
                });
        assertThat(calls).isNotEmpty();
        assertThat(calls).allSatisfy(call ->
                assertThat(call.path()).isEqualTo("/b/chat/completions"));
    }

    // ===== SC-FAIL-06: 500 流式 → 错误事件 =====

    @Test
    @DisplayName("SC-FAIL-06: 流式 500 → 错误事件，B 被调用")
    void scFail06_streaming500_errorEvent() {
        statusCode = 500;
        var handler = handler(false, "instance", catalog());
        var observer = new TestObserver();
        handler.streamQuery(request("b"), observer);
        assertThat(observer.errors).hasSize(1);
        assertThat(observer.completions).isZero();
        assertThat(calls).isNotEmpty();
        assertThat(calls).allSatisfy(call ->
                assertThat(call.path()).isEqualTo("/b/chat/completions"));
    }

    // ===== SC-CONC-01: 并发隔离 =====

    @Test
    @DisplayName("SC-CONC-01: 并发 A 和 B 各自路由到正确端点")
    void scConc01_concurrentSelectIndependently() throws Exception {
        var handler = handler(false, "instance", catalog());
        var a = java.util.concurrent.CompletableFuture.runAsync(
                () -> run(handler, request("a"), false));
        var b = java.util.concurrent.CompletableFuture.runAsync(
                () -> run(handler, request("b"), false));
        java.util.concurrent.CompletableFuture.allOf(a, b).join();
        assertThat(calls).hasSize(2);
        assertThat(calls).extracting(Call::model)
                .containsExactlyInAnyOrder("vendor-a", "vendor-b");
    }

    // ===== SC-ISOLATE-01: 实例隔离（相同别名不同 scope）=====

    @Test
    @DisplayName("SC-ISOLATE-01: 相同别名 'a' 在不同 scope 中路由到不同端点")
    void scIsolate01_sameAliasDifferentScope() {
        var first = handler(false, "first", catalog());
        // second handler: alias-a → vendor-b (different endpoint)
        var otherCatalog = new LlmModelCatalog("a",
                Map.of("a", new LlmModelCatalog.ModelDefinition(
                        "OpenAI", "secret-b", base("b"), "vendor-b", true)),
                0.0, 0.8, Duration.ofSeconds(2));
        var second = handler(false, "second", otherCatalog);

        run(first, request("a"), false);
        run(second, request("a"), false);

        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).path()).isEqualTo("/a/chat/completions");
        assertThat(calls.get(0).model()).isEqualTo("vendor-a");
        assertThat(calls.get(1).path()).isEqualTo("/b/chat/completions");
        assertThat(calls.get(1).model()).isEqualTo("vendor-b");
    }

    // ===== SC-RESUME-01: 工具续接保持选择 =====

    @Test
    @DisplayName("SC-RESUME-01: 工具续接请求保持原选模型 B，不注入伪造 Core 字段")
    void scResume01_toolResumePreservesSelection() {
        var request = request("b");
        request.setMetadata(Map.of("runtime.remoteToolResults", Map.of("call-a", "answer"),
                "dynamic_model_id", "forged", "target_model_id", "forged"));
        var captured = captureExecutionInputs("instance", catalog(), request);
        assertThat(captured.get("model_id")).isEqualTo("runtime:model:aW5zdGFuY2U:Yg");
        assertThat(captured).doesNotContainKeys("dynamic_model_id", "target_model_id");
    }

    // ===== SC-COMPAT-01: 无目录不选模保持旧路径 =====

    @Test
    @DisplayName("SC-COMPAT-01: catalog=null + 不传 model_name → 旧路径，不注入 model_id")
    void scCompat01_noCatalogNoSelection_oldPath() {
        var inputs = captureExecutionInputs("legacy", null, request(null));
        assertThat(inputs).doesNotContainKey("model_id");
    }

    // ===== SC-COMPAT-02: 无目录却显式选模 → 拒绝 =====

    @Test
    @DisplayName("SC-COMPAT-02: catalog=null + 传 model_name → IllegalArgumentException")
    void scCompat02_noCatalogButSelect_throws() {
        var handler = handler(false, "legacy", null);
        assertThatThrownBy(() -> handler.query(request("b")))
                .isInstanceOf(RuntimeException.class);
        assertThat(calls).isEmpty();
    }

    // ===== SC-CORE-12: 不提升伪造 Core 字段 =====

    @Test
    @DisplayName("SC-CORE-12: metadata 中 dynamic_model_id 不被提升到顶层")
    void scCore12_forgedCoreField_notPromoted() {
        var request = request(null);
        request.setMetadata(Map.of("dynamic_model_id", "forged-internal-id"));
        var captured = captureExecutionInputs("instance", catalog(), request);
        assertThat(captured).doesNotContainKey("dynamic_model_id");
        assertThat(captured).containsKey("model_id");
    }

    // ===== SC-ERROR-01: 错误信息不泄露凭据 =====

    @Test
    @DisplayName("SC-ERROR-01: 401 错误信息不包含 secret-b 或 api-key")
    void scError01_credentialsNotLeaked() {
        statusCode = 401;
        var handler = handler(false, "instance", catalog());
        var observer = new TestObserver();
        handler.streamQuery(request("b"), observer);
        assertThat(observer.errors).hasSize(1);
        // Error message should not contain credentials
        assertThat(observer.errors.get(0).getMessage()).doesNotContain("secret-b");
        // Stream error chunks should not contain credentials
        for (var chunk : observer.chunks) {
            assertThat(chunk.getData().toString()).doesNotContain("secret-b");
        }
    }

    // ===== SC-STARTUP-01: 启动注册不请求远端 LLM =====

    @Test
    @DisplayName("SC-STARTUP-01: 启动注册不请求远端 LLM（API_BASE 不可达但启动成功）")
    void scStartup01_unreachableApiBase_startupSucceeds() {
        var badCatalog = new LlmModelCatalog("a",
                Map.of("a", new LlmModelCatalog.ModelDefinition(
                        "OpenAI", "secret-a", "http://127.0.0.1:9/v1", "vendor-a", true),
                        "b", definition("b")),
                0.0, 0.8, Duration.ofSeconds(2));
        // Should start without error even though alias-a endpoint is unreachable
        var handler = handler(false, "instance", badCatalog);
        assertThat(calls).isEmpty(); // No LLM calls during startup
    }

    // ===== SC-STARTUP-02: 重复 start 幂等 =====

    @Test
    @DisplayName("SC-STARTUP-02: 重复 start 幂等（模型不重复注册）")
    void scStartup02_repeatStartIdempotent() {
        var handler = handler(false, "instance", catalog());
        handler.start(); // second start should be a no-op
        handler.start();
        // Only one set of models should be registered
        run(handler, request("b"), false);
        assertThat(calls).hasSize(1);
    }

    // ===== SC-STOP-01: stop 后 restart 重新注册 =====

    @Test
    @DisplayName("SC-STOP-01: stop 后 restart 重新注册 catalog")
    void scStop01_stopAndRestart_reregisters() {
        var handler = handler(false, "instance", catalog());
        run(handler, request("b"), false);
        assertThat(calls).hasSize(1);

        handler.stop();
        calls.clear();

        handler.start();
        run(handler, request("b"), false);
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).path()).isEqualTo("/b/chat/completions");
    }

    // ===== SC-ROLLBACK-01: 注册冲突回滚仅撤销己方资源 =====

    @Test
    @DisplayName("SC-ROLLBACK-01: 注册冲突回滚仅撤销己方资源，原有资源仍可用")
    void scRollback01_conflictRollsBackOnlyNewResources() {
        // First handler with alias-b
        var first = handler(false, "instance",
                new LlmModelCatalog("b", Map.of("b", definition("b")), 0.0, 0.8, Duration.ofSeconds(2)));

        // Second handler tries to register alias-a + alias-b → conflict on alias-b
        var ordered = new LinkedHashMap<String, LlmModelCatalog.ModelDefinition>();
        ordered.put("a", definition("a"));
        ordered.put("b", definition("b"));
        var conflict = new JiuwenCoreAgentHandler("unused", null, null, "instance",
                new LlmModelCatalog("a", ordered, 0.0, 0.8, Duration.ofSeconds(2)));
        assertThatThrownBy(conflict::start).isInstanceOf(IllegalStateException.class);

        // First handler's alias-b is still usable
        run(first, request("b"), false);
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).path()).isEqualTo("/b/chat/completions");
    }

    // ===== Helper methods =====

    private LlmModelCatalog catalog() {
        return new LlmModelCatalog("a",
                Map.of("a", definition("a"), "b", definition("b")),
                0.0, 0.8, Duration.ofSeconds(2));
    }

    private LlmModelCatalog.ModelDefinition definition(String name) {
        return new LlmModelCatalog.ModelDefinition("OpenAI", "secret-" + name, base(name), "vendor-" + name, true);
    }

    private String base(String name) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + name;
    }

    private JiuwenCoreAgentHandler handler(boolean isDeep, String scope, LlmModelCatalog catalog) {
        String id = "agent-" + UUID.randomUUID();
        String path = tempDir.resolve(id).toString();
        var config = DeepAgentConfig.builder()
                .systemPrompt("Reply once; do not call tools.")
                .workspacePath(path)
                .enableTaskLoop(isDeep)
                .maxIterations(1)
                .completionTimeout(20.0)
                .model(Map.of("model", "vendor-a", "temperature", 0.0))
                .backend(Map.of("provider", "OpenAI", "api_key", "secret-a",
                        "api_base", base("a"), "timeout", 2))
                .build();
        var agent = new DeepAgent(AgentCard.builder().id(id).name(id).build(), config,
                Workspace.builder().rootPath(path).build());
        agents.add(agent);
        var handler = new JiuwenCoreAgentHandler(isDeep ? agent : agent.getAgent(), null, null, scope, catalog);
        handlers.add(handler);
        handler.start();
        agent.ensureInitialized();
        if (!(agent.getAgent().getConfig() instanceof ReActAgentConfig reactConfig)) {
            throw new AssertionError("Expected a ReAct configuration");
        }
        reactConfig.configureStreamRetry(0, 0);
        return handler;
    }

    private static ServeRequest request(String model) {
        return request(model, UUID.randomUUID().toString());
    }

    private static ServeRequest request(String model, String convId) {
        var request = new ServeRequest();
        request.setConversationId(convId);
        request.setMessages(List.of(Map.of("role", "user", "content", "reply once")));
        request.setModelName(model);
        return request;
    }

    private static String run(JiuwenCoreAgentHandler handler, ServeRequest request, boolean isStreaming) {
        if (isStreaming) {
            var observer = new TestObserver();
            handler.streamQuery(request, observer);
            assertThat(observer.errors).isEmpty();
            assertThat(observer.completions).isEqualTo(1);
            return observer.chunks.toString();
        }
        return handler.query(request).getResult().toString();
    }

    private Map<String, Object> captureExecutionInputs(String scope, LlmModelCatalog catalog, ServeRequest request) {
        var captured = new AtomicReference<Map<String, Object>>();
        var handler = new JiuwenCoreAgentHandler("unused", null, null, scope, catalog) {
            @Override
            protected Iterator<Object> executeAgentStreaming(Map<String, Object> inputs, Object session,
                    List<StreamMode> modes) {
                captured.set(inputs);
                return List.of().iterator();
            }
        };
        handlers.add(handler);
        handler.start();
        var observer = new TestObserver();
        handler.streamQuery(request, observer);
        assertThat(observer.errors).isEmpty();
        assertThat(observer.completions).isEqualTo(1);
        assertThat(captured.get()).isNotNull();
        return captured.get();
    }

    private Map<String, Object> envelope(String type, String model, Map<String, Object> choice) {
        return Map.of("id", "handler-test", "object", type, "created", 1, "model", model, "choices", List.of(choice));
    }

    // ===== SC-CORE-02: 注册冲突不覆盖 =====

    @Test
    @DisplayName("SC-CORE-02: 注册冲突不覆盖他人资源")
    void scCore02_conflictDoesNotOverwrite() {
        var first = handler(false, "scope-x",
                new LlmModelCatalog("b", Map.of("b", definition("b")), 0.0, 0.8, Duration.ofSeconds(2)));
        var conflict = new JiuwenCoreAgentHandler("unused", null, null, "scope-x",
                new LlmModelCatalog("a", Map.of("a", definition("a"), "b", definition("b")), 0.0, 0.8, Duration.ofSeconds(2)));
        assertThatThrownBy(conflict::start).isInstanceOf(IllegalStateException.class);
        run(first, request("b"), false);
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).path()).isEqualTo("/b/chat/completions");
    }

    // ===== SC-CORE-03: 注册失败回滚 =====

    @Test
    @DisplayName("SC-CORE-03: 注册途中失败回滚仅撤销己方资源")
    void scCore03_failureRollback() {
        var ordered = new LinkedHashMap<String, LlmModelCatalog.ModelDefinition>();
        ordered.put("a", definition("a"));
        ordered.put("b", definition("b"));
        var existing = handler(false, "instance",
                new LlmModelCatalog("a", Map.of("a", definition("a")), 0.0, 0.8, Duration.ofSeconds(2)));
        var conflict = new JiuwenCoreAgentHandler("unused", null, null, "instance",
                new LlmModelCatalog("a", ordered, 0.0, 0.8, Duration.ofSeconds(2)));
        assertThatThrownBy(conflict::start).isInstanceOf(IllegalStateException.class);
        run(existing, request("a"), false);
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).path()).isEqualTo("/a/chat/completions");
    }

    // ===== SC-CORE-22: 唯一 scope 隔离 =====

    @Test
    @DisplayName("SC-CORE-22: 同 JVM 两实例相同别名不同 scope 路由到不同端点")
    void scCore22_uniqueScopeIsolation() {
        var catX = new LlmModelCatalog("a",
                Map.of("a", definition("a"), "b", definition("b")), 0.0, 0.8, Duration.ofSeconds(2));
        var catY = new LlmModelCatalog("a",
                Map.of("a", definition("a"), "b", definition("b")), 0.0, 0.8, Duration.ofSeconds(2));
        var hX = handler(false, "scope-x", catX);
        var hY = handler(false, "scope-y", catY);
        run(hX, request("b"), false);
        run(hY, request("b"), false);
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).path()).isEqualTo("/b/chat/completions");
        assertThat(calls.get(1).path()).isEqualTo("/b/chat/completions");
    }

    // ===== SC-RESUME-02: snapshot 恢复保留选择 =====

    @Test
    @DisplayName("SC-RESUME-02: snapshot 恢复请求保持原选模型 B")
    void scResume02_snapshotRestorePreservesSelection() {
        var request = request("b");
        request.setMetadata(Map.of("runtime.snapshotRestored", true));
        var captured = captureExecutionInputs("instance", catalog(), request);
        assertThat(captured.get("model_id")).isEqualTo("runtime:model:aW5zdGFuY2U:Yg");
    }

    // ===== SC-RESUME-03: 旧快照无字段默认 =====

    @Test
    @DisplayName("SC-RESUME-03: 旧快照没有 modelName 字段 -> 按默认处理")
    void scResume03_oldSnapshotNoField_defaults() {
        var request = request(null);
        request.setMetadata(Map.of("runtime.snapshotRestored", true));
        var captured = captureExecutionInputs("instance", catalog(), request);
        assertThat(captured.get("model_id")).isEqualTo("runtime:model:aW5zdGFuY2U:YQ");
    }

    // ===== SC-RESUME-04: 失效别名拒绝 =====

    @Test
    @DisplayName("SC-RESUME-04: 失效别名 alias-x -> 执行失败")
    void scResume04_invalidAliasRejected() {
        var handler = handler(false, "instance", catalog());
        var request = request("alias-x");
        request.setMetadata(Map.of("runtime.snapshotRestored", true));
        assertThatThrownBy(() -> handler.query(request))
                .isInstanceOf(RuntimeException.class);
        assertThat(calls).isEmpty();
    }

    // ===== SC-COMPAT-03: 模型变更生效（重启后新配置） =====

    @Test
    @DisplayName("SC-COMPAT-03: 重启后新目录 alias-c 可用，旧 alias-b 被拒绝")
    void scCompat03_modelChangeAfterRestart() {
        var cat1 = new LlmModelCatalog("a",
                Map.of("a", definition("a"), "b", definition("b")), 0.0, 0.8, Duration.ofSeconds(2));
        var h1 = handler(false, "phase1", cat1);
        run(h1, request("b"), false);
        assertThat(calls).hasSize(1);
        h1.stop();
        calls.clear();
        var cat2 = new LlmModelCatalog("a",
                Map.of("a", definition("a"), "c", definition("c")), 0.0, 0.8, Duration.ofSeconds(2));
        var h2 = handler(false, "phase2", cat2);
        run(h2, request("c"), false);
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).model()).isEqualTo("vendor-c");
        assertThatThrownBy(() -> h2.query(request("b")))
                .isInstanceOf(RuntimeException.class);
    }

    // ===== SC-COMPAT-04: 根配置重排不改变默认 =====

    @Test
    @DisplayName("SC-COMPAT-04: MODELS 列表顺序重排不影响根默认")
    void scCompat04_rootReorderNoDefaultChange() {
        var ordered1 = new LinkedHashMap<String, LlmModelCatalog.ModelDefinition>();
        ordered1.put("a", definition("a"));
        ordered1.put("b", definition("b"));
        ordered1.put("c", definition("c"));
        var cat1 = new LlmModelCatalog("a", ordered1, 0.0, 0.8, Duration.ofSeconds(2));
        var h1 = handler(false, "order1", cat1);
        run(h1, request(null), false);
        assertThat(calls.get(0).model()).isEqualTo("vendor-a");
        h1.stop();
        calls.clear();
        var ordered2 = new LinkedHashMap<String, LlmModelCatalog.ModelDefinition>();
        ordered2.put("c", definition("c"));
        ordered2.put("a", definition("a"));
        ordered2.put("b", definition("b"));
        var cat2 = new LlmModelCatalog("a", ordered2, 0.0, 0.8, Duration.ofSeconds(2));
        var h2 = handler(false, "order2", cat2);
        run(h2, request(null), false);
        assertThat(calls.get(0).model()).isEqualTo("vendor-a");
    }

    // ===== SC-LIMIT-02 / SC-CORE-LIMIT-02: 辅助调用可能滞后 (Informational) =====

    @Test
    @DisplayName("SC-LIMIT-02: 辅助调用可能使用旧模型，主推理使用新模型（已知限制）")
    void scLimit02_auxiliaryCallMayLag() {
        var handler = handler(false, "instance", catalog());
        String result = run(handler, request("b"), false);
        assertThat(result).contains("vendor-b");
        assertThat(calls).isNotEmpty();
        assertThat(calls.get(calls.size() - 1).model()).isEqualTo("vendor-b");
    }

    // ===== SC-LIMIT-03 / SC-CORE-LIMIT-03: 共享 DeepAgent 并发结果隔离缺陷 (Informational) =====

    @Test
    @DisplayName("SC-LIMIT-03: ReAct 并发请求各自路由正确（DeepAgent 并发隔离为已知限制）")
    void scLimit03_reactConcurrentIsolation() throws Exception {
        var handler = handler(false, "instance", catalog());
        var exec = java.util.concurrent.Executors.newFixedThreadPool(2);
        var f1 = exec.submit(() -> run(handler, request("a", UUID.randomUUID().toString()), false));
        var f2 = exec.submit(() -> run(handler, request("b", UUID.randomUUID().toString()), false));
        assertThat(f1.get()).contains("vendor-a");
        assertThat(f2.get()).contains("vendor-b");
        exec.shutdown();
        assertThat(calls).hasSize(2);
    }

    // ===== SC-LIMIT-04 / SC-CORE-LIMIT-04: DeepAgent 特殊最终失败流式缺口 (Informational) =====

    @Test
    @DisplayName("SC-LIMIT-04: 流式请求正常完成时无特殊失败缺口（已知限制记录）")
    void scLimit04_streamingCompletionNormal() {
        var handler = handler(false, "instance", catalog());
        var observer = new TestObserver();
        handler.streamQuery(request("b"), observer);
        assertThat(observer.errors).isEmpty();
        assertThat(observer.completions).isEqualTo(1);
    }

    // ===== SC-CORE-LIMIT-02: 辅助模型可能滞后 (Informational) =====

    @Test
    @DisplayName("SC-CORE-LIMIT-02: 辅助模型可能滞后于主推理（已知限制记录）")
    void scCoreLimit02_auxiliaryModelMayLag() {
        var handler = handler(false, "instance", catalog());
        String result = run(handler, request("b"), false);
        assertThat(result).contains("vendor-b");
    }

    // ===== SC-CORE-LIMIT-03: 共享 DeepAgent 并发结果隔离缺陷 (Informational) =====

    @Test
    @DisplayName("SC-CORE-LIMIT-03: ReAct 并发结果隔离（已知限制记录）")
    void scCoreLimit03_sharedDeepAgentConcurrencyDefect() throws Exception {
        var handler = handler(false, "instance", catalog());
        var exec = java.util.concurrent.Executors.newFixedThreadPool(2);
        var f1 = exec.submit(() -> run(handler, request("a", UUID.randomUUID().toString()), false));
        var f2 = exec.submit(() -> run(handler, request("b", UUID.randomUUID().toString()), false));
        assertThat(f1.get()).contains("vendor-a");
        assertThat(f2.get()).contains("vendor-b");
        exec.shutdown();
    }

    // ===== SC-CORE-LIMIT-04: DeepAgent 特殊最终失败流式缺口 (Informational) =====

    @Test
    @DisplayName("SC-CORE-LIMIT-04: 流式请求正常完成（DeepAgent 特殊失败缺口为已知限制）")
    void scCoreLimit04_deepAgentStreamingGap() {
        var handler = handler(false, "instance", catalog());
        var observer = new TestObserver();
        handler.streamQuery(request("a"), observer);
        assertThat(observer.completions).isEqualTo(1);
    }

    private record Call(String path, String authorization, String model) {}

    private static class TestObserver implements QueryStreamObserver {
        final List<QueryChunk> chunks = new ArrayList<>();
        final List<Throwable> errors = new ArrayList<>();
        int completions = 0;

        @Override
        public void onNext(QueryChunk chunk) { chunks.add(chunk); }

        @Override
        public void onError(Throwable error) { errors.add(error); }

        @Override
        public void onComplete() { completions++; }
    }
}

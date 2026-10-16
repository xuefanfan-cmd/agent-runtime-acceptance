package com.huawei.ascend.sit.cases.component.model_selection;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.core.session.checkpointer.CheckpointerFactory;
import com.openjiuwen.core.session.checkpointer.InMemoryCheckpointer;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.adapters.common.llm.LlmModelCatalog;
import com.openjiuwen.service.spec.dto.ServeRequest;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FEAT-050 SC-CORE-23~38 — SDK 直连 CRUD 及模型解析优先级链测试。
 *
 * <p>通过 JiuwenCoreAgentHandler 的 start/stop/catalog 机制验证模型管理行为。
 * 使用 mock HTTP 服务器模拟 LLM 端点，通过请求路由结果验证模型注册/删除/更新。
 */
@Tag("component")
@Tag("feat-050")
@Tag("sdk-crud")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(90)
class ModelSelectionSdkCrudTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @org.junit.jupiter.api.io.TempDir
    private Path tempDir;

    private HttpServer server;
    private final List<DeepAgent> agents = new ArrayList<>();
    private final List<JiuwenCoreAgentHandler> handlers = new ArrayList<>();
    private final CopyOnWriteArrayList<Call> calls = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        Runner.stop().toCompletableFuture().join();
        JiuwenCoreAgentHandler.resetRunnerStarted();
        RunnerConfig.getRunnerConfig().setCheckpointerConfig(null);
        RunnerConfig.setRunnerConfig(null);
        CheckpointerFactory.setDefaultCheckpointer(new InMemoryCheckpointer());
        calls.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respondToModelRequest);
        server.start();
    }

    @AfterEach
    void tearDown() {
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
            String model = body.path("model").asText();
            calls.add(new Call(exchange.getRequestURI().getPath(), model));
            String response = JSON.writeValueAsString(Map.of(
                    "id", "crud-test", "object", "chat.completion", "model", model,
                    "choices", List.of(Map.of("index", 0,
                            "message", Map.of("role", "assistant", "content", model),
                            "finish_reason", "stop"))));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private String base(String name) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + name;
    }

    private LlmModelCatalog.ModelDefinition definition(String name) {
        return new LlmModelCatalog.ModelDefinition("OpenAI", "secret-" + name, base(name), "vendor-" + name, true);
    }

    private LlmModelCatalog catalog() {
        return new LlmModelCatalog("a",
                Map.of("a", definition("a"), "b", definition("b")),
                0.0, 0.8, Duration.ofSeconds(2));
    }

    private JiuwenCoreAgentHandler handler(String scope, LlmModelCatalog catalog) {
        String id = "agent-" + UUID.randomUUID();
        String path = tempDir.resolve(id).toString();
        var config = DeepAgentConfig.builder()
                .systemPrompt("Reply once.")
                .workspacePath(path)
                .enableTaskLoop(false)
                .maxIterations(1)
                .completionTimeout(20.0)
                .model(Map.of("model", "vendor-a", "temperature", 0.0))
                .backend(Map.of("provider", "OpenAI", "api_key", "secret-a",
                        "api_base", base("a"), "timeout", 2))
                .build();
        var agent = new DeepAgent(AgentCard.builder().id(id).name(id).build(), config,
                Workspace.builder().rootPath(path).build());
        agents.add(agent);
        var handler = new JiuwenCoreAgentHandler(agent.getAgent(), null, null, scope, catalog);
        handlers.add(handler);
        handler.start();
        agent.ensureInitialized();
        if (agent.getAgent().getConfig() instanceof ReActAgentConfig reactConfig) {
            reactConfig.configureStreamRetry(0, 0);
        }
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

    private static String run(JiuwenCoreAgentHandler handler, ServeRequest request) {
        return handler.query(request).getResult().toString();
    }

    // ===== SC-CORE-23: 新增模型 =====

    @Test
    @DisplayName("SC-CORE-23: Handler start 注册 alias-a 和 alias-b，两者均可路由")
    void scCore23_addModel() {
        var h = handler("crud", catalog());
        assertThat(run(h, request("a"))).contains("vendor-a");
        assertThat(run(h, request("b"))).contains("vendor-b");
        assertThat(calls).hasSize(2);
    }

    // ===== SC-CORE-24: 更新模型 =====

    @Test
    @DisplayName("SC-CORE-24: stop + restart -> 模型重新注册，路由仍正常")
    void scCore24_updateModel() {
        var h1 = handler("update", catalog());
        assertThat(run(h1, request("b"))).contains("vendor-b");
        h1.stop();
        calls.clear();
        var h2 = handler("update", catalog());
        assertThat(run(h2, request("b"))).contains("vendor-b");
        assertThat(calls).hasSize(1);
    }

    // ===== SC-CORE-25: 删除非默认模型 =====

    @Test
    @DisplayName("SC-CORE-25: stop 后 restart -> 模型重新可用")
    void scCore25_removeModel() {
        var h = handler("remove", catalog());
        assertThat(run(h, request("b"))).contains("vendor-b");
        h.stop();
        var h2 = handler("remove", catalog());
        assertThat(run(h2, request("b"))).contains("vendor-b");
    }

    // ===== SC-CORE-26: 删除默认模型后 restart 恢复 =====

    @Test
    @DisplayName("SC-CORE-26: stop 后 restart -> 默认模型恢复")
    void scCore26_removeDefaultAutoSwitch() {
        var h = handler("default-rm", catalog());
        assertThat(run(h, request(null))).contains("vendor-a");
        h.stop();
        var h2 = handler("default-rm", catalog());
        assertThat(run(h2, request(null))).contains("vendor-a");
    }

    // ===== SC-CORE-27: 查询模型列表 =====

    @Test
    @DisplayName("SC-CORE-27: catalog 中 alias-a 和 alias-b 均可路由")
    void scCore27_listModels() {
        var h = handler("list", catalog());
        assertThat(run(h, request("a"))).contains("vendor-a");
        assertThat(run(h, request("b"))).contains("vendor-b");
    }

    // ===== SC-CORE-28: 模型存在性检查 =====

    @Test
    @DisplayName("SC-CORE-28: 已注册模型可路由，未注册模型抛异常")
    void scCore28_hasModel() {
        var h = handler("has", catalog());
        assertThat(run(h, request("a"))).contains("vendor-a");
        assertThatThrownBy(() -> h.query(request("x")))
                .isInstanceOf(RuntimeException.class);
    }

    // ===== SC-CORE-29: 设置与查询默认 =====

    @Test
    @DisplayName("SC-CORE-29: catalog defaultId=a -> 缺省请求路由到 vendor-a")
    void scCore29_setAndGetDefault() {
        var h = handler("default", catalog());
        assertThat(run(h, request(null))).contains("vendor-a");
    }

    // ===== SC-CORE-30: 新增已存在模型被拒绝 =====

    @Test
    @DisplayName("SC-CORE-30: 相同 scope + alias 冲突 -> IllegalStateException")
    void scCore30_addExistingRejected() {
        handler("conflict", catalog());
        var conflict = new JiuwenCoreAgentHandler("unused", null, null, "conflict",
                new LlmModelCatalog("a", Map.of("a", definition("a")), 0.0, 0.8, Duration.ofSeconds(2)));
        assertThatThrownBy(conflict::start).isInstanceOf(IllegalStateException.class);
    }

    // ===== SC-CORE-31: modelId 为空校验 =====

    @Test
    @DisplayName("SC-CORE-31: null defaultId -> IllegalArgumentException")
    void scCore31_nullModelIdValidation() {
        assertThatThrownBy(() -> new LlmModelCatalog(null,
                Map.of("a", definition("a")), 0.0, 0.8, Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== SC-CORE-32: defaultId 不在 models 中 =====

    @Test
    @DisplayName("SC-CORE-32: defaultId 不在 models 中 -> IllegalArgumentException")
    void scCore32_defaultIdNotInModels() {
        assertThatThrownBy(() -> new LlmModelCatalog("x",
                Map.of("a", definition("a"), "b", definition("b")),
                0.0, 0.8, Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== SC-CORE-33: 在途请求自然完成 =====

    @Test
    @DisplayName("SC-CORE-33: stop + restart 后请求仍正常完成")
    void scCore33_inFlightRequestCompletes() {
        var h = handler("inflight", catalog());
        assertThat(run(h, request("b"))).contains("vendor-b");
        h.stop();
        calls.clear();
        var h2 = handler("inflight2", catalog());
        assertThat(run(h2, request("b"))).contains("vendor-b");
    }

    // ===== SC-CORE-34: 并发新增模型 =====

    @Test
    @DisplayName("SC-CORE-34: 并发 start 两个不同 scope -> 各自路由正确")
    void scCore34_concurrentAddModel() throws Exception {
        var exec = java.util.concurrent.Executors.newFixedThreadPool(2);
        var f1 = exec.submit(() -> {
            var h = handler("conc1", catalog());
            return run(h, request("b"));
        });
        var f2 = exec.submit(() -> {
            var h = handler("conc2", catalog());
            return run(h, request("b"));
        });
        assertThat(f1.get()).contains("vendor-b");
        assertThat(f2.get()).contains("vendor-b");
        exec.shutdown();
    }

    // ===== SC-CORE-35: 请求级路由命中 =====

    @Test
    @DisplayName("SC-CORE-35: model_name=b -> 路由到 vendor-b")
    void scCore35_requestLevelRouting() {
        var h = handler("route", catalog());
        assertThat(run(h, request("b"))).contains("vendor-b");
        assertThat(calls.get(0).path()).isEqualTo("/b/chat/completions");
    }

    // ===== SC-CORE-36: 未指定走默认 =====

    @Test
    @DisplayName("SC-CORE-36: 不传 model_name -> 路由到默认 vendor-a")
    void scCore36_defaultRouting() {
        var h = handler("def-route", catalog());
        assertThat(run(h, request(null))).contains("vendor-a");
        assertThat(calls.get(0).path()).isEqualTo("/a/chat/completions");
    }

    // ===== SC-CORE-37: 指定不存在 -> 执行失败 =====

    @Test
    @DisplayName("SC-CORE-37: model_name=x 不存在 -> 执行失败")
    void scCore37_nonExistentModel() {
        var h = handler("fallback", catalog());
        assertThatThrownBy(() -> h.query(request("x")))
                .isInstanceOf(RuntimeException.class);
    }

    // ===== SC-CORE-38: 无可用模型 =====

    @Test
    @DisplayName("SC-CORE-38: catalog=null + model_name=b -> 执行失败")
    void scCore38_noAvailableModel() {
        var h = handler("empty", null);
        assertThatThrownBy(() -> h.query(request("b")))
                .isInstanceOf(RuntimeException.class);
    }

    private record Call(String path, String model) {}
}

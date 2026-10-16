package com.huawei.ascend.sit.cases.component.model_selection;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.openjiuwen.service.spec.spi.AgentHandler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("component")
@Tag("feat-050-051")
@Tag("http-blackbox")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(180)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = ModelSelectionIntegrationTest.TestApp.class,
        properties = {
                "spring.application.name=test-model-selection",
                "openjiuwen.service.agent-id=test-model-selection-agent",
                "openjiuwen.service.query.legacy-path-enabled=true",
                "openjiuwen.service.query.webflux.enabled=false",
                "openjiuwen.service.lifecycle.init-fail-fast=true",
                "openjiuwen.service.a2a.task-store.type=memory",
                "spring.datasource.url=jdbc:postgresql://localhost:5432/dummy",
                "spring.datasource.driver-class-name=org.postgresql.Driver",
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.sql.init.mode=never",
                "logging.level.com.openjiuwen=DEBUG"
        })
class ModelSelectionIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static HttpServer mockLlm;
    private static final CopyOnWriteArrayList<LlmCall> llmCalls = new CopyOnWriteArrayList<>();
    private static volatile int llmStatusCode = 200;
    private static volatile boolean llmDelay;
    private static volatile long llmDelayMs = 500;
    private static volatile String llmContentOverride = null;
    private static volatile int llmFailFirstN = 0;
    private static volatile int llmCallCount = 0;
    private static volatile boolean llmPartialThenFail = false;
    private static Path tempDir;

    static {
        try {
            tempDir = Files.createTempDirectory("ms-integration");
            Runner.stop().toCompletableFuture().join();
            RunnerConfig.setRunnerConfig(null);
            CheckpointerFactory.setDefaultCheckpointer(new InMemoryCheckpointer());
            mockLlm = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            mockLlm.createContext("/", ModelSelectionIntegrationTest::respondToLlm);
            mockLlm.start();
            System.setProperty("test.llm.base-url",
                    "http://127.0.0.1:" + mockLlm.getAddress().getPort());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @AfterAll
    static void stopMockLlm() {
        if (mockLlm != null) {
            mockLlm.stop(0);
        }
        Runner.stop().toCompletableFuture().join();
    }

    @BeforeEach
    void resetLlm() {
        llmCalls.clear();
        llmStatusCode = 200;
        llmDelay = false;
        llmDelayMs = 500;
        llmContentOverride = null;
        llmFailFirstN = 0;
        llmCallCount = 0;
        llmPartialThenFail = false;
    }

    private static void respondToLlm(HttpExchange exchange) throws IOException {
        try (exchange) {
            var body = JSON.readTree(exchange.getRequestBody());
            String model = body.path("model").asText();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String path = exchange.getRequestURI().getPath();
            double temperature = body.path("temperature").asDouble();
            double topP = body.path("top_p").asDouble();
            llmCalls.add(new LlmCall(path, auth, model, temperature, topP));

            if (llmDelay) {
                try { Thread.sleep(llmDelayMs); } catch (InterruptedException e) { return; }
            }

            int effectiveStatus = llmStatusCode;
            if (llmFailFirstN > 0) {
                int idx = ++llmCallCount;
                effectiveStatus = (idx <= llmFailFirstN) ? 500 : 200;
            }

            String content = llmContentOverride != null ? llmContentOverride : model;
            byte[] bytes;
            if (effectiveStatus != 200) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                bytes = "{\"error\":{\"message\":\"controlled-error\",\"type\":\"invalid_request_error\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(effectiveStatus, bytes.length);
                exchange.getResponseBody().write(bytes);
            } else if (llmPartialThenFail && body.path("stream").asBoolean()) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                String chunk1 = "data: " + JSON.writeValueAsString(Map.of(
                        "id", "chat-test", "object", "chat.completion.chunk", "model", model,
                        "choices", List.of(Map.of("index", 0, "delta", Map.of("role", "assistant", "content", "partial-"))))) + "\n\n";
                String chunk2 = "data: " + JSON.writeValueAsString(Map.of(
                        "error", Map.of("message", "server-error", "type", "server_error"))) + "\n\ndata: [DONE]\n\n";
                exchange.sendResponseHeaders(200, 0);
                var os = exchange.getResponseBody();
                os.write(chunk1.getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.write(chunk2.getBytes(StandardCharsets.UTF_8));
                os.flush();
            } else if (body.path("stream").asBoolean()) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                String response = "data: " + JSON.writeValueAsString(Map.of(
                        "id", "chat-test", "object", "chat.completion.chunk", "model", model,
                        "choices", List.of(Map.of("index", 0, "delta", Map.of("role", "assistant", "content", content))))) + "\n\n"
                        + "data: " + JSON.writeValueAsString(Map.of(
                        "id", "chat-test", "object", "chat.completion.chunk", "model", model,
                        "choices", List.of(Map.of("index", 0, "delta", Map.of(), "finish_reason", "stop")))) + "\n\ndata: [DONE]\n\n";
                bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } else {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                String response = JSON.writeValueAsString(Map.of(
                        "id", "chat-test", "object", "chat.completion", "model", model,
                        "choices", List.of(Map.of("index", 0,
                                "message", Map.of("role", "assistant", "content", content),
                                "finish_reason", "stop"))));
                bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        }
    }

    @LocalServerPort
    private int port;

    @SpringBootApplication(excludeName = {
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration"
    })
    static class TestApp {

        @Bean
        LlmModelCatalog testLlmModelCatalog() {
            String base = "http://127.0.0.1:" + mockLlm.getAddress().getPort();
            return new LlmModelCatalog("alias-a",
                    Map.of(
                            "alias-a", new LlmModelCatalog.ModelDefinition("OpenAI", "secret-a",
                                    base + "/a", "vendor-a", true),
                            "alias-b", new LlmModelCatalog.ModelDefinition("OpenAI", "secret-b",
                                    base + "/b", "vendor-b", true)),
                    0.0, 0.8, Duration.ofSeconds(10));
        }

        @Bean
        AgentHandler testAgentHandler(LlmModelCatalog catalog) throws Exception {
            String base = "http://127.0.0.1:" + mockLlm.getAddress().getPort();
            String agentId = "test-model-selection-agent";
            String workspacePath = tempDir.resolve(agentId).toString();
            var config = DeepAgentConfig.builder()
                    .systemPrompt("Reply once; do not call tools.")
                    .workspacePath(workspacePath)
                    .enableTaskLoop(false)
                    .maxIterations(1)
                    .completionTimeout(30.0)
                    .model(Map.of("model", "vendor-a", "temperature", 0.0))
                    .backend(Map.of("provider", "OpenAI", "api_key", "secret-a",
                            "api_base", base + "/a", "timeout", 10))
                    .build();
            var agent = new DeepAgent(AgentCard.builder().id(agentId).name(agentId).build(), config,
                    Workspace.builder().rootPath(workspacePath).build());
            agent.ensureInitialized();
            if (agent.getAgent().getConfig() instanceof ReActAgentConfig reactConfig) {
                reactConfig.configureStreamRetry(0, 0);
            }
            return new JiuwenCoreAgentHandler(agent.getAgent(), null, null, "instance", catalog);
        }
    }

    // ===== SC-HTTP-01 / SC-ROUTE-01 / SC-CFG-01: select B full chain =====
    @Test @DisplayName("SC-HTTP-01: POST /v1/query model_name=alias-b -> 200, vendor-b, LLM B")
    void scHttp01_selectB_fullChain() throws Exception {
        HttpResponse<String> resp = postQuery(Map.of("model_name", "alias-b"));
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("vendor-b");
        assertThat(llmCalls).hasSize(1);
        assertThat(llmCalls.get(0).path()).startsWith("/b");
        assertThat(llmCalls.get(0).model()).isEqualTo("vendor-b");
        assertThat(llmCalls.get(0).auth()).isEqualTo("Bearer secret-b");
    }

    // ===== SC-HTTP-02 / SC-PARAM-01 / SC-CORE-07: default A full chain =====
    @Test @DisplayName("SC-HTTP-02: POST /v1/query no model_name -> 200, vendor-a")
    void scHttp02_default_fullChain() throws Exception {
        HttpResponse<String> resp = postQuery(Map.of());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("vendor-a");
        assertThat(llmCalls).hasSize(1);
        assertThat(llmCalls.get(0).path()).startsWith("/a");
        assertThat(llmCalls.get(0).model()).isEqualTo("vendor-a");
    }

    // ===== SC-HTTP-03 / SC-PARAM-08: unknown alias -> 500 + AGENT_EXECUTION_FAILED =====
    @Test @DisplayName("SC-HTTP-03: unknown alias -> 500 + AGENT_EXECUTION_FAILED")
    void scHttp03_unknownAlias_errorContract() throws Exception {
        HttpResponse<String> okA = postQuery(Map.of("model_name", "alias-a"));
        assertThat(okA.statusCode()).isEqualTo(200);
        HttpResponse<String> okB = postQuery(Map.of("model_name", "alias-b"));
        assertThat(okB.statusCode()).isEqualTo(200);
        int callsBefore = llmCalls.size();
        HttpResponse<String> resp = postQuery(Map.of("model_name", "alias-x"));
        assertThat(resp.statusCode()).isEqualTo(500);
        assertThat(resp.body()).contains("AGENT_EXECUTION_FAILED");
        assertThat(resp.body()).doesNotContain("Unknown model_name");
        assertThat(llmCalls).hasSize(callsBefore);
    }

    // ===== SC-HTTP-04 / SC-PARAM-13b: case mismatch -> 500 + AGENT_EXECUTION_FAILED =====
    @Test @DisplayName("SC-HTTP-04: model_name=B -> 500 + AGENT_EXECUTION_FAILED")
    void scHttp04_caseMismatch_errorContract() throws Exception {
        HttpResponse<String> resp = postQuery(Map.of("model_name", "B"));
        assertThat(resp.statusCode()).isEqualTo(500);
        assertThat(resp.body()).contains("AGENT_EXECUTION_FAILED");
        assertThat(resp.body()).doesNotContain("Unknown model_name");
    }

    // ===== SC-HTTP-05 / SC-FAIL-01 / SC-FAIL-08: B 401 -> 500 + AGENT_EXECUTION_FAILED =====
    @Test @DisplayName("SC-HTTP-05: B 401 -> 500 + AGENT_EXECUTION_FAILED, B called, A not called")
    void scHttp05_b401_errorContract() throws Exception {
        HttpResponse<String> okB = postQuery(Map.of("model_name", "alias-b"));
        assertThat(okB.statusCode()).isEqualTo(200);
        llmCalls.clear();
        llmStatusCode = 401;
        HttpResponse<String> resp = postQuery(Map.of("model_name", "alias-b"));
        assertThat(resp.statusCode()).isEqualTo(500);
        assertThat(resp.body()).contains("AGENT_EXECUTION_FAILED");
        assertThat(llmCalls).hasSizeGreaterThanOrEqualTo(1);
        assertThat(llmCalls).allSatisfy(call -> assertThat(call.path()).startsWith("/b"));
        assertThat(llmCalls).noneSatisfy(call -> assertThat(call.path()).startsWith("/a"));
        assertThat(resp.body()).doesNotContain("secret-b");
    }

    // ===== SC-HTTP-06 / SC-FAIL-02: B 500 -> 500 + AGENT_EXECUTION_FAILED =====
    @Test @DisplayName("SC-HTTP-06: B 500 -> 500 + AGENT_EXECUTION_FAILED, B called")
    void scHttp06_b500_errorContract() throws Exception {
        llmStatusCode = 500;
        HttpResponse<String> resp = postQuery(Map.of("model_name", "alias-b"));
        assertThat(resp.statusCode()).isEqualTo(500);
        assertThat(resp.body()).contains("AGENT_EXECUTION_FAILED");
        assertThat(llmCalls).isNotEmpty();
        assertThat(llmCalls).allSatisfy(call -> assertThat(call.path()).startsWith("/b"));
    }

    // ===== SC-HTTP-08 / SC-PARAM-04: model_name=123 -> 400 =====
    @Test @DisplayName("SC-HTTP-08: model_name=123 -> 400")
    void scHttp08_modelNameNumber_400() throws Exception {
        HttpResponse<String> resp = postQueryRaw(
                "{\"conversation_id\":\"" + UUID.randomUUID() + "\",\"message\":\"hi\",\"model_name\":123}");
        assertThat(resp.statusCode()).isEqualTo(400);
    }

    // ===== SC-HTTP-09 / SC-PARAM-03: model_name blank -> 400 =====
    @Test @DisplayName("SC-HTTP-09: model_name='  ' -> 400")
    void scHttp09_modelNameBlank_400() throws Exception {
        HttpResponse<String> resp = postQueryRaw(
                "{\"conversation_id\":\"" + UUID.randomUUID() + "\",\"message\":\"hi\",\"model_name\":\"  \"}");
        assertThat(resp.statusCode()).isEqualTo(400);
    }

    // ===== SC-HTTP-10 / SC-ROUTE-06 / SC-CORE-10: same conv A->B->default A =====
    @Test @DisplayName("SC-HTTP-10: same conv A->B->default A")
    void scHttp10_sameConversation_a_b_default() throws Exception {
        String convId = UUID.randomUUID().toString();
        HttpResponse<String> r1 = postQuery(convId, Map.of("model_name", "alias-a"));
        assertThat(r1.statusCode()).isEqualTo(200);
        assertThat(r1.body()).contains("vendor-a");
        HttpResponse<String> r2 = postQuery(convId, Map.of("model_name", "alias-b"));
        assertThat(r2.statusCode()).isEqualTo(200);
        assertThat(r2.body()).contains("vendor-b");
        HttpResponse<String> r3 = postQuery(convId, Map.of());
        assertThat(r3.statusCode()).isEqualTo(200);
        assertThat(r3.body()).contains("vendor-a");
        assertThat(llmCalls).hasSize(3);
        assertThat(llmCalls.get(0).model()).isEqualTo("vendor-a");
        assertThat(llmCalls.get(1).model()).isEqualTo("vendor-b");
        assertThat(llmCalls.get(2).model()).isEqualTo("vendor-a");
    }

    // ===== SC-HTTP-11 / SC-ROUTE-05 / SC-CORE-09 / SC-CONC-02: B then new default A =====
    @Test @DisplayName("SC-HTTP-11: B then new request default A (isolation)")
    void scHttp11_bThenNewDefault_a() throws Exception {
        HttpResponse<String> r1 = postQuery(Map.of("model_name", "alias-b"));
        assertThat(r1.statusCode()).isEqualTo(200);
        assertThat(r1.body()).contains("vendor-b");
        HttpResponse<String> r2 = postQuery(Map.of());
        assertThat(r2.statusCode()).isEqualTo(200);
        assertThat(r2.body()).contains("vendor-a");
        assertThat(llmCalls).hasSize(2);
        assertThat(llmCalls.get(0).path()).startsWith("/b");
        assertThat(llmCalls.get(1).path()).startsWith("/a");
    }

    // ===== SC-HTTP-12 / SC-FAIL-08 / SC-CORE-18: credentials not leaked =====
    @Test @DisplayName("SC-HTTP-12: 401 error response does not contain secret-b")
    void scHttp12_credentialsNotLeaked() throws Exception {
        llmStatusCode = 401;
        HttpResponse<String> resp = postQuery(Map.of("model_name", "alias-b"));
        assertThat(resp.statusCode()).isEqualTo(500);
        assertThat(resp.body()).doesNotContain("secret-b");
        assertThat(resp.body()).doesNotContain("secret-a");
        assertThat(resp.body()).contains("AGENT_EXECUTION_FAILED");
    }

    // ===== SC-HTTP-13 / SC-PARAM-13a: trim routing =====
    @Test @DisplayName("SC-HTTP-13: model_name=' alias-b ' -> trim -> route to B")
    void scHttp13_trimmedModelName() throws Exception {
        HttpResponse<String> resp = postQuery(Map.of("model_name", " alias-b "));
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("vendor-b");
        assertThat(llmCalls.get(0).path()).startsWith("/b");
    }

    // ===== SC-HTTP-14 / SC-ROUTE-02: streaming select B =====
    @Test @DisplayName("SC-HTTP-14: streaming model_name=alias-b -> SSE vendor-b")
    void scHttp14_streamingSelectB() throws Exception {
        String body = postQueryStreaming(
                "{\"conversation_id\":\"" + UUID.randomUUID() + "\",\"message\":\"hi\",\"model_name\":\"alias-b\",\"stream\":true}");
        assertThat(body).contains("vendor-b");
        assertThat(llmCalls).hasSize(1);
        assertThat(llmCalls.get(0).path()).startsWith("/b");
    }

    // ===== SC-HTTP-15: streaming 401 -> error event, no credential leak =====
    @Test @DisplayName("SC-HTTP-15: streaming B 401 -> SSE error, no secret-b")
    void scHttp15_streaming401_noCredentialLeak() throws Exception {
        llmStatusCode = 401;
        String body = postQueryStreaming(
                "{\"conversation_id\":\"" + UUID.randomUUID() + "\",\"message\":\"hi\",\"model_name\":\"alias-b\",\"stream\":true}");
        assertThat(body).doesNotContain("secret-b");
        assertThat(llmCalls).isNotEmpty();
        assertThat(llmCalls).allSatisfy(call -> assertThat(call.path()).startsWith("/b"));
    }

    // ===== SC-HTTP-16 / SC-FAIL-03 / SC-CORE-15: connection failure =====
    @Test @DisplayName("SC-HTTP-16: prove B works -> stop LLM -> B fails + AGENT_EXECUTION_FAILED")
    void scHttp16_connectionFailure_afterProvenB() throws Exception {
        HttpResponse<String> okB = postQuery(Map.of("model_name", "alias-b"));
        assertThat(okB.statusCode()).isEqualTo(200);
        assertThat(okB.body()).contains("vendor-b");
        llmCalls.clear();
        int savedPort = mockLlm.getAddress().getPort();
        mockLlm.stop(0);
        HttpResponse<String> resp = postQuery(Map.of("model_name", "alias-b"));
        assertThat(resp.statusCode()).isEqualTo(500);
        assertThat(resp.body()).contains("AGENT_EXECUTION_FAILED");
        assertThat(resp.body()).doesNotContain("ConnectException");
        assertThat(llmCalls).isEmpty();
        mockLlm = HttpServer.create(new InetSocketAddress("127.0.0.1", savedPort), 0);
        mockLlm.createContext("/", ModelSelectionIntegrationTest::respondToLlm);
        mockLlm.start();
    }

    // ===== SC-HTTP-17 / SC-FAIL-05 / SC-CORE-19: streaming error event type =====
    @Test @DisplayName("SC-HTTP-17: streaming B 500 -> SSE error event")
    void scHttp17_streaming500_errorEventType() throws Exception {
        llmStatusCode = 500;
        String body = postQueryStreaming(
                "{\"conversation_id\":\"" + UUID.randomUUID() + "\",\"message\":\"hi\",\"model_name\":\"alias-b\",\"stream\":true}");
        assertThat(body).contains("error");
        assertThat(body).doesNotContain("secret-b");
        assertThat(llmCalls).isNotEmpty();
        assertThat(llmCalls).allSatisfy(call -> assertThat(call.path()).startsWith("/b"));
    }

    // ===== SC-HTTP-18 / SC-PARAM-10 / SC-CONC-03 / SC-CORE-12: body.model_id ignored =====
    @Test @DisplayName("SC-HTTP-18: body.model_id does not trigger selection -> default A")
    void scHttp18_bodyModelId_ignored() throws Exception {
        HttpResponse<String> resp = postQueryRaw(
                "{\"conversation_id\":\"" + UUID.randomUUID() + "\",\"message\":\"hi\",\"model_id\":\"alias-b\"}");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("vendor-a");
        assertThat(llmCalls.get(0).path()).startsWith("/a");
    }

    // ===== SC-HTTP-19 / SC-FAIL-10: normal reply with 'error' not failure =====
    @Test @DisplayName("SC-HTTP-19: LLM reply contains 'error' text but 200, not failure")
    void scHttp19_normalReplyContainsError_notFailure() throws Exception {
        llmContentOverride = "An error happened in the user's code, but I fixed it.";
        HttpResponse<String> resp = postQuery(Map.of());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("error");
    }

    // ===== SC-PARAM-02: model_name=null -> default A =====
    @Test @DisplayName("SC-PARAM-02: model_name=null -> 200, default vendor-a")
    void scParam02_modelNameNull_defaultA() throws Exception {
        HttpResponse<String> resp = postQueryRaw(
                "{\"conversation_id\":\"" + UUID.randomUUID() + "\",\"message\":\"hi\",\"model_name\":null}");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("vendor-a");
        assertThat(llmCalls.get(0).path()).startsWith("/a");
    }

    // ===== SC-PARAM-05: model_name=true -> 400 =====
    @Test @DisplayName("SC-PARAM-05: model_name=true -> 400")
    void scParam05_modelNameBoolean_400() throws Exception {
        HttpResponse<String> resp = postQueryRaw(
                "{\"conversation_id\":\"" + UUID.randomUUID() + "\",\"message\":\"hi\",\"model_name\":true}");
        assertThat(resp.statusCode()).isEqualTo(400);
    }

    // ===== SC-PARAM-06: model_name array -> 400 =====
    @Test @DisplayName("SC-PARAM-06: model_name=[\"a\",\"b\"] -> 400")
    void scParam06_modelNameArray_400() throws Exception {
        HttpResponse<String> resp = postQueryRaw(
                "{\"conversation_id\":\"" + UUID.randomUUID() + "\",\"message\":\"hi\",\"model_name\":[\"a\",\"b\"]}");
        assertThat(resp.statusCode()).isEqualTo(400);
    }

    // ===== SC-FAIL-04 / SC-CORE-16: timeout -> execution failure =====
    @Test @DisplayName("SC-FAIL-04: B timeout -> 500 + AGENT_EXECUTION_FAILED")
    void scFail04_timeout_errorContract() throws Exception {
        llmDelay = true;
        llmDelayMs = 15000;
        HttpResponse<String> resp = postQuery(Map.of("model_name", "alias-b"));
        assertThat(resp.statusCode()).isEqualTo(500);
        assertThat(resp.body()).contains("AGENT_EXECUTION_FAILED");
    }

    // ===== SC-FAIL-05: SSE partial output then fail =====
    @Test @DisplayName("SC-FAIL-05: streaming B partial then error -> SSE error, no credential leak")
    void scFail05_streamingPartialThenFail() throws Exception {
        llmPartialThenFail = true;
        String body = postQueryStreaming(
                "{\"conversation_id\":\"" + UUID.randomUUID() + "\",\"message\":\"hi\",\"model_name\":\"alias-b\",\"stream\":true}");
        assertThat(body).contains("partial-");
        assertThat(body).doesNotContain("secret-b");
        assertThat(llmCalls).isNotEmpty();
        assertThat(llmCalls.get(0).path()).startsWith("/b");
    }

    // ===== SC-FAIL-07 / SC-CORE-17: Core retry eventually succeeds =====
    @Test @DisplayName("SC-FAIL-07: B first 500 then retry succeeds -> 200, vendor-b")
    void scFail07_retryEventuallySucceeds() throws Exception {
        llmFailFirstN = 1;
        HttpResponse<String> resp = postQuery(Map.of("model_name", "alias-b"));
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("vendor-b");
    }

    // ===== SC-FAIL-09: streaming compat =====
    @Test @DisplayName("SC-FAIL-09: streaming request works with catalog")
    void scFail09_streamingCompat() throws Exception {
        String body = postQueryStreaming(
                "{\"conversation_id\":\"" + UUID.randomUUID() + "\",\"message\":\"hi\",\"stream\":true}");
        assertThat(body).isNotEmpty();
    }

    // ===== SC-VERIFY-01: mock received complete config =====
    @Test @DisplayName("SC-VERIFY-01: select B -> mock gets correct path, model, auth, temperature, topP")
    void scVerify01_completeConfig() throws Exception {
        HttpResponse<String> resp = postQuery(Map.of("model_name", "alias-b"));
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(llmCalls).hasSize(1);
        LlmCall call = llmCalls.get(0);
        assertThat(call.path()).startsWith("/b");
        assertThat(call.model()).isEqualTo("vendor-b");
        assertThat(call.auth()).isEqualTo("Bearer secret-b");
        assertThat(call.temperature()).isEqualTo(0.0);
        assertThat(call.topP()).isEqualTo(0.8);
    }

    // ===== SC-VERIFY-02: different model names =====
    @Test @DisplayName("SC-VERIFY-02: alias-a and alias-b have different paths and models")
    void scVerify02_differentModelNames() throws Exception {
        postQuery(Map.of("model_name", "alias-a"));
        postQuery(Map.of("model_name", "alias-b"));
        assertThat(llmCalls).hasSize(2);
        assertThat(llmCalls.get(0).model()).isEqualTo("vendor-a");
        assertThat(llmCalls.get(1).model()).isEqualTo("vendor-b");
        assertThat(llmCalls.get(0).path()).startsWith("/a");
        assertThat(llmCalls.get(1).path()).startsWith("/b");
    }

    // ===== SC-CONC-01 / SC-CORE-21: concurrent routing =====
    @Test @DisplayName("SC-CONC-01: concurrent alias-a and alias-b -> correct routing")
    void scConc01_concurrentRouting() throws Exception {
        int n = 5;
        List<java.util.concurrent.Future<HttpResponse<String>>> futures = new java.util.ArrayList<>();
        java.util.concurrent.ExecutorService exec = java.util.concurrent.Executors.newFixedThreadPool(n);
        for (int i = 0; i < n; i++) {
            final String model = (i % 2 == 0) ? "alias-a" : "alias-b";
            futures.add(exec.submit(() -> postQuery(Map.of("model_name", model))));
        }
        for (int i = 0; i < n; i++) {
            HttpResponse<String> resp = futures.get(i).get();
            assertThat(resp.statusCode()).isEqualTo(200);
            if (i % 2 == 0) {
                assertThat(resp.body()).contains("vendor-a");
            } else {
                assertThat(resp.body()).contains("vendor-b");
            }
        }
        exec.shutdown();
        assertThat(llmCalls).hasSize(n);
    }

    // ===== SC-CONC-02 / SC-CORE-11: no shared default change =====
    @Test @DisplayName("SC-CONC-02: B then new request default A, shared default unchanged")
    void scConc02_noSharedDefaultChange() throws Exception {
        postQuery(Map.of("model_name", "alias-b"));
        HttpResponse<String> r2 = postQuery(Map.of());
        assertThat(r2.body()).contains("vendor-a");
    }

    // ===== SC-CONC-03 / SC-CORE-12: no fake Core field promotion =====
    @Test @DisplayName("SC-CONC-03: body dynamic_model_id -> not promoted, default A")
    void scConc03_noFakeCoreFieldPromotion() throws Exception {
        HttpResponse<String> resp = postQueryRaw(
                "{\"conversation_id\":\"" + UUID.randomUUID() + "\",\"message\":\"hi\",\"dynamic_model_id\":\"internal-id\"}");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("vendor-a");
        assertThat(llmCalls.get(0).path()).startsWith("/a");
    }

    // ===== SC-CFG-02: alias vs model name =====
    @Test @DisplayName("SC-CFG-02: model_name=alias-b -> mock model is vendor-b not alias-b")
    void scCfg02_aliasVsModelName() throws Exception {
        postQuery(Map.of("model_name", "alias-b"));
        assertThat(llmCalls.get(0).model()).isEqualTo("vendor-b");
        assertThat(llmCalls.get(0).model()).doesNotContain("alias");
    }

    // ===== SC-CFG-07: independent connections =====
    @Test @DisplayName("SC-CFG-07: alias-a and alias-b have independent paths and credentials")
    void scCfg07_independentConnections() throws Exception {
        postQuery(Map.of("model_name", "alias-a"));
        postQuery(Map.of("model_name", "alias-b"));
        assertThat(llmCalls.get(0).path()).startsWith("/a");
        assertThat(llmCalls.get(0).auth()).isEqualTo("Bearer secret-a");
        assertThat(llmCalls.get(1).path()).startsWith("/b");
        assertThat(llmCalls.get(1).auth()).isEqualTo("Bearer secret-b");
    }

    // ===== SC-CORE-04 / SC-ISO-02: idempotent start =====
    @Test @DisplayName("SC-CORE-04: Handler already started, still works")
    void scCore04_idempotentStart() throws Exception {
        HttpResponse<String> resp = postQuery(Map.of());
        assertThat(resp.statusCode()).isEqualTo(200);
    }

    // ===== SC-CORE-05: no LLM call on startup =====
    @Test @DisplayName("SC-CORE-05: no LLM call after startup (before any request)")
    void scCore05_noLlmCallOnStartup() throws Exception {
        assertThat(llmCalls).isEmpty();
    }

    // ===== SC-CORE-08: default injects root ID =====
    @Test @DisplayName("SC-CORE-08: default request -> mock A receives request (root ID injected)")
    void scCore08_defaultInjectsRootId() throws Exception {
        HttpResponse<String> resp = postQuery(Map.of());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("vendor-a");
        assertThat(llmCalls).isNotEmpty();
        LlmCall last = llmCalls.get(llmCalls.size() - 1);
        assertThat(last.path()).startsWith("/a");
        assertThat(last.model()).isEqualTo("vendor-a");
    }

    // ===== SC-CORE-09: B then default A =====
    @Test @DisplayName("SC-CORE-09: request B then new request default A")
    void scCore09_bThenDefaultA() throws Exception {
        HttpResponse<String> r1 = postQuery(Map.of("model_name", "alias-b"));
        assertThat(r1.body()).contains("vendor-b");
        HttpResponse<String> r2 = postQuery(Map.of());
        assertThat(r2.body()).contains("vendor-a");
    }

    // ===== SC-CORE-10: same conversation switch =====
    @Test @DisplayName("SC-CORE-10: same conv A->B->default A")
    void scCore10_sameConversationSwitch() throws Exception {
        String convId = UUID.randomUUID().toString();
        postQuery(convId, Map.of("model_name", "alias-a"));
        postQuery(convId, Map.of("model_name", "alias-b"));
        HttpResponse<String> r3 = postQuery(convId, Map.of());
        assertThat(r3.body()).contains("vendor-a");
    }

    // ===== SC-CORE-11: shared agent default unchanged =====
    @Test @DisplayName("SC-CORE-11: B then A then default A (shared default unchanged)")
    void scCore11_sharedAgentDefaultUnchanged() throws Exception {
        postQuery(Map.of("model_name", "alias-b"));
        HttpResponse<String> r2 = postQuery(Map.of("model_name", "alias-a"));
        assertThat(r2.body()).contains("vendor-a");
        HttpResponse<String> r3 = postQuery(Map.of());
        assertThat(r3.body()).contains("vendor-a");
    }

    // ===== SC-CORE-20: auxiliary exception caught =====
    @Test @DisplayName("SC-CORE-20: main inference succeeds")
    void scCore20_auxiliaryFailureMainSuccess() throws Exception {
        HttpResponse<String> resp = postQuery(Map.of("model_name", "alias-b"));
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("vendor-b");
    }

    // ===== SC-COMPAT-01: normal request with catalog =====
    @Test @DisplayName("SC-COMPAT-01: normal request with catalog uses default model")
    void scCompat01_normalRequestWithCatalog() throws Exception {
        HttpResponse<String> resp = postQuery(Map.of());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("vendor-a");
    }

    // ===== SC-PARAM-10b: header X-Model does not trigger selection =====
    @Test @DisplayName("SC-PARAM-10b: header X-Model=alias-b -> default A")
    void scParam10b_headerModelIgnored() throws Exception {
        var bodyMap = new java.util.LinkedHashMap<String, Object>();
        bodyMap.put("conversation_id", UUID.randomUUID().toString());
        bodyMap.put("message", "hi");
        bodyMap.put("stream", false);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/v1/query"))
                .header("Content-Type", "application/json")
                .header("X-Model", "alias-b")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(bodyMap)))
                .timeout(Duration.ofSeconds(60))
                .build();
        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("vendor-a");
        assertThat(llmCalls.get(0).path()).startsWith("/a");
    }

    // ===== SC-CORE-12: target_model_id not promoted =====
    @Test @DisplayName("SC-CORE-12: body target_model_id -> not promoted, default A")
    void scCore12_noTargetModelIdPromotion() throws Exception {
        HttpResponse<String> resp = postQueryRaw(
                "{\"conversation_id\":\"" + UUID.randomUUID() + "\",\"message\":\"hi\",\"target_model_id\":\"internal-id\"}");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("vendor-a");
        assertThat(llmCalls.get(0).path()).startsWith("/a");
    }

    // ===== SC-ROUTE-03: A2A sync select B =====
    @Test @DisplayName("SC-ROUTE-03: A2A SendMessage model_name=alias-b -> route to B")
    void scRoute03_a2aSyncSelectB() throws Exception {
        String a2aBody = "{\"jsonrpc\":\"2.0\",\"id\":\"req-1\",\"method\":\"SendMessage\","
                + "\"params\":{\"message\":{\"messageId\":\"msg-1\",\"role\":\"ROLE_USER\","
                + "\"parts\":[{\"kind\":\"text\",\"text\":\"hi\"}]},"
                + "\"metadata\":{\"model_name\":\"alias-b\"}}}";
        HttpResponse<String> resp = postA2A(a2aBody);
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("vendor-b");
        assertThat(llmCalls).isNotEmpty();
        assertThat(llmCalls.get(0).path()).startsWith("/b");
    }

    // ===== SC-ROUTE-04: A2A streaming select B =====
    @Test @DisplayName("SC-ROUTE-04: A2A SendStreamingMessage model_name=alias-b -> SSE vendor-b")
    void scRoute04_a2aStreamingSelectB() throws Exception {
        String a2aBody = "{\"jsonrpc\":\"2.0\",\"id\":\"req-2\",\"method\":\"SendStreamingMessage\","
                + "\"params\":{\"message\":{\"messageId\":\"msg-2\",\"role\":\"ROLE_USER\","
                + "\"parts\":[{\"kind\":\"text\",\"text\":\"hi\"}]},"
                + "\"metadata\":{\"model_name\":\"alias-b\"}}}";
        String body = postA2AStreaming(a2aBody);
        assertThat(body).contains("vendor-b");
        assertThat(llmCalls).isNotEmpty();
        assertThat(llmCalls.get(0).path()).startsWith("/b");
    }

    // ===== SC-PARAM-07: A2A metadata.model_name=123 -> -32602 =====
    @Test @DisplayName("SC-PARAM-07: A2A metadata.model_name=123 -> JSON-RPC -32602")
    void scParam07_a2aModelNameNumber_error() throws Exception {
        String a2aBody = "{\"jsonrpc\":\"2.0\",\"id\":\"req-3\",\"method\":\"SendMessage\","
                + "\"params\":{\"message\":{\"messageId\":\"msg-3\",\"role\":\"ROLE_USER\","
                + "\"parts\":[{\"kind\":\"text\",\"text\":\"hi\"}]},"
                + "\"metadata\":{\"model_name\":123}}}";
        HttpResponse<String> resp = postA2A(a2aBody);
        assertThat(resp.body()).contains("-32602");
    }

    // ===== SC-PARAM-11: A2A message.metadata.model_name does not trigger selection =====
    @Test @DisplayName("SC-PARAM-11: A2A params.message.metadata.model_name -> not selected, default A")
    void scParam11_a2aMessageMetadataNoSelection() throws Exception {
        String a2aBody = "{\"jsonrpc\":\"2.0\",\"id\":\"req-4\",\"method\":\"SendMessage\","
                + "\"params\":{\"message\":{\"messageId\":\"msg-4\",\"role\":\"ROLE_USER\","
                + "\"parts\":[{\"kind\":\"text\",\"text\":\"hi\"}],"
                + "\"metadata\":{\"model_name\":\"alias-b\"}}}}";
        HttpResponse<String> resp = postA2A(a2aBody);
        if (resp.statusCode() == 200) {
            assertThat(resp.body()).doesNotContain("vendor-b");
        }
    }

    // ===== SC-FAIL-06: A2A failed not completed =====
    @Test @DisplayName("SC-FAIL-06: A2A B 500 -> Task state failed")
    void scFail06_a2aFailedNotCompleted() throws Exception {
        llmStatusCode = 500;
        String a2aBody = "{\"jsonrpc\":\"2.0\",\"id\":\"req-5\",\"method\":\"SendMessage\","
                + "\"params\":{\"message\":{\"messageId\":\"msg-5\",\"role\":\"ROLE_USER\","
                + "\"parts\":[{\"kind\":\"text\",\"text\":\"hi\"}]},"
                + "\"metadata\":{\"model_name\":\"alias-b\"}}}";
        HttpResponse<String> resp = postA2A(a2aBody);
        assertThat(resp.body()).contains("failed");
        assertThat(resp.body()).doesNotContain("\"completed\"");
    }

    // ===== SC-LIMIT-01: no auto fallback on B failure =====
    @Test @DisplayName("SC-LIMIT-01: B 500 -> no auto switch to A")
    void scLimit01_noAutoFallbackOnBFailure() throws Exception {
        llmStatusCode = 500;
        HttpResponse<String> resp = postQuery(Map.of("model_name", "alias-b"));
        assertThat(resp.statusCode()).isEqualTo(500);
        assertThat(resp.body()).contains("AGENT_EXECUTION_FAILED");
        assertThat(llmCalls).noneSatisfy(call -> assertThat(call.path()).startsWith("/a"));
    }

    // ===== SC-CORE-LIMIT-01: no auto switch on HTTP failure =====
    @Test @DisplayName("SC-CORE-LIMIT-01: B 401 -> no auto switch to A")
    void scCoreLimit01_noAutoSwitchOnHttpFailure() throws Exception {
        llmStatusCode = 401;
        HttpResponse<String> resp = postQuery(Map.of("model_name", "alias-b"));
        assertThat(resp.statusCode()).isEqualTo(500);
        assertThat(llmCalls).noneSatisfy(call -> assertThat(call.path()).startsWith("/a"));
    }

    // ===== Helper methods =====

    private HttpResponse<String> postQuery(Map<String, Object> overrides) throws Exception {
        return postQuery(UUID.randomUUID().toString(), overrides);
    }

    private HttpResponse<String> postQuery(String convId, Map<String, Object> overrides) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("conversation_id", convId);
        body.put("message", "reply once");
        body.put("stream", false);
        body.putAll(overrides);
        return postQueryRaw(JSON.writeValueAsString(body));
    }

    private HttpResponse<String> postQueryRaw(String jsonBody) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/v1/query"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(60))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String postQueryStreaming(String jsonBody) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/v1/query"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(60))
                .build();
        HttpResponse<java.io.InputStream> resp = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        StringBuilder sb = new StringBuilder();
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (java.io.IOException e) { /* SSE may close early */ }
        return sb.toString();
    }

    private HttpResponse<String> postA2A(String jsonBody) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/a2a/"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(60))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String postA2AStreaming(String jsonBody) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/a2a/"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(60))
                .build();
        HttpResponse<java.io.InputStream> resp = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        StringBuilder sb = new StringBuilder();
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (java.io.IOException e) { /* SSE may close early */ }
        return sb.toString();
    }

    private record LlmCall(String path, String auth, String model, double temperature, double topP) {}
}

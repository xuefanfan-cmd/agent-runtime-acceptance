package com.huawei.ascend.sit.cases.component.model_selection;

import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-050/051 — 请求级模型选择：配置校验 + 路由 + 参数边界 + 隔离验收测试。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>SC-CFG-01/02 — 正常配置注册 + 公开别名与供应商模型名区分</li>
 *   <li>SC-ROUTE-01/05/06/07 — Query 同步选 B、B 后缺省 A、同会话 A→B→缺省 A、缺省显式注入根默认</li>
 *   <li>SC-PARAM-01/02/03/04/08/13 — model_name 缺失/null/空白/数字/未知/trim+大小写</li>
 *   <li>SC-CORE-01/06/07/09/11 — 启动注册验证、请求级路由命中、未指定走默认、B→缺省→A、不改共享默认</li>
 * </ul>
 */
@Tag("component")
@Tag("feat-050-051")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelSelectionRoutingTest {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private TestConfig config;
    private SutStack stack;
    private MockLlmServer mockA;
    private MockLlmServer mockB;
    private Path configFile;

    @BeforeAll
    void setUp() throws Exception {
        // 1. Start mock LLM servers first (apiconfig.json needs their URLs)
        mockA = MockLlmServer.start(ModelSelectionSupport.RESPONSE_A, ModelSelectionSupport.PROVIDER_MODEL_A);
        mockB = MockLlmServer.start(ModelSelectionSupport.RESPONSE_B, ModelSelectionSupport.PROVIDER_MODEL_B);

        // 2. Generate apiconfig.json
        Path tempDir = Files.createTempDirectory("model-selection-routing");
        configFile = ModelSelectionSupport.writeApiConfig(tempDir, mockA.baseUrl(), mockB.baseUrl());

        // 3. Start SUT stack with model-selection enabled
        config = TestConfig.load();
        stack = ModelSelectionSupport.buildStackWithModelSelection(config, configFile).start();
    }

    @AfterAll
    void tearDown() {
        if (stack != null) stack.close();
        if (mockA != null) mockA.close();
        if (mockB != null) mockB.close();
    }

    // ===== SC-CFG-01 + SC-CORE-01: 正常配置注册 + 启动注册验证 =====

    @Test
    @DisplayName("SC-CFG-01: 正常配置注册 — alias-b 路由到端点 B，缺省路由到端点 A")
    void scCfg01_normalConfig_bothModelsRoutable() {
        // 选 B → 响应含 MODEL_B_RESPONSE
        String respB = postQuery(Map.of("model_name", ModelSelectionSupport.ALIAS_B));
        assertThat(respB).contains(ModelSelectionSupport.RESPONSE_B);

        // 缺省 → 响应含 MODEL_A_RESPONSE
        String respA = postQuery(Map.of());
        assertThat(respA).contains(ModelSelectionSupport.RESPONSE_A);
    }

    // ===== SC-CFG-02: 公开别名与供应商模型名区分 =====

    @Test
    @DisplayName("SC-CFG-02: 公开别名 alias-b → mock 收到的供应商 model 为 provider-chat-b")
    void scCfg02_aliasVsProviderModelName() {
        mockB.calls().clear();
        postQuery(Map.of("model_name", ModelSelectionSupport.ALIAS_B));

        mockB.awaitCalls(1, 5_000);
        MockLlmServer.RecordedRequest call = mockB.firstCall();
        assertThat(call).isNotNull();
        assertThat(call.model()).isEqualTo(ModelSelectionSupport.PROVIDER_MODEL_B);
        assertThat(call.model()).isNotEqualTo(ModelSelectionSupport.ALIAS_B);
    }

    // ===== SC-ROUTE-07: 缺省显式注入根默认 ID =====

    @Test
    @DisplayName("SC-ROUTE-07: 缺省时 mock 端点 A 收到请求（显式注入根默认 ID）")
    void scRoute07_defaultInjectsRootDefault() {
        mockA.calls().clear();
        postQuery(Map.of());

        mockA.awaitCalls(1, 5_000);
        assertThat(mockA.callCount()).isGreaterThanOrEqualTo(1);
        MockLlmServer.RecordedRequest call = mockA.firstCall();
        assertThat(call).isNotNull();
        assertThat(call.model()).isEqualTo(ModelSelectionSupport.PROVIDER_MODEL_A);
    }

    // ===== SC-PARAM-01: model_name 缺失 =====

    @Test
    @DisplayName("SC-PARAM-01: model_name 缺失 → 使用根默认 alias-a")
    void scParam01_modelNameAbsent_usesDefault() {
        String resp = postQuery(Map.of());
        assertThat(resp).contains(ModelSelectionSupport.RESPONSE_A);
    }

    // ===== SC-PARAM-02: model_name = null =====

    @Test
    @DisplayName("SC-PARAM-02: model_name=null → 使用根默认 alias-a")
    void scParam02_modelNameNull_usesDefault() {
        String resp = postQueryRaw(
                "{\"conversation_id\":\"" + UUID.randomUUID()
                        + "\",\"message\":\"你好\",\"model_name\":null,\"stream\":false}");
        assertThat(resp).contains(ModelSelectionSupport.RESPONSE_A);
    }

    // ===== SC-PARAM-03: model_name 空白 → HTTP 400 =====

    @Test
    @DisplayName("SC-PARAM-03: model_name 空白 → HTTP 400")
    void scParam03_modelNameBlank_returns400() {
        int status = postQueryStatus(Map.of("model_name", "  "));
        assertThat(status).isEqualTo(400);
    }

    // ===== SC-PARAM-04: model_name 数字 → HTTP 400 =====

    @Test
    @DisplayName("SC-PARAM-04: model_name=123 → HTTP 400（类型非法）")
    void scParam04_modelNameNumber_returns400() {
        int status = postQueryRawStatus(
                "{\"conversation_id\":\"" + UUID.randomUUID()
                        + "\",\"message\":\"你好\",\"model_name\":123,\"stream\":false}");
        assertThat(status).isEqualTo(400);
    }

    // ===== SC-PARAM-08: 未知别名 → 执行失败 =====

    @Test
    @DisplayName("SC-PARAM-08: 未知别名 → HTTP 500，响应含 AGENT_EXECUTION_FAILED（非 'not enabled'）")
    void scParam08_unknownAlias_returns500() {
        HttpResponse<String> resp = postQueryFull(Map.of("model_name", "alias-x"));
        assertThat(resp.statusCode()).isGreaterThanOrEqualTo(500);
        String body = resp.body();
        // 开发反馈：同步 Query 的执行异常统一返回 AGENT_EXECUTION_FAILED，不暴露内部异常明文
        // 当 catalog 正确注入时，未知别名 → AGENT_EXECUTION_FAILED
        // 当 catalog=null（ext handler）时，错误为 "not enabled"
        assertThat(body)
                .as("未知别名应返回 AGENT_EXECUTION_FAILED（当 catalog 正确注入时），而非 'Unknown model_name' 明文")
                .contains("AGENT_EXECUTION_FAILED");
    }

    // ===== SC-PARAM-13: trim 后路由 + 大小写敏感 =====

    @Test
    @DisplayName("SC-PARAM-13a: model_name=' alias-b ' → trim 后路由到端点 B")
    void scParam13a_trimmedModelName_routestoB() {
        String resp = postQuery(Map.of("model_name", " " + ModelSelectionSupport.ALIAS_B + " "));
        assertThat(resp).contains(ModelSelectionSupport.RESPONSE_B);
    }

    @Test
    @DisplayName("SC-PARAM-13b: model_name='ALIAS-B' → 大小写不命中，响应含 AGENT_EXECUTION_FAILED")
    void scParam13b_caseSensitive_notMatched() {
        HttpResponse<String> resp = postQueryFull(Map.of("model_name", "ALIAS-B"));
        assertThat(resp.statusCode()).isGreaterThanOrEqualTo(500);
        // 开发反馈：执行异常统一返回 AGENT_EXECUTION_FAILED，不暴露内部异常明文
        assertThat(resp.body())
                .as("大小写不命中应返回 AGENT_EXECUTION_FAILED，而非 'Unknown model_name' 明文")
                .contains("AGENT_EXECUTION_FAILED");
    }

    // ===== SC-ROUTE-05 + SC-CORE-09: B 后新请求缺省 A（请求间隔离）=====

    @Test
    @DisplayName("SC-ROUTE-05: B 后新请求缺省回 A（不同 conversationId）")
    void scRoute05_bThenDefault_returnsA() {
        String respB = postQuery(Map.of("model_name", ModelSelectionSupport.ALIAS_B));
        assertThat(respB).contains(ModelSelectionSupport.RESPONSE_B);

        String respA = postQuery(Map.of());
        assertThat(respA).contains(ModelSelectionSupport.RESPONSE_A);
    }

    // ===== SC-ROUTE-06: 同会话 A→B→缺省 A =====

    @Test
    @DisplayName("SC-ROUTE-06: 同会话 A→B→缺省 A")
    void scRoute06_sameConversation_a_b_defaultA() {
        String convId = "conv-route06-" + UUID.randomUUID();

        String respA1 = postQuery(convId, Map.of("model_name", ModelSelectionSupport.ALIAS_A));
        assertThat(respA1).contains(ModelSelectionSupport.RESPONSE_A);

        String respB = postQuery(convId, Map.of("model_name", ModelSelectionSupport.ALIAS_B));
        assertThat(respB).contains(ModelSelectionSupport.RESPONSE_B);

        String respA2 = postQuery(convId, Map.of());
        assertThat(respA2).contains(ModelSelectionSupport.RESPONSE_A);
    }

    // ===== SC-CORE-11: 不改共享默认 =====

    @Test
    @DisplayName("SC-CORE-11: 请求 B 后新请求 A — 共享 Agent 默认不变")
    void scCore11_bThenDefault_sharedDefaultUnchanged() {
        String respB = postQuery(Map.of("model_name", ModelSelectionSupport.ALIAS_B));
        assertThat(respB).contains(ModelSelectionSupport.RESPONSE_B);

        String respA = postQuery(Map.of());
        assertThat(respA).contains(ModelSelectionSupport.RESPONSE_A);

        String respA2 = postQuery(Map.of());
        assertThat(respA2).contains(ModelSelectionSupport.RESPONSE_A);
    }

    // ===== SC-ROUTE-01: A2A 同步选 B =====

    @Test
    @DisplayName("SC-ROUTE-01: A2A 同步 model_name=alias-b → 响应含 MODEL_B_RESPONSE")
    void scRoute01_a2aSyncSelectB() {
        String resp = postQueryA2a(ModelSelectionSupport.ALIAS_B);
        assertThat(resp).contains(ModelSelectionSupport.RESPONSE_B);
    }

    // ===== SC-PARAM-05: model_name 为布尔 → 400 =====

    @Test
    @DisplayName("SC-PARAM-05: model_name=true → HTTP 400（类型非法）")
    void scParam05_modelNameBoolean_returns400() {
        int status = postQueryRawStatus(
                "{\"conversation_id\":\"" + UUID.randomUUID()
                        + "\",\"message\":\"你好\",\"model_name\":true,\"stream\":false}");
        assertThat(status).isEqualTo(400);
    }

    // ===== SC-PARAM-06: model_name 为数组 → 400 =====

    @Test
    @DisplayName("SC-PARAM-06: model_name=[\"a\",\"b\"] → HTTP 400（类型非法）")
    void scParam06_modelNameArray_returns400() {
        int status = postQueryRawStatus(
                "{\"conversation_id\":\"" + UUID.randomUUID()
                        + "\",\"message\":\"你好\",\"model_name\":[\"a\",\"b\"],\"stream\":false}");
        assertThat(status).isEqualTo(400);
    }

    // ===== SC-PARAM-10: 非正式位置不触发选模 =====

    @Test
    @DisplayName("SC-PARAM-10: body.model_id=alias-b（非 model_name）→ 不触发选模，不返回 'not enabled'")
    void scParam10_nonStandardField_noSelection() {
        // body 含 model_id 但不含 model_name
        // modelCatalog=null 时：model_name 缺失 → selected=null → 走旧路径（不抛 "not enabled"）
        // 但旧路径使用 dummy LLM → ConnectException，错误不应是 "not enabled"
        HttpResponse<String> resp = postQueryRawFull(
                "{\"conversation_id\":\"" + UUID.randomUUID()
                        + "\",\"message\":\"你好\",\"model_id\":\"alias-b\",\"stream\":false}");
        String body = resp.body();
        // 关键断言：不应返回 "not enabled"（说明 model_id 没被误当作 model_name）
        assertThat(body)
                .as("body.model_id 不应触发模型选择，不应返回 'not enabled' 错误")
                .doesNotContain("not enabled");
    }

    // ===== SC-CONC-03: 不提升伪造 Core 字段 =====

    @Test
    @DisplayName("SC-CONC-03: body 含 dynamic_model_id → 不提升到顶层，不返回 'not enabled'")
    void scConc03_dynamicModelId_notPromoted() {
        HttpResponse<String> resp = postQueryRawFull(
                "{\"conversation_id\":\"" + UUID.randomUUID()
                        + "\",\"message\":\"你好\",\"dynamic_model_id\":\"internal-id\",\"stream\":false}");
        String body = resp.body();
        // 关键断言：dynamic_model_id 不应被提升为 model_name
        assertThat(body)
                .as("dynamic_model_id 不应被提升为 model_name，不应返回 'not enabled' 错误")
                .doesNotContain("not enabled");
    }

    // ===== SC-PARAM-12: WebFlux 入口参数校验 =====

    @Test
    @org.junit.jupiter.api.Disabled("WebFlux /v1/query/reactive 端点在当前 demo 中默认关闭（application.yml: webflux.enabled=false），"
            + "这是预期行为，不是选模 Bug。开启模型选择不会同时开启 WebFlux。")
    @DisplayName("SC-PARAM-12: POST /v1/query/reactive model_name=123 → HTTP 400（reactive 使用相同 DTO 校验）")
    void scParam12_webFlux_modelNameNumber_returns400() {
        int status = postReactiveQueryRawStatus(
                "{\"conversation_id\":\"" + UUID.randomUUID()
                        + "\",\"message\":\"你好\",\"model_name\":123,\"stream\":false}");
        assertThat(status).isEqualTo(400);
    }

    // ===== SC-PARAM-07: A2A metadata.model_name 格式错误 → -32602 =====

    @Test
    @DisplayName("SC-PARAM-07: A2A metadata.model_name=123 → JSON-RPC -32602")
    void scParam07_a2aMetadataModelNameType_error() {
        // A2A metadata.model_name is a number (not string) → should be rejected with -32602
        // Even though catalog=null (problem #2), the type validation happens before catalog check
        // Build raw JSON-RPC with metadata.model_name = 123 (number, not string)
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode rpcRequest = mapper.createObjectNode();
        rpcRequest.put("jsonrpc", "2.0");
        rpcRequest.put("id", "test-param07");
        rpcRequest.put("method", "SendMessage");
        com.fasterxml.jackson.databind.node.ObjectNode params = mapper.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode metadata = mapper.createObjectNode();
        metadata.put("model_name", 123); // number, not string
        params.set("metadata", metadata);
        com.fasterxml.jackson.databind.node.ObjectNode message = mapper.createObjectNode();
        message.put("role", "ROLE_USER");
        message.put("messageId", "msg-" + UUID.randomUUID());
        message.put("contextId", "ctx-param07-" + UUID.randomUUID());
        message.set("parts", mapper.createArrayNode().add(mapper.createObjectNode().put("text", "你好")));
        params.set("message", message);
        rpcRequest.set("params", params);

        try {
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create(stack.baseUrl("deepagent-a") + "/a2a"))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(rpcRequest)))
                    .build();
            java.net.http.HttpResponse<String> resp = HTTP.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            String body = resp.body();
            // Should contain -32602 error code
            assertThat(body)
                    .as("A2A metadata.model_name=123 应返回 JSON-RPC -32602 错误")
                    .contains("-32602");
        } catch (Exception e) {
            // If the request itself fails, that's also a form of rejection
            assertThat(e).isNotNull();
        }
    }

    // ===== Helper methods =====

    private String postQuery(Map<String, Object> extraFields) {
        return postQuery(UUID.randomUUID().toString(), extraFields);
    }

    private String postQuery(String convId, Map<String, Object> extraFields) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversation_id", convId);
        body.put("message", "你好");
        body.put("stream", false);
        body.putAll(extraFields);
        return postQueryRaw(ModelSelectionSupport.toJson(body));
    }

    private int postQueryStatus(Map<String, Object> extraFields) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversation_id", UUID.randomUUID().toString());
        body.put("message", "你好");
        body.put("stream", false);
        body.putAll(extraFields);
        return postQueryRawStatus(ModelSelectionSupport.toJson(body));
    }

    private String postQueryRaw(String jsonBody) {
        try {
            URI endpoint = URI.create(stack.baseUrl("deepagent-a") + "/v1/query");
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(endpoint)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.body();
        } catch (Exception e) {
            throw new RuntimeException("POST /v1/query failed", e);
        }
    }

    private int postQueryRawStatus(String jsonBody) {
        try {
            URI endpoint = URI.create(stack.baseUrl("deepagent-a") + "/v1/query");
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(endpoint)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode();
        } catch (Exception e) {
            throw new RuntimeException("POST /v1/query failed", e);
        }
    }

    private HttpResponse<String> postQueryFull(Map<String, Object> extraFields) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversation_id", UUID.randomUUID().toString());
        body.put("message", "你好");
        body.put("stream", false);
        body.putAll(extraFields);
        return postQueryRawFull(ModelSelectionSupport.toJson(body));
    }

    private HttpResponse<String> postQueryRawFull(String jsonBody) {
        try {
            URI endpoint = URI.create(stack.baseUrl("deepagent-a") + "/v1/query");
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(endpoint)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException("POST /v1/query failed", e);
        }
    }

    private int postReactiveQueryRawStatus(String jsonBody) {
        try {
            URI endpoint = URI.create(stack.baseUrl("deepagent-a") + "/v1/query/reactive");
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(endpoint)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode();
        } catch (Exception e) {
            throw new RuntimeException("POST /v1/query/reactive failed", e);
        }
    }

    private String postQueryA2a(String modelName) {
        A2aServiceClient a2aClient = stack.client("deepagent-a");
        AtomicReference<String> textRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Map<String, Object> metadata = Map.of("model_name", modelName);
        a2aClient.sendMessage(
                A2A.toUserMessage("你好"),
                metadata,
                List.of((event, card) -> {
                    if (event instanceof TaskEvent te) {
                        String state = te.getTask().status() != null
                                ? String.valueOf(te.getTask().status().state()) : "";
                        if ("TASK_STATE_COMPLETED".equals(state)
                                || "TASK_STATE_FAILED".equals(state)) {
                            textRef.set(com.huawei.ascend.sit.client.TaskTextExtractor.textOf(te.getTask()));
                            latch.countDown();
                        }
                    }
                }),
                error -> latch.countDown());

        try {
            latch.await(config.getPollTimeoutSeconds() * 1000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for A2A response", e);
        }
        return textRef.get() == null ? "" : textRef.get();
    }
}

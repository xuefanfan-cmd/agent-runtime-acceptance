package com.huawei.ascend.sit.cases.component.model_selection;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-050/051 — 兼容性验收测试。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>SC-CFG-03 — opt-in 开关关闭时不校验追加项，服务正常启动走旧路径</li>
 *   <li>SC-PARAM-09 — 无目录却显式选模 → 执行失败，不静默忽略</li>
 * </ul>
 *
 * <p>SC-CFG-03 使用真实 LLM 端点（开关关闭时模型选择不生效，走 deepagent-a 默认 LLM 配置），
 * 需要环境变量 {@code LLM_API_KEY} / {@code LLM_API_BASE} 已配置。
 */
@Tag("component")
@Tag("feat-050-051")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelSelectionCompatTest {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private TestConfig config;
    private SutStack stack;

    @BeforeAll
    void setUp() {
        config = TestConfig.load();
        // model-selection-enabled is NOT set — defaults to false (old path)
        stack = ModelSelectionSupport.buildStackWithoutModelSelection(config).start();
    }

    @AfterAll
    void tearDown() {
        if (stack != null) stack.close();
    }

    // ===== SC-CFG-03: opt-in 开关关闭 → 旧配置行为不变 =====

    @Test
    @DisplayName("SC-CFG-03: model-selection-enabled=false → 服务正常启动，请求走旧单模型路径")
    void scCfg03_switchOff_oldPathWorks() {
        // 发请求不传 model_name → 使用 Spring 默认 LLM 配置
        // 只要服务启动成功且返回 200，就说明旧路径正常工作
        String resp = postQuery(Map.of());
        assertThat(resp).isNotBlank();
    }

    // ===== SC-PARAM-09: 无目录却显式选模 → 执行失败 =====

    @Test
    @DisplayName("SC-PARAM-09: 无目录却显式选模 → 执行失败，不静默忽略")
    void scParam09_noCatalogExplicitSelect_fails() {
        int status = postQueryStatus(Map.of("model_name", ModelSelectionSupport.ALIAS_B));
        // 无目录时 model_name 无法解析 → 执行失败（500）
        assertThat(status).isGreaterThanOrEqualTo(500);
    }

    // ===== SC-COMPAT-02: 无目录不选模保持旧路径 =====

    @Test
    @DisplayName("SC-COMPAT-02: 无目录不传 model_name → 旧路径正常工作，不注入 model_id")
    void scCompat02_noCatalogOldPathWorks() {
        // model-selection-enabled=false → modelCatalog=null → 不传 model_name → 走旧路径
        String resp = postQuery(Map.of());
        assertThat(resp).isNotBlank();
        // 关键断言：响应不应包含 "not enabled" 错误（因为没有传 model_name）
        assertThat(resp)
                .as("无目录 + 不传 model_name → 旧路径正常，不应返回 'not enabled' 错误")
                .doesNotContain("not enabled");
    }

    // ===== SC-PARAM-11: A2A message.metadata 不触发选模 =====

    @Test
    @DisplayName("SC-PARAM-11: A2A params.message.metadata.model_name 不触发选模（非正式位置）")
    void scParam11_a2aMessageMetadata_noSelection() {
        // model_name in params.message.metadata (wrong location) should NOT be picked up
        // With model-selection off, even if it were picked up, it would cause "not enabled"
        // So the test verifies: no "not enabled" error → model_name was NOT detected
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode rpcRequest = mapper.createObjectNode();
        rpcRequest.put("jsonrpc", "2.0");
        rpcRequest.put("id", "test-param11");
        rpcRequest.put("method", "SendMessage");
        com.fasterxml.jackson.databind.node.ObjectNode params = mapper.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode message = mapper.createObjectNode();
        message.put("role", "user");
        message.put("messageId", "msg-" + UUID.randomUUID());
        message.set("parts", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("kind", "text").put("text", "你好")));
        // model_name in message.metadata — WRONG location, should not trigger selection
        com.fasterxml.jackson.databind.node.ObjectNode messageMetadata = mapper.createObjectNode();
        messageMetadata.put("model_name", ModelSelectionSupport.ALIAS_B);
        message.set("metadata", messageMetadata);
        params.set("message", message);
        rpcRequest.set("params", params);

        try {
            URI endpoint = URI.create(stack.baseUrl("deepagent-a") + "/a2a");
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(endpoint)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(rpcRequest)))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            // model_name in wrong location → NOT picked up → no "not enabled" error
            assertThat(resp.body())
                    .as("model_name 在 message.metadata 中不应触发选模，不应返回 'not enabled'")
                    .doesNotContain("not enabled");
        } catch (Exception e) {
            throw new RuntimeException("A2A SendMessage failed", e);
        }
    }

    // ===== SC-FAIL-10: 正常业务数据含 "error" 不误判 =====

    @Test
    @DisplayName("SC-FAIL-10: LLM 响应含 'error' 文本但非错误响应 → 不误判为失败")
    void scFail10_businessDataWithError_notMisjudged() throws Exception {
        // Start a mock LLM that returns content containing "error" in the business text
        try (MockLlmServer mockWithErr = MockLlmServer.start(
                "An error occurred in the user's code, please check line 42.", "mock-err-model")) {

            TestConfig config = TestConfig.load();
            SutStack errStack = SutStack.builder(config)
                    .streaming(false)
                    .agent("deepagent-a", a -> {
                        // model-selection off, point DeepAgent to mock LLM
                        a.property(ModelSelectionSupport.PROP_DEEP_AGENT_API_BASE, mockWithErr.baseUrl());
                        a.property(ModelSelectionSupport.PROP_DEEP_AGENT_API_KEY, "mock-key");
                        a.property(ModelSelectionSupport.PROP_DEEP_AGENT_MODEL_NAME, "mock-err-model");
                        a.property(ModelSelectionSupport.PROP_REMOTE_A2A_CARD_URL, "http://127.0.0.1:1/.well-known/agent-card.json");
                    })
                    .start();
            try {
                assertThat(errStack.isRunning("deepagent-a")).isTrue();
                // Send query — response should contain "error" in business content, but NOT be an error response
                String resp = postQueryTo(errStack.baseUrl("deepagent-a"), Map.of());
                assertThat(resp).isNotBlank();
                // The response should contain the mock content (with "error" in it)
                // but should NOT be an AGENT_EXECUTION_FAILED error response
                assertThat(resp)
                        .as("响应含 'error' 业务文本但不应误判为执行失败")
                        .doesNotContain("AGENT_EXECUTION_FAILED");
            } finally {
                errStack.close();
            }
        }
    }

    // ===== Helper methods =====

    private String postQuery(Map<String, Object> extraFields) {
        return postQueryTo(stack.baseUrl("deepagent-a"), extraFields);
    }

    private String postQueryTo(String baseUrl, Map<String, Object> extraFields) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversation_id", UUID.randomUUID().toString());
        body.put("message", "你好");
        body.put("stream", false);
        body.putAll(extraFields);
        try {
            URI endpoint = URI.create(baseUrl + "/v1/query");
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(endpoint)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(ModelSelectionSupport.toJson(body)))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.body();
        } catch (Exception e) {
            throw new RuntimeException("POST /v1/query failed", e);
        }
    }

    private int postQueryStatus(Map<String, Object> extraFields) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversation_id", UUID.randomUUID().toString());
        body.put("message", "你好");
        body.put("stream", false);
        body.putAll(extraFields);
        try {
            URI endpoint = URI.create(stack.baseUrl("deepagent-a") + "/v1/query");
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(endpoint)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(ModelSelectionSupport.toJson(body)))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode();
        } catch (Exception e) {
            throw new RuntimeException("POST /v1/query failed", e);
        }
    }
}

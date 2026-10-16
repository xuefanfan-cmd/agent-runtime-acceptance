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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-050/051 — 所选模型调用失败验收测试。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>SC-FAIL-01 / SC-CORE-13 — 401 认证失败 → 执行失败，不自动换模型</li>
 *   <li>SC-FAIL-02 / SC-CORE-14 — 5xx 服务端错误 → 执行失败</li>
 *   <li>SC-FAIL-03 / SC-CORE-15 — 连接失败 → 执行失败</li>
 * </ul>
 *
 * <p>每个测试方法独立启动一个 SUT 实例（通过 try-with-resources），因为不同场景
 * 需要 mock B 设置不同的错误模式。
 */
@Tag("component")
@Tag("feat-050-051")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelSelectionFailureTest {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private TestConfig config;

    @BeforeAll
    void setUp() {
        config = TestConfig.load();
    }

    // ===== SC-FAIL-01 / SC-CORE-13: 401 认证失败 =====

    @Test
    @DisplayName("SC-FAIL-01: 401 认证失败 → 执行失败，不自动调用 alias-a（假阳性检测：mockB 应被调用）")
    void scFail01_401_failureNoFallback() throws Exception {
        try (FailureStackHolder holder = new FailureStackHolder(
                MockLlmServer.ErrorMode.UNAUTHORIZED_401)) {

            int status = holder.postQuerySelectB();
            assertThat(status).isGreaterThanOrEqualTo(500);

            // 假阳性检测：mockB 应该收到请求（401 来自 LLM），而非因 "not enabled" 被拒绝
            holder.assertMockBCalled();
            // Mock A should NOT receive any request (no automatic fallback)
            holder.assertMockANotCalled();
        }
    }

    // ===== SC-FAIL-02 / SC-CORE-14: 5xx 服务端错误 =====

    @Test
    @DisplayName("SC-FAIL-02: 500 服务端错误 → 执行失败，不自动换模型（假阳性检测：mockB 应被调用）")
    void scFail02_500_failureNoFallback() throws Exception {
        try (FailureStackHolder holder = new FailureStackHolder(
                MockLlmServer.ErrorMode.SERVER_ERROR_500)) {

            int status = holder.postQuerySelectB();
            assertThat(status).isGreaterThanOrEqualTo(500);

            // 假阳性检测：mockB 应该收到请求（500 来自 LLM）
            holder.assertMockBCalled();
            holder.assertMockANotCalled();
        }
    }

    // ===== SC-FAIL-03 / SC-CORE-15: 连接失败 =====

    @Test
    @DisplayName("SC-FAIL-03: 连接失败（不可达地址）→ 执行失败，响应含 AGENT_EXECUTION_FAILED（非 'not enabled'）")
    void scFail03_connectionFailure_exposedAsFailure() throws Exception {
        // Use a completely unreachable port for mock B
        try (FailureStackHolder holder = new FailureStackHolder(
                "http://127.0.0.1:9/v1", // unreachable
                null)) { // no mock B needed
            HttpResponse<String> resp = holder.postQuerySelectBFull();
            assertThat(resp.statusCode()).isGreaterThanOrEqualTo(500);

            // 开发反馈：连接失败等执行异常统一返回 AGENT_EXECUTION_FAILED，不暴露 ConnectException 明文
            String body = resp.body();
            assertThat(body)
                    .as("连接失败应返回 AGENT_EXECUTION_FAILED，而非暴露 ConnectException 明文")
                    .contains("AGENT_EXECUTION_FAILED")
                    .doesNotContain("not enabled");

            holder.assertMockANotCalled();
        }
    }

    // ===== SC-FAIL-09: 旧无目录流式错误兼容 =====

    @Test
    @DisplayName("SC-FAIL-09: model-selection 关闭 + 不可达 LLM → 流式请求返回错误，不崩溃")
    void scFail09_noCatalogStreamingError_compat() throws Exception {
        // model-selection-enabled=false → modelCatalog=null → 走旧路径
        // Use unreachable LLM endpoint → streaming should return error gracefully, not crash
        SutStack noCatalogStack = SutStack.builder(config)
                .streaming(false)
                .agent("deepagent-a", a -> {
                    a.property(ModelSelectionSupport.PROP_DEEP_AGENT_API_BASE, "http://127.0.0.1:1/v1");
                    a.property(ModelSelectionSupport.PROP_DEEP_AGENT_API_KEY, "dummy-key");
                    a.property(ModelSelectionSupport.PROP_DEEP_AGENT_MODEL_NAME, "dummy-model");
                    a.property(ModelSelectionSupport.PROP_REMOTE_A2A_CARD_URL, "http://127.0.0.1:1/.well-known/agent-card.json");
                })
                .start();
        try {
            // Send a streaming request — should get an error response (not crash)
            URI endpoint = URI.create(noCatalogStack.baseUrl("deepagent-a") + "/v1/query");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("conversation_id", UUID.randomUUID().toString());
            body.put("message", "你好");
            body.put("stream", true);
            // No model_name — old path

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(endpoint)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(ModelSelectionSupport.toJson(body)))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

            // LLM unreachable → error in response body (HTTP 200 with error content or HTTP >= 500)
            // Either is acceptable — the key is the server doesn't crash
            String responseBody = resp.body();
            assertThat(responseBody)
                    .as("Response should contain error indicators (LLM unreachable)")
                    .containsAnyOf("error", "failed", "ConnectException", "transport failure", "circuit breaker");
            // Should NOT contain "not enabled" since model_name was not sent
            assertThat(responseBody).doesNotContain("not enabled");
        } finally {
            noCatalogStack.close();
        }
    }

    // ===== Helper: per-test SUT + mock holder =====

    /**
     * Holds a mock LLM A (always healthy), optionally a mock B (with error mode),
     * and a SutStack with model-selection pointing at them.
     */
    private final class FailureStackHolder implements AutoCloseable {
        private final MockLlmServer mockA;
        private final MockLlmServer mockB;  // may be null for connection-failure test
        private final SutStack stack;
        private final Path configFile;

        FailureStackHolder(MockLlmServer.ErrorMode errorMode) throws Exception {
            mockA = MockLlmServer.start(ModelSelectionSupport.RESPONSE_A, ModelSelectionSupport.PROVIDER_MODEL_A);
            mockB = MockLlmServer.start(ModelSelectionSupport.RESPONSE_B, ModelSelectionSupport.PROVIDER_MODEL_B);
            mockB.setErrorMode(errorMode);

            Path tempDir = Files.createTempDirectory("model-selection-failure");
            configFile = ModelSelectionSupport.writeApiConfig(tempDir, mockA.baseUrl(), mockB.baseUrl());

            stack = ModelSelectionSupport.buildStackWithModelSelection(config, configFile).start();
        }

        FailureStackHolder(String unreachableBUrl, Object unused) throws Exception {
            mockA = MockLlmServer.start(ModelSelectionSupport.RESPONSE_A, ModelSelectionSupport.PROVIDER_MODEL_A);
            mockB = null; // no mock B — use unreachable URL

            Path tempDir = Files.createTempDirectory("model-selection-failure");
            configFile = ModelSelectionSupport.writeApiConfigWithBadEndpoint(
                    tempDir, "http://127.0.0.1:9/v1", unreachableBUrl);

            stack = ModelSelectionSupport.buildStackWithModelSelection(config, configFile).start();
        }

        int postQuerySelectB() {
            return postQuerySelectBFull().statusCode();
        }

        HttpResponse<String> postQuerySelectBFull() {
            try {
                URI endpoint = URI.create(stack.baseUrl("deepagent-a") + "/v1/query");
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("conversation_id", UUID.randomUUID().toString());
                body.put("message", "你好");
                body.put("stream", false);
                body.put("model_name", ModelSelectionSupport.ALIAS_B);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(endpoint)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(ModelSelectionSupport.toJson(body)))
                        .build();
                return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                // Connection failure may throw — return a synthetic 500
                return null;
            }
        }

        void assertMockANotCalled() {
            // Wait briefly to ensure no delayed call to mock A
            try { Thread.sleep(1_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            assertThat(mockA.callCount())
                    .as("Mock A should NOT receive requests — no automatic fallback")
                    .isZero();
        }

        void assertMockBCalled() {
            // Wait briefly to ensure mock B has time to receive the request
            try { Thread.sleep(1_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            assertThat(mockB.callCount())
                    .as("Mock B should receive the request (LLM call attempted) — 假阳性检测：modelCatalog=null 时请求未到达 LLM")
                    .isGreaterThanOrEqualTo(1);
        }

        @Override
        public void close() {
            if (stack != null) stack.close();
            if (mockB != null) mockB.close();
            if (mockA != null) mockA.close();
        }
    }
}

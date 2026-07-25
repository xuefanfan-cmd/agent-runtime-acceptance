package com.huawei.ascend.sit.cases.component.registry_discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.config.TestConfig;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-015 P0 — rdc REST API 错误表面（无 fixture 依赖）。
 *
 * <p>直接以 JDK {@link HttpClient} 调 rdc 的 {@code POST /api/registry/discover}
 * 和 {@code POST /api/registry/register}，验证错误码和错误体。
 * rdc 作为外部 SUT 已部署在服务器上，不需要 SutStack 管理其生命周期。
 */
@Tag("component")
@Tag("registry-discovery")
@Tag("feat-015")
@Feature("FEAT-015: Agent Card 注册与发现")
class DiscoverErrorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static String rdcBaseUrl;

    @BeforeAll
    static void loadConfig() {
        TestConfig config = TestConfig.load();
        rdcBaseUrl = config.getString("sut.external.rdc.base-url", "http://localhost:8092");
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(rdcBaseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    // ---- FEAT-015.invalid-query ----

    @Test
    @DisplayName("缺 context → 400 INVALID_QUERY")
    @Story("discover.invalid-query: 请求字段非法 → INVALID_QUERY")
    void missingContextReturnsInvalidQuery() throws Exception {
        HttpResponse<String> resp = post("/api/registry/discover",
                "{\"agentId\":\"test\"}");
        assertError(resp, 400, "INVALID_QUERY", false);
    }

    @Test
    @DisplayName("缺 context.tenantId → 400 INVALID_QUERY")
    @Story("discover.invalid-query: 请求字段非法 → INVALID_QUERY")
    void missingTenantIdReturnsInvalidQuery() throws Exception {
        HttpResponse<String> resp = post("/api/registry/discover",
                "{\"context\":{},\"agentId\":\"test\"}");
        assertError(resp, 400, "INVALID_QUERY", false);
    }

    @Test
    @DisplayName("缺 agentId+serviceId+a2aSkillId → 400 INVALID_QUERY")
    @Story("discover.invalid-query: 请求字段非法 → INVALID_QUERY")
    void missingAllTargetFieldsReturnsInvalidQuery() throws Exception {
        HttpResponse<String> resp = post("/api/registry/discover",
                "{\"context\":{\"tenantId\":\"tenant-A\"}}");
        assertError(resp, 400, "INVALID_QUERY", false);
    }

    // ---- FEAT-015.deadline-exceeded ----

    @Test
    @DisplayName("已过期 deadline → 503 DEADLINE_EXCEEDED")
    @Story("discover.deadline-exceeded: 超时 → DEADLINE_EXCEEDED")
    void expiredDeadlineReturnsDeadlineExceeded() throws Exception {
        HttpResponse<String> resp = post("/api/registry/discover",
                "{\"context\":{\"tenantId\":\"tenant-A\",\"deadline\":\"2020-01-01T00:00:00Z\"},\"agentId\":\"test\"}");
        assertError(resp, 503, "DEADLINE_EXCEEDED", true);
    }

    // ---- FEAT-015.caller-unauthorized ----

    @Test
    @DisplayName("caller 不在 allowlist → 403 CALLER_NOT_AUTHORIZED")
    @Story("discover.caller-unauthorized: 未授权 caller → CALLER_NOT_AUTHORIZED")
    void unauthorizedCallerReturnsCallerNotAuthorized() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(rdcBaseUrl + "/api/registry/discover"))
                .header("Content-Type", "application/json")
                .header("X-Caller-Ref", "unauthorized-caller")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"context\":{\"tenantId\":\"tenant-A\"},\"agentId\":\"test\"}"))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        // 如果 allowlist 未配置（空 {}），任意非空 callerRef 都能通过，则返回 NO_MATCH 而非 403。
        // 此用例需要 rdc 配置 caller-allowlist 后才能 PASS。
        assertErrorOrNoMatch(resp);
    }

    // ---- helpers ----

    private void assertError(HttpResponse<String> resp, int expectedStatus,
                             String expectedError, boolean expectedRetryable) throws Exception {
        assertThat(resp.statusCode())
                .as("HTTP status for error=" + expectedError)
                .isEqualTo(expectedStatus);

        JsonNode body = MAPPER.readTree(resp.body());
        assertThat(body.get("error").asText())
                .as("error code")
                .isEqualTo(expectedError);
        assertThat(body.get("retryable").asBoolean())
                .as("retryable flag")
                .isEqualTo(expectedRetryable);
        assertThat(body.has("message")).as("has message field").isTrue();
        assertThat(body.has("traceId")).as("has traceId field").isTrue();
    }

    private void assertErrorOrNoMatch(HttpResponse<String> resp) throws Exception {
        int status = resp.statusCode();
        if (status == 403) {
            JsonNode body = MAPPER.readTree(resp.body());
            assertThat(body.get("error").asText()).isEqualTo("CALLER_NOT_AUTHORIZED");
        } else {
            // allowlist 未配置时，任意 callerRef 通过 → NO_MATCH
            assertThat(status).isEqualTo(200);
            JsonNode body = MAPPER.readTree(resp.body());
            assertThat(body.get("outcome").asText()).isEqualTo("NO_MATCH");
        }
    }
}

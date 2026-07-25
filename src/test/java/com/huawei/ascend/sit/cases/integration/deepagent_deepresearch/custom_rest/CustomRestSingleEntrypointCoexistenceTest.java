package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch.custom_rest;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-022 §5.1.2 单入口独占性：/a2a/ + /v1/query (存量) + /v1/{...} (custom REST) 三个入口
 * 共存于同一端口 18090，互不干扰。
 */
@Tag("integration")
@Tag("feat-022")
@Tag("custom-rest")
@Feature("FEAT-022: 自定义 REST API 智能体服务入口")
@Story("da.single-entrypoint-coexistence: 三入口同端口共存")
class CustomRestSingleEntrypointCoexistenceTest extends BaseCustomRestTest {

    @Test
    @DisplayName("FEAT-022.single-entrypoint-coexistence: /a2a/、/v1/query、/v1/{...} 三入口同端口可访问")
    void threeEntrypointsShouldCoexistOnSamePort() throws Exception {
        String baseUrl = stack.baseUrl(DEEP_RESEARCH);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        // ── 入口 1：/a2a/ 标准 JSON-RPC ──────────────────────────────────
        HttpRequest a2aReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/a2a"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"agent/getAuthenticatedExtendedCard\"}"))
                .timeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<String> a2aResp = http.send(a2aReq, HttpResponse.BodyHandlers.ofString());
        assertThat(a2aResp.statusCode())
                .as("FEAT-022.single-entrypoint-coexistence: /a2a 端点应可访问（非 404）\n  status=%d\n  body=%s",
                        a2aResp.statusCode(), truncate(a2aResp.body(), 300))
                .isNotEqualTo(404);

        // ── 入口 2：/v1/query 存量 REST ──────────────────────────────────
        HttpRequest legacyReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/query"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"ping\"}"))
                .timeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<String> legacyResp = http.send(legacyReq, HttpResponse.BodyHandlers.ofString());
        assertThat(legacyResp.statusCode())
                .as("FEAT-022.single-entrypoint-coexistence: /v1/query 存量端点应可访问（非 404）\n  status=%d\n  body=%s",
                        legacyResp.statusCode(), truncate(legacyResp.body(), 300))
                .isNotEqualTo(404);

        // ── 入口 3：custom REST /v1/{...} ───────────────────────────────
        String conversationId = "conv-feat022-coexist-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> restResp = postSync(conversationId, "ping");
        assertThat(restResp.statusCode())
                .as("FEAT-022.single-entrypoint-coexistence: custom REST 端点应可访问（非 404）\n  status=%d",
                        restResp.statusCode())
                .isNotEqualTo(404);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}

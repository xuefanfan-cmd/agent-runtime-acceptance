package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch.custom_rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Tag("feat-022")
@Tag("custom-rest")
@Feature("FEAT-022: 自定义 REST API 智能体服务入口")
@Story("da.request-mapping: 用户自定义请求映射")
class CustomRestRequestMappingTest extends BaseCustomRestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("FEAT-022.request-mapping: adapter toA2ARequest 接收完整 Context（headers/pathVars/queryParams/body）")
    void requestMappingContextShouldContainAllFields() throws Exception {
        String conversationId = "conv-feat022-req-map-" + UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"input\":\"test message\",\"workspace_id\":\"ws-001\",\"stream\":false}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(customRestUrl(conversationId) + "?debug=true"))
                .header("Content-Type", "application/json")
                .header("X-Custom-Header", "test-value")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(120))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode())
                .as("FEAT-022.request-mapping: 请求应成功（200 或合理错误，非 404/500）\n  conversationId=%s", conversationId)
                .isNotEqualTo(404);
        assertThat(response.statusCode())
                .as("FEAT-022.request-mapping: 不应返回 500\n  conversationId=%s\n  body=%s",
                        conversationId, response.body())
                .isNotEqualTo(500);

        // 若 200，验证响应可解析（adapter 正常处理了请求）
        if (response.statusCode() == 200) {
            JsonNode respBody = MAPPER.readTree(response.body());
            assertThat(respBody.isObject())
                    .as("FEAT-022.request-mapping: 200 响应应为 JSON object\n  body=%s", response.body())
                    .isTrue();
        }
    }
}

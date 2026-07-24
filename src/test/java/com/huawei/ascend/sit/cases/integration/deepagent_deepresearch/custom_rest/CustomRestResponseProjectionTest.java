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

/**
 * C: HTTP Method 限制 — custom REST 端点只允许 POST，其他 method 应返回 405。
 * G: 响应投影字段正确性 — adapter 定义的 success/task_id/conversation_id 应被正确填充。
 */
@Tag("integration")
@Tag("feat-022")
@Tag("custom-rest")
@Feature("FEAT-022: 自定义 REST API 智能体服务入口")
@Story("da.response-projection: adapter 响应字段正确填充 + HTTP Method 限制")
class CustomRestResponseProjectionTest extends BaseCustomRestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── C: HTTP Method 限制 ───────────────────────────────────────────────

    @Test
    @DisplayName("FEAT-022.method-not-allowed [C]: GET 请求应返回 405")
    void getMethodShouldReturn405() throws Exception {
        String conversationId = "conv-feat022-405-get-" + UUID.randomUUID().toString().substring(0, 8);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(customRestUrl(conversationId)))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();
        HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("FEAT-022.method-not-allowed: GET 应返回 405\n  conversationId=%s", conversationId)
                .isEqualTo(405);
    }

    @Test
    @DisplayName("FEAT-022.method-not-allowed [C]: PUT 请求应返回 405")
    void putMethodShouldReturn405() throws Exception {
        String conversationId = "conv-feat022-405-put-" + UUID.randomUUID().toString().substring(0, 8);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(customRestUrl(conversationId)))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                .timeout(Duration.ofSeconds(10))
                .build();
        HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("FEAT-022.method-not-allowed: PUT 应返回 405\n  conversationId=%s", conversationId)
                .isEqualTo(405);
    }

    @Test
    @DisplayName("FEAT-022.method-not-allowed [C]: DELETE 请求应返回 405")
    void deleteMethodShouldReturn405() throws Exception {
        String conversationId = "conv-feat022-405-del-" + UUID.randomUUID().toString().substring(0, 8);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(customRestUrl(conversationId)))
                .DELETE()
                .timeout(Duration.ofSeconds(10))
                .build();
        HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("FEAT-022.method-not-allowed: DELETE 应返回 405\n  conversationId=%s", conversationId)
                .isEqualTo(405);
    }

    // ── G: 响应投影字段正确性 ─────────────────────────────────────────────

    @Test
    @DisplayName("FEAT-022.response-projection [G]: 同步响应应包含 success/task_id/conversation_id 字段")
    void syncResponseShouldContainAdapterDefinedFields() throws Exception {
        String conversationId = "conv-feat022-proj-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> response = postSync(conversationId, "简单问题");

        assertThat(response.statusCode())
                .as("FEAT-022.response-projection [前置]: 请求应成功\n  conversationId=%s", conversationId)
                .isEqualTo(200);

        JsonNode body = MAPPER.readTree(response.body());

        // success 字段
        assertThat(body.has("success"))
                .as("FEAT-022.response-projection [G]: 响应应含 success 字段\n  body=%s", response.body())
                .isTrue();
        assertThat(body.path("success").asBoolean())
                .as("FEAT-022.response-projection [G]: success 应为 true\n  body=%s", response.body())
                .isTrue();

        // task.id 字段(SUT example adapter 嵌在 custom_rsp_data.id;字段位置由 adapter 自决,不丢信息即可)
        String taskId = body.path("custom_rsp_data").path("id").asText("");
        assertThat(taskId)
                .as("FEAT-022.response-projection [G]: 响应 custom_rsp_data.id 应非空(task_id)\n  body=%s", response.body())
                .isNotBlank();

        // conversation_id 字段应回显请求传入的值
        String respConvId = body.path("conversation_id").asText("");
        assertThat(respConvId)
                .as("FEAT-022.response-projection [G]: conversation_id 应回显请求传入的值\n"
                        + "  expected=%s\n  actual=%s\n  body=%s", conversationId, respConvId, response.body())
                .isEqualTo(conversationId);

        // status 字段(嵌在 custom_rsp_data.status)
        String state = body.path("custom_rsp_data").path("status").path("state").asText("");
        assertThat(state)
                .as("FEAT-022.response-projection [G]: 响应 custom_rsp_data.status.state 应非空\n  body=%s", response.body())
                .isNotBlank();
    }
}

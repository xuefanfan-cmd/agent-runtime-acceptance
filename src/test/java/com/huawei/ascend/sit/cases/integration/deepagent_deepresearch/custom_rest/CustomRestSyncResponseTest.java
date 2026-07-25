package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch.custom_rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Tag("feat-022")
@Tag("custom-rest")
@Feature("FEAT-022: 自定义 REST API 智能体服务入口")
@Story("da.sync-json-response: 同步消息调用返回 JSON")
class CustomRestSyncResponseTest extends BaseCustomRestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("FEAT-022.sync-json-response: stream=false 返回 application/json 且可解析")
    void syncRequestShouldReturnJson() throws Exception {
        String conversationId = "conv-feat022-sync-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> response = postSync(conversationId, "简单问题");

        assertThat(response.statusCode())
                .as("FEAT-022.sync-json-response: HTTP status 应为 200\n  conversationId=%s", conversationId)
                .isEqualTo(200);

        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertThat(contentType)
                .as("FEAT-022.sync-json-response: Content-Type 应含 application/json\n  conversationId=%s", conversationId)
                .contains("application/json");

        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.isObject())
                .as("FEAT-022.sync-json-response: 响应 body 应为 JSON object\n  body=%s", response.body())
                .isTrue();
    }
}

package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch.custom_rest;

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
@Story("da.custom-rest-enabled: 自定义 REST 入口启用")
class CustomRestEnabledTest extends BaseCustomRestTest {

    @Test
    @DisplayName("FEAT-022.custom-rest-enabled: 配置 query-path 后端点可访问（非 404）")
    void customRestEndpointShouldBeAccessible() throws Exception {
        String conversationId = "conv-feat022-enabled-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> response = postSync(conversationId, "hello");

        assertThat(response.statusCode())
                .as("FEAT-022.custom-rest-enabled: 端点应已注册，不应返回 404\n  url=%s",
                        customRestUrl(conversationId))
                .isNotEqualTo(404);
    }
}

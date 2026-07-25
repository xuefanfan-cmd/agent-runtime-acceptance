package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch.custom_rest;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2 §8.3 第 10 条 —— framework 内部拼装的 {@code custom-rest:v1:<projectId>:<agentId>:<hash>}
 * contextId 不得泄漏到客户端可见字段。客户端收到的 conversation_id 必须回显请求传入的原值。
 */
@Tag("integration")
@Tag("feat-022")
@Tag("custom-rest")
@Feature("FEAT-022: 自定义 REST API 智能体服务入口")
@Story("da.internal-context-id-not-leaked: 响应中不得出现 custom-rest:v1: 前缀")
class CustomRestInternalContextIdNotLeakedTest extends BaseCustomRestTest {

    /** framework 内部 contextId 前缀（L2 §4.3.2 定义）。 */
    private static final String INTERNAL_PREFIX = "custom-rest:v1:";

    @Test
    @DisplayName("FEAT-022.internal-context-id-not-leaked: 响应 body 不应包含 internal contextId 前缀")
    void responseShouldNotLeakInternalContextIdPrefix() throws Exception {
        String conversationId = "conv-feat022-leak-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> response = postSync(conversationId, "test leak check");

        assertThat(response.statusCode())
                .as("FEAT-022.internal-context-id-not-leaked [前置]: 请求应成功\n  conversationId=%s", conversationId)
                .isEqualTo(200);

        String body = response.body() == null ? "" : response.body();

        // 【核心】响应 body 不得包含 framework 内部 contextId 前缀
        assertThat(body)
                .as("FEAT-022.internal-context-id-not-leaked [核心]: 响应 body 不应包含 framework 内部 "
                        + "contextId 前缀 '%s'，防止 §8.3 第 10 条违反\n"
                        + "  conversationId=%s\n  body=%s",
                        INTERNAL_PREFIX, conversationId, body)
                .doesNotContain(INTERNAL_PREFIX);
    }
}

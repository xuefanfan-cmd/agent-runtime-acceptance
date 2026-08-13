package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-001.push-config-crud — Push Notification Config CRUD 应显式排除.
 *
 * <p>FEAT-001 v2 (version-scope 2026-07-24) 明确将 {@code SetTaskPushNotificationConfig} /
 * {@code GetTaskPushNotificationConfig} / {@code ListTaskPushNotificationConfig} /
 * {@code UpdateTaskPushNotificationConfig} / {@code DeleteTaskPushNotificationConfig}
 * 从 §2 能力表中划出去 —— 现规范只保留 SendMessage 内联 pushNotificationConfig
 * 的异步接受路径,不再暴露 config CRUD 独立 method。
 *
 * <p>L2 §2.3.1 错误码表进一步落到实现契约:显式排除的 method 应返 JSON-RPC
 * {@code -32601 Method Not Found},不允许静默 200、也不允许返 {@code -32603}(internal error)。
 *
 * <p><b>与旧用例的方向反转</b>:旧版 {@code PushConfigCrudTest} 走"5 步 CRUD 全链路成功"断言
 * (配 {@code capabilities.pushNotifications} 前置探针),那是 2026-07-15 版本 FEAT-001 的形态。
 * 本次(2026-08-04)按新 spec 完全反过来 —— 5 个 method 都应被拒。
 *
 * <p><b>用底层 HTTP + JSON-RPC</b>:A2A SDK 1.0.0.Final client 侧未必封装了这 5 个 method,
 * 且断言维度只需读 raw JSON-RPC {@code error.code} 判断 shape,底层 HTTP 更贴合契约层。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.push-config-crud: PushNotificationConfig CRUD 应显式排除返 -32601")
class PushConfigCrudTest extends BaseManagedStackTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final int JSON_RPC_METHOD_NOT_FOUND = -32601;
    private static final int JSON_RPC_INTERNAL_ERROR = -32603;

    private static final List<String> EXCLUDED_METHODS = List.of(
            "SetTaskPushNotificationConfig",
            "GetTaskPushNotificationConfig",
            "ListTaskPushNotificationConfig",
            "UpdateTaskPushNotificationConfig",
            "DeleteTaskPushNotificationConfig");

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // SUT jar 0.1.0 声明了 remote-agents[search-agent, verify-agent]，startup 会校验二者 URL 非空；
        // 本用例不打真实 sub-agent 链路，占位 URL 让 Spring bind 通过即可。
        return SutStack.builder(config)
                .agent(DEEP_RESEARCH, a -> a
                        .env("SEARCH_AGENT_URL", "http://127.0.0.1:1")
                        .env("VERIFY_AGENT_URL", "http://127.0.0.1:1"));
    }

    @Test
    @DisplayName("FEAT-001.push-config-crud: 5 个 push config CRUD method 都应返 -32601")
    void allPushConfigCrudMethodsReturnMethodNotFound() throws Exception {
        for (String method : EXCLUDED_METHODS) {
            JsonNode resp = callDummy(method);

            assertThat(resp.has("error"))
                    .as("FEAT-001.push-config-crud: %s 应返 JSON-RPC error(spec 显式排除)\nresp=%s", method, resp)
                    .isTrue();

            int code = resp.path("error").path("code").asInt();
            assertThat(code)
                    .as("FEAT-001.push-config-crud: %s 应返 -32601 Method Not Found(非 -32603 internal)\n"
                                    + "spec 定位:version-scope §2 显式排除 + L2 §2.3.1 error code 表\nresp=%s",
                            method, resp)
                    .isNotEqualTo(JSON_RPC_INTERNAL_ERROR)
                    .isEqualTo(JSON_RPC_METHOD_NOT_FOUND);
        }
    }

    /**
     * 发一个 params 结构基本合规的 dummy request —— 目的只是让 dispatcher 判 method,
     * 不希望被 params-shape 层提前拦为 -32602。所以 params 里塞一个 taskId 占位。
     */
    private JsonNode callDummy(String method) throws Exception {
        String body = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"probe-%s\",\"method\":\"%s\","
                        + "\"params\":{\"id\":\"%s\"}}",
                UUID.randomUUID().toString().substring(0, 8),
                method,
                UUID.randomUUID());
        HttpRequest request = HttpRequest.newBuilder(URI.create(stack.baseUrl(DEEP_RESEARCH) + "/a2a"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("FEAT-001.push-config-crud: HTTP status 应为 200(JSON-RPC error 也走 200 body)\n"
                                + "method=%s body=%s",
                        method, response.body())
                .isEqualTo(200);
        return mapper.readTree(response.body());
    }
}

package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.AgentCard;
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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-001.push-config-crud — Push Notification config CRUD 契约.
 *
 * <p>FEAT-001 §2「Push Notification 配置 CRUD」+ §3。串接四方法：
 * {@code SetTaskPushNotificationConfig} → {@code GetTaskPushNotificationConfig}
 * → {@code ListTaskPushNotificationConfig} → {@code DeleteTaskPushNotificationConfig}
 * → 再 Get（应 not-found）。
 *
 * <p><b>capabilities 前置探针</b>：deep-research Agent Card 若声明
 * {@code capabilities.pushNotifications == false}，本用例走 assumeTrue 跳过（视作能力未激活，
 * 与评审关联 §3 一致）。若声明 true 但 CRUD 全链路失败，视为"声明夸大能力"，判 FAIL。
 *
 * <p><b>URL 说明</b>：SUT 只做 config 存储契约验证，不实际推送 —— 这里用一个 SIT 侧
 * placeholder URL（{@code http://sit-placeholder.example/webhook}），SUT 不应尝试连接。
 *
 * <p><b>用底层 HTTP + JSON-RPC</b>：A2A SDK 1.0.0.Final 在 client 侧对 push config CRUD 的封装
 * 不像 {@code sendMessage} 那么直接可用，且 A2A SDK 1.0.0.Final 的 client 侧 CRUD 需要 SDK
 * 反序列化目标类型齐全 —— 走底层 HTTP 直发 JSON-RPC，本用例的断言维度只需拿到 raw JSON-RPC
 * response 判断 result/error shape 即可，够用且不受 SDK 版本升级影响。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.push-config-crud: PushNotificationConfig Set/Get/List/Delete 契约")
class PushConfigCrudTest extends BaseManagedStackTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final String PLACEHOLDER_WEBHOOK_URL = "http://sit-placeholder.example/webhook";
    private static final int JSON_RPC_METHOD_NOT_FOUND = -32601;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config).agent(DEEP_RESEARCH);
    }

    @Test
    @DisplayName("FEAT-001.push-config-crud: Set/Get/List/Delete 全链路（capabilities.pushNotifications 前置探针）")
    void pushConfigCrudRoundTrip() throws Exception {
        A2aServiceClient a2a = client(DEEP_RESEARCH);
        AgentCard card = a2a.getAgentCard();

        assumeTrue(card.capabilities() != null && card.capabilities().pushNotifications(),
                "FEAT-001.push-config-crud: capabilities.pushNotifications=false → 能力未激活，跳过（INCONCLUSIVE）");

        // 用一个随机 taskId —— SUT 应该允许对任意 taskId 挂 push config（config 是 sender-side 意向声明，
        // 不需要 task 已存在）。即便 SUT 要求 task 存在，返回的 not-found 类错误也应显式，而不是 5xx。
        String taskId = UUID.randomUUID().toString();
        String configToken = "sit-token-" + UUID.randomUUID().toString().substring(0, 8);

        // 1) Set
        String setBody = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"set-%s\",\"method\":\"SetTaskPushNotificationConfig\","
                        + "\"params\":{\"taskId\":\"%s\",\"pushNotificationConfig\":"
                        + "{\"url\":\"%s\",\"token\":\"%s\"}}}",
                UUID.randomUUID().toString().substring(0, 8), taskId, PLACEHOLDER_WEBHOOK_URL, configToken);
        JsonNode setResp = call(setBody);
        // Set 若返 -32601 说明 method 未激活（capabilities 撒谎），判 FAIL
        assertNotMethodNotFound(setResp,
                "SetTaskPushNotificationConfig 返 -32601 但 capabilities.pushNotifications=true —— 声明夸大能力");
        assertThat(setResp.has("error"))
                .as("FEAT-001.push-config-crud: SetTaskPushNotificationConfig 不应返 error\nresp=%s", setResp)
                .isFalse();
        JsonNode setResult = setResp.path("result");
        assertThat(setResult.isMissingNode() || setResult.isNull())
                .as("FEAT-001.push-config-crud: Set 应有 result 节点\nresp=%s", setResp)
                .isFalse();
        // configId 由 SUT 分配 —— 有些实现直接回 pushNotificationConfig.id / 有些回顶层 id，取到即可
        String configId = extractConfigId(setResult);
        assertThat(configId)
                .as("FEAT-001.push-config-crud: Set 结果应能抽取 configId\nresult=%s", setResult)
                .isNotBlank();

        // 2) Get 回显同一 url / token
        String getBody = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"get-%s\",\"method\":\"GetTaskPushNotificationConfig\","
                        + "\"params\":{\"id\":\"%s\",\"pushNotificationConfigId\":\"%s\"}}",
                UUID.randomUUID().toString().substring(0, 8), taskId, configId);
        JsonNode getResp = call(getBody);
        assertNotMethodNotFound(getResp,
                "GetTaskPushNotificationConfig 返 -32601 但 capabilities.pushNotifications=true —— CRUD 不齐");
        assertThat(getResp.has("error"))
                .as("FEAT-001.push-config-crud: Get 不应返 error\nresp=%s", getResp)
                .isFalse();
        String getUrl = findStringValue(getResp.path("result"), "url");
        assertThat(getUrl)
                .as("FEAT-001.push-config-crud: Get 应回显 url=%s\nresp=%s", PLACEHOLDER_WEBHOOK_URL, getResp)
                .isEqualTo(PLACEHOLDER_WEBHOOK_URL);

        // 3) List 包含 configId
        String listBody = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"list-%s\",\"method\":\"ListTaskPushNotificationConfig\","
                        + "\"params\":{\"id\":\"%s\"}}",
                UUID.randomUUID().toString().substring(0, 8), taskId);
        JsonNode listResp = call(listBody);
        assertNotMethodNotFound(listResp,
                "ListTaskPushNotificationConfig 返 -32601 但 capabilities.pushNotifications=true —— CRUD 不齐");
        assertThat(listResp.has("error"))
                .as("FEAT-001.push-config-crud: List 不应返 error\nresp=%s", listResp)
                .isFalse();
        assertThat(listResp.path("result").toString())
                .as("FEAT-001.push-config-crud: List 结果应含刚 Set 的 configId=%s\nresp=%s", configId, listResp)
                .contains(configId);

        // 4) Delete
        String deleteBody = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"del-%s\",\"method\":\"DeleteTaskPushNotificationConfig\","
                        + "\"params\":{\"id\":\"%s\",\"pushNotificationConfigId\":\"%s\"}}",
                UUID.randomUUID().toString().substring(0, 8), taskId, configId);
        JsonNode deleteResp = call(deleteBody);
        assertNotMethodNotFound(deleteResp,
                "DeleteTaskPushNotificationConfig 返 -32601 但 capabilities.pushNotifications=true —— CRUD 不齐");
        assertThat(deleteResp.has("error"))
                .as("FEAT-001.push-config-crud: Delete 不应返 error\nresp=%s", deleteResp)
                .isFalse();

        // 5) 再 Get —— 应 not-found（error 存在）
        JsonNode reGetResp = call(getBody);
        assertThat(reGetResp.has("error"))
                .as("FEAT-001.push-config-crud: Delete 后再 Get 应返 error（not-found 类）\nresp=%s", reGetResp)
                .isTrue();
    }

    private JsonNode call(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(stack.baseUrl(DEEP_RESEARCH) + "/a2a"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("FEAT-001.push-config-crud: HTTP status 应为 200（JSON-RPC error 也走 200 body）\nbody=%s",
                        response.body())
                .isEqualTo(200);
        return mapper.readTree(response.body());
    }

    private static void assertNotMethodNotFound(JsonNode resp, String failMessage) {
        if (resp.has("error")
                && resp.path("error").path("code").asInt() == JSON_RPC_METHOD_NOT_FOUND) {
            throw new AssertionError(failMessage + "\nresp=" + resp);
        }
    }

    /**
     * 从 Set 结果里抽 configId。可能位置：
     *   result.pushNotificationConfig.id
     *   result.id
     *   result.pushNotificationConfigId
     */
    private static String extractConfigId(JsonNode result) {
        if (result.hasNonNull("pushNotificationConfig")) {
            String id = result.path("pushNotificationConfig").path("id").asText(null);
            if (id != null && !id.isBlank()) return id;
        }
        String top = result.path("id").asText(null);
        if (top != null && !top.isBlank()) return top;
        return result.path("pushNotificationConfigId").asText(null);
    }

    /**
     * 递归找第一个匹配 key 的字符串值 —— A2A 响应结构可能是 {result:{pushNotificationConfig:{url}}} 或
     * {result:{url}} 或 {result:{config:{url}}}，一次探测多种形态。
     */
    private static String findStringValue(JsonNode node, String key) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.hasNonNull(key) && node.get(key).isTextual()) {
            return node.get(key).asText();
        }
        for (JsonNode child : node) {
            String v = findStringValue(child, key);
            if (v != null) return v;
        }
        return null;
    }
}
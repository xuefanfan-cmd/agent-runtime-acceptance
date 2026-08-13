package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Disabled;
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
 * FEAT-001.inline-push-config-untrusted-host — 内联 pushNotificationConfig 若指向未受信任主机,
 * SUT 应在<b>入口层拒绝</b>,不落 config、不触发任何回调.
 *
 * <p><b>⚠️ 本用例已 @Disabled —— 项目实际未采纳此策略</b>(2026-08-06 项目组明示):
 * <ul>
 *   <li><b>接收方 agent(处理 SendMessage 的 SUT)</b>:不做非信任 URL 的入口拒绝,即使是
 *     {@code sit-untrusted.example} 也照单全收;</li>
 *   <li><b>调用方 agent(回调实际目标)</b>:回调进来时校验 token,token 是 pushNotificationConfig
 *     提交时携带的共享 secret;</li>
 *   <li>因此 spec §2 "未列入 trusted hosts 的 callbackUrl 不得被接受" 是 spec 文档描述,
 *     项目防御设计放在<b>callback 路径 auth</b>,不放在 SendMessage 入口的 URL 白名单。</li>
 * </ul>
 *
 * <p><b>真实的 auth 覆盖路径</b>:
 * 参见 {@code PushNotificationCallbackReceiverTest#unauthorizedCallbackReturns401or403} ——
 * 覆盖的是"错 auth token → 401/403",符合项目实际策略。
 *
 * <p><b>保留本文件的原因</b>:文档留痕,标明 spec §2 描述 vs 项目实际差异,避免未来重复写入。
 *
 * <p>以下原设计保留供参考(项目实际未落地):
 * <ul>
 *   <li>version-scope FEAT-001 §2 「callback 安全边界」MUST:未列入 trusted hosts 的 callbackUrl
 *     不得被接受;</li>
 *   <li>L2 §2.3.1 错误码归位:trust-policy 违规应走 {@code -32602 invalid params}
 *     (params 内 callbackUrl 违约)或实现层 trust-policy 专属错误。</li>
 * </ul>
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.inline-push-config-untrusted-host: 未受信任 callbackUrl 应入口拒绝")
@Disabled("项目实际未采纳 spec §2 trust-hosts 入口 gate —— 防御在 callback 路径 auth(token 校验),"
        + "而非 SendMessage 入口 URL 白名单。参见 PushNotificationCallbackReceiverTest。"
        + "2026-08-06 项目组明示。")
class InlinePushConfigUntrustedHostTest extends BaseManagedStackTest {

    /**
     * <b>为什么打 search 而不是 deep-research</b>:deep-research jar 0.1.0 的 SendMessage 参数 schema
     * 对未知 field 早期拒绝(spec §2 inline pushNotificationConfig 未实现),测不到 trust-policy 层;
     * search agent 是同一构建家族里最小的 A2A 服务实体 —— spec §2 对 trust-policy 是<b>runtime 级</b>
     * 强 MUST,不特化 deep-research。若 search 也未落地,即 SUT 家族尚未实现 trust-policy 入口 gate。
     */
    private static final String SEARCH = "search";
    private static final String UNTRUSTED_URL = "http://sit-untrusted.example/webhook";
    private static final int JSON_RPC_INVALID_PARAMS = -32602;

    /**
     * 允许的 reject error code 集合:
     * - {@code -32602} invalid params(首选,L2 §2.3.1 归位);
     * - {@code -32001~-32099} JSON-RPC 2.0 保留给实现层 application error,SUT 可用作 trust-policy 专属码。
     */
    private static final List<Integer> ACCEPTABLE_REJECT_CODES = List.of(
            JSON_RPC_INVALID_PARAMS,
            -32001, -32002, -32003, -32004, -32005,
            -32010, -32020, -32050, -32099);

    /** trust-policy 语义关键词 —— error message 至少命中一个,证明拒绝原因可诊断。 */
    private static final List<String> TRUST_POLICY_KEYWORDS = List.of(
            "trust", "host", "whitelist", "allowlist", "policy",
            "callback", "url", "notification", "untrusted",
            "信任", "白名单", "受信", "回调");

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // search agent 无 remote-agents 依赖,直接起;显式打开 push-notifications 让 capabilities 声明"支持",
        // 从而覆盖真实的 inline pushNotificationConfig 处理路径(否则 SUT 可能因 capability=false 直接短路)。
        //
        // search agent jar 0.1.0 的 application.yml 里 openjiuwen.demo.search-agent.api-key
        // 未绑 ${LLM_API_KEY:} env 占位(deep-research 绑了) —— SUT jar 侧遗漏,需由 test 侧强注。
        // @ConfigurationProperties(prefix="openjiuwen.demo.search-agent") + Java 字段 apiKey
        // → 走 relaxed binding,property key 为 openjiuwen.demo.search-agent.api-key。
        // 值取自 ~/.llmrc 的 LLM_API_KEY env,不硬编码到源码。
        String llmApiKey = System.getenv("LLM_API_KEY");
        return SutStack.builder(config)
                .agent(SEARCH, a -> a
                        .env("SEARCH_AGENT_PUSH_NOTIFICATIONS", "true")
                        .property("openjiuwen.demo.search-agent.api-key", llmApiKey));
    }

    @Test
    @DisplayName("FEAT-001.inline-push-config-untrusted-host: 未受信任 callbackUrl → error,不接受")
    void inlinePushConfigWithUntrustedHostIsRejected() throws Exception {
        String contextId = "ctx-untrusted-" + UUID.randomUUID().toString().substring(0, 8);
        String messageId = UUID.randomUUID().toString();
        String configId = "sit-cfg-" + UUID.randomUUID().toString().substring(0, 8);
        String configToken = "sit-token-" + UUID.randomUUID().toString().substring(0, 8);

        // A2A SDK 1.0.0.Final schema: MessageSendParams.configuration.taskPushNotificationConfig
        // 是嵌套两层的正确路径,url/token 扁平在 TaskPushNotificationConfig 里。
        // SDK Builder 里 id + url 是 @NotNull(Assert.checkNotNullParam),缺 id 会在 build 时
        // 抛 IllegalArgumentException → parser catch 后统一转 -32602 Invalid parameters,
        // 无法进入 trust-policy 检查。所以 id 必须提供。
        String body = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"untrusted-%s\","
                        + "\"method\":\"SendMessage\",\"params\":{"
                        + "\"message\":{"
                        + "\"role\":\"ROLE_USER\","
                        + "\"messageId\":\"%s\","
                        + "\"contextId\":\"%s\","
                        + "\"parts\":[{\"text\":\"hi\"}]"
                        + "},"
                        + "\"configuration\":{"
                        + "\"taskPushNotificationConfig\":{"
                        + "\"id\":\"%s\","
                        + "\"url\":\"%s\","
                        + "\"token\":\"%s\""
                        + "}}}}",
                UUID.randomUUID().toString().substring(0, 8),
                messageId,
                contextId,
                configId,
                UNTRUSTED_URL,
                configToken);

        HttpResponse<String> response = post("/a2a", body);

        assertThat(response.statusCode())
                .as("FEAT-001.inline-push-config-untrusted-host: HTTP status 应为 200\nbody=%s", response.body())
                .isEqualTo(200);

        JsonNode node = mapper.readTree(response.body());

        // A) 必须有 error,不能静默接受
        assertThat(node.has("error"))
                .as("FEAT-001.inline-push-config-untrusted-host: 应返 error(不允许静默接受未信任 URL)\n"
                                + "body=%s", response.body())
                .isTrue();

        // B) error.code 属于允许集合
        int code = node.path("error").path("code").asInt();
        assertThat(ACCEPTABLE_REJECT_CODES)
                .as("FEAT-001.inline-push-config-untrusted-host: error.code 应为 -32602 或实现层 "
                                + "trust-policy 专属码(-32001~-32099),实测 %d\nbody=%s",
                        code, response.body())
                .contains(code);

        // C) error.message + error.data 应含 trust-policy 语义关键词
        String message = node.path("error").path("message").asText("");
        String data = node.path("error").path("data").toString();
        String combined = (message + " " + data).toLowerCase();
        boolean hasKeyword = TRUST_POLICY_KEYWORDS.stream()
                .map(String::toLowerCase)
                .anyMatch(combined::contains);
        assertThat(hasKeyword)
                .as("FEAT-001.inline-push-config-untrusted-host: error.message/data 应含 trust-policy 语义"
                                + "(候选:%s),实测 message='%s' data='%s'",
                        TRUST_POLICY_KEYWORDS, message, data)
                .isTrue();
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(stack.baseUrl(SEARCH) + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}

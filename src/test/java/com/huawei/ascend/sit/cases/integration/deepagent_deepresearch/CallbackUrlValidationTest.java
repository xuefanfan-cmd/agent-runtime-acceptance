package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-001.callback-url-validation — 下游智能体收到 {@code SendMessage.params.configuration
 * .taskPushNotificationConfig.url} 时,必须校验 URL 合法性 —— <b>绝对 URL + scheme ∈ (http, https)
 * + 非空 host</b> —— 不合法即拒绝(JSON-RPC error 或 4xx),不能默认接受进入 TaskStore。
 *
 * <p><b>Spec 依据</b>:
 * <ul>
 *   <li>version-scope FEAT-001 §2.17 「callback 安全边界」+ §5.1.8 exception 表 "callback target
 *     untrusted → 拒绝 SendMessage inline config 或拒绝 delivery,MUST NOT 送到 untrusted URL";</li>
 *   <li>FEAT-001-callback-url-trust-documentation-mismatch.md:<b>无</b> trusted-host allowlist,
 *     实现只做 {@code A2aPushNotificationCallbackUrlPolicy.callbackUri()} 三点校验:
 *     {@code URI absolute + scheme http/https + host non-blank}。</li>
 * </ul>
 *
 * <p><b>断言</b>:4 个非法 URL 变体应被拒绝 —— 拒绝形态可以是 JSON-RPC error(-32602 invalid-params
 * / -32600 invalid-request)或 4xx HTTP,不允许 200 + 空 error 的静默接受。
 *
 * <p><b>为什么用参数化而不是拆多个 @Test</b>:4 个 case 断言相同,只是 URL 变体不同;
 * {@code @CsvSource} 让每个变体独立 report,又共享 @BeforeAll 起 stack 一次(启 SUT jar 昂贵)。
 *
 * <p><b>URL 变体覆盖</b>:
 * <table border="1">
 *   <tr><th>Case</th><th>URL</th><th>违反的校验点</th></tr>
 *   <tr><td>ftp scheme</td><td>ftp://example.com/callback</td><td>scheme ∉ (http, https)</td></tr>
 *   <tr><td>relative</td><td>/callback</td><td>URI 非 absolute</td></tr>
 *   <tr><td>empty host (http)</td><td>http:///callback</td><td>host 空</td></tr>
 *   <tr><td>malformed</td><td>not a url</td><td>URI 语法非法</td></tr>
 * </table>
 *
 * <p><b>topology</b>:direct 到 search-agent — 校验发生在 SendMessage 入口,不需要 LLM 参与。
 * 因此不打 LLM_API_KEY(即使打了也不会触发 LLM 调用)。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.callback-url-validation: 非法 pushConfig.url 应被下游智能体入口拒绝(§2.17/§5.1.8)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CallbackUrlValidationTest {

    private static final Logger LOG = Logger.getLogger(CallbackUrlValidationTest.class.getName());

    private static final String SEARCH = "search";

    private TestConfig config;
    private SutStack searchStack;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    void startStack() {
        config = TestConfig.load();
        // search-agent startup 强制要求 search-agent.llm.api-key 非空(SearchAgentProperties.requireText);
        // 给一个 placeholder key 让 boot 过,SendMessage 入口校验(§2.17 URL 三点)不触发 LLM,不受影响.
        searchStack = SutStack.builder(config)
                .agent(SEARCH, a -> a.property("openjiuwen.demo.search-agent.api-key",
                        "SIT-PLACEHOLDER-" + java.util.UUID.randomUUID()))
                .start();
        LOG.info("[url-validation] search-agent ready at " + searchStack.baseUrl(SEARCH));
    }

    @AfterAll
    void tearDown() {
        if (searchStack != null) searchStack.close();
    }

    @ParameterizedTest(name = "[{index}] {0}: url={1}")
    @CsvSource(delimiter = '|', value = {
            "ftp-scheme   | ftp://example.com/callback",
            "relative-url | /callback",
            "empty-host   | http:///callback",
            "malformed    | not a url"
    })
    @DisplayName("FEAT-001.callback-url-validation: 非法 pushConfig.url → JSON-RPC error 或 4xx(不允许静默接受)")
    void invalidCallbackUrlShouldBeRejected(String caseLabel, String badUrl) throws Exception {
        String contextId = "ctx-url-val-" + UUID.randomUUID().toString().substring(0, 8);
        String messageId = UUID.randomUUID().toString();
        String configId = "sit-cfg-" + UUID.randomUUID().toString().substring(0, 8);
        String rpcId = "url-val-" + caseLabel + "-" + UUID.randomUUID().toString().substring(0, 6);

        // 手工拼 JSON — badUrl 里可能有非法字符(空格),JSON string 需转义,ObjectMapper writeValueAsString
        // 会自动处理。为清晰起见,直接用 mapper 构造 payload 拆开逐层组装。
        ObjectNode pushConfig = mapper.createObjectNode()
                .put("id", configId)
                .put("url", badUrl)
                .put("token", "sit-token-" + UUID.randomUUID().toString().substring(0, 8));

        ObjectNode configuration = mapper.createObjectNode();
        configuration.set("taskPushNotificationConfig", pushConfig);
        configuration.put("returnImmediately", true);

        ArrayNode parts = mapper.createArrayNode();
        parts.add(mapper.createObjectNode().put("text", "test"));

        ObjectNode message = mapper.createObjectNode()
                .put("role", "ROLE_USER")
                .put("messageId", messageId)
                .put("contextId", contextId);
        message.set("parts", parts);

        ObjectNode params = mapper.createObjectNode();
        params.set("message", message);
        params.set("configuration", configuration);

        ObjectNode root = mapper.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", rpcId)
                .put("method", "SendMessage");
        root.set("params", params);

        String body = mapper.writeValueAsString(root);

        HttpResponse<String> response = post(searchStack.baseUrl(SEARCH) + "/a2a", body);
        LOG.info(String.format("[url-validation:%s] status=%d body=%s",
                caseLabel, response.statusCode(), response.body()));

        int status = response.statusCode();
        JsonNode result = mapper.readTree(response.body());
        boolean hasError = result.has("error");
        int errorCode = hasError ? result.path("error").path("code").asInt(0) : 0;

        // 收紧(2026-08-08):原 `status >= 400 || hasError` 会把 5xx(LLM InternalError / 网络故障)
        // 也算作"URL 被拒",与 §2.17 URL 三点校验根本无关 = 假绿。真正的合法拒绝形态是二选一:
        //   (a) JSON-RPC error node(code ∈ {-32602 invalid-params, -32600 invalid-request,
        //       -32603 internal-error 但仅当 message 明示 URL 违规});或
        //   (b) HTTP 4xx client-error(不含 5xx —— 5xx 属服务器内部 bug 不是 URL 校验反馈)。
        boolean isClientHttpReject = status >= 400 && status < 500;
        boolean isJsonRpcInvalidParams = hasError
                && (errorCode == -32602 || errorCode == -32600);
        boolean isRejected = isJsonRpcInvalidParams || isClientHttpReject;

        assertThat(isRejected)
                .as("[url-validation:%s] 非法 URL '%s' 应被 URL 校验层拒绝(§2.17 三点校验)。"
                                + "合法拒绝 = JSON-RPC error code ∈ {-32602, -32600} 或 HTTP 4xx。"
                                + "5xx 属服务器内部错误,<b>不算</b> URL 校验反馈(收紧于 2026-08-08,避免"
                                + "把 LLM InternalError 误当 URL 拒绝)。\n"
                                + "  实测 status=%d hasError=%s errorCode=%d\n  body=%s",
                        caseLabel, badUrl, status, hasError, errorCode, response.body())
                .isTrue();
    }

    private HttpResponse<String> post(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}

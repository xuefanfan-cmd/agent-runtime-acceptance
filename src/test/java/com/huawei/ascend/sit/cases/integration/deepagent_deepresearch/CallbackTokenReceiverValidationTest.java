package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-001.callback-receiver-external-rejection — 上游 receiver 端点对<b>无对应 binding</b>的外部
 * callback POST 必须拒绝(4xx 或 JSON-RPC error),不能静默 200 接受进 TaskStore。
 *
 * <p><b>诚实定位(2026-08-08 修订)</b>:本用例<b>不能证明 token 校验层的独立行为</b>,原因如下:
 * <ol>
 *   <li>Receiver 校验分层(2026-08-08 probe 观测):(1) JSON-RPC shape → (2) nid header/body
 *       consistency → (3) binding lookup → (4) auth token。任一层拒即 return;</li>
 *   <li>Test 外部直接 POST /callback,SUT 侧<b>没有对应 binding</b>(binding 是 caller→deep-research
 *       →sub-agent 的 outbound cascade 走完才注册),layer (3) 就会 404 "binding not found"</b>拒绝,
 *       auth 层根本走不到;</li>
 *   <li>因此断言"非 200/202"能通过,但只证明"结构性拒陌生 callback",<b>不证明 token 校验独立生效</b>
 *       —— 属于弱断言。真正的 token 独立断言在 {@link CascadeCallbackReceiverAuthTest} 里,通过
 *       MockSearchAgentServer 走完 outbound cascade 让 binding 建立后,再 fire 带错 token 的 callback
 *       观测 401/403。当前 {@link CascadeCallbackReceiverAuthTest} 被 [[deep-research-outbound-push-not-wired]]
 *       (BUG-009) 阻塞。</li>
 * </ol>
 *
 * <p><b>本用例的实际价值</b>:receiver 端点激活 smoke —— 证明
 * {@code /a2a/push-notifications/callback} 端点确实<b>存在且做了结构性校验</b>(不是敞开门 200
 * 接受任意 POST)。这是<b>必要不充分</b>的 receiver 侧安全性证据。
 *
 * <p><b>激活前置</b>:runtime 层具备 callback receiver 能力,但需<b>同时</b>设置以下 env 才能激活
 * deep-research 的 receiver 端点:
 * <ul>
 *   <li>{@code DEEP_RESEARCH_PUSH_NOTIFICATIONS=true} —— 激活 push notification bean 装配;</li>
 *   <li>{@code DEEP_RESEARCH_PUBLIC_URL=http://127.0.0.1:<port>} —— receiver 需知晓自己 base URL
 *       用于构造 callback identity;必须与随机端口一致,通过 sut-agents-deep-research-port
 *       系统属性把端口"预锁"。</li>
 * </ul>
 * 不激活 → 端点返 501 "push notification callback is not enabled" → assumeTrue skip。
 *
 * <p><b>参数化 case 覆盖</b>(所有 case 当前预期都命中 404 binding-not-found,不是 auth 层):
 * <table border="1">
 *   <tr><th>Case</th><th>Auth Header</th><th>预期拒绝层</th></tr>
 *   <tr><td>no-auth</td><td>(不设)</td><td>binding lookup(404)</td></tr>
 *   <tr><td>empty-bearer</td><td>Bearer (空 token)</td><td>binding lookup(404)</td></tr>
 *   <tr><td>random-bearer</td><td>Bearer sit-invalid-uuid</td><td>binding lookup(404)</td></tr>
 * </table>
 *
 * <p><b>BUG-009 修复后</b>:CascadeCallbackReceiverAuthTest 就能真正独立测 token 层。届时本用例
 * 可评估是否合并或删除(结构性 smoke 价值不大)。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.callback-receiver-external-rejection: receiver 端点激活 smoke + 对无 binding 的外部 callback 结构性拒绝")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CallbackTokenReceiverValidationTest {

    private static final Logger LOG = Logger.getLogger(CallbackTokenReceiverValidationTest.class.getName());

    private static final String DEEP_RESEARCH = "deep-research";
    private static final String CALLBACK_PATH = "/a2a/push-notifications/callback";
    private static final String DEEP_RESEARCH_PORT_SYSPROP = "sut-agents-deep-research-port";

    private TestConfig config;
    private SutStack stack;
    private int deepPort;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    void startStack() throws Exception {
        config = TestConfig.load();

        // 参照 PushNotificationCascadeProbeTest:pre-reserve 端口,通过 sysprop 让 SutStack 用它,
        // 同时 PUBLIC_URL 与端口一致,receiver 才能激活。
        try (ServerSocket ss = new ServerSocket(0)) {
            deepPort = ss.getLocalPort();
        }
        String publicUrl = "http://127.0.0.1:" + deepPort;
        System.setProperty(DEEP_RESEARCH_PORT_SYSPROP, String.valueOf(deepPort));

        stack = SutStack.builder(config)
                .agent(DEEP_RESEARCH, a -> a
                        .env("SEARCH_AGENT_URL", "http://127.0.0.1:1")
                        .env("VERIFY_AGENT_URL", "http://127.0.0.1:1")
                        .env("DEEP_RESEARCH_PUSH_NOTIFICATIONS", "true")
                        .env("DEEP_RESEARCH_PUBLIC_URL", publicUrl))
                .start();

        LOG.info("[cb-token-recv] deep-research ready at " + stack.baseUrl(DEEP_RESEARCH)
                + " (push-notifications activated, PUBLIC_URL=" + publicUrl + ")");
    }

    @AfterAll
    void tearDown() {
        if (stack != null) stack.close();
        System.clearProperty(DEEP_RESEARCH_PORT_SYSPROP);
    }

    @ParameterizedTest(name = "[{index}] {0}: auth={1}")
    @CsvSource(delimiter = '|', value = {
            "no-auth       | ",
            "empty-bearer  | Bearer ",
            "random-bearer | Bearer sit-invalid-token-x"
    })
    @DisplayName("FEAT-001.callback-receiver-smoke: 无 binding 的外部 callback POST 应被结构性拒绝(不是敞开门 200)")
    void externalCallbackWithoutBindingShouldBeRejected(String caseLabel, String authHeaderRaw) throws Exception {
        String authHeader = authHeaderRaw == null ? "" : authHeaderRaw.trim();

        String notificationId = UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();
        String body = validCallbackBody(notificationId, taskId);

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(stack.baseUrl(DEEP_RESEARCH) + CALLBACK_PATH))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("X-A2A-Notification-Id", notificationId);
        if (!authHeader.isEmpty()) {
            builder.header("Authorization", authHeader);
        }
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        int status = response.statusCode();
        JsonNode result = safeReadTree(response.body());
        boolean hasError = result.has("error");
        boolean silentAccept = (status == 200 || status == 202) && !hasError;

        LOG.info(String.format("[cb-recv-smoke:%s] status=%d hasError=%s body=%s",
                caseLabel, status, hasError, response.body()));

        // 501 说明 receiver 激活失败(env 未生效或 SUT 版本回退) —— skip
        if (status == 501) {
            LOG.warning(String.format(
                    "[cb-recv-smoke:%s] receiver 端点返 501,可能激活失败(DEEP_RESEARCH_PUSH_NOTIFICATIONS "
                            + "env 未生效)。跳过 smoke 断言。body=%s",
                    caseLabel, response.body()));
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "receiver endpoint not activated (501)");
        }

        assertThat(silentAccept)
                .as("[cb-recv-smoke:%s] receiver 端点结构性拒绝 smoke:外部 POST 没走过 caller→sub-agent "
                                + "cascade,SUT 侧<b>没有对应 binding</b>,layer 3 (binding lookup) 应先 404 拒。"
                                + "本用例<b>不能</b>单独证明 token 校验层生效(auth 层在 binding 层之后,当前用例走不到),"
                                + "真正的 token 独立断言见 CascadeCallbackReceiverAuthTest(被 [[deep-research-outbound-push-not-wired]]"
                                + " BUG-009 阻塞)。\n"
                                + "  auth header = '%s'\n"
                                + "  实测 status=%d hasError=%s → 疑似静默 200 接受 = 严重 spec-gap(敞开门)。\n"
                                + "  body=%s",
                        caseLabel, authHeader, status, hasError, response.body())
                .isFalse();
    }

    private JsonNode safeReadTree(String s) {
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    /**
     * 构造 shape 合法的 callback body。SUT receiver 校验顺序(2026-08-08 probe 观测):
     * <ol>
     *   <li>JSON-RPC envelope 必须有 {@code result} 节点(否则 400 "must contain a JSON-RPC result");</li>
     *   <li>{@code notificationId} header 与 body 一致(L2 §2.7 幂等键契约,否则 400 mismatch);</li>
     *   <li>Authorization header 校验(token-on-callback,否则 401/403)—— 本用例断言点。</li>
     * </ol>
     * 前两层必须先满足,才能真正跑到 token 校验层。这里构造 JSON-RPC 包 + notificationId 一致,
     * 只留 auth 变量。
     */
    private static String validCallbackBody(String notificationId, String taskId) {
        String contextId = "ctx-cb-tok-" + UUID.randomUUID().toString().substring(0, 8);
        return String.format(
                "{\"jsonrpc\":\"2.0\",\"notificationId\":\"%s\",\"result\":{"
                        + "\"task\":{"
                        + "\"id\":\"%s\","
                        + "\"contextId\":\"%s\","
                        + "\"status\":{\"state\":\"TASK_STATE_WORKING\"}"
                        + "}}}",
                notificationId, taskId, contextId);
    }
}

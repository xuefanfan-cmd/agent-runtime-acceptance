package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.mock.MockCallbackReceiver;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-001.failed-sender-fire — 契约:agent 因 handler/runtime 异常进入 FAILED 时,sender 必须
 * fire callback 把 FAILED 状态通知调用方(spec version-scope §5.1.8 「handler/runtime exception」
 * + §5.1.7 「FAILED 表面 MUST 携带程序化可读错误信息」)。
 *
 * <p><b>2026-08-08 实测结论(本用例当前 red)</b>:
 * 用 invalid api-key 触发 LLM 401,SUT 端实际路径:
 * <pre>
 *   OpenAiCompatibleModelClient.ensureSuccess:390  throw RuntimeException("HTTP 401: ...")
 *     → RunnerImpl.wrapRunnerRuntime:940            wrap 成 RuntimeException("Failed to invoke agent")
 *     → escape past A2AAgentExecutor.execute:108    (SUT catch 只覆盖 IAE/ISE/NPE)
 *     → SDK DefaultRequestHandler:935                记录 "Agent execution threw unexpected RuntimeException"
 *     → SDK 包成 InternalError(非 StreamingEventKind)
 *     → MainEventBusProcessor 跳过 sender 分支
 *     → callback 永远不 fire
 * </pre>
 * 即 <b>[[push-notification-sender-not-activated]]</b> 提到的 FEAT-001-failed-task-push-notification-
 * may-be-lost.md §S2 <b>buggy 路径</b>。invalid api-key 是 §S2 的 <b>可靠触发器</b>(不是 §S3)。
 *
 * <p><b>本用例价值</b>:red-first regression 探针 —— 断言 spec §5.1.7 承诺的"FAILED → sender fire"
 * 契约。当前 SUT §S2 bug 存在 → red。SUT 修复(把 generic RuntimeException 也捕获并转 FAILED
 * StreamingEventKind)后自动绿。<b>不要</b>因为"当前红"把断言 relax。
 *
 * <p><b>Green 化路径</b>(SUT 侧修 §S2 后自然绿):
 * <ol>
 *   <li>{@code A2AAgentExecutor.execute} 的 catch 从 {@code IAE|ISE|NPE} 扩到 {@code Throwable};</li>
 *   <li>catch 内统一走 {@code failAndDrain()} → {@code emitter.fail(Message)} → 产
 *       {@code TaskStatusUpdateEvent(FAILED)}({@code StreamingEventKind});</li>
 *   <li>{@code MainEventBusProcessor} 命中 sender 分支 fire callback。</li>
 * </ol>
 *
 * <p><b>拓扑</b>:
 * <pre>
 *   caller (this test) --SendMessage(inline pushConfig.url = MockCallbackReceiver)-->
 *     search-agent  --[LLM 401 → RuntimeException]-->
 *       ✗ sender skip (§S2 bug)   MockCallbackReceiver: 0 callback
 * </pre>
 *
 * <p><b>MockCallbackReceiver 作为 deep-research 站位</b>:用户需求原文"让 search agent 回调
 * deep research agent",架构含义 = search-agent 的 outbound sender fire callback 到调用方
 * (=deep-research)的 {@code /a2a/push-notifications/callback}。用 {@link MockCallbackReceiver}
 * 站位 deep-research callback endpoint,HTTP 语义与真实链路等价(sender 只按 pushConfig.url 发,
 * 不判断受体是谁),避开 [[FEAT-004 auto-resume gap]] 的观察盲点。
 *
 * <p><b>断言</b>:
 * <ol>
 *   <li>SendMessage 初返 200(非阻塞骨架 OK);</li>
 *   <li>观察窗口 {@value #FAILED_CALLBACK_WAIT_MS} ms 内 MockCallbackReceiver 收到 ≥1 次 callback;</li>
 *   <li>callback body {@code status.state} 属 FAILED 家族。</li>
 * </ol>
 *
 * <p><b>Tag</b>:{@code manual} —— 依赖真 LLM 端点(以便 401),CI 默认不满足。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Tag("manual")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.failed-sender-fire: FAILED 终态必须触发 sender 回调(§5.1.7 契约;当前红=§S2 gap regression 信号)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FailedSenderFireTest {

    private static final Logger LOG = Logger.getLogger(FailedSenderFireTest.class.getName());

    private static final String SEARCH = "search";

    /**
     * 简短确定性 prompt —— 触发 LLM 调用,让 auth 失败得以抛出。避免复杂 prompt 引入 verify tool
     * 分支的干扰。
     */
    private static final String PROMPT = "帮我搜索 2026 年 7 月全球黄金价格盘中最高价";

    /**
     * 显性 invalid api-key:格式合法(非空)但语义无效,让 SUT 启动能过,LLM 首次调用 401。
     * 用 UUID 后缀,防止极小概率碰撞 valid key。
     */
    private static final String INVALID_API_KEY = "SIT-INVALID-API-KEY-" + UUID.randomUUID();

    /** FAILED sender fire 观测预算 —— LLM 401 通常几秒内返回,给 60s 覆盖 warmup + retry. */
    private static final long FAILED_CALLBACK_WAIT_MS = 60_000L;

    private TestConfig config;
    private SutStack searchStack;
    private MockCallbackReceiver mockReceiver;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    void startStack() throws Exception {
        config = TestConfig.load();

        mockReceiver = MockCallbackReceiver.start();
        LOG.info("[failed-sender-fire] MockCallbackReceiver ready at " + mockReceiver.callbackUrl());

        // 关键:api-key 设 invalid,让 LLM 首次调用抛 auth 异常 —— 触发 §S3 catch 集合.
        searchStack = SutStack.builder(config)
                .agent(SEARCH, a -> a
                        .property("openjiuwen.demo.search-agent.api-key", INVALID_API_KEY))
                .start();
        LOG.info("[failed-sender-fire] search-agent ready at " + searchStack.baseUrl(SEARCH)
                + " (api-key intentionally invalid: " + INVALID_API_KEY + ")");
    }

    @AfterAll
    void tearDown() {
        if (searchStack != null) searchStack.close();
        if (mockReceiver != null) mockReceiver.close();
    }

    @Test
    @DisplayName("FEAT-001.failed-sender-fire: search-agent 因 auth 失败进入 FAILED 时 sender 应 fire callback")
    void failedTaskShouldFireSenderCallback() throws Exception {
        String contextId = "ctx-failed-sender-" + UUID.randomUUID().toString().substring(0, 8);
        String messageId = UUID.randomUUID().toString();
        String configId = "sit-cfg-" + UUID.randomUUID().toString().substring(0, 8);
        String configToken = "sit-token-" + UUID.randomUUID().toString().substring(0, 8);

        String body = buildSendMessage(
                "failed-sender-" + UUID.randomUUID().toString().substring(0, 8),
                messageId, contextId, PROMPT,
                configId, mockReceiver.callbackUrl(), configToken);

        long t0 = System.currentTimeMillis();
        HttpResponse<String> response = post(searchStack.baseUrl(SEARCH) + "/a2a", body);
        long sendElapsed = System.currentTimeMillis() - t0;

        LOG.info(String.format(
                "[failed-sender-fire] SendMessage returned in %d ms | status=%d%n  body=%s",
                sendElapsed, response.statusCode(), response.body()));

        assertThat(response.statusCode())
                .as("[failed-sender-fire] SendMessage HTTP status 应 200(非阻塞骨架)\nbody=%s",
                        response.body())
                .isEqualTo(200);

        JsonNode initial = mapper.readTree(response.body());
        assertThat(initial.has("error"))
                .as("[failed-sender-fire] SendMessage 不应返 JSON-RPC error(承接 SendMessage 骨架应先返 200,"
                                + "失败通过 sender callback 表达)\nbody=%s", response.body())
                .isFalse();

        LOG.info(String.format("[failed-sender-fire] observing %d ms for FAILED callback ...",
                FAILED_CALLBACK_WAIT_MS));
        boolean reached = mockReceiver.awaitAtLeast(1, FAILED_CALLBACK_WAIT_MS);
        int count = mockReceiver.count();
        long totalElapsed = System.currentTimeMillis() - t0;

        LOG.info(String.format(
                "[failed-sender-fire] ===== observation =====%n"
                        + "  reached=%s callbackCount=%d elapsed=%d ms",
                reached, count, totalElapsed));
        dumpCaptured(t0);

        assertThat(reached)
                .as("[failed-sender-fire] spec §5.1.7 承诺 FAILED 表面必须能程序化通知调用方;"
                                + "期望 %d ms 内 MockCallbackReceiver 收到 ≥1 次 callback。实测 %d 次。\n"
                                + "  【2026-08-08 实测确认】invalid api-key 触发路径:LLM 401 → generic "
                                + "RuntimeException → escape past A2AAgentExecutor(catch 只覆盖 IAE/ISE/NPE) "
                                + "→ SDK DefaultRequestHandler 包成 InternalError(非 StreamingEventKind) "
                                + "→ MainEventBusProcessor 跳过 sender → callback lost。\n"
                                + "  归属 FEAT-001-failed-task-push-notification-may-be-lost.md §S2 buggy 路径,"
                                + "本用例红 = 该 gap 存在的 regression 信号,SUT 修 §S2 后自动绿。",
                        FAILED_CALLBACK_WAIT_MS, count)
                .isTrue();

        MockCallbackReceiver.CapturedCallback cb = mockReceiver.captured().get(0);
        JsonNode cbBody = mapper.readTree(cb.body());
        String state = extractState(cbBody);
        LOG.info(String.format("[failed-sender-fire] callback[0].status.state = %s", state));

        assertThat(state)
                .as("[failed-sender-fire] callback body 里 status.state 应属 FAILED 家族"
                                + "(TASK_STATE_FAILED 或含 FAILED 子串);实测 %s\n  body=%s",
                        state, cb.body())
                .isNotNull()
                .containsIgnoringCase("FAILED");
    }

    private static String buildSendMessage(String rpcId, String messageId, String contextId,
                                            String prompt, String configId, String callbackUrl,
                                            String configToken) {
        return String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"%s\","
                        + "\"method\":\"SendMessage\",\"params\":{"
                        + "\"message\":{"
                        + "\"role\":\"ROLE_USER\","
                        + "\"messageId\":\"%s\","
                        + "\"contextId\":\"%s\","
                        + "\"parts\":[{\"text\":\"%s\"}]"
                        + "},"
                        + "\"configuration\":{"
                        + "\"taskPushNotificationConfig\":{"
                        + "\"id\":\"%s\","
                        + "\"url\":\"%s\","
                        + "\"token\":\"%s\""
                        + "},"
                        + "\"returnImmediately\":true"
                        + "}}}",
                rpcId, messageId, contextId, prompt,
                configId, callbackUrl, configToken);
    }

    /** 从 callback body 里稳健提取 status.state:兼容不同 SUT 版本 body shape. */
    private static String extractState(JsonNode body) {
        JsonNode s1 = body.path("status").path("state");
        if (s1.isTextual()) return s1.asText();
        JsonNode s2 = body.path("state");
        if (s2.isTextual()) return s2.asText();
        JsonNode s3 = body.path("task").path("status").path("state");
        if (s3.isTextual()) return s3.asText();
        return null;
    }

    private void dumpCaptured(long t0) {
        for (int i = 0; i < mockReceiver.captured().size(); i++) {
            MockCallbackReceiver.CapturedCallback cb = mockReceiver.captured().get(i);
            LOG.info(String.format(
                    "[failed-sender-fire] callback #%d at t+%d ms | headers=%s%n  body=%s",
                    i, cb.timestampMs() - t0, cb.headers(), cb.body()));
        }
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

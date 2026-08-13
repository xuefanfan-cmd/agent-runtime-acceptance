package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.mock.MockCallbackReceiver;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-001.push-notification-cascade-probe — 级联 push notification 端到端探针.
 *
 * <p><b>链路模型</b>(用户 2026-08-06 明示):
 * <pre>{@code
 *   caller ──SendMessage(pushConfig{url=URL_A, token=T_A})──▶ deep-research
 *       ▲                                                          │
 *       │ callback POST                                             │ SendMessage
 *       │ (URL_A, X-Notification-Id, Authorization: T_A)            │ (pushConfig{url=URL_B, token=T_B})
 *       │                                                          ▼
 *   MockCallbackReceiver                                       search-agent
 *                                                                    │
 *                                       callback POST(URL_B)         │
 *                                       ◀────────────────────────────┘
 *   deep-research
 *   (self /a2a/push-notifications/callback = URL_B)
 * }</pre>
 *
 * <p><b>每一跳独立构造自己的 push config</b>,不透传上游的 URL —— deep-research 收到 caller
 * 的 URL_A 后,自己完成任务时回调 URL_A,同时它调 search-agent 时构造自己的 URL_B(SUT 自己的
 * receiver 端点),不把 URL_A 传给 search-agent。
 *
 * <p><b>探针语义</b>:本用例是<b>行为观测</b>,不做严格 spec 契约断言。核心信号:
 * <ol>
 *   <li>MockCallbackReceiver 是否收到 ≥1 次 callback —— 收到 = 链路通到最外层;</li>
 *   <li>captured callback body/headers 内容 —— 供后续判断 notification-id、token、payload shape;</li>
 *   <li>SendMessage 初返 response(应非阻塞 200 + 非 COMPLETED 骨架);</li>
 * </ol>
 *
 * <p><b>断言宽松度</b>:仅 assumeTrue(capabilities.pushNotifications) 前置 + soft 断言
 * "至少 1 次 callback",详细诊断走 log。这条 test 的价值在于跑一次得到 SUT 真实 behavior,
 * 而不是先假设契约再红定级。跑通后可基于观察改写为严格契约用例(或标 spec-gap)。
 *
 * <p><b>2026-08-07 多轮观测汇总</b>(5 次 run,详见 [[push-notification-sender-not-activated]]):
 * <b>正确的级联模型</b> —— 用户澄清:不是"下游 push 触发上游"链式;每个节点独立判断自己的
 * 任务状态,只有 COMPLETED/FAILED 才触发它自己的 push callback。所以看到 mock receiver 收到
 * callback 的前提是 <b>deep-research 侧任务 COMPLETED</b>;URL_B 那一跳需要 <b>search-agent 侧
 * COMPLETED + search-agent 侧 store 里有 URL_B config</b>。
 *
 * <p><b>观测到的现象</b>:
 * <ol>
 *   <li>SendMessage 初返 200 in ~100-150ms,initial state=TASK_STATE_WORKING(非阻塞骨架 ✓);</li>
 *   <li>tool_call search-agent 一发出,controller 层立即 "Task requires interaction" + eventQueue
 *     closed (INPUT_REQUIRED preserved) —— <b>deep-research 侧任务实际停在 INPUT_REQUIRED,不是
 *     COMPLETED</b>,即使 sub-agent 后来完成。原因:同步 remote invocation 返回 INPUT_REQUIRED
 *     骨架(SDK/framework 语义),deep-research 依此判定自己也 INPUT_REQUIRED;</li>
 *   <li>因 (2),deep-research 侧 URL_A callback 在 5 次 run 全部为 0(mock receiver 从未收到);</li>
 *   <li>search-agent 侧:某些 prompt 会走 COMPLETED(如"黄金"和早期"DeepSeek 定价"），另一些
 *     走 INPUT_REQUIRED(受 LLM 判断影响);</li>
 *   <li>唯有 2026-08-06 21:00 那次 search-agent 触发 {@code HttpPushNotificationSender} 但
 *     delivery {@code Connection refused: getsockopt}(端口错配,现已 fix);后续多次 run 里
 *     search-agent 即使 COMPLETED 也<b>不再触发 sender</b>(store 里可能没 URL_B config)。</li>
 * </ol>
 *
 * <p><b>根因猜测(未二分)</b>:
 * <ul>
 *   <li>URL_A 侧:deep-research 收到 caller 的 pushNotificationConfig 后可能未存入
 *     {@code A2aPushNotificationCallbackStore},但更主要的是任务从没走到 COMPLETED 状态
 *     (总是被 sub-agent INPUT_REQUIRED 打断),所以即使 store 有 config 也不该触发;</li>
 *   <li>URL_B 侧:deep-research 侧有专门的 {@code deepResearchOutboundPushRemoteAgentCaller}
 *     bean(启动 fail-fast log 命名),但当前 remote invocation log 看不到 URL_B 转发的显性
 *     证据。触发是否稳定依赖 deep-research 是否往下游附加 pushNotificationConfig。</li>
 * </ul>
 *
 * <p><b>test 定位</b>:探针目的已达 —— 观察到"当前 SUT 形态下 mock receiver URL_A 从未收到
 * callback,即使 sub-agent 侧曾偶发触发过 sender"。这是<b>本轮探针的核心发现</b>。保留 red
 * 显性化 gap;正式化前建议先与项目组对齐:(a) 是否规划 sender 侧完整落地;(b) URL_A/URL_B
 * 转发路径的稳定契约。
 *
 * <p><b>dual-stack 沿用 {@link InputRequiredFakeCompletedTest} 模式</b>:search-agent 单独起
 * stack,deep-research stack 通过 {@code SEARCH_AGENT_URL} 环境变量寻址,让真实 sub-agent
 * 调用链跑起来。
 *
 * <p><b>为什么用 raw JSON HTTP 发 SendMessage</b>:SDK 1.0.0.Final 的 Message.Builder 未
 * 直接暴露 inline pushNotificationConfig 通路,raw JSON 是最贴合 spec 契约的路径,与
 * {@link InlinePushConfigAsyncAcceptTest} 保持同款。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Tag("manual")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.push-notification-cascade-probe: caller → deep-research → search-agent 反向 callback 链探针")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PushNotificationCascadeProbeTest {

    private static final Logger LOG = Logger.getLogger(PushNotificationCascadeProbeTest.class.getName());

    private static final String DEEP_RESEARCH = "deep-research";
    private static final String SEARCH = "search";

    /**
     * TestConfig 内 key 归一化:{@code .} → {@code _} → {@code -}(见 TestConfig#getString L77)。
     * 所以 YAML key {@code sut.agents.deep-research.port} 对应 System property
     * {@code sut-agents-deep-research-port}。
     */
    private static final String DEEP_RESEARCH_PORT_SYSPROP = "sut-agents-deep-research-port";

    /**
     * 完整 prompt —— 让 deep-research 与 search-agent 两端都 COMPLETED,触发各自的 callback.
     *
     * <p><b>正确的级联模型</b>(2026-08-07 用户澄清):不是"下游 push 触发上游 push"链式,
     * 而是每个节点独立判断自己的任务状态,只有 COMPLETED/FAILED 才触发它自己的 push callback。
     * 因此全链路可观测的前提是<b>两端都 COMPLETED</b>:search-agent COMPLETED → push URL_B
     * (deep-research 侧 endpoint) + deep-research COMPLETED → push URL_A (mock receiver)。
     *
     * <p><b>为什么用"搜黄金"prompt</b>:先前用「请查询 DeepSeek-R1 官方定价」LLM 走 ask_user
     * 分支 → search-agent 或 deep-research 至少一端进 INPUT_REQUIRED → 框架
     * {@code HttpPushNotificationSender.isCallbackState()} 只对 {@code COMPLETED / FAILED} 触发
     * (bytecode 确认)→ callback 不触发。搜黄金 prompt 时间/对象/指标皆具象,LLM 无 clarify
     * 空间,历史 run 已观察到两端 COMPLETED(search 侧 18s,deep 侧 6.7s)。
     */
    private static final String CASCADE_PROMPT =
            "帮我搜索 2026 年 7 月全球黄金价格盘中最高价";

    /** 整链跑完(含 LLM + sub-agent + 反向 callback)预算 —— 240s 与 InputRequired 用例同尺度. */
    private static final long CASCADE_TIMEOUT_MS = 240_000L;

    private TestConfig config;
    private SutStack searchStack;
    private SutStack deepStack;
    private MockCallbackReceiver mockReceiver;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    void startStack() throws Exception {
        config = TestConfig.load();

        // 起 MockCallbackReceiver 扮演最外层调用方 receiver —— 先起,拿 URL 后再启 SUT stack.
        mockReceiver = MockCallbackReceiver.start();
        LOG.info("[cascade-probe] MockCallbackReceiver ready at " + mockReceiver.callbackUrl());

        // search-agent stack:显式开 push-notifications capability,让 sub-agent 端也能接受
        // deep-research 给它的 pushNotificationConfig(URL_B 那一跳的必要条件)。
        String llmApiKey = System.getenv("LLM_API_KEY");
        searchStack = SutStack.builder(config)
                .agent(SEARCH, a -> a
                        .env("SEARCH_AGENT_PUSH_NOTIFICATIONS", "true")
                        .property("openjiuwen.demo.search-agent.api-key", llmApiKey))
                .start();
        String searchBaseUrl = searchStack.baseUrl(SEARCH);
        LOG.info("[cascade-probe] search-agent stack ready at " + searchBaseUrl);

        // deep-research stack:通过 SEARCH_AGENT_URL 指向真实 search-agent。
        // 强制打开 push-notifications capability —— jar 默认 false,不打开的话整条 cascade
        // 不可能触发。deep-research runtime jar 里 DeepResearchOutboundPushConfiguration
        // 强 gate:{@code DEEP_RESEARCH_PUSH_NOTIFICATIONS=true} + 必须同时给
        // {@code DEEP_RESEARCH_PUBLIC_URL}(用于构造它调 sub-agent 时携带的 URL_B)。
        //
        // 由于 SUT bean init 阶段就 fail-fast,PUBLIC_URL 必须在启动前就有,而
        // SutStack 默认 --server.port=0 让 OS 随机分配 —— 我们不知道启动后端口。
        // 走 YAML config 层 {@code sut.agents.deep-research.port}(SutStack line 788 会读取)
        // + System property(TestConfig key 转换 . → _ → -,line 77):
        // {@code -Dsut-agents-deep-research-port=<preReserved>} 生效于当前 test 进程,
        // 不侵入 prod,不改共享 yml,scoped to @BeforeAll/@AfterAll。
        int deepPort;
        try (ServerSocket ss = new ServerSocket(0)) {
            deepPort = ss.getLocalPort();
        }
        String deepPublicUrl = "http://127.0.0.1:" + deepPort;
        System.setProperty(DEEP_RESEARCH_PORT_SYSPROP, String.valueOf(deepPort));
        LOG.info("[cascade-probe] pre-reserved deep-research port=" + deepPort
                + ", public-url=" + deepPublicUrl);

        deepStack = SutStack.builder(config)
                .streaming(false)
                .agent(DEEP_RESEARCH, a -> a
                        .env("SEARCH_AGENT_URL", searchBaseUrl)
                        .env("VERIFY_AGENT_URL", "http://127.0.0.1:1")
                        .env("DEEP_RESEARCH_PUSH_NOTIFICATIONS", "true")
                        .env("DEEP_RESEARCH_PUBLIC_URL", deepPublicUrl))
                .start();
        LOG.info("[cascade-probe] deep-research stack ready at " + deepStack.baseUrl(DEEP_RESEARCH));
    }

    @AfterAll
    void tearDown() {
        if (deepStack != null) deepStack.close();
        if (searchStack != null) searchStack.close();
        if (mockReceiver != null) mockReceiver.close();
        // 清理 test 内注入的 System property,避免污染同 JVM 后续 test.
        System.clearProperty(DEEP_RESEARCH_PORT_SYSPROP);
    }

    @Test
    @DisplayName("FEAT-001.push-cascade-probe: caller inline push config → mock receiver ≥1 callback(观测)")
    void cascadeReversalCallbackReachesOutermostCaller() throws Exception {
        // 前置:两端都要声明 pushNotifications 能力打开,否则整条链不可能通。
        // 显性化两端 capabilities,便于诊断"哪端 off 导致 skip"。
        AgentCard deepCard = deepStack.client(DEEP_RESEARCH).getAgentCard();
        AgentCard searchCard = searchStack.client(SEARCH).getAgentCard();
        boolean deepPush = deepCard.capabilities() != null && deepCard.capabilities().pushNotifications();
        boolean searchPush = searchCard.capabilities() != null && searchCard.capabilities().pushNotifications();
        LOG.info(String.format(
                "[cascade-probe] capabilities: deep-research.pushNotifications=%s, search-agent.pushNotifications=%s",
                deepPush, searchPush));
        assumeTrue(deepPush,
                "deep-research capabilities.pushNotifications=false → 级联 sender 侧未激活,跳过(INCONCLUSIVE)");
        assumeTrue(searchPush,
                "search-agent capabilities.pushNotifications=false → sub-agent 侧未激活,跳过(INCONCLUSIVE)");

        String contextId = "ctx-cascade-" + UUID.randomUUID().toString().substring(0, 8);
        String messageId = UUID.randomUUID().toString();
        String configId = "sit-cfg-" + UUID.randomUUID().toString().substring(0, 8);
        String configToken = "sit-token-" + UUID.randomUUID().toString().substring(0, 8);

        // 参照 InlinePushConfigAsyncAcceptTest:configuration.taskPushNotificationConfig 是 SDK 1.0.0.Final
        // 正确嵌套路径;id + url 是 Builder NotNull 硬约束;returnImmediately=true 显式请非阻塞骨架.
        String body = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"cascade-%s\","
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
                UUID.randomUUID().toString().substring(0, 8),
                messageId,
                contextId,
                CASCADE_PROMPT,
                configId,
                mockReceiver.callbackUrl(),
                configToken);

        long t0 = System.currentTimeMillis();
        HttpResponse<String> response = post(deepStack.baseUrl(DEEP_RESEARCH) + "/a2a", body);
        long sendElapsed = System.currentTimeMillis() - t0;

        LOG.info(String.format(
                "[cascade-probe] SendMessage returned in %d ms | status=%d%n"
                        + "----- initial response body -----%n%s%n---------------",
                sendElapsed, response.statusCode(), response.body()));

        // 前置探针:初返应 200 + 无 error(非阻塞骨架),否则整条链不必再观测
        assertThat(response.statusCode())
                .as("[cascade-probe] SendMessage HTTP status 应 200(非阻塞骨架)\nbody=%s", response.body())
                .isEqualTo(200);
        JsonNode initialNode = mapper.readTree(response.body());
        assertThat(initialNode.has("error"))
                .as("[cascade-probe] SendMessage 不应返 error(SUT 应接受内联 config)\nbody=%s", response.body())
                .isFalse();

        // 等 mock receiver 收到 ≥1 次 callback,总超时 CASCADE_TIMEOUT_MS
        LOG.info("[cascade-probe] awaiting callback at " + mockReceiver.callbackUrl()
                + " up to " + CASCADE_TIMEOUT_MS + " ms ...");
        boolean reached = mockReceiver.awaitAtLeast(1, CASCADE_TIMEOUT_MS);
        long elapsedTotal = System.currentTimeMillis() - t0;

        // 全量 dump 便于后续分析(无论绿红都 log,方便一次跑完就取得完整证据)
        LOG.info(String.format(
                "[cascade-probe] ===== observation summary =====%n"
                        + "reached=%b, elapsed=%d ms, callbackCount=%d%n",
                reached, elapsedTotal, mockReceiver.count()));
        for (int i = 0; i < mockReceiver.captured().size(); i++) {
            MockCallbackReceiver.CapturedCallback cb = mockReceiver.captured().get(i);
            LOG.info(String.format(
                    "[cascade-probe] --- callback #%d at t+%d ms ---%n"
                            + "headers=%s%n"
                            + "body=%s%n",
                    i, cb.timestampMs() - t0, cb.headers(), cb.body()));
        }

        // 探针主断言:soft —— 期望 ≥1 次 callback。若 0 次,说明级联 sender 侧未落地 or URL_A 未透传,
        // 是<b>本轮探针的核心发现</b>,test 视为 red 以显性化 gap(下一轮再定 spec-gap vs bug)。
        assertThat(reached)
                .as("[cascade-probe] MockCallbackReceiver 未在 %d ms 内收到任何 callback。%n"
                                + "两种可能:%n"
                                + "  (a) SUT 未实现级联 sender(deep-research 完成任务后未回调 URL_A),%n"
                                + "  (b) SUT 侧 push notification 发送链路存在其它 gap(auth / URL 校验 / DNS 等)。%n"
                                + "详情看 log 里 initial response body + captured 计数。",
                        CASCADE_TIMEOUT_MS)
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

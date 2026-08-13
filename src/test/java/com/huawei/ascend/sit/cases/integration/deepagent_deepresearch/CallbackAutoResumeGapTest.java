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
 * FEAT-004.callback-auto-resume-gap — 契约验证:下游 callback 到达后,当前实现<b>不会</b>
 * 自动 resume 父 Agent;必须等调用方再发一次 {@code SendMessage("continue")} 才能续跑。
 *
 * <p><b>Spec 依据</b>:{@code FEAT-004-callback-recovery-does-not-auto-resume-parent-agent.md}
 * §2:当前状态机停在 {@code READY_TO_RESUME},设计要求是
 * {@code callback → READY_TO_RESUME → continuation 调度 → resume}。
 *
 * <p><b>测试形态(双阶段观测,保留完整日志证据)</b>:
 * <pre>
 *   Phase 1: SendMessage(inline URL_A → MockCallbackReceiver)
 *     - deep-research 调 search 子任务
 *     - search COMPLETED → callback deep-research URL_B(runtime 内部)
 *     - deep-research 写 READY_TO_RESUME,但不自动 resume
 *     - <b>观察点</b>:等 90s,断言 MockCallbackReceiver 收到 <b>0</b> 次 URL_A callback
 *
 *   Phase 2: 同 contextId 发第二次 SendMessage(text="continue")
 *     - deep-research 消费 READY_TO_RESUME → resume 父 Agent
 *     - 父 Task COMPLETED → 触发 URL_A callback
 *     - <b>观察点</b>:等 60s,断言 MockCallbackReceiver 收到 <b>≥1</b> 次 URL_A callback
 * </pre>
 *
 * <p><b>为什么两个阶段都必须成立才算证据充分</b>:
 * <ol>
 *   <li>只看 Phase 1 (0 callback):可能是 URL_B 侧根本没跑通,不能定性到 auto-resume 层;</li>
 *   <li>加上 Phase 2 (第二次消息后 callback 到达):证明 URL_A 侧 sender 通道是通的,链路
 *     只差 continuation 触发点。这样才把根因锁定到 {@code READY_TO_RESUME → resume} 缺自动
 *     调度器。</li>
 * </ol>
 *
 * <p><b>与 {@link PushNotificationCascadeProbeTest} 的差别</b>:那份是纯探针,soft 断言"至少
 * 1 次 callback",不区分 Phase 1/2 语义;本用例把"gap 表现"和"workaround 生效"分开断言,
 * 是 FEAT-004 auto-resume gap 的<b>专项契约用例</b>。
 *
 * <p><b>预期结果(基于 2026-08-07 静态分析 + 2026-08-08 探针)</b>:
 * <ul>
 *   <li>Phase 1 断言(0 callback in 90s):green —— 证明 gap 存在;</li>
 *   <li>Phase 2 断言(≥1 callback in 60s):green —— 证明 workaround 生效。</li>
 * </ul>
 * 若 Phase 1 反而收到 callback,说明 SUT 已经补齐 continuation scheduler(gap 已修),test 应
 * 重新定位为 regression positive control。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-004")
@Tag("feat-001")
@Tag("manual")
@Feature("FEAT-004: 远程编排 continuation 自动 resume")
@Story("da.callback-auto-resume-gap: callback 只写 READY_TO_RESUME,不自动 resume,需二次 SendMessage")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CallbackAutoResumeGapTest {

    private static final Logger LOG = Logger.getLogger(CallbackAutoResumeGapTest.class.getName());

    private static final String DEEP_RESEARCH = "deep-research";
    private static final String SEARCH = "search";
    private static final String DEEP_RESEARCH_PORT_SYSPROP = "sut-agents-deep-research-port";

    /**
     * "对比 DeepSeek 和 GLM 的 API 定价并给出来源" —— 复用 FEAT-004 doc §4.2 复现 prompt.
     * 会触发 deep-research 委派 search-agent(远程 tool_call),满足级联条件。
     */
    private static final String INITIAL_PROMPT = "对比 DeepSeek 和 GLM 的 API 定价并给出来源";
    private static final String CONTINUE_PROMPT = "continue";

    /**
     * Phase 1 等待窗口 —— 90s 足够让 search-agent COMPLETED + deep-research 侧写完 READY_TO_RESUME
     * (search 通常 15-30s 内 COMPLETED,shadow batch 写 READY_TO_RESUME 是内部同步操作).
     * 90s 内若还没有 URL_A callback,基本可断言"不会自动 resume"。
     */
    private static final long PHASE1_WAIT_MS = 90_000L;

    /**
     * Phase 2 等待窗口 —— 60s 足够让 deep-research 消费 READY_TO_RESUME + 恢复 Agent + 合成
     * 最终 report + 走 sender fire.
     */
    private static final long PHASE2_WAIT_MS = 60_000L;

    private TestConfig config;
    private SutStack searchStack;
    private SutStack deepStack;
    private MockCallbackReceiver mockReceiver;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    void startStack() throws Exception {
        config = TestConfig.load();

        mockReceiver = MockCallbackReceiver.start();
        LOG.info("[auto-resume-gap] MockCallbackReceiver ready at " + mockReceiver.callbackUrl());

        String llmApiKey = System.getenv("LLM_API_KEY");
        // SEARCH_AGENT_USE_STUB=true 走 stub 分支避免 Tavily API key 强依赖 —— 本用例只关心
        // callback 反向链路 + auto-resume gap,不关心 search 结果内容真伪.
        searchStack = SutStack.builder(config)
                .agent(SEARCH, a -> a
                        .env("SEARCH_AGENT_USE_STUB", "true")
                        .env("SEARCH_AGENT_PUSH_NOTIFICATIONS", "true")
                        .property("openjiuwen.demo.search-agent.api-key", llmApiKey))
                .start();
        String searchBaseUrl = searchStack.baseUrl(SEARCH);
        LOG.info("[auto-resume-gap] search stack ready at " + searchBaseUrl);

        // 参照 PushNotificationCascadeProbeTest:predeserve deep-research port 用于构造
        // DEEP_RESEARCH_PUBLIC_URL —— SUT bean init fail-fast 需要它在启动前就存在.
        int deepPort;
        try (ServerSocket ss = new ServerSocket(0)) {
            deepPort = ss.getLocalPort();
        }
        String deepPublicUrl = "http://127.0.0.1:" + deepPort;
        System.setProperty(DEEP_RESEARCH_PORT_SYSPROP, String.valueOf(deepPort));
        LOG.info("[auto-resume-gap] pre-reserved deep-research port=" + deepPort
                + ", public-url=" + deepPublicUrl);

        deepStack = SutStack.builder(config)
                .streaming(false)
                .agent(DEEP_RESEARCH, a -> a
                        .env("SEARCH_AGENT_URL", searchBaseUrl)
                        .env("VERIFY_AGENT_URL", "http://127.0.0.1:1")
                        .env("DEEP_RESEARCH_PUSH_NOTIFICATIONS", "true")
                        .env("DEEP_RESEARCH_PUBLIC_URL", deepPublicUrl))
                .start();
        LOG.info("[auto-resume-gap] deep-research stack ready at " + deepStack.baseUrl(DEEP_RESEARCH));
    }

    @AfterAll
    void tearDown() {
        if (deepStack != null) deepStack.close();
        if (searchStack != null) searchStack.close();
        if (mockReceiver != null) mockReceiver.close();
        System.clearProperty(DEEP_RESEARCH_PORT_SYSPROP);
    }

    @Test
    @DisplayName("FEAT-004.callback-auto-resume-gap: Phase1 无自动 resume(0 callback);Phase2 二次消息后收到 callback")
    void callbackDoesNotAutoResumeButSecondSendMessageDoes() throws Exception {
        String contextId = "ctx-auto-resume-" + UUID.randomUUID().toString().substring(0, 8);
        String initialMessageId = UUID.randomUUID().toString();
        String configId = "sit-cfg-" + UUID.randomUUID().toString().substring(0, 8);
        String configToken = "sit-token-" + UUID.randomUUID().toString().substring(0, 8);

        // ============== Phase 1:初始 SendMessage ==============
        String phase1Body = buildSendMessage(
                "auto-resume-p1-" + UUID.randomUUID().toString().substring(0, 8),
                initialMessageId, contextId, INITIAL_PROMPT,
                configId, mockReceiver.callbackUrl(), configToken);

        long t0 = System.currentTimeMillis();
        HttpResponse<String> phase1Response = post(deepStack.baseUrl(DEEP_RESEARCH) + "/a2a", phase1Body);
        long phase1SendElapsed = System.currentTimeMillis() - t0;

        LOG.info(String.format(
                "[auto-resume-gap][phase1] SendMessage returned in %d ms | status=%d%n"
                        + "  body=%s",
                phase1SendElapsed, phase1Response.statusCode(), phase1Response.body()));

        assertThat(phase1Response.statusCode())
                .as("[phase1] SendMessage HTTP status 应 200(非阻塞骨架)\nbody=%s", phase1Response.body())
                .isEqualTo(200);

        JsonNode phase1Result = mapper.readTree(phase1Response.body());
        assertThat(phase1Result.has("error"))
                .as("[phase1] SendMessage 不应返 error\nbody=%s", phase1Response.body())
                .isFalse();

        // 从初返骨架里拿 taskId,用于 Phase 2 的 continue 消息关联.
        String parentTaskId = extractTaskId(phase1Result);
        LOG.info(String.format("[auto-resume-gap][phase1] parent taskId = %s", parentTaskId));
        assertThat(parentTaskId).as("[phase1] 初返 result 应含 task.id").isNotBlank();

        // Phase 1 观察窗口:等 PHASE1_WAIT_MS,期望 <b>0</b> callback ——
        // 即使 search 侧 COMPLETED,deep-research 也只写 READY_TO_RESUME 不自动 resume,
        // 因此 MockCallbackReceiver(URL_A)不会收到 callback.
        LOG.info(String.format("[auto-resume-gap][phase1] observing %d ms for auto-resume ...",
                PHASE1_WAIT_MS));
        boolean phase1ReachedOne = mockReceiver.awaitAtLeast(1, PHASE1_WAIT_MS);
        int phase1Count = mockReceiver.count();
        long phase1TotalElapsed = System.currentTimeMillis() - t0;

        LOG.info(String.format(
                "[auto-resume-gap][phase1] ===== observation =====%n"
                        + "  reachedOne=%s, callbackCount=%d, elapsed=%d ms",
                phase1ReachedOne, phase1Count, phase1TotalElapsed));
        dumpCaptured("phase1", t0);

        // ============== Phase 2:二次 SendMessage("continue")同 contextId + parent taskId ==============
        String phase2MessageId = UUID.randomUUID().toString();
        String phase2Body = buildContinueMessage(
                "auto-resume-p2-" + UUID.randomUUID().toString().substring(0, 8),
                phase2MessageId, contextId, parentTaskId, CONTINUE_PROMPT);

        long t2 = System.currentTimeMillis();
        HttpResponse<String> phase2Response = post(deepStack.baseUrl(DEEP_RESEARCH) + "/a2a", phase2Body);
        long phase2SendElapsed = System.currentTimeMillis() - t2;

        LOG.info(String.format(
                "[auto-resume-gap][phase2] second SendMessage(continue) returned in %d ms | status=%d%n"
                        + "  body=%s",
                phase2SendElapsed, phase2Response.statusCode(), phase2Response.body()));

        LOG.info(String.format("[auto-resume-gap][phase2] observing %d ms for URL_A callback ...",
                PHASE2_WAIT_MS));
        boolean phase2Reached = mockReceiver.awaitAtLeast(phase1Count + 1, PHASE2_WAIT_MS);
        int phase2Count = mockReceiver.count();
        long phase2TotalElapsed = System.currentTimeMillis() - t2;

        LOG.info(String.format(
                "[auto-resume-gap][phase2] ===== observation =====%n"
                        + "  reached=%s, callbackCount=%d (+%d), elapsed=%d ms",
                phase2Reached, phase2Count, phase2Count - phase1Count, phase2TotalElapsed));
        dumpCaptured("phase2", t2);

        // ============== 断言 ==============
        // Phase 1:期望 0 callback,证明 gap 存在.
        assertThat(phase1Count)
                .as("[auto-resume-gap][phase1] 期望 %d ms 内 URL_A callback 计数 = 0(证明 auto-resume "
                                + "gap 存在:callback 到达后只写 READY_TO_RESUME,不 resume 父 Agent)。"
                                + "实际收到 %d 次 —— 若 >0,说明 SUT 已补齐 continuation 调度,请更新用例定位.",
                        PHASE1_WAIT_MS, phase1Count)
                .isEqualTo(0);

        // Phase 2:期望 ≥1 callback,证明第二次消息触发 resume,workaround 生效.
        assertThat(phase2Count - phase1Count)
                .as("[auto-resume-gap][phase2] 第二次 SendMessage(continue)后 %d ms 内 URL_A callback "
                                + "增量应 ≥1(证明 workaround 生效:第二次消息消费 READY_TO_RESUME → "
                                + "resume 父 Agent → 父 Task COMPLETED → 触发 URL_A sender)。"
                                + "实际增量 %d —— 若 0,说明 workaround 也不生效,链路存在更深 gap.",
                        PHASE2_WAIT_MS, phase2Count - phase1Count)
                .isGreaterThanOrEqualTo(1);
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

    /**
     * Phase 2 的"continue"消息:同 contextId + 同 parentTaskId 关联到 READY_TO_RESUME 的 batch.
     * 不再携带 pushNotificationConfig —— 复用 Phase 1 已绑定的 config,避免"同 taskId 二次绑定"歧义.
     */
    private static String buildContinueMessage(String rpcId, String messageId, String contextId,
                                                String parentTaskId, String prompt) {
        return String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"%s\","
                        + "\"method\":\"SendMessage\",\"params\":{"
                        + "\"message\":{"
                        + "\"role\":\"ROLE_USER\","
                        + "\"messageId\":\"%s\","
                        + "\"contextId\":\"%s\","
                        + "\"taskId\":\"%s\","
                        + "\"parts\":[{\"text\":\"%s\"}]"
                        + "},"
                        + "\"configuration\":{"
                        + "\"returnImmediately\":true"
                        + "}}}",
                rpcId, messageId, contextId, parentTaskId, prompt);
    }

    private static String extractTaskId(JsonNode result) {
        JsonNode taskId = result.path("result").path("task").path("id");
        if (taskId.isTextual()) return taskId.asText();
        JsonNode topId = result.path("result").path("id");
        if (topId.isTextual()) return topId.asText();
        return "";
    }

    private void dumpCaptured(String phase, long t0) {
        for (int i = 0; i < mockReceiver.captured().size(); i++) {
            MockCallbackReceiver.CapturedCallback cb = mockReceiver.captured().get(i);
            LOG.info(String.format(
                    "[auto-resume-gap][%s] callback #%d at t+%d ms | headers=%s%n  body=%s",
                    phase, i, cb.timestampMs() - t0, cb.headers(), cb.body()));
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

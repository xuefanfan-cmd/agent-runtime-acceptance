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
 * FEAT-004.callback-interrupt-resume-delivery —— 中断驻留不推送 + 续跑后终态投递（多轮闭环契约）。
 *
 * <p><b>2026-08-17 重写（原名语义作废）</b>：本类原为「auto-resume gap 记录型」用例——把
 * 「callback 回灌后只写 READY_TO_RESUME、不自动续跑父 Agent」这一旧缺陷行为写成期望
 * （Phase1 断言 90s 内 0 次外层回调）。两个事实使旧语义作废：
 * <ol>
 *   <li><b>gap 已修</b>：runtime PR
 *       <a href="https://gitcode.com/openJiuwen/agent-runtime-java/merge_requests/151">#151</a>
 *       （2026-08-11 合入，issue #68）让回灌进入 {@code READY_TO_RESUME} 后自动续跑父 Task，
 *       不再要求客户端补发 continue；</li>
 *   <li><b>旧 Phase1 在本拓扑测不到 auto-resume</b>：2026-08-17 日志验尸证实，本拓扑下
 *       search 子代理 0.7s 即以 {@code ask_user} 中断冒泡 {@code INPUT_REQUIRED}（非终态），
 *       而 callback 仅终态触发——没有回灌发生，自动续跑根本无前提；旧 Phase1 的「0 回调」
 *       实际测的是「下游停在澄清态」，与 #68 修没修无关。</li>
 * </ol>
 *
 * <p><b>新语义（正向契约，两阶段）</b>：以 {@code GetTask} 为父任务状态观察面（与开发组
 * #68 回归口径一致），外层 {@link MockCallbackReceiver} 为投递观察面：
 * <pre>
 *   Phase 1: SendMessage(inline push config, url=URL_A)
 *     - 父任务进入 INPUT_REQUIRED 驻留（GetTask 轮询确认）
 *     - 断言：驻留静默窗内 URL_A 收到 0 次回调（非终态不得推送，§5.1.6/终态触发语义）
 *   Phase 2: 同 contextId+taskId 二次 SendMessage 应答澄清
 *     - 父任务在窗口内达终态（GetTask 轮询确认——多轮续跑闭环）
 *     - 断言：URL_A 收到 ≥1 次回调，body 引用父 taskId 且携带终态（多轮后的终态投递）
 * </pre>
 * 若 Phase 1 中父任务未驻留而直接收束终态（LLM 未走澄清分支），则中断腿不在本轮触发：
 * 降级为直接断言终态投递（≥1 回调）后返回，日志注明。
 *
 * <p><b>#68 auto-resume 本身的覆盖归属</b>：需要「下游异步达终态→回灌→自动续跑」前提，
 * 本地实链 search 不声明 push 能力（SEARCH_AGENT_PUSH_NOTIFICATIONS=true 亦不翻转 capability，
 * 2026-08-17 实测），故该前提须由 {@code MockSearchAgentServer} 直驱回灌的级联用例承接
 * （见 {@link CascadeCallbackReceiverAuthTest}），不在本类。
 *
 * <p><b>窗口可调</b>：四个窗口均可用 system property 覆盖（沙箱单次调用限时场景用），
 * 默认值按 CI 口径给足余量。
 *
 * <p><b>Tag</b>：{@code manual} —— 依赖本地双 jar + 真实 LLM。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-004")
@Tag("feat-001")
@Tag("manual")
@Feature("FEAT-004: 远程编排 continuation 自动 resume")
@Story("da.callback-interrupt-resume-delivery: INPUT_REQUIRED 驻留不推送；续跑达终态后向登记 URL 投递")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CallbackAutoResumeGapTest {

    private static final Logger LOG = Logger.getLogger(CallbackAutoResumeGapTest.class.getName());

    private static final String DEEP_RESEARCH = "deep-research";
    private static final String SEARCH = "search";
    private static final String DEEP_RESEARCH_PORT_SYSPROP = "sut-agents-deep-research-port";

    /**
     * 会触发 deep-research 委派 search-agent 的调研 prompt（欠定：厂商无 SKU，命中 search 侧
     * ask_user 硬规则，预期走澄清分支）。刻意单厂商单维度——澄清后只需极少检索轮次即可终态，
     * 避免多厂商对比展开成多轮循环撑爆观察窗（2026-08-17 实测教训）。
     */
    private static final String INITIAL_PROMPT = "你好,帮我查一下 DeepSeek 官网 API 定价，请给出来源。";
    /** Phase 2 的澄清应答——给出具体 SKU 并显式收窄范围，让续跑用最少轮次收束终态。 */
    private static final String CONTINUE_PROMPT = "DeepSeek-V3，只需要输入价格一项，给一条来源即可。";

    /** Phase1：等待父任务驻留 INPUT_REQUIRED 的轮询上限。 */
    private static final long PHASE1_PARK_TIMEOUT_MS =
            Long.getLong("sit.autoresume.phase1-park-timeout-ms", 60_000L);
    /** Phase1：驻留确认后的静默观察窗（断言窗内 0 次外层回调）。 */
    private static final long PHASE1_QUIET_MS =
            Long.getLong("sit.autoresume.phase1-quiet-ms", 15_000L);
    /** Phase2：续跑后等待父任务达终态的轮询上限。 */
    private static final long PHASE2_TERMINAL_TIMEOUT_MS =
            Long.getLong("sit.autoresume.phase2-terminal-timeout-ms", 120_000L);
    /** Phase2：终态后等待外层回调投递的窗口。 */
    private static final long PHASE2_CALLBACK_WAIT_MS =
            Long.getLong("sit.autoresume.phase2-callback-wait-ms", 60_000L);

    private static final long POLL_INTERVAL_MS = 2_000L;

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
        LOG.info("[interrupt-resume] MockCallbackReceiver ready at " + mockReceiver.callbackUrl());

        String llmApiKey = System.getenv("LLM_API_KEY");
        searchStack = SutStack.builder(config)
                .agent(SEARCH, a -> a
                        .env("SEARCH_AGENT_USE_STUB", "true")
                        .env("SEARCH_AGENT_PUSH_NOTIFICATIONS", "true")
                        .property("openjiuwen.demo.search-agent.api-key", llmApiKey))
                .start();
        String searchBaseUrl = searchStack.baseUrl(SEARCH);
        LOG.info("[interrupt-resume] search stack ready at " + searchBaseUrl);

        int deepPort;
        try (ServerSocket ss = new ServerSocket(0)) {
            deepPort = ss.getLocalPort();
        }
        String deepPublicUrl = "http://127.0.0.1:" + deepPort;
        System.setProperty(DEEP_RESEARCH_PORT_SYSPROP, String.valueOf(deepPort));
        LOG.info("[interrupt-resume] pre-reserved deep-research port=" + deepPort
                + ", public-url=" + deepPublicUrl);

        deepStack = SutStack.builder(config)
                .streaming(false)
                .agent(DEEP_RESEARCH, a -> a
                        .env("SEARCH_AGENT_URL", searchBaseUrl)
                        .env("VERIFY_AGENT_URL", "http://127.0.0.1:1")
                        .env("DEEP_RESEARCH_PUSH_NOTIFICATIONS", "true")
                        .env("DEEP_RESEARCH_PUBLIC_URL", deepPublicUrl))
                .start();
        LOG.info("[interrupt-resume] deep-research stack ready at " + deepStack.baseUrl(DEEP_RESEARCH));
    }

    @AfterAll
    void tearDown() {
        if (deepStack != null) deepStack.close();
        if (searchStack != null) searchStack.close();
        if (mockReceiver != null) mockReceiver.close();
        System.clearProperty(DEEP_RESEARCH_PORT_SYSPROP);
    }

    @Test
    @DisplayName("FEAT-004.callback-interrupt-resume-delivery: INPUT_REQUIRED 驻留期 0 推送；澄清续跑达终态后 URL_A 收到终态投递")
    void interruptParksWithoutPushAndResumeDeliversTerminalCallback() throws Exception {
        String contextId = "ctx-int-resume-" + UUID.randomUUID().toString().substring(0, 8);
        String configId = "sit-cfg-" + UUID.randomUUID().toString().substring(0, 8);
        String configToken = "sit-token-" + UUID.randomUUID().toString().substring(0, 8);

        // ============== Phase 1：初始 SendMessage（内联 push config → URL_A）==============
        String phase1Body = buildSendMessage(
                "int-resume-p1-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID().toString(), contextId, INITIAL_PROMPT,
                configId, mockReceiver.callbackUrl(), configToken);

        long t0 = System.currentTimeMillis();
        HttpResponse<String> phase1Response = post(deepStack.baseUrl(DEEP_RESEARCH) + "/a2a", phase1Body);
        LOG.info(String.format("[interrupt-resume][phase1] SendMessage returned in %d ms | status=%d",
                System.currentTimeMillis() - t0, phase1Response.statusCode()));

        assertThat(phase1Response.statusCode())
                .as("[phase1] SendMessage HTTP status 应 200\nbody=%s", phase1Response.body())
                .isEqualTo(200);
        JsonNode phase1Result = mapper.readTree(phase1Response.body());
        assertThat(phase1Result.has("error"))
                .as("[phase1] SendMessage 不应返 error\nbody=%s", phase1Response.body())
                .isFalse();

        String parentTaskId = extractTaskId(phase1Result);
        assertThat(parentTaskId).as("[phase1] 初返 result 应含 task.id").isNotBlank();
        LOG.info("[interrupt-resume][phase1] parent taskId = " + parentTaskId);

        // 以 GetTask 为状态观察面：等父任务驻留 INPUT_REQUIRED（或直接收束终态）。
        String parkedState = pollTaskState(parentTaskId, PHASE1_PARK_TIMEOUT_MS,
                s -> s.contains("INPUT_REQUIRED") || isTerminal(s));
        LOG.info("[interrupt-resume][phase1] parked/observed state = " + parkedState);

        if (parkedState != null && isTerminal(parkedState)) {
            // 中断腿未触发（LLM 未走澄清分支）：降级为直接断言终态投递后返回。
            LOG.info("[interrupt-resume][phase1] 父任务未驻留、直接收束 " + parkedState
                    + "——中断腿本轮未触发，降级断言终态投递。");
            boolean delivered = mockReceiver.awaitAtLeast(1, PHASE2_CALLBACK_WAIT_MS);
            assertThat(delivered)
                    .as("[phase1-degraded] 父任务已终态 %s 但 %d ms 内 URL_A 无投递（终态投递缺失）",
                            parkedState, PHASE2_CALLBACK_WAIT_MS)
                    .isTrue();
            return;
        }

        assertThat(parkedState)
                .as("[phase1] %d ms 内父任务未达 INPUT_REQUIRED 驻留（GetTask 观察面），无从进入多轮闭环。"
                        + "最后观测态=%s", PHASE1_PARK_TIMEOUT_MS, parkedState)
                .isNotNull()
                .contains("INPUT_REQUIRED");

        // 驻留静默窗：非终态不得推送 —— URL_A 必须保持 0 次。
        boolean phase1Any = mockReceiver.awaitAtLeast(1, PHASE1_QUIET_MS);
        assertThat(phase1Any)
                .as("[phase1] 父任务驻留 INPUT_REQUIRED（非终态）期间 URL_A 不得收到推送；"
                        + "静默窗 %d ms 内实收 %d 次（非终态推送=契约外）",
                        PHASE1_QUIET_MS, mockReceiver.count())
                .isFalse();
        LOG.info("[interrupt-resume][phase1] 驻留静默窗通过：0 push while INPUT_REQUIRED");

        // ============== Phase 2：同 contextId+taskId 应答澄清，续跑到终态 ==============
        String phase2Body = buildContinueMessage(
                "int-resume-p2-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID().toString(), contextId, parentTaskId, CONTINUE_PROMPT);
        long t2 = System.currentTimeMillis();
        HttpResponse<String> phase2Response = post(deepStack.baseUrl(DEEP_RESEARCH) + "/a2a", phase2Body);
        LOG.info(String.format("[interrupt-resume][phase2] continue returned in %d ms | status=%d",
                System.currentTimeMillis() - t2, phase2Response.statusCode()));
        assertThat(phase2Response.statusCode())
                .as("[phase2] continue HTTP status 应 200\nbody=%s", phase2Response.body())
                .isEqualTo(200);

        // GetTask 轮询到终态（多轮续跑闭环的核心断言，与 #68 回归口径同观察面）。
        String terminalState = pollTaskState(parentTaskId, PHASE2_TERMINAL_TIMEOUT_MS,
                CallbackAutoResumeGapTest::isTerminal);
        assertThat(terminalState != null && isTerminal(terminalState))
                .as("[phase2] 澄清应答后 %d ms 内父任务未达终态（GetTask 观察面）——多轮续跑闭环断裂；"
                        + "最后观测态=%s", PHASE2_TERMINAL_TIMEOUT_MS, terminalState)
                .isTrue();
        LOG.info("[interrupt-resume][phase2] parent terminal state = " + terminalState);

        // 终态投递：URL_A 必须收到 ≥1 次，body 引用父 taskId 且携带终态。
        boolean delivered = mockReceiver.awaitAtLeast(1, PHASE2_CALLBACK_WAIT_MS);
        assertThat(delivered)
                .as("[phase2] 父任务已终态 %s 但 %d ms 内 URL_A 无投递（多轮后的终态投递缺失）",
                        terminalState, PHASE2_CALLBACK_WAIT_MS)
                .isTrue();
        MockCallbackReceiver.CapturedCallback cb =
                mockReceiver.captured().get(mockReceiver.captured().size() - 1);
        LOG.info(String.format("[interrupt-resume][phase2] callback at t+%d ms | body(前400)=%s",
                cb.timestampMs() - t2, truncate(cb.body(), 400)));
        assertThat(cb.body())
                .as("[phase2] 投递 body 应引用父 taskId=%s", parentTaskId)
                .contains(parentTaskId);
        assertThat(cb.body())
                .as("[phase2] 投递 body 应携带终态（%s）", terminalState)
                .contains(terminalState);
    }

    // —— helpers ——

    /** 轮询 GetTask 直到状态满足谓词或超时；命中即返回，超时返回最后观测到的状态（可能不满足谓词，便于诊断），全程无状态则 null。 */
    private String pollTaskState(String taskId, long timeoutMs,
                                 java.util.function.Predicate<String> until) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            String body = String.format(
                    "{\"jsonrpc\":\"2.0\",\"id\":\"gt-%s\",\"method\":\"GetTask\",\"params\":{\"id\":\"%s\"}}",
                    UUID.randomUUID().toString().substring(0, 8), taskId);
            HttpResponse<String> resp = post(deepStack.baseUrl(DEEP_RESEARCH) + "/a2a", body);
            if (resp.statusCode() == 200) {
                JsonNode root = mapper.readTree(resp.body());
                String s = firstNonBlank(
                        root.path("result").path("task").path("status").path("state").asText(null),
                        root.path("result").path("status").path("state").asText(null));
                if (s != null) {
                    last = s;
                    if (until.test(s)) {
                        return s;
                    }
                }
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        return last;
    }

    private static boolean isTerminal(String state) {
        return state != null && (state.contains("COMPLETED") || state.contains("FAILED")
                || state.contains("CANCELED") || state.contains("REJECTED"));
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

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b != null && !b.isBlank() ? b : null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
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

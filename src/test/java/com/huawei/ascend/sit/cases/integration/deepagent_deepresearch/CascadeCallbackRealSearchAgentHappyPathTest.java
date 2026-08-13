package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.mock.TransparentA2AProxy;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.ServerSocket;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-001.cascade-callback-real-search-agent-happy-path — 用 <b>真实 search-agent</b>(非 mock)
 * 端到端验证 push-notification 级联 happy path。
 *
 * <p><b>为什么要独立于 {@link CascadeCallbackReceiverAuthTest}</b>:后者用 mock 精细注入
 * WRONG_TOKEN / MISSING_TOKEN / WRONG_NID 变体,拆开测 receiver 侧 auth 层每个 rejection code;
 * 但 mock 侧构造的 SendMessage response 是 skeleton COMPLETED,没有真正的 LLM/tool 参与,不能证明
 * <b>SUT 端到端配合真实 sub-agent</b> 也能走通级联。本用例补这一块 —— 只测 happy path,不做
 * 变体注入,只回答:"真 caller → 真 deep-research → 真 search-agent → 真 callback → deep-research
 * 收到并处理" 这条链是否端到端可跑。
 *
 * <p><b>链路</b>:
 * <pre>{@code
 *   caller (test) ──SendMessage(NO pushConfig)──▶ deep-research (PUSH=true, PUBLIC_URL=<self>)
 *                                                        │
 *                                                        │ ReAct tool_call → SendMessage(WITH
 *                                                        │   pushConfig{url=self/callback, id, token})
 *                                                        ▼
 *                                                real search-agent (PUSH=false)
 *                                                        │ LLM + tool 执行完成
 *                                                        │ TASK_STATE_COMPLETED
 *                                                        │
 *                                                        ▼ sender 反向 POST /a2a/push-notifications/callback
 *                                                        │ (Authorization: Bearer <token>,
 *                                                        │  X-A2A-Notification-Id: <nid>)
 *                                                deep-research receiver 接受 → 恢复 caller task
 * }</pre>
 *
 * <p><b>断言 (双向 wire 抓包驱动, 2026-08-09 重构)</b>:
 * <ol>
 *   <li><b>硬</b>:searchProxy 至少捕获 1 次 outbound SendMessage POST(证明 tool_call 路径通);</li>
 *   <li><b>硬</b>:outbound SendMessage 请求体含完整 {@code taskPushNotificationConfig{url,id,token}};</li>
 *   <li><b>硬</b>:callbackProxy 捕获 sub-agent → deep-research 反向 POST /callback,且 deep-research
 *       receiver 返 200 accepted(证明反向 fire + auth + binding 全通);</li>
 *   <li><b>硬</b>:caller parent task 达 COMPLETED —— 这一条持续红,是 FEAT-004 auto-resume gap
 *       的 red-first 显性化:即使前 3 步 wire 全通,dr 收 callback 后仍未把 sub-agent 结果 wire 回
 *       ReAct 循环 emit terminal state.</li>
 * </ol>
 *
 * <p><b>依赖</b>:真 LLM(deep-research + search-agent 都需 LLM_API_KEY)。CI 默认不满足,
 * {@code @Tag("manual")}。
 *
 * <p><b>与 {@link MultiTurnSearchFollowupTest} 的差异</b>:MultiTurnSearchFollowupTest 关心多轮
 * INPUT_REQUIRED 追问循环,不激活 push notification;本用例主要激活 push + 关心 callback 链路。
 * 参考同一双 stack pattern(先启 search-agent 拿 baseUrl,再启 deep-research 塞 SEARCH_AGENT_URL env)。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Tag("manual")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.cascade-callback-real-search-happy-path: 真 caller → 真 deep-research(PUSH) → 真 search-agent → 真 callback 端到端 smoke")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CascadeCallbackRealSearchAgentHappyPathTest {

    private static final Logger LOG = Logger.getLogger(CascadeCallbackRealSearchAgentHappyPathTest.class.getName());

    private static final String SEARCH = "search";
    private static final String DEEP = "deep-research";
    private static final String DEEP_PORT_SYSPROP = "sut-agents-deep-research-port";
    /**
     * 直接明确的搜索指令,不含时间/地域等易触发 clarification 的模糊条件 —— 配合
     * {@code SEARCH_AGENT_USE_STUB=true} 走 stub 分支避免 Tavily 真调,让 search-agent 走一次
     * 完整 LLM 生命周期直接 COMPLETE,不进 INPUT_REQUIRED clarification 循环. 2026-08-08 之前
     * 用"2026 年 7 月盘中最高价"含歧义时间锚点,deep-research 侧观察到 sub-agent 224ms 进
     * INPUT_REQUIRED,替换为完全明确的 URL 查询后避免这条路径.
     */
    private static final String PROMPT = "查询 Python 官方网站的 URL";
    /**
     * 2026-08-08 探针实测:BUG-009 未修情况下,任务在 ~225ms 内到 INPUT_REQUIRED 后永久卡住
     * (search-agent 无 pushConfig 可回调 → deep-research 永不收到 tool_call 结果)。
     * 90s 足够 happy path(search LLM 通常 10-15s 完成 + 一次 LLM 归纳),同时避免修坏时白等 4 分钟。
     */
    private static final long TASK_TIMEOUT_MS = 90_000L;

    private TestConfig config;
    private SutStack searchStack;
    private SutStack deepStack;
    private TransparentA2AProxy searchProxy;
    private TransparentA2AProxy callbackProxy;
    private int deepPort;

    @BeforeAll
    void startStacks() throws Exception {
        String llmApiKey = System.getenv("LLM_API_KEY");
        assumeTrue(llmApiKey != null && !llmApiKey.isBlank(),
                "LLM_API_KEY 缺,deep-research + search-agent 都需真 LLM,跳过。");

        config = TestConfig.load();

        // 先起真 search-agent,拿到 baseUrl 后再启 deep-research 塞进 SEARCH_AGENT_URL env。
        // 参考 MultiTurnSearchFollowupTest 双 stack pattern —— 单 stack 会因 SEARCH_AGENT_URL
        // 默认值不匹配随机端口而失败。
        // 用 SEARCH_AGENT_USE_STUB=true 避免真 Tavily 依赖(见 BUG-008 §4.3);对本用例足够 ——
        // 我们只验证 callback 级联链路,不关心 search 结果本身内容。
        //
        // SEARCH_AGENT_PUSH_NOTIFICATIONS=true: search-agent 需激活自己的 push sender bean,
        // 才能在 caller 携 pushConfig 时于 COMPLETED/FAILED 反向 fire callback。
        // 默认 false → sender bean 不装配 → 即便 deep-research 传了合规 pushConfig,
        // search-agent 完成任务时也不会 POST 回来,deep-research 侧永卡等超时。
        // SEARCH_AGENT_PUBLIC_URL 是 agent-card 自身公告 URL,与 push sender 激活无直接强绑,
        // 但保留完整以贴合 real deploy 形态,防止 SUT 内部有二次校验挂钩此值。
        int searchPort;
        try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) { searchPort = ss.getLocalPort(); }
        String searchPublicUrl = "http://127.0.0.1:" + searchPort;
        System.setProperty("sut-agents-search-port", String.valueOf(searchPort));
        searchStack = SutStack.builder(config)
                .agent(SEARCH, a -> a
                        .env("SEARCH_AGENT_USE_STUB", "true")
                        .env("SEARCH_AGENT_PUSH_NOTIFICATIONS", "true")
                        .env("SEARCH_AGENT_PUBLIC_URL", searchPublicUrl)
                        .property("openjiuwen.demo.search-agent.api-key", llmApiKey))
                .start();
        String searchBaseUrl = searchStack.baseUrl(SEARCH);
        LOG.info("[real-cascade.happy] real search-agent @ " + searchBaseUrl);

        // 2026-08-09: 用户明确 —— 不 grep 日志,直接抓 wire. 把透明代理夹在 deep-research
        // 与 real search-agent 之间,捕获 outbound A2A 请求体. 断言 taskPushNotificationConfig
        // 是否出现在 wire 上,直接定性 BUG-009 outbound 侧是否 attach pushConfig.
        searchProxy = TransparentA2AProxy.startForwarding(searchBaseUrl);
        String searchUrlForDeep = searchProxy.baseUrl();
        LOG.info("[real-cascade.happy] proxy @ " + searchUrlForDeep + " → " + searchBaseUrl);

        // 预锁 deep-research 端口 —— PUBLIC_URL 必须与随机端口一致,receiver 才能激活。
        try (ServerSocket ss = new ServerSocket(0)) { deepPort = ss.getLocalPort(); }
        System.setProperty(DEEP_PORT_SYSPROP, String.valueOf(deepPort));

        // 反向 callback 抓包代理: search-agent 完成任务时会 POST 到 pushConfig.url,
        // pushConfig.url = DEEP_RESEARCH_PUBLIC_URL + /a2a/push-notifications/callback.
        // 让 DEEP_RESEARCH_PUBLIC_URL 指向本代理,代理 → 真 deep-research 端口 (deepPort),
        // 就能截获 search-agent → deep-research 的 callback 请求体,直接定性 sub-agent
        // 是否真 fire callback + fire 的 headers/body 内容.
        callbackProxy = TransparentA2AProxy.startForwarding("http://127.0.0.1:" + deepPort);
        String publicUrl = callbackProxy.baseUrl();
        LOG.info("[real-cascade.happy] callback proxy @ " + publicUrl
                + " → http://127.0.0.1:" + deepPort);

        // callback-auth.bearer-token 是 deep-research 侧 shared secret,通过 DEEP_RESEARCH_CALLBACK_TOKEN
        // 环境变量配置(见 agent-deep-research jar 内 application.yml L85-86):deep-research 调 sub-agent
        // 时 pushConfig 携带该 token,sub-agent COMPLETED 反向 callback 时 echo 回来 → receiver 侧比对确认。
        // 空 token → mock 场景 pushConfig.token="" 静态假象;real e2e 场景 receiver 一定拒(token 校验层)。
        // 这里注入固定测试 token —— 用例只需一致的字符串,不需要真强度密钥。
        String callbackToken = "test-cascade-token-" + UUID.randomUUID();

        deepStack = SutStack.builder(config)
                .streaming(false)
                .agent(DEEP, a -> a
                        // SEARCH_AGENT_URL 指向 proxy 而不是 real search-agent,让 outbound HTTP
                        // 请求先落 proxy 抓完 wire 再透传给 real search-agent.
                        .env("SEARCH_AGENT_URL", searchUrlForDeep)
                        .env("VERIFY_AGENT_URL", "http://127.0.0.1:1")
                        .env("DEEP_RESEARCH_PUSH_NOTIFICATIONS", "true")
                        .env("DEEP_RESEARCH_PUBLIC_URL", publicUrl)
                        .env("DEEP_RESEARCH_CALLBACK_TOKEN", callbackToken)
                        .property("openjiuwen.demo.deep-research.api-key", llmApiKey))
                .start();
        LOG.info("[real-cascade.happy] deep-research @ " + deepStack.baseUrl(DEEP)
                + " (PUBLIC_URL=" + publicUrl + ")");
    }

    @AfterAll
    void tearDown() {
        if (deepStack != null) deepStack.close();
        if (searchProxy != null) searchProxy.close();
        if (callbackProxy != null) callbackProxy.close();
        if (searchStack != null) searchStack.close();
        System.clearProperty(DEEP_PORT_SYSPROP);
        System.clearProperty("sut-agents-search-port");
    }

    @Test
    @DisplayName("cascade.real-search.happy: 真 e2e caller → deep-research(PUSH) → real search → callback 闭环 → COMPLETED")
    void realCascadeHappyPathReachesCompletedAndNoBindingGap() throws Exception {
        A2aServiceClient a2a = deepStack.client(DEEP);

        String contextId = "ctx-real-cascade-" + UUID.randomUUID().toString().substring(0, 8);
        Message message = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .contextId(contextId)
                .parts(List.of(new TextPart(PROMPT)))
                .build();

        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> sendError = new AtomicReference<>();
        a2a.sendMessage(message, List.of(collector.createConsumer()), sendError::set);
        if (sendError.get() != null) {
            fail("[real-cascade.happy] message/send 抛异常", sendError.get());
        }

        TaskState finalState;
        Throwable timeoutError = null;
        try {
            finalState = awaitTerminal(collector, a2a, TASK_TIMEOUT_MS);
        } catch (Throwable t) {
            finalState = null;
            timeoutError = t;
        }
        String taskId = collector.findFirstTaskId();
        LOG.info(String.format("[real-cascade.happy] terminalState=%s taskId=%s contextId=%s",
                finalState, taskId, contextId));

        // wire 抓包:proxy 已经沉了 deep-research → search-agent 所有 HTTP 交换,
        // 不依赖 SUT log(用户 2026-08-09 明确日志不齐全,以 HTTP 包为准).
        List<TransparentA2AProxy.CapturedExchange> allExchanges = searchProxy.captured();
        List<TransparentA2AProxy.CapturedExchange> sendMessagePosts = allExchanges.stream()
                .filter(e -> "POST".equals(e.method()) && e.path().startsWith("/a2a"))
                .filter(e -> e.requestBody() != null && e.requestBody().contains("\"SendMessage\""))
                .toList();
        List<TransparentA2AProxy.CapturedExchange> withPushConfig = searchProxy.capturedWithPushConfig();

        LOG.info(String.format(
                "[real-cascade.happy] proxy captured %d exchanges, %d SendMessage POSTs, "
                        + "%d with taskPushNotificationConfig",
                allExchanges.size(), sendMessagePosts.size(), withPushConfig.size()));
        for (int i = 0; i < sendMessagePosts.size(); i++) {
            TransparentA2AProxy.CapturedExchange ex = sendMessagePosts.get(i);
            LOG.info(String.format(
                    "[real-cascade.happy] SendMessage[%d] status=%d bodyLen=%d hasPushConfig=%s%n"
                            + "  requestBody=%s%n"
                            + "  responseBody=%s",
                    i, ex.responseStatus(), ex.requestBody().length(),
                    ex.requestBody().contains("taskPushNotificationConfig"),
                    ex.requestBody(),
                    ex.responseBody()));
        }

        // 反向 callback 抓包: search-agent → deep-research 的所有 HTTP 请求全过 callbackProxy.
        // 关注 POST /a2a/push-notifications/callback —— sub-agent 完成任务时反向 fire 的 wire.
        List<TransparentA2AProxy.CapturedExchange> callbackExchanges = callbackProxy.captured();
        List<TransparentA2AProxy.CapturedExchange> callbackPosts = callbackExchanges.stream()
                .filter(e -> "POST".equals(e.method())
                        && e.path().contains("/a2a/push-notifications/callback"))
                .toList();
        LOG.info(String.format(
                "[real-cascade.happy] callbackProxy captured %d exchanges, %d POST /callback",
                callbackExchanges.size(), callbackPosts.size()));
        for (int i = 0; i < callbackPosts.size(); i++) {
            TransparentA2AProxy.CapturedExchange ex = callbackPosts.get(i);
            LOG.info(String.format(
                    "[real-cascade.happy] callback[%d] status=%d headers=%s%n"
                            + "  requestBody=%s%n"
                            + "  responseBody=%s",
                    i, ex.responseStatus(), ex.requestHeaders(),
                    ex.requestBody(), ex.responseBody()));
        }

        // 硬断言 1(wire 级别):deep-research 至少发过一次 SendMessage 给 search-agent —— 证明
        // tool_call 路径本身通,不是 LLM 拒绝委派或 discovery 前置失败.
        assertThat(sendMessagePosts)
                .as("[real-cascade.happy] searchProxy 应捕获到 deep-research → search-agent 至少 1 次 "
                                + "SendMessage POST。实测 %d 次 —— 若 0,LLM 未触发 tool_call 或 "
                                + "SEARCH_AGENT_URL/discovery 断链,链路问题更靠上游.%n  allExchanges=%d",
                        sendMessagePosts.size(), allExchanges.size())
                .isNotEmpty();

        // 硬断言 2(wire 级别):outbound pushConfig 显性化 —— deep-research 出站请求体应含
        // taskPushNotificationConfig(url + id + token). 2026-08-09 wire 已证实此断言 green,
        // 保留是为了在未来 regression (env 忘设 / 装配丢失) 时立即红.
        assertThat(withPushConfig)
                .as("[real-cascade.happy] outbound —— deep-research 出站 A2A 请求应携带"
                                + " taskPushNotificationConfig{url,id,token},实测 %d 条含 pushConfig。"
                                + "空 ⇒ SUT 出站 push caller 未 wire 进 ReAct tool_call 路径或 env"
                                + " (DEEP_RESEARCH_PUSH_NOTIFICATIONS/PUBLIC_URL/CALLBACK_TOKEN) 未设.%n"
                                + "  SendMessage samples: %s",
                        withPushConfig.size(),
                        sendMessagePosts.isEmpty() ? "(none)"
                                : sendMessagePosts.get(0).requestBody())
                .isNotEmpty();

        // 硬断言 3(wire 级别):反向 callback fire + receiver accept 显性化 ——
        // callbackProxy 应捕获 sub-agent → deep-research 的 POST /callback,且 deep-research
        // 返 200 accepted. 2026-08-09 wire 已证实此断言 green(subTaskId 一致, token echo,
        // notificationId 双向匹配, receiver 返 200 accepted). 为未来 sub-agent push sender
        // 失效 / receiver 拒绝签名等 regression 兜底.
        assertThat(callbackPosts)
                .as("[real-cascade.happy] reverse callback —— sub-agent 完成任务后应反向 fire 到"
                                + " pushConfig.url,callbackProxy 应捕获 POST /callback 至少 1 次,"
                                + "实测 %d 次。空 ⇒ sub-agent push sender bean 未装配 (需 "
                                + "SEARCH_AGENT_PUSH_NOTIFICATIONS=true) 或 pushConfig 传参丢失.%n"
                                + "  callbackExchanges total=%d",
                        callbackPosts.size(), callbackExchanges.size())
                .isNotEmpty();
        assertThat(callbackPosts).allSatisfy(ex ->
                assertThat(ex.responseStatus())
                        .as("[real-cascade.happy] deep-research receiver 应接受 callback (200 accepted),"
                                        + "实测 status=%d 拒绝 ⇒ auth/binding 层异常 [[push-notification-security-model]]",
                                ex.responseStatus())
                        .isEqualTo(200));

        // 硬断言 4:端到端 auto-resume —— caller task 应达 COMPLETED. 2026-08-09 wire 证据表明,
        // 即便前 3 步全通 (outbound OK, callback fire OK, receiver 200 accepted), caller task
        // 仍然永不 terminal — 这就是 FEAT-004 auto-resume gap. 本断言持续硬红是设计正确姿势,
        // 直到 dr 内部 callback → ReAct 循环 wake-up 机制修好.
        assertThat(finalState)
                .as("[real-cascade.happy] FEAT-004 auto-resume gap —— cascade wire 全通 "
                                + "(outbound pushConfig OK, callback fire OK, receiver 200 accepted),"
                                + "但 caller parent task 仍未 resume 到 COMPLETED。timeoutError=%s%n"
                                + "  terminalState=%s taskId=%s callbackAccepted=%d/%d%n"
                                + "  dr 收下 callback 但未把 sub-agent 结果 wire 回 ReAct 循环 —— "
                                + "参见 [[feat-004-callback-auto-resume-gap]] memory.",
                        timeoutError, finalState, taskId,
                        callbackPosts.stream().filter(ex -> ex.responseStatus() == 200).count(),
                        callbackPosts.size())
                .isEqualTo(TaskState.TASK_STATE_COMPLETED);
    }

    /** 等 collector 或 getTask 拿到 final terminal state(COMPLETED/FAILED/CANCELED). */
    private static TaskState awaitTerminal(A2aEventCollector collector, A2aServiceClient a2a, long timeoutMs) {
        return Awaitility.await("real-cascade terminal")
                .atMost(timeoutMs, TimeUnit.MILLISECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> {
                    Optional<ClientEvent> terminal = collector.findTerminalEvent();
                    if (terminal.isPresent()) {
                        return stateOf(terminal.get());
                    }
                    String tid = collector.findFirstTaskId();
                    if (tid == null || tid.isBlank()) return null;
                    Task task = a2a.getTask(tid);
                    if (task == null || task.status() == null || task.status().state() == null) return null;
                    TaskState s = task.status().state();
                    return s.isFinal() ? s : null;
                }, Objects::nonNull);
    }

    private static TaskState stateOf(ClientEvent e) {
        if (e instanceof TaskEvent te) return te.getTask().status().state();
        if (e instanceof TaskUpdateEvent ue) return ue.getTask().status().state();
        return null;
    }

    /** 抓 log 里含 callback/push 关键字的最后 N 行,便于 debug —— 不做强断言,SUT log 格式易变. */
    private static String grepCallbackLines(String logContent, int lastN) {
        String[] lines = logContent.split("\\r?\\n");
        StringBuilder sb = new StringBuilder();
        int matched = 0;
        for (int i = lines.length - 1; i >= 0 && matched < lastN; i--) {
            String ln = lines[i];
            if (ln.contains("callback") || ln.contains("Callback")
                    || ln.contains("pushConfig") || ln.contains("push-notification")
                    || ln.contains("PushNotification") || ln.contains("notificationId")) {
                sb.insert(0, ln).insert(0, "\n");
                matched++;
            }
        }
        return sb.length() == 0 ? "(no callback-related log lines found)" : sb.toString();
    }
}

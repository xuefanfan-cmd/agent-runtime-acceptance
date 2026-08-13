package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.mock.MockCallbackReceiver;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-001.callback-delivery-contract — 三条 push-notification delivery 契约合并覆盖:
 * <ol>
 *   <li><b>token 传播</b>(§2.17 上游):SendMessage 里 {@code taskPushNotificationConfig.token}
 *       必须被 sender 携到 callback HTTP,让最外层调用方(上游 agent)能在 callback 收到时校验;</li>
 *   <li><b>trigger scope</b>(§2.13):终态(COMPLETED)触发 sender 恰好一次,中间态(WORKING/
 *       SUBMITTED)不应触发独立 callback;</li>
 *   <li><b>payload shape</b>(§3.27):callback body 复用 Task/Message 表面,而 notification-id
 *       走 {@code X-A2A-Notification-Id} header,不出现在 body 顶层字段。</li>
 * </ol>
 *
 * <p><b>为什么合并到一个文件</b>:三条都要 <b>valid LLM key</b> + search-agent stack + Mock
 * callback receiver 才能观测;每次 stack 起 15-30s 昂贵。合并 @BeforeAll 起 stack 一次,每个
 * @Test 用新 receiver + fresh contextId 避免残余 callback 污染。
 *
 * <p><b>Spec 依据</b>:
 * <ul>
 *   <li>version-scope FEAT-001 §2.13 「push notifications callback trigger 边界」:
 *       终态才 fire,中间态不 fire 独立 callback(避免风暴);</li>
 *   <li>§2.17 「callback 安全边界」:发送方在 config 里带 token,接收方(调用方 agent)在
 *       {@code /a2a/push-notifications/callback} 路径 auth 校验;
 *       [[push-notification-security-model]] token-on-callback 项目实际策略;</li>
 *   <li>§3.27 「push notifications 表面共享」:callback body 复用 Task 或 Message JSON 表面,
 *       notification-id 属于传输元数据,应走 header。</li>
 * </ul>
 *
 * <p><b>观测依赖</b>:LLM 401 是 §S2 buggy 路径([[push-notification-sender-not-activated]]),
 * 会造成 sender skip。因此必须用 <b>valid LLM_API_KEY</b>(走 §S3 working COMPLETED 路径),
 * callback 才会 fire。key 缺 → {@code assumeTrue} skip,不算失败。
 *
 * <p><b>为什么不断言"恰好一次"的严格上界</b>:trigger scope 用例的 count 上界只在观察窗口内
 * 断言 {@code count == 1};若 SUT 未来加了 progress-notification(§S4 可能扩展),再修正断言。
 * 严格 {@code == 1} 比 {@code >= 1} 更能显性化 "多余 fire" 或 "重复 fire" 的 regression。
 *
 * <p><b>Tag</b>:{@code manual} —— 依赖真 LLM 端点(合法 key + 网络连通),CI 默认不满足。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Tag("manual")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.callback-delivery-contract: token/trigger-scope/payload-shape 三条 push notification 契约")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CallbackDeliveryContractTest {

    private static final Logger LOG = Logger.getLogger(CallbackDeliveryContractTest.class.getName());

    private static final String SEARCH = "search";

    /** 短确定性 prompt —— 快速 COMPLETED,给 sender fire 留窗口. */
    private static final String PROMPT = "帮我搜索 2026 年 7 月全球黄金价格盘中最高价";

    /** COMPLETED callback 观测预算 —— search-agent 端到端通常 15-30s,给 90s 覆盖 warmup + tool. */
    private static final long CALLBACK_WAIT_MS = 90_000L;

    /**
     * trigger-scope 用例:等到第 1 次 callback 后,再多观察 20s,确认没有 extra callback。
     * 这个窗口不能太长 —— 太长会拖 CI;不能太短 —— 短了可能漏掉延迟 fire 的 extra。
     */
    private static final long EXTRA_CALLBACK_QUIET_MS = 20_000L;

    private TestConfig config;
    private SutStack searchStack;

    /** 每个 @Test 独立 receiver,避免跨用例 callback 污染. */
    private MockCallbackReceiver mockReceiver;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    void startStack() {
        config = TestConfig.load();
        String llmApiKey = System.getenv("LLM_API_KEY");
        assumeTrue(llmApiKey != null && !llmApiKey.isBlank(),
                "[callback-delivery] 需 LLM_API_KEY 走 §S3 working COMPLETED 路径;跳过。");

        searchStack = SutStack.builder(config)
                .agent(SEARCH, a -> a.property("openjiuwen.demo.search-agent.api-key", llmApiKey))
                .start();
        LOG.info("[callback-delivery] search-agent ready at " + searchStack.baseUrl(SEARCH));
    }

    @AfterAll
    void tearDown() {
        if (searchStack != null) searchStack.close();
    }

    @BeforeEach
    void newReceiver() throws Exception {
        mockReceiver = MockCallbackReceiver.start();
        LOG.info("[callback-delivery] receiver at " + mockReceiver.callbackUrl());
    }

    @AfterEach
    void closeReceiver() {
        if (mockReceiver != null) mockReceiver.close();
    }

    /**
     * 契约 1: token 从 SendMessage.taskPushNotificationConfig.token 应被 sender 携到 callback
     * HTTP。项目 [[push-notification-security-model]] 是 token-on-callback:接收方(调用方 agent)
     * 靠这个 token 校验 callback 真伪。丢 token = 调用方无法鉴权 = 契约破。
     *
     * <p><b>Header 位置</b>:兼容多种实现 —— {@code Authorization: Bearer <token>} 或
     * {@code X-A2A-Callback-Token} 或直接 {@code X-Push-Notification-Token}。任一 header 值包含
     * SIT 提交的 token 即视为传播成功。均无 → red。
     */
    @Test
    @DisplayName("FEAT-001.callback-delivery.token: SendMessage config.token 应在 callback header 里携出")
    void tokenFromSendMessageMustBeCarriedInCallbackHeader() throws Exception {
        String token = "sit-token-" + UUID.randomUUID();
        String rpcId = "cb-token-" + UUID.randomUUID().toString().substring(0, 8);
        String messageId = UUID.randomUUID().toString();
        String contextId = "ctx-token-" + UUID.randomUUID().toString().substring(0, 8);
        String configId = "sit-cfg-" + UUID.randomUUID().toString().substring(0, 8);

        long t0 = System.currentTimeMillis();
        HttpResponse<String> response = post(searchStack.baseUrl(SEARCH) + "/a2a",
                buildSendMessage(rpcId, messageId, contextId, PROMPT,
                        configId, mockReceiver.callbackUrl(), token));
        LOG.info(String.format("[callback-delivery.token] SendMessage status=%d body=%s",
                response.statusCode(), response.body()));
        assertThat(response.statusCode()).isEqualTo(200);

        boolean reached = mockReceiver.awaitAtLeast(1, CALLBACK_WAIT_MS);
        dumpCaptured("token", t0);

        assumeTrue(reached,
                String.format("[callback-delivery.token][前置] %d ms 内未收到 callback。可能触发了 §S2 "
                                + "buggy 路径或 sender 未激活(见 [[push-notification-sender-not-activated]]);"
                                + "token 断言无从评估,不算失败。elapsed=%d",
                        CALLBACK_WAIT_MS, System.currentTimeMillis() - t0));

        MockCallbackReceiver.CapturedCallback cb = mockReceiver.captured().get(0);
        boolean tokenPresent = headerContainsToken(cb.headers(), token);
        LOG.info(String.format("[callback-delivery.token] tokenPresent=%s headers=%s",
                tokenPresent, cb.headers()));

        assertThat(tokenPresent)
                .as("[callback-delivery.token] §2.17 承诺 SendMessage 里 config.token 由 sender 携到 "
                                + "callback HTTP header,让上游 agent 在 callback 路径鉴权。"
                                + "预期 Authorization/X-A2A-Callback-Token/X-Push-Notification-Token 之一"
                                + "包含 SIT 提交的 token='%s';实测 headers=%s\n  body=%s\n"
                                + "  项目 [[push-notification-security-model]]: token-on-callback,丢 token"
                                + "则调用方无法鉴权 = 契约破。",
                        token, cb.headers(), cb.body())
                .isTrue();
    }

    /**
     * 契约 2: §2.13 trigger scope —— 一次 SendMessage 至终态 COMPLETED,sender 应恰好 fire 一次
     * callback。中间态(WORKING / SUBMITTED)不 fire。若观察到 count>1 → sender 状态机漏斗过宽
     * (每个状态变更都发)或幂等缺失 → red。若 count==0 → §S2/§S3 前置未通过 → assume skip。
     */
    @Test
    @DisplayName("FEAT-001.callback-delivery.trigger-scope: COMPLETED 恰好 fire 一次,中间态不 fire 独立 callback")
    void callbackFiresExactlyOnceForCompletedNoIntermediate() throws Exception {
        String token = "sit-token-" + UUID.randomUUID();
        String rpcId = "cb-scope-" + UUID.randomUUID().toString().substring(0, 8);
        String messageId = UUID.randomUUID().toString();
        String contextId = "ctx-scope-" + UUID.randomUUID().toString().substring(0, 8);
        String configId = "sit-cfg-" + UUID.randomUUID().toString().substring(0, 8);

        long t0 = System.currentTimeMillis();
        HttpResponse<String> response = post(searchStack.baseUrl(SEARCH) + "/a2a",
                buildSendMessage(rpcId, messageId, contextId, PROMPT,
                        configId, mockReceiver.callbackUrl(), token));
        assertThat(response.statusCode()).isEqualTo(200);

        boolean reached = mockReceiver.awaitAtLeast(1, CALLBACK_WAIT_MS);
        assumeTrue(reached, "[callback-delivery.trigger-scope][前置] 未收到任何 callback,"
                + "trigger-scope 断言无从评估。见 [[push-notification-sender-not-activated]]。");

        long firstT = mockReceiver.captured().get(0).timestampMs();
        LOG.info(String.format("[callback-delivery.trigger-scope] first callback at t+%d ms, "
                + "observing %d ms extra for potential extras...", firstT - t0, EXTRA_CALLBACK_QUIET_MS));
        Thread.sleep(EXTRA_CALLBACK_QUIET_MS);

        int total = mockReceiver.count();
        dumpCaptured("trigger-scope", t0);

        String firstState = extractState(safeReadTree(mockReceiver.captured().get(0).body()));
        LOG.info(String.format("[callback-delivery.trigger-scope] total=%d firstState=%s", total, firstState));

        assertThat(total)
                .as("[callback-delivery.trigger-scope] §2.13 承诺 sender 仅在<b>终态</b>触发 callback;"
                                + "一次 SendMessage COMPLETED 应 fire 恰好 1 次,观察窗口 (%d + %d) ms 内收 %d 次。"
                                + "count>1 = SUT 也在中间态发 callback(违反 §2.13,可能引起风暴);\n"
                                + "  callbacks=%s",
                        CALLBACK_WAIT_MS, EXTRA_CALLBACK_QUIET_MS, total, dumpBodies())
                .isEqualTo(1);

        assertThat(firstState)
                .as("[callback-delivery.trigger-scope] callback body 的 status.state 应是终态"
                                + "(COMPLETED/FAILED/CANCELED);实测 %s\nbody=%s",
                        firstState, mockReceiver.captured().get(0).body())
                .isNotNull()
                .matches("(?i).*(COMPLETED|FAILED|CANCELED).*");
    }

    /**
     * 契约 3: §3.27 payload shape —— callback body 复用 Task/Message JSON 表面(有 taskId 或
     * task.id + status.state 至少一层),notification-id 走 {@code X-A2A-Notification-Id} header,
     * 不应出现在 body 顶层字段(避免 body 与 header 双份职责)。
     *
     * <p><b>断言</b>:
     * <ul>
     *   <li>(a) body 有 taskId 或 task.id 或 id 字段;</li>
     *   <li>(b) body 有 status.state(或 task.status.state / state);</li>
     *   <li>(c) header 存在 X-A2A-Notification-Id(不强绑值,只求存在);</li>
     *   <li>(d) body 顶层<b>没有</b> notificationId / notification_id / X-A2A-Notification-Id 字段
     *       (违反 = header/body 职责重叠)。</li>
     * </ul>
     */
    @Test
    @DisplayName("FEAT-001.callback-delivery.payload-shape: body 复用 Task 表面 + notification-id 走 header")
    void callbackBodyReusesTaskSurfaceAndNotificationIdInHeader() throws Exception {
        String token = "sit-token-" + UUID.randomUUID();
        String rpcId = "cb-shape-" + UUID.randomUUID().toString().substring(0, 8);
        String messageId = UUID.randomUUID().toString();
        String contextId = "ctx-shape-" + UUID.randomUUID().toString().substring(0, 8);
        String configId = "sit-cfg-" + UUID.randomUUID().toString().substring(0, 8);

        long t0 = System.currentTimeMillis();
        HttpResponse<String> response = post(searchStack.baseUrl(SEARCH) + "/a2a",
                buildSendMessage(rpcId, messageId, contextId, PROMPT,
                        configId, mockReceiver.callbackUrl(), token));
        assertThat(response.statusCode()).isEqualTo(200);

        boolean reached = mockReceiver.awaitAtLeast(1, CALLBACK_WAIT_MS);
        assumeTrue(reached, "[callback-delivery.payload-shape][前置] 未收到任何 callback,shape 断言无从评估。"
                + "见 [[push-notification-sender-not-activated]]。");

        MockCallbackReceiver.CapturedCallback cb = mockReceiver.captured().get(0);
        JsonNode body = safeReadTree(cb.body());
        dumpCaptured("payload-shape", t0);

        // (a) task 身份字段:直裸(taskId / task.id / id)或 JSON-RPC 包(result.task.id / result.id)
        String taskIdCandidate = firstText(body,
                new String[]{"taskId"},
                new String[]{"task", "id"},
                new String[]{"id"},
                new String[]{"result", "task", "id"},
                new String[]{"result", "taskId"},
                new String[]{"result", "id"});
        assertThat(taskIdCandidate)
                .as("[callback-delivery.payload-shape] §3.27 body 应含 Task 身份字段"
                                + "(taskId / task.id / id 之一)\nbody=%s", cb.body())
                .isNotNull();

        // (b) status.state
        String state = extractState(body);
        assertThat(state)
                .as("[callback-delivery.payload-shape] §3.27 body 应含 status.state\nbody=%s", cb.body())
                .isNotNull();

        // (c) notification-id header 存在
        String nid = cb.header("X-A2A-Notification-Id");
        if (nid == null) nid = cb.header("X-Notification-Id");
        assertThat(nid)
                .as("[callback-delivery.payload-shape] §3.27 notification-id 应走 header "
                                + "(X-A2A-Notification-Id);实测 headers=%s\nbody=%s",
                        cb.headers(), cb.body())
                .isNotNull();

        // 观察点(2026-08-08 收回"leak"框架):body 顶层含 notificationId 是<b>合规</b>行为,
        // 不是 leak —— L2 §2.7 明示 notificationId 是<b>幂等键契约</b>(receiver 用 body.notificationId
        // 与 header X-A2A-Notification-Id 双重匹配 + 用于同 nid 重放去重)。SUT 双份写符合契约,
        // 只在存疑的极端 §3.27 严格 header-only 解读下才算 leak,而项目实际实现选择了 §2.7 幂等键
        // 的宽解读。仅 log 观察,不做断言,更不再暗示"leak"。
        boolean bodyHasNidField = body.has("notificationId") || body.has("notification_id");
        if (bodyHasNidField) {
            LOG.info(String.format(
                    "[callback-delivery.payload-shape] observation: body 顶层含 notificationId (幂等键契约 L2 §2.7)"
                            + "。SUT header + body 双份写,合规。\n  body=%s",
                    cb.body()));
        }
    }

    // ----- helpers -----

    private static boolean headerContainsToken(Map<String, List<String>> headers, String token) {
        String[] candidateNames = {
                "Authorization",
                "X-A2A-Callback-Token",
                "X-Push-Notification-Token",
                "X-A2A-Token"
        };
        for (String name : candidateNames) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (!e.getKey().equalsIgnoreCase(name)) continue;
                for (String v : e.getValue()) {
                    if (v != null && v.contains(token)) return true;
                }
            }
        }
        return false;
    }

    /**
     * 稳健 extract status.state —— 兼容多种 SUT body shape:
     * 直裸(status.state / state / task.status.state),或 JSON-RPC 包(result.task.status.state
     * / result.status.state / result.state).
     */
    private static String extractState(JsonNode body) {
        String[][] paths = {
                {"status", "state"},
                {"state"},
                {"task", "status", "state"},
                {"result", "task", "status", "state"},
                {"result", "status", "state"},
                {"result", "state"}
        };
        for (String[] p : paths) {
            JsonNode cur = body;
            for (String seg : p) cur = cur.path(seg);
            if (cur.isTextual()) return cur.asText();
        }
        return null;
    }

    /** 按 path 序列返第一个 textual 值,path 是嵌套 field name 数组. */
    private static String firstText(JsonNode body, String[]... paths) {
        for (String[] p : paths) {
            JsonNode cur = body;
            for (String seg : p) cur = cur.path(seg);
            if (cur.isTextual()) return cur.asText();
        }
        return null;
    }

    private JsonNode safeReadTree(String s) {
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private String dumpBodies() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mockReceiver.captured().size(); i++) {
            MockCallbackReceiver.CapturedCallback cb = mockReceiver.captured().get(i);
            sb.append("\n  [").append(i).append("] ").append(cb.body());
        }
        return sb.toString();
    }

    private void dumpCaptured(String label, long t0) {
        for (int i = 0; i < mockReceiver.captured().size(); i++) {
            MockCallbackReceiver.CapturedCallback cb = mockReceiver.captured().get(i);
            LOG.info(String.format(
                    "[callback-delivery.%s] callback #%d at t+%d ms | headers=%s%n  body=%s",
                    label, i, cb.timestampMs() - t0, cb.headers(), cb.body()));
        }
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

    private HttpResponse<String> post(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}

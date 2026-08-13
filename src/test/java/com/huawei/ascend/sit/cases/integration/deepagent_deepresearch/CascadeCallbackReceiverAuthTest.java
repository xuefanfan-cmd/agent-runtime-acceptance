package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.mock.MockSearchAgentServer;
import com.huawei.ascend.sit.mock.MockSearchAgentServer.CallbackBehavior;
import com.huawei.ascend.sit.mock.MockSearchAgentServer.CapturedPushConfig;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-001.cascade-callback-receiver-auth — 用户 2026-08-08 明示的完整级联 auth 验证链路。
 *
 * <p><b>链路模型</b>:
 * <pre>{@code
 *   caller (test) ──SendMessage(NO pushConfig)──▶ deep-research (PUSH=TRUE)
 *                                                       │
 *                                                       │ SendMessage(WITH pushConfig{
 *                                                       │    url=deep-research/callback,
 *                                                       │    id=<nid>, token=<T>})
 *                                                       ▼
 *                                             MockSearchAgentServer (PUSH=false)
 *                                                       │ 捕获 pushConfig
 *                                                       │ 立即返 Task skeleton COMPLETED
 *                                                       │
 *                                                       ▼ (async 200ms)
 *                                             POST /a2a/push-notifications/callback
 *                                             (headers X-A2A-Notification-Id + Authorization,
 *                                              按 CallbackBehavior 变体)
 *                                                       │
 *                                                       ▼
 *                                             deep-research receiver
 *                                                 ├─ HAPPY:         200/202
 *                                                 ├─ WRONG_TOKEN:   401/403
 *                                                 ├─ MISSING_TOKEN: 401/403
 *                                                 └─ WRONG_TASK_ID: 404 binding-not-found
 * }</pre>
 *
 * <p><b>Binding 依据</b>(dev-team 2026-08-08 明确):receiver 侧真正 binding lookup 用
 * {@code callback.result.task.id == shadow._remote_batch.members[].remoteTaskId};
 * {@code notificationId} 仅参与幂等/冲突检查,不用于 binding。所以 binding-not-found 显性化
 * 必须篡改 callback body 里的 {@code result.task.id}(即 WRONG_TASK_ID 场景),而非篡改 nid。
 *
 * <p><b>验证点</b>:
 * <ol>
 *   <li>deep-research 是否<b>正确构造 outbound pushConfig</b>:URL 指回 deep-research 自身
 *       {@code /a2a/push-notifications/callback},id/token 非空(见 {@code happyPath} 中的
 *       {@code pushConfig != null && url ends-with callback path});</li>
 *   <li>mock 用正确 token+nid fire 时 deep-research 应<b>接受</b>(happy);</li>
 *   <li>token/nid 变体时 deep-research 应<b>按层次拒</b>(spec §2.17 token-on-callback
 *       [[push-notification-security-model]])。</li>
 * </ol>
 *
 * <p><b>依赖</b>:真 LLM(deep-research 需 LLM 决策触发 tool_call → search)。
 * {@code LLM_API_KEY} 缺 → {@code assumeTrue} skip。CI 默认不满足,标 {@code @Tag("manual")}。
 *
 * <p><b>受体激活</b>:2026-08-08 探针确认,deep-research callback receiver 端点激活需
 * {@code DEEP_RESEARCH_PUSH_NOTIFICATIONS=true} + {@code DEEP_RESEARCH_PUBLIC_URL} 同时给。
 * PUBLIC_URL 需与随机端口一致,通过 sys-prop 预锁端口。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Tag("manual")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.cascade-callback-receiver-auth: caller → deep-research(PUSH) → mock-search → callback 反向 token 校验")
class CascadeCallbackReceiverAuthTest {

    private static final Logger LOG = Logger.getLogger(CascadeCallbackReceiverAuthTest.class.getName());
    private static final String DEEP = "deep-research";
    private static final String DEEP_PORT_SYSPROP = "sut-agents-deep-research-port";
    private static final String PROMPT = "帮我搜索 2026 年 7 月全球黄金价格盘中最高价";
    private static final long CALLBACK_OBSERVE_MS = 90_000L;
    private static final long POLL_INTERVAL_MS = 500L;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("cascade.happy: mock 用正确 token+nid → deep-research callback 端点 200/202 接受")
    void happyPath_callbackAccepted() throws Exception {
        ScenarioResult r = runScenario(CallbackBehavior.HAPPY);
        assertPushConfigWellFormed(r);
        assertThat(r.callbackStatuses())
                .as("[cascade.happy] deep-research 应接受 mock 用正确 token+nid fire 的 callback;"
                                + "实测 %s\n  pushConfigs=%s\n  callbackResults=%s",
                        r.callbackStatuses(), r.pushConfigs(), r.callbackResults())
                .contains(200).doesNotContain(401, 403, 404);
    }

    @Test
    @DisplayName("cascade.wrong-token: mock 用错 token → deep-research callback 端点 401/403 拒")
    void wrongTokenRejected() throws Exception {
        ScenarioResult r = runScenario(CallbackBehavior.WRONG_TOKEN);
        assertPushConfigWellFormed(r);
        assertThat(r.callbackStatuses())
                .as("[cascade.wrong-token] §2.17 承诺 receiver 应校验 Authorization token;错 token 应 401/403,"
                                + "实测 %s\n  pushConfigs=%s\n  callbackResults=%s\n"
                                + "若返 200/202 → 静默接受错 token = token-on-callback 未实施 spec-gap red-first",
                        r.callbackStatuses(), r.pushConfigs(), r.callbackResults())
                .containsAnyOf(401, 403).doesNotContain(200, 202);
    }

    @Test
    @DisplayName("cascade.missing-token: mock 不携 Authorization → deep-research callback 端点 401/403 拒")
    void missingTokenRejected() throws Exception {
        ScenarioResult r = runScenario(CallbackBehavior.MISSING_TOKEN);
        assertPushConfigWellFormed(r);
        assertThat(r.callbackStatuses())
                .as("[cascade.missing-token] §2.17 承诺 receiver 应拒无 Authorization 的 callback;应 401/403,"
                                + "实测 %s\n  pushConfigs=%s\n  callbackResults=%s\n"
                                + "若返 200/202 → 静默接受无 auth = token-on-callback 未实施 spec-gap red-first",
                        r.callbackStatuses(), r.pushConfigs(), r.callbackResults())
                .containsAnyOf(401, 403).doesNotContain(200, 202);
    }

    @Test
    @DisplayName("cascade.wrong-task-id: mock 篡改 callback body 里 result.task.id → deep-research 404 binding-not-found")
    void wrongTaskIdBindingNotFound() throws Exception {
        ScenarioResult r = runScenario(CallbackBehavior.WRONG_TASK_ID);
        assertPushConfigWellFormed(r);
        assertThat(r.callbackStatuses())
                .as("[cascade.wrong-task-id] dev-team 明确 receiver binding 用 callback.result.task.id "
                                + "== shadow._remote_batch.members[].remoteTaskId;篡改 callback body 里 "
                                + "task.id 应 404 binding-not-found,实测 %s\n  pushConfigs=%s\n  callbackResults=%s\n"
                                + "若返 200/202 → upstream 未拒绝错 taskId,binding 侧防御 spec-gap",
                        r.callbackStatuses(), r.pushConfigs(), r.callbackResults())
                .contains(404).doesNotContain(200, 202);
    }

    // ------ helpers ------

    private record ScenarioResult(
            List<CapturedPushConfig> pushConfigs,
            List<MockSearchAgentServer.CallbackResult> callbackResults) {
        List<Integer> callbackStatuses() {
            return callbackResults.stream().map(MockSearchAgentServer.CallbackResult::status).toList();
        }
    }

    private ScenarioResult runScenario(CallbackBehavior behavior) throws Exception {
        String llmApiKey = System.getenv("LLM_API_KEY");
        assumeTrue(llmApiKey != null && !llmApiKey.isBlank(),
                "LLM_API_KEY 缺,deep-research 无法决策 tool_call,跳过。");

        TestConfig config = TestConfig.load();
        int deepPort;
        try (ServerSocket ss = new ServerSocket(0)) { deepPort = ss.getLocalPort(); }
        String publicUrl = "http://127.0.0.1:" + deepPort;
        System.setProperty(DEEP_PORT_SYSPROP, String.valueOf(deepPort));

        // DEEP_RESEARCH_CALLBACK_TOKEN: deep-research 侧 shared secret(application.yml
        // callback-auth.bearer-token = ${DEEP_RESEARCH_CALLBACK_TOKEN:})。deep-research 调
        // sub-agent 时 pushConfig.token = 这里注入的值;sub-agent 反向 callback 时应 echo 回来。
        // 之前空 token 观察到 pushConfig.token="" 是 env 没设的静态假象,不是 SUT bug。
        String callbackToken = "test-cascade-token-" + UUID.randomUUID();

        try (MockSearchAgentServer mock = MockSearchAgentServer.builder()
                .callbackBehavior(behavior)
                .callbackDelayMs(300L)
                .start();
             SutStack stack = SutStack.builder(config)
                     .agent(DEEP, a -> a
                             .env("SEARCH_AGENT_URL", mock.baseUrl())
                             .env("VERIFY_AGENT_URL", "http://127.0.0.1:1")
                             .env("DEEP_RESEARCH_PUSH_NOTIFICATIONS", "true")
                             .env("DEEP_RESEARCH_PUBLIC_URL", publicUrl)
                             .env("DEEP_RESEARCH_CALLBACK_TOKEN", callbackToken)
                             .property("openjiuwen.demo.deep-research.api-key", llmApiKey))
                     .start()) {

            LOG.info(String.format("[cascade:%s] mock @ %s | deep-research @ %s | PUBLIC_URL=%s",
                    behavior, mock.baseUrl(), stack.baseUrl(DEEP), publicUrl));

            // caller 侧 SendMessage —— 不带 pushConfig,不做 blocking 等待
            String contextId = "ctx-cascade-" + UUID.randomUUID().toString().substring(0, 8);
            String messageId = UUID.randomUUID().toString();
            String rpcId = "cascade-" + behavior + "-" + UUID.randomUUID().toString().substring(0, 6);
            String reqBody = String.format(
                    "{\"jsonrpc\":\"2.0\",\"id\":\"%s\",\"method\":\"SendMessage\",\"params\":{"
                            + "\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"%s\","
                            + "\"contextId\":\"%s\",\"parts\":[{\"text\":\"%s\"}]}}}",
                    rpcId, messageId, contextId, PROMPT);

            HttpResponse<String> callerResp = post(stack.baseUrl(DEEP) + "/a2a", reqBody);
            LOG.info(String.format("[cascade:%s] caller SendMessage status=%d body=%s",
                    behavior, callerResp.statusCode(), callerResp.body()));

            // 等 mock 观察 pushConfig + callbackResult
            long deadline = System.currentTimeMillis() + CALLBACK_OBSERVE_MS;
            while (System.currentTimeMillis() < deadline) {
                if (!mock.capturedPushConfigs().isEmpty() && !mock.callbackResults().isEmpty()) break;
                Thread.sleep(POLL_INTERVAL_MS);
            }

            LOG.info(String.format(
                    "[cascade:%s] observation: pushConfigs=%s | callbackResults=%s",
                    behavior, mock.capturedPushConfigs(), mock.callbackResults()));
            return new ScenarioResult(mock.capturedPushConfigs(), mock.callbackResults());
        } finally {
            System.clearProperty(DEEP_PORT_SYSPROP);
        }
    }

    /**
     * deep-research 应在调 sub-agent 时携合规 pushConfig:URL 指回自己 /a2a/push-notifications/callback,
     * id + token 非空。任一缺 → 上游触发不了 callback → chain 断,不算 receiver 侧问题。
     */
    private static void assertPushConfigWellFormed(ScenarioResult r) {
        assertThat(r.pushConfigs())
                .as("[cascade] deep-research 应给 sub-agent 附带 pushConfig(§2.17 outbound),实测 empty\n"
                                + "  callbackResults=%s", r.callbackResults())
                .isNotEmpty();
        CapturedPushConfig cfg = r.pushConfigs().get(0);
        assertThat(cfg.url())
                .as("[cascade] pushConfig.url 应指回 deep-research 自己 /a2a/push-notifications/callback,实测 %s",
                        cfg.url())
                .isNotNull()
                .contains("/a2a/push-notifications/callback");
        assertThat(cfg.id()).as("[cascade] pushConfig.id 应非空").isNotNull().isNotBlank();
        assertThat(cfg.token()).as("[cascade] pushConfig.token 应非空(token-on-callback 前提)").isNotNull().isNotBlank();
    }

    private HttpResponse<String> post(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode safeReadTree(String s) {
        try { return mapper.readTree(s); } catch (Exception e) { return mapper.createObjectNode(); }
    }
}

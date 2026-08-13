package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.mock.MockCallbackReceiver;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * FEAT-001.push-notification-direction-probe — 探针:验证 {@code SEARCH_AGENT_PUSH_NOTIFICATIONS}
 * 到底影响 <b>outbound(agent 作 caller,自己发起对下游 push)</b> 还是 <b>inbound(runtime 收到
 * 上游内联 pushNotificationConfig 时是否 fire sender)</b>。
 *
 * <p><b>假设</b>(用户 2026-08-08 澄清):{@code *_PUSH_NOTIFICATIONS} 是 outbound-only。
 * 不管其 true/false,只要上游 SendMessage 带 inline {@code taskPushNotificationConfig.url},
 * runtime 都应 fire sender POST 到该 URL(inbound 契约由 runtime 层保证)。
 *
 * <p><b>探针方案</b>:同一个 search-agent,两种 stack 配置对比。
 * <ul>
 *   <li>Off:<b>不</b>设 SEARCH_AGENT_PUSH_NOTIFICATIONS(default false);</li>
 *   <li>On:显式 SEARCH_AGENT_PUSH_NOTIFICATIONS=true。</li>
 * </ul>
 *
 * <p><b>观测点</b>:
 * <ol>
 *   <li>AgentCard {@code capabilities.pushNotifications} 值(true / false / null);</li>
 *   <li>SendMessage 带 inline callback URL 后,MockCallbackReceiver 是否在 60s 内收到 callback。</li>
 * </ol>
 *
 * <p><b>结论矩阵(观测后填充)</b>:
 * <pre>
 *   Off  → capabilities=? , callback=? → 说明 env 语义 = ?
 *   On   → capabilities=? , callback=? → 说明 env 语义 = ?
 * </pre>
 *
 * <p><b>不做严格断言</b>:每个 @Test 都 always-green,信号在 log 里。目的是<b>拿到真实 SUT
 * 行为矩阵</b>,再回来对齐用例设计。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Tag("manual")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.push-notification-direction-probe: SEARCH_AGENT_PUSH_NOTIFICATIONS outbound vs inbound 语义探针")
class PushNotificationDirectionProbeTest {

    private static final Logger LOG = Logger.getLogger(PushNotificationDirectionProbeTest.class.getName());
    private static final String SEARCH = "search";

    /** 简短确定性 prompt,让 search-agent 快速 COMPLETED,进入 sender 触发窗口。 */
    private static final String PROMPT = "帮我搜索 2026 年 7 月全球黄金价格盘中最高价";

    /** 收 callback 的等待预算 —— 单个 search-agent 端到端 15-30s 通常够;放宽到 60s. */
    private static final long CALLBACK_WAIT_MS = 60_000L;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("FEAT-001.probe.push-off: search 默认 stack(env 不设)+ inline callback URL → 观测 capabilities + sender fire")
    void probe_defaultStack_pushOff() throws Exception {
        runProbe("push-OFF (default)", b -> {
            /* no push-notifications env — default false */
        });
    }

    @Test
    @DisplayName("FEAT-001.probe.push-on: search 显式 SEARCH_AGENT_PUSH_NOTIFICATIONS=true + inline callback URL → 观测 capabilities + sender fire")
    void probe_optInStack_pushOn() throws Exception {
        runProbe("push-ON (opt-in)", b -> b.env("SEARCH_AGENT_PUSH_NOTIFICATIONS", "true"));
    }

    private void runProbe(String label, Consumer<SutStack.AgentBuilder> extraConfig) throws Exception {
        TestConfig config = TestConfig.load();
        String llmApiKey = System.getenv("LLM_API_KEY");

        try (MockCallbackReceiver receiver = MockCallbackReceiver.start();
             SutStack stack = SutStack.builder(config)
                     .agent(SEARCH, a -> {
                         a.property("openjiuwen.demo.search-agent.api-key", llmApiKey);
                         extraConfig.accept(a);
                     })
                     .start()) {

            LOG.info(String.format("[direction-probe:%s] search stack ready at %s | callback URL = %s",
                    label, stack.baseUrl(SEARCH), receiver.callbackUrl()));

            AgentCard card = stack.client(SEARCH).getAgentCard();
            boolean pushCap = card.capabilities() != null && card.capabilities().pushNotifications();
            LOG.info(String.format("[direction-probe:%s] AgentCard capabilities.pushNotifications = %s",
                    label, pushCap));

            String contextId = "ctx-probe-" + UUID.randomUUID().toString().substring(0, 8);
            String messageId = UUID.randomUUID().toString();
            String configId = "sit-cfg-" + UUID.randomUUID().toString().substring(0, 8);
            String configToken = "sit-token-" + UUID.randomUUID().toString().substring(0, 8);

            String body = String.format(
                    "{\"jsonrpc\":\"2.0\",\"id\":\"probe-%s\","
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
                    messageId, contextId, PROMPT,
                    configId, receiver.callbackUrl(), configToken);

            long t0 = System.currentTimeMillis();
            HttpResponse<String> response = post(stack.baseUrl(SEARCH) + "/a2a", body);
            long sendElapsed = System.currentTimeMillis() - t0;
            LOG.info(String.format(
                    "[direction-probe:%s] SendMessage returned in %d ms | status=%d%n"
                            + "  body=%s",
                    label, sendElapsed, response.statusCode(), response.body()));

            JsonNode initial = mapper.readTree(response.body());
            boolean initialHasError = initial.has("error");
            LOG.info(String.format("[direction-probe:%s] initial response hasError=%s",
                    label, initialHasError));

            LOG.info(String.format("[direction-probe:%s] awaiting callback up to %d ms ...",
                    label, CALLBACK_WAIT_MS));
            boolean reached = receiver.awaitAtLeast(1, CALLBACK_WAIT_MS);
            long totalElapsed = System.currentTimeMillis() - t0;

            LOG.info(String.format(
                    "[direction-probe:%s] ===== RESULT =====%n"
                            + "  label                = %s%n"
                            + "  agentCard.pushNotif  = %s%n"
                            + "  initialResponseError = %s%n"
                            + "  callbackReached      = %s%n"
                            + "  callbackCount        = %d%n"
                            + "  totalElapsed         = %d ms%n"
                            + "======================",
                    label, label, pushCap, initialHasError,
                    reached, receiver.count(), totalElapsed));

            for (int i = 0; i < receiver.captured().size(); i++) {
                MockCallbackReceiver.CapturedCallback cb = receiver.captured().get(i);
                LOG.info(String.format(
                        "[direction-probe:%s] callback #%d at t+%d ms | headers=%s | body=%s",
                        label, i, cb.timestampMs() - t0, cb.headers(), cb.body()));
            }
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

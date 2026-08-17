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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-001 矩阵 <b>D6</b>（sender 半面）—— 投递失败后：<b>Task 终态不变 + 重试保持稳定 notification id</b>。
 *
 * <p><b>Spec 依据</b>：§5.1.4「Callback 投递失败不得回滚或改变 Task 终态；实现<b>可以</b>重试同一
 * notification，但必须保持 notification id 稳定」；§5.1.9「callback delivery failure 不得改变 Task 终态；
 * 必须保留可观察的通知投递失败事实，并允许按稳定 notification id 重试」。
 *
 * <p><b>接收侧幂等半面</b>由 {@code PushNotificationIdempotencyTest} 承接；本类只测 sender 半面。
 *
 * <p><b>故障注入</b>：{@link MockCallbackReceiver#failFirst(int, int)} 让首个投递收 500（仍捕获），
 * 之后恢复 200。sender=search 单节点拓扑（终态确定性可达，同 D2/D3 口径）。
 *
 * <p><b>断言分层</b>（严格贴 spec 的 MUST/MAY 边界）：
 * <ol>
 *   <li><b>硬</b>：任务达终态 + 首个投递被 500 拒后，{@code GetTask} 终态<b>不变</b>（投递失败不改终态）；</li>
 *   <li><b>条件硬</b>：若观察窗内发生重试（POST≥2），全部投递的 {@code X-A2A-Notification-Id}
 *       必须一致（id 稳定）；重试是 MAY——未重试<b>不判 FAIL</b>，仅记录（供与开发确认实现策略）。</li>
 * </ol>
 *
 * <p><b>Tag</b>：manual —— 依赖本地 search jar + 真实 LLM/检索。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Tag("manual")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.callback-retry-after-delivery-failure (D6): 投递失败不改终态；若重试则 notification id 稳定")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CallbackRetryAfterDeliveryFailureTest {

    private static final Logger LOG = Logger.getLogger(CallbackRetryAfterDeliveryFailureTest.class.getName());
    private static final String SEARCH = "search";
    private static final String USER_INPUT = "你好,到deepseek官网查询下DeepSeek-V3 上下文长度多少 tokens。";

    private static final long TERMINAL_POLL_TIMEOUT_MS = 90_000;
    private static final long FIRST_DELIVERY_WAIT_MS = 60_000;
    /** 首投失败后观察重试的窗口（重试策略未知，给足退避余量）。 */
    private static final long RETRY_OBSERVE_MS = 45_000;
    private static final long POLL_INTERVAL_MS = 2_000;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private TestConfig config;
    private SutStack searchStack;
    private MockCallbackReceiver receiver;

    @BeforeAll
    void startStack() throws Exception {
        config = TestConfig.load();
        receiver = MockCallbackReceiver.start();
        receiver.failFirst(1, 500);
        LOG.info("[d6-retry] receiver at " + receiver.callbackUrl() + " (first POST -> 500)");
        searchStack = SutStack.builder(config)
                .agent(SEARCH, a -> a.property("openjiuwen.demo.search-agent.api-key",
                        System.getenv("LLM_API_KEY")))
                .start();
        LOG.info("[d6-retry] search ready at " + searchStack.baseUrl(SEARCH));
    }

    @AfterAll
    void tearDown() {
        if (searchStack != null) searchStack.close();
        if (receiver != null) receiver.close();
    }

    @Test
    @DisplayName("FEAT-001.callback-retry (D6-sender): 首投 500 后终态不变；若重试则 X-A2A-Notification-Id 稳定")
    void deliveryFailureKeepsTerminalStateAndRetryKeepsStableId() throws Exception {
        String contextId = "ctx-d6-retry-" + UUID.randomUUID().toString().substring(0, 8);
        String body = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"d6-%s\",\"method\":\"SendMessage\",\"params\":{"
                        + "\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"%s\",\"contextId\":\"%s\","
                        + "\"parts\":[{\"text\":\"%s\"}]},"
                        + "\"configuration\":{\"taskPushNotificationConfig\":{\"id\":\"pn-%s\",\"url\":\"%s\","
                        + "\"token\":\"sit-token-%s\"},\"returnImmediately\":true}}}",
                UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID(), contextId, USER_INPUT,
                UUID.randomUUID().toString().substring(0, 8), receiver.callbackUrl(),
                UUID.randomUUID().toString().substring(0, 8));
        HttpResponse<String> ack = post("/a2a", body);
        assertThat(ack.statusCode()).as("SendMessage 应 200\n%s", ack.body()).isEqualTo(200);
        String taskId = extractTaskId(mapper.readTree(ack.body()));
        assumeTrue(taskId != null && !taskId.isBlank(), "未取到 taskId，INCONCLUSIVE");

        // 前置：达终态（sender=search 确定性收束）。
        String terminal = pollState(taskId, TERMINAL_POLL_TIMEOUT_MS, this::isTerminal);
        assumeTrue(terminal != null && isTerminal(terminal),
                "任务未达终态（最后观测=" + terminal + "），投递失败注入无从谈起，INCONCLUSIVE");
        LOG.info("[d6-retry] terminal=" + terminal);

        // 首投必须到达（并被注入 500 拒绝）。
        assertThat(receiver.awaitAtLeast(1, FIRST_DELIVERY_WAIT_MS))
                .as("终态 %s 后 %d ms 内应有首次投递（D2 已证通路），实收 %d",
                        terminal, FIRST_DELIVERY_WAIT_MS, receiver.count())
                .isTrue();
        assertThat(receiver.respondedStatuses().get(0))
                .as("注入自检：首投应被 mock 以 500 拒绝").isEqualTo(500);
        LOG.info("[d6-retry] first delivery rejected with 500 at count=" + receiver.count());

        // 硬断言 1（§5.1.4/§5.1.9）：投递失败不得改变 Task 终态。
        String after = pollState(taskId, 10_000, s -> true);
        assertThat(after)
                .as("首投失败后 GetTask 终态不得改变：之前=%s 现在=%s", terminal, after)
                .isEqualTo(terminal);

        // 条件硬断言 2：观察重试；若发生，notification id 必须稳定（重试本身是 MAY，不发生不判 FAIL）。
        boolean retried = receiver.awaitAtLeast(2, RETRY_OBSERVE_MS);
        if (retried) {
            Set<String> ids = new HashSet<>();
            receiver.captured().forEach(cb -> ids.add(cb.header("X-A2A-Notification-Id")));
            LOG.info("[d6-retry] retry observed: total=" + receiver.count()
                    + " statuses=" + receiver.respondedStatuses() + " ids=" + ids);
            assertThat(ids)
                    .as("§5.1.4: 重试必须保持稳定 notification id；实测 id 集合=%s", ids)
                    .hasSize(1);
            assertThat(ids.iterator().next()).isNotBlank();
            // 终态在重试后仍不变。
            String afterRetry = pollState(taskId, 10_000, s -> true);
            assertThat(afterRetry).as("重试后终态仍不得改变").isEqualTo(terminal);
        } else {
            LOG.info("[d6-retry] 观察窗 " + RETRY_OBSERVE_MS + " ms 内未发生重试。spec 口径为 MAY（\"实现可以重试\"），"
                    + "不判 FAIL；记录事实供与开发确认投递失败的重试/可观测策略（§5.1.9 要求保留可观察的失败事实）。");
        }
    }

    // —— helpers ——

    private boolean isTerminal(String s) {
        return s != null && (s.contains("COMPLETED") || s.contains("FAILED")
                || s.contains("CANCELED") || s.contains("REJECTED"));
    }

    private String pollState(String taskId, long timeoutMs,
                             java.util.function.Predicate<String> until) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> resp = post("/a2a", String.format(
                    "{\"jsonrpc\":\"2.0\",\"id\":\"gt-%s\",\"method\":\"GetTask\",\"params\":{\"id\":\"%s\"}}",
                    UUID.randomUUID().toString().substring(0, 8), taskId));
            if (resp.statusCode() == 200) {
                JsonNode root = mapper.readTree(resp.body());
                String s = root.path("result").path("task").path("status").path("state").asText(null);
                if (s == null) s = root.path("result").path("status").path("state").asText(null);
                if (s != null) {
                    last = s;
                    if (until.test(s)) return s;
                }
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        return last;
    }

    private static String extractTaskId(JsonNode root) {
        String a = root.path("result").path("task").path("id").asText(null);
        return a != null ? a : root.path("result").path("id").asText(null);
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(searchStack.baseUrl(SEARCH) + path))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}

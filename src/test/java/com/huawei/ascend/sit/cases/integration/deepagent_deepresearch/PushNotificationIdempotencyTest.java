package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-001.push-notification-idempotency — Callback receiver 幂等性契约.
 *
 * <p><b>Spec 依据</b>(L2 §2.7):
 * <ul>
 *   <li>{@code X-A2A-Notification-Id} header 与 body 里的 {@code notificationId} 必须一致,
 *     并作为幂等键;</li>
 *   <li>同 id + 同 payload 重投 → 允许 SUT 返 200/202(等价接受)或 409(显式声明 dedup);</li>
 *   <li>同 id + <b>不同</b> payload 重投 → 必须返 409 conflict,禁止覆盖前一份记录。</li>
 * </ul>
 *
 * <p><b>断言维度</b>:
 * <ol>
 *   <li>首次投递:200/202;</li>
 *   <li>相同 id + 相同 payload 重投:200/202 或 409(SUT 二选一,都合规);</li>
 *   <li>相同 id + 不同 payload 重投:必须 409,不允许 200/202(会造成 silent overwrite)。</li>
 * </ol>
 *
 * <p><b>为什么允许首轮 202</b>:receiver 可能异步处理,202 是标准"已接受待处理"信号;
 * 幂等语义关注"同 id 二次收到的 SUT 行为",不关注首轮同步 vs 异步的细节。
 *
 * <p><b>2026-08-08 修订</b>:{@link PushNotificationDirectionProbeTest} 实证 AgentCard 的
 * {@code capabilities.pushNotifications} 只是 outbound 声明位,<b>不代表 receiver 端点是否激活</b>。
 * 因此本用例不再按其值 {@code assumeTrue} skip;无条件跑,让 SUT 真实行为决定 —— 若 receiver
 * 端点未激活,test 会 red 显性化契约与实现不一致的 gap。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.push-notification-idempotency: X-A2A-Notification-Id 幂等 + 冲突返 409")
class PushNotificationIdempotencyTest extends BaseManagedStackTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final String CALLBACK_PATH = "/a2a/push-notifications/callback";
    private static final List<Integer> ACCEPTED = List.of(200, 202);
    private static final int CONFLICT = 409;

    private final HttpClient http = HttpClient.newHttpClient();

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // SUT jar 0.1.0 声明了 remote-agents[search-agent, verify-agent]，startup 会校验二者 URL 非空；
        // 本用例不打真实 sub-agent 链路，占位 URL 让 Spring bind 通过即可。
        return SutStack.builder(config)
                .agent(DEEP_RESEARCH, a -> a
                        .env("SEARCH_AGENT_URL", "http://127.0.0.1:1")
                        .env("VERIFY_AGENT_URL", "http://127.0.0.1:1"));
    }

    @Test
    @DisplayName("FEAT-001.push-notification-idempotency: 相同 id + 相同 payload → 二次也接受(200/202/409)")
    void sameNotificationIdSamePayloadIsIdempotent() throws Exception {
        // 2026-08-08 PushNotificationDirectionProbeTest 实证:AgentCard.capabilities.pushNotifications
        // 只是 outbound 声明位,不代表 receiver 端点是否激活。因此不再按其值 skip;无条件跑,让 SUT
        // 真实行为决定结果。
        String notificationId = UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();
        String body = callbackBody(notificationId, taskId, "TASK_STATE_WORKING");

        HttpResponse<String> first = postCallback(notificationId, body);
        assertThat(first.statusCode())
                .as("FEAT-001.push-notification-idempotency: 首次投递应 200/202\n"
                                + "status=%d body=%s", first.statusCode(), first.body())
                .isIn(ACCEPTED);

        HttpResponse<String> second = postCallback(notificationId, body);
        List<Integer> allowed = List.of(200, 202, CONFLICT);
        assertThat(second.statusCode())
                .as("FEAT-001.push-notification-idempotency: 相同 id + 相同 payload 重投应 200/202/409(等价幂等)"
                                + ",不允许 5xx 或静默丢弃\nstatus=%d body=%s",
                        second.statusCode(), second.body())
                .isIn(allowed);
    }

    @Test
    @DisplayName("FEAT-001.push-notification-idempotency: 相同 id + 不同 payload → 必须 409 conflict")
    void sameNotificationIdDifferentPayloadReturns409() throws Exception {
        // 见 sameNotificationIdSamePayloadIsIdempotent 注释:不再按 AgentCard capability skip.
        String notificationId = UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();

        HttpResponse<String> first = postCallback(notificationId,
                callbackBody(notificationId, taskId, "TASK_STATE_WORKING"));
        assertThat(first.statusCode())
                .as("FEAT-001.push-notification-idempotency: 首次投递应 200/202\n"
                                + "status=%d body=%s", first.statusCode(), first.body())
                .isIn(ACCEPTED);

        // 同 id 但 state 不同 —— payload 语义变化,SUT 必须拒绝(不允许 silent overwrite)
        HttpResponse<String> second = postCallback(notificationId,
                callbackBody(notificationId, taskId, "TASK_STATE_COMPLETED"));

        assertThat(second.statusCode())
                .as("FEAT-001.push-notification-idempotency: 相同 id + 不同 payload 必须 409 conflict"
                                + "(禁止 silent overwrite),实测 %d\nbody=%s",
                        second.statusCode(), second.body())
                .isEqualTo(CONFLICT);
    }

    private HttpResponse<String> postCallback(String notificationId, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(stack.baseUrl(DEEP_RESEARCH) + CALLBACK_PATH))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("X-A2A-Notification-Id", notificationId)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String callbackBody(String notificationId, String taskId, String state) {
        return String.format(
                "{\"notificationId\":\"%s\","
                        + "\"taskId\":\"%s\","
                        + "\"status\":{\"state\":\"%s\"},"
                        + "\"contextId\":\"ctx-idem-%s\"}",
                notificationId,
                taskId,
                state,
                UUID.randomUUID().toString().substring(0, 8));
    }
}

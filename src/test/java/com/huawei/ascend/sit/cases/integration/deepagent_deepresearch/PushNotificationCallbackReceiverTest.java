package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-001.push-notification-callback-receiver — Runtime-to-runtime callback receiver 端点契约.
 *
 * <p><b>Spec 依据</b>(version-scope FEAT-001 §2 + L2 §2.1/§2.7):
 * <ul>
 *   <li>version-scope §2 MUST:「作为接收方,runtime 必须暴露
 *     {@code POST /a2a/push-notifications/callback} 接收上游 runtime 的 task 状态回调」;</li>
 *   <li>L2 §2.1 endpoint 表明确列出 callback receiver 路径;</li>
 *   <li>L2 §2.7 定义 request/response 契约:
 *     <ul>
 *       <li>正常:200 或 202,回 {@code {status:"ok"|"accepted"}};</li>
 *       <li>malformed body: 400 或 -32602 body error;</li>
 *       <li>未授权: 401 或 403(在进 TaskStore 之前拦);</li>
 *       <li>能力关:404 或 501。</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p><b>2026-08-08 修订</b>:{@link PushNotificationDirectionProbeTest} 实证 AgentCard 的
 * {@code capabilities.pushNotifications} 只是<b>outbound 声明位</b>(agent 作 caller 是否给下游
 * 附 callback URL),<b>不代表 runtime 是否 fire inbound sender</b>——capability=false 时 runtime
 * 依然对 inline {@code taskPushNotificationConfig} 触发 sender。因此本用例:
 * <ol>
 *   <li><b>不再</b>用 {@code assumeTrue(capabilities.pushNotifications)} skip 正例断言;</li>
 *   <li>{@code callbackEndpointMatchesAdvertisedCapability} 保留双分支 —— 若观察到"声明关但端点开"
 *     或"声明开但端点关",都视为契约不一致,red-first 显性化。</li>
 * </ol>
 *
 * <p><b>为什么不校验 delivery 侧</b>:本用例只对 receiver 端点做<b>直接 HTTP 打点</b>,不涉及
 * "sender runtime 发出 callback → receiver 收到"链路 —— 那是 delivery 用例(需要两个 runtime
 * 之间的实际 push notification 链路);receiver 独立断言是 gate,把 sender 侧解耦。
 *
 * <p><b>callback body</b>:参照 A2A callback 契约,body 应含 {@code taskId} + {@code status.state}
 * 至少两个字段。用一个 SIT 侧构造的合法 body,只测 receiver 契约表面。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.push-notification-callback-receiver: /a2a/push-notifications/callback 端点契约")
class PushNotificationCallbackReceiverTest extends BaseManagedStackTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final String CALLBACK_PATH = "/a2a/push-notifications/callback";

    private static final List<Integer> POSITIVE_STATUS = List.of(200, 202);
    private static final List<Integer> CAPABILITY_OFF_STATUS = List.of(404, 501);
    private static final List<Integer> UNAUTHORIZED_STATUS = List.of(401, 403);
    private static final List<Integer> MALFORMED_STATUS = List.of(400, 422);

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
    @DisplayName("FEAT-001.push-callback-receiver: capabilities.pushNotifications ⇔ endpoint 可达性")
    void callbackEndpointMatchesAdvertisedCapability() throws Exception {
        AgentCard card = client(DEEP_RESEARCH).getAgentCard();
        boolean advertisesPush = card.capabilities() != null && card.capabilities().pushNotifications();

        String validBody = validCallbackBody(UUID.randomUUID().toString());

        HttpResponse<String> response = postWithNotificationId(CALLBACK_PATH, validBody,
                UUID.randomUUID().toString());

        if (advertisesPush) {
            // 声明打开:endpoint 必须可达,且非 404/501。允许 200/202(成功) 或 401/403(缺 auth)。
            assertThat(response.statusCode())
                    .as("FEAT-001.push-callback-receiver: capabilities.pushNotifications=true 时端点必须存在"
                                    + "(不允许 404/501,§6.4 composite check)\nstatus=%d body=%s",
                            response.statusCode(), response.body())
                    .isNotIn(CAPABILITY_OFF_STATUS);
        } else {
            // 声明关闭:endpoint 应 404/501 或至少非 200/202(不应静默接受)
            assertThat(response.statusCode())
                    .as("FEAT-001.push-callback-receiver: capabilities.pushNotifications=false 时"
                                    + "端点应 404/501 或至少不接受(非 200/202)\nstatus=%d body=%s",
                            response.statusCode(), response.body())
                    .isNotIn(POSITIVE_STATUS);
        }
    }

    @Test
    @DisplayName("FEAT-001.push-callback-receiver: 合法 body + 合法 Notification-Id → 200/202")
    void validCallbackReturnsAccepted() throws Exception {
        // AgentCard.capabilities.pushNotifications 只反映 outbound 声明,不代表 receiver 是否激活;
        // 2026-08-08 PushNotificationDirectionProbeTest 实测:capability=false 时 runtime sender 仍 fire。
        // 因此 receiver 端点契约不能用 capability 值 skip —— 无条件跑,让 SUT 真实行为决定。
        String taskId = UUID.randomUUID().toString();
        HttpResponse<String> response = postWithNotificationId(
                CALLBACK_PATH, validCallbackBody(taskId), UUID.randomUUID().toString());

        assertThat(response.statusCode())
                .as("FEAT-001.push-callback-receiver: 合法 callback 应 200/202\nstatus=%d body=%s",
                        response.statusCode(), response.body())
                .isIn(POSITIVE_STATUS);
    }

    @Test
    @DisplayName("FEAT-001.push-callback-receiver: malformed body → 400/422")
    void malformedCallbackBodyReturns4xx() throws Exception {
        // 见 validCallbackReturnsAccepted 注释:不再按 AgentCard capability skip.
        HttpResponse<String> response = postWithNotificationId(
                CALLBACK_PATH, "{not-json", UUID.randomUUID().toString());

        assertThat(response.statusCode())
                .as("FEAT-001.push-callback-receiver: 非法 JSON body 应 400/422,不应 500\n"
                                + "status=%d body=%s", response.statusCode(), response.body())
                .isIn(MALFORMED_STATUS);
    }

    /**
     * L2 §2.7:未授权(缺 auth header 或 auth 无效)应在<b>进 TaskStore 之前</b>拦下,返 401/403。
     * 本用例发一个显式错误的 Authorization header,不指望 SUT 处理它 —— 只测拒绝路径。
     *
     * <p>注意:如果 SUT 完全没启用 callback auth 校验(某些实现允许 SIT trusted network 内免 auth),
     * 此断言会 red 但属于 spec-gap。用 System.err 输出诊断信息。
     */
    @Test
    @DisplayName("FEAT-001.push-callback-receiver: 显式错误 auth → 401/403(spec gap 时 red-first)")
    void unauthorizedCallbackReturns401or403() throws Exception {
        // 见 validCallbackReturnsAccepted 注释:不再按 AgentCard capability skip.
        String taskId = UUID.randomUUID().toString();
        HttpRequest request = HttpRequest.newBuilder(URI.create(stack.baseUrl(DEEP_RESEARCH) + CALLBACK_PATH))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer sit-invalid-token-" + UUID.randomUUID())
                .header("X-A2A-Notification-Id", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(validCallbackBody(taskId)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (POSITIVE_STATUS.contains(response.statusCode())) {
            System.err.println("[FEAT-001.push-callback-receiver] SUT 接受了显式错误 auth header 的 callback"
                    + ",spec §2.7 应返 401/403。可能 SUT 未启用 callback auth 校验(spec-gap)。"
                    + "status=" + response.statusCode() + " body=" + response.body());
        }

        assertThat(response.statusCode())
                .as("FEAT-001.push-callback-receiver: 错误 auth 应 401/403(spec §2.7);"
                                + "若返 200/202 系 SUT 未启用 callback auth 校验(spec-gap red-first)\n"
                                + "status=%d body=%s",
                        response.statusCode(), response.body())
                .isIn(UNAUTHORIZED_STATUS);
    }

    private HttpResponse<String> postWithNotificationId(String path, String body, String notificationId)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(stack.baseUrl(DEEP_RESEARCH) + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        if (notificationId != null) {
            builder.header("X-A2A-Notification-Id", notificationId);
        }
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String validCallbackBody(String taskId) {
        return String.format(
                "{\"notificationId\":\"%s\","
                        + "\"taskId\":\"%s\","
                        + "\"status\":{\"state\":\"TASK_STATE_WORKING\"},"
                        + "\"contextId\":\"ctx-cb-%s\"}",
                UUID.randomUUID(),
                taskId,
                UUID.randomUUID().toString().substring(0, 8));
    }
}

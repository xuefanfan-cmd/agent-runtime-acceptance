package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aServiceClient;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-001.callback-receiver-auth (D9a) —— callback receiver 的鉴权强制（预期 <b>PASS</b>）。
 *
 * <p>本用例从原 {@code CallbackReceiverSurfaceTest} 的 D9 拆出，聚焦<b>单一关注点：鉴权层是否在
 * 进入业务处理前独立拦截非法 callback</b>。与之独立的「幂等重放」缺陷看守见
 * {@link CallbackReplayIdempotencyTest}。
 *
 * <p><b>基线</b>：2026-08-10 版 FEAT-001 §2「callback 安全边界」（MUST）：「接收 callback 时由现有
 * 授权框架独立校验请求，非法调用必须在进入 TaskStore 或业务处理前拦截」。
 *
 * <p><b>实测鉴权语义（2026-08-14 字节码 + 真机双证）</b>：receiver 鉴权由
 * {@code DeepResearchCallbackBearerTokenFilter}（{@code @Profile("callback-auth") @Order(MIN_VALUE)}）
 * 完成，只拦固定入口 {@code POST /a2a/push-notifications/callback}，用 {@code MessageDigest.isEqual}
 * 常量时间比对 {@code Authorization: Bearer <token>}，其中 token 取自
 * {@code openjiuwen.demo.deep-research.callback-auth.bearer-token}（= 环境变量
 * {@code DEEP_RESEARCH_CALLBACK_TOKEN}）。用户澄清：配了 token 后 deep-research 发消息给 search 时
 * 会带上，search 回调时也要带上。
 *
 * <p><b>门控前提（配置陷阱）</b>：该 filter <b>只在 Spring profile {@code callback-auth} 激活时</b>注册。
 * 本用例通过 {@link SutStack.Builder} 显式激活该 profile 并注入 token，因此校验的是「鉴权被正确启用后
 * 的强制效果」。若运维只设 {@code DEEP_RESEARCH_CALLBACK_TOKEN} 却不激活 profile，filter 不注册、
 * 入口裸奔——此为独立的部署告警项，不在本用例判定范围内。
 *
 * <p><b>判定表</b>（receiver 入口，三类请求）：
 * <ul>
 *   <li>无 {@code Authorization} 头 → 必须 <b>401</b>（授权层拒绝），且携带 {@code WWW-Authenticate: Bearer}；</li>
 *   <li>{@code Authorization: Bearer <错误 token>} → 必须 <b>401</b>；</li>
 *   <li>{@code Authorization: Bearer <正确 token>} → <b>放行到业务层</b>：不得为 401/403。业务层对未绑定
 *       task 返回 404 {@code callback binding not found} 属正常（证明已越过鉴权到达 handler）。</li>
 * </ul>
 * 任一分支不符即 FAIL —— 说明鉴权未被独立强制（拦截点缺失或过深）。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.callback-receiver-auth: 启用鉴权后未授权 callback 在进入业务处理前被拒绝")
class CallbackReceiverAuthTest extends BaseManagedStackTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final String RECEIVER_PATH = "/a2a/push-notifications/callback";
    private static final String CALLBACK_TOKEN = "sit-callback-auth-" + UUID.randomUUID();

    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * 启用 {@code callback-auth} profile + 注入 token + 打开 push notification（否则 receiver 能力不暴露）。
     * <ul>
     *   <li>{@code DEEP_RESEARCH_PUSH_NOTIFICATIONS=true}：让 capabilities.pushNotifications=true、固定
     *       receiver 入口存在。</li>
     *   <li>{@code DEEP_RESEARCH_PUBLIC_URL}：push=true 时为必填（否则 boot fail-fast
     *       {@code DEEP_RESEARCH_PUBLIC_URL must be configured ...}）。本用例只往 receiver 入口直接 POST、
     *       不走真实出站推送，故给占位值即可过校验。</li>
     *   <li>{@code SEARCH_AGENT_URL}/{@code VERIFY_AGENT_URL}：deep-research 对 remote-agents[*].url 做
     *       <b>非空存在性</b>校验，缺失即 fail-fast；但下游不可达不阻塞启动（仅 WARN + 每 30s 重试）。本用例
     *       不触发下游调用，注入 dummy URL 即可。</li>
     * </ul>
     */
    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config).agent(DEEP_RESEARCH, a -> a
                .profile("callback-auth")
                .env("DEEP_RESEARCH_PUSH_NOTIFICATIONS", "true")
                .env("DEEP_RESEARCH_PUBLIC_URL", "http://127.0.0.1:18090")
                .env("DEEP_RESEARCH_CALLBACK_TOKEN", CALLBACK_TOKEN)
                .env("SEARCH_AGENT_URL", "http://127.0.0.1:19991")
                .env("VERIFY_AGENT_URL", "http://127.0.0.1:19992"));
    }

    @Test
    @DisplayName("FEAT-001.callback-receiver-auth (D9a): 无 Authorization 的 callback 必须被授权层 401 拒绝")
    void missingAuthorizationRejectedAtAuthLayer() throws Exception {
        assumePushEnabledAndRouteExposed();

        HttpResponse<String> response = post(callbackPayload(), null);

        assertThat(response.statusCode())
                .as("FEAT-001 §2: 启用 callback-auth 后，无 Authorization 头的 callback 必须在授权层被拒（401）；\n"
                        + "实测 status=%s body=%s", response.statusCode(), response.body())
                .isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate").orElse(""))
                .as("FEAT-001 §2: 401 应按 Bearer 方案给出 WWW-Authenticate 质询头")
                .contains("Bearer");
    }

    @Test
    @DisplayName("FEAT-001.callback-receiver-auth (D9a): 错误 bearer 的 callback 必须被授权层 401 拒绝")
    void wrongBearerRejectedAtAuthLayer() throws Exception {
        assumePushEnabledAndRouteExposed();

        HttpResponse<String> response = post(callbackPayload(), "Bearer wrong-" + UUID.randomUUID());

        assertThat(response.statusCode())
                .as("FEAT-001 §2: 错误 bearer 的 callback 必须在授权层被拒（401）；\n"
                        + "实测 status=%s body=%s", response.statusCode(), response.body())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("FEAT-001.callback-receiver-auth (D9a): 正确 bearer 的 callback 必须越过鉴权到达业务层")
    void correctBearerPassesAuthFilter() throws Exception {
        assumePushEnabledAndRouteExposed();

        HttpResponse<String> response = post(callbackPayload(), "Bearer " + CALLBACK_TOKEN);

        // 正确 bearer 必须被鉴权层放行：不得 401/403。业务层对未绑定 task 返回 404
        // callback binding not found 属正常（恰好证明请求已越过鉴权到达 handler）。
        assertThat(response.statusCode())
                .as("FEAT-001 §2: 正确 bearer 的 callback 不应被授权层拒绝（不得 401/403）；\n"
                        + "实测 status=%s body=%s", response.statusCode(), response.body())
                .isNotIn(401, 403);
        assertThat(response.statusCode() / 100)
                .as("FEAT-001 §2: 越过鉴权后不得打出 5xx（路由内部异常同样违约）；\n"
                        + "实测 status=%s body=%s", response.statusCode(), response.body())
                .isNotEqualTo(5);
    }

    // —— helpers ——

    private void assumePushEnabledAndRouteExposed() throws Exception {
        assumeTrue(pushNotificationsDeclared(),
                "callback-receiver-auth: capabilities.pushNotifications=false，receiver 能力未启用，"
                        + "本用例无对象，跳过（INCONCLUSIVE）");
        // 用一个必然被授权层拒绝的探针确认路由存在（callback-auth 下应为 401，而非 404/405/501）。
        HttpResponse<String> probe = post("{\"probe\":\"sit-d9a-route\"}", null);
        assumeTrue(!isNotExposed(probe.statusCode()),
                "callback-receiver-auth: receiver 路由不可用（status=" + probe.statusCode()
                        + "），本用例无对象，跳过（INCONCLUSIVE）");
    }

    private boolean pushNotificationsDeclared() {
        A2aServiceClient a2a = client(DEEP_RESEARCH);
        AgentCard card = a2a.getAgentCard();
        return card.capabilities() != null && Boolean.TRUE.equals(card.capabilities().pushNotifications());
    }

    /**
     * 完整的 callback payload：复用 {@code SendMessage} 的 JSON-RPC result 表面并携带 notification id，
     * Task 状态用实测 wire 枚举 {@code TASK_STATE_*}（终态 COMPLETED，符合"仅终态才回调"的触发语义）。
     */
    private static String callbackPayload() {
        String notificationId = "sit-auth-" + UUID.randomUUID();
        String taskId = "sit-task-" + UUID.randomUUID();
        return String.format(
                "{\"notificationId\":\"%s\",\"jsonrpc\":\"2.0\",\"id\":\"cb-1\",\"result\":{\"task\":{"
                        + "\"id\":\"%s\",\"contextId\":\"ctx-%s\","
                        + "\"status\":{\"state\":\"TASK_STATE_COMPLETED\"}}}}",
                notificationId, taskId, UUID.randomUUID().toString().substring(0, 8));
    }

    /** POST 到固定 receiver 入口；{@code authorization} 为 null 表示不带任何凭据。 */
    private HttpResponse<String> post(String payload, String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create(stack.baseUrl(DEEP_RESEARCH) + RECEIVER_PATH))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static boolean isNotExposed(int status) {
        return status == 404 || status == 405 || status == 501;
    }
}

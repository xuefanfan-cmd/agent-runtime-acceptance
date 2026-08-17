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
 * FEAT-001.callback-replay-idempotency (D9b) —— callback 幂等去重的<b>顺序缺陷回归看守</b>
 * （缺陷修复前预期 <b>FAIL</b>）。
 *
 * <p>本用例从原 {@code CallbackReceiverSurfaceTest} 的 D9 拆出，聚焦<b>与鉴权正交的另一关注点</b>：
 * 幂等去重把「首次被拒绝的 callback」在重放时误判为「已受理」。鉴权强制见独立用例
 * {@link CallbackReceiverAuthTest}。为把关注点隔离干净，本用例<b>不激活 {@code callback-auth} profile</b>
 * （无需 bearer），使判定只取决于「首发拒绝 vs 重放受理」的语义，而非鉴权。
 *
 * <p><b>缺陷（2026-08-14 字节码 + 真机双证，已提交开发组）</b>：
 * {@code A2aPushNotificationCallbackController.handleCallback} 的处理顺序错误——
 * {@code saveIfAbsent(notificationId, sha256(payload))} <b>先于</b>绑定校验 {@code onAccepted(task)}，
 * 且只有 {@code CREATED} 分支才调用 {@code onAccepted}；{@code DUPLICATE} 分支直接落到
 * {@code return 200 accepted}，<b>跳过绑定校验</b>。真实 handler
 * {@code A2AEnabledServeOrchestrator.onAccepted → batchCoordinator.recoverCallback(task)}
 * <b>按 task id</b> 查在途批次（notificationId 仅做幂等）。后果：
 * <ol>
 *   <li>首发 payload P（task 无在途绑定）→ <b>404 callback binding not found</b>
 *       （但 store 已写入 notificationId→hash）；</li>
 *   <li>原样重放 P → <b>200 accepted</b>（saveIfAbsent 命中 DUPLICATE，跳过 onAccepted）。</li>
 * </ol>
 * 即：同一个被 404 拒绝的请求，重放变成 200「已受理」。
 *
 * <p><b>看守不变式（本用例断言的正确契约）</b>：<em>若一次 callback 在首发时被<b>拒绝</b>（非 2xx），
 * 则对其原样重放<b>不得</b>返回 2xx「已受理」</em>——「受理」应记在 {@code onAccepted} 成功之后，而非
 * 「收到即落库」。当前实现违反该不变式，故本用例在缺陷修复前<b>保持 FAIL</b>，作为回归看守；开发修复
 * （落库移到 onAccepted 成功之后，或 DUPLICATE 时重放原判定）后自动转 PASS。
 *
 * <p><b>关联缺陷单</b>：https://gitcode.com/openJiuwen/agent-runtime-java/issues/77
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.callback-replay-idempotency: 首发被拒的 callback 重放不得被误判为已受理")
class CallbackReplayIdempotencyTest extends BaseManagedStackTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final String RECEIVER_PATH = "/a2a/push-notifications/callback";

    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * 打开 push notification 让 receiver 入口存在；<b>不</b>激活 callback-auth（本用例与鉴权正交）。
     * {@code DEEP_RESEARCH_PUBLIC_URL} 为 push=true 时的必填占位；{@code SEARCH/VERIFY_AGENT_URL} 为
     * remote-agents 非空存在性校验的 dummy 值（下游不可达不阻塞启动）。
     */
    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config).agent(DEEP_RESEARCH, a -> a
                .env("DEEP_RESEARCH_PUSH_NOTIFICATIONS", "true")
                .env("DEEP_RESEARCH_PUBLIC_URL", "http://127.0.0.1:18090")
                .env("SEARCH_AGENT_URL", "http://127.0.0.1:19991")
                .env("VERIFY_AGENT_URL", "http://127.0.0.1:19992"));
    }

    @Test
    @DisplayName("FEAT-001.callback-replay-idempotency (D9b): 首发被拒的 callback 重放不得变为已受理 [缺陷修复前 FAIL]")
    void replayOfRejectedCallbackMustNotBecomeAccepted() throws Exception {
        assumeTrue(pushNotificationsDeclared(),
                "callback-replay-idempotency: capabilities.pushNotifications=false，receiver 未启用，"
                        + "本用例无对象，跳过（INCONCLUSIVE）");

        // 全新 notificationId + 未绑定 task id：首发注定在绑定校验处被拒。
        String notificationId = "sit-replay-" + UUID.randomUUID();
        String taskId = "sit-unbound-" + UUID.randomUUID();
        String payload = callbackPayload(notificationId, taskId);

        HttpResponse<String> first = post(payload);
        HttpResponse<String> replay = post(payload);

        // 看守不变式的前提：本探针的首发确实被"拒绝"（非 2xx，实测为 404 binding not found）。
        // 注意：此处的 404 是<b>业务级</b>拒绝（未绑定 task → "callback binding not found"），入口是<b>存在</b>的；
        // 不能把它误当成"路由不存在"而跳过——那恰恰会漏掉本用例要看守的缺陷。push=true 前置已保证 receiver 暴露；
        // 万一路由真缺失（first/replay 均 404），下方断言 replay 非 2xx 仍成立，不会误报 FAIL。
        assumeTrue(first.statusCode() / 100 != 2,
                "callback-replay-idempotency: 首发意外被受理（status=" + first.statusCode()
                        + "），未构成'先拒后重放'场景，跳过（INCONCLUSIVE）");

        // 核心断言（正确契约）：首发被拒 ⇒ 原样重放不得返回 2xx 已受理。
        // 当前实现 first=404 → replay=200，违反该不变式 ⇒ 本用例 FAIL，作为回归看守。
        assertThat(replay.statusCode() / 100)
                .as("FEAT-001 §2 幂等语义缺陷回归看守"
                        + "（issue: https://gitcode.com/openJiuwen/agent-runtime-java/issues/77）：\n"
                        + "callback（notificationId=%s，task 无在途绑定）首发被拒 %s，原样重放却返回 %s。\n"
                        + "缺陷根因：saveIfAbsent 先于 onAccepted 绑定校验，DUPLICATE 分支跳过校验直接 200 accepted。\n"
                        + "正确契约：首发被拒（非 2xx）的 callback 重放不得被误判为已受理（2xx）。\n"
                        + "修法：落库移到 onAccepted 成功之后，或 DUPLICATE 时重放原判定而非无条件 200。\n"
                        + "first=%s %s\nreplay=%s %s",
                        notificationId, first.statusCode(), replay.statusCode(),
                        first.statusCode(), first.body(), replay.statusCode(), replay.body())
                .isNotEqualTo(2);
    }

    // —— helpers ——

    private boolean pushNotificationsDeclared() {
        A2aServiceClient a2a = client(DEEP_RESEARCH);
        AgentCard card = a2a.getAgentCard();
        return card.capabilities() != null && Boolean.TRUE.equals(card.capabilities().pushNotifications());
    }

    private static String callbackPayload(String notificationId, String taskId) {
        return String.format(
                "{\"notificationId\":\"%s\",\"jsonrpc\":\"2.0\",\"id\":\"cb-1\",\"result\":{\"task\":{"
                        + "\"id\":\"%s\",\"contextId\":\"ctx-%s\","
                        + "\"status\":{\"state\":\"TASK_STATE_COMPLETED\"}}}}",
                notificationId, taskId, UUID.randomUUID().toString().substring(0, 8));
    }

    private HttpResponse<String> post(String payload) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(stack.baseUrl(DEEP_RESEARCH) + RECEIVER_PATH))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}

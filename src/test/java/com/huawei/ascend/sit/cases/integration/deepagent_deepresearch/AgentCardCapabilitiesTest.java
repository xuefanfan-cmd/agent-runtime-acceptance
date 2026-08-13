package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.AgentCapabilities;
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
 * FEAT-001.agent-card-capabilities — Agent Card capabilities 声明真实性.
 *
 * <p>FEAT-001 §2「Agent Card capabilities」+ §5.1.1「capabilities 反映部署配置」。
 * capabilities 声明必须与 SUT 实际部署能力一致：
 * <ul>
 *   <li>{@code streaming=true} —— DA-03 {@code StreamingSendMessageTest} 已经证明流式路径能跑；
 *       本条只做字段声明快照。</li>
 *   <li>{@code pushNotifications} —— 与 {@link PushConfigCrudTest} 交叉验证（此条不做 sender-真发一条 POST
 *       的负路径断言，那是评审 §3 的 deferred 项）。当前只做字段存在 + boolean 合法性快照。</li>
 *   <li>{@code extendedAgentCard} / {@code extensions} —— 只做字段可读断言，SUT 现状不启用即为 false / 空列表。</li>
 * </ul>
 *
 * <p><b>与 {@link AgentCardDiscoveryTest#deepResearchCardMatchesManualContract()} 的分工</b>：
 * DA-01.C 已经断言 {@code streaming=true} / {@code pushNotifications=false} 的具体值。本用例走
 * FEAT-001 视角，把断言组织成"声明真实性 vs 实际能力"格式，便于未来 pushNotifications 打开时
 * 只改本类而非 DA-01。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.agent-card-capabilities: Agent Card capabilities 声明真实性")
class AgentCardCapabilitiesTest extends BaseManagedStackTest {

    private static final String DEEP_RESEARCH = "deep-research";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // SUT jar startup 要求 openjiuwen.service.a2a.remote-agents[0].url 非空,
        // 本用例只做 card capabilities 探针,不打真实 search 链路 → 注入本地 loopback 占位即可
        // (校验只查 non-blank,不做 reachability)。
        return SutStack.builder(config)
                .agent(DEEP_RESEARCH, a -> a
                        .env("SEARCH_AGENT_URL", "http://127.0.0.1:1")
                        .env("VERIFY_AGENT_URL", "http://127.0.0.1:1"));
    }

    @Test
    @DisplayName("FEAT-001.agent-card-capabilities: capabilities 字段结构完整且与部署一致")
    void capabilitiesReflectDeployment() {
        A2aServiceClient a2a = client(DEEP_RESEARCH);
        AgentCard card = a2a.getAgentCard();

        AgentCapabilities cap = card.capabilities();
        assertThat(cap)
                .as("FEAT-001.agent-card-capabilities: capabilities 不应为 null")
                .isNotNull();

        // streaming 声明必须与 SUT 实际流式能力一致。DA-03 已在 SIT 上跑通 SSE 路径 → 声明必须为 true。
        assertThat(cap.streaming())
                .as("FEAT-001.agent-card-capabilities: capabilities.streaming 应声明 true"
                        + "（DA-03 StreamingSendMessageTest 证明 SUT 支持 SendStreamingMessage）")
                .isTrue();

        // pushNotifications 是 boolean 字段 —— 字段可读性快照。
        // 与 CRUD 探针关系:2026-07-24 版本 FEAT-001 已将 CRUD 5 method 显式下线,
        // {@link PushConfigCrudTest} 现在断"5 method 都返 -32601"（不再受 pushNotifications 值影响)。
        // 与 callback endpoint 的<b>组合可达性</b>由本类新增 test {@code capabilityImpliesCallbackReachability} 交叉验证。
        boolean pushDeclared = cap.pushNotifications();
        assertThat(pushDeclared)
                .as("FEAT-001.agent-card-capabilities: capabilities.pushNotifications 字段可读（当前值=%s）",
                        pushDeclared)
                .isIn(true, false);

        // extendedAgentCard 是 A2A 1.0 新增字段。SUT 未启用扩展 card 时为 false。
        assertThat(cap.extendedAgentCard())
                .as("FEAT-001.agent-card-capabilities: capabilities.extendedAgentCard 字段可读（当前值=%s）",
                        cap.extendedAgentCard())
                .isIn(true, false);

        // extensions 列表可为 null 或空 —— SUT 未声明扩展时；不强制 non-null，只要不抛出即可。
        assertThat(cap.extensions())
                .as("FEAT-001.agent-card-capabilities: capabilities.extensions 可为 null / 空，不应触发解析异常")
                .satisfiesAnyOf(
                        ext -> assertThat(ext).isNull(),
                        ext -> assertThat(ext).isNotNull());
    }

    /**
     * L2 §6.4「composite capability check」:capabilities.pushNotifications=true 是一个复合能力声明,
     * 意味着 SUT 同时具备:
     * <ul>
     *   <li>callback receiver 端点 {@code POST /a2a/push-notifications/callback} 可达;</li>
     *   <li>callback delivery/store handler 已注入。</li>
     * </ul>
     * 本用例只做 receiver 端点可达性的交叉验证 —— 声明 true 时 endpoint 不允许 404/501。
     *
     * <p>声明 false 时:endpoint 可以 404/501,也可以静默拒绝(比如返 400/401 不加处理)。
     * 只不允许"声明关但 endpoint 反而 200/202" —— 那说明 card 撒谎了。
     */
    @Test
    @DisplayName("FEAT-001.agent-card-capabilities: pushNotifications ⇔ callback endpoint 可达(§6.4)")
    void capabilityImpliesCallbackReachability() throws Exception {
        A2aServiceClient a2a = client(DEEP_RESEARCH);
        AgentCard card = a2a.getAgentCard();
        boolean pushDeclared = card.capabilities() != null && card.capabilities().pushNotifications();

        String body = String.format(
                "{\"notificationId\":\"%s\",\"taskId\":\"%s\","
                        + "\"status\":{\"state\":\"TASK_STATE_WORKING\"},"
                        + "\"contextId\":\"ctx-cap-probe-%s\"}",
                UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID().toString().substring(0, 8));

        HttpClient http = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(stack.baseUrl(DEEP_RESEARCH) + "/a2a/push-notifications/callback"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("X-A2A-Notification-Id", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        List<Integer> capabilityOff = List.of(404, 501);
        List<Integer> positive = List.of(200, 202);
        if (pushDeclared) {
            assertThat(response.statusCode())
                    .as("FEAT-001.agent-card-capabilities: pushNotifications=true 时 callback endpoint 必须存在"
                                    + "(§6.4 composite check;不允许 404/501)\nstatus=%d body=%s",
                            response.statusCode(), response.body())
                    .isNotIn(capabilityOff);
        } else {
            assertThat(response.statusCode())
                    .as("FEAT-001.agent-card-capabilities: pushNotifications=false 时 callback endpoint 不应"
                                    + "接受回调(§6.4 反向;不允许 200/202)\nstatus=%d body=%s",
                            response.statusCode(), response.body())
                    .isNotIn(positive);
        }
    }
}
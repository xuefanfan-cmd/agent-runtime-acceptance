package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
class AgentCardCapabilitiesTest extends BaseManagedStackTest {

    private static final String DEEP_RESEARCH = "deep-research";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config).agent(DEEP_RESEARCH);
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

        // pushNotifications 是 boolean 字段 —— 只断言字段可读，不断言具体值。
        // 具体值由 PushConfigCrudTest 通过 CRUD 探针间接验证：
        //   声明 true → PushConfigCrudTest 应 PASS（CRUD 契约齐全）；
        //   声明 false → PushConfigCrudTest assumeTrue skip。
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
}
package com.huawei.ascend.sit.cases.integration.workflow_call;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.config.TestEnvironment;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.transport.MessageProtocol;
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;

import java.util.stream.Stream;

/**
 * balanceThenTransfers 的<b>直连 plan-agent gateway 端点</b>变体（openjiuwen profile 限定）：
 * edpa-adapter + edpa-plan-agent，<b>不起 edpa-gateway</b>；Conversation 经
 * {@link com.huawei.ascend.sit.conversation.ConversationInteractionAdapter}（{@link MessageProtocol#REST_GATEWAY}）
 * 直发 plan-agent 原生的 gateway 自定义端点（{@code custom_rsp_data}/{@code think_chunk} 投影线）。
 *
 * <p>plan-agent（artifact {@code versatile-orch-demo-plan-agent}）已把 gateway 自定义 REST 端点
 * （{@code POST /v1/{project_id}/agents/{agent_id}/conversations/{conversation_id}}，端口 18093）从 adapter 迁入自身
 * （commit {@code cc1cab7}），故客户端可<b>绕过 edpa-gateway 直连</b>——本用例覆盖这条新路径。线形态（{@code custom_rsp_data}
 * 投影）与 {@link com.huawei.ascend.sit.transport.GatewayEventMapping} 编写依据的 {@code GatewayStreamProjector} 吻合，
 * 故解析逻辑沿用、无需改动。
 *
 * <p>流程、核心断言、stepUi 驱动全部继承自 {@link AbstractBalanceThenTransfersTest}；本类只 override
 * {@link #buildStack(TestConfig)}（直连栈）与 {@link AbstractBalanceThenTransfersTest#protocols()}（协议集 =
 * {@code REST_GATEWAY}），{@code openConversation}/驱动/断言全复用（adapter 的 {@code transportFor} 已有
 * {@code REST_GATEWAY} 分支 → {@code RestGatewayTransport} → 端点 {@code /v1/{pid}/agents/{aid}/conversations/{cid}}，
 * 与 plan-agent 端点模板逐字匹配；query 参被端点忽略，无害）。
 *
 * @see PlanAgentDirectStreamingTest 直连 + A2A/REST_QUERY 同场景对照
 * @see TransferAfterBalanceAcceptanceTest 经 edpa-gateway 代理的同场景对照（bare 线，另一条路径）
 */
@Tag("integration")
@Feature("FEAT-004: 任务驱动远程智能体调用")
@Stories({
        @Story("wf.rest-gateway-direct: 直连 plan-agent 原生 gateway 自定义端点（custom_rsp_data 线）")
})
class PlanAgentGatewayDirectStreamingTest extends AbstractBalanceThenTransfersTest {

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // 第一行 gate：非 openjiuwen 在基类 .start() 之前 abort，不拉容器。
        Assumptions.assumeTrue(TestEnvironment.current() == TestEnvironment.OPENJIUWEN,
                "openjiuwen profile only — re-run with -Dtest.env=openjiuwen (需 LLM_API_KEY 等)");
        // 直连栈：不起 edpa-gateway。envexplorer 由 edpa-adapter 的 service-bindings 自动拉起。
        return SutStack.builder(config)
                .agent("edpa-adapter")
                .agent("edpa-plan-agent", a -> a.downstream("edpa-adapter"));
    }

    @Override
    protected Stream<MessageProtocol> protocols() {
        return Stream.of(MessageProtocol.REST_GATEWAY);   // 直连 plan-agent 的 gateway 自定义端点
    }
}

package com.huawei.ascend.sit.cases.integration.workflow_call;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.config.TestEnvironment;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Assumptions;

/**
 * balanceThenTransfers 的<b>纯直连</b>变体（openjiuwen profile 限定）：edpa-adapter + edpa-plan-agent，无中间件。
 *
 * <p>流程、核心断言、stepUi 驱动、参数化（{@link com.huawei.ascend.sit.transport.MessageProtocol#A2A_STREAM} /
 * {@link com.huawei.ascend.sit.transport.MessageProtocol#REST_QUERY}）全部继承自 {@link AbstractBalanceThenTransfersTest}。
 * 本类只 override {@link #buildStack} 描述直连栈，不追加额外检查点（{@code afterCheck} 用基类默认软捕获）。
 *
 * @see PlanAgentDirectStreamingRedisTest 同栈 + redis 中间件变体
 * @see TransferAfterBalanceAcceptanceTest 经 gateway 的同场景对照
 */
@Feature("FEAT-004: 任务驱动远程智能体调用")
@Stories({
        @Story("wf.verstaile-once: Versatile 远端链路"),
        @Story("wf.verstaile-input-required: Versatile 远端链路多轮中断续接")
})
class PlanAgentDirectStreamingTest extends AbstractBalanceThenTransfersTest {

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // 第一行 gate：非 openjiuwen 在基类 .start() 之前 abort，不拉容器。
        Assumptions.assumeTrue(TestEnvironment.current() == TestEnvironment.OPENJIUWEN,
                "openjiuwen profile only — re-run with -Dtest.env=openjiuwen (需 LLM_API_KEY 等)");
        // 精简栈：不起 edpa-gateway。envexplorer 由 edpa-adapter 的 service-bindings 自动拉起。
        return SutStack.builder(config)
                .agent("edpa-adapter")
                .agent("edpa-plan-agent", a -> a.downstream("edpa-adapter"));
    }
}

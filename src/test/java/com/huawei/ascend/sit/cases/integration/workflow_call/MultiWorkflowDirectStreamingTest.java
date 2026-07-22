package com.huawei.ascend.sit.cases.integration.workflow_call;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.config.TestEnvironment;
import com.huawei.ascend.sit.conversation.Conversation;
import com.huawei.ascend.sit.conversation.ConversationIdentity;
import com.huawei.ascend.sit.conversation.ConversationInteractionAdapter;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.transport.MessageProtocol;
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;

import java.time.Duration;

/**
 * balanceThenTransfers 的<b>直连 + 多 workflow 路由</b>变体（openjiuwen profile 限定）。
 *
 * <p>流程、核心断言、stepUi 驱动、参数化全部继承自 {@link AbstractBalanceThenTransfersTest}。本类差别只在两个 seam：
 * <ul>
 *   <li>{@link #buildStack} —— 被测 adapter 跑 {@code multi-workflow} profile（YAML agent {@code edpa-adapter-multi-workflow}）。
 *       两条 workflow URL 占位符经编程式 {@code serviceBinding} 指向<b>同一个 envexplorer 容器</b>（YAML service-bindings 按服务名 1:1，
 *       做不到「两 key 指同一服务」）；框架去重后只拉一个 envexplorer，容器起后、adapter jar 起前把两条 {@code {{url}}} 解析成
 *       {@code host:mappedPort}、发 {@code --VERSATILE_BALANCE_WORKFLOW_URL=...} / {@code --VERSATILE_TRANSFER_WORKFLOW_URL=...}
 *       （Spring 命令行参，最高优先级，直接解析 SUT 占位符）。</li>
 *   <li>{@link #openConversation} override —— adapter 下游按 intent 路由（不经 controller 类型），故禁掉 {@code type} 查询参
 *       （{@code workspace_id} 仍带，仍打在 REST /v1/query URL 与 A2A metadata.query 上）。</li>
 * </ul>
 *
 * <p><b>栈</b>：{@code edpa-adapter-multi-workflow}（coords + profile + fallback 绑定来自 YAML）+
 * {@code edpa-plan-agent}(downstream→adapter)。envexplorer 由 YAML fallback 绑定自动收集、拉起。fallback
 * {@code openjiuwen.service.versatile.url-template}（agents 格式）作未匹配 intent 兜底。
 *
 * <p><b>首轮校准点（无真机 LLM 无法验证）</b>：承袭直连基线的 4 点（plan-agent 接受标准 A2A 文本一轮输入 / taskId 续轮 /
 * 直连无需 EDPA inputs 富化 / plan-agent 暴露 {@code /v1/query}），外加：(5) plan-agent 调 adapter 时携带正确 {@code intent}
 * （{@code 查询账户余额}/{@code 快速转账}），使多 workflow 路由命中 workflow 端点而非 fallback；(6) {@code multi-workflow} profile
 * 正确叠加 base 配置（endpoints 列表合并、fallback 继承、timeout/headers-template/result-node-name 不变）。
 *
 * <p><b>当前 {@code @Disabled}</b>：scenario 存在未解问题。
 *
 * @see PlanAgentDirectStreamingTest 单 workflow（无 profile）的同场景对照
 * @see TransferAfterBalanceAcceptanceTest 经 gateway 的同场景对照
 */
@Feature("FEAT-002: 异构智能体框架兼容")
@Stories({
        @Story("wf.workflow-direct: 多 workflow intent 路由到远程 Versatile")
})
@Tag("integration")
@Disabled("There is issue about this scenario")
class MultiWorkflowDirectStreamingTest extends AbstractBalanceThenTransfersTest {

    /** 多 workflow adapter（YAML agent：coords + profile:multi-workflow + fallback 绑定）。 */
    private static final String ADAPTER = "edpa-adapter-multi-workflow";

    /** envexplorer 侧 workflow 路径常量（project_id + agent_id 固定，workflow_id 区分余额/转账）。 */
    private static final String PROJECT_AGENT = "mock_project_id/fb723468-c8ca-424b-a95f-a3e74b37e090";
    private static final String BALANCE_WORKFLOW = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
    private static final String TRANSFER_WORKFLOW = "45c08bf2-b591-44e2-9d7c-57dd0bd8f760";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // 第一行 gate：非 openjiuwen 在基类 .start() 之前 abort，不拉容器。
        Assumptions.assumeTrue(TestEnvironment.current() == TestEnvironment.OPENJIUWEN,
                "openjiuwen profile only — re-run with -Dtest.env=openjiuwen (需 LLM_API_KEY 等)");
        String base = "http://{{url}}/v1/" + PROJECT_AGENT + "/workflows/";
        return SutStack.builder(config)
                .agent(ADAPTER, a -> a
                        // 两条 workflow 绑定指向同一 envexplorer（框架去重→一个容器），各注入一条占位符。
                        .serviceBinding("envexplorer", "VERSATILE_BALANCE_WORKFLOW_URL",
                                base + BALANCE_WORKFLOW + "/conversations/{conversation_id}")
                        .serviceBinding("envexplorer", "VERSATILE_TRANSFER_WORKFLOW_URL",
                                base + TRANSFER_WORKFLOW + "/conversations/{conversation_id}"))
                .agent(PLAN_AGENT, a -> a.downstream(ADAPTER));
    }

    /**
     * 重抄基类 conv 构造，仅给 adapter 加 {@code .disableQueryParam("type")}：多 workflow adapter 下游按 intent 路由，
     * 不经 controller 类型，故禁掉 {@code type} 查询参；{@code workspace_id} 仍带。
     */
    @Override
    protected Conversation openConversation(MessageProtocol protocol) {
        return Conversation.at(stack.baseUrl(PLAN_AGENT), stack.serviceUrl("envexplorer"))
                .identity(ConversationIdentity.loadDefault())
                .transport(new ConversationInteractionAdapter(protocol, client(PLAN_AGENT), ROUND_TIMEOUT_MS)
                        .disableQueryParam("type"))
                .timeout(Duration.ofSeconds(600))
                .open();
    }
}

package com.huawei.ascend.sit.cases.integration.workflow_call;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.config.TestEnvironment;
import com.huawei.ascend.sit.conversation.Conversation;
import com.huawei.ascend.sit.conversation.ConversationIdentity;
import com.huawei.ascend.sit.conversation.ConversationInteractionAdapter;
import com.huawei.ascend.sit.conversation.DriveMode;
import com.huawei.ascend.sit.conversation.SseEvent;
import com.huawei.ascend.sit.conversation.TurnResult;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.transport.MessageProtocol;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 直连 edpa-plan-agent 的【多 workflow 路由】转账场景验收（openjiuwen profile 限定；A2A/REST 流式参数化）。
 *
 * <p>与 {@link PlanAgentDirectStreamingTest} 同结构（直连 plan-agent、stepUi 自推进、同句同 select 同断言），
 * 差别只在被测 adapter 跑的是 {@code multi-workflow} profile（YAML agent {@code edpa-adapter-multi-workflow}）：
 * adapter 的 {@code application-multi-workflow.yml} 按 {@code intent} 精确路由到两条 workflow 端点（余额查询 /
 * 快速转账），各自经 Spring 占位符 {@code ${VERSATILE_BALANCE_WORKFLOW_URL:...}} / {@code ${VERSATILE_TRANSFER_WORKFLOW_URL:...}}
 * 承载整条 URL（默认假地址 {@code 127.0.0.1:31113}）。
 *
 * <p><b>本用例验证的框架能力</b>：两条 workflow URL 占位符都要指向<b>同一个 envexplorer 容器</b>的解析地址——
 * 这是 YAML {@code service-bindings}（按服务名 1:1）做不到的，故由编程式 {@link SutStack.AgentBuilder#serviceBinding}
 * 在 {@code buildStack} 里声明两条绑定（同 {@code serviceName=envexplorer}，不同 {@code urlKey/urlTemplate}）。
 * 框架去重后只拉一个 envexplorer 容器，并在容器起后、adapter jar 起前，把两条 {@code {{url}}} 解析成
 * {@code host:mappedPort}、套各自模板、发 {@code --VERSATILE_BALANCE_WORKFLOW_URL=...} /
 * {@code --VERSATILE_TRANSFER_WORKFLOW_URL=...}（Spring 命令行参数，最高优先级，直接解析 SUT 占位符）。
 *
 * <p><b>栈</b>：{@code edpa-adapter-multi-workflow}（coords + profile + fallback 绑定来自 YAML）+
 * {@code edpa-plan-agent}(downstream→adapter)。envexplorer 由 YAML fallback 绑定自动收集、拉起。fallback
 * {@code openjiuwen.service.versatile.url-template}（agents 格式）作未匹配 intent 兜底。
 *
 * <p><b>首轮校准点（无真机 LLM 无法验证）</b>：承袭 {@link PlanAgentDirectStreamingTest} 的 4 点（plan-agent
 * 接受标准 A2A 文本一轮输入 / taskId 续轮 / 直连无需 EDPA inputs 富化 / plan-agent 暴露 {@code /v1/query}），
 * 外加：(5) plan-agent 调 adapter 时携带正确 {@code intent}（{@code 查询账户余额}/{@code 快速转账}），使多 workflow
 * 路由命中 workflow 端点而非 fallback；(6) {@code multi-workflow} profile 正确叠加 base 配置（endpoints 列表合并、
 * fallback 继承、timeout/headers-template/result-node-name 不变）。真机实跑前确认被测 adapter jar 已就绪。
 *
 * @see PlanAgentDirectStreamingTest 单 workflow（无 profile）的同场景对照
 * @see TransferAfterBalanceAcceptanceTest 经 gateway 的同场景对照
 */
@Tag("integration")
class MultiWorkflowDirectStreamingTest extends BaseManagedStackTest {

    private static final String SENTENCE = "先查下余额，再给李四和王五各转50元";
    private static final List<String> STACK_LEAK_MARKERS = List.of(
            "java.io.IOException", "Caused by:", "Exception in thread",
            "at java.base/", "at org.springframework.", "at reactor.");
    private static final List<String> TOPICAL = List.of(
            "余额", "账", "转", "李四", "王五", "成功", "失败", "无法", "元");
    private static final List<String> TRANSFER_DONE = List.of(
            "转账成功", "转账信息已处理成功", "transfer_07", "SSTANDARDANSWER", "处理成功");

    /** 直连目标：plan-agent。adapter 由 plan-agent 作为 A2A 工具调用（名=versatile-adapter，url=新 adapter base）。 */
    private static final String PLAN_AGENT = "edpa-plan-agent";
    /** 多 workflow adapter（YAML agent：coords + profile:multi-workflow + fallback 绑定）。 */
    private static final String ADAPTER = "edpa-adapter-multi-workflow";
    private static final long ROUND_TIMEOUT_MS = 600_000L;

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

    @ParameterizedTest(name = "[{index}] {0}")
    @EnumSource(value = MessageProtocol.class, mode = EnumSource.Mode.INCLUDE,
            names = {"A2A_STREAM", "REST_QUERY"})
    @Disabled("There is issue about this scenario")
    @DisplayName("多 workflow 直连 plan-agent：查余额+转账（stepUi）—— A2A_STREAM / REST_QUERY 两流式")
    void balanceThenTransfersMultiWorkflow(MessageProtocol protocol) {
        try (Conversation conv = Conversation.at(
                stack.baseUrl(PLAN_AGENT), stack.serviceUrl("envexplorer"))
                .identity(ConversationIdentity.loadDefault())
                .transport(new ConversationInteractionAdapter(protocol, client(PLAN_AGENT), ROUND_TIMEOUT_MS)
                        // 多 workflow adapter 的下游（envexplorer workflow 端点）按 intent 路由，不经 controller 类型；
                        // 故只禁掉 type=controller —— workspace_id 仍带（仍打在 REST /v1/query URL 与 A2A metadata.query 上）。
                        .disableQueryParam("type"))
                .timeout(Duration.ofSeconds(600))
                .open()) {

            // stepUi 自推进（与 PlanAgentDirectStreamingTest 同 kickoff、同 5 个 manual select）。
            // 差别仅在 adapter 侧：multi-workflow 把 intent 路由到 balance/transfer workflow 端点。
            TurnResult turn = conv.turn(SENTENCE)
                    .intent("")
                    // —— 转账李四 ——
                    .select("on_payee_input",   Map.of("recSerialNum", "SN20240001"))
                    .select("on_paycard_input", Map.of("accIndex", "0"))
                    .select("on_confirm_remit", Map.of("_text", "确定"))
                    // —— 转账王五 ——
                    .select("on_paycard_input", Map.of("accIndex", "0"))
                    .select("on_confirm_remit", Map.of("_text", "确定"))
                    .driveMode(DriveMode.stepUi())
                    .run();

            List<SseEvent> events = turn.allEvents();
            String blob = concat(events);

            for (String m : STACK_LEAK_MARKERS) {
                assertThat(blob).as("SSE 不得泄露 JVM 堆栈").doesNotContain(m);
            }
            assertThat(blob).as("plan-agent 汇总非空").isNotBlank();
            assertThat(TOPICAL.stream().anyMatch(blob::contains))
                    .as("汇总须含 余额/转账/参与者 之一").isTrue();

            assertThat(blob).as("余额笔证据(8200)").contains("8200");
            assertThat(blob).as("收款人 李四").contains("李四");
            assertThat(blob).as("收款人 王五").contains("王五");

            List<String> hit = TRANSFER_DONE.stream().filter(blob::contains).toList();
            System.out.println("[transfer-completion markers hit][" + protocol + "] " + hit);
        }
    }

    private static String concat(List<SseEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (SseEvent e : events) {
            if (e.text() != null) sb.append(e.text());
            if (e.data() != null) e.data().values().forEach(v -> { if (v != null) sb.append(v); });
        }
        return sb.toString();
    }
}

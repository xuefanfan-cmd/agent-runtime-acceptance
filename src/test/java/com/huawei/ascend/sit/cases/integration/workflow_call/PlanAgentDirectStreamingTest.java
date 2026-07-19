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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 直连 edpa-plan-agent 的转账场景验收（openjiuwen profile 限定；A2A/REST 流式参数化）。
 *
 * <p>与 {@link TransferAfterBalanceAcceptanceTest} 同一句话、同 5 个 manual select、同 stepUi 自推进，差别只在
 * 出站路径：<b>不经 edpa-gateway 中转</b>，由 {@link ConversationInteractionAdapter} 把 Conversation 的动态驱动
 * 循环直接接到 plan-agent 的 A2A 流式（{@link MessageProtocol#A2A_STREAM}，JSON-RPC {@code SendStreamingMessage}）
 * 或 REST 流式（{@link MessageProtocol#REST_QUERY}，{@code POST /v1/query} {@code stream:true}）线上。该 adapter
 * 复用 {@code InteractionFlow} 的传输层（{@code A2aStreamingTransport}/{@code RestQueryTransport}），只做
 * Conversation SPI ↔ InteractionFlow wire 的桥接，无新线逻辑。
 *
 * <p><b>精简栈</b>：仅 edpa-adapter + edpa-plan-agent(downstream→edpa-adapter)，envexplorer 由 edpa-adapter 的
 * service-bindings 自动拉起——<b>不起 edpa-gateway</b>。{@code Conversation.at} 第一参数（gateway base URL）对本
 * adapter 无意义（adapter 用注入的 client 定址），填 plan-agent base URL 仅为语义诚实；第二参数仍是 envexplorer
 * 中台 URL——stepUi 的 step-ui/next-request READ 仍走该通道（与 transport 无关）。
 *
 * <p><b>续轮</b>：adapter 按 {@code InteractionFlow} 契约跨 {@code send} 记忆 prevTaskId/prevState——manual 步
 * ({@code INPUT_REQUIRED}) 后下一 {@code send} 续传同一 A2A taskId；contextId 始终钉在 cid 上（A2A 与 REST 共用，
 * REST 按 {@code conversation_id} 续轮）。
 *
 * <p><b>为何只跑 stepUi 一种驱动器。</b>本用例的目的是验证<b>直连 plan-agent 的出站桥</b>在 A2A/REST 两种流式下成立——
 * 驱动器维度（stepUi vs SCRIPT）已在 {@link TransferAfterBalanceAcceptanceTest} 覆盖，此处不复刻 SCRIPT 以免无谓加倍
 * 真机 LLM 运行；如需 SCRIPT 直连变体，按同型加一个 {@code @ParameterizedTest} 即可。
 *
 * <p><b>首轮校准点（无真机 LLM 无法验证）</b>：(1) plan-agent 接受标准 A2A 文本即一轮输入；(2) taskId(A2A)/
 * conversation_id(REST) 续轮；(3) 直连路径无需 EDPA {@code inputs} 富化；(4) plan-agent 暴露 {@code /v1/query}
 * （否则 REST_QUERY 404——与 gateway REST 模式同一假设）。真机实跑前确认下述被测 jar 已就绪。
 *
 * @see TransferAfterBalanceAcceptanceTest 经 gateway 的同场景对照
 */
@Tag("integration")
class PlanAgentDirectStreamingTest extends BaseManagedStackTest {

    private static final String SENTENCE = "先查下余额，再给李四和王五各转50元";
    private static final List<String> STACK_LEAK_MARKERS = List.of(
            "java.io.IOException", "Caused by:", "Exception in thread",
            "at java.base/", "at org.springframework.", "at reactor.");
    private static final List<String> TOPICAL = List.of(
            "余额", "账", "转", "李四", "王五", "成功", "失败", "无法", "元");
    // 转账完成态标记：首轮仅采集（System.out），确认出现后提升为硬断言
    private static final List<String> TRANSFER_DONE = List.of(
            "转账成功", "转账信息已处理成功", "transfer_07", "SSTANDARDANSWER", "处理成功");

    /** 直连目标：plan-agent。adapter 用其 getBaseUrl() 定 REST 端点、用其 sendMessage 驱动 A2A 线。 */
    private static final String PLAN_AGENT = "edpa-plan-agent";
    /** adapter 每轮解析超时——LLM 多工具轮较慢，与 Conversation 600s 超时对齐。 */
    private static final long ROUND_TIMEOUT_MS = 600_000L;

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

    /**
     * 直连 plan-agent 的查余额+转账（stepUi 自推进），参数化覆盖两种流式线协议。
     *
     * <p>与 {@link TransferAfterBalanceAcceptanceTest#balanceThenTransfers()} 同 kickoff、同 5 个 manual select
     * （李四 3 + 王五 2），差别只在 transport：{@link ConversationInteractionAdapter} 直接打到 plan-agent，绕过
     * gateway。断言同型：不泄露 JVM 堆栈、汇总非空且主题相关、余额 8200、收款人 李四/王五；转账完成态首轮软捕获。
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @EnumSource(value = MessageProtocol.class, mode = EnumSource.Mode.INCLUDE,
            names = {"A2A_STREAM", "REST_QUERY"})
    @DisplayName("直连 plan-agent：查余额+转账（stepUi）—— A2A_STREAM / REST_QUERY 两流式")
    void balanceThenTransfersDirect(MessageProtocol protocol) {
        try (Conversation conv = Conversation.at(
                stack.baseUrl(PLAN_AGENT), stack.serviceUrl("envexplorer"))
                .identity(ConversationIdentity.loadDefault())
                .transport(new ConversationInteractionAdapter(protocol, client(PLAN_AGENT), ROUND_TIMEOUT_MS))
                .timeout(Duration.ofSeconds(600))
                .open()) {

            // stepUi 自推进：kickoff 后每步查中台 step-ui → 渲染 next-request → 经 adapter 直发 plan-agent 转发推进 envexplorer。
            // balance on_balance_detail 纯展示（无 selection_key）→ 自动放行、不占 select 槽。
            // transfer 三处 manual 步按 encounter 顺序注入 select；plan-agent 在李四腿走完后自动触发王五腿第一步。
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

            // —— 断言（与 TransferAfterBalance stepUi 用例一致）——
            assertThat(blob).as("余额笔证据(8200)").contains("8200");
            assertThat(blob).as("收款人 李四").contains("李四");
            assertThat(blob).as("收款人 王五").contains("王五");

            // —— 证据采集（首轮软捕获，确认后提升为硬断言）——
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

package com.huawei.ascend.sit.cases.integration.workflow_call;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.config.TestEnvironment;
import com.huawei.ascend.sit.conversation.Conversation;
import com.huawei.ascend.sit.conversation.ConversationIdentity;
import com.huawei.ascend.sit.conversation.DriveMode;
import com.huawei.ascend.sit.conversation.SseEvent;
import com.huawei.ascend.sit.conversation.TurnResult;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * workflow-openjiuwen 拓扑端到端验收（openjiuwen profile 限定）。
 *
 * <p>4 服务合并进<b>一个 {@link SutStack}</b>：envexplorer(容器，由 versatile-call 的 service-bindings 自动拉起)
 * + edpa-versatile-call + edpa-plan-agent(ReAct) + edpa-gateway。conversation client 经 edpa-gateway 发一句话，
 * 由 plan-agent 自分解为 余额查询 + 转账(李四/王五) 多次调用 versatile-call→envexplorer。
 *
 * <p><b>接线</b>：plan-agent 用 {@code downstream("edpa-versatile-call")} 注入位置槽
 * {@code openjiuwen.service.a2a.remote-agents[0].url}；gateway 用重载
 * {@code downstream("edpa-plan-agent", "workflow-openjiuwen.gateway.plan-agent-base-url")} 把 plan-agent 的
 * 解析后 base URL 注入到 gateway 自定义属性键（gateway 自己拼 {@code /a2a}）。这样整个拓扑在 {@code buildStack}
 * 里静态声明完，可继承 {@link BaseManagedStackTest}（原子 {@code buildStack(config).start()}）。
 *
 * <p><b>网关协议（一套用例两模式）</b>：gateway 往 plan-agent 的下行报文格式由
 * {@code workflow-openjiuwen.gateway.plan-agent-protocol} 控制——{@code a2a}（默认，JSON-RPC
 * {@code SendStreamingMessage} 到 {@code /a2a}，带 INPUT_REQUIRED taskId 续轮缓存）或 {@code rest}
 * （{@code POST /v1/query}，按 {@code conversation_id} 影子任务续轮，无 taskId 缓存）。该值由
 * {@link #gatewayProtocol()} 从 {@code -DGATEWAY_PROTOCOL=...}（或同名环境变量）解析，缺省 {@code a2a}，注入到
 * edpa-gateway。{@code buildStack}/流/断言<b>与模式无关</b>——gateway 的入站契约和回包 SSE（{@code {event,data}}）
 * 两种模式一致——故<b>同一套用例</b>跑两次即可覆盖两模式：
 * <pre>
 *   ./mvnw test -Dtest.env=openjiuwen                                # A2A 模式（默认）
 *   ./mvnw test -Dtest.env=openjiuwen -DGATEWAY_PROTOCOL=rest        # REST 模式
 * </pre>
 * REST 模式 E2E 依赖 plan-agent 运行时暴露 {@code /v1/query}（同源 runtime 的 QueryMvcController）——网关侧
 * REST 客户端已单测覆盖，真机确认待 LLM keys 实跑。
 *
 * <p><b>为何不像 ExpenseReviewAcceptanceTest 那样参数化 A2A/REST 流式。</b>本用例走 {@link Conversation} 客户端，
 * 其出站 transport 目前只有 {@code RestVersatileTransport}（{@link com.huawei.ascend.sit.transport.MessageProtocol#REST_VERSATILE}），
 * <b>没有 A2A 流式 ConversationTransport</b>——故无法像 {@link InteractionFlow} 那样以 {@code .protocol(A2A_STREAM/REST_QUERY)}
 * 参数化。这里的"两模式"维度是<b>网关下行协议</b>（gateway→plan-agent 的 a2a/rest，见上），且为 stack 级属性（{@code @BeforeAll}
 * 构栈一次），靠 {@code -DGATEWAY_PROTOCOL} 跑两遍覆盖，而非 JUnit 参数化。待新增 A2A 版 ConversationTransport 后方可同型参数化。
 *
 * <p><b>profile 门</b>：{@code buildStack} 第一行 {@code assumeTrue(openjiuwen)} —— 非 openjiuwen 在基类
 * {@code .start()} 之前就 abort，不拉任何容器；{@code stack} 留 null，{@code tearDownStack} null-safe。
 *
 * <p><b>活跃 advance（stepUi）</b>：一句话 kickoff 后用 {@link DriveMode#stepUi()} 自推进。每步：中台 step-ui 裁定
 * auto/manual/终态 → 渲染 next-request → 以 {@code intent="LATEST"} POST 给 gateway→plan-agent，由 plan-agent
 * 转发给 versatile 推进 envexplorer 一步。envexplorer<b>全程共用同一 cid</b>、每个场景到 END 删 cid 行、plan-agent
 * 下一次 versatile 调用按 intent 重绑新场景——{@code driveStepUi} 因此<b>天然跨腿</b>（下一轮 step-ui 看到的是新腿的
 * pending 步，非终态），只有所有腿跑完、cid 不再重绑才真正终态。
 *
 * <p><b>select（位置序）</b>：balance {@code on_balance_detail} 是 manual 但<b>纯展示</b>（无 selection_key）→ 自动
 * 放行、不占 select 槽。transfer 三处 manual 步各占一个 select：{@code on_payee_input}(recSerialNum)、
 * {@code on_paycard_input}(accIndex)、{@code on_confirm_remit}(_text=确定)；李四 + 王五 两腿共 6 个 select，
 * 按 encounter 顺序声明。select 的 label 是 step_id 漂移校验——plan-agent 跳步/重排会以"选择标注漂移"硬错误暴露。
 *
 * <p><b>运行期依赖（非静态可定）</b>：每步 advance 靠 plan-agent 转发并在 manual 步暂停等测试注入 select。若 plan-agent
 * 推平 manual 步或不转发，select 位置序错位会硬失败（便于诊断）。跨腿依赖 plan-agent 同步触发下一腿第一步。
 *
 * <p>断言分层：硬断言=拓扑/接线/流不崩/主题相关/余额 8200/收款人 李四·王五；
 * 转账完成态首轮作证据采集（写日志），确认后提升为硬断言。
 */
@Tag("integration")
@Feature("FEAT-002: 异构智能体框架兼容")
@Stories({
        @Story("wf.verpkt-gateway-rest: ReAct 智能体经 gateway 入口远程编排REST"),
        @Story("wf.verpkt-gateway-a2a: ReAct 智能体经 gateway 入口远程编排A2A")
})
class TransferAfterBalanceAcceptanceTest extends BaseManagedStackTest {

    private static final String SENTENCE = "先查下余额，再给李四和王五各转50元";
    private static final List<String> STACK_LEAK_MARKERS = List.of(
            "java.io.IOException", "Caused by:", "Exception in thread",
            "at java.base/", "at org.springframework.", "at reactor.");
    private static final List<String> TOPICAL = List.of(
            "余额", "账", "转", "李四", "王五", "成功", "失败", "无法", "元");
    // 转账完成态标记：首轮仅采集（System.out），确认出现后提升为硬断言
    private static final List<String> TRANSFER_DONE = List.of(
            "转账成功", "转账信息已处理成功", "transfer_07", "SSTANDARDANSWER", "处理成功");

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // 第一行 gate：非 openjiuwen 在基类 .start() 之前 abort，不拉容器。
        Assumptions.assumeTrue(TestEnvironment.current() == TestEnvironment.OPENJIUWEN,
                "openjiuwen profile only — re-run with -Dtest.env=openjiuwen (需 LLM_API_KEY 等)");
        String protocol = gatewayProtocol();
        System.out.println("[gateway-protocol] " + protocol
                + " (override via -DGATEWAY_PROTOCOL=a2a|rest)");
        return SutStack.builder(config)
                .agent("edpa-adapter")
                .agent("edpa-plan-agent", a -> a.downstream("edpa-adapter"))
                .agent("edpa-gateway", a -> a
                        .downstream("edpa-plan-agent", "versatile-orchestration.gateway.plan-agent-base-url")
                        // 下行报文格式：a2a（默认）/a2a 或 rest /v1/query。流/断言与模式无关。
                        .property("versatile-orchestration.gateway.plan-agent-protocol", protocol));
    }

    /**
     * 解析 gateway→plan-agent 的下行协议：优先 {@code -DGATEWAY_PROTOCOL} 系统属性，其次同名环境变量，
     * 缺省 {@code a2a}。返回值小写归一，注入到 edpa-gateway 的 {@code plan-agent-protocol}。一套用例两模式
     * 的运行维度旋钮——见类 javadoc。
     */
    private static String gatewayProtocol() {
        String sys = System.getProperty("GATEWAY_PROTOCOL");
        if (sys != null && !sys.isBlank()) {
            return sys.trim().toLowerCase(java.util.Locale.ROOT);
        }
        String env = System.getenv("GATEWAY_PROTOCOL");
        if (env != null && !env.isBlank()) {
            return env.trim().toLowerCase(java.util.Locale.ROOT);
        }
        return "a2a";
    }

    @Test
    @DisplayName("查余额+转账：plan-agent 自分解 → 拓扑通 + 余额(8200) + 收款人(李四/王五)；转账完成态作证据采集")
    void balanceThenTransfers() {
        try (Conversation conv = Conversation.at(
                stack.baseUrl("edpa-gateway"), stack.serviceUrl("envexplorer"))
                .identity(ConversationIdentity.loadDefault())
                .timeout(Duration.ofSeconds(600))
                .open()) {

            // stepUi 自推进：kickoff 后每步查中台 step-ui → 渲染 next-request → POST 给 plan-agent 转发推进 envexplorer。
            // balance on_balance_detail 纯展示（无 selection_key）→ 自动放行、不占 select 槽；balance 3 步自推进。
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

            // —— 断言（很可能，首轮后可校准）——
            assertThat(blob).as("余额笔证据(8200)").contains("8200");
            assertThat(blob).as("收款人 李四").contains("李四");
            assertThat(blob).as("收款人 王五").contains("王五");

            // —— 证据采集（首轮软捕获，确认后提升为硬断言）——
            List<String> hit = TRANSFER_DONE.stream().filter(blob::contains).toList();
            System.out.println("[transfer-completion markers hit] " + hit);
        }
    }

    /**
     * <b>SCRIPT 步计数驱动</b>版（与 {@link #balanceThenTransfers()} 同拓扑、同 kickoff、同 5 个 manual select）。
     *
     * <p>与 stepUi 的差别仅在驱动器：SCRIPT <b>不查中台 step-ui</b>，按声明的 {@code advance/select}
     * 序列逐条 {@code next-request → POST}。select 的 label 在 SCRIPT 下<b>仅记录不校验</b>（步漂移不会硬失败，
     * 这是 SCRIPT 的已知弱点；步漂移检测是 stepUi 的 {@code consumeSelection} 职责）。
     *
     * <p><b>指令编排依据</b>（来自 envexplorer 场景定义）：plan-agent 的 versatile 委托会把 envexplorer 的
     * auto/纯展示 步<b>打包推进到下一个 input-required 边界</b>再中断——故<b>一个框架 POST = 跨到下一个 manual 步</b>，
     * auto 步无需单独 advance。kickoff 一句话已驱动到 balance 的纯展示步 {@code on_balance_detail}
     * （manual 但无 selection_key → 需一次 advance 放行）；该 advance 同时把 balance 推到 END 并跨腿到 transfer 李四
     * 的 {@code on_payee_input}。之后 5 个 select 与 stepUi 用例<b>同序同值</b>：李四 3 个 + 王五 2 个
     * （王五 payee 由 {@code context_builder} 的 {@code skipTo='paycard_input'} 预解析，故跳过 {@code on_payee_input}）。
     * 跨腿发生在 on_confirm_remit 的恢复 POST 内（李四 END → versatile 返回 → plan-agent 继续 → 王五第一步）。
     *
     * <p><b>cid-gap</b>：SCRIPT 与 stepUi 共用 {@link com.huawei.ascend.sit.conversation.mid.MidConversationSupport}
     * 的 {@code getOpt} 容错，跨腿行为一致（plan-agent 在 3s 重试窗口内重绑同一 cid）。{@code untilDone()} 让脚本跑到
     * 工作流自然结束（next-request 返回 null）或指令耗尽。
     *
     * <p><b>首轮校准点</b>：开头 advance 数取决于 versatile 是否把 auto/展示步打包到 manual 边界（此处按"打包"模型取 1 次）。
     * 若首轮实测 balance 在 {@code on_balance_detail} 不中断（kickoff 直接跨到 on_payee_input），改为 0 次；若 versatile
     * 一次只推一步，改为 2 次。select 的 label/值与 stepUi 用例一致，无需校准。
     */
    @Test
    @DisplayName("查余额+转账（SCRIPT 步计数）：同拓扑同 kickoff，1 advance(balance 展示步) + 5 select(李四3/王五2)")
    void balanceThenTransfersScript() {
        try (Conversation conv = Conversation.at(
                stack.baseUrl("edpa-gateway"), stack.serviceUrl("envexplorer"))
                .identity(ConversationIdentity.loadDefault())
                .timeout(Duration.ofSeconds(600))
                .open()) {

            // SCRIPT：select 必须声明在 DriveMode.script() 构建器内（Turn.select() 仅 stepUi 消费）。
            TurnResult turn = conv.turn(SENTENCE)
                    .intent("")
                    .driveMode(DriveMode.script()
                            // balance on_balance_detail(纯展示, 无 selection_key) 放行 → END → 跨腿 transfer 李四 on_payee_input
                            .advance(2)
                            // —— 转账李四 ——
                            .advance(1)
                            .select("on_payee_input",   Map.of("recSerialNum", "SN20240001"))
                            .select("on_paycard_input", Map.of("accIndex", "0"))
                            .advance(1)
                            .select("on_confirm_remit", Map.of("_text", "确定"))
                            .advance(1)
                            // —— 转账王五（on_confirm_remit 的 POST 内跨腿；payee 由 skipTo 预解析，无 on_payee_input）——
                            .advance(1)
                            .select("on_paycard_input", Map.of("accIndex", "0"))
                            .advance(1)
                            .select("on_confirm_remit", Map.of("_text", "确定"))
                            .untilDone())
                    .run();

            List<SseEvent> events = turn.allEvents();
            String blob = concat(events);

            for (String m : STACK_LEAK_MARKERS) {
                assertThat(blob).as("SSE 不得泄露 JVM 堆栈").doesNotContain(m);
            }
            assertThat(blob).as("plan-agent 汇总非空").isNotBlank();
            assertThat(TOPICAL.stream().anyMatch(blob::contains))
                    .as("汇总须含 余额/转账/参与者 之一").isTrue();

            // —— 断言（与 stepUi 用例一致；SCRIPT 下 select label 仅记录不校验）——
            assertThat(blob).as("余额笔证据(8200)").contains("8200");
            assertThat(blob).as("收款人 李四").contains("李四");
            assertThat(blob).as("收款人 王五").contains("王五");

            // —— 证据采集（首轮软捕获，确认后提升为硬断言）——
            List<String> hit = TRANSFER_DONE.stream().filter(blob::contains).toList();
            System.out.println("[transfer-completion markers hit] " + hit);
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

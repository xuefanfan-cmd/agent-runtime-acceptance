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
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;
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
 * 直连 edpa-plan-agent 的转账场景验收：{@code /v1/query/reactive}（WebFlux）与 {@code /v1/query}（MVC）流式等价对照。
 *
 * <p>与 {@link PlanAgentDirectStreamingTest} 同 kickoff、同 5 个 manual select、同 stepUi 自推进、同断言；差别只在
 * transport 维度参数化为 {@link MessageProtocol#REST_QUERY}（{@code POST /v1/query}）与
 * {@link MessageProtocol#REST_REACTIVE}（{@code POST /v1/query/reactive}）。两 controller 报文逐字节同构（共用
 * {@code QuerySseSupport} + 同一 {@code ServeOrchestrator}），故同断言全过即等价证明。
 *
 * <p><b>前置</b>：plan-agent SUT 须以 {@code openjiuwen.service.query.webflux.enabled=true} 启动（用户已重建 jar 放开）。
 *
 * <p><b>sync 延后</b>：{@code REST_QUERY_SYNC}/{@code REST_REACTIVE_SYNC} 接线已就、单测覆盖，但多选 sync 场景的真机
 * 校准延后——见 spec §9。
 *
 * @see PlanAgentDirectStreamingTest A2A_STREAM/REST_QUERY 直连对照
 */
@Feature("FEAT-001: 标准化智能体服务入口")
@Stories({
        @Story("wf.rest-query: reactive/MVC query 端点流式等价"),
        @Story("wf.rest-a2a-equivalence: REST 入口结果等价")
})
@Tag("integration")
@Disabled("pending rebuilt plan-agent jar with openjiuwen.service.query.webflux.enabled=true; "
        + "remove this annotation to run the {REST_QUERY, REST_REACTIVE} live streaming-equivalence "
        + "matrix (spec §9 — sync real-LLM run deferred). REST_REACTIVE 404s against the current jar.")
class PlanAgentReactiveQueryTest extends BaseManagedStackTest {

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
     * 同 kickoff + 同 5 个 manual select + 同 stepUi 自推进 + 同断言，仅在 transport 维度切换
     * {@code REST_QUERY}（{@code /v1/query}）与 {@code REST_REACTIVE}（{@code /v1/query/reactive}）。
     * 两 protocol 同断言全过 ⇒ WebFlux 与 MVC 两入口对同一活 SUT 产出等价结果。
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @EnumSource(value = MessageProtocol.class, mode = EnumSource.Mode.INCLUDE,
            names = {"REST_QUERY", "REST_REACTIVE"})
    @DisplayName("直连 plan-agent：查余额+转账（stepUi）—— REST_QUERY / REST_REACTIVE 流式等价")
    void balanceThenTransfersReactive(MessageProtocol protocol) {
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

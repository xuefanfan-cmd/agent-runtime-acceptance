package com.huawei.ascend.sit.cases.integration.workflow_call;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.conversation.Conversation;
import com.huawei.ascend.sit.conversation.ConversationIdentity;
import com.huawei.ascend.sit.conversation.ConversationInteractionAdapter;
import com.huawei.ascend.sit.conversation.DriveMode;
import com.huawei.ascend.sit.conversation.SseEvent;
import com.huawei.ascend.sit.conversation.TurnResult;
import com.huawei.ascend.sit.transport.MessageProtocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * balanceThenTransfers 场景的模板方法基类 —— 固定「查余额 + 转账李四/王五」的 stepUi 流程与核心语义/不泄露断言；
 * 子类只 override {@link #buildStack(TestConfig)} 切换栈拓扑/中间件，可选 override {@link #afterCheck} 追加专属检查点。
 *
 * <p><b>流程</b>（{@link #balanceThenTransfers}，{@code final} 不可改）：{@link #openConversation} →
 * {@link #runFlow}（kickoff + 5 个 manual select + stepUi 自推进）→ {@link #assertCoreSemantics}（不泄露 JVM 堆栈 /
 * 汇总非空且主题相关 / 余额 8200 / 收款人 李四·王五）→ {@link #afterCheck} 钩子（默认软捕获转账完成态）。
 *
 * <p><b>直连栈假设</b>：仅 edpa-adapter + edpa-plan-agent（plan-agent downstream→adapter），不起 edpa-gateway；
 * envexplorer 由 adapter 的 service-bindings 自动拉起。{@link ConversationInteractionAdapter} 把 Conversation 的动态驱动
 * 循环直接桥到 plan-agent 的 A2A 流式（{@link MessageProtocol#A2A_STREAM}）或 REST 流式（{@link MessageProtocol#REST_QUERY}）线上，
 * 复用 InteractionFlow 传输层，无新线逻辑。续轮：A2A taskId / REST conversation_id 跨 {@code send} 续传；contextId 钉在 cid 上。
 *
 * <p><b>四个 seam</b>：
 * <ul>
 *   <li>{@link #buildStack} —— 继承自 {@link BaseManagedStackTest}，本类<b>不</b>实现，仍 {@code abstract}，
 *       叶子类决定栈拓扑/中间件（如 redis profile / multi-workflow 绑定）。非目标环境在叶子 {@code buildStack} 第一行 {@code assumeTrue} gate。</li>
 *   <li>{@link #openConversation} —— conv 构造 / transport seam，有默认实现（直连 plan-agent 标准线）；叶子类 override 改 transport
 *       （如 multi-workflow 禁 {@code type} 查询参）或 base URL（如 gateway 变体）。</li>
 *   <li>{@link #afterCheck} —— 后置检查点 seam，默认软捕获转账完成态标记（{@code System.out} 打印命中清单）；叶子类 override 追加专属断言
 *       （如 Redis 键空间门禁 + 完成态硬断言）。</li>
 *   <li>{@link #protocols} —— 参数化协议集 seam，默认 A2A_STREAM + REST_QUERY；<b>非静态</b>——靠 {@code @TestInstance(PER_CLASS)}
 *       生效，叶子类 override 多态命中（如换 {@link MessageProtocol#REST_GATEWAY}）。由 {@link BalanceTransfersProtocolProvidersTest} 兜底锁定。</li>
 * </ul>
 *
 * <p><b>为何只跑 stepUi 一种驱动器</b>：SCRIPT 驱动器维度已在 {@code TransferAfterBalanceAcceptanceTest} 覆盖，
 * 此处不复刻以免无谓加倍真机 LLM 运行；如需 SCRIPT 直连变体，按同型在叶子类加一个 {@code @ParameterizedTest} 即可。
 *
 * @see PlanAgentDirectStreamingTest 纯直连变体（无中间件）
 * @see PlanAgentDirectStreamingRedisTest 直连 + redis 中间件变体
 * @see MultiWorkflowDirectStreamingTest 直连 + 多 workflow 路由变体（@Disabled）
 */
@Tag("integration")
abstract class AbstractBalanceThenTransfersTest extends BaseManagedStackTest {

    protected static final String SENTENCE = "先查下余额，再给李四和王五各转50元";
    protected static final List<String> STACK_LEAK_MARKERS = List.of(
            "java.io.IOException", "Caused by:", "Exception in thread",
            "at java.base/", "at org.springframework.", "at reactor.");
    protected static final List<String> TOPICAL = List.of(
            "余额", "账", "转", "李四", "王五", "成功", "失败", "无法", "元");
    /** 转账完成态标记候选；基类软捕获，redis 变体在 {@link #afterCheck} override 提升为硬断言。*/
    protected static final List<String> TRANSFER_DONE = List.of(
            "转账成功", "转账信息已处理成功", "transfer_07", "SSTANDARDANSWER", "处理成功");

    /** 直连目标：plan-agent。adapter 用其 getBaseUrl() 定 REST 端点、用其 sendMessage 驱动 A2A 线。*/
    protected static final String PLAN_AGENT = "edpa-plan-agent";
    /** adapter 每轮解析超时——LLM 多工具轮较慢，与 Conversation 600s 超时对齐。*/
    protected static final long ROUND_TIMEOUT_MS = 600_000L;

    // buildStack(TestConfig) 不在此实现 —— 继承自 BaseManagedStackTest 仍为 abstract，叶子类 override 它切换栈拓扑/中间件。

    /**
     * 本变体驱动的协议集；叶子可 override 以驱动不同的线（如直连 plan-agent gateway 端点的
     * {@link MessageProtocol#REST_GATEWAY}）。<b>非静态</b>——靠 {@link com.huawei.ascend.sit.base.BaseManagedStackTest}
     * 的 {@code @TestInstance(PER_CLASS)} 生效，子类 override 多态命中。默认 = 原行为（A2A_STREAM + REST_QUERY）。
     */
    protected Stream<MessageProtocol> protocols() {
        return Stream.of(MessageProtocol.A2A_STREAM, MessageProtocol.REST_QUERY);
    }

    /**
     * 模板方法：openConversation → runFlow → assertCoreSemantics → afterCheck。{@code final} 锁骨架，子类不得 override；
     * 变体差异一律走 {@link #buildStack}（栈/中间件）与 {@link #afterCheck}（额外检查点）。
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("protocols")
    @DisplayName("查余额+转账（stepUi）")
    protected final void balanceThenTransfers(MessageProtocol protocol) {
        try (Conversation conv = openConversation(protocol)) {
            String blob = runFlow(conv);
            assertCoreSemantics(blob);
            afterCheck(blob, protocol);
        }
    }

    /**
     * 后置检查点钩子。默认：软捕获转账完成态标记（打印命中清单，不硬断言）。叶子类 override 以追加专属断言
     * （如 Redis 键空间门禁），并把完成态标记按需提升为硬断言。override <b>不</b>调用 super（默认仅打印，避免重复）。
     */
    protected void afterCheck(String blob, MessageProtocol protocol) {
        System.out.println("[transfer-completion markers hit][" + protocol + "] "
                + TRANSFER_DONE.stream().filter(blob::contains).toList());
    }

    /**
     * 构造并打开直连 plan-agent 的 {@link Conversation}（seam：conv 构造 / transport）。默认：plan-agent base URL +
     * envexplorer 中台 + 标准 {@link ConversationInteractionAdapter}（A2A 流式 / REST 流式）。叶子类按需 override 改 transport
     * （如 multi-workflow 禁 {@code type} 查询参）或 base URL（如 gateway 变体）。
     *
     * <p><b>注意</b>：override 须整段重抄 conv 构造；基类 conv 构造将来若变更（如加 {@code .maxInteractions(...)}），
     * 所有 override 叶子须手动同步。
     */
    protected Conversation openConversation(MessageProtocol protocol) {
        // Conversation.at 第一参数（gateway base URL）对本 adapter 无意义（adapter 用注入的 client 定址），
        // 填 plan-agent base URL 仅为语义诚实；第二参数是 envexplorer 中台 URL（stepUi 的 READ 走该通道，与 transport 无关）。
        return Conversation.at(stack.baseUrl(PLAN_AGENT), stack.serviceUrl("envexplorer"))
                .identity(ConversationIdentity.loadDefault())
                .transport(new ConversationInteractionAdapter(protocol, client(PLAN_AGENT), ROUND_TIMEOUT_MS))
                .timeout(Duration.ofSeconds(600))
                .open();
    }

    // --- 不变部分（私有）---

    /** stepUi 自推进：kickoff 后每步查中台 step-ui → 渲染 next-request → 经 adapter 直发 plan-agent 推进 envexplorer。*/
    private static String runFlow(Conversation conv) {
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
        return concat(turn.allEvents());
    }

    /** 参考用例的语义/不泄露硬断言（gateway/direct/redis 通用）。*/
    private static void assertCoreSemantics(String blob) {
        for (String m : STACK_LEAK_MARKERS) {
            assertThat(blob).as("SSE 不得泄露 JVM 堆栈").doesNotContain(m);
        }
        assertThat(blob).as("plan-agent 汇总非空").isNotBlank();
        assertThat(TOPICAL.stream().anyMatch(blob::contains))
                .as("汇总须含 余额/转账/参与者 之一").isTrue();
        assertThat(blob).as("余额笔数据(8200)").contains("8200");
        assertThat(blob).as("收款人 李四").contains("李四");
        assertThat(blob).as("收款人 王五").contains("王五");
    }

    private static String concat(List<SseEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (SseEvent e : events) {
            if (e.text() != null) {
                sb.append(e.text());
            }
            if (e.data() != null) {
                e.data().values().forEach(v -> { if (v != null) sb.append(v); });
            }
        }
        return sb.toString();
    }
}

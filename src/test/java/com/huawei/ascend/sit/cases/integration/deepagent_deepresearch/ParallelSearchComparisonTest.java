package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.conversation.RemoteInvocationProbe;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.transport.InboundEvent;
import com.huawei.ascend.sit.transport.MessageProtocol;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DA-09 — parallel-search profile COMPARISON 并行触发验收。
 *
 * <p>参考 {@code docs/cases/deepagent/DA-09-parallel-search-comparison.md} 与
 * {@code docs/superpowers/specs/2026-07-29-parallel-search-trigger-design.md}。
 *
 * <p>给 deep-research 激活 parallel-search profile、search 走 stub fixture、verify 一并拉起(ReAct 判官,
 * root 真调而非走兜底),发一句 COMPARISON 查询;root 应在同一个 assistant turn 批量发出多个 per-vendor
 * search-agent 调用,runtime 经 parentContextId 并发分发。客户端只发 kickoff、等终态 —— search/verify 均
 * 无状态自动完成,不需要并发续轮(区别于 parallel-transfer,后者因需并发续轮而 @Disabled)。
 *
 * <p><b>走 InteractionFlow(参照 {@code StreamingTravelPlanningTest})</b>:交互(send/await/answer)经
 * {@link InteractionFlow} 驱动,用 {@code @ParameterizedTest + @EnumSource} 参数化线协议,便于后续逐步放开。
 * 本期仅 {@link MessageProtocol#A2A_STREAM}(实测后逐步放开 A2A_SYNC / REST)。终态/报告断言协议中立;
 * DA-09.B 的 fan-out(_remote_invocation)是 A2A 远端工具分发独有产物,参照 {@code assertStreamTrajectory}
 * 仅在 A2A_STREAM 生效,其余协议 no-op。
 *
 * <p><b>栈构建</b>:deep-research-auto(隔离别名,不影响老的 deep-research 用例)已在 application-openjiuwen.yml
 * 声明 {@code remote-agents-prefix} 并预置 [0]/[1] 两个 name,故单 stack + {@code .downstreams(SEARCH, VERIFY)}
 * 即可 —— 框架自动先起两个叶子、等就绪,再把各自 baseUrl 注入 {@code --openjiuwen.service.a2a.remote-agents[0]/[1].url}。
 * 由 {@link BaseManagedStackTest} 统一 {@code .start()/.close()} + {@code @TestInstance(PER_CLASS)} + SessionLabelExtension。
 *
 * <p>断言维度:
 * <ul>
 *   <li>DA-09.A 终态 COMPLETED;</li>
 *   <li>DA-09.B(核心,A2A_STREAM-only)同一 batch ≥2 个不同 toolCallId = 一个 turn 批量发出 + runtime 并发分发;</li>
 *   <li>DA-09.C 多 vendor 对比报告(≥2 vendor 名 + 价格信号词);</li>
 *   <li>DA-09.D bug 标志串缺席(沿用 DA-02/03/06/08 守卫)。</li>
 * </ul>
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-parallel-search")
@Feature("agent-runtime 同轮多 remote A2A 调用并行分发 · parallel-search")
@Story("da.parallel-search: COMPARISON 一个 turn 批量并行 search")
class ParallelSearchComparisonTest extends BaseManagedStackTest {

    private static final Logger LOG = Logger.getLogger(ParallelSearchComparisonTest.class.getName());

    private static final String DEEP_RESEARCH = "deep-research-auto";
    private static final String SEARCH = "search";
    private static final String VERIFY = "verify";

    /** COMPARISON 模式查询 —— fixture 覆盖 deepseek / 通义|qwen / 豆包 三条 route。 */
    private static final String COMPARISON_QUERY =
            "对比 DeepSeek V3、Qwen-Max、Doubao-pro 三家的大模型 API 输入定价";

    // 每个 marker 只出现在 stub fixture 对应 vendor 的结果里,绝不出现在查询串中 ——
    // ≥2 命中证明报告纳入了 ≥2 家的真实搜索结果,而非对查询的回显
    // (qwen-max→通义, 火山方舟→豆包, $0.27→deepseek)。对照 DA-08 用 "DeepSeek-R1"(只存于结果)的同款手法。
    private static final List<String> VENDOR_MARKERS = List.of("qwen-max", "火山方舟", "$0.27");
    // 价格信号:取 fixture 中真实出现的价格格式,均不在查询串中(避免 "定价" 等查询词造成假通过)。
    private static final List<String> PRICE_SIGNAL_WORDS = List.of("元/千 tokens", "million tokens", "$0.27");
    private static final String BUG_MARKER_TASK_EXISTS = "deep_agent_task_1 already exists";
    private static final String BUG_MARKER_CONTROLLER_ERR = "controller task parameter error";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // 单 stack(deep-research-auto 已声明 remote-agents-prefix + [0]/[1] 两个 name):
        // .downstreams(SEARCH, VERIFY) 让框架自动先起两个叶子、等就绪,再分别把 baseUrl 注入
        // --openjiuwen.service.a2a.remote-agents[0].url(search)/[1].url(verify)。
        // search 走 stub;verify 是 ReAct 判官(无工具、单轮,需 LLM_API_KEY),root 真调 verify
        // 而非走「verify-agent unavailable」兜底。由基类统一 start/close + PER_CLASS + SessionLabelExtension。
        return SutStack.builder(config)
                .streaming(true)   // SSE,观测流里的 _remote_invocations 进度事件
                .agent(SEARCH, a -> a.env("SEARCH_AGENT_USE_STUB", "true"))
                .agent(VERIFY)
                .agent(DEEP_RESEARCH, a -> a
                        .downstreams(SEARCH, VERIFY)
                        .profile("parallel-search"));
    }

    /**
     * DA-09 主路径:COMPARISON 查询 → 一个 turn 批量并行 search(fan-out ≥2)→ 多 vendor 报告 COMPLETED。
     *
     * <p>参数化线协议(参照 {@code StreamingTravelPlanningTest});本期仅 {@link MessageProtocol#A2A_STREAM},
     * 实测后逐步放开。终态/报告断言协议中立;fan-out 断言(DA-09.B)仅 A2A_STREAM 生效。
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @EnumSource(value = MessageProtocol.class, mode = EnumSource.Mode.INCLUDE,
            names = {"A2A_STREAM", "A2A_SYNC"})
    @DisplayName("parallel-search: 一个 turn 批量并行 search(fan-out ≥2)→ 多 vendor 报告 COMPLETED")
    void parallelSearchComparisonFanOutAndCompletes(MessageProtocol protocol) {
        InteractionFlow.of(client(DEEP_RESEARCH))
                .withTimeoutMs(config.getPollTimeoutSeconds() * 1000L)
                .protocol(protocol)
                .send(COMPARISON_QUERY)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertThat(assertParallelFanOut(protocol))
                    .assertAnswer(artifact -> {
                        // DA-09.C — 多 vendor 对比报告(协议中立:读离散 ANSWER = 最终对比报告)
                        assertThat(artifact).as("DA-09.C [%s]: 合并 artifact 文本", protocol).isNotBlank();
                        long vendorHits = VENDOR_MARKERS.stream().filter(artifact::contains).count();
                        assertThat(vendorHits)
                                .as("DA-09.C [%s]: artifact 应含 ≥2 vendor 名 %s\nartifact 头 500 字: %s",
                                        protocol, VENDOR_MARKERS, truncate(artifact, 500))
                                .isGreaterThanOrEqualTo(2);
                        boolean hasPrice = PRICE_SIGNAL_WORDS.stream().anyMatch(artifact::contains);
                        assertThat(hasPrice)
                                .as("DA-09.C [%s]: artifact 应至少含一个价格信号词 %s\nartifact 头 500 字: %s",
                                        protocol, PRICE_SIGNAL_WORDS, truncate(artifact, 500))
                                .isTrue();
                        // DA-09.D — bug 标志串缺席
                        assertThat(artifact)
                                .as("DA-09.D [%s]: artifact 不应含已知 bug 标志\nartifact 头 500 字: %s",
                                        protocol, truncate(artifact, 500))
                                .doesNotContain(BUG_MARKER_TASK_EXISTS)
                                .doesNotContain(BUG_MARKER_CONTROLLER_ERR);
                    })
                .execute();
    }

    // ---- helpers ----

    /**
     * DA-09.B(核心,A2A-only):同一 batch ≥2 个不同 toolCallId。{@code _remote_invocation} 是 A2A 远端工具
     * 分发独有产物,REST 无对应;非 {@link MessageProtocol#A2A_STREAM} 时 no-op(终态+报告已由
     * {@code .awaitState/.assertAnswer} 覆盖)。参照 {@code StreamingTravelPlanningTest#assertStreamTrajectory}
     * 的 protocol 门控写法 —— 现仅参数化 A2A_STREAM,该分支是「逐步放开」时的预留门控。
     */
    private static Consumer<InteractionFlow.RoundContext> assertParallelFanOut(MessageProtocol protocol) {
        return ctx -> {
            if (protocol != MessageProtocol.A2A_STREAM) {
                return;
            }
            // InteractionFlow 暴露 List<InboundEvent>;每个 InboundEvent.raw() 携带来源 ClientEvent
            // (A2aEventMapping 注入)。解包去重后喂给既有的 fromClientEvents(不加新 API,参照
            // StreamingTravelPlanningTest 直接在 helper 里消费 ctx.events() 的写法)。
            List<ClientEvent> clientEvents = clientEventsFrom(ctx.events());
            List<RemoteInvocationProbe.ChildRef> fanOut =
                    RemoteInvocationProbe.fromClientEvents(ctx.contextId(), clientEvents);
            LOG.info(String.format("DA-09.B [%s]: fan-out children=%d toolCallIds=%s inboundEvents=%d",
                    protocol, fanOut.size(),
                    fanOut.stream().map(RemoteInvocationProbe.ChildRef::toolCallId).toList(),
                    ctx.events().size()));
            assertThat(fanOut.size())
                    .as("DA-09.B [%s]: 同一 batch 应 ≥2 个不同 toolCallId(一个 turn 批量发出 + runtime 并发分发)\n"
                            + "fanOut=%s\n(若=0:_remote_invocation 未观测到 → 见 spec §8 探活降级)", protocol, fanOut)
                    .isGreaterThanOrEqualTo(2);
        };
    }

    /**
     * 解包每个 {@link InboundEvent#raw()} 携带的 A2A {@code ClientEvent}({@code A2aEventMapping} 注入),
     * 按身份去重({@code toEventList} 把多 part 事件摊平 → 同一 ClientEvent 可能对应多个 InboundEvent)。
     * 非 A2A 事件的 raw 非 ClientEvent,被 {@code instanceof} 丢弃。
     */
    private static List<ClientEvent> clientEventsFrom(List<InboundEvent> events) {
        List<ClientEvent> out = new ArrayList<>();
        for (InboundEvent e : events) {
            if (e.raw() instanceof ClientEvent ce && !out.contains(ce)) {
                out.add(ce);
            }
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}

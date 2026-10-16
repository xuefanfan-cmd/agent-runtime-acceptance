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
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-019 B1 — Python multi-deep-research demo 并行下游派发验收。
 *
 * <p>与 {@link ParallelSearchComparisonTest}(Java jar,stub fixture)同一 FEAT-019 权威条款,
 * 差异只在被测端点:本类指向 <b>框架托管的 Python demo</b>(SIT 通过 {@code SutStack} 直接 spawn/
 * wait-ready/teardown 三个 Python 进程,不再依赖 dev 组的 {@code scripts/smoke_real_llm.py})。
 *
 * <p><b>栈构建</b>:{@link PythonDeepResearchStackSupport} — 三个 Python agent(verify/search/
 * deep-research)由 {@code PythonProcessLauncher} 拉起,{@code SEARCH_AGENT_URL} /
 * {@code VERIFY_AGENT_URL} 由框架按 {@code downstreamEnv} 声明在 spawn 时注入到 deep-research 的环境。
 *
 * <p><b>断言维度(严格锚在 FEAT-019 权威条款)</b>:
 * <ul>
 *   <li>B1.A 终态 COMPLETED(基线:并行派发能收敛到终态);</li>
 *   <li>B1.B(核心)同一 batch ≥2 个不同 toolCallId = 一个 turn 批量发出 + runtime 并发分发;</li>
 *   <li>B1.C artifact 非空(基线:并行 fan-in 汇总回落到最终 answer)。</li>
 * </ul>
 * 不断言 vendor 名 / 价格信号 / bug 标志串 —— 这三条依赖 Java 侧 stub fixture,Python 走真 LLM
 * 无法可复现锚定,且不属于 FEAT-019 权威条款(产品/内容语义,超出规格)。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-parallel-search")
@Tag("python-demo")
@Feature("FEAT-019: 智能体生成并行的下游智能体调用委托")
@Story("FEAT-019.B1: Python multi-deep-research demo 一个 turn 批量 fan-out")
class ParallelSearchComparisonPythonTest extends BaseManagedStackTest {

    private static final Logger LOG = Logger.getLogger(ParallelSearchComparisonPythonTest.class.getName());

    private static final String DEEP_RESEARCH = PythonDeepResearchStackSupport.DEEP_RESEARCH;

    private static final String COMPARISON_QUERY =
            "对比 DeepSeek V3、Qwen-Max、Doubao-pro 三家的大模型 API 输入定价,"
                    + "统一换算为 USD/百万 token,逐项给出价格、上下文窗口、引用来源与置信度。";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return PythonDeepResearchStackSupport.builder(config);
    }

    /**
     * FEAT-019 B1 主路径:COMPARISON 查询 → 一个 turn 批量并行 search(fan-out ≥2)→ COMPLETED。
     *
     * <p>仅参数化 {@link MessageProtocol#A2A_STREAM} —— fan-out 断言(B1.B)读 {@code _remote_invocation}
     * (A2A 远端工具分发独有产物),REST/SYNC 无对应。
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @EnumSource(value = MessageProtocol.class, mode = EnumSource.Mode.INCLUDE, names = {"A2A_STREAM"})
    @DisplayName("FEAT-019 B1 (Python): 一个 turn 批量并行 search(fan-out ≥2) → COMPLETED")
    void parallelSearchComparisonPython(MessageProtocol protocol) {
        // Python 真 LLM + per-token SSE:6 路 search 并发 + 每 token 一帧,观测到 5min 内
        // 一轮 search 还在 URL streaming 中,fan-out 已完成但 fan-in 未收敛。给 15min 覆盖真通道。
        InteractionFlow.of(client(DEEP_RESEARCH))
                .withTimeoutMs(900_000L)
                .protocol(protocol)
                .send(COMPARISON_QUERY)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertThat(assertParallelFanOut(protocol))
                    .assertAnswer(artifact -> {
                        // B1.C — artifact 非空(并行 fan-in 有汇总回落)
                        assertThat(artifact)
                                .as("B1.C [%s]: 最终 artifact 应非空(fan-in 汇总结果)", protocol)
                                .isNotBlank();
                    })
                .execute();
    }

    /**
     * B1.B(核心,A2A-only):同一 batch ≥2 个不同 toolCallId。Python runtime-ext-python 与 Java 同 wire
     * 契约,{@code _remote_invocation.{batchId,toolCallId}} 在 delegation artifact 的 part-metadata 中,
     * 复用 {@link RemoteInvocationProbe#fromClientEvents} 直接解析。
     */
    private static Consumer<InteractionFlow.RoundContext> assertParallelFanOut(MessageProtocol protocol) {
        return ctx -> {
            if (protocol != MessageProtocol.A2A_STREAM) {
                return;
            }
            List<ClientEvent> clientEvents = clientEventsFrom(ctx.events());
            List<RemoteInvocationProbe.ChildRef> fanOut =
                    RemoteInvocationProbe.fromClientEvents(ctx.contextId(), clientEvents);
            LOG.info(String.format("B1.B [%s]: fan-out children=%d toolCallIds=%s inboundEvents=%d",
                    protocol, fanOut.size(),
                    fanOut.stream().map(RemoteInvocationProbe.ChildRef::toolCallId).toList(),
                    ctx.events().size()));
            assertThat(fanOut.size())
                    .as("B1.B [%s]: 同一 batch 应 ≥2 个不同 toolCallId(一个 turn 批量发出 + runtime 并发分发)\n"
                            + "fanOut=%s", protocol, fanOut)
                    .isGreaterThanOrEqualTo(2);
        };
    }

    /**
     * 解包每个 {@link InboundEvent#raw()} 携带的 A2A {@code ClientEvent}(A2aEventMapping 注入),
     * 按身份去重(同一 ClientEvent 可能对应多个 InboundEvent)。非 A2A 事件的 raw 非 ClientEvent,
     * 被 {@code instanceof} 丢弃。参照 {@link ParallelSearchComparisonTest#clientEventsFrom}。
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
}

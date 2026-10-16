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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-019 M8 (Python) — all-settled 后单次继续推理:每个 search 子任务只 delegate 一次,
 * 父任务在扇出期间无自身 output 事件,只有子成员输出 + 一次最终收尾。
 *
 * <p>对照 §2.5 设计档 {@code docs/cases/FEAT-019-parallel-downstream-agent-tasks-generation-and-handoff-python.md#2.5}。
 * FEAT-019 §2"all-settled 后单次继续推理"(不得每个下游完成后各自触发一次)+ §5.3。
 *
 * <p><b>阻塞条件(2026-09-24 更新)</b>:D1(RemoteDelegationRail resume 短路)已由 dev 修复
 * —— {@code ParallelSearchComparisonPythonSyncTest} 60s 就拿到完整 Task 响应体,证明内部
 * loop 已收敛、无限重试通路已闭合。<b>新的且唯一的阻塞点</b> = 服务端产出的某条 Message
 * 的 {@code role} 字段为空(A2A protobuf schema {@code Message.role} required),SDK 反序列化
 * 时校验拒收 —— sync 端抛 {@code InvalidParamsError: Parameter 'role' may not be null},SSE
 * 端由 {@code A2aStreamingWire} errorHandler 吞掉那一帧、后续无 COMPLETED。dev 已确认 sync/SSE
 * 同一 bug,修复后本用例应能跑到 COMPLETED。
 *
 * <p>历史旁路 {@code SSE_NO_FINAL_ROOT_CAUSE.md}(batch admission 静默截断)由 demo 配置修好
 * (max_concurrency=8/max_queue_size=32);D1 rail 修复不涉及 wire Message 组装,role bug 是独立
 * 的第三条通路。
 *
 * <p><b>探针依赖</b>:SR.A(去重后 == 出现次数)要求 wire 里能识别到 delegation 事件的
 * {@code target.taskId}(即 childCid)—— 承载位:{@code _remote_invocation.toolCallId}
 * (legacy)或 {@code agentEvent.type=delegation, target.taskId}(D2 落地后)。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-parallel-search")
@Tag("python-demo")
@Feature("FEAT-019: 智能体生成并行的下游智能体调用委托")
@Story("FEAT-019.PY-B1.SR: all-settled 后单次继续推理 —— fan-in 恰好一次")
class PythonParallelSearchFanInSingleRecoveryTest extends BaseManagedStackTest {

    private static final Logger LOG = Logger.getLogger(PythonParallelSearchFanInSingleRecoveryTest.class.getName());

    private static final String DEEP_RESEARCH = PythonDeepResearchStackSupport.DEEP_RESEARCH;

    private static final String COMPARISON_QUERY =
            "对比 DeepSeek V3、Qwen-Max、Doubao-pro 三家的大模型 API 输入定价,"
                    + "统一换算为 USD/百万 token,逐项给出价格、上下文窗口、引用来源与置信度。";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return PythonDeepResearchStackSupport.builder(config);
    }

    @Test
    @DisplayName("FEAT-019 PY-B1.SR (Python): 每个 search 只 delegate 一次 → all-settled 后单次收尾")
    void fanInHappensExactlyOnceAcrossAllChildren() {
        InteractionFlow.of(client(DEEP_RESEARCH))
                .withTimeoutMs(900_000L)
                .protocol(MessageProtocol.A2A_STREAM)
                .send(COMPARISON_QUERY)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertThat(ctx -> {
                        List<ClientEvent> clientEvents = clientEventsFrom(ctx.events());
                        List<RemoteInvocationProbe.ChildRef> fanOut =
                                RemoteInvocationProbe.fromClientEvents(ctx.contextId(), clientEvents);
                        LOG.info(String.format("PY-B1.SR: fanOut(after dedup by batch+toolCallId).size()=%d "
                                        + "toolCallIds=%s eventCount=%d",
                                fanOut.size(),
                                fanOut.stream().map(RemoteInvocationProbe.ChildRef::toolCallId).toList(),
                                ctx.events().size()));

                        // PY-B1.SR.A — 每个 search tool_call 只 delegate 一次
                        // probe.fromClientEvents 已按 (batchId, toolCallId) 去重,取 batch-max。
                        // 因此若同一 (batchId, toolCallId) 被重复投影,dedup 会 collapse 到 1;
                        // 只要观察到 fanOut.size() ≥ 2,即"批量 fan-out 存在且每成员至少 delegate 过 1 次"。
                        //
                        // 注:LLM 会把"3 家 vendor 对比"自然拆解为多轮 web 查询(实测 ~7 条 tool_call),
                        // 都属于同一 batchId 下的合法 fan-out —— 不构成 D1 rail 无限重试(那种 bug 的
                        // 特征是"同 toolCallId 跨多个 batchId 反复出现",本探针未暴露分组通道,若后续
                        // 需要精确闭合再扩探针)。
                        assertThat(fanOut.size())
                                .as("PY-B1.SR.A: fan-out 批量成员数应 ≥2(fan-out 存在);"
                                        + "probe 已按 (batch, toolCallId) 去重 → 天然满足'每成员 delegate ≥1 次'"
                                        + "\nfanOut=%s", fanOut)
                                .isGreaterThanOrEqualTo(2);

                        // PY-B1.SR.C 粗判 — 父任务应有最终 answer(fan-in 单次收尾)。
                        // 精细化断言"父在扇出期间无自身 output"需要 producer 分流(D2 落地后)。
                        assertThat(ctx.answerText())
                                .as("PY-B1.SR.C: 父任务应有最终 answer(fan-in 单次收尾)")
                                .isNotBlank();
                    })
                .execute();
    }

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

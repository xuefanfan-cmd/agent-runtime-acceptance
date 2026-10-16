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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-019 补充 (Python) — 后续轮次再委托不串线:同 contextId 两轮独立 fan-out,新批次不混旧状态。
 *
 * <p>对照 §2.4 设计档 {@code docs/cases/FEAT-019-parallel-downstream-agent-tasks-generation-and-handoff-python.md#2.4}。
 * FEAT-019 §4 场景"后续轮次再次委托":逐轮前缀树单调只增、根唯一、已完成成员重发不增节点。
 *
 * <p><b>依赖 demo 需求 D4(同 contextId followup 支持)</b>:deep-research 接受
 * {@code SendStreamingMessage} 时若带 {@code contextId} 且该 context 上已有已完成 task,
 * 能作为**新轮**继续推理(新 batch、新 toolCallId、共享同一 parentContextId)。
 * 未落地前挂 {@link Disabled}。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-parallel-search")
@Tag("python-demo")
@Feature("FEAT-019: 智能体生成并行的下游智能体调用委托")
@Story("FEAT-019.PY-B1.MR: 同 contextId 两轮独立 fan-out,新批次不混旧")
@Disabled("waiting on demo requirement D4: same-contextId followup support "
        + "(见 docs/cases/FEAT-019-parallel-downstream-agent-tasks-generation-and-handoff-python.md §3)")
class PythonParallelSearchNextRoundIsolationTest extends BaseManagedStackTest {

    private static final Logger LOG = Logger.getLogger(PythonParallelSearchNextRoundIsolationTest.class.getName());

    private static final String DEEP_RESEARCH = "deep-research";
    private static final String DEEP_RESEARCH_URL = "http://127.0.0.1:18090";

    /** 第一轮:三家主流 vendor 对比。 */
    private static final String ROUND_1_QUERY =
            "对比 DeepSeek V3、Qwen-Max、Doubao-pro 三家的大模型 API 输入定价,"
                    + "统一换算为 USD/百万 token,逐项给出价格。";

    /** 第二轮:切到另外三家(vendor 集合不相交),验证新批次不混旧 toolCallId。 */
    private static final String ROUND_2_QUERY =
            "再对比 GLM-4、Kimi-K2、Baichuan4 三家的大模型 API 输入定价,"
                    + "统一换算为 USD/百万 token,逐项给出价格。";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .streaming(true)
                .remoteAgent(DEEP_RESEARCH, DEEP_RESEARCH_URL);
    }

    @Test
    @DisplayName("FEAT-019 PY-B1.MR (Python): 同 contextId 两轮,fan-out 批次互不相交")
    void nextRoundFanOutDoesNotBleedIntoPrior() {
        String anchorContextId = "ctx-py-b1-mr-" + UUID.randomUUID().toString().substring(0, 8);
        Set<String> round1ToolCallIds = new HashSet<>();
        Set<String> round1TargetTaskIds = new HashSet<>();
        Set<String> round2ToolCallIds = new HashSet<>();
        Set<String> round2TargetTaskIds = new HashSet<>();

        InteractionFlow.of(client(DEEP_RESEARCH))
                .withTimeoutMs(900_000L)
                .withContextId(anchorContextId)
                .protocol(MessageProtocol.A2A_STREAM)
                // Round 1
                .send(ROUND_1_QUERY)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertThat(ctx -> {
                        List<ClientEvent> clientEvents = clientEventsFrom(ctx.events());
                        List<RemoteInvocationProbe.ChildRef> fanOut =
                                RemoteInvocationProbe.fromClientEvents(ctx.contextId(), clientEvents);
                        assertThat(fanOut.size())
                                .as("PY-B1.MR.前置: 第一轮扇出 ≥2\nfanOut=%s", fanOut)
                                .isGreaterThanOrEqualTo(2);
                        fanOut.forEach(ref -> {
                            round1ToolCallIds.add(ref.toolCallId());
                            round1TargetTaskIds.add(ref.childCid());
                        });
                        LOG.info(String.format("PY-B1.MR round1: toolCallIds=%s childCids=%s",
                                round1ToolCallIds, round1TargetTaskIds));
                    })
                // Round 2
                .send(ROUND_2_QUERY)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertThat(ctx -> {
                        List<ClientEvent> clientEvents = clientEventsFrom(ctx.events());
                        List<RemoteInvocationProbe.ChildRef> fanOut =
                                RemoteInvocationProbe.fromClientEvents(ctx.contextId(), clientEvents);
                        assertThat(fanOut.size())
                                .as("PY-B1.MR.前置: 第二轮扇出 ≥2\nfanOut=%s", fanOut)
                                .isGreaterThanOrEqualTo(2);
                        fanOut.forEach(ref -> {
                            round2ToolCallIds.add(ref.toolCallId());
                            round2TargetTaskIds.add(ref.childCid());
                        });
                        LOG.info(String.format("PY-B1.MR round2: toolCallIds=%s childCids=%s",
                                round2ToolCallIds, round2TargetTaskIds));

                        // PY-B1.MR.B — 两轮 toolCallId 集合完全不相交
                        Set<String> intersection = new HashSet<>(round1ToolCallIds);
                        intersection.retainAll(round2ToolCallIds);
                        assertThat(intersection)
                                .as("PY-B1.MR.B: 第一轮与第二轮 toolCallId 集合应完全不相交"
                                        + "\nround1=%s\nround2=%s", round1ToolCallIds, round2ToolCallIds)
                                .isEmpty();

                        // PY-B1.MR.C — childCid(target.taskId)也应不相交(前缀树单调只增)
                        Set<String> cidIntersection = new HashSet<>(round1TargetTaskIds);
                        cidIntersection.retainAll(round2TargetTaskIds);
                        assertThat(cidIntersection)
                                .as("PY-B1.MR.C: 两轮 childCid 应不相交(树按 target 去重后逐轮只增,不重发)"
                                        + "\nround1=%s\nround2=%s",
                                        round1TargetTaskIds, round2TargetTaskIds)
                                .isEmpty();
                    })
                    .assertAnswer(round2Artifact -> {
                        // PY-B1.MR.D — 第二轮 artifact 命中当轮 vendor(GLM/Kimi/Baichuan),不回显 round1 vendor
                        assertThat(round2Artifact)
                                .as("PY-B1.MR.D: 第二轮 artifact 应含当轮 vendor 之一\nartifact 头 500 字: %s",
                                        truncate(round2Artifact, 500))
                                .containsAnyOf("GLM", "Kimi", "Baichuan", "百川");
                    })
                .execute();
        // PY-B1.MR.A 由两次 awaitState(COMPLETED) 承担
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

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}

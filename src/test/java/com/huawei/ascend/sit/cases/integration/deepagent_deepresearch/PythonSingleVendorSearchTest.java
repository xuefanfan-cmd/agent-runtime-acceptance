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
 * FEAT-019 M10 (Python) — 单成员兼容:批次=1 走既有单中断路径,不误聚合为 fan-out。
 *
 * <p>对照 §2.2 设计档 {@code docs/cases/FEAT-019-parallel-downstream-agent-tasks-generation-and-handoff-python.md#2.2}。
 * 与 {@link ParallelSearchComparisonPythonTest}(fan-out ≥2)互补:同载体,只发单 vendor 查询,
 * 验证 runtime 不会把单 tool_call 错误聚合成批量 fan-out(batchId 里成员数应 <2 → probe 返回 empty)。
 *
 * <p><b>不依赖 demo 改造</b>——只要 LLM 对"单 vendor query"生成单 tool_call,现有 demo 就能承载。
 * 若 LLM 偶发把单 vendor 拆成多轮 web 搜索(非 FEAT-019 缺陷),按 §"INCONCLUSIVE"降级。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-parallel-search")
@Tag("python-demo")
@Feature("FEAT-019: 智能体生成并行的下游智能体调用委托")
@Story("FEAT-019.PY-B1.SV: 单 vendor query 走 single-member 路径")
class PythonSingleVendorSearchTest extends BaseManagedStackTest {

    private static final Logger LOG = Logger.getLogger(PythonSingleVendorSearchTest.class.getName());

    private static final String DEEP_RESEARCH = PythonDeepResearchStackSupport.DEEP_RESEARCH;

    /** 单 vendor 查询:LLM 应只生成一个 search tool_call,不构成 fan-out batch。 */
    private static final String SINGLE_VENDOR_QUERY =
            "DeepSeek V3 大模型 API 的输入定价是多少?请给出 USD/百万 token 单位以及引用来源。";

    /**
     * DeepSeek 在 fixture 中真实出现的<b>输入</b>价格标记(不在查询串内,防回显假通过)。
     * fixture 原文:{@code deepseek-chat 输入 $0.14/百万 token,输出 $0.28/百万 token}
     * 查询问的是"输入定价",故锚 $0.14(与查询语义对齐;$0.28 是输出价、非用户所问)。
     */
    private static final String DEEPSEEK_PRICE_MARKER = "$0.14";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return PythonDeepResearchStackSupport.builder(config);
    }

    @Test
    @DisplayName("FEAT-019 PY-B1.SV (Python): 单 vendor query → 单 tool_call → COMPLETED,不聚合为 fan-out")
    void singleVendorSearchGoesSingleMember() {
        InteractionFlow.of(client(DEEP_RESEARCH))
                .withTimeoutMs(900_000L)
                .protocol(MessageProtocol.A2A_STREAM)
                .send(SINGLE_VENDOR_QUERY)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertThat(ctx -> {
                        // PY-B1.SV.B — 单成员批不构成 fan-out
                        // fromClientEvents 语义:batch size < 2 时返回 empty list(见 RemoteInvocationProbe#fanOutFromProjections)
                        // 即"单成员批走既有单中断路径" —— MUST 10 单成员兼容 的黑盒观察面。
                        List<ClientEvent> clientEvents = clientEventsFrom(ctx.events());
                        List<RemoteInvocationProbe.ChildRef> fanOut =
                                RemoteInvocationProbe.fromClientEvents(ctx.contextId(), clientEvents);
                        LOG.info(String.format(
                                "PY-B1.SV.B: single-vendor fanOut.size()=%d (期望 0 = 单成员不构成批量) toolCallIds=%s",
                                fanOut.size(),
                                fanOut.stream().map(RemoteInvocationProbe.ChildRef::toolCallId).toList()));
                        assertThat(fanOut)
                                .as("PY-B1.SV.B: 单 vendor query 不应触发 ≥2 成员的 fan-out batch\n"
                                        + "(单成员批应走既有单中断路径,probe 返回 empty)\nfanOut=%s", fanOut)
                                .isEmpty();
                    })
                    .assertAnswer(artifact -> {
                        // PY-B1.SV.C — artifact 命中 DeepSeek 的真实价格标记(证明结果落到该 vendor)
                        assertThat(artifact)
                                .as("PY-B1.SV.C: 单 vendor artifact 应非空")
                                .isNotBlank();
                        assertThat(artifact)
                                .as("PY-B1.SV.C: 最终 artifact 应含 DeepSeek 定价标记 '%s'\nartifact 头 500 字: %s",
                                        DEEPSEEK_PRICE_MARKER, truncate(artifact, 500))
                                .contains(DEEPSEEK_PRICE_MARKER);
                    })
                .execute();
        // PY-B1.SV.A 由 awaitState(COMPLETED) 承担
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

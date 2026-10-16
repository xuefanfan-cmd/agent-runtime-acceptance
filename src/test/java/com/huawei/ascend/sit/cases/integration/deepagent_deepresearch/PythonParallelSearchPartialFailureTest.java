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
 * FEAT-019 补充 (Python) — 部分失败回灌:一路 search 失败,其他成功 vendor 结果不被覆盖 + 父任务收敛。
 *
 * <p>对照 §2.3 设计档 {@code docs/cases/FEAT-019-parallel-downstream-agent-tasks-generation-and-handoff-python.md#2.3}。
 * FEAT-019 §4 场景"部分失败回灌"+ §5.3(成功项与失败项按 toolCallId 归位;失败不覆盖成功)。
 *
 * <p><b>D3 已由 dev 团队交付(2026-09-23)</b>:search agent 挂了 {@code FailInjectingHandler}
 * ({@code agent-search/src/search_agent/service/fail_injecting_handler.py}),接受环境变量
 * {@code SEARCH_AGENT_FAIL_VENDOR}(**大小写不敏感的子串匹配**,匹配 last_user_query),命中即以
 * {@code QueryChunk.of_error(code="SEARCH_AGENT_FAIL_INJECTED")} 短路 → chunk_mapper TYPE_ERROR →
 * 下游 search 子任务 A2A 帧带 {@code TaskStatus(state=TASK_STATE_FAILED)}。续接请求
 * ({@code request.is_resume=True})不注入,不破坏 batch 屏障。
 *
 * <p><b>fixture 覆盖仅三家</b>:{@code DeepSeek / GLM / 豆包}(见 {@code web_search_tool.py._FIXTURES})。
 * 故 query / INJECTED_FAIL_VENDOR / SURVIVING_VENDOR_MARKERS 三者必须从这三家里挑
 * (Qwen/Doubao-pro 之类不在 fixture,注入不成立、survivor 无法回显)。
 *
 * <p><b>本类仍锁 @Disabled,blocker 已刷新为 Python 服务端 Message.role 缺失(2026-09-24)</b>:
 * D3 侧已就绪,D1(RemoteDelegationRail resume 短路)也已由 dev 修好
 * ({@code ParallelSearchComparisonPythonSyncTest} 60s 拿到完整 Task 响应体的实证)。
 * 剩下的唯一阻塞点 = 服务端产出的某条 Message(疑似终态帧)未赋 {@code role=ROLE_AGENT},
 * A2A SDK protobuf schema 校 required 字段拒收 —— sync 端抛
 * {@code InvalidParamsError: Parameter 'role' may not be null},SSE 端由
 * {@code A2aStreamingWire} errorHandler 吞掉该帧后再无 COMPLETED。dev 已确认 sync/SSE 同一 bug。
 *
 * <p><b>运行约定</b>:{@link PythonDeepResearchStackSupport} 拉起 search-agent 时,
 * 本类通过 {@code searchAgentCustomizer} 直接注入
 * {@code SEARCH_AGENT_FAIL_VENDOR=DeepSeek}(与本类 {@link #INJECTED_FAIL_VENDOR} 对齐)。
 * 框架接管 spawn/wait-ready/teardown,不再依赖外部 dev 脚本预置 env。
 *
 * <p><b>为什么必须 fail-injection</b>:黑盒面无法主动构造"3 路 search 里恰好 1 路失败"的场景;
 * LLM 决策不可复现钉死。业务级注入是唯一可控手段,对照 B2 侧
 * {@code ParallelPartialFailureResumeTest} 用非对称步 recSerialNum 注入的思路。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-parallel-search")
@Tag("python-demo")
@Feature("FEAT-019: 智能体生成并行的下游智能体调用委托")
@Story("FEAT-019.PY-B1.PF: 部分失败回灌 —— 失败不覆盖成功 + 父任务收敛")
class PythonParallelSearchPartialFailureTest extends BaseManagedStackTest {

    private static final Logger LOG = Logger.getLogger(PythonParallelSearchPartialFailureTest.class.getName());

    private static final String DEEP_RESEARCH = PythonDeepResearchStackSupport.DEEP_RESEARCH;

    /**
     * 三家 vendor 均在 search-agent stub fixture 内(DeepSeek / GLM / 豆包 — 见
     * {@code web_search_tool.py._FIXTURES}),保证注入失败一家、其余两家有可回显的确定性结果。
     */
    private static final String COMPARISON_QUERY =
            "对比 DeepSeek V3、GLM-4、豆包 Pro 三家的大模型 API 输入定价,"
                    + "统一换算为 USD/百万 token,逐项给出价格、上下文窗口、引用来源与置信度。";

    /**
     * D3 约定的失败 vendor 名 —— dev 团队 fail-injection 环境变量 {@code SEARCH_AGENT_FAIL_VENDOR}
     * 的值须与此对齐。匹配是"lower-cased 子串",故 DeepSeek 会命中"DeepSeek V3"或"deepseek"任何形态。
     */
    private static final String INJECTED_FAIL_VENDOR = "DeepSeek";

    /**
     * 成功 vendor 在 fixture 中真实出现的标记(不在查询串内,防"query 回显造成假通过"):
     * {@code 智谱} 来自 GLM fixture title,{@code 火山方舟} 来自豆包 fixture title;
     * 命中任一即证明"失败不覆盖成功"。
     */
    private static final List<String> SURVIVING_VENDOR_MARKERS = List.of("智谱", "火山方舟");

    /** D3 交付的 error_code 字面值(FailInjectingHandler._ERROR_CODE),wire 里必出现。 */
    private static final String INJECTED_ERROR_CODE = "SEARCH_AGENT_FAIL_INJECTED";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return PythonDeepResearchStackSupport.builder(config,
                a -> a.env("SEARCH_AGENT_FAIL_VENDOR", INJECTED_FAIL_VENDOR));
    }

    @Test
    @DisplayName("FEAT-019 PY-B1.PF (Python): 一路 search 失败 → 成功 vendor 保留 + 父任务收敛")
    void partialFailureDoesNotOverrideSurvivors() {
        // PY-B1.PF.A — 父任务达终态(COMPLETED 或结构化 FAILED,不能悬挂 WORKING 超时)
        // 用 awaitTerminalOrInputRequired 即可,但 InteractionFlow 只暴露 awaitState(具体 state);
        // 这里锚 COMPLETED —— 若父任务因某一路失败被 demo 直接 FAILED,视为设计缺陷(FEAT-019 §5.3
        // 要求失败不覆盖成功,应汇总回落而不是整体失败);若 D3 落地后行为不同,再据实调整。
        InteractionFlow.of(client(DEEP_RESEARCH))
                .withTimeoutMs(900_000L)
                .protocol(MessageProtocol.A2A_STREAM)
                .send(COMPARISON_QUERY)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertThat(ctx -> {
                        // PY-B1.PF.A(前置): 扇出仍应 ≥2(失败成员计入批量)
                        List<ClientEvent> clientEvents = clientEventsFrom(ctx.events());
                        List<RemoteInvocationProbe.ChildRef> fanOut =
                                RemoteInvocationProbe.fromClientEvents(ctx.contextId(), clientEvents);
                        LOG.info(String.format("PY-B1.PF: fanOut.size()=%d toolCallIds=%s (期望 ≥2,含失败成员)",
                                fanOut.size(),
                                fanOut.stream().map(RemoteInvocationProbe.ChildRef::toolCallId).toList()));
                        assertThat(fanOut.size())
                                .as("PY-B1.PF.前置: 扇出仍应 ≥2(失败成员计入批量)\nfanOut=%s", fanOut)
                                .isGreaterThanOrEqualTo(2);
                        // PY-B1.PF.B(诊断日志,非硬断言): 失败信号 —— 观测窗仅到父 SSE 顶层帧,子任务
                        // TaskStatus(state=FAILED)不冒泡到父 ClientEvent.toString(),要精确闭合需要 D2
                        // delegation events 落地(按 producer taskId 分组),届时可硬断言 child.state=FAILED
                        // + error_code=SEARCH_AGENT_FAIL_INJECTED。当前 §5.3 acceptance 通过 PY-B1.PF.C
                        // (survivors 保留)承担核心判据 —— demo 层 fail-injection 是否触发由 search-agent
                        // 日志验证(agent-search.smoke.log 中 FailInjectingHandler 命中即证明 D3 起效)。
                        String wireText = ctx.events().stream()
                                .map(e -> e.raw() == null ? "" : e.raw().toString())
                                .reduce("", (a, b) -> a + "\n" + b);
                        boolean hasInjectedFailure = wireText.contains(INJECTED_ERROR_CODE);
                        boolean hasFailedSignal = hasInjectedFailure
                                || wireText.contains("state=TASK_STATE_FAILED")
                                || wireText.contains("\"state\":\"TASK_STATE_FAILED\"")
                                || wireText.toLowerCase().contains("error_code");
                        LOG.info(String.format("PY-B1.PF.B(诊断): hasInjectedFailure=%s hasFailedSignal=%s "
                                        + "(注:承载位待 D2 delegation events;当前 §5.3 由 C 承担)",
                                hasInjectedFailure, hasFailedSignal));
                    })
                    .assertAnswer(artifact -> {
                        // PY-B1.PF.C — 成功 vendor 的标记应仍在最终 artifact 中
                        long survivorHits = SURVIVING_VENDOR_MARKERS.stream()
                                .filter(artifact::contains).count();
                        assertThat(survivorHits)
                                .as("PY-B1.PF.C: 一路失败时,其他成功 vendor 结果应仍归位到最终 artifact\n"
                                        + "(失败不覆盖成功,§5.3)。期望 ≥1 命中 %s\nartifact 头 500 字: %s",
                                        SURVIVING_VENDOR_MARKERS, truncate(artifact, 500))
                                .isGreaterThanOrEqualTo(1);
                        // PY-B1.PF.D — 分流后,失败 vendor 语义与成功 vendor 不共流(归位不串腿)
                        // 这条依赖 D2 delegation events(按 producer taskId 分流),D2 未落地时先降级为
                        // "artifact 里不应出现两 vendor 语义混合成同一段答复"的粗判(先跳过,承载位落地再补)。
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

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}

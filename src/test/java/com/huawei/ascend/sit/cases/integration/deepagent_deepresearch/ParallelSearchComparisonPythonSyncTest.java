package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.transport.MessageProtocol;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-019 Python demo —— <b>{@code A2A_SYNC}(JSON-RPC {@code message/send} blocking)</b> 版终态验收。
 *
 * <p><b>为什么再加一条(与 {@link ParallelSearchComparisonPythonTest} 的分工)</b>:
 * 与 SSE 版形成 sync/stream 对照面。首跑(2026-09-24)证明 sync 路径 60s 就拿到完整 Task
 * 响应体,反证 Python 内部 loop 已收敛、终态帧已构造并发出 —— 唯一卡点是响应体里某条
 * Message 的 {@code role} 字段为空,A2A SDK 反序列化时抛
 * {@code InvalidParamsError: Parameter 'role' may not be null}。dev 已确认此 bug 与 SSE 版
 * 5:36 时 {@code A2aStreamingWire} errorHandler 报的 {@code A2A stream error: Parameter 'role'
 * may not be null} <b>同源</b>,只是打包粒度不同:sync 整包 parse 失败,SSE 单帧被吞掉后
 * 无后续 COMPLETED。修好 Message.role 后本类应能直接跑到 COMPLETED。
 *
 * <p>参见 [[python-remote-delegation-rail-resume-shortcircuit-bug]]。历史上曾假设 SSE 卡点
 * 在 A2A executor 的 {@code emitted_terminal} gate,已被 sync 60s 拿到完整 Task 的证据推翻。
 *
 * <p><b>断言</b>:仅 {@code awaitState(TASK_STATE_COMPLETED)} + artifact 非空。
 * fan-out(B1.B)的 wire 观测面在 sync 语义下不产出中间 delegation artifact 帧(全部被 collapse 到
 * 单条 blocking response),该判据在 {@link ParallelSearchFanOutOnInputRequiredPythonTest} 里通过
 * SSE 首帧 {@code INPUT_REQUIRED} 的 {@code recovery_point_id ≥ 2} 已经硬闭合,本类不重复。
 *
 * <p><b>栈构建</b>:{@link PythonDeepResearchStackSupport} —— 三个 Python 进程由框架 spawn/
 * wait-ready/teardown,{@code SEARCH_AGENT_URL} / {@code VERIFY_AGENT_URL} 由 downstreamEnv 注入。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-parallel-search")
@Tag("python-demo")
@Feature("FEAT-019: 智能体生成并行的下游智能体调用委托")
@Story("FEAT-019.B1 (Python, A2A_SYNC): sendMessage blocking 语义下父 task 收敛到 COMPLETED")
class ParallelSearchComparisonPythonSyncTest extends BaseManagedStackTest {

    private static final String DEEP_RESEARCH = PythonDeepResearchStackSupport.DEEP_RESEARCH;

    private static final String COMPARISON_QUERY =
            "对比 DeepSeek V3、Qwen-Max、Doubao-pro 三家的大模型 API 输入定价,"
                    + "统一换算为 USD/百万 token,逐项给出价格、上下文窗口、引用来源与置信度。";

    private static final long SYNC_TIMEOUT_MS = 900_000L;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return PythonDeepResearchStackSupport.builder(config);
    }

    @Test
    @DisplayName("FEAT-019 B1 (Python, A2A_SYNC/sendMessage): 父 task 收敛到 COMPLETED")
    void parentTaskCompletesUnderSendMessage() {
        InteractionFlow.of(client(DEEP_RESEARCH))
                .withTimeoutMs(SYNC_TIMEOUT_MS)
                .protocol(MessageProtocol.A2A_SYNC)
                .send(COMPARISON_QUERY)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertAnswer(artifact -> assertThat(artifact)
                            .as("A2A_SYNC 下父 task 应返回非空 artifact(fan-in 汇总结果)")
                            .isNotBlank())
                .execute();
    }
}

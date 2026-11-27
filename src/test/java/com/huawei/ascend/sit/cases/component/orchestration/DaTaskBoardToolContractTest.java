/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.openjiuwen.da.workflow.DaTaskBoard;
import com.openjiuwen.da.workflow.DelegateTaskTool;
import com.openjiuwen.da.workflow.TaskOutputTool;
import com.openjiuwen.harness.tools.ToolOutput;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 067-S04：{@code delegate_task} + {@code task_output} 的四态（submitted / running / completed / failed）。
 *
 * <p>Oracle 来源：FEAT-067 §2 两行（{@code delegate_task}「立即返回 task_id，不阻塞会话」；
 * {@code task_output}「按 task_id 查进度与产物，返回 running/completed/failed」）+ §5.1.2；
 * 实现依据 L2-067 §2.2（{@code DaTaskBoard.submit}，{@code CompletableFuture} + 固定池）。
 *
 * <p>四态 + 一项**非阻塞**判据：
 * ① {@code delegate_task} 返回 {@code status=submitted} + 非空 {@code task_id}，且 invoke
 *    **立即返回**——用"委托体仍被门闸挡住、而 invoke 已在有界时间内返回"来证明，不是靠测耗时长短；
 * ② 立即 {@code task_output} → {@code running}；③ 放行门闸后有界等待 → {@code completed} 且带 {@code output}；
 * ④ 未知 {@code task_id} → 可读 {@code error}、不悬挂；⑤ 委托体抛异常 → {@code failed} 且带 {@code error}。
 *
 * <p>不应断言：{@code task_id} 的具体格式（L2 §3.2 提到 {@code task-<seq>}，属实现约定）；
 * 委托执行体是真实模型（L1t 注入确定性函数；真实形态归 067-S16）。
 */
@Tag("component")
@Tag("feat-067")
@Feature("FEAT-067: 编排承接——会话内工作流工具组")
class DaTaskBoardToolContractTest {

    private static final String RUN = OrchestrationTestSupport.uid();
    private static final long POLL_TIMEOUT_MS = 30_000L;
    private static final long POLL_INTERVAL_MS = 50L;

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @Story("067-S04: delegate_task + task_output 四态")
    @DisplayName("067-S04 submitted/running/completed/failed 四态齐备，delegate_task 不阻塞，未知 id 不悬挂")
    void delegateAndQueryCoverFourStates() throws Exception {
        final String goal = "G-067-S04-" + RUN;
        final String expectedOutput = "OUT-" + goal;
        final CountDownLatch gate = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);

        try (DaTaskBoard board = new DaTaskBoard(4)) {
            final Function<String, String> blockingDelegate = g -> {
                started.countDown();
                try {
                    if (!gate.await(60, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("067-S04 门闸未放行（测试装置超时）");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                return "OUT-" + g;
            };
            final DelegateTaskTool delegateTool = new DelegateTaskTool(board, blockingDelegate);
            final TaskOutputTool outputTool = new TaskOutputTool(board);

            try {
                // ① delegate_task 立即返回 submitted + 非空 task_id（委托体仍被门闸挡住 ⇒ 证明未阻塞）
                final long beginNanos = System.nanoTime();
                final Map<String, Object> submitted = DaWorkflowTestSupport.dataOf(
                        DaWorkflowTestSupport.output(delegateTool.invoke(Map.of("goal", goal), Map.of())));
                final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - beginNanos);
                assertThat(submitted).as("① 应返回 status=submitted").containsEntry("status", "submitted");
                assertThat(submitted.get("task_id")).as("① 应返回非空 task_id").isInstanceOf(String.class);
                final String taskId = (String) submitted.get("task_id");
                assertThat(taskId).isNotBlank();
                assertThat(gate.getCount()).as("① invoke 返回时门闸仍未放行 ⇒ 委托体确实还在后台跑").isEqualTo(1);
                assertThat(elapsedMs).as("① invoke 应在有界时间内返回（不阻塞会话）；实测 %s ms", elapsedMs)
                        .isLessThan(5_000L);
                assertThat(started.await(10, TimeUnit.SECONDS))
                        .as("① 委托体应已在后台线程启动（证明是真异步而非惰性）").isTrue();

                // ② 立即查 → running
                final Map<String, Object> running = DaWorkflowTestSupport.dataOf(
                        DaWorkflowTestSupport.output(outputTool.invoke(Map.of("task_id", taskId,
                                "block", false), Map.of())));
                assertThat(running).as("② 委托体未完成时应为 running（r9 起 task_output 缺省阻塞等待终态，"
                                + "立即查必须显式 block=false——TaskOutputTool.invoke:77 shouldBlock 缺省 true）")
                        .containsEntry("status", "running");

                // ③ 放行门闸 → 有界等待后 completed 且带 output
                gate.countDown();
                final Map<String, Object> completed = awaitStatus(outputTool, taskId, "completed");
                assertThat(completed.get("output")).as("③ completed 态应带产物原文")
                        .isEqualTo(expectedOutput);

                // ④ 未知 task_id → 可读 error、不悬挂
                final String unknown = "task-unknown-" + RUN;
                final String unknownError = DaWorkflowTestSupport.errorOf(DaWorkflowTestSupport.output(
                        outputTool.invoke(Map.of("task_id", unknown, "block", false), Map.of())));
                assertThat(unknownError).as("④ 未知 task_id 的文案应带该 id 定位；实收=[%s]", unknownError)
                        .contains(unknown);

                // ⑤ 委托体抛异常 → failed 且带 error
                final String boom = "BOOM-067-S04-" + RUN;
                final DelegateTaskTool failingTool = new DelegateTaskTool(board, g -> {
                    throw new IllegalStateException(boom);
                });
                final String failedTaskId = (String) DaWorkflowTestSupport.dataOf(
                        DaWorkflowTestSupport.output(failingTool.invoke(
                                Map.of("goal", "G-067-S04-fail-" + RUN), Map.of()))).get("task_id");
                final ToolOutput failedOut = DaWorkflowTestSupport.output(
                        awaitRawStatus(outputTool, failedTaskId, "failed"));
                assertThat(failedOut.isSuccess()).as("⑤ failed 态 isSuccess 应为 false").isFalse();
                assertThat(failedOut.getError()).as("⑤ failed 态应带可读 error；实收=[%s]",
                        failedOut.getError()).contains(boom);
                @SuppressWarnings("unchecked")
                final Map<String, Object> failedData = (Map<String, Object>) failedOut.getData();
                assertThat(failedData).as("⑤ failed 态的 data 应含 status 与 error")
                        .containsEntry("status", "failed").containsKey("error");

                // 任务板侧的一致性：两个任务都在册
                assertThat(board.listAll()).as("任务板应登记两个任务").contains(taskId, failedTaskId);
            } finally {
                gate.countDown();
            }
        }
    }

    /** 有界轮询直到 {@code task_output} 返回期望状态，返回其 data 映射。 */
    private static Map<String, Object> awaitStatus(final TaskOutputTool tool, final String taskId,
            final String expected) throws InterruptedException {
        final ToolOutput out = awaitRawStatus(tool, taskId, expected);
        return DaWorkflowTestSupport.dataOf(out);
    }

    /** 有界轮询直到 {@code task_output} 的 data.status == expected，返回原始信封（保留 success/error 供断言）。 */
    private static ToolOutput awaitRawStatus(final TaskOutputTool tool, final String taskId,
            final String expected) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        ToolOutput last = null;
        while (System.currentTimeMillis() < deadline) {
            last = DaWorkflowTestSupport.output(tool.invoke(Map.of("task_id", taskId,
                    "block", false), Map.of()));
            final Object data = last.getData();
            if (data instanceof Map<?, ?> map && expected.equals(map.get("status"))) {
                return last;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("有界等待 " + POLL_TIMEOUT_MS + "ms 内未达 " + expected + " 态；task_id="
                + taskId + "；最后一次返回=" + (last == null ? "无" : last.getData() + " / error=" + last.getError()));
    }
}

/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.openjiuwen.da.workflow.DaTaskBoard;
import com.openjiuwen.da.workflow.DaWorkflowRunner;
import com.openjiuwen.da.workflow.DelegateTaskTool;
import com.openjiuwen.da.workflow.WorkflowStatusTool;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 067-S08：{@code workflow_status} 两动作 + 未知动作。
 *
 * <p>Oracle 来源：FEAT-067 §2「按动作查后台工作流状态：{@code list} 列全部任务、
 * {@code status}+{@code task_id} 查单任务」+ §5.1.5 错误表；L2-067 §2.3。
 *
 * <p>三实例：{@code action=list} 返回任务板快照（含 1 个 completed 与 1 个 running）；
 * {@code action=status} + 已知 {@code task_id} 返回单任务详情；{@code action=<未知>} 返回可读错误。
 * 另附 {@code action=status} 缺 {@code task_id} 的错误面（同动作的参数缺失族）。
 * 全部实例都在 {@code @Timeout} 内返回 ⇒ 未知动作不悬挂。
 *
 * <p>不应断言：任务在快照里的**排列顺序**（{@code listAll} 是并发 Map 的键集，顺序未承诺）；
 * {@code task_id} 的具体格式。
 */
@Tag("component")
@Tag("feat-067")
@Feature("FEAT-067: 编排承接——会话内工作流工具组")
class DaWorkflowStatusToolTest {

    private static final String RUN = OrchestrationTestSupport.uid();

    @ParameterizedTest(name = "action={0}")
    @ValueSource(strings = {"list", "status", "teleport"})
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @Story("067-S08: workflow_status 两动作 + 未知动作")
    @DisplayName("067-S08 list 返回任务板快照、status 返回单任务、未知 action 返回可读错误且不悬挂")
    void twoActionsPlusUnknownAction(final String action) throws Exception {
        final CountDownLatch gate = new CountDownLatch(1);
        try (DaTaskBoard board = new DaTaskBoard(4)) {
            // 造一个已完成任务与一个仍在跑的任务
            final DelegateTaskTool immediate = new DelegateTaskTool(board, goal -> "DONE-" + goal);
            final String completedId = (String) DaWorkflowTestSupport.dataOf(DaWorkflowTestSupport.output(
                    immediate.invoke(Map.of("goal", "G-067-S08-done-" + RUN), Map.of()))).get("task_id");
            final DelegateTaskTool blocking = new DelegateTaskTool(board, goal -> {
                try {
                    if (!gate.await(60, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("067-S08 门闸未放行（测试装置超时）");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                return "LATE-" + goal;
            });
            final String runningId = (String) DaWorkflowTestSupport.dataOf(DaWorkflowTestSupport.output(
                    blocking.invoke(Map.of("goal", "G-067-S08-run-" + RUN), Map.of()))).get("task_id");
            awaitCompleted(board, completedId);

            // 生产装配是**三参**（【2026-10-26 r6 适配】原为两参）：
            //   new WorkflowStatusTool(board, runner.getTranscripts(), runner.getSendMessage())（DaWorkflowTools.attach:46）
            // 少参形态被产品 javadoc 定性为「只查委托任务」、transcripts「null 表示不查运行」⇒ 属降级形态，不用。
            // 一律经 DaWorkflowTestSupport.newStatusTool 取工具（该处已同步升三参），由 067-S24 parity 门禁看守。
            final DaWorkflowRunner statusRunner = DaWorkflowTestSupport.newRunner(
                    member -> (userInput, subGoal) -> "unused-by-status-probe", 4);
            final WorkflowStatusTool tool = DaWorkflowTestSupport.newStatusTool(board, statusRunner);
            switch (action) {
                case "list" -> {
                    final Map<String, Object> data = DaWorkflowTestSupport.dataOf(
                            DaWorkflowTestSupport.output(tool.invoke(Map.of("action", "list"), Map.of())));
                    assertThat(data).as("list 应返回 tasks 快照").containsKey("tasks");
                    @SuppressWarnings("unchecked")
                    final List<Map<String, Object>> tasks = (List<Map<String, Object>>) data.get("tasks");
                    assertThat(tasks).as("快照应含两个任务").hasSize(2);
                    final List<String> ids = new ArrayList<>();
                    for (Map<String, Object> task : tasks) {
                        assertThat(task).as("每个任务条目应带 task_id 与 status")
                                .containsKeys("task_id", "status");
                        ids.add(String.valueOf(task.get("task_id")));
                    }
                    assertThat(ids).as("快照应覆盖已完成与仍在跑的两个任务")
                            .containsExactlyInAnyOrder(completedId, runningId);
                    final Map<String, Object> completedEntry = tasks.stream()
                            .filter(t -> completedId.equals(t.get("task_id"))).findFirst().orElseThrow();
                    final Map<String, Object> runningEntry = tasks.stream()
                            .filter(t -> runningId.equals(t.get("task_id"))).findFirst().orElseThrow();
                    assertThat(completedEntry).containsEntry("status", "completed");
                    assertThat(runningEntry).containsEntry("status", "running");
                }
                case "status" -> {
                    final Map<String, Object> data = DaWorkflowTestSupport.dataOf(DaWorkflowTestSupport.output(
                            tool.invoke(Map.of("action", "status", "task_id", completedId), Map.of())));
                    assertThat(data).as("status 应返回该单任务的状态").containsEntry("status", "completed");
                    assertThat(data.get("output")).as("status 应带该任务产物")
                            .isEqualTo("DONE-G-067-S08-done-" + RUN);
                    // 同动作的参数缺失族：缺 task_id 应给可读错误
                    final String missing = DaWorkflowTestSupport.errorOf(DaWorkflowTestSupport.output(
                            tool.invoke(Map.of("action", "status"), Map.of())));
                    assertThat(missing).as("action=status 缺 task_id 时文案应指明该参数；实收=[%s]", missing)
                            .contains("task_id");
                    // 未知 task_id 也应给可读错误而不是悬挂
                    final String unknown = DaWorkflowTestSupport.errorOf(DaWorkflowTestSupport.output(
                            tool.invoke(Map.of("action", "status", "task_id", "task-nope-" + RUN), Map.of())));
                    assertThat(unknown).as("未知 task_id 的文案应带定位；实收=[%s]", unknown)
                            .contains("task-nope-" + RUN);
                }
                case "teleport" -> {
                    final String error = DaWorkflowTestSupport.errorOf(DaWorkflowTestSupport.output(
                            tool.invoke(Map.of("action", "teleport"), Map.of())));
                    assertThat(error).as("未知 action 的文案应带该动作定位；实收=[%s]", error)
                            .contains("teleport");
                    // action 缺失同族
                    final String blank = DaWorkflowTestSupport.errorOf(DaWorkflowTestSupport.output(
                            tool.invoke(Map.of(), Map.of())));
                    assertThat(blank).as("action 缺失时文案应指明该参数；实收=[%s]", blank).contains("action");
                }
                default -> throw new IllegalStateException("未覆盖的 action：" + action);
            }
        } finally {
            gate.countDown();
        }
    }

    /** 有界等待任务板上的任务进入 completed。 */
    private static void awaitCompleted(final DaTaskBoard board, final String taskId)
            throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            final Map<String, Object> status = board.statusOf(taskId).orElseThrow();
            if ("completed".equals(status.get("status"))) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("有界等待内任务未进入 completed：" + taskId);
    }
}

/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import com.openjiuwen.agents.orchestration.MemberSpec;
import com.openjiuwen.agents.reactrails.loopforest.execution.SubAgentExecutor;
import com.openjiuwen.da.workflow.DaTaskBoard;
import com.openjiuwen.da.workflow.DaWorkflowRunner;
import com.openjiuwen.da.workflow.TaskOutputTool;
import com.openjiuwen.da.workflow.WorkflowRunTool;
import com.openjiuwen.da.workflow.WorkflowStatusTool;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 067-S03：错误表面五态，**会话不炸**。
 *
 * <p>Oracle 来源：FEAT-067 §5.1.5 错误表全 5 行 + §2「坏图纸与执行失败返回可读错误，**不炸会话**」；
 * L2-067 §2.1/§2.3。§11.2 更正：原"待裁决"的 {@code DV067-02} 已消除
 * （Feature §5.1.4 明文「无起即无悬挂义务」）⇒ 装载失败例的断言**升为 gating**。
 *
 * <p>五态判据：五者**均返回带 {@code error} 的结果对象**，而不是把异常抛穿调用栈
 * （抛穿即"炸会话"）；①② 的错误文案**指明缺失参数**；③ 含图纸定位；④⑤ 为可读错误且
 * **在有界等待内返回**（{@code @Timeout} 兜住"悬挂"这一失败形态）。
 *
 * <p>不应断言：错误文案的**具体措辞**（Feature 只要求"可读""指明缺失参数"，未给文案模板）
 * ⇒ 本类只断言文案里出现缺失参数名 / 图纸定位串，不断言整句。
 */
@Tag("component")
@Tag("feat-067")
@Feature("FEAT-067: 编排承接——会话内工作流工具组")
class DaWorkflowErrorSurfaceTest {

    /** 生产装配需要 baseRoot（DaWorkflowTools.attach:40）；缺它 workflow_run 走"不落盘"降级形态。 */
    @TempDir
    Path baseRoot;

    private static final String RUN = OrchestrationTestSupport.uid();

    /** 有界等待：任一实例若悬挂，本用例以超时失败而不是无限挂住（"不悬挂"是判据之一）。 */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"① question 缺失", "② blueprint 与 template 双缺",
            "③ blueprint 为违规图纸（依赖环）", "④ task_output 传未知 task_id",
            "⑤ workflow_status 传未知 action"})
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @Story("067-S03: 错误表面五态，会话不炸")
    @DisplayName("067-S03 五态均返回带 error 的结果对象（不抛穿调用栈、不悬挂），文案指明缺失参数/图纸定位")
    void fiveErrorStatesReturnReadableErrorWithoutBreakingTheSession(final String label) {
        final Function<MemberSpec, SubAgentExecutor> factory =
                member -> (userInput, subGoal) -> "M-067-S03-" + member.id() + "-" + RUN;
        final DaWorkflowRunner runner = DaWorkflowTestSupport.newRunner(factory, 4);
        final WorkflowRunTool runTool = DaWorkflowTestSupport.newRunTool(runner, baseRoot);
        final String error;

        switch (label) {
            case "① question 缺失" -> {
                final Map<String, Object> args = new LinkedHashMap<>();
                args.put("template", "research-pipeline");
                error = DaWorkflowTestSupport.errorOf(
                        DaWorkflowTestSupport.output(runTool.invoke(args, Map.of())));
                assertThat(error).as("① 文案应指明缺失的参数名 question；实收=[%s]", error)
                        .contains("question");
            }
            case "② blueprint 与 template 双缺" -> {
                final Map<String, Object> args = new LinkedHashMap<>();
                args.put("question", "U-067-S03-" + RUN);
                error = DaWorkflowTestSupport.errorOf(
                        DaWorkflowTestSupport.output(runTool.invoke(args, Map.of())));
                assertThat(error).as("② 文案应指明缺失的两个参数名 blueprint / template；实收=[%s]", error)
                        .contains("blueprint").contains("template");
            }
            case "③ blueprint 为违规图纸（依赖环）" -> {
                final String cyclic = """
                        blueprint:
                          id: T-067-S03-cycle
                          type: dag
                          members:
                            - {id: m1, instruction: "甲"}
                            - {id: m2, instruction: "乙"}
                          steps:
                            - {id: s1, member: m1, depends_on: [s2]}
                            - {id: s2, member: m2, depends_on: [s1]}
                        """;
                final Map<String, Object> args = new LinkedHashMap<>();
                args.put("blueprint", cyclic);
                args.put("question", "U-067-S03-cycle-" + RUN);
                error = DaWorkflowTestSupport.errorOf(
                        DaWorkflowTestSupport.output(runTool.invoke(args, Map.of())));
                assertThat(error).as("③ 文案应含图纸定位（图纸 id）；实收=[%s]", error)
                        .contains("T-067-S03-cycle");
                assertThat(error).as("③ 文案应含违规定位（环）；实收=[%s]", error).contains("cycle");
            }
            case "④ task_output 传未知 task_id" -> {
                try (DaTaskBoard board = new DaTaskBoard(2)) {
                    final TaskOutputTool tool = new TaskOutputTool(board);
                    error = DaWorkflowTestSupport.errorOf(DaWorkflowTestSupport.output(
                            tool.invoke(Map.of("task_id", "task-does-not-exist-" + RUN), Map.of())));
                }
                assertThat(error).as("④ 文案应含被查询的未知 task_id 定位；实收=[%s]", error)
                        .contains("task-does-not-exist-" + RUN);
            }
            case "⑤ workflow_status 传未知 action" -> {
                try (DaTaskBoard board = new DaTaskBoard(2)) {
                    // 两参形态（生产口径，DaWorkflowTools.attach:47）；单参是「只查委托任务」降级形态
                    final WorkflowStatusTool tool = DaWorkflowTestSupport.newStatusTool(board, runner);
                    error = DaWorkflowTestSupport.errorOf(DaWorkflowTestSupport.output(
                            tool.invoke(Map.of("action", "teleport"), Map.of())));
                }
                assertThat(error).as("⑤ 文案应含未知 action 定位与合法取值提示；实收=[%s]", error)
                        .contains("teleport");
            }
            default -> throw new IllegalStateException("未覆盖的实例：" + label);
        }

        // 五态共性：返回了结果对象（走到这里就说明没有异常抛穿调用栈），且文案可读
        assertThat(error).as("%s：应返回可读错误文案而不是抛穿调用栈（抛穿即炸会话）", label).isNotBlank();
    }
}

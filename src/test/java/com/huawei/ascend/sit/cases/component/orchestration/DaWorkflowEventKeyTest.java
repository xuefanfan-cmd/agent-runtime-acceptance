/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.openjiuwen.agents.orchestration.MemberSpec;
import com.openjiuwen.agents.reactrails.loopforest.execution.SubAgentExecutor;
import com.openjiuwen.da.workflow.DaWorkflowRunner;
import com.openjiuwen.da.workflow.WorkflowRunTool;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 067-S13：SSE 订阅键外流（主断言，gating）+ catch 面外异常原样逃逸（§11.2 新增的可执行替代断言）。
 *
 * <p>Oracle 来源：FEAT-067 §2「订阅键随 {@code workflow_run} 出参外流」+ §3「订阅键
 * {@code workflow:{workflowId}}」+ §5.1.4「{@code workflow_id} 不出流的事件面等于不存在
 * ——**订阅键以外流为准**」；L2-067 §2.5（finally 口径，#33 V2 方案②）。
 *
 * <p><b>§11.2 把本条拆成三段</b>：
 * <ul>
 *   <li>① <b>键外流 = gating</b>（本类断言）：{@code workflow_id} 非空，拼接后的订阅键形态合法
 *       （匹配 {@code workflow:.+}、无空白、可作 map key）；</li>
 *   <li>② <b>"本次运行订阅可达性" = OUT</b>：Feature §2 已明文把它挂到 1130 异步化/缓冲评估项，
 *       「不作为当期验收内容」⇒ **不再是缺口，也不构造用例**；</li>
 *   <li>③ <b>失败补发 + 真实耗时 = 仍 blocked</b>：{@code workflowId = "wf-" + System.nanoTime()}
 *       不可预知，{@code DaPushEventBus.publish} 在无订阅者时直接丢弃且无通配订阅 API
 *       ⇒ 进程内无观测点。四项门槛留痕见批次记录，**不得写成 FAIL**。</li>
 * </ul>
 *
 * <p><b>§11.2 新增的可执行替代断言</b>：catch 面外异常**原样上抛、不被吞、不换型**——
 * 这是 L2 §2.5 与开发侧 UT 都承诺且**可观测**的行为，作为 finally 兜底未被绕过的**间接**证据。
 * ⚠️ 它**不等于**"事件已补发"（补发本身仍 blocked），本类在断言文案里明确写出这一点。
 *
 * <p><b>构造方式与设计 §6 的一处细化（已登记偏差）</b>：§11.1 的 {@code L067-01} 答复建议用
 * "成员执行体抛未枚举 RuntimeException"构造面外逃逸。实测：执行体（{@code SubAgentExecutor.execute}）
 * 抛出的异常会先被调度器的 {@code Future.get()} 包成 {@code ExecutionException}、再被包成
 * {@code BlueprintException}，落回 catch **面内**。要真正走到面外，抛出点必须在
 * {@code runner.run} 的 try 块里、且不在枚举 catch 之内——即**绑定期**的
 * {@code memberFactory.apply(member)}。本用例据此把抛出点放在成员工厂，判据（原样逃逸、不吞不换型）不变。
 *
 * <p>不应断言：错误文案的具体措辞；{@code workflow_id} 的具体格式（{@code wf-<nanoTime>} 属实现约定）。
 */
@Tag("component")
@Tag("feat-067")
@Feature("FEAT-067: 编排承接——会话内工作流工具组")
class DaWorkflowEventKeyTest {

    /** 生产装配需要 baseRoot（DaWorkflowTools.attach:40）；缺它 workflow_run 走"不落盘"降级形态。 */
    @TempDir
    Path baseRoot;

    private static final String RUN = OrchestrationTestSupport.uid();

    @Test
    @Story("067-S13: SSE 订阅键随 workflow_run 出参外流")
    @DisplayName("067-S13① workflow_id 非空，订阅键 workflow:<id> 形态合法且可作 map key")
    void subscriptionKeyFlowsOutWithTheRunResult() {
        final Function<MemberSpec, SubAgentExecutor> factory =
                member -> (userInput, subGoal) -> "M-067-S13-" + member.id() + "-" + RUN;
        final DaWorkflowRunner runner = DaWorkflowTestSupport.newRunner(factory, 4);
        final WorkflowRunTool tool = DaWorkflowTestSupport.newRunTool(runner, baseRoot);

        final Map<String, Object> data = DaWorkflowTestSupport.dataOf(DaWorkflowTestSupport.output(
                tool.invoke(Map.of("template", "research-pipeline", "question", "U-067-S13-" + RUN),
                        Map.of())));

        assertThat(data).containsEntry("status", "completed");
        assertThat(data.get("workflow_id")).as("出参应含 workflow_id（不出流的事件面等于不存在）")
                .isInstanceOf(String.class);
        final String workflowId = (String) data.get("workflow_id");
        assertThat(workflowId).as("workflow_id 不应为空白").isNotBlank();

        // 订阅键形态（FEAT-067 §3：workflow:{workflowId}）
        final String key = "workflow:" + workflowId;
        assertThat(key).as("订阅键应匹配 workflow:.+ 形态").matches("workflow:.+");
        assertThat(key).as("订阅键不应含任何空白字符（否则不可作 SSE 键）").doesNotContainPattern("\\s");
        // 可作 map key：等价键能取回同一值（hashCode/equals 稳定）
        final Map<String, String> subscriptions = new HashMap<>();
        subscriptions.put(key, "subscriber-" + RUN);
        assertThat(subscriptions.get("workflow:" + workflowId))
                .as("同一 workflow_id 拼出的订阅键应能命中同一订阅位").isEqualTo("subscriber-" + RUN);
        assertThat(subscriptions).as("订阅键应可枚举（前端据此订阅）").containsKey(key);

        // 键的**唯一性**：两次运行应给出不同的 workflow_id（否则订阅键会互相串台）
        final Map<String, Object> second = DaWorkflowTestSupport.dataOf(DaWorkflowTestSupport.output(
                tool.invoke(Map.of("template", "research-pipeline", "question", "U-067-S13-2-" + RUN),
                        Map.of())));
        final String secondId = (String) second.get("workflow_id");
        assertThat(secondId).as("第二次运行的 workflow_id 应非空").isNotBlank();
        assertThat(secondId).as("两次运行的 workflow_id 不应相同（订阅键不得串台）").isNotEqualTo(workflowId);
    }

    @Test
    @Story("067-S13: catch 面外异常原样逃逸（finally 兜底未被绕过的间接证据）")
    @DisplayName("067-S13③替代断言 面外异常原样上抛、不被吞、不换型（**不等于**事件已补发）")
    void outOfCatchSurfaceExceptionEscapesUnchanged() {
        final String escapeMarker = "ESCAPE-067-S13-" + RUN;
        final UnsupportedOperationException injected = new UnsupportedOperationException(escapeMarker);
        // 抛出点放在**绑定期**的成员工厂：这里在 runner.run 的 try 块内、且不在枚举 catch 之内
        final Function<MemberSpec, SubAgentExecutor> escapingFactory = member -> {
            throw injected;
        };
        final DaWorkflowRunner runner = DaWorkflowTestSupport.newRunner(escapingFactory, 4);
        final WorkflowRunTool tool = DaWorkflowTestSupport.newRunTool(runner, baseRoot);

        assertThatThrownBy(() -> tool.invoke(Map.of(
                "blueprint", DaWorkflowTestSupport.singleMemberBlueprint("T-067-S13-escape", "mSolo"),
                "question", "U-067-S13-escape-" + RUN), Map.of()))
                .as("catch 面外异常应**原样逃逸**：不吞（不变成 error 结果对象）、不换型（不是别的异常类型）。"
                        + "注意：这只证明 finally 兜底路径未被绕过，**不等于**证明 failed 事件已补发"
                        + "（事件面仍 blocked，见类注释 ③）")
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage(escapeMarker)
                .isSameAs(injected);
    }
}

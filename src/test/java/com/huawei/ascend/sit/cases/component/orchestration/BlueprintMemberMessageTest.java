/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.openjiuwen.agents.orchestration.BlueprintResult;
import com.openjiuwen.agents.orchestration.BlueprintScheduler;
import com.openjiuwen.agents.orchestration.StepOutcome;
import com.openjiuwen.agents.reactrails.loopforest.observability.TraceForest;
import com.huawei.ascend.sit.cases.component.orchestration.OrchestrationTestSupport.Recording;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 065-S11：成员留言暂存、投影携带、取走即清。
 *
 * <p>Oracle 来源：FEAT-065 §2「成员消息暂存与投影」+ §5.1.3（留言作为一个投影段随步骤目标位送达）；
 * L2-065 §3.7「成员消息消费」。
 *
 * <p><b>本用例的判据边界</b>：调度器侧承诺的是「每次投影时向消费面取该成员的待读留言并拼入步骤目标位」，
 * "取走即清"由消费面（宿主的 {@code SendMessageTool.drain()}）实现——FEAT-065 §3 把装配交给宿主。
 * 因此本用例装配一个**真实会清空**的消费面（读取即出队），据此判定：
 * ① 第一次运行 B 实收输入含该留言；② 第二次运行 B 实收输入**不再**含该留言。
 * 若调度器缓存了留言（而非每次向消费面取），第二次仍会带上 ⇒ 断言失败。
 *
 * <p>两次运行共用同一个 {@link TraceForest}（宿主持有留痕载体、跨多次执行累积），
 * 因此第二次运行不会另建执行根——这本身也是 §5.1.5「留痕结构归宿主，跨多次执行累积可查」的组件层证据。
 *
 * <p>不应断言：留言的来源标识（{@code from} 字段在宿主侧恒空串，属 FEAT-067 范畴）。
 */
@Tag("component")
@Tag("feat-065")
@Feature("FEAT-065: 确定性编排层(蓝图调度)")
class BlueprintMemberMessageTest {

    private static final String RUN = OrchestrationTestSupport.uid();

    private static final String SINGLE_MEMBER_DAG = """
            blueprint:
              id: T-065-S11
              type: dag
              members:
                - {id: mB, instruction: "成员乙作答"}
              steps:
                - {id: sB, member: mB}
            """;

    @Test
    @Story("065-S11: 成员留言暂存、投影携带、取走即清")
    @DisplayName("065-S11 首次运行携带留言、第二次运行不再携带（消费面取走即清）")
    void pendingMessageIsCarriedOnceThenCleared() {
        final var loaded = OrchestrationTestSupport.load(SINGLE_MEMBER_DAG, "065-S11", RUN);
        final Recording memberB = loaded.recorders.get("mB");
        final String message = "MSG-065-S11-" + RUN;

        // 消费面：读取即出队（"取走即清"由宿主的 drain 实现，此处按 FEAT-065 §3 装配）
        final Map<String, Deque<String>> inbox = new LinkedHashMap<>();
        inbox.put("mB", new ArrayDeque<>(List.of(message)));
        final List<String> drainedFor = new ArrayList<>();
        final Function<String, List<String>> drain = memberId -> {
            drainedFor.add(memberId);
            final Deque<String> queue = inbox.get(memberId);
            if (queue == null || queue.isEmpty()) {
                return List.of();
            }
            final List<String> taken = new ArrayList<>();
            while (!queue.isEmpty()) {
                taken.add(queue.poll());
            }
            return taken;
        };

        final TraceForest forest = new TraceForest();
        final BlueprintResult first;
        final BlueprintResult second;
        try (BlueprintScheduler scheduler = new BlueprintScheduler(forest, 4, drain)) {
            first = scheduler.execute(loaded.spec, OrchestrationTestSupport.bindings(loaded.recorders),
                    "U-065-S11-first-" + RUN);
            second = scheduler.execute(loaded.spec, OrchestrationTestSupport.bindings(loaded.recorders),
                    "U-065-S11-second-" + RUN);
        }

        // ① 第一次运行：留言随步骤目标位送达
        assertThat(memberB.calls()).as("两次运行各调用一次").isEqualTo(2);
        assertThat(memberB.subGoal(0)).as("第一次运行 B 实收输入应含该留言；实收原文=[%s]", memberB.subGoal(0))
                .contains(message);
        assertThat(memberB.subGoal(0)).as("第一次运行仍应含成员指令原文").contains("成员乙作答");

        // ② 第二次运行：留言已被取走，不再携带
        assertThat(memberB.subGoal(1)).as("第二次运行 B 实收输入**不应**再含该留言（取走即清）；实收原文=[%s]",
                memberB.subGoal(1)).doesNotContain(message);
        assertThat(inbox.get("mB")).as("消费面队列应已清空").isEmpty();
        assertThat(drainedFor).as("两次运行都应向消费面取过 B 的留言").contains("mB");

        // 两次运行都正常收口，且共用同一执行根（留痕跨多次执行累积）
        assertThat(first.stepOutcomes()).hasSize(1);
        assertThat(second.stepOutcomes()).hasSize(1);
        assertThat(first.stepOutcomes().get(0).skipped()).isFalse();
        assertThat(second.stepOutcomes().get(0).skipped()).isFalse();
        assertThat(forest.roots()).as("共用同一 TraceForest ⇒ 只应有一个执行根").hasSize(1);
        assertThat(forest.childrenOf(forest.roots().get(0).branchId()))
                .as("两次运行的步骤留痕应累积在同一执行根下").hasSize(2);
        assertThat(List.of(first, second)).allSatisfy(r ->
                assertThat(r.finalOutputText()).as("每次运行都应有收口产物").isPresent());
        final List<StepOutcome> all = new ArrayList<>(first.stepOutcomes());
        all.addAll(second.stepOutcomes());
        assertThat(all).extracting(StepOutcome::branchId).as("两次运行的分支标识不应重复").doesNotHaveDuplicates();
    }

    /**
     * 补：L2-065 §6「关键配置项设计」的 {@code messageDrain} 行——「成员消息消费函数；**null=无消息面**」。
     *
     * <p>该行在 2026-10-17 的**第三次独立覆盖重扫**中被查出目录里无任何条目承接（批次记录 §7W）：
     * 065-S11 原用例只验了"给了 drain 时留言会送达且取走即清"，从未验"不给 drain（两参构造器）时
     * 即使消息区里有留言也**不会**出现留言段"。本方法与原用例构成**对照**：同一份留言、同一张图纸，
     * 唯一差别是调度器用两参还是三参构造，结果必须相反。
     */
    @Test
    @Story("065-S11: 成员留言暂存、投影携带、取走即清")
    @DisplayName("065-S11 补：messageDrain 为 null 时无消息面——留言已投递也不会出现在成员输入里")
    void nullMessageDrainMeansNoMessageSurface() {
        final String yaml = """
                blueprint:
                  id: T-065-S11-nulldrain
                  type: dag
                  members:
                    - {id: mB, instruction: "成员乙作答"}
                  steps:
                    - {id: sB, member: mB}
                """;
        final var loaded = OrchestrationTestSupport.load(yaml, "065-S11-null", RUN);
        final String message = "MSG-065-S11-NULL-" + RUN;

        // 前置：留言确实被投递进消息区（与 065-S11 相同的前置）
        final com.openjiuwen.da.workflow.SendMessageTool send =
                new com.openjiuwen.da.workflow.SendMessageTool();
        // 【2026-10-26 r6 适配】registerMember 的 **1 参重载已删**，现为 (owner, memberId)。
        // 本用例不设会话上下文，故 owner 取 DaOrchestrationScope.UNSCOPED 的值 ""——该常量是**包私有**
        // （da.workflow 包内 `static final String UNSCOPED = ""`），测试包取不到，只能用字面值并在此注明。
        // 一致性核对：下面的 1 参 drain("mB") 内部是 drain(DaOrchestrationScope.currentKey(), memberId, "")，
        // 无会话上下文时 currentKey() 同样返回 "" ⇒ **注册与取走落在同一分区**，前置自检仍然有效、判据未被削弱。
        send.registerMember("", "mB");
        final Object ack = send.invoke(java.util.Map.of("target", "mB", "content", message), java.util.Map.of());
        assertThat(((com.openjiuwen.harness.tools.ToolOutput) ack).isSuccess())
                .as("前置：留言应投递成功").isTrue();
        assertThat(send.drain("mB")).as("前置自检：消息区里确实有这条留言（否则本用例证明不了任何事）")
                .isNotEmpty();
        send.invoke(java.util.Map.of("target", "mB", "content", message), java.util.Map.of());

        // 用**两参构造器**（messageDrain = null）执行 ⇒ 无消息面
        final var result = OrchestrationTestSupport.run(loaded.spec,
                OrchestrationTestSupport.bindings(loaded.recorders), "U-065-S11-null-" + RUN, 4,
                new com.openjiuwen.agents.reactrails.loopforest.observability.TraceForest());

        final var memberB = loaded.recorders.get("mB");
        assertThat(memberB.calls()).as("成员应被执行一次").isEqualTo(1);
        assertThat(memberB.subGoal(0))
                .as("messageDrain 为 null 时**无消息面**：即使消息区里有该留言，成员输入也不应含它；实收=[%s]",
                        memberB.subGoal(0))
                .doesNotContain(message);
        assertThat(memberB.subGoal(0)).as("步骤目标位应恰好等于成员指令原文（无任何追加段）")
                .isEqualTo("成员乙作答");
        // 留言仍留在消息区（未被消费）⇒ 证明"不是被吃了"，而是"根本没有消费面"
        assertThat(send.drain("mB")).as("null drain 下留言应仍留在消息区未被消费")
                .anyMatch(m -> message.equals(m.content()));
        assertThat(result.stepOutcomes()).hasSize(1);
    }
}

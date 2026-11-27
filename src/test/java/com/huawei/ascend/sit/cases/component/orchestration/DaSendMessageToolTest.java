/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.openjiuwen.da.workflow.SendMessageTool;
import com.openjiuwen.da.workflow.WorkflowRunTool;
import com.huawei.ascend.sit.cases.component.orchestration.OrchestrationTestSupport.Recording;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 067-S05 / 067-S06：{@code send_message} 定向、投影携带、取走即清、广播可达。
 *
 * <p>Oracle 来源：FEAT-067 §2 三行（定向留言 / 广播 {@code target="all"} / 留言随下一次任务输入投影）
 * + §5.1.3 + §4「成员留言」场景；L2-067 §2.3（含 !805 修复「绑定期 {@code registerMember}
 * 预建收件箱」以保证广播可达全员）。
 * §11.2 更正：「同一 Task 内两次运行」已是**设计口径**（Feature §4 明文「同一任务(Task)内的两次
 * 工作流运行之间」）；跨 Task 留言属设计明确**不承诺** ⇒ 按范围外处理，不再构造。
 *
 * <p><b>S06 的构造次序说明（与设计 §6 的 Given 文字有一处必要调整，已登记偏差）</b>：
 * 广播 {@code target="all"} 的实现是"向**已登记的收件箱**逐个投递"，而收件箱由
 * {@code DaWorkflowRunner} 在**绑定期**（即某次 run 内）为每个成员预建。因此若在任何 run 之前
 * 就广播，收件箱尚不存在、无人可达——这正是 !805 修复要解决的场景的**前置条件**。
 * 本用例据此按「先跑一次完成成员登记 → 再定向留言 → 再广播 → 再跑一次」的次序构造，
 * 判据本身（含从未被定向过的 mC 也能收到广播）**未变**。
 *
 * <p>不应断言：留言的**来源可辨**（L2 §2.3 明示 {@code from} 为预留字段、实收恒空串
 * ⇒ 要求来源标识会把符合设计的实现判 FAIL）；"成员在运行中互相留言"（Feature §2 OUT
 * 「成员带工具执行」；L2 §1.3「成员为裸 ReActAgent，无工具」——⚠️ 该前提已被 docs `!379`（`main@65118fda`）推翻：FEAT-067:50 现为 MUST「成员与委托只读工具」（按发起方会话范围挂 14 件只读工具）、:51 为 MUST NOT（不得挂写工具与编排六件），其中**编排六件含 `send_message`** ⇒ 「成员运行中不能调用 send_message」这条边界**依然成立**，只是理由由「成员无工具」变成「成员的工具集里显式排除编排六件」（FEAT-065:71 的措辞也已同步改为「成员运行中不能调用 send_message」）。本用例判据不受影响。【2026-10-27 更正·docs !379】）；跨 Task 形态。
 */
@Tag("component")
@Tag("feat-067")
@Feature("FEAT-067: 编排承接——会话内工作流工具组")
class DaSendMessageToolTest {

    private static final String RUN = OrchestrationTestSupport.uid();

    @Test
    @Story("067-S05: 定向留言随下一次任务输入投影，取走即清")
    @DisplayName("067-S05 首次运行 mB 实收输入含该留言、第二次运行不再含（取走即清、不重放）")
    void directedMessageIsCarriedOnceThenCleared() {
        try (DaWorkflowTestSupport.Fixture fixture = DaWorkflowTestSupport.fixture("067-S05", RUN)) {
            final SendMessageTool send = fixture.runner.getSendMessage();
            final WorkflowRunTool runTool = fixture.runTool();
            final String blueprint = DaWorkflowTestSupport.threeMemberDag("T-067-S05");
            final String message = "MSG-067-S05-" + RUN;

            // 第一次运行**前**定向留言给 mB
            final Map<String, Object> sent = DaWorkflowTestSupport.dataOf(DaWorkflowTestSupport.output(
                    send.invoke(Map.of("target", "mB", "content", message), Map.of())));
            // 【2026-10-26 r6 判据改按设计口径】原断言 containsEntry("status","sent") 钉的是**实现细节**：
            // Feature-067 §4「send_message」与 §5.1.3、L2-067 §78 只规定**行为**（定向按成员标识投递、
            // 广播到达全部已登记成员、接收方在下一步 subGoal 的 [pending messages] 段看到、取走即清），
            // **从未定义出参字段名或 status 的取值**。r6 起实现把 status 由 "sent" 改为 "queued"，并新增
            // mode/queued_for/recipients/delivered/delivery 等键（SendMessageTool.receipt:246-272）
            // ⇒ 旧断言失败**不是产品缺陷**，是我方判据钉错了层。
            // 现只断"设计承诺的可观测量"：target 回带（出自 basics()，是**消息本身**的字段而非状态口径）。
            // 「定向投递 + 取走即清」这两条设计 MUST 由本用例后半段的**成员实收输入**断言承载（下方 first/second）。
            assertThat(sent).as("定向留言应回带 target（消息本身字段）；实收=%s", sent)
                    .containsEntry("target", "mB");

            final Map<String, Object> first = DaWorkflowTestSupport.dataOf(DaWorkflowTestSupport.output(
                    runTool.invoke(Map.of("blueprint", blueprint, "question", "U-067-S05-1-" + RUN), Map.of())));
            final Map<String, Object> second = DaWorkflowTestSupport.dataOf(DaWorkflowTestSupport.output(
                    runTool.invoke(Map.of("blueprint", blueprint, "question", "U-067-S05-2-" + RUN), Map.of())));
            assertThat(first).containsEntry("status", "completed");
            assertThat(second).containsEntry("status", "completed");

            final Recording memberB = fixture.members.get("mB");
            assertThat(memberB).as("mB 的执行体应已被装配").isNotNull();
            assertThat(memberB.calls()).as("两次运行各调用一次").isEqualTo(2);
            // 第一次运行：留言随任务输入投影送达
            assertThat(memberB.subGoal(0)).as("第一次运行 mB 实收输入应含该留言；实收=[%s]",
                    memberB.subGoal(0)).contains(message);
            // 第二次运行：取走即清、不重放
            assertThat(memberB.subGoal(1)).as("第二次运行 mB 实收输入**不应**再含该留言（取走即清）；实收=[%s]",
                    memberB.subGoal(1)).doesNotContain(message);
            // 未被定向的成员两次都不应收到该留言
            for (String other : List.of("mA", "mC")) {
                final Recording rec = fixture.members.get(other);
                assertThat(rec.allSubGoals()).as("成员 %s 未被定向，不应收到该留言", other)
                        .allSatisfy(goal -> assertThat(goal).doesNotContain(message));
            }
            // 参数缺失族：target/content 缺一即给可读错误
            final String error = DaWorkflowTestSupport.errorOf(DaWorkflowTestSupport.output(
                    send.invoke(Map.of("target", "mB"), Map.of())));
            assertThat(error).as("缺 content 时文案应指明所需参数；实收=[%s]", error)
                    .contains("target").contains("content");
        }
    }

    @Test
    @Story("067-S06: 广播可达全员（含从未被定向过的成员）")
    @DisplayName("067-S06 target=all 后 mA/mB/mC 三者实收输入都含广播内容（含从未被定向过的 mC）")
    void broadcastReachesEveryRegisteredMember() {
        try (DaWorkflowTestSupport.Fixture fixture = DaWorkflowTestSupport.fixture("067-S06", RUN)) {
            final SendMessageTool send = fixture.runner.getSendMessage();
            final WorkflowRunTool runTool = fixture.runTool();
            final String blueprint = DaWorkflowTestSupport.threeMemberDag("T-067-S06");
            final String directed = "DIR-067-S06-" + RUN;
            final String broadcast = "BC-067-S06-" + RUN;

            // 第一次运行：完成成员登记（绑定期 registerMember 预建收件箱）
            final Map<String, Object> first = DaWorkflowTestSupport.dataOf(DaWorkflowTestSupport.output(
                    runTool.invoke(Map.of("blueprint", blueprint, "question", "U-067-S06-1-" + RUN), Map.of())));
            assertThat(first).containsEntry("status", "completed");
            assertThat(fixture.members).as("三个成员都应已被登记并装配").containsKeys("mA", "mB", "mC");
            // 第一次运行时还没有任何留言
            fixture.members.forEach((memberId, rec) ->
                    assertThat(rec.subGoal(0)).as("第一次运行成员 %s 不应收到任何留言", memberId)
                            .doesNotContain(directed).doesNotContain(broadcast));

            // 先只向 mA 定向留言（⇒ mC 从未被定向过），再广播
            DaWorkflowTestSupport.dataOf(DaWorkflowTestSupport.output(
                    send.invoke(Map.of("target", "mA", "content", directed), Map.of())));
            final Map<String, Object> broadcastAck = DaWorkflowTestSupport.dataOf(
                    DaWorkflowTestSupport.output(
                            send.invoke(Map.of("target", "all", "content", broadcast), Map.of())));
            // 【2026-10-26 r6 判据改按设计口径，并**加强**】同上：不再断 status 的字面值（设计未定义）。
            // 改为直接断 Feature-067 §4 的 MUST 原文——「广播必须到达**全部已登记成员**（含从未被定向过的）」：
            // 出参的排队名单 queued_for 必须覆盖 mA/mB/mC 三者，其中 **mC 从未被定向过**（本用例上方刻意只向
            // mA 定向）。这比旧断言 `status=sent` **更强、且直接来自设计原文**。
            assertThat(broadcastAck).as("广播应回带 target=all；实收=%s", broadcastAck)
                    .containsEntry("target", "all");
            assertThat(String.valueOf(broadcastAck.get("queued_for")))
                    .as("Feature-067 §4 MUST：广播必须到达全部已登记成员（含从未被定向过的 mC）；实收=%s",
                            broadcastAck)
                    .contains("mA").contains("mB").contains("mC");

            // 第二次运行：三者都应收到广播
            final Map<String, Object> second = DaWorkflowTestSupport.dataOf(DaWorkflowTestSupport.output(
                    runTool.invoke(Map.of("blueprint", blueprint, "question", "U-067-S06-2-" + RUN), Map.of())));
            assertThat(second).containsEntry("status", "completed");

            for (String memberId : List.of("mA", "mB", "mC")) {
                final Recording rec = fixture.members.get(memberId);
                assertThat(rec.calls()).as("成员 %s 两次运行各调用一次", memberId).isEqualTo(2);
                assertThat(rec.subGoal(1)).as("成员 %s 第二次运行实收输入应含广播内容（含从未被定向过的 mC）；"
                        + "实收=[%s]", memberId, rec.subGoal(1)).contains(broadcast);
            }
            // 定向留言只到 mA
            assertThat(fixture.members.get("mA").subGoal(1))
                    .as("mA 第二次运行应同时收到定向留言；实收=[%s]",
                            fixture.members.get("mA").subGoal(1)).contains(directed);
            for (String other : List.of("mB", "mC")) {
                assertThat(fixture.members.get(other).subGoal(1))
                        .as("成员 %s 不应收到只定向给 mA 的留言", other).doesNotContain(directed);
            }
            // 第三次运行：广播也已被取走（取走即清同样适用于广播）
            DaWorkflowTestSupport.dataOf(DaWorkflowTestSupport.output(runTool.invoke(
                    Map.of("blueprint", blueprint, "question", "U-067-S06-3-" + RUN), Map.of())));
            for (String memberId : List.of("mA", "mB", "mC")) {
                assertThat(fixture.members.get(memberId).subGoal(2))
                        .as("成员 %s 第三次运行不应重放已取走的广播", memberId).doesNotContain(broadcast);
            }
        }
    }
}

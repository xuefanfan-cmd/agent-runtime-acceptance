package com.huawei.ascend.sit.cases.integration.workflow_call;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.config.TestEnvironment;
import com.huawei.ascend.sit.conversation.Conversation;
import com.huawei.ascend.sit.conversation.ConversationIdentity;
import com.huawei.ascend.sit.conversation.ConversationInteractionAdapter;
import com.huawei.ascend.sit.conversation.DriveMode;
import com.huawei.ascend.sit.conversation.ParallelTurnResult;
import com.huawei.ascend.sit.conversation.Step;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.transport.MessageProtocol;
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.huawei.ascend.sit.cases.integration.workflow_call.BalanceTransferFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并行转账（{@code parallel-transfer} profile）的<b>独立</b>验收用例（openjiuwen 限定）：仅 A2A_STREAM，
 * 仅 edpa-adapter + edpa-plan-agent，不继承 {@link AbstractBalanceThenTransfersTest}（其 runFlow 锁死且单 cid 线性）。
 *
 * <p>{@code parallel-transfer} Spring profile 切换 plan-agent 系统提示为并行分解：余额串行查完后，李四/王五两笔转账
 * 在<b>同一轮批量派发</b>，runtime 扇出 2 个并发子会话（共享 parentContextId）。本用例用
 * {@link DriveMode#parallelStepUi(Map)} + {@link com.huawei.ascend.sit.conversation.Turn#runParallel()}
 * 驱动这两个子会话：kickoff 流带回 {@code _remote_invocation.{batchId,toolCallId}} × 2，框架推导 childCid 后并发驱动每腿，
 * 续传经 {@code params.message.parts[0].metadata.toolCallId=<childId>} 路由到指定子成员（body.conversation_id 保持 parentCid，
 * 不携带 metadata.runtime.remoteToolInputs）。
 *
 * <p><b>硬断言</b>：核心语义不泄露 + ≥2 个子会话被驱动 + 未被 maxInteractions 熔断 + 命中转账完成态标记其一。
 * 选择按 step_id 键控（非位置序），故两腿<b>非对称</b>也能各自拿到正确 kv。
 * 确切人工步序已在真机钉死：完整序 {@code on_payee_input→on_paycard_input→on_confirm_remit}，其中一腿（收款人预解析）跳过 {@code on_payee_input}；运行时的 {@code [parallel-transfer][child …] labels} 行会逐腿复核该序列。
 *
 * @see PlanAgentDirectStreamingTest 串行直连变体（继承 AbstractBalanceThenTransfersTest）
 */
@Tag("integration")
@Disabled("真机验收：需 -Dtest.env=openjiuwen + LLM_API_KEY + Docker（edpa-plan-agent parallel-transfer profile）")
@Feature("FEAT-004: 任务驱动远程智能体调用")
@Stories({ @Story("wf.parallel-transfer: 并行转账批量派发与并发驱动") })
class PlanAgentParallelTransferStreamingTest extends BaseManagedStackTest {

    /**
     * 每腿转账的人工步选择，按 step_id 键控（非位置序）。两腿非对称时（一腿多一个 on_payee_input 收款人步），
     * 每条腿按自己当前的 step_id 取 kv，不会被位置序错配。值取自串行转账用例的权威步序
     * （{@link AbstractBalanceThenTransfersTest} / {@link PlanAgentReactiveQueryTest} 等，三处一致）。
     */
    private static final Map<String, Map<String, String>> SELECTIONS_BY_STEP = Map.of(
            "on_payee_input", Map.of("recSerialNum", "SN20240001"),
            "on_paycard_input", Map.of("accIndex", "0"),
            "on_confirm_remit", Map.of("_text", "确定"));

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // 第一行 gate：非 openjiuwen 在基类 .start() 之前 abort，不拉容器。
        Assumptions.assumeTrue(TestEnvironment.current() == TestEnvironment.OPENJIUWEN,
                "openjiuwen profile only — re-run with -Dtest.env=openjiuwen (需 LLM_API_KEY 等)");
        // 精简栈：不起 edpa-gateway。envexplorer 由 edpa-adapter 的 service-bindings 自动拉起。
        // parallel-transfer profile 切换 plan-agent 提示为并行分解（余额串行 + 转账同轮批量派发）。
        return SutStack.builder(config)
                .agent("edpa-adapter")
                .agent(PLAN_AGENT, a -> a.profile("parallel-transfer").downstream("edpa-adapter"));
    }

    @Test
    @DisplayName("并行转账（parallel-transfer，stepUi 并发扇出）— A2A_STREAM")
    void parallelTransfersA2aStream() {
        try (Conversation conv = Conversation
                .at(stack.baseUrl(PLAN_AGENT), stack.serviceUrl("envexplorer"))
                .identity(ConversationIdentity.loadDefault())
                .transport(new ConversationInteractionAdapter(
                        MessageProtocol.A2A_STREAM, client(PLAN_AGENT), ROUND_TIMEOUT_MS))
                .timeout(Duration.ofSeconds(600))
                .open()) {

            ParallelTurnResult r = conv.turn(SENTENCE)
                    .intent("")
                    .driveMode(DriveMode.parallelStepUi(SELECTIONS_BY_STEP))
                    .runParallel();

            String blob = concat(r.allEvents());
            List<String> hit = TRANSFER_DONE.stream().filter(blob::contains).toList();

            // Diagnostic (folded in from the Phase 0 capture probe): per-child step-label sequence —
            // the quickest way to see which leg hit which manual step when a future regression changes
            // the parallel-transfer sequence. Printed BEFORE the assertions so a hard-assert failure
            // still leaves the per-leg picture in stdout for triage.
            System.out.println("[parallel-transfer][children=" + r.childCount() + "]");
            for (ParallelTurnResult.ChildResult c : r.children()) {
                List<String> labels = new ArrayList<>();
                for (Step s : c.steps()) {
                    labels.add(s.stepLabel() != null ? s.stepLabel() : "(auto)");
                }
                System.out.println("[parallel-transfer][child " + c.toolCallId()
                        + "] steps=" + c.steps().size() + " capped=" + c.capped() + " labels=" + labels);
            }
            System.out.println("[parallel-transfer][completion markers hit] " + hit);

            // 并发驱动硬断言：核心语义不泄露 + ≥2 子会话 + 未被 maxInteractions 熔断 + 命中转账完成态标记其一。
            assertCoreSemantics(blob);
            assertThat(r.childCount()).as("≥2 个并行子会话被驱动").isGreaterThanOrEqualTo(2);
            assertThat(r.capped()).as("未被 maxInteractions 熔断").isFalse();
            assertThat(hit).as("转账完成态标记命中其一（候选: " + TRANSFER_DONE + "）").isNotEmpty();
        }
    }
}

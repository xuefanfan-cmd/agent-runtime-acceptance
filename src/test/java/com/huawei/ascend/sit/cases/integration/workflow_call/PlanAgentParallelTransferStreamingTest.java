package com.huawei.ascend.sit.cases.integration.workflow_call;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.config.TestEnvironment;
import com.huawei.ascend.sit.conversation.Conversation;
import com.huawei.ascend.sit.conversation.ConversationIdentity;
import com.huawei.ascend.sit.conversation.ConversationInteractionAdapter;
import com.huawei.ascend.sit.conversation.DriveMode;
import com.huawei.ascend.sit.conversation.ParallelTurnResult;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.transport.MessageProtocol;
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;
import org.junit.jupiter.api.*;

import java.time.Duration;
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
 * {@link DriveMode#parallelStepUi(List)} + {@link com.huawei.ascend.sit.conversation.Turn#runParallel()}
 * 驱动这两个子会话：kickoff 流带回 {@code _remote_invocation.{batchId,toolCallId}} × 2，框架推导 childCid 后并发驱动每腿，
 * 续传经 {@code params.message.parts[0].metadata.toolCallId=<childId>} 路由到指定子成员（body.conversation_id 保持 parentCid，
 * 不携带 metadata.runtime.remoteToolInputs）。
 *
 * <p><b>宽松断言</b>：只断言两个子会话都被驱动到完成（未熔断 + 命中转账完成态标记其一 + 核心语义不泄露）。
 * 确切人工步序（每腿的 select 步）由 Phase 0 真机抓包钉死；当前按串行同形假设（选卡 + 确认）填 sharedSelections，
 * 真机跑通后按实测步序回填。
 *
 * @see PlanAgentDirectStreamingTest 串行直连变体（继承 AbstractBalanceThenTransfersTest）
 */
@Tag("integration")
@Disabled("当前不支持客户端并发续轮")
@Feature("FEAT-004: 任务驱动远程智能体调用")
@Stories({ @Story("wf.parallel-transfer: 并行转账批量派发与并发驱动") })
class PlanAgentParallelTransferStreamingTest extends BaseManagedStackTest {

    /**
     * 每腿转账的人工步（Phase 0 钉死确切步序；先按串行同形假设：选卡 + 确认）。
     * 每个子会话消费自己的位置序副本（selIdx 各自从 0 起），故两腿共享同一份列表。
     */
    private static final List<Map<String, String>> SHARED_SELECTIONS = List.of(
            Map.of("accIndex", "0"),    // on_paycard_input
            Map.of("_text", "确定"));   // on_confirm_remit

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
                    .driveMode(DriveMode.parallelStepUi(SHARED_SELECTIONS))
                    .runParallel();

            String blob = concat(r.allEvents());
            assertCoreSemantics(blob);

            // 并发驱动断言：≥2 个子会话被驱动，未被 maxInteractions 熔断，命中转账完成态标记其一。
            assertThat(r.childCount()).as("≥2 个并行子会话被驱动").isGreaterThanOrEqualTo(2);
            assertThat(r.capped()).as("未被 maxInteractions 熔断").isFalse();
            List<String> hit = TRANSFER_DONE.stream().filter(blob::contains).toList();
            assertThat(hit).as("转账完成态标记命中其一（候选: " + TRANSFER_DONE + "）").isNotEmpty();

            System.out.println("[parallel-transfer][children=" + r.childCount() + "] "
                    + r.children().stream()
                        .map(c -> c.toolCallId() + ":steps=" + c.steps().size() + ",capped=" + c.capped())
                        .toList());
            System.out.println("[parallel-transfer][completion markers hit] " + hit);
        }
    }
}

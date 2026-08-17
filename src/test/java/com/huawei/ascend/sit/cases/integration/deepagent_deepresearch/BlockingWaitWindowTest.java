package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-001.blocking-wait-window (C8) — 阻塞调用不得无限挂起.
 *
 * <p><b>基线</b>：2026-08-10 版 FEAT-001 特性档 §5.1.6「阻塞 S2C 语义」：
 * 「阻塞等待不能无限挂起。超过 agent 执行等待窗口时，runtime 可以返回当前 Task 快照；
 * 超过消费等待窗口时，runtime 必须返回 JSON-RPC error。」
 *
 * <p><b>断言口径</b>：spec 承诺的是「有界返回」这一下限，两个合规分支：
 * <ul>
 *   <li>分支 A：等待窗口内返回当前 Task 快照（非终态 WORKING 快照即 deep-research 当前实测行为，
 *       毫秒级返回 ack + taskId 供后续 {@code GetTask} 拉取）——快照必须能抽出 taskId，
 *       否则调用方无法继续，快照失去意义；</li>
 *   <li>分支 B：返回 JSON-RPC error（消费等待窗口超限）。</li>
 * </ul>
 * 唯一 FAIL 分支：窗口内既无快照也无 error —— 连接被无限挂起。
 *
 * <p><b>不断言</b>：具体窗口时长（spec 未定值，本用例取 {@code SYNC_ACK_TIMEOUT_MS} 作为
 * SIT 侧观察上限，远大于任何合理实现的窗口）；快照的具体 state 值（WORKING / 已终态均合规——
 * 快任务在窗口内直接完成也是合法返回）。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.blocking-wait-window: 阻塞 SendMessage 有界返回（快照或 error），不无限挂起")
class BlockingWaitWindowTest extends BaseManagedStackTest {

    private static final String DEEP_RESEARCH = "deep-research";

    /**
     * SIT 侧观察上限：任何合理实现的「执行等待窗口 + 消费等待窗口」都应远小于此值。
     * 超过即判定为无限挂起（FAIL 分支）。
     */
    private static final long SYNC_ACK_TIMEOUT_MS = 60_000;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .streaming(false)
                .agent(DEEP_RESEARCH);
    }

    @Test
    @DisplayName("FEAT-001.blocking-wait-window: 长任务 blocking SendMessage 在窗口内返回快照或 error")
    void blockingSendMessageReturnsWithinWaitWindow() {
        A2aServiceClient a2a = client(DEEP_RESEARCH);

        String runSuffix = "-" + UUID.randomUUID().toString().substring(0, 8);
        String contextId = "ctx-feat001-blocking-window" + runSuffix;
        // 长任务 prompt：research 链路（LLM + 搜索）耗时远超任何合理阻塞窗口，
        // 用于逼出「窗口超限 → 快照 / error」分支，而不是快任务的直接终态返回。
        String userInput = "请深入调研 2025 年以来 RISC-V 在数据中心服务器领域的商用进展,"
                + "对比至少三家厂商的产品路线并给出详细分析报告。";

        Message message = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .contextId(contextId)
                .parts(List.of(new TextPart(userInput)))
                .build();

        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> sendError = new AtomicReference<>();
        List<BiConsumer<ClientEvent, AgentCard>> consumers = List.of(collector.createConsumer());
        Consumer<Throwable> errorHandler = sendError::set;

        long start = System.currentTimeMillis();
        a2a.sendMessage(message, consumers, errorHandler);

        // 合规分支 A：窗口内收到 Task 快照（任意 state —— WORKING 或直接终态均可）。
        // 注意：awaitAnyTaskState 超时抛 ConditionTimeoutException（Awaitility 语义），不是返回 null。
        TaskState ackState;
        try {
            ackState = collector.awaitAnyTaskState(SYNC_ACK_TIMEOUT_MS);
        } catch (ConditionTimeoutException timeout) {
            // 合规分支 B：无快照但有显式 JSON-RPC error（消费等待窗口超限语义）。
            assertThat(sendError.get())
                    .as("FEAT-001 §5.1.6: blocking SendMessage 在 %d ms 内既无 Task 快照也无 "
                                    + "JSON-RPC error —— 连接被无限挂起（唯一 FAIL 分支）",
                            SYNC_ACK_TIMEOUT_MS)
                    .isNotNull();
            return;
        }
        long elapsed = System.currentTimeMillis() - start;

        // 分支 A 的快照必须可用：taskId 可抽取，调用方才能用 GetTask 继续（快照的意义所在）。
        assertThat(collector.findFirstTaskId())
                .as("FEAT-001 §5.1.6: 窗口内返回的 Task 快照应携带 taskId（%d ms 收到 state=%s），"
                        + "否则调用方无法通过 GetTask 继续跟进", elapsed, ackState)
                .isNotBlank();
        assertThat(collector.findFirstContextId())
                .as("快照 contextId 应回显 send 时值")
                .isEqualTo(contextId);
    }
}

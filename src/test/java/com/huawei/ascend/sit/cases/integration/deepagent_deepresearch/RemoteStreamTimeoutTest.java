package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.mock.MockRemoteAgentServer;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * FEAT-004.remote-stream-timeout — 远端 A2A SSE 长时间无数据后 server 主动关流时,agent-runtime 应
 * 感知 SSE close 并让父 Task 在有限时间内到达终态(不 hang),这是 BUG-004 已修复能力的活体 watchdog。
 *
 * <p><b>开发组 2026-07-23 澄清的错误分类模型</b>(三档稳定码):
 * <ul>
 *   <li>{@code REMOTE_TIMEOUT}:远端 SSE 超过 stream-timeout —— <b>回灌 LLM 当 tool-result</b>,
 *       由 LLM 决策终态(通常 COMPLETED,LLM 兜底汇总)。</li>
 *   <li>{@code REMOTE_STREAM_CLOSED}:远端 SSE 已建立后 server 关流,无终态事件 —— 同样<b>回灌 LLM</b>,
 *       LLM 决策终态。本用例场景。</li>
 *   <li>{@code REMOTE_ERROR}:连接层失败/远端不可达(issue-42 场景) —— 直接报 FAILED,由
 *       {@link DownstreamAgentKilledMidStreamTest} + {@code BUG-005} 覆盖,<b>本用例不涉及</b>。</li>
 * </ul>
 *
 * <p><b>Spec 依据</b>:
 * <ul>
 *   <li><b>L2 §2.1 能力清单</b>(architecture/L2-Low-Level-Design/agent-runtime/
 *       Feat-Func-004-remote-agent-orchestration.md line 72):「超时检测 ✅ REMOTE_TIMEOUT + 孤儿 Task cancel」。</li>
 *   <li><b>L2 §3.2 远程调用管道 · 远端结果映射</b>(同档 line 163):「超时 | 超过 stream-timeout |
 *       {@code {"error":"remote A2A stream timed out","code":"REMOTE_TIMEOUT"}} |」—— 注意:此 payload
 *       按澄清后的模型走"回灌 LLM"路径,不落 A2A wire 的 {@code status.message}。</li>
 *   <li><b>L2 §5.3 错误、取消、降级处理</b>(同档 line 280)。</li>
 * </ul>
 *
 * <p><b>本用例定性</b>:<b>BUG-004 修复回归 watchdog</b>(<em>期望绿</em>)。
 * <ul>
 *   <li>BUG-004 首观(2026-07-21):mock 30s 关流后 SUT 侧 3 分钟零日志,父 Task 永久卡
 *       {@code requires-interaction} —— 用例纯超时。</li>
 *   <li>BUG-004 修复后(2026-07-23):SUT 侧 {@code A2ARemoteAgentClient} 感知 SSE close,分派
 *       {@code code=REMOTE_STREAM_CLOSED},回灌 LLM,LLM 兜底汇总收束到终态。</li>
 * </ul>
 *
 * <p><b>为何不断言 {@code REMOTE_STREAM_CLOSED} 字面串出现在 client event</b>:
 * 按澄清模型,该稳定码走"回灌 LLM"路径,payload 是 LLM 的 tool-result prompt,不会以字面串出现在
 * A2A wire 上的 {@code status.message.parts} 或 artifact 中 —— LLM 会消化并以自然语言汇总输出。
 * 因此本用例只断言 <b>健康度</b>(不 hang + 走了远端 tool call),而不断言 wire 端字面串。
 *
 * <p><b>拓扑</b>:
 * <pre>
 *   [test] --SendMessage--> [deep-research :SutStack] --card fetch--> [MockRemoteAgentServer]
 *                            (真 SUT 进程)               --A2A SSE-->  (JDK HttpServer;stall_sse)
 *                                                        └── 30s keep-alive 后主动 close
 * </pre>
 *
 * <p><b>断言层次</b>:
 * <ol>
 *   <li><b>层 2(前置一致性)</b>:{@code mock.cardGetCount >= 1} 且 {@code mock.a2aPostCount >= 1}
 *       —— 证明 SUT 走到了 Card Cache + 远端 tool 装配 + SendStreamingMessage。</li>
 *   <li><b>层 1(核心 · BUG-004 修复)</b>:任务在 {@link #SEND_TIMEOUT_MS} 内到达某终态
 *       (不再永久 hang)—— 证明 SSE close 被感知、父 Task 被 un-suspend。</li>
 *   <li><b>层 3(健康度)</b>:{@code mock.a2aClientClosedCount >= 1} 或 {@code a2aLastHoldMillis < MOCK_STALL_MS}
 *       —— 证明 SUT 主动关了 SSE 连接(非必需,但作为辅助信号)。</li>
 * </ol>
 *
 * <p><b>不断言</b>:
 * <ul>
 *   <li>{@code REMOTE_STREAM_CLOSED} 字面串出现在 wire —— 按澄清模型是"回灌 LLM"路径,不落 wire。</li>
 *   <li>具体终态类型 —— 允许 FAILED / COMPLETED(LLM 兜底汇总),spec §3.2 只规定 toolResult 内容不规定父终态。</li>
 *   <li>mock 侧是否收到 {@code CancelTask} POST —— best-effort cancel 属实现细节,时序不硬约束。</li>
 * </ul>
 *
 * <p><b>Tag 说明</b>:
 * <ul>
 *   <li>{@code manual} —— 需本地 deep-research jar;CI 未内置。</li>
 *   <li>{@code slow} —— stall_sse 最长 30s + LLM 兜底汇总,单跑约 60-90s。</li>
 * </ul>
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-004")
@Tag("manual")
@Tag("slow")
@Feature("FEAT-004: 任务驱动远程智能体调用")
@Story("da.remote-stream-timeout: BUG-004 修复 watchdog — SSE close 感知 + 父 Task 收束")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoteStreamTimeoutTest {

    private static final String DEEP_RESEARCH = "deep-research";

    /** stall_sse 最长持续:比预期 SUT stream-timeout 大一档,让 SUT 有充分空间先关连接。 */
    private static final long MOCK_STALL_MS = 30_000;

    /** 用例整体超时:mock stall + SUT 收束 + LLM 兜底 + margin。 */
    private static final long SEND_TIMEOUT_MS = 180_000;

    /**
     * 明确 route 到远端 search tool 的 prompt —— 让 planner 有强烈动机去调用远端。
     * 若 SUT 装配了远端 tool,LLM 会打向 mock,mock 挂 SSE 触发本用例观测路径。
     */
    private static final String USER_INPUT =
            "帮我搜索 2026 年 7 月 15 日全球黄金价格盘中最高价的准确数字,直接给出数字和单位";

    /** 非空 skills —— 让 P1.1 那条空 skills 分支不触发,tool spec 应正常装配。 */
    private static final String NON_EMPTY_SKILLS_JSON =
            "[{\"id\":\"web_search\","
            + "\"name\":\"web_search\","
            + "\"description\":\"Search the internet and return concise result summaries with citations.\","
            + "\"tags\":[\"search\"],"
            + "\"inputModes\":[\"text\",\"text/plain\"],"
            + "\"outputModes\":[\"text\",\"text/plain\"]}]";

    private TestConfig config;
    private MockRemoteAgentServer mock;
    private SutStack deepStack;

    @BeforeAll
    void startStack() throws IOException {
        config = TestConfig.load();
        mock = MockRemoteAgentServer.builder()
                .name("MockSearchAgent")
                .description("SIT mock — SSE stalls to trigger REMOTE_STREAM_CLOSED path (回灌 LLM)")
                .rawSkillsJson(NON_EMPTY_SKILLS_JSON)
                .stallA2aSse(MOCK_STALL_MS)
                .start();
        deepStack = SutStack.builder(config)
                .agent(DEEP_RESEARCH, a -> a.env("SEARCH_AGENT_URL", mock.baseUrl()))
                .start();
    }

    @AfterAll
    void tearDown() {
        if (deepStack != null) {
            deepStack.close();
        }
        if (mock != null) {
            mock.close();
        }
    }

    @Test
    @DisplayName("FEAT-004.remote-stream-timeout: SSE stall 30s → SUT 感知 close 并让父 Task 在 SEND_TIMEOUT_MS 内到达终态 "
            + "(BUG-004 修复 watchdog)")
    void remoteStreamStallShouldNotHangParentTask() {
        A2aServiceClient a2a = deepStack.client(DEEP_RESEARCH);

        String contextId = "ctx-feat004-remote-timeout-" + UUID.randomUUID().toString().substring(0, 8);
        Message message = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .contextId(contextId)
                .parts(List.of(new TextPart(USER_INPUT)))
                .build();

        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> streamError = new AtomicReference<>();
        List<BiConsumer<ClientEvent, AgentCard>> consumers = List.of(collector.createConsumer());
        Consumer<Throwable> errorHandler = streamError::set;

        long sendStartMs = System.currentTimeMillis();
        a2a.sendMessage(message, consumers, errorHandler);

        TaskState terminalState;
        try {
            terminalState = collector.awaitTerminalState(SEND_TIMEOUT_MS);
        } catch (ConditionTimeoutException timeout) {
            long elapsed = System.currentTimeMillis() - sendStartMs;
            Throwable err = streamError.get();
            String msg = "FEAT-004.remote-stream-timeout [层1 核心 · BUG-004 回归]: "
                    + "awaitTerminalState 纯超时 —— SUT 未在 " + SEND_TIMEOUT_MS + "ms 内收束。\n"
                    + "  预期形态:BUG-004 修复后 A2ARemoteAgentClient 应感知 SSE close/EOF,\n"
                    + "           分派 code=REMOTE_STREAM_CLOSED 并回灌 LLM,LLM 兜底汇总到终态。\n"
                    + "  当前形态:纯 hang —— BUG-004 回归。\n"
                    + "  contextId=" + contextId
                    + "\n  mock.baseUrl=" + mock.baseUrl()
                    + "\n  mock.cardGetCount=" + mock.cardGetCount()
                    + "\n  mock.a2aPostCount=" + mock.a2aPostCount()
                    + "\n  mock.a2aClientClosedCount=" + mock.a2aClientClosedCount()
                    + "\n  mock.a2aLastHoldMillis=" + mock.a2aLastHoldMillis()
                    + "\n  elapsedMs=" + elapsed;
            if (err != null) {
                fail(msg + "\n  stream.error 已挂:", err);
            }
            fail(msg, timeout);
            return;
        }
        long elapsedMs = System.currentTimeMillis() - sendStartMs;

        // 层 2(前置一致性):SUT 应至少拉过一次 card 且发起过一次 A2A POST
        assertThat(mock.cardGetCount())
                .as("FEAT-004.remote-stream-timeout [层2 前置]: SUT 应至少拉取一次 mock 的 card;"
                        + "若为 0 说明 SEARCH_AGENT_URL 未被 SUT 消费,层 1 结果无意义\n"
                        + "  contextId=%s\n  mock.baseUrl=%s", contextId, mock.baseUrl())
                .isGreaterThanOrEqualTo(1);
        assertThat(mock.a2aPostCount())
                .as("FEAT-004.remote-stream-timeout [层2 前置]: SUT 应至少向 mock /a2a 发起一次 POST;"
                        + "若为 0 说明 tool spec 未装配或 planner 未 route,层 1 结果无意义\n"
                        + "  contextId=%s\n  mock.baseUrl=%s\n  terminalState=%s",
                        contextId, mock.baseUrl(), terminalState)
                .isGreaterThanOrEqualTo(1);

        // 层 1(核心 · BUG-004 修复):任务已到达终态(既然 awaitTerminalState 已返回,只做防御性 non-null)
        assertThat(terminalState)
                .as("FEAT-004.remote-stream-timeout [层1 核心]: 终态应非空\n  contextId=%s", contextId)
                .isNotNull();

        // 层 3(健康度):SUT 应主动关了 SSE 连接(mock 侧计数或 hold 时长小于 MOCK_STALL_MS)。
        // 非硬约束 —— 只作 soft check,失败会 log 但不阻断;真正的健康度靠层 1 的"不 hang"托底。
        boolean sutClosedSseActively =
                mock.a2aClientClosedCount() >= 1 || mock.a2aLastHoldMillis() < MOCK_STALL_MS;
        if (!sutClosedSseActively) {
            System.err.println("FEAT-004.remote-stream-timeout [层3 健康度 soft-check]: "
                    + "mock 侧未观察到 SUT 主动关 SSE(a2aClientClosedCount=" + mock.a2aClientClosedCount()
                    + ", a2aLastHoldMillis=" + mock.a2aLastHoldMillis() + " >= MOCK_STALL_MS=" + MOCK_STALL_MS
                    + ")—— SUT 可能是等 mock 关流后被动感知的,不阻断本用例,但值得留意。"
                    + "  contextId=" + contextId + "  elapsedMs=" + elapsedMs);
        }
    }
}

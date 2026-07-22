package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.mock.MockRemoteAgentServer;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.a2aproject.sdk.spec.UpdateEvent;
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
 * FEAT-004.remote-stream-timeout — 远端 A2A SSE 长时间无数据时,agent-runtime 应在
 * per-remote {@code stream-timeout} 内主动收束,产出携带 {@code REMOTE_TIMEOUT} 稳定码的
 * 结构化错误(spec §3.2:{@code {"error":"remote A2A stream timed out","code":"REMOTE_TIMEOUT"}}),
 * best-effort 取消远端 Task.
 *
 * <p><b>Spec 依据</b>(primary source 已 verbatim 核对):
 * <ul>
 *   <li><b>L2 §2.1 能力清单</b>(architecture/L2-Low-Level-Design/agent-runtime/
 *       Feat-Func-004-remote-agent-orchestration.md line 72):「超时检测 ✅ REMOTE_TIMEOUT + 孤儿 Task cancel」。</li>
 *   <li><b>L2 §3.2 远程调用管道 · 远端结果映射</b>(同档 line 163):「超时 | 超过 stream-timeout |
 *       {@code {"error":"remote A2A stream timed out","code":"REMOTE_TIMEOUT"}} |」。</li>
 *   <li><b>L2 §5.3 错误、取消、降级处理</b>(同档 line 280):「远程超时 | 超过 stream-timeout |
 *       REMOTE_TIMEOUT → child error | toolResult = {@code {"error":"REMOTE_TIMEOUT"}} |」。</li>
 *   <li><b>agent-runtime README</b>(line 121):「{@code stream-timeout} caps one streaming invocation
 *       of that remote agent. On expiry the runtime keeps the results already received, appends a
 *       failed result carrying the stable code {@code REMOTE_TIMEOUT} (retryable), and best-effort
 *       cancels the remote task.」</li>
 *   <li><b>SUT 源常量</b>:{@code A2aRemoteAgentOutboundAdapter.REMOTE_TIMEOUT_CODE = "REMOTE_TIMEOUT"}
 *       (spring-ai-ascend/agent-runtime/src/main/.../a2a/A2aRemoteAgentOutboundAdapter.java line 47)。</li>
 * </ul>
 *
 * <p><b>本用例定性</b>:<b>bug watchdog</b> —— 覆盖
 * {@link ../../../../../../../docs/bugs/BUG-004-remote-sse-close-not-detected-parent-task-hangs-forever.md BUG-004}
 * (openjiuwen {@code A2ARemoteAgentClient} 完全未处理远端 SSE close/EOF,父 Task 永久卡
 * {@code requires-interaction})。<br>
 * 首跑观测:mock 30s 后主动 close SSE,但 {@code a2aClientClosedCount=0} · {@code elapsedMs≈180_000}
 * · SUT 侧 {@code A2A streaming call} 之后 3 分钟零日志 → 证明是 <b>基础错误路径完全缺失</b>
 * (不是"实现了但用了不同错误码"级别的 spec 差异)。因此:
 * <ul>
 *   <li>Layer B(核心 spec,{@code REMOTE_TIMEOUT} 字面串)—— <em>期望绿</em>,当前红是 SUT bug;</li>
 *   <li>Layer C(健康度,终态在 {@link #SEND_TIMEOUT_MS} 内到达)—— <em>期望绿</em>,当前红也是 SUT bug;</li>
 *   <li>Layer A(下限,{@code a2aClientClosedCount >= 1} 或有 SSE close 感知日志)—— 由 mock 计数器观测。</li>
 * </ul>
 * SUT 修复 BUG-004 后本用例自动转绿。
 *
 * <p><b>拓扑</b>:
 * <pre>
 *   [test] --SendMessage--> [deep-research :SutStack] --card fetch--> [MockRemoteAgentServer]
 *                            (真 SUT 进程)               --A2A SSE-->  (JDK HttpServer;stall_sse)
 *                                                        └── 保持连接开着,不发任何 SSE 事件
 * </pre>
 *
 * <p><b>断言层次</b>:
 * <ol>
 *   <li><b>层 2(前置一致性)</b>:{@code mock.cardGetCount() >= 1} 且 {@code mock.a2aPostCount() >= 1}
 *       —— 证明 SUT 走到了 Card Cache + 远端 tool 装配 + SendStreamingMessage;若不成立,
 *       层 1 的红/绿无意义(可能连 tool 都没装配上)。</li>
 *   <li><b>层 1(核心 spec)</b>:任一 client event(status message parts / artifact text)
 *       内出现字面串 {@code "REMOTE_TIMEOUT"} —— 证明 SUT 按 L2 §3.2 line 163 把超时投射为结构化错误。
 *       <b>预期首次红</b>(BUG-004 未修)。</li>
 *   <li><b>层 3(健康度)</b>:任务应在 {@code SEND_TIMEOUT_MS} 内到达某终态(不卡 SSE 死等)。
 *       若纯超时 → BUG-004 表现:SSE close 未被感知,父 Task 永久卡 {@code requires-interaction}。</li>
 * </ol>
 *
 * <p><b>不断言</b>:
 * <ul>
 *   <li>具体终态类型 —— 允许 FAILED(超时视作调用失败)或 COMPLETED(LLM 拿到 error toolResult 后
 *       兜底汇总),spec §3.2 line 163 只规定 toolResult 内容不规定父终态。</li>
 *   <li>mock 侧是否收到 {@code CancelTask} POST —— best-effort cancel 属实现细节,spec 未硬约束
 *       落到 mock 时序内可观测,不作为核心断言。</li>
 * </ul>
 *
 * <p><b>Tag 说明</b>:
 * <ul>
 *   <li>{@code manual} —— 需本地 deep-research jar;CI 未内置。</li>
 *   <li>{@code slow} —— stall_sse 最长 30s + 生成 + 收束,单跑约 60-90s。</li>
 * </ul>
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-004")
@Tag("manual")
@Tag("slow")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoteStreamTimeoutTest {

    private static final String DEEP_RESEARCH = "deep-research";

    /** stall_sse 最长持续:比预期 SUT stream-timeout 大一档,让 SUT 有充分空间先关连接。 */
    private static final long MOCK_STALL_MS = 30_000;

    /** 用例整体超时:mock stall + SUT 收束 + LLM 兜底 + margin。 */
    private static final long SEND_TIMEOUT_MS = 180_000;

    /** L2 §3.2 line 163 定义的稳定码:观测点核心字面串。 */
    private static final String EXPECTED_ERROR_CODE = "REMOTE_TIMEOUT";

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
                .description("SIT mock — SSE stalls to trigger REMOTE_TIMEOUT")
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
    @DisplayName("FEAT-004.remote-stream-timeout: SSE stall 30s → 父 Task 结构化错误含 REMOTE_TIMEOUT "
            + "(BUG-004 watchdog)")
    void remoteStreamStallShouldSurfaceRemoteTimeoutCode() {
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
            String msg = "FEAT-004.remote-stream-timeout: awaitTerminalState 纯超时 —— SUT 未在 "
                    + SEND_TIMEOUT_MS + "ms 内收束"
                    + "(BUG-004:openjiuwen A2ARemoteAgentClient 未感知 SSE close,父 Task 永久 hang)\n"
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
                        + "若为 0 说明 SEARCH_AGENT_URL 未被 SUT 消费,层 1 结果无效\n"
                        + "  contextId=%s\n  mock.baseUrl=%s", contextId, mock.baseUrl())
                .isGreaterThanOrEqualTo(1);
        assertThat(mock.a2aPostCount())
                .as("FEAT-004.remote-stream-timeout [层2 前置]: SUT 应至少向 mock /a2a 发起一次 POST;"
                        + "若为 0 说明 tool spec 未装配或 planner 未 route,层 1 结果无效\n"
                        + "  contextId=%s\n  mock.baseUrl=%s\n  terminalState=%s",
                        contextId, mock.baseUrl(), terminalState)
                .isGreaterThanOrEqualTo(1);

        // 层 1(核心 spec):任一 client event 内含 REMOTE_TIMEOUT 字面串
        String haystack = collectAllTextForTimeoutScan(collector);
        assertThat(haystack)
                .as("FEAT-004.remote-stream-timeout [层1 核心 spec L2 §3.2 line 163]: SUT 应按 spec 把远端 SSE"
                        + " 超时投射为结构化错误(toolResult 含 code=%s)。观察面:任一 client event 的"
                        + " status.message.parts / artifact TextPart 文本内应出现字面串 \"%s\"。\n"
                        + "  未命中 → BUG-004:SUT 未落 REMOTE_TIMEOUT 稳定码(基础错误路径缺失;首次预期红)\n"
                        + "  contextId=%s\n  mock.baseUrl=%s\n  mock.cardGetCount=%d\n"
                        + "  mock.a2aPostCount=%d\n  mock.a2aClientClosedCount=%d\n"
                        + "  mock.a2aLastHoldMillis=%d\n  elapsedMs=%d\n  terminalState=%s\n"
                        + "  stream.error=%s\n  haystack(head)=%s",
                        EXPECTED_ERROR_CODE, EXPECTED_ERROR_CODE,
                        contextId, mock.baseUrl(), mock.cardGetCount(), mock.a2aPostCount(),
                        mock.a2aClientClosedCount(), mock.a2aLastHoldMillis(), elapsedMs,
                        terminalState, streamError.get(), truncate(haystack, 800))
                .contains(EXPECTED_ERROR_CODE);

        // 层 3(健康度):终态可解析(既然到这一步 awaitTerminalState 已返回,只做防御性 non-null)
        assertThat(terminalState)
                .as("FEAT-004.remote-stream-timeout [层3]: 终态应非空\n  contextId=%s", contextId)
                .isNotNull();
    }

    /**
     * 把所有 client event 的可读文本汇集成一根大 haystack:
     * <ul>
     *   <li>task-bearing 事件(TaskEvent / TaskStatusUpdate): status.message().parts() 的 TextPart</li>
     *   <li>artifact 事件:collector.collectArtifactText()(已内部合并 chunk)</li>
     * </ul>
     * 用于 {@link String#contains(CharSequence)} 扫 {@code REMOTE_TIMEOUT} 字面串。
     */
    private static String collectAllTextForTimeoutScan(A2aEventCollector collector) {
        StringBuilder sb = new StringBuilder();
        for (ClientEvent e : collector.snapshotAllEvents()) {
            appendTaskStatusText(sb, e);
        }
        sb.append('\n').append(collector.collectArtifactText());
        return sb.toString();
    }

    private static void appendTaskStatusText(StringBuilder sb, ClientEvent event) {
        Task task = null;
        if (event instanceof TaskEvent te) task = te.getTask();
        if (event instanceof TaskUpdateEvent tue) {
            task = tue.getTask();
            UpdateEvent u = tue.getUpdateEvent();
            if (u instanceof TaskStatusUpdateEvent tsu && tsu.status() != null
                    && tsu.status().message() != null) {
                for (Part<?> p : tsu.status().message().parts()) {
                    if (p instanceof TextPart tp && tp.text() != null) {
                        sb.append(tp.text()).append('\n');
                    }
                }
            }
        }
        if (task != null && task.status() != null && task.status().message() != null) {
            for (Part<?> p : task.status().message().parts()) {
                if (p instanceof TextPart tp && tp.text() != null) {
                    sb.append(tp.text()).append('\n');
                }
            }
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}

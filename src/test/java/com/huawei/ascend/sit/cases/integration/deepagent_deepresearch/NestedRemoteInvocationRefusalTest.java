package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * FEAT-004.nested-remote-invocation-refused — 单个父 Task 生命周期内出现两次远程调用时,
 * agent-runtime 应在第二次远程 tool call 起飞前把父 Task 收束为 {@code FAILED},
 * 并在 payload 中携带字面串 {@code NESTED_REMOTE_INVOCATION_UNSUPPORTED}。
 *
 * <p><b>Spec 依据</b>(primary source 已 verbatim 核对):
 * <ul>
 *   <li><b>L2 §3 能力汇总表</b>(spring-ai-ascend/architecture/docs/L2/agent-runtime/
 *       remote-agent-orchestration-design.md line 53):「嵌套远程调用 ⬜ resume 后再次请求远程
 *       → 返回错误 NESTED_REMOTE_INVOCATION_UNSUPPORTED」。<b>注意:L2 明标 ⬜ 未实现</b>。</li>
 *   <li><b>L2 §4.2 结束条件</b>(同档 line 267-270):「parent task 的最终结束由本地 OpenJiuwen resume
 *       后的结果决定:{@code result_type=interrupt (REMOTE_AGENT_INVOCATION)} → 嵌套调用 →
 *       FAILED (NESTED_REMOTE_INVOCATION_UNSUPPORTED)」。</li>
 *   <li><b>L2 §4 显式禁止</b>(同档 line 99):「禁止:嵌套远程调用(resume 后再次请求远程 →
 *       返回 NESTED_REMOTE_INVOCATION_UNSUPPORTED)」。</li>
 *   <li><b>L2 §5.3 错误分支</b>(同档 line 388):「嵌套远程调用 | resume 后 LLM 再次请求远程 |
 *       返回 NESTED_REMOTE_INVOCATION_UNSUPPORTED | parent task FAILED |」。</li>
 * </ul>
 *
 * <p><b>本用例定性</b>:<b>spec-⬜ watchdog</b>(<em>非 bug 报告</em>)。
 * L2 line 53 能力总表内 「嵌套远程调用」明标 ⬜ (未实现) —— 与 REMOTE_TIMEOUT(L2 line 52 ✅ 已实现,
 * 未落等价于 SUT bug,见 BUG-004)在**根本上不同**:嵌套禁止是 spec 自己承认尚未落地的能力,
 * 全代码库 grep {@code NESTED_REMOTE_INVOCATION_UNSUPPORTED} 常量<b>零命中</b>
 * (spring-ai-ascend agent-runtime 也没有,openjiuwen SUT 也没有)。因此本用例首次跑必然
 * <b>红</b>,不作 bug 记录,而是作为**特性未落地的活体观察点**:待
 * <ol>
 *   <li>L2 能力总表把「嵌套远程调用」从 ⬜ 升级到 ✅;</li>
 *   <li>SUT 侧实现 resume-back 嵌套检测并投射 {@code NESTED_REMOTE_INVOCATION_UNSUPPORTED} 常量;</li>
 * </ol>
 * 之后本用例自动转绿。
 *
 * <p><b>拓扑</b>:框架管理 deep-research + search 两 agent (同 DownstreamAgentKilledMidStreamTest);
 * 通过强 prompt 让 planner 在同一父 Task 生命周期内触发两次 {@code web_search} 远程 tool call。
 * <pre>
 *   [test] --SendMessage("查A, 然后查B")--> [deep-research :SutStack]
 *                                                │
 *                       ┌────────tool_call(web_search "A")─────►[search :SutStack]
 *                       │◄──────────result A─────────┐(remote leg 1 COMPLETED,resume 父)
 *                       │
 *                       └────────tool_call(web_search "B")─────► ??
 *                                                                (spec 期望:被 SUT 侧拦截,
 *                                                                 parent FAILED w/ NESTED constant)
 * </pre>
 *
 * <p><b>断言层次</b>:
 * <ol>
 *   <li><b>层 2(前置一致性)</b>:任务应在 {@link #SEND_TIMEOUT_MS} 内到达某终态(即 planner 至少
 *       启动了调用链;未终态说明整个 SUT hang,与嵌套无关)。</li>
 *   <li><b>层 1(核心 spec)</b>:任一 client event(status message parts / artifact text)内
 *       出现字面串 {@code "NESTED_REMOTE_INVOCATION_UNSUPPORTED"} —— 直接证据 SUT 按 L2 落
 *       该稳定码。<b>预期首次红</b>(spec-⬜ 定性)。</li>
 *   <li><b>层 3(终态类型)</b>:终态应为 {@code FAILED}(spec §5.3 明确「parent task FAILED」)。
 *       <b>预期首次红</b>(当前 SUT 会走 COMPLETED —— LLM 静默完成两次搜索,spec 未实现)。</li>
 * </ol>
 *
 * <p><b>INCONCLUSIVE 情形</b>:本用例的一个已知非确定性来自 LLM —— 若 planner 决定把两个查询
 * <em>合并成一次</em> web_search 调用,或干脆不再触发第二次远程,那么"嵌套"根本不发生,本用例无法
 * 观察到 spec 的触发条件,输出上无 {@code NESTED_REMOTE_INVOCATION_UNSUPPORTED} 但也不算 SUT
 * 反悔。此形态在诊断信息里通过 {@code haystack} + terminal state 组合可读:若终态 COMPLETED 且文本内
 * 两个查询主题都出现,可能是 LLM 合并调用(INCONCLUSIVE);若终态 COMPLETED 但只有一个查询主题出现,
 * 更接近"LLM 只调用了一次"(INCONCLUSIVE)。当前不加机器可读的 INCONCLUSIVE 分支,依赖诊断消息人肉读。
 *
 * <p><b>Tag 说明</b>:
 * <ul>
 *   <li>{@code manual} —— 需本地 deep-research + search 两 jar;CI 未内置。</li>
 *   <li>{@code slow} —— 两次远程搜索 + LLM 汇总,单跑约 60-120s。</li>
 * </ul>
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-004")
@Tag("manual")
@Tag("slow")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NestedRemoteInvocationRefusalTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final String SEARCH = "search";

    /** 用例整体超时:两次真远程搜索 + 中间 LLM 决策 + 收束。 */
    private static final long SEND_TIMEOUT_MS = 240_000;

    /** L2 §4.2/§5.3 定义的稳定码;观察点核心字面串。 */
    private static final String EXPECTED_ERROR_CODE = "NESTED_REMOTE_INVOCATION_UNSUPPORTED";

    /**
     * 强 prompt —— 明确要求"分两次调用 web_search",并禁止合并、禁止用记忆回答。目的是最大化
     * LLM 产出"两次连续远程 tool call"的概率,从而触发 L2 §4.2 line 269 的嵌套判定路径。
     * INCONCLUSIVE 情形(LLM 仍然合并 / 只调一次)由诊断消息暴露,不作机器分支。
     */
    private static final String USER_INPUT =
            "请你严格分两次调用 web_search 工具帮我查两个独立问题:"
            + "第一次搜索 'DeepSeek-R1 官方定价',第二次搜索 'DeepSeek-V3 官方定价'。"
            + "两次必须都调用 web_search 工具,不允许把两个查询合并成一次调用,"
            + "也不允许凭记忆或已有知识自己回答。两次都完成之后再综合汇总。";

    private TestConfig config;
    private SutStack searchStack;
    private SutStack deepStack;

    @BeforeAll
    void startStack() {
        config = TestConfig.load();
        // 与 DownstreamAgentKilledMidStreamTest 同款启动序:先启 search 拿到其 baseUrl,
        // 再 build deep-research 的 stack 并把 SEARCH_AGENT_URL env 塞进去。
        searchStack = SutStack.builder(config).agent(SEARCH).start();
        String searchBaseUrl = searchStack.baseUrl(SEARCH);
        deepStack = SutStack.builder(config)
                .agent(DEEP_RESEARCH, a -> a.env("SEARCH_AGENT_URL", searchBaseUrl))
                .start();
    }

    @AfterAll
    void tearDown() {
        // 反向拆:先关 deep-research,再关 search(避免 upstream 还在跑时下游先没)。
        if (deepStack != null) {
            deepStack.close();
        }
        if (searchStack != null) {
            searchStack.close();
        }
    }

    @Test
    @DisplayName("FEAT-004.nested-remote-invocation-refused: 单 task 内二次远程调用 → 父 Task FAILED "
            + "含 NESTED_REMOTE_INVOCATION_UNSUPPORTED (spec-⬜ watchdog)")
    void nestedRemoteInvocationShouldBeRefused() {
        A2aServiceClient a2a = deepStack.client(DEEP_RESEARCH);

        String contextId = "ctx-feat004-nested-refused-" + UUID.randomUUID().toString().substring(0, 8);
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
            String msg = "FEAT-004.nested-remote-invocation-refused: awaitTerminalState 纯超时 —— "
                    + "SUT 未在 " + SEND_TIMEOUT_MS + "ms 内收束"
                    + "(与嵌套无关的更基础问题:planner 甚至没把调用链跑到终态)\n"
                    + "  contextId=" + contextId
                    + "\n  elapsedMs=" + elapsed;
            Throwable err = streamError.get();
            if (err != null) {
                fail(msg + "\n  stream.error 已挂:", err);
            }
            fail(msg, timeout);
            return;
        }
        long elapsedMs = System.currentTimeMillis() - sendStartMs;

        String haystack = collectAllTextForConstantScan(collector);

        // 层 1(核心 spec):任一 client event 内出现字面串 NESTED_REMOTE_INVOCATION_UNSUPPORTED
        //
        // 未命中的两个原因需要通过 terminalState + haystack 组合读:
        //   A. 终态 COMPLETED + haystack 含两个查询主题 → LLM 合并 / 只调一次,嵌套未发生
        //      (INCONCLUSIVE,非 SUT 反悔;调整 prompt 或换用 mock 反射路径)
        //   B. 终态 COMPLETED + 无 NESTED 常量 → LLM 真发起两次远程,SUT 静默通过嵌套
        //      (spec 违反最严重形态,当前 spec-⬜ 预期形态之一)
        //   C. 终态 FAILED + 无 NESTED 常量 → SUT 收束到 FAILED 但缺常量(信息面丢失,
        //      spec ✅ 未来若实现但缺常量应告警)
        assertThat(haystack)
                .as("FEAT-004.nested-remote-invocation-refused [层1 核心 spec L2 §4.2 line 269 / §5.3 line 388]: "
                        + "同一父 Task 生命周期内 LLM 发起第二次远程 tool call 时,SUT 应按 spec 落 "
                        + "%s 稳定码并把父 Task 收束为 FAILED。观察面:任一 client event 的 "
                        + "status.message.parts / artifact TextPart 文本内应出现字面串 \"%s\"。\n"
                        + "  未命中 → SUT 未落该常量(spec-⬜ 明标未实现;首次预期红)\n"
                        + "  contextId=%s\n  elapsedMs=%d\n  terminalState=%s\n"
                        + "  stream.error=%s\n  haystack(head)=%s",
                        EXPECTED_ERROR_CODE, EXPECTED_ERROR_CODE,
                        contextId, elapsedMs, terminalState, streamError.get(),
                        truncate(haystack, 1200))
                .contains(EXPECTED_ERROR_CODE);

        // 层 3(终态类型):spec §5.3 明确 parent FAILED;当前 SUT 大概率走 COMPLETED
        assertThat(terminalState)
                .as("FEAT-004.nested-remote-invocation-refused [层3 spec §5.3 line 388]: "
                        + "父 Task 终态应为 FAILED(嵌套调用被拒)。当前 spec-⬜,SUT 大概率静默走 COMPLETED。\n"
                        + "  contextId=%s\n  terminalState=%s\n  haystack(head)=%s",
                        contextId, terminalState, truncate(haystack, 800))
                .isEqualTo(TaskState.TASK_STATE_FAILED);
    }

    /**
     * 把所有 client event 的可读文本汇集成一根大 haystack:
     * <ul>
     *   <li>task-bearing 事件(TaskEvent / TaskStatusUpdate):status.message().parts() 的 TextPart</li>
     *   <li>artifact 事件:collector.collectArtifactText()(已内部合并 chunk)</li>
     * </ul>
     * 用于 {@link String#contains(CharSequence)} 扫 {@code NESTED_REMOTE_INVOCATION_UNSUPPORTED} 字面串。
     */
    private static String collectAllTextForConstantScan(A2aEventCollector collector) {
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

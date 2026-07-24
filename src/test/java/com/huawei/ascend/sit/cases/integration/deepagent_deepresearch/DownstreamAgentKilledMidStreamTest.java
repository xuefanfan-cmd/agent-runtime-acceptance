package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
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
 * FEAT-001.downstream-agent-killed-mid-stream — SSE 过程中下游 agent 被杀,handler 无法完成 task,
 * 必须以 failed 家族终态收束 + 携带结构化错误 payload.
 *
 * <p><b>特性依据</b>(FEAT-001 明文 MUST):
 * <ul>
 *   <li>§5.1.4 —— 当状态进入 final 状态时,当前 message stream 必须关闭;
 *       final 包括 {@code completed/failed/canceled/rejected};</li>
 *   <li>§5.1.6 —— COMPLETED 语义:任务已完成、结果就绪、无进一步动作;下游 agent 中途挂
 *       导致 deep-research 拿不到 search 结果 → 任务未完成 → 不得走 COMPLETED;</li>
 *   <li>§5.1.8 —— handler/runtime exception 必须形成 A2A failed Task 或 JSON-RPC internal error,
 *       可形成 Task 的路径应携带<b>结构化错误 payload</b>;下游 A2A 依赖不可达属 handler 运行时异常。</li>
 * </ul>
 *
 * <p><b>断言层次</b>(严格按 spec MUST):
 * <ol>
 *   <li><b>层 1</b>: stream 终态 ∈ {FAILED, CANCELED, REJECTED} —— 直接落 §5.1.4 + §5.1.6 + §5.1.8;
 *       COMPLETED 视为 <b>FAIL</b>(agent 无法完整回答用户,却把任务包装成成功,违反 spec)。</li>
 *   <li><b>层 2</b>: 终态 Task 携带结构化错误 payload({@code status.message.parts} 非空)—— 直接落 §5.1.8。</li>
 * </ol>
 * <p><b>不断言</b>:具体 {@code error.code} 值(评审 §6 未列)、错误消息具体措辞、L2 §5.3 实现事实。
 *
 * <p><b>与 {@code NonexistentToolRefusalTest} 的边界</b>:姐妹用例走"用户请求虚构工具 → LLM 拒答 →
 * COMPLETED"的业务层路径(§5.1.6 正例);本用例走"真实下游 A2A agent 中途挂 → handler runtime
 * exception → failed 家族"的运行时错误路径(§5.1.4 + §5.1.8)。两者合起来完整刻画 FEAT-001 对
 * "工具不可达/不存在"的错误面语义。
 *
 * <p><b>触发机制</b>:框架管理 deep-research + search 两 agent,test 内 {@code sendMessage} 一个
 * 明确需要 search 的 prompt,待 WORKING 状态出现后调 {@link SutStack#stop(String)} 立即杀掉
 * search 进程;deep-research 尝试调 search 时应遭遇 connection refused → 走 §5.1.8 路径。
 *
 * <p><b>Tag 说明</b>:标 {@code manual} —— 需要本地就绪 deep-research + search 两 jar
 * ({@code ~/.m2/repository/com/openjiuwen/example/}),CI 环境默认不具备;jar 就绪后可移除 manual
 * tag 让本用例进 CI。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Tag("manual")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.downstream-killed-mid-stream: §5.1.4 下游被杀 → failed 家族收束")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DownstreamAgentKilledMidStreamTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final String SEARCH = "search";
    private static final long SEND_TIMEOUT_MS = 240_000;
    /** SUBMITTED → WORKING 一般在数秒内完成;30s 足够容纳 SUT 冷启动 + LLM 首帧延迟。 */
    private static final long WORKING_TIMEOUT_MS = 30_000;
    /**
     * 观测到 WORKING 后再等一小段,让 planner 有机会真正把请求打给 search;之后才 stop —— 避免
     * 在 tool call 尚未发起前就杀 search 使得 deep-research 根本不需要 search 就走完。
     */
    private static final long POST_WORKING_GRACE_MS = 3_000;

    /**
     * 明确需要 search 才能回答的 prompt —— 强绑一个专有名 + 数字型问题,推动 planner 触发
     * search tool call;避免 LLM 凭记忆瞎答绕过 search。
     */
    private static final String USER_INPUT =
            "帮我搜索 2026 年 7 月 15 日全球黄金价格盘中最高价的准确数字,直接给出数字和单位";

    private TestConfig config;
    private SutStack searchStack;
    private SutStack deepStack;

    @BeforeAll
    void startStack() {
        config = TestConfig.load();
        // 分两个 SutStack 而非单栈:deep-research 通过 SEARCH_AGENT_URL 环境变量寻址 search,
        // 需先启动 search 拿到其解析后的 baseUrl,再 build deep-research 的 stack 并把 env 塞进去。
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
    @DisplayName("FEAT-001.downstream-agent-killed-mid-stream: search 中途被杀,deep-research 应以 failed 家族收束 + 携带结构化 payload")
    void searchKilledMidStreamShouldNotWalkToCompleted() throws InterruptedException {
        A2aServiceClient a2a = deepStack.client(DEEP_RESEARCH);

        String contextId = "ctx-feat001-downstream-killed-" + UUID.randomUUID().toString().substring(0, 8);
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

        a2a.sendMessage(message, consumers, errorHandler);

        // 等 deep-research 进入 WORKING(planner 已经开始工作),之后再等一小段窗口让 tool call 真的打给 search。
        try {
            collector.awaitAnyTaskState(WORKING_TIMEOUT_MS);
        } catch (ConditionTimeoutException timeout) {
            fail("FEAT-001.downstream-agent-killed-mid-stream: WORKING_TIMEOUT_MS 内未观测到任何 task 状态 —— "
                    + "deep-research 未启动 planner,故障注入前置条件不满足。\n  contextId=" + contextId, timeout);
            return;
        }
        Thread.sleep(POST_WORKING_GRACE_MS);

        // 故障注入:此时 deep-research 应已开始或即将调 search;杀 search 后其后续 A2A 调用必失败。
        assertThat(searchStack.isRunning(SEARCH))
                .as("FEAT-001.downstream-agent-killed-mid-stream [前置]: 故障注入前 search 应仍在运行")
                .isTrue();
        searchStack.stop(SEARCH);
        assertThat(searchStack.isRunning(SEARCH))
                .as("FEAT-001.downstream-agent-killed-mid-stream [前置]: stop() 后 search 应已死")
                .isFalse();

        TaskState terminalState;
        try {
            terminalState = collector.awaitTerminalState(SEND_TIMEOUT_MS);
        } catch (ConditionTimeoutException timeout) {
            Throwable err = streamError.get();
            if (err != null) {
                fail("FEAT-001.downstream-agent-killed-mid-stream: 超时未观测到终态,但 stream 有异常 — contextId="
                        + contextId, err);
            }
            fail("FEAT-001.downstream-agent-killed-mid-stream: 超时未观测到终态 —— stream 未按 §5.1.4 收束 — contextId="
                    + contextId, timeout);
            return;
        }

        // 层 1(§5.1.4 + §5.1.6 + §5.1.8):终态 ∈ {FAILED, CANCELED, REJECTED} —— 特别是 !=COMPLETED
        assertThat(terminalState)
                .as("FEAT-001.downstream-agent-killed-mid-stream [层1]: search 中途被杀后 deep-research 无法完整回答,"
                        + "必须以 failed/canceled/rejected 收束,不得包装成 COMPLETED\n"
                        + "  contextId=%s\n  stream.error=%s", contextId, streamError.get())
                .isIn(TaskState.TASK_STATE_FAILED,
                        TaskState.TASK_STATE_CANCELED,
                        TaskState.TASK_STATE_REJECTED);

        // 层 2(§5.1.8):终态 Task 携带结构化错误 payload
        Task terminalTask = extractTaskFromEvent(collector.findTerminalEvent().orElse(null));
        assertThat(terminalTask)
                .as("FEAT-001.downstream-agent-killed-mid-stream [层2 前置]: 终态事件应携带 Task 对象\n"
                        + "  contextId=%s", contextId)
                .isNotNull();

        Message statusMessage = terminalTask.status().message();
        assertThat(statusMessage)
                .as("FEAT-001.downstream-agent-killed-mid-stream [层2]: failed 家族 Task 必须携带 status.message "
                        + "承载结构化错误 payload\n"
                        + "  contextId=%s\n  terminalState=%s\n  task.id=%s",
                        contextId, terminalState, terminalTask.id())
                .isNotNull();
        assertThat(statusMessage.parts())
                .as("FEAT-001.downstream-agent-killed-mid-stream [层2]: status.message.parts 应非空,"
                        + "承载 downstream 不可达的结构化描述\n"
                        + "  contextId=%s\n  task.id=%s", contextId, terminalTask.id())
                .isNotNull()
                .isNotEmpty();
    }

    private static Task extractTaskFromEvent(ClientEvent event) {
        if (event instanceof TaskEvent te) {
            return te.getTask();
        }
        if (event instanceof TaskUpdateEvent tue) {
            return tue.getTask();
        }
        return null;
    }
}
package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

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
 * issue-42 layer-1 watchdog —— 远端 SSE 中途异常时,父 Task 不得假报 COMPLETED。
 *
 * <p><b>开发组 2026-07-23 澄清的错误分类模型</b>(三档稳定码):
 * <ul>
 *   <li>{@code REMOTE_TIMEOUT} / {@code REMOTE_STREAM_CLOSED} —— 回灌 LLM 由 LLM 决策终态
 *       (BUG-004 覆盖场景,已修复;见 {@link RemoteStreamTimeoutTest})。</li>
 *   <li>{@code REMOTE_ERROR} —— 连接层失败/远端不可达,<b>直接报 FAILED</b>,携带结构化 payload。
 *       本用例观测这一档。</li>
 * </ul>
 *
 * <p><b>issue-42 修复现状</b>(2026-07-23 实测):
 * <ul>
 *   <li>✅ <b>层 1(本用例)</b>:kill search 后终态 = FAILED(不再假报 COMPLETED)。</li>
 *   <li>❌ <b>层 2(BUG-005)</b>:{@code task.status.message == null} —— 稳定码与结构化 payload
 *       都没有落到 A2A wire,调用方无法 machine-read 错误原因;详见
 *       {@link DownstreamAgentKilledMidStreamTest} + {@code docs/bugs/BUG-005-*.md}。</li>
 * </ul>
 *
 * <p><b>本用例定位</b>:issue-42 <b>层 1 watchdog</b>(<em>期望绿</em>) —— 只断言"不假报 COMPLETED"。
 * 层 2(wire 端 payload 完整性)由 {@link DownstreamAgentKilledMidStreamTest} 单独承载,方便
 * BUG-005 修复前后独立看红/绿。
 *
 * <p><b>触发机制</b>:框架管理 deep-research + search,test 内 {@code sendMessage} 一个明确需要
 * search 的 prompt;等 WORKING 出现且 SSE 流已开始传输(POST_WORKING_GRACE_MS = 5s,比姐妹用例
 * 的 3s 更长,专门为了让 tool call 打出去之后再杀)后调 {@link SutStack#stop(String)} 杀掉 search。
 * deep-research 后续 A2A 调用应遭遇 {@code Connection refused} → {@code REMOTE_ERROR} 分派 → FAILED。
 *
 * <p><b>与 {@link DownstreamAgentKilledMidStreamTest} 的分工</b>:
 * <ul>
 *   <li>{@link DownstreamAgentKilledMidStreamTest}:POST_WORKING_GRACE_MS = 3s,承载 FEAT-001
 *       §5.1.4/§5.1.6/§5.1.8 完整两层断言(终态 + wire 端 payload)。</li>
 *   <li><b>本用例</b>:POST_WORKING_GRACE_MS = 5s(SSE 流已在传输中被打断的场景),只关心层 1 —— 若
 *       BUG-005 后续被修好,层 2 断言集中在姐妹用例演化,本用例保持"issue-42 层 1 watchdog"角色不变。</li>
 * </ul>
 *
 * <p><b>Tag 说明</b>:{@code manual} —— 需本地就绪 deep-research + search 两 jar。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-004")
@Tag("manual")
@Feature("FEAT-004: 任务驱动远程智能体调用")
@Story("issue-42 layer-1 watchdog: 远端 SSE 中途异常应报 FAILED,不假报 COMPLETED")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoteSseAbortFalseCompletedTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final String SEARCH = "search";
    private static final long SEND_TIMEOUT_MS = 180_000;
    private static final long WORKING_TIMEOUT_MS = 30_000;
    /** 5s > 姐妹用例的 3s —— 专为"SSE 流已在传输中"的中途异常场景留时间窗口。 */
    private static final long POST_WORKING_GRACE_MS = 5_000;

    private static final String USER_INPUT =
            "帮我搜索 2026 年 7 月 15 日全球黄金价格盘中最高价的准确数字,直接给出数字和单位";

    private TestConfig config;
    private SutStack searchStack;
    private SutStack deepStack;

    @BeforeAll
    void startStack() {
        config = TestConfig.load();
        searchStack = SutStack.builder(config).agent(SEARCH).start();
        String searchBaseUrl = searchStack.baseUrl(SEARCH);
        deepStack = SutStack.builder(config)
                .agent(DEEP_RESEARCH, a -> a.env("SEARCH_AGENT_URL", searchBaseUrl))
                .start();
    }

    @AfterAll
    void tearDown() {
        if (deepStack != null) {
            deepStack.close();
        }
        if (searchStack != null) {
            searchStack.close();
        }
    }

    @Test
    @DisplayName("issue-42 layer-1: 远端 SSE 中途 abort(kill search)应报 FAILED,不假报 COMPLETED")
    void remoteSseAbortShouldReportFailedNotCompleted() throws InterruptedException {
        A2aServiceClient a2a = deepStack.client(DEEP_RESEARCH);

        String contextId = "ctx-issue42-" + UUID.randomUUID().toString().substring(0, 8);
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

        try {
            collector.awaitAnyTaskState(WORKING_TIMEOUT_MS);
        } catch (ConditionTimeoutException timeout) {
            fail("issue-42 [前置]: WORKING_TIMEOUT_MS 内未观测到任何 task 状态 —— planner 未启动\n"
                    + "  contextId=" + contextId, timeout);
            return;
        }
        Thread.sleep(POST_WORKING_GRACE_MS);

        assertThat(searchStack.isRunning(SEARCH))
                .as("issue-42 [前置]: 故障注入前 search 应仍在运行\n  contextId=%s", contextId)
                .isTrue();
        searchStack.stop(SEARCH);
        assertThat(searchStack.isRunning(SEARCH))
                .as("issue-42 [前置]: stop() 后 search 应已死\n  contextId=%s", contextId)
                .isFalse();

        TaskState terminalState;
        try {
            terminalState = collector.awaitTerminalState(SEND_TIMEOUT_MS);
        } catch (ConditionTimeoutException timeout) {
            Throwable err = streamError.get();
            if (err != null) {
                fail("issue-42: 超时未观测到终态,但 stream 有异常\n  contextId=" + contextId, err);
            }
            fail("issue-42: 超时未观测到终态 —— 父 Task 未收束\n  contextId=" + contextId, timeout);
            return;
        }

        // 层 1(issue-42 核心):终态不得为 COMPLETED —— kill search 后 deep-research 无法完整
        // 回答用户,不得包装成成功。REMOTE_ERROR 路径按开发组设计应直接报 FAILED。
        assertThat(terminalState)
                .as("issue-42 [层1 核心]: kill search 后终态不得为 COMPLETED\n"
                        + "  【期望】: FAILED(REMOTE_ERROR 路径 → 直接报 FAILED)\n"
                        + "  【实际】: %s\n"
                        + "  【备注】: wire 端 status.message payload 完整性由 "
                        + "DownstreamAgentKilledMidStreamTest 承载(BUG-005)\n"
                        + "  contextId=%s\n  streamError=%s",
                        terminalState, contextId, streamError.get())
                .isNotEqualTo(TaskState.TASK_STATE_COMPLETED);
    }
}

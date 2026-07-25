package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.awaitility.Awaitility;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.TaskTextExtractor;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;

/**
 * DA-02 — deep-research 同步 SendMessage (场景 2).
 *
 * <p>参考 §2 curl：{@code method=SendMessage} 同步一发一收，期望最终 COMPLETED + artifact
 * 非空。走 {@code streaming(false)} 让 SDK client 使用同步 {@code message/send} 路径
 * （与手工脚本一致），而不是 SSE 流式。
 *
 * <h3>A2A 同步语义（服务侧手工 curl 观测）</h3>
 * <ul>
 *   <li>{@code POST /a2a} {@code SendMessage} 同步响应 <b>不是</b> 终态：几百毫秒内返
 *       {@code state=TASK_STATE_WORKING} + 空 artifacts，仅携带 taskId 让客户端后续拉取。</li>
 *   <li>客户端需要用 {@code GetTask(id)} REST 轮询到 {@code state.isFinal()==true} 拿终态。</li>
 * </ul>
 * 所以本用例分两阶段：先等 collector 拿到任何 TaskState 事件抽出 taskId，再 REST 轮询终态。
 *
 * <p><b>Bug 断言（用户明示，§2 手工脚本记录两种变体）</b>：
 * <ul>
 *   <li><b>variant 1</b>：{@code rounds[0].error} 含 "controller task parameter error,
 *       reason: deep_agent_task_1 already exists!" —— 由 doesNotContain 命中。</li>
 *   <li><b>variant 2</b>：task COMPLETED 但 {@code artifacts[0].parts[0].text = ""}，
 *       agent 内部提前终止未产出内容 —— 由 isNotBlank 命中。</li>
 * </ul>
 * 两种变体本 case 都判 FAIL，即使 A2A 层返 COMPLETED。
 *
 * <p>每次跑用 UUID 后缀化 contextId，避免 SUT 内 checkpointer 与前次残留串扰。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Feature("FEAT-001: 标准化智能体服务入口")
@Stories({
        @Story("da.send-message-sync: A2A SendMessage 同步一发一收"),
        @Story("da.task-lifecycle: SUBMITTED→WORKING→COMPLETED 状态收束")
})
class SyncSendMessageTest extends BaseManagedStackTest {

    private static final String DEEP_RESEARCH = "deep-research";
    /** 同步响应超时 —— SUT 一般毫秒级返 working。 */
    private static final long SYNC_ACK_TIMEOUT_MS = 30_000;
    /** REST 轮询终态总超时 —— 长任务包含 LLM + 搜索链路。 */
    private static final long TERMINAL_POLL_TIMEOUT_MS = 240_000;
    private static final long POLL_INTERVAL_MS = 2_000;

    /** 已知 bug 标志串（用户明示：出现即用例 FAIL）。 */
    private static final String BUG_MARKER_TASK_EXISTS = "deep_agent_task_1 already exists";
    private static final String BUG_MARKER_CONTROLLER_ERR = "controller task parameter error";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .streaming(false)
                .agent(DEEP_RESEARCH);
    }

    @Test
    @DisplayName("DA-02: 同步 SendMessage → REST 轮询到 COMPLETED + artifact 非空且不含已知 bug 标志串")
    void syncSendMessageReachesCompletedWithoutKnownBug() {
        A2aServiceClient a2a = client(DEEP_RESEARCH);

        String runSuffix = "-" + UUID.randomUUID().toString().substring(0, 8);
        String contextId = "ctx-da02-sync" + runSuffix;
        String userInput = "你好,到deepseek官网查询下DeepSeek-V3 上下文长度多少 tokens。";

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

        a2a.sendMessage(message, consumers, errorHandler);

        if (sendError.get() != null) {
            fail("DA-02: message/send 失败", sendError.get());
        }

        // 阶段 1：同步响应（预期 state=WORKING）—— 唯一目的是抽 taskId
        TaskState ackState = collector.awaitAnyTaskState(SYNC_ACK_TIMEOUT_MS);
        assertThat(ackState).as("DA-02.A: 同步 ack 应带任意 TaskState（通常 WORKING）").isNotNull();
        String taskId = collector.findFirstTaskId();
        assertThat(taskId).as("DA-02.A: 同步 ack 中应能抽出 taskId").isNotBlank();
        String ackContextId = collector.findFirstContextId();
        assertThat(ackContextId).as("DA-02.A: 同步 ack 中 contextId 应回显 send 时值")
                .isEqualTo(contextId);

        // 阶段 2：REST 轮询终态
        Task terminalTask = Awaitility.await("terminal task via GetTask")
                .atMost(TERMINAL_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .pollInterval(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .until(() -> {
                    Task t = a2a.getTask(taskId);
                    if (t == null || t.status() == null || t.status().state() == null) {
                        return null;
                    }
                    return t.status().state().isFinal() ? t : null;
                }, task -> task != null);

        TaskState terminalState = terminalTask.status().state();
        assertThat(terminalState).as("DA-02.B: 终态应为 COMPLETED\n任务 id=%s", terminalTask.id())
                .isEqualTo(TaskState.TASK_STATE_COMPLETED);

        assertThat(terminalTask.id()).as("task.id").isNotBlank();
        assertThat(terminalTask.contextId()).as("DA-02.B: task.contextId 应回显 send 时值")
                .isEqualTo(contextId);

        String artifactText = TaskTextExtractor.textOf(terminalTask);
        assertThat(artifactText)
                .as("DA-02.C: artifact 文本非空（bug variant 2：COMPLETED 但 parts.text 空字符串）")
                .isNotBlank();

        // DA-02.D: bug variant 1 —— artifact 里出现 task-1 命名冲突 marker。
        assertThat(artifactText)
                .as("DA-02.D: artifact 不应包含 bug variant 1 标志 '%s'\nartifact 头 300 字: %s",
                        BUG_MARKER_TASK_EXISTS, truncate(artifactText, 300))
                .doesNotContain(BUG_MARKER_TASK_EXISTS)
                .doesNotContain(BUG_MARKER_CONTROLLER_ERR);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
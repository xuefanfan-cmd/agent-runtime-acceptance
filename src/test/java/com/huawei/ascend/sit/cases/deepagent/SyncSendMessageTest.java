package com.huawei.ascend.sit.cases.deepagent;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.TaskTextExtractor;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * DA-02 — deep-research 同步 SendMessage (场景 2).
 *
 * <p>参考 §2 curl：{@code method=SendMessage} 同步一发一收，期望 COMPLETED + artifact
 * 非空。走 {@code streaming(false)} 让 SDK client 使用同步 {@code message/send} 路径
 * （与手工脚本一致），而不是 SSE 流式。
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
class SyncSendMessageTest extends BaseManagedStackTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final long SEND_TIMEOUT_MS = 240_000;

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
    @DisplayName("DA-02: 同步 SendMessage → COMPLETED + artifact 非空且不含已知 bug 标志串")
    void syncSendMessageReachesCompletedWithoutKnownBug() {
        A2aServiceClient a2a = client(DEEP_RESEARCH);

        String runSuffix = "-" + UUID.randomUUID().toString().substring(0, 8);
        String contextId = "ctx-da02-sync" + runSuffix;
        String userInput = "你好,请用一句话介绍你是什么 agent";

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

        TaskState terminalState = collector.awaitTerminalState(SEND_TIMEOUT_MS);
        assertThat(terminalState).as("DA-02: 终态应为 COMPLETED")
                .isEqualTo(TaskState.TASK_STATE_COMPLETED);

        Task task = collector.findTerminalEvent()
                .flatMap(SyncSendMessageTest::taskFrom)
                .orElseThrow(() -> new AssertionError("DA-02: 未拿到终态 task 快照"));
        assertThat(task.id()).as("task.id").isNotBlank();
        assertThat(task.contextId()).as("task.contextId 应回显 send 时值")
                .isEqualTo(contextId);

        String artifactText = TaskTextExtractor.textOf(task);
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

    private static Optional<Task> taskFrom(ClientEvent event) {
        if (event instanceof TaskEvent taskEvent) {
            return Optional.of(taskEvent.getTask());
        }
        if (event instanceof TaskUpdateEvent updateEvent) {
            return Optional.of(updateEvent.getTask());
        }
        return Optional.empty();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
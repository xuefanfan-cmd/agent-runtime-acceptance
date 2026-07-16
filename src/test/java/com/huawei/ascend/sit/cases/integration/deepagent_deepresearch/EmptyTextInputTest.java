package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.TaskTextExtractor;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-001.empty-text-input — 空文本输入应被 runtime 拒绝，不应交给 agent 猜.
 *
 * <p>FEAT-001 §5.1.7「runtime 应拒绝空输入 / 无意义输入，不应交给 agent 猜」。
 * 观测口径：runtime 应通过下列之一表达拒绝：
 * <ol>
 *   <li>send 阶段：SDK 抛出异常 / errorHandler 收到 Throwable（客户端/服务端 shape 校验）；</li>
 *   <li>task 阶段：task 走 {@code FAILED} / {@code REJECTED} 终态；</li>
 *   <li>artifact 阶段：即便 task 到 {@code COMPLETED}，artifact 文本必须为空 —— 不应生成任何 hallucinated 内容。</li>
 * </ol>
 *
 * <p><b>不能走的分支（FAIL）</b>：task {@code COMPLETED} + artifact 非空 —— 说明 runtime 把空输入交给了 LLM，
 * agent 猜了一个回答，这是文档明示禁止的行为。
 *
 * <p><b>Scope 说明</b>：评审关联 §6 —— 具体 JSON-RPC error code 无法断言（version-scope 未固定
 * empty-text 用哪个 code）。本用例只做"拒绝语义"存在性断言，不断言具体码值。
 *
 * <p>与 {@link SyncSendMessageTest} 用同一"sync ack + REST 轮询"两阶段模式（sync 走 message/send
 * 拿 taskId → GetTask 轮询终态）。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
class EmptyTextInputTest extends BaseManagedStackTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final long SYNC_ACK_TIMEOUT_MS = 30_000;
    private static final long TERMINAL_POLL_TIMEOUT_MS = 120_000;
    private static final long POLL_INTERVAL_MS = 2_000;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .streaming(false)
                .agent(DEEP_RESEARCH);
    }

    @Test
    @DisplayName("FEAT-001.empty-text-input: 空 TextPart 应触发拒绝语义（send 异常 / FAILED / REJECTED / 空 artifact）")
    void emptyTextInputMustBeRejected() {
        A2aServiceClient a2a = client(DEEP_RESEARCH);

        String contextId = "ctx-feat001-empty-" + UUID.randomUUID().toString().substring(0, 8);
        Message message = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .contextId(contextId)
                .parts(List.of(new TextPart("")))
                .build();

        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> sendError = new AtomicReference<>();
        List<BiConsumer<ClientEvent, AgentCard>> consumers = List.of(collector.createConsumer());
        Consumer<Throwable> errorHandler = sendError::set;

        try {
            a2a.sendMessage(message, consumers, errorHandler);
        } catch (RuntimeException ex) {
            // 分支 A：SDK 层同步抛异常 —— 客户端/服务端 shape 校验拒绝空输入，判 PASS
            return;
        }

        // 分支 B：errorHandler 收到 Throwable —— SDK 走异步失败回调
        if (sendError.get() != null) {
            return;
        }

        // 未在 send 阶段拒绝 —— 进入 task 生命周期，走两阶段（sync ack → REST 轮询终态）
        TaskState ackState = collector.awaitAnyTaskState(SYNC_ACK_TIMEOUT_MS);
        if (ackState == null) {
            // 无 sync ack 且无 send 异常 —— runtime 未产出 TaskState，视为拒绝（无 task 被创建）
            return;
        }
        String taskId = collector.findFirstTaskId();
        if (taskId == null || taskId.isBlank()) {
            // 无 taskId 亦视为拒绝（同上）
            return;
        }

        Task terminalTask = Awaitility.await("empty-input terminal via GetTask")
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

        // 分支 C：terminal state 是 FAILED / REJECTED —— 判 PASS
        if (terminalState == TaskState.TASK_STATE_FAILED
                || terminalState == TaskState.TASK_STATE_REJECTED) {
            return;
        }

        // 分支 D：COMPLETED 但 artifact 空 —— 边缘 PASS（runtime 没生成内容，等价于拒绝）
        if (terminalState == TaskState.TASK_STATE_COMPLETED) {
            String artifactText = TaskTextExtractor.textOf(terminalTask);
            assertThat(artifactText)
                    .as("FEAT-001.empty-text-input: task COMPLETED 时 artifact 必须为空 —— "
                            + "否则说明 runtime 把空输入交给了 agent 猜（文档明示禁止）\n"
                            + "artifact 头 300 字: %s\ntaskId=%s\ncontextId=%s",
                            truncate(artifactText, 300), terminalTask.id(), contextId)
                    .isBlank();
            return;
        }

        // 其他终态（例如 CANCELED / AUTH_REQUIRED / INPUT_REQUIRED）—— 不属于拒绝语义中的合法分支
        throw new AssertionError(
                "FEAT-001.empty-text-input: 意外终态 " + terminalState
                        + "（合法分支：send 异常 / FAILED / REJECTED / COMPLETED+空 artifact）"
                        + "\ntaskId=" + terminalTask.id() + " contextId=" + contextId);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
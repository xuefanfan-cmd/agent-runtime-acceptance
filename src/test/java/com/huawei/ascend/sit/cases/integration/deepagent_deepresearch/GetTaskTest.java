package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.TaskTextExtractor;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.A2AClientException;
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
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.fail;

/**
 * DA-04 — deep-research GetTask 查询已完成任务 (场景 4).
 *
 * <p>参考 §4 curl：send → 拿 taskId → GetTask → 断言 id / contextId / state / artifact 文本
 * 与 send 侧终态快照一致。走同步 {@code streaming(false)} 与 §2 手工脚本口径保持一致；
 * 也复用 travel 侧 {@link com.huawei.ascend.sit.cases.component.protocol.AgentTaskGetTest}
 * 的骨架。
 *
 * <p><b>Bug 断言</b>：与 DA-02/DA-03 相同，artifact 中含 bug 标志串即 FAIL。
 *
 * <p><b>负路径</b>：{@link #getTaskWithNonExistentIdShouldReturnProtocolError()} —— §4 手工脚本
 * 记录了另一 bug：查询不存在的 taskId 时，SUT 抛 HTTP 500 + `A2AClientException` 堆栈到客户端，
 * 而不是走 JSON-RPC 协议错误路径。期望 SDK 抛 {@link A2AClientException}（即服务端返回了
 * 结构化协议错误 -32001）；若抛的是 HTTP 层通用异常，说明 SUT 复现该 bug。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Feature("FEAT-001: 标准化智能体服务入口")
class GetTaskTest extends BaseManagedStackTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final long SEND_TIMEOUT_MS = 240_000;

    private static final String BUG_MARKER_TASK_EXISTS = "deep_agent_task_1 already exists";
    private static final String BUG_MARKER_CONTROLLER_ERR = "controller task parameter error";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .streaming(false)
                .agent(DEEP_RESEARCH);
    }

    @Test
    @DisplayName("DA-04: send → getTask 快照 id / state / artifact 一致且无 bug")
    @Story("da.get-task: GetTask 快照与 send 侧终态一致")
    void getTaskMatchesSendSnapshotAfterCompleted() {
        A2aServiceClient a2a = client(DEEP_RESEARCH);

        String runSuffix = "-" + UUID.randomUUID().toString().substring(0, 8);
        String contextId = "ctx-da04-getTask" + runSuffix;
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
            fail("DA-04: message/send 失败", sendError.get());
        }

        TaskState terminalState = collector.awaitTerminalState(SEND_TIMEOUT_MS);
        assertThat(terminalState).as("DA-04: send 侧终态应为 COMPLETED")
                .isEqualTo(TaskState.TASK_STATE_COMPLETED);

        Task sendTask = collector.findTerminalEvent()
                .flatMap(GetTaskTest::taskFrom)
                .orElseThrow(() -> new AssertionError("DA-04: send 未产生终态 task 快照"));

        String taskId = sendTask.id();
        assertThat(taskId).as("send 侧 taskId").isNotBlank();

        String sendText = TaskTextExtractor.textOf(sendTask);
        assertThat(sendText).as("send 侧 artifact 文本").isNotBlank();

        // 独立发一次 GetTask
        Task queried = a2a.getTask(taskId);
        assertThat(queried).as("DA-04: getTask 结果").isNotNull();
        assertThat(queried.id()).as("get task.id").isEqualTo(taskId);
        assertThat(queried.contextId()).as("get task.contextId").isEqualTo(contextId);
        assertThat(queried.status().state())
                .as("get task.state").isEqualTo(TaskState.TASK_STATE_COMPLETED);

        String getText = TaskTextExtractor.textOf(queried);
        assertThat(getText).as("get 侧 artifact 文本").isNotBlank();
        assertThat(getText).as("DA-04: send / get artifact 文本应一致")
                .isEqualTo(sendText);

        // bug 断言 —— send 侧或 get 侧任一命中即 FAIL
        assertThat(sendText)
                .as("DA-04: send artifact 不应包含 bug 标志\nhead: %s", truncate(sendText, 300))
                .doesNotContain(BUG_MARKER_TASK_EXISTS)
                .doesNotContain(BUG_MARKER_CONTROLLER_ERR);
        assertThat(getText)
                .as("DA-04: get artifact 不应包含 bug 标志\nhead: %s", truncate(getText, 300))
                .doesNotContain(BUG_MARKER_TASK_EXISTS)
                .doesNotContain(BUG_MARKER_CONTROLLER_ERR);
    }

    @Test
    @DisplayName("DA-04.F: getTask(<不存在 id>) 应走 JSON-RPC 协议错误路径（unmarshalResponse 抛 A2AClientException + 'Task not found'），而非 HTTP 500 泄漏")
    @Story("da.get-task-notfound: 不存在 taskId 走 -32001 协议错误(而非 HTTP 500)")
    void getTaskWithUnknownIdReturnsJsonRpcProtocolError() {
        A2aServiceClient a2a = client(DEEP_RESEARCH);

        String fakeTaskId = UUID.randomUUID().toString();

        Throwable thrown = catchThrowable(() -> a2a.getTask(fakeTaskId));

        assertThat(thrown)
                .as("DA-04.F: 查询不存在的 taskId=%s 应抛 A2AClientException（SDK 1.0.0.Final 不按 code 分流具体子类）",
                        fakeTaskId)
                .isInstanceOf(A2AClientException.class)
                .hasMessageContaining("Task not found");

        // 抛点识别：unmarshalResponse = 服务端返回了规范 JSON-RPC 错误 body（PASS 路径）；
        // sendPostRequest = 服务端把异常泄漏成 HTTP 500（§4 bug 复现，FAIL）。
        String topFrameMethod = thrown.getStackTrace().length == 0
                ? ""
                : thrown.getStackTrace()[0].getMethodName();
        assertThat(topFrameMethod)
                .as("DA-04.F: 异常应从 JSONRPCTransport.unmarshalResponse 抛出（服务端返回规范 JSON-RPC 错误 body）；"
                        + "若抛点是 sendPostRequest，说明 SUT 复现 §4 bug —— 未捕获异常导致 HTTP 500 泄漏。")
                .isEqualTo("unmarshalResponse");
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
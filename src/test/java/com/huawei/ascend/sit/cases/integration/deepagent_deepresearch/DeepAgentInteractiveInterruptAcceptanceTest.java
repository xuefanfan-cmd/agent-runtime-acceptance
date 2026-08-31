package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.TaskTextExtractor;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfig;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.A2AClientException;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * FEAT-008 black-box acceptance against an externally supplied multi-agent DeepAgent stack.
 *
 * <p>The stack root is supplied through {@code BANK_INTENT_DEMO_ROOT} or
 * {@code -Dbankintent.demo.root=...}. The test owns only launched processes and a local
 * reranker process; it observes the public A2A surface and does not inspect agent internals.</p>
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-008")
@Tag("blackbox")
@Feature("FEAT-008: 运行时用户交互式任务中断与请求响应")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeepAgentInteractiveInterruptAcceptanceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long ROUND_TIMEOUT_MS = 240_000L;
    private static final long HEALTH_TIMEOUT_MS = 180_000L;
    private static final String INTENT = "intent";
    private static final String TRANSFER = "transfer";
    private static final String WEALTH_PURCHASE = "wealth-purchase";

    private BankDemoStack stack;

    @BeforeAll
    void startStack() {
        String configuredRoot = System.getProperty("bankintent.demo.root",
                System.getenv("BANK_INTENT_DEMO_ROOT"));
        Assumptions.assumeTrue(configuredRoot != null && !configuredRoot.isBlank(),
                "BANK_INTENT_DEMO_ROOT or -Dbankintent.demo.root must point to the DeepAgent stack");
        stack = new BankDemoStack(Path.of(configuredRoot));
        stack.start();
    }

    @AfterAll
    void stopStack() {
        if (stack != null) {
            stack.close();
        }
    }

    @Test
    @Story("FEAT-008.remote-interrupt.resume: 远端中断投影后同 Task 续接")
    @DisplayName("FEAT-008.remote-interrupt.resume: 远端 INPUT_REQUIRED 后同 Task 多轮续接")
    void remoteInterruptProjectsAndResumesSameTask() {
        A2aServiceClient client = stack.client(INTENT);
        String contextId = context("remote");

        Round first = send(client, contextId, null, "我要转账");
        assertInputRequired(first);
        Round second = send(client, contextId, first.taskId(), "收款人是李四");
        assertInputRequired(second);
        assertSameTask(first, second);
        Round third = send(client, contextId, first.taskId(), "金额是200元");
        assertInputRequired(third);
        assertSameTask(first, third);
        Round completed = send(client, contextId, first.taskId(), "确认");

        assertThat(completed.state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertSameTask(first, completed);
    }

    @Test
    @Story("FEAT-008.same-task.semantic-mismatch: 业务语义由智能体判断")
    @DisplayName("FEAT-008.same-task.semantic-mismatch: 同 Task 不匹配输入仍交回智能体")
    void semanticMismatchStillReachesOriginalAgent() {
        A2aServiceClient client = stack.client(INTENT);
        String contextId = context("semantic-mismatch");

        Round first = send(client, contextId, null, "给王五转50元");
        assertInputRequired(first);
        Round changed = send(client, contextId, first.taskId(), "改为购买1000元稳盈90天理财");

        assertThat(changed.state()).isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);
        assertSameTask(first, changed);
        assertThat(changed.text()).contains("稳盈90天");
        assertThat(changed.text()).contains("1000");

        Round completed = send(client, contextId, first.taskId(), "确认");
        assertThat(completed.state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertSameTask(first, completed);
    }

    @Test
    @Story("FEAT-008.non-current-task.isolation: 非同 Task 请求隔离")
    @DisplayName("FEAT-008.non-current-task.isolation: 新 Task 不抢占旧等待")
    void newTaskDoesNotStealWaitingTask() {
        A2aServiceClient client = stack.client(INTENT);
        String waitingContext = context("waiting");
        Round waiting = send(client, waitingContext, null, "我要转账");
        assertInputRequired(waiting);

        String newContext = context("new-task");
        Round newTask = send(client, newContext, null, "推荐一款稳健的三个月理财");
        assertThat(newTask.state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertThat(newTask.taskId()).isNotEqualTo(waiting.taskId());
        assertThat(newTask.contextId()).isEqualTo(newContext);

        Task oldSnapshot = client.getTask(waiting.taskId());
        assertThat(oldSnapshot).isNotNull();
        assertThat(oldSnapshot.status().state()).isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);
        assertThat(oldSnapshot.contextId()).isEqualTo(waitingContext);
    }

    @Test
    @Story("FEAT-008.waiting.get-task: 等待期间查询与续接")
    @DisplayName("FEAT-008.waiting.get-task: GetTask 观察 INPUT_REQUIRED 后续接")
    void getTaskObservesWaitingAndResume() {
        A2aServiceClient client = stack.client(INTENT);
        String contextId = context("get-task");
        Round waiting = send(client, contextId, null, "我要转账");
        assertInputRequired(waiting);

        Task snapshot = client.getTask(waiting.taskId());
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.id()).isEqualTo(waiting.taskId());
        assertThat(snapshot.contextId()).isEqualTo(contextId);
        assertThat(snapshot.status().state()).isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);

        Round second = send(client, contextId, waiting.taskId(), "收款人是李四");
        assertInputRequired(second);
        assertSameTask(waiting, second);
        Round completed = send(client, contextId, waiting.taskId(), "金额是200元");
        assertInputRequired(completed);
        Round finalRound = send(client, contextId, waiting.taskId(), "确认");
        assertThat(finalRound.state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertSameTask(waiting, finalRound);
    }

    @Test
    @Story("FEAT-008.waiting.subscription: 等待期间订阅和流结束语义")
    @DisplayName("FEAT-008.waiting.subscription: interrupted stream 暴露 INPUT_REQUIRED")
    void waitingStreamExposesInterruptedSemantics() {
        A2aServiceClient client = stack.client(INTENT);
        SendOutcome waiting = sendOutcome(client, context("subscription"), null, "我要转账");

        assertThat(waiting.error()).isNull();
        assertThat(waiting.round()).isNotNull();
        assertThat(waiting.round().state()).isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);
        assertThat(waiting.collector().findInputRequiredEvent())
                .as("中断流必须暴露 INPUT_REQUIRED 事件")
                .isPresent();
        assertThat(waiting.collector().findTerminalEvent())
                .as("INPUT_REQUIRED 流不能同时伪装为终态完成")
                .isEmpty();
    }

    @Test
    @Story("FEAT-008.single-wait.idempotency: 单等待点只推进一次")
    @DisplayName("FEAT-008.single-wait.idempotency: 重复续接不重复执行副作用")
    void singleWaitPointAdvancesOnce() {
        A2aServiceClient client = stack.client(TRANSFER);
        String contextId = context("idempotency");
        int before = stack.executionCount(TRANSFER, "execute_transfer");

        Round first = send(client, contextId, null, "我要转账");
        Round second = send(client, contextId, first.taskId(), "收款人是张三");
        Round third = send(client, contextId, first.taskId(), "金额是100元");
        assertInputRequired(third);

        Round completed = send(client, contextId, first.taskId(), "确认");
        assertThat(completed.state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
        Awaitility.await("transfer execution log").atMost(10, TimeUnit.SECONDS)
                .until(() -> stack.executionCount(TRANSFER, "execute_transfer") >= before + 1);

        HttpResponse<String> duplicate = stack.postJson(TRANSFER, requestBody(contextId, first.taskId(), "确认"));
        assertThat(stack.executionCount(TRANSFER, "execute_transfer"))
                .as("同一等待点重复续接不得再次执行转账")
                .isEqualTo(before + 1);
        assertThat(duplicate.body().contains("\"error\"")
                || !duplicate.body().contains("TASK_STATE_COMPLETED"))
                .as("终态 Task 的重复续接应进入标准冲突/错误处理")
                .isTrue();
    }

    @Test
    @Story("FEAT-008.long-wait.resume: 长时挂起后续接")
    @DisplayName("FEAT-008.long-wait.resume: 等待窗口内查询后仍可同 Task 续接")
    void longWaitRemainsResumable() {
        A2aServiceClient client = stack.client(TRANSFER);
        String contextId = context("long-wait");
        Round waiting = send(client, contextId, null, "我要转账");
        assertInputRequired(waiting);

        Instant holdUntil = Instant.now().plusSeconds(3);
        Awaitility.await("bounded long wait window").atMost(8, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> Instant.now().isAfter(holdUntil));

        Task snapshot = client.getTask(waiting.taskId());
        assertThat(snapshot.status().state()).isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);
        Round resumed = send(client, contextId, waiting.taskId(), "收款人是张三");
        assertThat(resumed.state()).isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);
        assertSameTask(waiting, resumed);
    }

    @Test
    @Story("FEAT-008.local-interrupt.resume: 本地智能体中断后恢复")
    @DisplayName("FEAT-008.local-interrupt.resume: 直接访问业务 Agent 的本地中断续接")
    void localInterruptResumesSameTask() {
        A2aServiceClient client = stack.client(TRANSFER);
        String contextId = context("local");

        Round first = send(client, contextId, null, "我要转账");
        assertInputRequired(first);
        Round second = send(client, contextId, first.taskId(), "收款人是张三");
        assertInputRequired(second);
        assertSameTask(first, second);
        Round third = send(client, contextId, first.taskId(), "金额是100元");
        assertInputRequired(third);
        assertSameTask(first, third);
        Round completed = send(client, contextId, first.taskId(), "确认");
        assertThat(completed.state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertSameTask(first, completed);
    }

    @Test
    @Story("FEAT-008.multi-round: 同一 Task 多轮 INPUT_REQUIRED")
    @DisplayName("FEAT-008.multi-round: 多次等待与恢复保持 Task 身份")
    void multiRoundKeepsTaskIdentity() {
        A2aServiceClient client = stack.client(WEALTH_PURCHASE);
        String contextId = context("multi-round");

        Round first = send(client, contextId, null, "我要买理财");
        assertInputRequired(first);
        Round second = send(client, contextId, first.taskId(), "产品是稳盈90天");
        assertInputRequired(second);
        assertSameTask(first, second);
        Round third = send(client, contextId, first.taskId(), "金额是1000元");
        assertInputRequired(third);
        assertSameTask(first, third);
        Round completed = send(client, contextId, first.taskId(), "确认");
        assertThat(completed.state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertSameTask(first, completed);
    }

    @Test
    @Story("FEAT-008.current-instance.resume: 当前实例恢复")
    @DisplayName("FEAT-008.current-instance.resume: 当前实例续接不创建替代 Task")
    void currentInstanceResumePreservesTaskIdentity() {
        A2aServiceClient client = stack.client(WEALTH_PURCHASE);
        String contextId = context("current-instance");
        Round waiting = send(client, contextId, null, "购买一万元稳盈90天");
        assertInputRequired(waiting);

        Round completed = send(client, contextId, waiting.taskId(), "确认");
        assertThat(completed.state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertSameTask(waiting, completed);
    }

    @Test
    @Story("FEAT-008.error.task-unavailable: Task 不存在错误表面")
    @DisplayName("FEAT-008.error.task-unavailable: 查询不存在 Task 返回协议错误")
    void unknownTaskReturnsErrorSurface() {
        A2aServiceClient client = stack.client(INTENT);
        String unknownTaskId = "missing-" + UUID.randomUUID();
        assertThatThrownBy(() -> client.getTask(unknownTaskId))
                .as("不存在的 Task 不应创建隐式任务或返回成功快照")
                .isInstanceOf(A2AClientException.class)
                .hasMessageContaining("Task not found");

        Round waiting = send(client, context("inaccessible-task"), null, "我要转账");
        HttpResponse<String> wrongContext = stack.postJson(INTENT,
                requestBody(context("wrong-context"), waiting.taskId(), "确认"));
        assertThat(wrongContext.body().contains("\"error\"")
                || !wrongContext.body().contains("TASK_STATE_COMPLETED"))
                .as("不同 context 不得访问或恢复原 Task")
                .isTrue();
    }

    @Test
    @Story("FEAT-008.error.protocol-format: 续接请求格式非法")
    @DisplayName("FEAT-008.error.protocol-format: 非法 JSON-RPC 请求返回协议错误")
    void malformedResumeReturnsProtocolError() {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":\"feat008-malformed\","
                + "\"method\":\"SendStreamingMessage\",\"params\":{\"message\":{"
                + "\"role\":\"ROLE_USER\",\"parts\":[]}}}";
        HttpResponse<String> response = stack.postJson(INTENT, body);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"error\"").doesNotContain("\"result\"");
    }

    @Test
    @Story("FEAT-008.error.invalid-state: Task 状态不允许续接")
    @DisplayName("FEAT-008.error.invalid-state: 已完成 Task 不重新执行")
    void terminalTaskDoesNotResume() {
        A2aServiceClient client = stack.client(INTENT);
        String contextId = context("terminal-state");
        Round completed = send(client, contextId, null, "推荐一款稳健的三个月理财");
        assertThat(completed.state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);

        HttpResponse<String> resume = stack.postJson(INTENT, requestBody(contextId, completed.taskId(), "确认"));
        assertThat(resume.body().contains("\"error\"")
                || !resume.body().contains("TASK_STATE_COMPLETED"))
                .as("已完成 Task 不得重新进入业务执行")
                .isTrue();
    }

    @Test
    @Story("FEAT-008.error.recovery-context: 恢复上下文不可用")
    @DisplayName("FEAT-008.error.recovery-context: Task 可见但恢复上下文不可用时返回失败")
    void recoveryContextUnavailableReturnsFailureSurface() {
        A2aServiceClient client = stack.client(TRANSFER);
        String contextId = context("recovery-context");
        Round waiting = send(client, contextId, null, "我要转账");
        assertInputRequired(waiting);

        stack.armFault(TRANSFER, contextId, "RECOVERY_CONTEXT_MISSING");
        SendOutcome resumed = sendOutcome(client, contextId, waiting.taskId(), "收款人是张三");

        assertThat(resumed.error()).isNull();
        assertThat(resumed.round()).isNotNull();
        assertThat(resumed.state()).isEqualTo(TaskState.TASK_STATE_FAILED);
        assertSameTask(waiting, resumed.round());
        assertThat(resumed.round().text()).contains("FEAT008_RECOVERY_CONTEXT_UNAVAILABLE");
    }

    @Test
    @Story("FEAT-008.error.local-recovery: 本地执行恢复失败")
    @DisplayName("FEAT-008.error.local-recovery: 本地恢复阶段确定性失败并保留 Task 关联")
    void localRecoveryFailureReturnsFailureSurface() {
        A2aServiceClient client = stack.client(TRANSFER);
        String contextId = context("local-recovery");
        Round waiting = send(client, contextId, null, "我要转账");
        assertInputRequired(waiting);

        stack.armFault(TRANSFER, contextId, "LOCAL_RECOVERY_FAILED");
        SendOutcome resumed = sendOutcome(client, contextId, waiting.taskId(), "收款人是张三");

        assertThat(resumed.error()).isNull();
        assertThat(resumed.round()).isNotNull();
        assertThat(resumed.state()).isEqualTo(TaskState.TASK_STATE_FAILED);
        assertSameTask(waiting, resumed.round());
        assertThat(resumed.round().text()).contains("FEAT008_LOCAL_RECOVERY_FAILED");
    }

    @Test
    @Story("FEAT-008.audit.lifecycle: 中断生命周期公开审计")
    @DisplayName("FEAT-008.audit.lifecycle: 公开审计关联中断续接恢复和完成且不泄露输入")
    void lifecycleAuditCorrelatesInterruptResumeAndCompletion() {
        A2aServiceClient client = stack.client(TRANSFER);
        String contextId = context("audit-lifecycle");
        Round first = send(client, contextId, null, "我要转账");
        assertInputRequired(first);
        Round second = send(client, contextId, first.taskId(), "收款人是张三");
        assertInputRequired(second);
        Round third = send(client, contextId, first.taskId(), "金额是100元");
        assertInputRequired(third);
        Round completed = send(client, contextId, first.taskId(), "确认");
        assertThat(completed.state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertSameTask(first, completed);

        List<JsonNode> events = stack.audit(TRANSFER, contextId);
        assertThat(events).extracting(event -> event.path("event").asText())
                .contains("REQUEST_RECEIVED", "INPUT_REQUIRED", "RESUME_REQUESTED",
                        "RECOVERY_SUCCEEDED", "COMPLETED");
        assertThat(events).allSatisfy(event -> {
            assertThat(event.path("contextId").asText()).isEqualTo(contextId);
            assertThat(event.path("timestamp").asText()).isNotBlank();
            assertThat(event.has("message")).isFalse();
            assertThat(event.has("prompt")).isFalse();
        });
        assertThat(events.stream().map(event -> event.path("taskId").asText())
                .filter(value -> !value.isBlank()).distinct()).containsExactly(first.taskId());
    }

    private static String requestBody(String contextId, String taskId, String text) {
        ObjectNode root = JSON.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("id", "feat008-" + UUID.randomUUID());
        root.put("method", "SendStreamingMessage");
        ObjectNode params = root.putObject("params");
        ObjectNode message = params.putObject("message");
        message.put("role", "ROLE_USER");
        message.put("messageId", "m-" + UUID.randomUUID());
        message.put("contextId", contextId);
        message.put("taskId", taskId);
        ArrayNode parts = message.putArray("parts");
        parts.addObject().put("text", text);
        return root.toString();
    }

    @Test
    @Tag("manual")
    @Story("FEAT-008.error.remote-resume: 远端续接失败")
    @DisplayName("FEAT-008.error.remote-resume: 远端中断后下游不可用呈现为业务失败")
    void remoteResumeFailureReturnsFailureSurface() {
        A2aServiceClient client = stack.client(INTENT);
        String contextId = context("remote-failure");
        Round waiting = send(client, contextId, null, "我要转账");
        assertInputRequired(waiting);
        stack.stop(TRANSFER);
        try {
            SendOutcome resume = sendOutcome(client, contextId, waiting.taskId(), "确认");
            assertThat(resume.error())
                    .as("远端业务失败应被父 Agent 确定性消费，而不是转成未捕获 A2A 异常")
                    .isNull();
            assertThat(resume.round()).isNotNull();
            assertThat(resume.state())
                    .as("父 Task 已处理远端业务失败，可以正常完成，但不得误报业务成功")
                    .isEqualTo(TaskState.TASK_STATE_COMPLETED);
            assertSameTask(waiting, resume.round());
            assertThat(resume.round().text())
                    .as("用户应看到明确的远端不可用提示")
                    .containsAnyOf("失败", "异常", "不可用", "无法")
                    .doesNotContain("转账成功", "购买成功");
        } finally {
            stack.restart(TRANSFER);
        }
    }

    private static Round send(A2aServiceClient client, String contextId, String taskId, String text) {
        SendOutcome outcome = sendOutcome(client, contextId, taskId, text);
        if (outcome.error() != null) {
            fail("A2A 请求失败", outcome.error());
        }
        return outcome.round();
    }

    private static SendOutcome sendOutcome(A2aServiceClient client, String contextId, String taskId, String text) {
        Message.Builder builder = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .contextId(contextId)
                .parts(List.of(new TextPart(text)));
        if (taskId != null) {
            builder.taskId(taskId);
        }
        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> error = new AtomicReference<>();
        List<BiConsumer<ClientEvent, AgentCard>> consumers = List.of(collector.createConsumer());
        Consumer<Throwable> errorHandler = error::set;
        client.sendMessageStreaming(builder.build(), null, null, consumers, errorHandler);
        if (error.get() != null) {
            return new SendOutcome(null, error.get(), null);
        }

        TaskState state = Awaitility.await("A2A task state")
                .atMost(ROUND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> observedState(collector), value -> value != null);
        String observedTaskId = collector.findFirstTaskId();
        assertThat(observedTaskId).as("Task id").isNotBlank();
        Task snapshot = client.getTask(observedTaskId);
        String observedContextId = snapshot == null ? contextId : snapshot.contextId();
        String fullText = snapshot == null ? collector.collectArtifactText() : TaskTextExtractor.textOf(snapshot);
        return new SendOutcome(new Round(observedTaskId, observedContextId, state, fullText), null, collector);
    }

    private static TaskState observedState(A2aEventCollector collector) {
        if (collector.findInputRequiredEvent().isPresent()) {
            return TaskState.TASK_STATE_INPUT_REQUIRED;
        }
        return collector.findTerminalEvent()
                .flatMap(DeepAgentInteractiveInterruptAcceptanceTest::taskState)
                .orElse(null);
    }

    private static java.util.Optional<TaskState> taskState(ClientEvent event) {
        if (event instanceof org.a2aproject.sdk.client.TaskEvent taskEvent) {
            return java.util.Optional.of(taskEvent.getTask().status().state());
        }
        if (event instanceof org.a2aproject.sdk.client.TaskUpdateEvent updateEvent) {
            return java.util.Optional.of(updateEvent.getTask().status().state());
        }
        return java.util.Optional.empty();
    }

    private static void assertInputRequired(Round round) {
        assertThat(round.state()).as("Task 应进入 INPUT_REQUIRED").isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);
    }

    private static void assertSameTask(Round first, Round next) {
        assertThat(next.taskId()).as("续接 taskId 应保持不变").isEqualTo(first.taskId());
        assertThat(next.contextId()).as("续接 contextId 应保持不变").isEqualTo(first.contextId());
    }

    private static String context(String suffix) {
        return "ctx-feat008-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private record Round(String taskId, String contextId, TaskState state, String text) {
    }

    private record SendOutcome(Round round, Throwable error, A2aEventCollector collector) {
        private TaskState state() {
            return round == null ? null : round.state();
        }
    }

    private static final class BankDemoStack implements AutoCloseable {
        private static final Map<String, Service> SERVICES = services();

        private final Path root;
        private final Map<String, Process> processes = new LinkedHashMap<>();
        private Process reranker;

        private BankDemoStack(Path root) {
            this.root = root.toAbsolutePath().normalize();
        }

        private static Map<String, Service> services() {
            Map<String, Service> values = new LinkedHashMap<>();
            values.put(INTENT, new Service("intent-agent-runtime", "bank-intent-agent-runtime-0.1.0.jar", 18200));
            values.put("balance", new Service("balance-agent-runtime", "bank-balance-agent-runtime-0.1.0.jar", 18201));
            values.put(TRANSFER, new Service("transfer-agent-runtime", "bank-transfer-agent-runtime-0.1.0.jar", 18202));
            values.put("wealth-advisor", new Service("wealth-advisor-agent-runtime",
                    "bank-wealth-advisor-agent-runtime-0.1.0.jar", 18203));
            values.put(WEALTH_PURCHASE, new Service("wealth-purchase-agent-runtime",
                    "bank-wealth-purchase-agent-runtime-0.1.0.jar", 18204));
            return values;
        }

        void start() {
            try {
                Path localConfig = root.resolve("application-intent_local.yml");
                Assumptions.assumeTrue(Files.isRegularFile(localConfig),
                        "application-intent_local.yml is required by the supplied stack");
                startRerankerIfNeeded();
                startService("balance");
                startService(TRANSFER);
                startService("wealth-advisor");
                startService(WEALTH_PURCHASE);
                startService(INTENT);
            } catch (IOException exception) {
                throw new IllegalStateException("failed to start DeepAgent stack", exception);
            }
        }

        A2aServiceClient client(String service) {
            String baseUrl = url(service);
            AgentCard card = A2A.getAgentCard(baseUrl);
            assertThat(card).as("Agent Card for " + service).isNotNull();
            ClientConfig config = new ClientConfig.Builder().setAcceptedOutputModes(List.of("text"))
                    .setStreaming(false).build();
            Client sdkClient = Client.builder(card).clientConfig(config)
                    .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig()).build();
            return new A2aServiceClient(baseUrl, sdkClient, card);
        }

        String url(String service) {
            return "http://127.0.0.1:" + require(service).port();
        }

        HttpResponse<String> postJson(String service, String body) {
            return postJson(service, "/a2a", body);
        }

        HttpResponse<String> postJson(String service, String path, String body) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url(service) + path))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException | InterruptedException exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("failed to send raw A2A request", exception);
            }
        }

        void armFault(String service, String contextId, String fault) {
            HttpResponse<String> response = postJson(service, "/__feat008/test/fault",
                    "{\"contextId\":\"" + contextId + "\",\"fault\":\"" + fault + "\"}");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains(fault);
        }

        List<JsonNode> audit(String service, String contextId) {
            AtomicReference<List<JsonNode>> latest = new AtomicReference<>(List.of());
            Awaitility.await("FEAT-008 completion audit visibility").atMost(5, TimeUnit.SECONDS)
                    .pollInterval(100, TimeUnit.MILLISECONDS).until(() -> {
                        latest.set(readAudit(service, contextId));
                        return latest.get().stream()
                                .anyMatch(event -> "COMPLETED".equals(event.path("event").asText()));
                    });
            return latest.get();
        }

        private List<JsonNode> readAudit(String service, String contextId) {
            try {
                HttpRequest request = HttpRequest.newBuilder(
                        URI.create(url(service) + "/__feat008/test/audit?contextId=" + contextId))
                        .timeout(Duration.ofSeconds(10)).GET().build();
                HttpResponse<String> response = HttpClient.newHttpClient()
                        .send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                JsonNode value = JSON.readTree(response.body());
                assertThat(value.isArray()).isTrue();
                List<JsonNode> events = new java.util.ArrayList<>();
                value.forEach(events::add);
                return events;
            } catch (IOException | InterruptedException exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("failed to read FEAT-008 audit events", exception);
            }
        }

        int executionCount(String service, String tool) {
            Path log = root.resolve("target").resolve("feat008-sut-logs").resolve(service + ".log");
            if (!Files.isRegularFile(log)) {
                return 0;
            }
            try {
                return (int) Files.readAllLines(log).stream()
                        .filter(line -> line.contains("BANK_DEMO_EXECUTION tool=" + tool))
                        .count();
            } catch (IOException exception) {
                throw new IllegalStateException("failed to read execution log " + log, exception);
            }
        }

        void stop(String service) {
            Process process = processes.get(service);
            if (process != null) {
                process.destroy();
                waitForExit(process);
                processes.remove(service);
            }
        }

        void restart(String service) {
            try {
                startService(service);
            } catch (IOException exception) {
                throw new IllegalStateException("failed to restart DeepAgent service " + service, exception);
            }
        }

        @Override
        public void close() {
            List<String> order = List.of(INTENT, WEALTH_PURCHASE, "wealth-advisor", TRANSFER, "balance");
            for (String service : order) {
                stop(service);
            }
            if (reranker != null) {
                reranker.destroy();
                waitForExit(reranker);
            }
        }

        private void startService(String name) throws IOException {
            Service definition = require(name);
            Path jar = root.resolve(definition.module()).resolve("target").resolve(definition.jar());
            if (!Files.isRegularFile(jar)) {
                throw new IllegalStateException("missing supplied SUT jar: " + jar);
            }
            Path logDir = root.resolve("target").resolve("feat008-sut-logs");
            Files.createDirectories(logDir);
            ProcessBuilder builder = new ProcessBuilder(javaExecutable(), "-jar", jar.toString(),
                    "--feat008.test.enabled=true");
            builder.directory(root.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(logDir.resolve(name + ".log").toFile());
            Process process = builder.start();
            processes.put(name, process);
            waitForHealth(name);
        }

        private void startRerankerIfNeeded() throws IOException {
            if (isPortOpen(18099)) {
                return;
            }
            String python = System.getenv().getOrDefault("PYTHON", "python3");
            Path script = root.resolve("mock-reranker.py");
            if (!Files.isRegularFile(script)) {
                throw new IllegalStateException("missing reranker fixture: " + script);
            }
            Path log = root.resolve("target").resolve("feat008-reranker.log");
            Files.createDirectories(log.getParent());
            reranker = new ProcessBuilder(python, script.toString()).directory(root.toFile()).redirectErrorStream(true)
                    .redirectOutput(log.toFile()).start();
            Awaitility.await("mock reranker readiness").atMost(30, TimeUnit.SECONDS)
                    .until(() -> isPortOpen(18099));
        }

        private void waitForHealth(String service) {
            Awaitility.await(service + " health").atMost(HEALTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .pollInterval(1, TimeUnit.SECONDS)
                    .until(() -> {
                        try {
                            HttpRequest request = HttpRequest.newBuilder(URI.create(url(service) + "/health"))
                                    .timeout(Duration.ofSeconds(3)).GET().build();
                            HttpResponse<String> response = HttpClient.newHttpClient()
                                    .send(request, HttpResponse.BodyHandlers.ofString());
                            return response.statusCode() == 200 && response.body().contains("\"status\":\"healthy\"");
                        } catch (Exception ignored) {
                            return false;
                        }
                    });
        }

        private static boolean isPortOpen(int port) {
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 300);
                return true;
            } catch (IOException ignored) {
                return false;
            }
        }

        private static void waitForExit(Process process) {
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }

        private static String javaExecutable() {
            String binary = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
            return Path.of(System.getProperty("java.home"), "bin", binary).toString();
        }

        private static Service require(String name) {
            Service service = SERVICES.get(name);
            if (service == null) {
                throw new IllegalArgumentException("unknown bank service: " + name);
            }
            return service;
        }

        private record Service(String module, String jar, int port) {
        }
    }
}

package com.huawei.ascend.sit.cases.integration.edpa;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.A2aStreamErrors;
import com.huawei.ascend.sit.client.TaskTextExtractor;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 已打包并预部署的 {@code edp-agent-java} 理财购买业务流式验收。
 *
 * <p>默认直连 {@code http://127.0.0.1:8190}，并使用本地 AgentEnvExplorer
 * {@code http://127.0.0.1:31113} 初始化张三的理财场景。两个地址分别可用
 * {@code EDP_AGENT_BASE_URL}/{@code -Dedp.agent.base-url} 和
 * {@code LOCAL_VERSATILE_BASE_URL}/{@code -Dlocal.versatile.base-url} 覆盖。
 * 测试不启动或停止 EDP、adapter、Redis 与 AgentEnvExplorer。
 *
 * <p>推荐和购买是同一 conversation 中的两个 A2A Task。每个 Task 内部遇到 Versatile
 * 通用中断时保持 taskId 发送“继续”；真正的 ask_user 中断则依次回答产品、金额和确认。
 */
@Tag("integration")
@Tag("feat-000")
@Feature("FEAT-000: Solution 层 Fat Jar 瘦身")
@Story("FEAT-000.wealth-purchase: 瘦身后的 EDP Agent 理财购买业务闭环")
class EdpAgentWealthWorkflowStreamingTest extends BaseManagedStackTest {

    private static final Logger LOG = Logger.getLogger(EdpAgentWealthWorkflowStreamingTest.class.getName());

    private static final String EDP_AGENT = "edp-agent";
    // WSL acceptance tests reach Windows-hosted services through the WSL gateway.
    private static final String DEFAULT_EDP_BASE_URL = "http://172.23.32.1:8190";
    private static final String DEFAULT_VERSATILE_BASE_URL = "http://172.23.32.1:31113";
    /** AgentEnvExplorer agent-mode endpoint used to seed the same Versatile conversation. */
    private static final String WEALTH_SEED_PATH = "/v1/mock_project_id/agents/mock-agent/conversations/";
    private static final long ROUND_TIMEOUT_MS = 300_000L;
    private static final int RECOMMENDATION_MAX_ROUNDS = 6;
    private static final int PURCHASE_MAX_ROUNDS = 30;

    private static final String INITIAL_REQUEST = "理财推荐";
    private static final String PRODUCT_SELECTION = "第一个";
    private static final String PURCHASE_AMOUNT = "100元";
    private static final String PURCHASE_CONFIRMATION = "确认";
    private static final String AUTO_RESUME = "继续";
    private static final String REMOTE_INPUT_REQUIRED = "Remote agent requires input";

    private static final List<String> SUCCESS_MARKERS = List.of(
            "购买成功", "交易成功", "购买完成", "已完成购买", "成功购买", "办理成功",
            "\"buyStatus\":\"1\"");
    private static final List<String> FAILURE_MARKERS = List.of(
            "error_event", "Exception in thread", "Caused by:",
            "deep_agent_task_1 already exists", "controller task parameter error",
            "当前账户没有绑定借记卡");
    private static final List<String> INSUFFICIENT_FUNDS_MARKERS = List.of(
            "余额不足", "资金不足", "可用余额不足", "资金规划", "转账");
    private static final List<String> TRANSFER_SUCCESS_MARKERS = List.of(
            "转账成功", "转账完成", "转账信息已处理成功", "transfer_07", "SSTANDARDANSWER");

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config).remoteAgent(EDP_AGENT, configuredEdpBaseUrl());
    }

    @Test
    @DisplayName("EDP Agent 流式理财购买：跨 Task 续轮直到完整业务完成")
    void wealthPurchaseContinuesUntilWholeWorkflowCompletes() {
        A2aServiceClient a2a = client(EDP_AGENT);
        assertThat(a2a.getAgentCard().capabilities().streaming())
                .as("预部署 edp-agent-java 的 Agent Card 必须声明 streaming=true")
                .isTrue();

        String contextId = "ctx-edp-wealth-" + UUID.randomUUID().toString().substring(0, 8);
        seedLocalWealthConversation(contextId);

        TaskRun recommendation = runTask(
                a2a,
                contextId,
                INITIAL_REQUEST,
                RECOMMENDATION_MAX_ROUNDS,
                evidence -> {
                    assertThat(evidence)
                            .as("推荐 workflow 的内部续接必须是 Versatile 通用中断")
                            .contains(REMOTE_INPUT_REQUIRED);
                    return AUTO_RESUME;
                });

        assertThat(recommendation.evidence())
                .as("推荐阶段必须返回本地场景中的产品列表")
                .contains("339现管DXXJ-1339", "招银日盈BGA-0088", "聚宝盆RJZ-2016");
        assertThat(recommendation.evidence())
                .as("本地场景初始化后必须识别张三的理财卡尾号")
                .contains("0202")
                .doesNotContain("当前账户没有绑定借记卡");

        PurchaseResumeDecider purchaseDecider = new PurchaseResumeDecider();
        TaskRun purchase = runTask(
                a2a,
                contextId,
                PRODUCT_SELECTION,
                PURCHASE_MAX_ROUNDS,
                purchaseDecider::nextInput);

        String evidence = recommendation.evidence() + "\n" + purchase.evidence();
        assertThat(purchase.taskId())
                .as("推荐 Task 完成后，购买轮次必须在同一 conversation 中创建新 Task")
                .isNotEqualTo(recommendation.taskId());
        assertThat(purchaseDecider.amountProvided())
                .as("购买流程必须响应金额输入节点")
                .isTrue();
        assertThat(purchaseDecider.confirmed())
                .as("购买流程必须响应购买确认节点")
                .isTrue();
        assertThat(purchase.trajectory())
                .as("购买 Task 必须经历输入中断并最终完成")
                .contains(TaskState.TASK_STATE_INPUT_REQUIRED)
                .endsWith(TaskState.TASK_STATE_COMPLETED);
        assertThat(purchase.finalTask())
                .as("购买 COMPLETED 时必须能取得最终 Task 快照")
                .isNotNull();
        assertThat(evidence)
                .as("资金规划必须取得足以覆盖 100 元购买金额的账户余额")
                .contains("\"balance\":\"31500.00\"");
        assertThat(evidence)
                .as("本地购买 workflow 必须返回成功状态")
                .contains("\"productBuyResponse\"", "\"buyStatus\":\"1\"");
        assertThat(SUCCESS_MARKERS.stream().anyMatch(evidence::contains))
                .as("最终结果应命中购买成功语义之一 %s\nevidence=%s",
                        SUCCESS_MARKERS, truncate(evidence, 3000))
                .isTrue();
        for (String marker : FAILURE_MARKERS) {
            assertThat(evidence).as("业务输出不得包含失败标志 '%s'", marker).doesNotContain(marker);
        }
    }

    @Test
    @DisplayName("FEAT-000.wealth-purchase-transfer: 金额不足后转账并完成理财购买")
    void insufficientFundsTransferThenPurchaseCompletes() {
        A2aServiceClient a2a = client(EDP_AGENT);
        assertThat(a2a.getAgentCard().capabilities().streaming())
                .as("瘦身后 EDP Agent 的公开 Agent Card 必须可用且声明 streaming=true")
                .isTrue();

        String contextId = "ctx-edp-wealth-transfer-" + UUID.randomUUID().toString().substring(0, 8);
        seedLocalWealthConversation(contextId);

        TaskRun recommendation = runTask(
                a2a,
                contextId,
                INITIAL_REQUEST,
                RECOMMENDATION_MAX_ROUNDS,
                evidence -> evidence.contains(REMOTE_INPUT_REQUIRED) ? AUTO_RESUME : AUTO_RESUME);
        assertThat(recommendation.evidence())
                .as("资金不足旅程必须先取得产品列表")
                .contains("339现管DXXJ-1339", "招银日盈BGA-0088", "聚宝盆RJZ-2016");

        InsufficientFundsResumeDecider decider = new InsufficientFundsResumeDecider();
        TaskRun purchase = runTask(
                a2a,
                contextId,
                PRODUCT_SELECTION,
                PURCHASE_MAX_ROUNDS,
                decider::nextInput);
        String evidence = recommendation.evidence() + "\n" + purchase.evidence();

        assertThat(decider.amountProvided())
                .as("金额不足旅程必须到达购买金额输入节点")
                .isTrue();
        assertThat(decider.transferConfirmed())
                .as("金额不足旅程必须确认转账")
                .isTrue();
        assertThat(purchase.trajectory())
                .as("转账后原购买 Task 必须经历输入中断并最终完成")
                .contains(TaskState.TASK_STATE_INPUT_REQUIRED)
                .endsWith(TaskState.TASK_STATE_COMPLETED);
        assertThat(INSUFFICIENT_FUNDS_MARKERS.stream().anyMatch(evidence::contains))
                .as("应观察到余额不足/资金规划/转账语义\nevidence=%s", truncate(evidence, 3000))
                .isTrue();
        assertThat(TRANSFER_SUCCESS_MARKERS.stream().anyMatch(evidence::contains)
                || (evidence.contains("转账") && evidence.contains("补齐")))
                .as("应观察到转账成功语义\nevidence=%s", truncate(evidence, 3000))
                .isTrue();
        assertThat(evidence)
                .as("转账后购买必须返回成功状态")
                .contains("\"productBuyResponse\"", "\"buyStatus\":\"1\"");
    }

    private static TaskRun runTask(A2aServiceClient a2a,
                                   String contextId,
                                   String initialInput,
                                   int maxRounds,
                                   ResumeDecider resumeDecider) {
        String pendingInput = initialInput;
        String taskId = null;
        List<TaskState> trajectory = new ArrayList<>();
        StringBuilder allEvidence = new StringBuilder();

        for (int round = 1; round <= maxRounds; round++) {
            String priorTaskId = taskId;
            Message message = buildMessage(pendingInput, contextId, priorTaskId);
            A2aEventCollector collector = new A2aEventCollector();
            AtomicReference<Throwable> streamError = new AtomicReference<>();

            a2a.sendMessageStreaming(
                    message,
                    null,
                    null,
                    List.of(collector.createConsumer()),
                    streamError::set);

            TaskState state = awaitRoundOutcome(collector, a2a, priorTaskId, streamError, round);
            trajectory.add(state);

            String observedTaskId = collector.findFirstTaskId();
            assertThat(observedTaskId).as("第 %d 轮必须返回 taskId", round).isNotBlank();
            if (priorTaskId != null) {
                assertThat(observedTaskId)
                        .as("第 %d 轮必须续接同一非终态 Task", round)
                        .isEqualTo(priorTaskId);
            }
            taskId = observedTaskId;

            assertThat(collector.findFirstContextId())
                    .as("第 %d 轮必须保持客户端锚定的 contextId", round)
                    .isEqualTo(contextId);

            Task snapshot = a2a.getTask(taskId);
            String roundEvidence = TaskTextExtractor.fullSnapshotTextOf(snapshot)
                    + "\n" + collector.collectArtifactText();
            allEvidence.append("\n--- round ").append(round).append(" ---\n").append(roundEvidence);

            LOG.info(String.format(
                    "[edp-wealth] round=%d state=%s taskId=%s contextId=%s input=%s evidence=%s",
                    round, state, taskId, contextId, pendingInput, truncate(roundEvidence, 800)));

            if (state == TaskState.TASK_STATE_COMPLETED) {
                return new TaskRun(taskId, List.copyOf(trajectory), allEvidence.toString(), snapshot);
            }

            assertThat(state)
                    .as("第 %d 轮只允许 INPUT_REQUIRED 或 COMPLETED，trajectory=%s", round, trajectory)
                    .isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);
            pendingInput = resumeDecider.nextInput(roundEvidence);
            assertThat(pendingInput).as("第 %d 轮续接输入不能为空", round).isNotBlank();
        }

        throw new AssertionError("最多 " + maxRounds + " 轮内 Task 未完成，trajectory=" + trajectory
                + "\nevidence=" + truncate(allEvidence.toString(), 3000));
    }

    private static void seedLocalWealthConversation(String contextId) {
        String url = configuredVersatileBaseUrl() + WEALTH_SEED_PATH + contextId
                + "?type=controller&workspace_id=10";
        String body = "{\"inputs\":{\"query\":\"理财推荐\",\"intent\":\"理财选品购买\","
                + "\"wap_userName\":\"张三\"}}";
        HttpURLConnection connection = null;
        try {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(30_000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            try (var output = connection.getOutputStream()) {
                output.write(payload);
            }

            int status = connection.getResponseCode();
            InputStream responseStream = status >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            String responseBody = responseStream == null
                    ? "" : new String(responseStream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(status)
                    .as("初始化本地 AgentEnvExplorer wealth 场景必须返回 2xx，url=%s body=%s",
                            url, truncate(responseBody, 1000))
                    .isBetween(200, 299);
            assertThat(responseBody)
                    .as("初始化场景必须返回完整 SSE end 事件")
                    .contains("\"event\":\"end\"");
        } catch (Exception ex) {
            throw new AssertionError("无法初始化本地 AgentEnvExplorer wealth 场景: " + url, ex);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static Message buildMessage(String text, String contextId, String priorTaskId) {
        Message.Builder builder = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .contextId(contextId)
                .parts(List.of(new TextPart(text)));
        if (priorTaskId != null && !priorTaskId.isBlank()) {
            builder.taskId(priorTaskId);
        }
        return builder.build();
    }

    private static TaskState awaitRoundOutcome(A2aEventCollector collector,
                                               A2aServiceClient a2a,
                                               String knownTaskId,
                                               AtomicReference<Throwable> streamError,
                                               int round) {
        return Awaitility.await("EDP wealth round " + round + " outcome")
                .atMost(ROUND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> resolveState(collector, a2a, knownTaskId, streamError, round), Objects::nonNull);
    }

    private static TaskState resolveState(A2aEventCollector collector,
                                          A2aServiceClient a2a,
                                          String knownTaskId,
                                          AtomicReference<Throwable> streamError,
                                          int round) {
        Throwable error = streamError.get();
        boolean roundResolved = collector.findInputRequiredEvent().isPresent()
                || collector.findTerminalEvent().isPresent();
        if (error != null && (!roundResolved || !A2aStreamErrors.isBenignShutdown(error))) {
            throw new AssertionError("第 " + round + " 轮 A2A streaming 失败", error);
        }

        if (collector.findInputRequiredEvent().isPresent()) {
            return TaskState.TASK_STATE_INPUT_REQUIRED;
        }

        Optional<ClientEvent> terminal = collector.findTerminalEvent();
        if (terminal.isPresent()) {
            return requireSuccessfulTerminal(stateOf(terminal.get()), round);
        }

        String taskId = collector.findFirstTaskId();
        if (taskId == null || taskId.isBlank()) {
            taskId = knownTaskId;
        }
        if (taskId == null || taskId.isBlank()) {
            return null;
        }

        Task task = a2a.getTask(taskId);
        if (task == null || task.status() == null || task.status().state() == null) {
            return null;
        }
        TaskState state = task.status().state();
        return state.isFinal() ? requireSuccessfulTerminal(state, round) : null;
    }

    private static TaskState requireSuccessfulTerminal(TaskState state, int round) {
        if (state != TaskState.TASK_STATE_COMPLETED) {
            throw new AssertionError("第 " + round + " 轮进入非成功终态: " + state);
        }
        return state;
    }

    private static TaskState stateOf(ClientEvent event) {
        if (event instanceof TaskEvent taskEvent) {
            return taskEvent.getTask().status().state();
        }
        if (event instanceof TaskUpdateEvent updateEvent) {
            return updateEvent.getTask().status().state();
        }
        return null;
    }

    private static String configuredEdpBaseUrl() {
        return configuredUrl("edp.agent.base-url", "EDP_AGENT_BASE_URL", DEFAULT_EDP_BASE_URL);
    }

    private static String configuredVersatileBaseUrl() {
        return configuredUrl(
                "local.versatile.base-url", "LOCAL_VERSATILE_BASE_URL", DEFAULT_VERSATILE_BASE_URL);
    }

    private static String configuredUrl(String property, String environment, String defaultValue) {
        String systemValue = System.getProperty(property);
        if (systemValue != null && !systemValue.isBlank()) {
            return stripTrailingSlash(systemValue.trim());
        }
        String environmentValue = System.getenv(environment);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return stripTrailingSlash(environmentValue.trim());
        }
        return defaultValue;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    @FunctionalInterface
    private interface ResumeDecider {
        String nextInput(String evidence);
    }

    private static final class PurchaseResumeDecider {
        private boolean amountProvided;
        private boolean confirmed;

        private String nextInput(String evidence) {
            if (!amountProvided && (evidence.contains("购买的金额")
                    || evidence.contains("product_select_missing_amount"))) {
                amountProvided = true;
                return PURCHASE_AMOUNT;
            }
            if (amountProvided && !confirmed && (evidence.contains("确认是否购买")
                    || evidence.contains("product_select_confirm"))) {
                confirmed = true;
                return PURCHASE_CONFIRMATION;
            }
            assertThat(evidence)
                    .as("金额和确认之外的续接必须是 Versatile 通用中断")
                    .contains(REMOTE_INPUT_REQUIRED);
            return AUTO_RESUME;
        }

        private boolean amountProvided() {
            return amountProvided;
        }

        private boolean confirmed() {
            return confirmed;
        }
    }

    private static final class InsufficientFundsResumeDecider {
        private boolean amountProvided;
        private boolean transferConfirmed;
        private boolean transferCompleted;
        private int purchaseConfirmations;

        private String nextInput(String evidence) {
            if (!amountProvided && (evidence.contains("购买的金额")
                    || evidence.contains("product_select_missing_amount"))) {
                amountProvided = true;
                return "32000元";
            }
            // The task snapshot keeps earlier purchase prompts. Prefer the second-card
            // balance observation so "确认是否购买" cannot consume the transfer step.
            if (!transferConfirmed && evidence.contains("\"bankCardNumber\":\"622202")
                    && evidence.contains("\"balance\":")) {
                transferConfirmed = true;
                return "确认转账";
            }
            if (!transferConfirmed && (evidence.contains("余额不足")
                    || evidence.contains("资金不足")
                    || evidence.contains("资金规划")
                    || evidence.contains("确认转账")
                    || evidence.contains("转账确认"))) {
                transferConfirmed = true;
                return "确认转账";
            }
            if (transferConfirmed && !transferCompleted) {
                if (TRANSFER_SUCCESS_MARKERS.stream().anyMatch(evidence::contains)
                        || evidence.contains("\"transferStatus\"")) {
                    transferCompleted = true;
                } else {
                    return AUTO_RESUME;
                }
            }
            if (evidence.contains("确认是否购买") || evidence.contains("product_select_confirm")) {
                purchaseConfirmations++;
                return PURCHASE_CONFIRMATION;
            }
            return AUTO_RESUME;
        }

        private boolean amountProvided() {
            return amountProvided;
        }

        private boolean transferConfirmed() {
            return transferConfirmed;
        }
    }

    private record TaskRun(String taskId, List<TaskState> trajectory, String evidence, Task finalTask) {
    }
}

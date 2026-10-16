package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

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
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * FEAT-001.input-required-fake-completed — Task 状态语义忠实性(负向验证).
 *
 * <p><b>Spec 依据</b>(version-scope FEAT-001 §5.1.7):
 * 「handler 输出需要用户输入的中断时,Task 必须进入 input-required 类语义,
 * <b>而不是伪装成 completed</b>」。
 *
 * <p>正向已由 {@link MultiTurnSearchFollowupTest} 覆盖(缺项 prompt → 期望 INPUT_REQUIRED)。
 * 本用例覆盖<b>反向</b>:提供<b>信息齐全</b>的 prompt 时,SUT 不得<b>误伪装 INPUT_REQUIRED</b>
 * —— 即"当不需要 clarification 时,Task 应 COMPLETED,不能装追问骗客户端续轮"。
 *
 * <p><b>为什么反向也要测</b>:INPUT_REQUIRED 是一条昂贵路径 —— 客户端会挂机等续答、消费方
 * 会误分配用户会话槽位。若 agent 因保守/歧义处理不当滥用 INPUT_REQUIRED,与 §5.1.7 "忠实反映
 * handler 意图" 的精神对立。正反向配对形成完整的状态语义 gate。
 *
 * <p><b>prompt 挑选</b>:{@link #COMPLETE_PROMPT} 显式提供了模型(DeepSeek-R1)+ 具体问题
 * (官方定价)+ 期望格式(链接) —— search-agent 的 LLM 判追问条件"缺型号/缺问题" 无一命中,
 * 应直接调 search tool 返回答案。若首轮就 INPUT_REQUIRED,判 FAIL。
 *
 * <p><b>dual-stack 沿用 {@link MultiTurnSearchFollowupTest} 模式</b>:deep-research 通过
 * {@code SEARCH_AGENT_URL} env 寻址 search agent,单 stack 会因默认端口不通导致 tool 装配失败
 * 走 MCP 直接答,绕过 search-agent 追问链路(那样测的就不是 spec §5.1.7 而是别的东西)。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Tag("manual")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.input-required-fake-completed: 信息齐全时不得误伪装 INPUT_REQUIRED (§5.1.7 反向)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InputRequiredFakeCompletedTest {

    private static final Logger LOG = Logger.getLogger(InputRequiredFakeCompletedTest.class.getName());

    private static final String DEEP_RESEARCH = "deep-research";
    private static final String SEARCH = "search";
    private static final long ROUND_TIMEOUT_MS = 240_000;

    /**
     * 完全指定的 prompt —— 模型 + 具体问题 + 期望格式 都齐,search-agent LLM 无判追问的条件。
     * 若 SUT 仍返 INPUT_REQUIRED,说明"agent 对齐全 prompt 也误走追问路径" —— §5.1.7 反向违约。
     */
    private static final String COMPLETE_PROMPT =
            "请查询 DeepSeek-R1 模型的官方定价,请给出官方定价页面链接。";

    /** 追问语义关键词 —— artifact 若含这些,配合 COMPLETED 状态说明"假 COMPLETED 装追问答复"。 */
    private static final List<String> ASK_USER_KEYWORDS = List.of(
            "请告诉我", "请提供", "请指定", "请补充", "请说明",
            "which model", "please specify", "please provide", "please clarify",
            "?"
    );

    private TestConfig config;
    private SutStack searchStack;
    private SutStack deepStack;

    @BeforeAll
    void startStack() {
        config = TestConfig.load();
        searchStack = SutStack.builder(config).agent(SEARCH).start();
        String searchBaseUrl = searchStack.baseUrl(SEARCH);
        deepStack = SutStack.builder(config)
                .streaming(false)
                .agent(DEEP_RESEARCH, a -> a
                        .env("SEARCH_AGENT_URL", searchBaseUrl)
                        // verify-agent 是本 jar 0.1.0 新增 remote-agents[1]，startup 校验非空；
                        // 本用例只测 §5.1.7 反向语义，不打 verify 链路，占位即可。
                        .env("VERIFY_AGENT_URL", "http://127.0.0.1:1"))
                .start();
    }

    @AfterAll
    void tearDown() {
        if (deepStack != null) deepStack.close();
        if (searchStack != null) searchStack.close();
    }

    @Test
    @DisplayName("FEAT-001.input-required-fake-completed: 信息齐全 prompt → COMPLETED,非 INPUT_REQUIRED")
    void completePromptShouldNotTriggerInputRequired() {
        A2aServiceClient a2a = deepStack.client(DEEP_RESEARCH);

        String contextId = "ctx-fake-ir-" + UUID.randomUUID().toString().substring(0, 8);
        Message message = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .contextId(contextId)
                .parts(List.of(new TextPart(COMPLETE_PROMPT)))
                .build();

        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> sendError = new AtomicReference<>();
        a2a.sendMessage(message, List.of(collector.createConsumer()), sendError::set);
        if (sendError.get() != null) {
            fail("FEAT-001.input-required-fake-completed: message/send failed", sendError.get());
        }

        TaskState state = awaitOutcome(collector, a2a, ROUND_TIMEOUT_MS);

        String taskId = collector.findFirstTaskId();
        Task snapshot = a2a.getTask(taskId);
        String artifactText = TaskTextExtractor.textOf(snapshot);
        String fullSnapshot = TaskTextExtractor.fullSnapshotTextOf(snapshot);

        LOG.info(String.format(
                "%n===== FEAT-001.input-required-fake-completed =====%n"
                        + "state=%s taskId=%s%n"
                        + "----- artifact -----%n%s%n"
                        + "----- fullSnapshot -----%n%s%n"
                        + "==================================",
                state, taskId, artifactText, fullSnapshot));

        // A) 首轮就 INPUT_REQUIRED = 直接违约(信息齐全却假装追问)
        assertThat(state)
                .as("FEAT-001.input-required-fake-completed: 信息齐全 prompt 不应首轮就 INPUT_REQUIRED"
                                + "(§5.1.7 反向:不当滥用追问路径)。artifact/fullSnapshot 见 log。")
                .isNotEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);

        // B) 若 COMPLETED,artifact 应是"实际答复"而非"假 COMPLETED 装追问"文本
        //    (§5.1.7 正向:handler 意图应忠实反映到 Task 状态)
        if (state == TaskState.TASK_STATE_COMPLETED) {
            boolean looksLikeAskUser = ASK_USER_KEYWORDS.stream()
                    .filter(k -> !k.equals("?"))
                    .anyMatch(fullSnapshot::contains);
            boolean hasModelReference = fullSnapshot.contains("DeepSeek");
            assertThat(looksLikeAskUser && !hasModelReference)
                    .as("FEAT-001.input-required-fake-completed: COMPLETED 时 artifact 不应是"
                                    + "'假 COMPLETED 装追问文本'(§5.1.7 正向违约)\nfullSnapshot 头 500 字: %s",
                            truncate(fullSnapshot, 500))
                    .isFalse();
        }
    }

    private static TaskState awaitOutcome(A2aEventCollector collector, A2aServiceClient a2a, long timeoutMs) {
        return Awaitility.await("outcome")
                .atMost(timeoutMs, TimeUnit.MILLISECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> resolveState(collector, a2a), Objects::nonNull);
    }

    private static TaskState resolveState(A2aEventCollector collector, A2aServiceClient a2a) {
        if (collector.findInputRequiredEvent().isPresent()) {
            return TaskState.TASK_STATE_INPUT_REQUIRED;
        }
        Optional<ClientEvent> terminalEvent = collector.findTerminalEvent();
        if (terminalEvent.isPresent()) {
            TaskState s = extractState(terminalEvent.get());
            if (s == TaskState.TASK_STATE_FAILED || s == TaskState.TASK_STATE_CANCELED) {
                throw new AssertionError("ended with " + s);
            }
            if (s == TaskState.TASK_STATE_COMPLETED) return s;
        }
        String taskId = collector.findFirstTaskId();
        if (taskId == null || taskId.isBlank()) return null;
        Task task = a2a.getTask(taskId);
        if (task == null || task.status() == null || task.status().state() == null) return null;
        TaskState state = task.status().state();
        if (state == TaskState.TASK_STATE_FAILED || state == TaskState.TASK_STATE_CANCELED) {
            throw new AssertionError("ended with " + state);
        }
        if (state.isFinal() && state == TaskState.TASK_STATE_COMPLETED) return state;
        return null;
    }

    private static TaskState extractState(ClientEvent event) {
        if (event instanceof TaskEvent te) return te.getTask().status().state();
        if (event instanceof TaskUpdateEvent ue) return ue.getTask().status().state();
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}

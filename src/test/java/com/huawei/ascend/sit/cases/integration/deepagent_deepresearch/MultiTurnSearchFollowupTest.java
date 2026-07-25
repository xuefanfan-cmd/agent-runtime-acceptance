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
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
 * DA-08 — deep-research 通过 search-agent 多轮追问补全查询意图（同 contextId 循环回答）.
 *
 * <p>参考 {@code docs/cases/deepagent/DA-08-multi-turn-search-followup.md}：
 * <ol>
 *   <li>Turn1 发送"你好,帮我查一下DeepSeek官方定价，请给出官网链接"（信息缺项）；</li>
 *   <li>deep-research 转 search-agent，search-agent 判定缺型号 → 期望 {@code INPUT_REQUIRED}；</li>
 *   <li>Turn2 同 {@code contextId + taskId} 回答 "DeepSeek-V3"；</li>
 *   <li>若仍追问，按 §5 追问 → 回答映射表循环回答，直到 {@code COMPLETED}；</li>
 *   <li>最多 {@link #MAX_ROUNDS} 轮，超上限判 FAIL。</li>
 * </ol>
 *
 * <p><b>为什么不复用 {@code OpenjiuwenSyncTwoTurnRunner}</b>：那个 runner 是 2 轮定长，
 * turn2 期望由外部 scenario 驱动；本档需 n 轮动态循环 + 追问驱动答复，故内联控制流。
 *
 * <p><b>轮内等待策略</b>：与 {@code OpenjiuwenRoundAwait} 语义等价 —— 事件优先，
 * getTask 只当作终态兜底（不信任 getTask 里的 INPUT_REQUIRED，避免延续轮起点拿到上一轮残留）。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-004")
@Feature("FEAT-004: 任务驱动远程智能体调用")
@Story("da.multi-turn-search-followup: 远端 INPUT_REQUIRED 多轮追问补全查询意图")
class MultiTurnSearchFollowupTest extends BaseManagedStackTest {

    private static final Logger LOG = Logger.getLogger(MultiTurnSearchFollowupTest.class.getName());

    private static final String DEEP_RESEARCH = "deep-research";
    private static final long ROUND_TIMEOUT_MS = 240_000;
    private static final int MAX_ROUNDS = 5;

    private static final String TURN1_TEXT = "你好,帮我查一下DeepSeek官方定价，请给出官网链接";
    private static final String TURN2_TEXT = "帮我查DeepSeek-R1的官方定价";

    /** DA-08.D 必含专有名 —— 证明 agent 定位到用户指定的型号。 */
    private static final String MODEL_MARKER = "DeepSeek-R1";

    /** DA-08.D 至少含其一 —— 价格语义信号词。 */
    private static final List<String> PRICE_SIGNAL_WORDS = List.of("价格", "定价", "token", "元", "USD", "$");

    /** DA-08.E 沿用 DA-02/03/06 bug 守卫。 */
    private static final String BUG_MARKER_TASK_EXISTS = "deep_agent_task_1 already exists";
    private static final String BUG_MARKER_CONTROLLER_ERR = "controller task parameter error";

    private static final List<TaskState> ALLOWED_STATES = List.of(
            TaskState.TASK_STATE_COMPLETED,
            TaskState.TASK_STATE_INPUT_REQUIRED);

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .streaming(false)
                .agent(DEEP_RESEARCH);
    }

    @Test
    @DisplayName("DA-08: 多轮追问 → 同 contextId 续答 → 最终命中模型 + 价格语义")
    void multiTurnSearchFollowupReachesCompletedWithPricing() {
        A2aServiceClient a2a = client(DEEP_RESEARCH);

        String runSuffix = "-" + UUID.randomUUID().toString().substring(0, 8);
        String anchorContextId = "ctx-da08-followup" + runSuffix;

        List<TaskState> stateTrajectory = new ArrayList<>();
        List<String> observedContextIds = new ArrayList<>();
        StringBuilder mergedArtifacts = new StringBuilder();

        String pendingReply = TURN1_TEXT;
        String priorTaskId = null;
        TaskState lastState = null;
        String lastRoundArtifact = "";
        int round = 0;

        while (round < MAX_ROUNDS) {
            round++;
            String label = "round " + round;

            Message message = buildRoundMessage(pendingReply, anchorContextId, priorTaskId, round == 1);

            A2aEventCollector collector = new A2aEventCollector();
            AtomicReference<Throwable> sendError = new AtomicReference<>();

            a2a.sendMessage(
                    message,
                    List.of(collector.createConsumer()),
                    sendError::set);

            if (sendError.get() != null) {
                fail("DA-08: " + label + " message/send failed", sendError.get());
            }

            TaskState state = awaitAllowedOutcome(collector, a2a, priorTaskId, ROUND_TIMEOUT_MS, label);
            stateTrajectory.add(state);
            lastState = state;

            String cid = collector.findFirstContextId();
            observedContextIds.add(cid);

            String tid = collector.findFirstTaskId();
            assertThat(tid).as("DA-08: %s taskId 应非空", label).isNotBlank();
            priorTaskId = tid;

            Task snapshot = a2a.getTask(tid);
            String roundText = TaskTextExtractor.textOf(snapshot);
            String fullSnapshot = TaskTextExtractor.fullSnapshotTextOf(snapshot);
            lastRoundArtifact = roundText;
            mergedArtifacts.append(roundText).append("\n---\n");

            LOG.info(String.format(
                    "%n===== DA-08 %s =====%nstate=%s taskId=%s contextId=%s%n"
                            + "----- artifact(textOf) -----%n%s%n"
                            + "----- full snapshot (history + artifact + status.message) -----%n%s%n"
                            + "==============================",
                    label, state, tid, cid, roundText, fullSnapshot));

            if (state == TaskState.TASK_STATE_COMPLETED) {
                break;
            }

            // INPUT_REQUIRED → 选下一轮回答；用 fullSnapshot 而非 textOf，
            // 因为 agent 的追问文本可能落在 status.message 或 history，而不是 artifacts。
            String promptForNextReply = !fullSnapshot.isBlank() ? fullSnapshot : roundText;
            pendingReply = (round == 1) ? TURN2_TEXT : chooseFollowupReply(promptForNextReply);
            LOG.info(String.format("DA-08 %s → INPUT_REQUIRED, next reply=%s", label, pendingReply));
        }

        // DA-08.A — 多轮循环内终态达成 COMPLETED
        assertThat(lastState)
                .as("DA-08.A: 循环退出时终态应为 COMPLETED（超上限 %d 轮或非法终态判 FAIL）\ntrajectory=%s",
                        MAX_ROUNDS, stateTrajectory)
                .isEqualTo(TaskState.TASK_STATE_COMPLETED);

        // DA-08.B — 轨迹中至少出现过一次 INPUT_REQUIRED（追问链路存在的存在性证据）
        assertThat(stateTrajectory)
                .as("DA-08.B: 轨迹应至少出现一次 INPUT_REQUIRED（agent 应经由 search-agent 追问型号）"
                                + "\ntrajectory=%s\n(若首轮就 COMPLETED，说明 agent 没有走追问链路)",
                        stateTrajectory)
                .contains(TaskState.TASK_STATE_INPUT_REQUIRED);

        // DA-08.C — 每轮 contextId 保持一致
        assertThat(observedContextIds)
                .as("DA-08.C: 所有轮的 contextId 应等于客户端锚 '%s'\nobserved=%s",
                        anchorContextId, observedContextIds)
                .allSatisfy(cid -> assertThat(cid).isEqualTo(anchorContextId));

        // DA-08.D — 最终 artifact 命中"模型 + 定价语义"
        assertThat(lastRoundArtifact)
                .as("DA-08.D: 最终 artifact 应包含专有名 '%s'\nartifact 头 500 字: %s",
                        MODEL_MARKER, truncate(lastRoundArtifact, 500))
                .contains(MODEL_MARKER);

        boolean hasPriceSignal = PRICE_SIGNAL_WORDS.stream().anyMatch(lastRoundArtifact::contains);
        assertThat(hasPriceSignal)
                .as("DA-08.D: 最终 artifact 应至少含一个价格信号词 %s\nartifact 头 500 字: %s",
                        PRICE_SIGNAL_WORDS, truncate(lastRoundArtifact, 500))
                .isTrue();

        // DA-08.E — bug 标志串缺席
        String merged = mergedArtifacts.toString();
        assertThat(merged)
                .as("DA-08.E: 合并 artifact 不应含已知 bug 标志\nmerged 头 500 字: %s", truncate(merged, 500))
                .doesNotContain(BUG_MARKER_TASK_EXISTS)
                .doesNotContain(BUG_MARKER_CONTROLLER_ERR);
    }

    /**
     * 构造本轮消息。首轮只带 contextId；INPUT_REQUIRED 延续轮必须同时带 taskId
     * （A2A 契约：非终态延续同一 task）。
     */
    private static Message buildRoundMessage(String text, String contextId, String priorTaskId, boolean firstRound) {
        Message.Builder builder = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .contextId(contextId)
                .parts(List.of(new TextPart(text)));
        if (!firstRound) {
            assertThat(priorTaskId)
                    .as("DA-08: 延续轮必须携带前一轮 taskId（INPUT_REQUIRED 非终态延续契约）")
                    .isNotBlank();
            builder.taskId(priorTaskId);
        }
        return builder.build();
    }

    /**
     * 单轮等待：事件优先（含 INPUT_REQUIRED 语义）；getTask 只当作终态兜底。
     * 与 {@code OpenjiuwenRoundAwait.awaitAllowedOutcome} 等价，因该 helper 是包私有的
     * 且位于 openjiuwen 支持包，本档不引入跨用例耦合，故内联同语义控制流。
     */
    private static TaskState awaitAllowedOutcome(A2aEventCollector collector, A2aServiceClient a2a,
                                                 String knownTaskId, long timeoutMs, String label) {
        return Awaitility.await(label + " outcome")
                .atMost(timeoutMs, TimeUnit.MILLISECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> resolveState(collector, a2a, knownTaskId, label), Objects::nonNull);
    }

    private static TaskState resolveState(A2aEventCollector collector, A2aServiceClient a2a,
                                          String knownTaskId, String label) {
        // 1) 事件优先：INPUT_REQUIRED 只信任事件
        if (collector.findInputRequiredEvent().isPresent()) {
            return TaskState.TASK_STATE_INPUT_REQUIRED;
        }
        Optional<ClientEvent> terminalEvent = collector.findTerminalEvent();
        if (terminalEvent.isPresent()) {
            TaskState s = extractState(terminalEvent.get());
            if (s == TaskState.TASK_STATE_FAILED || s == TaskState.TASK_STATE_CANCELED) {
                throw new AssertionError(label + " ended with " + s);
            }
            if (ALLOWED_STATES.contains(s)) {
                return s;
            }
        }
        // 2) getTask 兜底：只信任终态
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
        if (state == TaskState.TASK_STATE_FAILED || state == TaskState.TASK_STATE_CANCELED) {
            throw new AssertionError(label + " ended with " + state);
        }
        if (state.isFinal() && ALLOWED_STATES.contains(state)) {
            return state;
        }
        return null;
    }

    private static TaskState extractState(ClientEvent event) {
        if (event instanceof TaskEvent te) {
            return te.getTask().status().state();
        }
        if (event instanceof TaskUpdateEvent ue) {
            return ue.getTask().status().state();
        }
        return null;
    }

    /**
     * DA-08 §5 追问 → 回答映射（简单启发式，避免客户端引入 LLM 理解 agent 追问）。
     */
    private static String chooseFollowupReply(String promptText) {
        String p = promptText == null ? "" : promptText.toLowerCase();
        boolean hasInputWord = promptText != null && (promptText.contains("输入") || p.contains("input"));
        boolean hasOutputWord = promptText != null && (promptText.contains("输出") || p.contains("output"));
        boolean hasToken = p.contains("token");
        if (hasInputWord && hasToken) return "输入 token 定价";
        if (hasOutputWord && hasToken) return "输出 token 定价";
        if (p.contains("context") || (promptText != null && promptText.contains("上下文"))) return "标准上下文";
        if (p.contains("cache") || (promptText != null && promptText.contains("缓存"))) return "不使用缓存";
        return "请直接给我 DeepSeek-V3 的官方定价链接";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
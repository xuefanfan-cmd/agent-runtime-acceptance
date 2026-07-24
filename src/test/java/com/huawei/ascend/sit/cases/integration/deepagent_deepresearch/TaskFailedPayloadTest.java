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
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * FEAT-001.task-failed-payload — failed Task 必须携带可供客户端<b>程序化判断</b>的结构化错误 payload.
 *
 * <p><b>Spec 依据</b>（version-scope FEAT-001 明文 MUST）：
 * <ul>
 *   <li>§5.1.6 —— 「handler 输出 {@code FAILED} 或执行异常时必须形成 failed Task 表面，
 *       并携带<b>可供客户端程序化判断</b>的错误信息」；</li>
 *   <li>§5.1.8 错误表 —— 「handler/runtime exception → 形成 A2A failed Task 或 JSON-RPC internal
 *       error；可形成 Task 的路径应携带<b>结构化错误 payload</b>」。</li>
 * </ul>
 *
 * <p><b>关键判读</b>：spec 里「<b>可供客户端程序化判断</b>」 &gt; 「payload 非空」。C3 层 2 覆盖后者
 * （{@link DownstreamAgentKilledMidStreamTest} 层 2 已断 {@code status.message.parts} 非空），
 * 但只要 payload 是一坨自然语言错误字符串，客户端就只能靠正则匹配来分支处理 —— 那不叫「程序化判断」。
 * 程序化判断的语义是：客户端能按<b>稳定的结构化字段</b>（{@link DataPart} 结构 / TextPart 内部 JSON
 * 里的稳定字段 / message.metadata 里的稳定字段）分支决策，而不是靠自然语言启发式解析。
 *
 * <p><b>断言层次</b>（严格按 spec MUST）：
 * <ol>
 *   <li><b>层 1</b>：与 {@link DownstreamAgentKilledMidStreamTest} 层 1 一致 —— 终态 ∈
 *       {FAILED, CANCELED, REJECTED}（§5.1.4 + §5.1.6 + §5.1.8）。</li>
 *   <li><b>层 2</b>：与 {@link DownstreamAgentKilledMidStreamTest} 层 2 一致 ——
 *       {@code status.message.parts} 非空（§5.1.8）。</li>
 *   <li><b>层 3（本用例专属）</b>：payload 至少满足以下任一「程序化判断」信号（§5.1.6 承诺）：
 *     <ul>
 *       <li>3-a：至少一个 Part 是结构化 {@link DataPart}（SDK 里 DataPart 是承载结构化数据的
 *           一等 Part 类型），<b>或</b></li>
 *       <li>3-b：TextPart 的文本能被解析为 JSON 对象，<b>或</b></li>
 *       <li>3-c：{@code status.message.metadata} 里存在任一约定错误字段（{@code error} /
 *           {@code errorCode} / {@code code} / {@code type} / {@code reason}）。</li>
 *     </ul>
 *     三条择一满足即视为「程序化判断可行」。三条全不满足 → 客户端只能靠自然语言字符串启发式
 *     处理 → 违反 §5.1.6「可供客户端程序化判断」。</li>
 * </ol>
 *
 * <p><b>当下预期</b>（<b>expected-red at current stage</b>）：本用例的层 3 断言在当前 SUT 阶段
 * <b>大概率红</b>。开发组尚未落实 §5.1.6 / §5.1.8 的「程序化判断」shape —— 失败 Task 的 payload
 * 当前多以自然语言 TextPart 承载。这里刻意让用例失败，作为 <b>SIT 侧的红旗</b>：SUT 违约就红，
 * SUT 侧补齐结构化 payload 后自动绿。<b>不要</b>因为「当前红」把断言 relax 成 SUT 当前行为。
 *
 * <p><b>关于评审 §6</b>：具体 error code 枚举、字段命名（是 {@code error.code} 还是 {@code type}
 * 还是别的）由评审 §6 定；本用例故意不硬钉字段名，只断「至少存在一种可程序化判断的形态」。评审
 * §6 落地后可把层 3 收紧为具体字段。
 *
 * <p><b>触发机制</b>：与 {@link DownstreamAgentKilledMidStreamTest} 相同 —— deep-research + search
 * 双 agent，WORKING 出现后杀 search 进程，让 deep-research 的下游 A2A 调用 connection refused，
 * 走 §5.1.8「handler/runtime exception」路径。</p>
 *
 * <p><b>Tag 说明</b>：{@code manual} 与 {@link DownstreamAgentKilledMidStreamTest} 保持一致
 * —— 需本地就绪 deep-research + search 两 jar；CI 环境默认不具备。</p>
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Tag("manual")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.task-failed-payload: §5.1.6 失败终态携带程序化判断信息")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskFailedPayloadTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final String SEARCH = "search";
    private static final long SEND_TIMEOUT_MS = 240_000;
    private static final long WORKING_TIMEOUT_MS = 30_000;
    private static final long POST_WORKING_GRACE_MS = 3_000;

    private static final String USER_INPUT =
            "帮我搜索 2026 年 7 月 15 日全球黄金价格盘中最高价的准确数字，直接给出数字和单位";

    private static final List<String> PROGRAMMATIC_METADATA_KEYS =
            List.of("error", "errorcode", "code", "type", "reason", "status");

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
    @DisplayName("FEAT-001.task-failed-payload: failed Task 必须携带可程序化判断的结构化错误 payload"
            + "（DataPart / JSON TextPart / metadata error 字段三选一）")
    void failedTaskMustCarryProgrammaticallyJudgeablePayload() throws InterruptedException {
        A2aServiceClient a2a = deepStack.client(DEEP_RESEARCH);

        String contextId = "ctx-feat001-failed-payload-" + UUID.randomUUID().toString().substring(0, 8);
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
            fail("FEAT-001.task-failed-payload: WORKING_TIMEOUT_MS 内未观测到任何 task 状态 —— "
                    + "故障注入前置条件不满足。\n  contextId=" + contextId, timeout);
            return;
        }
        Thread.sleep(POST_WORKING_GRACE_MS);

        assertThat(searchStack.isRunning(SEARCH))
                .as("FEAT-001.task-failed-payload [前置]: 故障注入前 search 应仍在运行")
                .isTrue();
        searchStack.stop(SEARCH);
        assertThat(searchStack.isRunning(SEARCH))
                .as("FEAT-001.task-failed-payload [前置]: stop() 后 search 应已死")
                .isFalse();

        TaskState terminalState;
        try {
            terminalState = collector.awaitTerminalState(SEND_TIMEOUT_MS);
        } catch (ConditionTimeoutException timeout) {
            Throwable err = streamError.get();
            if (err != null) {
                fail("FEAT-001.task-failed-payload: 超时未观测到终态,但 stream 有异常 - contextId="
                        + contextId, err);
            }
            fail("FEAT-001.task-failed-payload: 超时未观测到终态 —— stream 未按 §5.1.4 收束 - contextId="
                    + contextId, timeout);
            return;
        }

        // 层 1(§5.1.4 + §5.1.6 + §5.1.8): failed 家族 - 与 downstream-killed 一致基线
        assertThat(terminalState)
                .as("FEAT-001.task-failed-payload [层1]: search 中途被杀后 deep-research 无法完整回答,"
                        + "必须以 failed/canceled/rejected 收束\n"
                        + "  contextId=%s\n  stream.error=%s", contextId, streamError.get())
                .isIn(TaskState.TASK_STATE_FAILED,
                        TaskState.TASK_STATE_CANCELED,
                        TaskState.TASK_STATE_REJECTED);

        Task terminalTask = extractTaskFromEvent(collector.findTerminalEvent().orElse(null));
        assertThat(terminalTask)
                .as("FEAT-001.task-failed-payload [层2 前置]: 终态事件应携带 Task 对象\n"
                        + "  contextId=%s", contextId)
                .isNotNull();

        Message statusMessage = terminalTask.status().message();
        assertThat(statusMessage)
                .as("FEAT-001.task-failed-payload [层2]: failed 家族 Task 必须携带 status.message\n"
                        + "  contextId=%s\n  terminalState=%s\n  task.id=%s",
                        contextId, terminalState, terminalTask.id())
                .isNotNull();

        List<Part<?>> parts = statusMessage.parts();
        assertThat(parts)
                .as("FEAT-001.task-failed-payload [层2]: status.message.parts 应非空\n"
                        + "  contextId=%s\n  task.id=%s", contextId, terminalTask.id())
                .isNotNull()
                .isNotEmpty();

        // 层 3(§5.1.6 "可供客户端程序化判断"): DataPart / JSON TextPart / metadata error 字段三选一
        ProgrammaticJudgmentDiagnosis diag = diagnoseProgrammaticJudgment(parts, statusMessage.metadata());

        assertThat(diag.hasProgrammaticSignal())
                .as("FEAT-001.task-failed-payload [层3]: §5.1.6 承诺「可供客户端程序化判断的错误信息」"
                        + "—— failed Task 至少应满足以下一种「程序化判断」信号:\n"
                        + "  (a) 至少一个 Part 是结构化 DataPart，或\n"
                        + "  (b) TextPart 文本可被解析为 JSON 对象，或\n"
                        + "  (c) status.message.metadata 里包含 error/code/type/reason 等约定字段。\n"
                        + "  三者皆无 → 客户端只能靠自然语言启发式解析 → 违反 §5.1.6。\n"
                        + "  【当前预期】本断言在当前 SUT 阶段大概率红 —— 开发组尚未落实结构化 payload；\n"
                        + "  失败即证明 SUT 与 spec 存在 gap，待评审 §6 定 shape 后 SUT 补齐即自动绿。\n"
                        + "  contextId=%s\n  task.id=%s\n  terminalState=%s\n  diagnosis=%s",
                        contextId, terminalTask.id(), terminalState, diag.summary())
                .isTrue();
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

    private static ProgrammaticJudgmentDiagnosis diagnoseProgrammaticJudgment(
            List<Part<?>> parts, Map<String, Object> metadata) {
        boolean hasDataPart = false;
        boolean hasJsonTextPart = false;
        String textSample = null;
        for (Part<?> part : parts) {
            if (part instanceof DataPart) {
                hasDataPart = true;
            } else if (part instanceof TextPart tp) {
                String text = tp.text();
                if (textSample == null && text != null) {
                    textSample = text.length() <= 200 ? text : text.substring(0, 200) + "...";
                }
                if (looksLikeJsonObject(text)) {
                    hasJsonTextPart = true;
                }
            }
        }

        boolean hasMetadataKey = false;
        String metadataKeys = "";
        if (metadata != null && !metadata.isEmpty()) {
            metadataKeys = String.join(",", metadata.keySet());
            for (String key : metadata.keySet()) {
                if (key == null) continue;
                String lower = key.toLowerCase();
                for (String want : PROGRAMMATIC_METADATA_KEYS) {
                    if (lower.equals(want) || lower.contains(want)) {
                        hasMetadataKey = true;
                        break;
                    }
                }
                if (hasMetadataKey) break;
            }
        }

        return new ProgrammaticJudgmentDiagnosis(
                hasDataPart, hasJsonTextPart, hasMetadataKey, textSample, metadataKeys);
    }

    private static boolean looksLikeJsonObject(String s) {
        if (s == null) return false;
        String trimmed = s.trim();
        if (trimmed.length() < 2) return false;
        // 只接受 JSON object；纯字符串 / 数字 / 数组不算「可程序化判断的错误信息」结构。
        if (trimmed.charAt(0) != '{' || trimmed.charAt(trimmed.length() - 1) != '}') {
            return false;
        }
        // 至少包含一个 quoted key : value 形态,避免 "{something}" 之类的自然语言误判。
        return trimmed.matches("(?s)\\{\\s*\"[^\"]+\"\\s*:.*}");
    }

    private record ProgrammaticJudgmentDiagnosis(
            boolean hasDataPart,
            boolean hasJsonTextPart,
            boolean hasMetadataKey,
            String textSample,
            String metadataKeys) {

        boolean hasProgrammaticSignal() {
            return hasDataPart || hasJsonTextPart || hasMetadataKey;
        }

        String summary() {
            return "hasDataPart=" + hasDataPart
                    + " hasJsonTextPart=" + hasJsonTextPart
                    + " hasMetadataKey=" + hasMetadataKey
                    + " metadataKeys=[" + metadataKeys + "]"
                    + " textSample=" + textSample;
        }
    }
}

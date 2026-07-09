package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * DA-07 — deep-research 沙箱工具调用（流式）(场景 7 首个 curl).
 *
 * <p>参考 §7 场景 1 首个 curl：一次 SendStreamingMessage，prompt 让 deep-research
 * 用 {@code render_comparison_table} 工具对比 DeepSeek V4 PRO 与 GLM 5.2 的输入定价
 * 与上下文窗口。期望终态 COMPLETED，artifact 同时命中两个专有名（DeepSeek / GLM）
 * 与至少一个话题词（定价 / 价格 / 上下文 / context_window / token），且不含已知 bug
 * 标志串。
 *
 * <p><b>Precondition (§7 注释)</b>：本场景与 §6 同源 bug——测试前需要算子先手工重启
 * deep-research agent（并保证启动脚本已带 {@code SANDBOX_ENABLED=true} 等 env）。
 * 因此本类 {@code @Tag("manual")} 让 CI 默认不跑；同时用 {@link Assumptions#assumeTrue}
 * 做 agent 探活兜底：若 remote agent 不可达，用例被跳过而非 FAIL。若 bug 未被算子重启
 * 修复，artifact 会命中 {@code deep_agent_task_1 already exists} 标志串，用例 FAIL
 * （与 DA-02/03/04/06 口径一致）。
 *
 * <p><b>限制说明</b>：本档不严格证明"沙箱真的执行了 Python"——LLM 也可能凭记忆合成
 * markdown 表格而不真调 {@code render_comparison_table}。从 artifact 层面难以区分。
 * 要严格证明沙箱执行，需看服务端 sandbox 日志或 SANDBOX_URL 侧访问计数（SUT 内部
 * 可观测性，SIT 端无法直触）。本档定位为业务级 smoke，参见 DA-07-sandbox-tools.md §9。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("manual")
class SandboxToolsTest extends BaseManagedStackTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final long SEND_TIMEOUT_MS = 300_000;

    private static final String USER_INPUT =
            "对比 DeepSeek V4 PRO 与 GLM 5.2 的输入定价和上下文窗口，用 render_comparison_table 出对比表";

    /** 两个对比对象——都必须在 artifact 里出现，任缺一即 agent 未完整回答。 */
    private static final String SUBJECT_DEEPSEEK = "DeepSeek";
    private static final String SUBJECT_GLM = "GLM";

    /** 话题词候选——至少命中一个，代表 artifact 落到业务主题（而不是只念了两个名字）。 */
    private static final List<String> TOPIC_TOKENS =
            List.of("定价", "价格", "上下文", "context_window", "token");

    private static final String BUG_MARKER_TASK_EXISTS = "deep_agent_task_1 already exists";
    private static final String BUG_MARKER_CONTROLLER_ERR = "controller task parameter error";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // 默认 streaming(true) —— §7 手工脚本走 SendStreamingMessage。
        return SutStack.builder(config).agent(DEEP_RESEARCH);
    }

    @Test
    @DisplayName("DA-07: 流式沙箱工具 render_comparison_table — artifact 覆盖 DeepSeek + GLM + 至少一个话题词")
    void sandboxRenderComparisonTableProducesBothSubjectsAndTopic() {
        A2aServiceClient a2a = client(DEEP_RESEARCH);
        assumeAgentReachable(a2a);

        // 每次跑用独立 contextId，避免 SUT 记忆缓存串扰上次跑。
        String runSuffix = "-" + UUID.randomUUID().toString().substring(0, 8);
        String contextId = "ctx-da07-sandbox" + runSuffix;

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

        TaskState terminalState;
        try {
            terminalState = collector.awaitTerminalState(SEND_TIMEOUT_MS);
        } catch (ConditionTimeoutException timeout) {
            Throwable err = streamError.get();
            if (err != null) {
                fail("DA-07: awaitTerminalState 超时且 stream 期间发生异常 — contextId=" + contextId, err);
            }
            fail("DA-07: awaitTerminalState 纯超时（无 stream 异常）— contextId=" + contextId, timeout);
            return;
        }

        assertThat(terminalState).as("DA-07.A: 终态应为 COMPLETED")
                .isEqualTo(TaskState.TASK_STATE_COMPLETED);

        String artifactText = collector.collectArtifactText();
        assertThat(artifactText).as("DA-07.A: artifact 文本非空").isNotBlank();

        // bug 断言 —— 与 §6 同源，命中即"是否忘了按 §7 说明手工重启 agent？"。
        assertThat(artifactText)
                .as("DA-07.A: artifact 不应包含 bug 标志（是否忘了按 §7 说明手工重启 agent？）\nhead: %s",
                        truncate(artifactText, 300))
                .doesNotContain(BUG_MARKER_TASK_EXISTS)
                .doesNotContain(BUG_MARKER_CONTROLLER_ERR);

        // DA-07.B: 两个对比对象必须同时命中。
        assertThat(artifactText)
                .as("DA-07.B: artifact 应同时命中两个对比对象 '%s' + '%s'\nhead: %s",
                        SUBJECT_DEEPSEEK, SUBJECT_GLM, truncate(artifactText, 400))
                .contains(SUBJECT_DEEPSEEK)
                .contains(SUBJECT_GLM);

        // DA-07.C: 至少一个话题词命中——避免只念名字不谈主题。
        boolean hitAnyTopic = TOPIC_TOKENS.stream().anyMatch(artifactText::contains);
        assertThat(hitAnyTopic)
                .as("DA-07.C: artifact 应命中至少一个话题词 %s\nhead: %s",
                        TOPIC_TOKENS, truncate(artifactText, 400))
                .isTrue();
    }

    private void assumeAgentReachable(A2aServiceClient a2a) {
        try {
            Assumptions.assumeTrue(a2a.getAgentCard() != null,
                    "deep-research 不可达（getAgentCard() 返 null）— 场景 7 需要算子先手工重启 agent + 保证 sandbox 就绪。");
        } catch (RuntimeException e) {
            Assumptions.abort(
                    "deep-research 不可达: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
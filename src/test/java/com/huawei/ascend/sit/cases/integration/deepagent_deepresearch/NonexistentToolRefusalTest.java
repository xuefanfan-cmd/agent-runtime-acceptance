package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * FEAT-001.nonexistent-tool-refusal — 用户请求调用不存在的工具时,LLM 应正常完成任务并如实告知工具不存在.
 *
 * <p><b>特性依据</b>(FEAT-001 明文 MUST):
 * <ul>
 *   <li>§5.1.6 —— COMPLETED 语义:任务已完成、无进一步动作。用户请求的"工具不存在"不是 handler
 *       内部异常,而是 LLM 认知层面的推理结论;LLM 拒答/澄清仍属正常业务结论,应走 COMPLETED
 *       路径,不应包装成 FAILED。</li>
 *   <li>§2 能力表「JSON-RPC 错误表面」—— 此路径**不**触发错误表面(反例基线):既非
 *       handler exception 也非 runtime error,只是业务侧回答。</li>
 * </ul>
 *
 * <p><b>断言层次</b>(spec §5.1.6 硬 MUST):
 * <ol>
 *   <li><b>层 1</b>: 终态 == COMPLETED —— 拒答工具属于正常业务完成,不能走 failed 家族。</li>
 *   <li><b>层 2</b>: artifact 文本包含目标工具名 {@code __sit_fault_probe_nonexistent_tool__}
 *       —— 证明 LLM 确实认知到了具体请求,不是空洞泛化回答。</li>
 *   <li><b>层 3</b>: artifact 文本至少命中一个「工具不存在/不可用」的关键词
 *       —— 证明 LLM 给出了正确的业务结论,而不是幻觉调用成功。</li>
 * </ol>
 *
 * <p><b>与 {@code DownstreamAgentKilledMidStreamTest} 的边界</b>:本用例走"业务层拒答"分支,
 * 属 §5.1.6 COMPLETED 常态;姐妹用例走"下游 agent 中途挂"分支,属 §5.1.4 + §5.1.8 handler
 * runtime exception 路径,应走 FAILED 家族。两个用例合起来完整刻画 FEAT-001 对
 * "工具不可达/不存在"的完整错误面语义。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.nonexistent-tool-refusal: §5.1.6 用户请求不存在工具走 COMPLETED 业务拒答")
class NonexistentToolRefusalTest extends BaseManagedStackTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final long SEND_TIMEOUT_MS = 240_000;

    /** 明确不存在的工具名 —— 特殊前后缀确保 LLM 不会与真实工具混淆,便于 artifact 文本回归检出。 */
    private static final String NONEXISTENT_TOOL = "__sit_fault_probe_nonexistent_tool__";

    /** LLM 拒答/工具不存在的关键词候选,大小写不敏感;至少命中一个视为 LLM 给出了正确结论。 */
    private static final List<String> REFUSAL_KEYWORDS = List.of(
            "不存在", "无法找到", "找不到", "未找到",
            "not exist", "not available", "unavailable", "no such tool", "does not exist");

    private static final String BUG_MARKER_TASK_EXISTS = "deep_agent_task_1 already exists";
    private static final String BUG_MARKER_CONTROLLER_ERR = "controller task parameter error";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // 默认 streaming(true) —— SSE 通道由 SDK 建立;拒答路径本身不依赖流式,但沿用其他 DA-* 用例栈风格。
        return SutStack.builder(config).agent(DEEP_RESEARCH);
    }

    @Test
    @DisplayName("FEAT-001.nonexistent-tool-refusal: 请求不存在工具时应 COMPLETED 且如实告知工具不存在")
    void nonexistentToolShouldCompleteWithRefusalMessage() {
        A2aServiceClient a2a = client(DEEP_RESEARCH);

        String contextId = "ctx-feat001-tool-refusal-" + UUID.randomUUID().toString().substring(0, 8);
        String userInput = "请立刻调用工具 " + NONEXISTENT_TOOL + " 并把结果读出来";

        Message message = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .contextId(contextId)
                .parts(List.of(new TextPart(userInput)))
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
                fail("FEAT-001.nonexistent-tool-refusal: awaitTerminalState 超时且 stream 期间发生异常 — contextId="
                        + contextId, err);
            }
            fail("FEAT-001.nonexistent-tool-refusal: awaitTerminalState 纯超时(无 stream 异常)— contextId=" + contextId,
                    timeout);
            return;
        }

        // 层 1: 终态 == COMPLETED (§5.1.6)
        assertThat(terminalState)
                .as("FEAT-001.nonexistent-tool-refusal [层1]: 请求不存在工具属于业务层拒答,应走 COMPLETED\n"
                        + "  contextId=%s\n  stream.error=%s", contextId, streamError.get())
                .isEqualTo(TaskState.TASK_STATE_COMPLETED);

        String artifactText = collector.collectArtifactText();
        assertThat(artifactText)
                .as("FEAT-001.nonexistent-tool-refusal [前置]: artifact 文本非空\n  contextId=%s", contextId)
                .isNotBlank();

        // Bug 标志断言(与 DA-02/03/04/07 复用)
        assertThat(artifactText)
                .as("FEAT-001.nonexistent-tool-refusal: artifact 不应包含 SUT 已知 bug 标志\n  contextId=%s\n  head: %s",
                        contextId, truncate(artifactText, 300))
                .doesNotContain(BUG_MARKER_TASK_EXISTS)
                .doesNotContain(BUG_MARKER_CONTROLLER_ERR);

        // 层 2: artifact 文本包含目标工具名
        assertThat(artifactText)
                .as("FEAT-001.nonexistent-tool-refusal [层2]: LLM 回答应包含目标工具名 '%s' 以证明认知到具体请求\n"
                        + "  contextId=%s\n  head: %s",
                        NONEXISTENT_TOOL, contextId, truncate(artifactText, 400))
                .contains(NONEXISTENT_TOOL);

        // 层 3: artifact 文本至少命中一个「工具不存在/不可用」关键词
        String lowered = artifactText.toLowerCase(Locale.ROOT);
        boolean hitAnyKeyword = REFUSAL_KEYWORDS.stream()
                .map(kw -> kw.toLowerCase(Locale.ROOT))
                .anyMatch(lowered::contains);
        assertThat(hitAnyKeyword)
                .as("FEAT-001.nonexistent-tool-refusal [层3]: LLM 回答应至少命中一个「工具不存在/不可用」关键词 %s\n"
                        + "  contextId=%s\n  head: %s",
                        REFUSAL_KEYWORDS, contextId, truncate(artifactText, 400))
                .isTrue();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
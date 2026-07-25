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
 * FEAT-001.empty-text-input — 空文本输入必须被拒绝（从 §5.1.6 反推）.
 *
 * <p><b>Spec 依据</b>：
 * <ul>
 *   <li>version-scope §5.1.7「输入与元数据语义」<b>并未</b>对"空 TextPart"承诺任何具体行为
 *       （只谈 request-level / message-level metadata、user / session / correlation 派生）。</li>
 *   <li>version-scope §5.1.8 错误场景表<b>没有</b> empty-input / no-content 条目。</li>
 *   <li>version-scope §5.1.6 明文精神："handler 输出需要用户输入的中断时，Task 必须进入
 *       input-required 类语义，而不是伪装成 completed"。空 TextPart 属于"没有用户输入实质"
 *       的边界情况；由此反推：空输入不得被处理成 COMPLETED + agent 猜出的 artifact，否则等价
 *       于 §5.1.6 明文禁止的"伪装成 completed"。</li>
 *   <li>version-scope §5.1.2 只覆盖 JSON-RPC 及 A2A wire shape 校验；空 TextPart 是合法的
 *       TextPart（text = ""），wire shape 通过，不落 §5.1.2 的 invalid-request 范畴。</li>
 * </ul>
 *
 * <p><b>本用例的断言基线（spec-first）</b>：
 * <ul>
 *   <li>runtime <b>必须拒绝</b>空输入 —— 拒绝分支 A/B/C/D 任一皆合规（栈由外到内）：
 *     <ol>
 *       <li>A —— SDK 客户端 shape 校验：{@code a2a.sendMessage} 同步抛异常；</li>
 *       <li>B —— 服务端 JSON-RPC 拒绝：SDK 走异步失败回调（{@code errorHandler} 收到 Throwable）；</li>
 *       <li>C —— 无 sync ack：send 走完但 runtime 未产生任何 TaskState / taskId（等价拒绝）；</li>
 *       <li>D —— task 终态 FAILED / REJECTED / CANCELED，<b>或</b> COMPLETED+空 artifact
 *           （agent 未猜答案）。</li>
 *     </ol></li>
 *   <li><b>唯一 FAIL 分支</b>：D-COMPLETED 且 artifact 文本非空 —— 这就是 §5.1.6 明文
 *       禁止的"伪装成 completed"。</li>
 * </ul>
 *
 * <p><b>为什么不硬钉具体分支</b>：spec 允许 A/B/C/D 任一拒绝方式，栈上不同层（SDK / JSON-RPC
 * 分发器 / task handler）都可以合法承接拒绝责任。硬钉当前观察到的分支 = 把 SUT 现状当契约，
 * 属于 spec 之外的额外约束；未来 SUT 内部重构（比如把校验从 send 阶段挪到 handler 层）不应
 * 引发 SIT 红。
 *
 * <p><b>不断言</b>：具体 HTTP status（400 vs 200+error body）、具体 error code / message
 * 措辞、具体异常类的全限定名 —— 这些都是 spec 未承诺项。
 *
 * <p>与 {@link SyncSendMessageTest} 同"sync ack + REST 轮询"两阶段模式。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.empty-text-input: §5.1.6 反推空文本输入拒绝语义")
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
    @DisplayName("FEAT-001.empty-text-input: 空 TextPart 必须被 runtime 拒绝；"
            + "唯一 FAIL 分支是 COMPLETED + agent 猜答案（§5.1.6 反推）")
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
        AtomicReference<String> reachedBranch = new AtomicReference<>();
        AtomicReference<String> branchDetail = new AtomicReference<>();
        List<BiConsumer<ClientEvent, AgentCard>> consumers = List.of(collector.createConsumer());
        Consumer<Throwable> errorHandler = sendError::set;

        try {
            a2a.sendMessage(message, consumers, errorHandler);
        } catch (RuntimeException ex) {
            reachedBranch.set("A");
            branchDetail.set(ex.getClass().getName() + ": " + truncate(ex.getMessage(), 200));
        }

        if (reachedBranch.get() == null && sendError.get() != null) {
            Throwable err = sendError.get();
            reachedBranch.set("B");
            branchDetail.set(err.getClass().getName() + ": " + truncate(err.getMessage(), 200));
        }

        if (reachedBranch.get() == null) {
            TaskState ackState = collector.awaitAnyTaskState(SYNC_ACK_TIMEOUT_MS);
            if (ackState == null) {
                reachedBranch.set("C-noAck");
                branchDetail.set("no ack in " + SYNC_ACK_TIMEOUT_MS + "ms");
            } else {
                String taskId = collector.findFirstTaskId();
                if (taskId == null || taskId.isBlank()) {
                    reachedBranch.set("C-noTaskId");
                    branchDetail.set("ack=" + ackState + " but no taskId");
                } else {
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
                    String artifactText = TaskTextExtractor.textOf(terminalTask);
                    boolean artifactBlank = artifactText == null || artifactText.isBlank();
                    reachedBranch.set("D-" + terminalState + (artifactBlank ? "-empty" : "-nonempty"));
                    branchDetail.set("terminal=" + terminalState
                            + " artifact.isBlank=" + artifactBlank
                            + " artifact.head=" + truncate(artifactText, 200)
                            + " taskId=" + terminalTask.id());
                }
            }
        }

        // spec-first 断言：任何拒绝分支合规，唯一禁止的是 §5.1.6 反推 "COMPLETED + agent 猜答案"。
        String branch = reachedBranch.get();
        assertThat(branch)
                .as("FEAT-001.empty-text-input: 至少要落进某个已知拒绝分支.\n"
                        + "  contextId=%s\n  branchDetail=%s", contextId, branchDetail.get())
                .isNotNull();

        assertThat(branch)
                .as("FEAT-001.empty-text-input: §5.1.6 明文禁止把无实质输入包装成 COMPLETED + "
                        + "agent 猜答案（等价于伪装成 completed）；任何其他拒绝分支合规.\n"
                        + "  contextId=%s\n  branchDetail=%s", contextId, branchDetail.get())
                .isNotEqualTo("D-TASK_STATE_COMPLETED-nonempty");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}

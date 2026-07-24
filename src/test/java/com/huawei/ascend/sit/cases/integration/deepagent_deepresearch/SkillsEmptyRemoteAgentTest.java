package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.mock.MockRemoteAgentServer;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * FEAT-004.remote-agent-no-skills-not-installed — 远端 Agent Card 的 {@code skills=[]} 时,
 * agent-runtime 的 Card Cache <b>不应</b>为其生成 {@code RemoteAgentToolSpec},该远端对 LLM 不可见.
 *
 * <p><b>Spec 依据</b>(primary source 已 verbatim 核对):
 * <ul>
 *   <li><b>L2 §3.1 远程 Agent 配置接入</b>({@code architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-004-remote-agent-orchestration.md}
 *       line 120)明标 <b>⚠️ 关键约束</b>:「没有 skills 的 Agent Card 不会被 LLM 作为 Tool 调用。
 *       如果远端 Agent Card 的 skills 字段为空或不存在,Card Cache 不会为其生成
 *       {@code RemoteAgentToolSpec},该 Agent 对 LLM 不可见。」</li>
 *   <li><b>L2 §2.1 能力清单</b>(同档 line 64):「RemoteAgentToolSpec 生成 —— 从 Card skills 生成,
 *       <b>无 skills 的 Agent Card 不会被注入为 Tool</b>。」</li>
 *   <li><b>version-scope §2.1 能力清单</b>({@code version-scope/FEAT-004-remote-agent-orchestration.md}
 *       line 30):同款转述。</li>
 * </ul>
 *
 * <p><b>拓扑</b>:
 * <pre>
 *   [test] --SendMessage--> [deep-research :SutStack] --card fetch + A2A call--> [MockRemoteAgentServer :random]
 *                            (真 SUT 进程)                                        (JDK HttpServer;skills=[])
 * </pre>
 *
 * <p><b>断言层次</b>(严格按 spec §3.1 关键约束):
 * <ol>
 *   <li><b>层 1(核心,确定性)</b>:{@code mock.a2aPostCount() == 0} —— SUT 从未 route 到远端,
 *       证明 tool <b>未</b>被注入 LLM。这是 L2 §3.1 ⚠️ 关键约束的直接可观测硬信号。</li>
 *   <li><b>层 2(前置一致性)</b>:{@code mock.cardGetCount() >= 1} —— SUT 至少拉过一次 card,
 *       证明 mock 的 URL 生效、SUT 走到了 Card Cache 分支;若为 0,说明 {@code SEARCH_AGENT_URL}
 *       没被 SUT 消费(触发前提不满足,层 1 无效)。</li>
 *   <li><b>层 3(终态健康度)</b>:任务应在超时前收束到某终态(非 stream 卡死)。<b>不断言</b>具体
 *       终态类型:LLM 可能 COMPLETED-with-hallucination(用记忆答)或 COMPLETED-with-refusal
 *       (承认没工具),两者对 spec §3.1 都合规 —— 关键是 layer 1 的 0-POST。</li>
 * </ol>
 *
 * <p><b>不断言</b>:
 * <ul>
 *   <li>artifact 里是否含"不存在/不可用"关键词 —— 依赖 LLM 行为,doc §9.1 明标 INCONCLUSIVE 风险
 *       (LLM 可能直接从记忆答,不承认工具缺失)。层 1 已经够狠,不需要 layer 3 层弱信号背书。</li>
 *   <li>终态是否 COMPLETED —— 未定义行为:planner 拿不到期望工具时可能 fail 也可能 complete-with-fallback,
 *       spec §3.1 没规定。</li>
 * </ul>
 *
 * <p><b>预期</b>:
 * <ul>
 *   <li>SUT 若合规 → 层 1/2/3 全绿。</li>
 *   <li>SUT 若违反(把 skills=[] 的远端也注册成 tool)→ LLM 会尝试 route 到 mock/a2a →
 *       {@link MockRemoteAgentServer#a2aPostCount()} > 0 → 层 1 红,{@code a2aPostBodies}
 *       调试用可看请求内容。</li>
 * </ul>
 *
 * <p><b>Tag 说明</b>:{@code manual} —— 需本地就绪 deep-research jar;不需要真 search jar
 * (mock 顶替)。CI 具备 deep-research jar 后可去 manual。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-004")
@Tag("manual")
@Feature("FEAT-004: 任务驱动远程智能体调用")
@Story("da.skills-empty-not-installed: L2 §3.1 skills=[] 不被 Card Cache 装配为 tool")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SkillsEmptyRemoteAgentTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final long SEND_TIMEOUT_MS = 240_000;

    /**
     * 明确请求"搜索"类型能力的 prompt —— 让 planner 有强烈动机去调用远端 search tool。
     * 若 SUT 合规(tool 未注入),planner 拿不到路由,应走其他兜底路径;
     * 若 SUT 违反(tool 被误注入),planner 会向 mock 打 POST,layer 1 立刻红。
     */
    private static final String USER_INPUT =
            "帮我搜索 2026 年 7 月 15 日全球黄金价格盘中最高价的准确数字,直接给出数字和单位";

    private TestConfig config;
    private MockRemoteAgentServer mock;
    private SutStack deepStack;

    @BeforeAll
    void startStack() throws IOException {
        config = TestConfig.load();
        // 先起 mock 拿到 baseUrl,再把 URL 注入 deep-research 的 SEARCH_AGENT_URL env
        mock = MockRemoteAgentServer.builder()
                .name("MockSearchAgent")
                .description("SIT mock — skills=[] should never be installed as tool")
                .emptySkills()
                .start();
        deepStack = SutStack.builder(config)
                .agent(DEEP_RESEARCH, a -> a.env("SEARCH_AGENT_URL", mock.baseUrl()))
                .start();
    }

    @AfterAll
    void tearDown() {
        // 反向拆:deep-research 先关(它是 mock 的 client),mock 后关。
        if (deepStack != null) {
            deepStack.close();
        }
        if (mock != null) {
            mock.close();
        }
    }

    @Test
    @DisplayName("FEAT-004.remote-agent-no-skills-not-installed: skills=[] 远端不应被注入为 LLM tool "
            + "(mock /a2a 零 POST)")
    void skillsEmptyRemoteMustNotBeInstalledAsTool() {
        A2aServiceClient a2a = deepStack.client(DEEP_RESEARCH);

        String contextId = "ctx-feat004-skills-empty-" + UUID.randomUUID().toString().substring(0, 8);
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
                fail("FEAT-004.remote-agent-no-skills-not-installed: awaitTerminalState 超时且 stream 期间发生异常\n"
                        + "  contextId=" + contextId, err);
            }
            fail("FEAT-004.remote-agent-no-skills-not-installed: awaitTerminalState 纯超时(无 stream 异常)—"
                    + " SUT 未按 §5.1.4 收束\n  contextId=" + contextId, timeout);
            return;
        }

        // 层 2(前置):SUT 至少拉过一次 card,证明 mock URL 已被 SUT 消费到 Card Cache 分支
        assertThat(mock.cardGetCount())
                .as("FEAT-004.remote-agent-no-skills-not-installed [层2 前置]: SUT 应至少拉取一次 mock 的 "
                        + ".well-known/agent-card.json;若为 0 说明 SEARCH_AGENT_URL 未被 SUT 消费,"
                        + "触发前提不满足,层 1 结果无效\n"
                        + "  contextId=%s\n  mock.baseUrl=%s", contextId, mock.baseUrl())
                .isGreaterThanOrEqualTo(1);

        // 层 1(核心 L2 §3.1 ⚠️ 关键约束):mock /a2a 零 POST —— tool 未被注入 LLM 的硬信号
        assertThat(mock.a2aPostCount())
                .as("FEAT-004.remote-agent-no-skills-not-installed [层1 核心]: skills=[] 远端 tool 不应被"
                        + "注入 LLM (L2 §3.1 ⚠️ 关键约束);mock /a2a 观测到 POST → SUT 违反约束\n"
                        + "  contextId=%s\n  mock.baseUrl=%s\n  mock.cardGetCount=%d\n"
                        + "  mock.a2aPostBodies(head)=%s\n  terminalState=%s\n  stream.error=%s",
                        contextId, mock.baseUrl(), mock.cardGetCount(),
                        truncatePostBodies(mock.a2aPostBodies()), terminalState, streamError.get())
                .isZero();

        // 层 3(终态健康度):非 stream 卡死;不硬断具体终态类型
        assertThat(terminalState)
                .as("FEAT-004.remote-agent-no-skills-not-installed [层3]: 终态应可解析(非超时)\n"
                        + "  contextId=%s\n  stream.error=%s", contextId, streamError.get())
                .isNotNull();
    }

    private static String truncatePostBodies(List<String> bodies) {
        if (bodies.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < bodies.size(); i++) {
            if (i > 0) sb.append(", ");
            String b = bodies.get(i);
            sb.append(b.length() <= 300 ? b : b.substring(0, 300) + "...");
        }
        sb.append("]");
        return sb.toString();
    }
}

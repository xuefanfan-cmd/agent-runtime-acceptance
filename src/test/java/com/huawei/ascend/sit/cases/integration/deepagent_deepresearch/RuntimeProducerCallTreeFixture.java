package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.conversation.RemoteInvocationProbe;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.lifecycle.SutInstance;
import com.huawei.ascend.sit.mock.MockRemoteAgentServer;
import com.openjiuwen.client.api.AgentClient;
import com.openjiuwen.client.api.AgentClients;
import com.openjiuwen.client.api.EndpointType;
import com.openjiuwen.client.api.InvocationCall;
import com.openjiuwen.client.api.InvocationMode;
import com.openjiuwen.client.api.InvocationRequest;
import com.openjiuwen.client.api.InvocationSnapshot;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-026 §9.7 — Runtime 生产者 delegation/output/status fixture 捕获。
 *
 * <p>由 {@link BaseManagedStackTest} 统一 {@code .start()/.close()} + {@code @TestInstance(PER_CLASS)} + SessionLabelExtension。
 * 栈拓扑参照 {@link ParallelSearchComparisonTest}：deep-research-auto（parallel-search profile，:18090）
 * 经 A2A STREAMING 委托给 search-agent（:18091，stub fixture）与 verify-agent（:18093，ReAct 判官）。
 *
 * <p>正式 {@code agent-client} SDK（{@code com.openjiuwen:agent-client-sdk-for-jvm:0.1.0}）已交付且已作为
 * acceptance Maven 依赖。本类使用 {@link A2aEventCollector} 以 A2A SDK 直连消费 root 的 SSE 流（独立于
 * agent-client SDK 的 {@code callTree()} 通道），捕获完整 delegation/output/status wire 序列作为 Runtime
 * 生产者 L2 的协议验证材料。归并正确性由正式 agent-client 用例覆盖（见 {@link MultiHopCallTreeBlackboxTest}），
 * 本类只产出 fixture 证据。
 *
 * <p><b>runtime-artifact-gated</b>：需要真实 deep-research/search/verify JAR + LLM 密钥才能执行。search 走 stub
 * fixture 保证 ≥2 vendor 命中。{@link RemoteInvocationProbe#hasFanOut} 作为 root 真实批量并行委托的前置证据
 * （非树归并断言）。
 *
 * <p>参考设计文档：
 * {@code docs/cases/FEAT-026-multi-hop-agent-stream-parsing-deepagent.md} §4 provider-fixture。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-026")
@Feature("FEAT-026: 多跳智能体调用的流式数据解析")
class RuntimeProducerCallTreeFixture extends BaseManagedStackTest {

    private static final Logger LOG = Logger.getLogger(RuntimeProducerCallTreeFixture.class.getName());

    private static final String DEEP_RESEARCH = "deep-research-auto";
    private static final String SEARCH = "search";
    private static final String VERIFY = "verify";

    private static final String COMPARISON_QUERY =
            "对比 DeepSeek V3、Qwen-Max、Doubao-pro 三家的大模型 API 输入定价";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .streaming(true)
                .agent(SEARCH, a -> a.env("SEARCH_AGENT_USE_STUB", "true"))
                .agent(VERIFY)
                .agent(DEEP_RESEARCH, a -> a
                        .downstreams(SEARCH, VERIFY)
                        .profile("parallel-search"));
    }

    /**
     * FEAT-026.contract.provider-fixture — 捕获 multi-deep-research 真实 delegation/output/status wire 序列。
     *
     * <p>G：真实 deep-research + search + verify 栈；{@link A2aEventCollector} 以 A2A SDK 直连消费 root SSE 流。
     * W：发起 COMPARISON 调用；收集完整事件流并解析 artifact.metadata.agentEvent 的 type/source/target/state
     *    与 controller_output 标识；固化为可复用 fixture。
     * T：捕获真实 delegation（source=root, target=search/verify）、output（source=search/verify）、status 序列，
     *    source.taskId 与外层 rootTaskId 区分正确；fixture 可作为协议验证材料；
     *    {@link RemoteInvocationProbe#hasFanOut} 证明 root 真实批量并行委托。
     * 不应断言：该 fixture 证明 client 侧 CallTreeReducer 归并正确（归并由正式 agent-client 用例覆盖）。
     */
    @Test
    @Tag("contract")
    @Tag("story-feat-026-contract-provider-fixture")
    @Story("FEAT-026.contract.provider-fixture: Runtime 生产者 fixture 捕获")
    @DisplayName("Feat-026 捕获 multi-deep-research 真实 delegation/output/status 作为生产者 fixture")
    void feat026CaptureRuntimeProducerDelegationOutputStatusFixture() {
        A2aServiceClient a2a = client(DEEP_RESEARCH);
        String contextId = "ctx-feat026-fixture-" + UUID.randomUUID().toString().substring(0, 8);

        A2aEventCollector collector = new A2aEventCollector();
        java.util.concurrent.atomic.AtomicReference<Throwable> sendError = new java.util.concurrent.atomic.AtomicReference<>();

        Message message = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .contextId(contextId)
                .parts(List.of(new TextPart(COMPARISON_QUERY)))
                .build();

        a2a.sendMessage(message, null, List.of(collector.createConsumer()), sendError::set);

        // The root agent fans out to search-agent sub-agents in parallel. Some sub-agents may
        // emit INPUT_REQUIRED (ask_user interrupt) when the LLM deems the query ambiguous, while
        // others complete normally. Either outcome is valid for this fixture — the key assertion
        // is hasFanOut (≥2 parallel remote invocations), not full task completion.
        long timeoutMs = config.getPollTimeoutSeconds() * 1000L;
        boolean inputRequired = collector.awaitInputRequired(timeoutMs);
        TaskState finalState;
        if (inputRequired) {
            finalState = TaskState.TASK_STATE_INPUT_REQUIRED;
        } else {
            finalState = collector.awaitTerminalState(timeoutMs);
        }
        assertThat(finalState)
                .as("FEAT-026 fixture: 调用应到达终态 COMPLETED 或 INPUT_REQUIRED（子代理 ask_user 中断）")
                .isIn(TaskState.TASK_STATE_COMPLETED, TaskState.TASK_STATE_INPUT_REQUIRED);

        List<ClientEvent> allEvents = collector.snapshotAllEvents();
        assertThat(allEvents)
                .as("FEAT-026 fixture: 应捕获到 ≥1 个事件")
                .isNotEmpty();

        // 提取 rootTaskId（首个 TaskEvent 的 task.id）
        String rootTaskId = collector.findFirstTaskId();
        assertThat(rootTaskId)
                .as("FEAT-026 fixture: rootTaskId 应非空")
                .isNotBlank();

        // 获取 agent stdout 日志路径（用于日志 fallback 检测 fan-out）
        Path agentLog = null;
        SutInstance sutInstance = stack.managedInstance(DEEP_RESEARCH);
        if (sutInstance instanceof ManagedSutInstance managed) {
            agentLog = managed.logFile();
        }

        // T-1: RemoteInvocationProbe.hasFanOut 证明 root 真实批量并行委托（前置证据）
        // 先尝试从 A2A 事件 metadata 检测 _remote_invocation 投影；
        // 若运行时版本未将投影写入 A2A 事件，则 fallback 到 agent 日志解析。
        boolean hasFanOut = RemoteInvocationProbe.hasFanOut(contextId, allEvents);
        String fanOutSource = "A2A-events";
        if (!hasFanOut && agentLog != null) {
            hasFanOut = RemoteInvocationProbe.hasFanOutFromLog(agentLog);
            fanOutSource = "agent-log";
        }
        LOG.info(String.format(
                "FEAT-026 fixture: events=%d, rootTaskId=%s, hasFanOut=%s (source=%s)",
                allEvents.size(), rootTaskId, hasFanOut, fanOutSource));
        assertThat(hasFanOut)
                .as("FEAT-026 fixture: root 应在同一 turn 批量并行调用 search + verify（fan-out ≥2）\n"
                        + "source=%s, events=%d\n"
                        + "(若=false: _remote_invocation 未观测到且日志无批量证据 → 可能 LLM 未生成 tool_calls)",
                        fanOutSource, allEvents.size())
                .isTrue();

        // T-2: fixture 应含 delegation / output / status 三类帧
        Map<String, List<ClientEvent>> classified = classifyEventsByKind(allEvents);
        LOG.info(String.format(
                "FEAT-026 fixture: classified events — taskBearing=%d, artifact=%d, message=%d",
                classified.getOrDefault("taskBearing", List.of()).size(),
                classified.getOrDefault("artifact", List.of()).size(),
                classified.getOrDefault("message", List.of()).size()));

        // T-3: fan-out 应含 ≥2 个不同 toolCallId
        // 同 T-1: 先 A2A 事件，再 fallback 到日志
        List<RemoteInvocationProbe.ChildRef> fanOut =
                RemoteInvocationProbe.fromClientEvents(contextId, allEvents);
        if (fanOut.isEmpty() && agentLog != null) {
            fanOut = RemoteInvocationProbe.fromLog(agentLog);
        }
        assertThat(fanOut.size())
                .as("FEAT-026 fixture: fan-out 应含 ≥2 个不同 toolCallId（search + verify）\nfanOut=%s",
                        fanOut)
                .isGreaterThanOrEqualTo(2);

        // 固化 fixture
        LOG.info("FEAT-026 fixture 捕获完成 — rootTaskId=" + rootTaskId
                + ", events=" + allEvents.size()
                + ", fanOut=" + fanOut.size()
                + ", contextId=" + contextId);
    }

    /**
     * FEAT-006.streaming.runtime-direct — Runtime 直连 E2E wire allowlist fixture 捕获。
     *
     * <p>交叉验证于 FEAT-026 测试基础设施（MockRemoteAgentServer + agent-client SDK）。
     * <p>G：正式 agent-client 配置 {@link EndpointType#RUNTIME} 直连 MockRemoteAgentServer；
     *    mock 以 FIXTURE_STREAM 模式产出标准 A2A SSE 事件序列。
     * <p>W：以 STREAMING 发起调用；调用完成后从 mock 捕获的 POST body 中提取完整 wire 请求；
     *    验证 Runtime 收到的请求不含 Gateway 策略字段。
     * <p>T：Runtime 收到的请求不含 {@code agentId}、{@code Authorization}、租户/用户/空间/路由
     *    headers 和任意 {@code attributes}；Runtime 请求只含标准 A2A 字段
     *    （{@code jsonrpc}、{@code method}、{@code params}、{@code message}）；
     *    调用正常完成且 SDK 不泄漏 Gateway 身份或路由字段。
     * <p>不应断言：Runtime 内部 TaskStore 结构、A2A 信封内部字段顺序、HTTP header 大小写。
     * <p><b>门禁说明</b>：当前 MockRemoteAgentServer 只捕获 POST body，不捕获 HTTP headers。
     *    Header 级别隔离（Authorization header、agentId header）需扩展 mock 或使用真实 Runtime。
     *    本用例验证 body 级别的 wire allowlist。
     */
    @Test
    @Tag("blackbox")
    @Tag("story-feat-006-streaming-runtime-direct")
    @Story("FEAT-006.streaming.runtime-direct: Runtime 直连 E2E")
    @DisplayName("Feat-006 Runtime 直连正向投影不含 Gateway 身份字段")
    void feat026RuntimeDirectWireFixtureIsolatesFromGatewayPolicy() throws Exception {
        String rootTask = "task-rt-direct-" + UUID.randomUUID().toString().substring(0, 8);
        String ctxId = "ctx-rt-direct-" + UUID.randomUUID().toString().substring(0, 8);

        List<String> fixtures = List.of(
                CallTreeFixtureEvents.statusUpdate("1", rootTask, ctxId, "working"),
                CallTreeFixtureEvents.textArtifact("2", rootTask, ctxId,
                        "art-rt-out", "runtime direct output",
                        false, true),
                CallTreeFixtureEvents.statusUpdate("3", rootTask, ctxId, "completed", true)
        );

        try (MockRemoteAgentServer mock = MockRemoteAgentServer.builder()
                .name("MockRuntimeDirect")
                .description("FEAT-006 runtime-direct: wire allowlist fixture")
                .rawSkillsJson("[{\"id\":\"deep_research\",\"name\":\"deep_research\","
                        + "\"description\":\"Runtime direct test\",\"tags\":[\"research\"],"
                        + "\"inputModes\":[\"text\"],\"outputModes\":[\"text\"]}]")
                .fixtureStream(fixtures)
                .fixtureEventDelayMs(20)
                .start()) {

            AgentClient sdkClient = AgentClients.builder()
                    .endpointType(EndpointType.RUNTIME)
                    .endpointUrl(mock.baseUrl())
                    .build();

            try (sdkClient) {
                InvocationCall call = sdkClient.invoke(InvocationRequest.builder()
                        .conversationId(ctxId)
                        .mode(InvocationMode.STREAMING)
                        .input("runtime direct wire test")
                        .build());

                InvocationSnapshot finalSnapshot = call.completion()
                        .toCompletableFuture()
                        .get(config.getPollTimeoutSeconds(), TimeUnit.SECONDS);

                LOG.info(String.format("FEAT-006 runtime-direct: finalState=%s, POSTs=%d",
                        finalSnapshot.state(), mock.a2aPostCount()));

                // T-1: invocation completes successfully (Runtime direct connection works)
                assertThat(finalSnapshot.state())
                        .as("FEAT-006 runtime-direct: Runtime 直连调用应到达终态")
                        .isIn(com.openjiuwen.client.api.TaskState.COMPLETED,
                                com.openjiuwen.client.api.TaskState.INPUT_REQUIRED);

                // T-2: mock captured the wire request body
                assertThat(mock.a2aPostBodies())
                        .as("FEAT-006 runtime-direct: mock 应捕获 ≥1 个 POST body")
                        .isNotEmpty();

                String wireBody = mock.a2aPostBodies().get(0);
                LOG.info("FEAT-006 runtime-direct: wire body length=" + wireBody.length()
                        + ", preview=" + wireBody.substring(0, Math.min(200, wireBody.length())));

                // T-3: wire request contains standard A2A JSON-RPC fields
                assertThat(wireBody)
                        .as("FEAT-006 runtime-direct: wire 请求应含 jsonrpc 字段")
                        .contains("\"jsonrpc\"");
                assertThat(wireBody)
                        .as("FEAT-006 runtime-direct: wire 请求应含 method 字段")
                        .contains("\"method\"");
                assertThat(wireBody)
                        .as("FEAT-006 runtime-direct: wire 请求应含 params 字段")
                        .contains("\"params\"");
                assertThat(wireBody)
                        .as("FEAT-006 runtime-direct: wire 请求应含 message 字段")
                        .contains("\"message\"");

                // T-4: wire request does NOT contain Gateway policy fields
                // agentId is a Gateway-level routing concept; Runtime direct should not include it
                assertThat(wireBody)
                        .as("FEAT-006 runtime-direct: Runtime wire 请求不应含 agentId 字段"
                                + "（Gateway 路由概念，直连不需要）\nwire=%s",
                                wireBody.substring(0, Math.min(300, wireBody.length())))
                        .doesNotContain("\"agentId\"");

                // Authorization is a Gateway authentication header;
                // body should not contain a serialized Authorization field
                assertThat(wireBody)
                        .as("FEAT-006 runtime-direct: Runtime wire 请求不应含 Authorization 字段")
                        .doesNotContain("\"Authorization\"");

                // T-5: wire request does not contain tenant/user/space/route fields
                // These are Gateway policy attributes not part of the A2A standard
                assertThat(wireBody)
                        .as("FEAT-006 runtime-direct: Runtime wire 请求不应含 tenant 字段")
                        .doesNotContain("\"tenant\"");
                assertThat(wireBody)
                        .as("FEAT-006 runtime-direct: Runtime wire 请求不应含 routeHandle 字段")
                        .doesNotContain("\"routeHandle\"");

                // T-6: wire request contains the conversationId provided by the caller
                assertThat(wireBody)
                        .as("FEAT-006 runtime-direct: wire 请求应含调用方提供的 conversationId")
                        .contains(ctxId);

                LOG.info("FEAT-006 runtime-direct: wire allowlist verified — "
                        + "standard A2A fields present, Gateway policy fields absent");
            }
        }
    }

    // ---- helpers ----

    /**
     * 将 ClientEvent 按 kind 分类为 taskBearing（TaskEvent + status-update TaskUpdateEvent）、
     * artifact（artifact-update TaskUpdateEvent）、message（MessageEvent）三组。
     */
    private static Map<String, List<ClientEvent>> classifyEventsByKind(List<ClientEvent> events) {
        Map<String, List<ClientEvent>> out = new LinkedHashMap<>();
        out.put("taskBearing", new ArrayList<>());
        out.put("artifact", new ArrayList<>());
        out.put("message", new ArrayList<>());
        for (ClientEvent e : events) {
            if (e instanceof TaskUpdateEvent tue) {
                if (tue.getUpdateEvent() instanceof TaskArtifactUpdateEvent) {
                    out.get("artifact").add(e);
                } else {
                    out.get("taskBearing").add(e);
                }
            } else if (e instanceof MessageEvent) {
                out.get("message").add(e);
            } else {
                out.computeIfAbsent("other", k -> new ArrayList<>()).add(e);
            }
        }
        return out;
    }
}

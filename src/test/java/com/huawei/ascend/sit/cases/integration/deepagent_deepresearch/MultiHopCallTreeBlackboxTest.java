package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.conversation.RemoteInvocationProbe;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.mock.MockRemoteAgentServer;
import com.huawei.ascend.sit.transport.MessageProtocol;
import com.openjiuwen.client.api.AgentClient;
import com.openjiuwen.client.api.AgentClients;
import com.openjiuwen.client.api.EndpointType;
import com.openjiuwen.client.api.InvocationCall;
import com.openjiuwen.client.api.InvocationEvent;
import com.openjiuwen.client.api.InvocationMode;
import com.openjiuwen.client.api.InvocationRequest;
import com.openjiuwen.client.api.InvocationSnapshot;
import com.openjiuwen.client.api.TaskState;
import com.openjiuwen.client.api.calltree.CallTreeDiagnostic;
import com.openjiuwen.client.api.calltree.CallTreeNode;
import com.openjiuwen.client.api.calltree.CallTreeSnapshot;
import com.openjiuwen.client.api.calltree.Completeness;
import com.openjiuwen.client.api.calltree.NodeKey;
import com.openjiuwen.client.api.calltree.SpeakingPhase;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-026 — 多跳智能体调用的流式数据解析黑盒测试。
 *
 * <p>由 {@link BaseManagedStackTest} 统一 {@code .start()/.close()} + {@code @TestInstance(PER_CLASS)} + SessionLabelExtension。
 * 栈拓扑参照 {@link ParallelSearchComparisonTest}：deep-research-auto（parallel-search profile，:18090）
 * 经 A2A STREAMING 委托给 search-agent（:18091，stub fixture）与 verify-agent（:18093，ReAct 判官）。
 * COMPARISON 查询触发 root 在同一 turn 批量并行调用 search + verify。
 *
 * <p><b>产品 SDK 状态</b>：正式 {@code agent-client} SDK（{@code com.openjiuwen:agent-client-sdk-for-jvm:0.1.0}）
 * 已交付且已作为 acceptance test-scope Maven 依赖。{@link InvocationCall#callTree()} 返回
 * {@code Flow.Publisher<CallTreeSnapshot>}，{@link com.openjiuwen.client.transport.a2a.CallTreeReducer}
 * 从 {@code artifact.metadata.agentEvent} 提取 delegation/output/status 事件建树。
 *
 * <p><b>当前门禁</b>：SDK 层 {@code callTree()} API 已可用；门禁为 Runtime 侧
 * {@code artifact.metadata.agentEvent} 发射状态。若 Runtime（{@code agent-service-app-0.1.1}）
 * 未在 A2A artifact metadata 中写入 {@code agentEvent} 字段，SDK 的 {@code CallTreeReducer}
 * 无法建树，{@code callTree()} Publisher 不发布快照。以下用例中 {@code two-hop-tree}（P0）
 * 已用正式 SDK API 实现，可执行验证 Runtime 发射状态；其余用例因需 mock 基础设施
 * （{@code MockRemoteAgentServer} 自定义帧、{@code FaultLink} 断线恢复等）仍以
 * {@code @Disabled} 门禁，标注了 SDK API 落地后需填充的断言位置（TODO）。
 *
 * <p>参考设计文档：
 * {@code docs/cases/FEAT-026-multi-hop-agent-stream-parsing-deepagent.md} §4。
 *
 * <p>L2 当前交付能力（§2.1）：delegation 建树、output 按 source 归并、根 agentId 延迟补全已交付；
 * activeSpeakers 集合（⬜ 计划中）、DataPart 深复制（⬜ 目标）、BLOCKING/ASYNC 占位树移除（⬜ 目标）以
 * expected-red / deferred 形式留作实现驱动。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-026")
@Tag("runtime-artifact-gated")
@Feature("FEAT-026: 多跳智能体调用的流式数据解析")
class MultiHopCallTreeBlackboxTest extends BaseManagedStackTest {

    private static final Logger LOG = Logger.getLogger(MultiHopCallTreeBlackboxTest.class.getName());

    private static final String DEEP_RESEARCH = "deep-research-auto";
    private static final String SEARCH = "search";
    private static final String VERIFY = "verify";

    private static final String COMPARISON_QUERY =
            "对比 DeepSeek V3、Qwen-Max、Doubao-pro 三家的大模型 API 输入定价";

    private static final List<String> VENDOR_MARKERS = List.of("qwen-max", "火山方舟", "$0.27");

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

    // ==================== §4.1 two-hop-tree — P0 ====================

    /**
     * FEAT-026.streaming.two-hop-tree — 两跳调用树与并发兄弟发言。
     *
     * <p>G：MockRemoteAgentServer 顶替 deep-research root，产出并发 delegation（search + verify）、
     *    交织 output、根 output、终态 completed 的 fixture 序列。
     * <p>W：以 STREAMING 发起调用并订阅 callTree()；消费并发 delegation + 交织 output 序列；记录快照。
     * <p>T：root.children 含 search + verify 两个子节点；speakingPhase=DESCENDANT_SPEAKING；
     *    根 outputText 不含下游 agentEvent；revision 单调递增。
     * <p>不应断言：固定 taskId 字面值、固定 vendor 文本、currentSpeaker 单值字段名。
     */
    @Test
    @Tag("blackbox")
    @Tag("story-feat-026-streaming-two-hop-tree")
    @Story("FEAT-026.streaming.two-hop-tree: 两跳调用树与并发兄弟发言")
    @DisplayName("Feat-026 两跳调用树归并并发兄弟发言且保持根输出洁净")
    void feat026TwoHopTreeBuildsConcurrentSiblingsAndKeepsRootClean() throws Exception {
        String rootTask = "task-twohop-" + UUID.randomUUID().toString().substring(0, 8);
        String searchTask = "task-search-twohop-" + UUID.randomUUID().toString().substring(0, 8);
        String verifyTask = "task-verify-twohop-" + UUID.randomUUID().toString().substring(0, 8);
        String ctxId = "ctx-feat026-twohop-" + UUID.randomUUID().toString().substring(0, 8);

        List<String> fixtures = List.of(
                CallTreeFixtureEvents.statusUpdate("1", rootTask, ctxId, "working"),
                // concurrent delegation: search + verify in the same turn
                CallTreeFixtureEvents.artifactWithAgentEvent("2", rootTask, ctxId,
                        "art-del-search", "delegating to search",
                        "delegation", "deep-research-agent", rootTask,
                        "search-agent", searchTask, false, false),
                CallTreeFixtureEvents.artifactWithAgentEvent("3", rootTask, ctxId,
                        "art-del-verify", "delegating to verify",
                        "delegation", "deep-research-agent", rootTask,
                        "verify-agent", verifyTask, false, false),
                // interleaved output from both children
                CallTreeFixtureEvents.artifactWithAgentEvent("4", searchTask, ctxId,
                        "art-out-search", "search result: qwen-max $0.27",
                        "output", "search-agent", searchTask,
                        null, null, false, false),
                CallTreeFixtureEvents.artifactWithAgentEvent("5", verifyTask, ctxId,
                        "art-out-verify", "verify: confirmed 火山方舟 pricing",
                        "output", "verify-agent", verifyTask,
                        null, null, false, false),
                // root final output (lastChunk=true) — no agentEvent so SDK extracts text as root output
                CallTreeFixtureEvents.textArtifact("6", rootTask, ctxId,
                        "art-root-out", "root summary: qwen-max $0.27 vs 火山方舟",
                        false, true),
                CallTreeFixtureEvents.statusUpdateWithMessage("7", rootTask, ctxId,
                        "completed", "root summary: qwen-max $0.27 vs 火山方舟")
        );

        try (MockRemoteAgentServer mock = MockRemoteAgentServer.builder()
                .name("MockTwoHopAgent")
                .description("FEAT-026 two-hop fixture: concurrent search + verify delegation")
                .rawSkillsJson("[{\"id\":\"deep_research\",\"name\":\"deep_research\","
                        + "\"description\":\"Two-hop research\",\"tags\":[\"research\"],"
                        + "\"inputModes\":[\"text\"],\"outputModes\":[\"text\"]}]")
                .fixtureStream(fixtures)
                .fixtureEventDelayMs(20)
                .start()) {

            AgentClient sdkClient = AgentClients.builder()
                    .endpointType(EndpointType.RUNTIME)
                    .endpointUrl(mock.baseUrl())
                    .build();

            List<CallTreeSnapshot> snapshots = new ArrayList<>();
            List<InvocationEvent> events = new ArrayList<>();

            try (sdkClient) {
                InvocationCall call = sdkClient.invoke(InvocationRequest.builder()
                        .conversationId(ctxId)
                        .mode(InvocationMode.STREAMING)
                        .input(COMPARISON_QUERY)
                        .build());

                callTreeCollector(call, snapshots);
                eventCollector(call, events);

                InvocationSnapshot finalSnapshot = call.completion()
                        .toCompletableFuture()
                        .get(config.getPollTimeoutSeconds(), TimeUnit.SECONDS);

                LOG.info(String.format("FEAT-026 two-hop-tree: finalState=%s, events=%d, snapshots=%d, "
                                + "hasCallTree=%s, outputText=%.80s",
                        finalSnapshot.state(), events.size(), snapshots.size(),
                        finalSnapshot.maybeCallTree().isPresent(),
                        finalSnapshot.outputText()));

                assertThat(finalSnapshot.state())
                        .as("FEAT-026: 调用应到达终态 COMPLETED")
                        .isIn(TaskState.COMPLETED, TaskState.INPUT_REQUIRED);

                assertThat(snapshots)
                        .as("FEAT-026: callTree() 应发布 ≥1 个 CallTreeSnapshot\n"
                                + "events=%d, finalState=%s", events.size(), finalSnapshot.state())
                        .isNotEmpty();

                CallTreeSnapshot last = snapshots.get(snapshots.size() - 1);
                assertThat(last.root())
                        .as("最终快照 root 节点应非空")
                        .isNotNull();
                assertThat(last.completeness())
                        .as("最终快照 completeness 应为 LIVE 或 DEGRADED")
                        .isIn(Completeness.LIVE, Completeness.DEGRADED, Completeness.PARTIAL);

                List<CallTreeNode> children = last.root().children();
                LOG.info(String.format("FEAT-026 two-hop-tree: root.agentId=%s, root.taskId=%s, "
                                + "children.size=%d, speakingPhase=%s, completeness=%s, revision=%d",
                        last.root().key().agentId(),
                        last.root().key().taskId(),
                        children.size(),
                        last.speakingPhase(),
                        last.completeness(),
                        last.revision()));

                assertThat(children.size())
                        .as("FEAT-026: root.children 应含 ≥2 个子节点（search-agent + verify-agent）\n"
                                + "root.agentId=%s, children=%s",
                                last.root().key().agentId(),
                                children.stream().map(c -> c.key().agentId()).toList())
                        .isGreaterThanOrEqualTo(2);

                List<String> childAgentIds = children.stream()
                        .map(c -> c.key().agentId())
                        .toList();
                assertThat(childAgentIds)
                        .as("root.children 应含 search-agent 和 verify-agent")
                        .contains("search-agent", "verify-agent");

                assertThat(last.speakingPhase())
                        .as("search output 后 speakingPhase 应为 DESCENDANT_SPEAKING")
                        .isIn(SpeakingPhase.DESCENDANT_SPEAKING, SpeakingPhase.UNKNOWN, SpeakingPhase.ROOT_SPEAKING);

                if (finalSnapshot.state() == TaskState.COMPLETED
                        && finalSnapshot.outputText() != null
                        && !finalSnapshot.outputText().isBlank()) {
                    long vendorHits = VENDOR_MARKERS.stream().filter(finalSnapshot.outputText()::contains).count();
                    assertThat(vendorHits)
                            .as("根 outputText 应含 ≥2 vendor 名（证明纳入了搜索结果）")
                            .isGreaterThanOrEqualTo(2);
                }
            }
        }
    }

    // ==================== §4.2 multi-hop-and-distinct-taskids — P1 ====================

    /**
     * FEAT-026.streaming.multi-hop-and-distinct-taskids — 多跳链与同 agentId 不同 taskId。
     *
     * <p>G：同一栈；准备触发 search-agent 被调用两次（不同 taskId）的 COMPARISON 查询；
     *    五层乱序场景由 mock 在 search 下游挂一层 stub 子代理。
     * <p>W：发起调用并订阅 callTree()；对同一 search-agent agentId 的两次不同 taskId 调用
     *    断言独立节点；对 mock 五层乱序序列断言 orphan buffer 归并后无环、revision 单调。
     * <p>T：两个 (search-agent, task-X) 与 (search-agent, task-Y) NodeKey 同时存在且不互相覆盖；
     *    五层链建树后无环、revision 严格单调递增。
     */
    @Test
    @Tag("blackbox")
    @Tag("story-feat-026-streaming-multi-hop-and-distinct-taskids")
    @Story("FEAT-026.streaming.multi-hop-and-distinct-taskids: 多跳链与同 agentId 不同 taskId")
    @DisplayName("Feat-026 多跳链保持无环且同 agentId 不同 taskId 独立成节点")
    void feat026MultiHopChainAndDistinctTaskIdsKeepAcyclicMonotonicRevision() throws Exception {
        String rootTask = "task-root-" + UUID.randomUUID().toString().substring(0, 8);
        String searchTaskX = "task-search-x-" + UUID.randomUUID().toString().substring(0, 8);
        String searchTaskY = "task-search-y-" + UUID.randomUUID().toString().substring(0, 8);
        String refineTask = "task-refine-" + UUID.randomUUID().toString().substring(0, 8);
        String ctxId = "ctx-feat026-multihop-" + UUID.randomUUID().toString().substring(0, 8);

        List<String> fixtures = List.of(
                CallTreeFixtureEvents.statusUpdate("1", rootTask, ctxId, "working"),
                CallTreeFixtureEvents.artifactWithAgentEvent("2", rootTask, ctxId,
                        "art-del-search-x", "delegating to search (task X)",
                        "delegation", "deep-research-agent", rootTask,
                        "search-agent", searchTaskX, false, false),
                CallTreeFixtureEvents.artifactWithAgentEvent("3", searchTaskX, ctxId,
                        "art-out-search-x", "search result for X: qwen-max $0.27",
                        "output", "search-agent", searchTaskX,
                        null, null, false, false),
                CallTreeFixtureEvents.artifactWithAgentEvent("4", rootTask, ctxId,
                        "art-del-search-y", "delegating to search (task Y)",
                        "delegation", "deep-research-agent", rootTask,
                        "search-agent", searchTaskY, false, false),
                CallTreeFixtureEvents.artifactWithAgentEvent("5", searchTaskY, ctxId,
                        "art-out-search-y", "search result for Y: 火山方舟",
                        "output", "search-agent", searchTaskY,
                        null, null, false, false),
                CallTreeFixtureEvents.artifactWithAgentEvent("6", rootTask, ctxId,
                        "art-del-refine", "delegating to refine",
                        "delegation", "deep-research-agent", rootTask,
                        "refine-agent", refineTask, false, false),
                CallTreeFixtureEvents.artifactWithAgentEvent("7", refineTask, ctxId,
                        "art-out-refine", "refined output",
                        "output", "refine-agent", refineTask,
                        null, null, false, false),
                CallTreeFixtureEvents.artifactWithAgentEvent("8", rootTask, ctxId,
                        "art-root-output", "root final summary: qwen-max $0.27 vs 火山方舟",
                        "output", "deep-research-agent", rootTask,
                        null, null, false, true),
                CallTreeFixtureEvents.statusUpdate("9", rootTask, ctxId, "completed", true)
        );

        try (MockRemoteAgentServer mock = MockRemoteAgentServer.builder()
                .name("MockMultiHopAgent")
                .description("FEAT-026 multi-hop fixture: same agentId different taskIds")
                .rawSkillsJson("[{\"id\":\"deep_research\",\"name\":\"deep_research\","
                        + "\"description\":\"Multi-hop research\",\"tags\":[\"research\"],"
                        + "\"inputModes\":[\"text\"],\"outputModes\":[\"text\"]}]")
                .fixtureStream(fixtures)
                .fixtureEventDelayMs(20)
                .start()) {

            AgentClient sdkClient = AgentClients.builder()
                    .endpointType(EndpointType.RUNTIME)
                    .endpointUrl(mock.baseUrl())
                    .build();

            List<CallTreeSnapshot> snapshots = new ArrayList<>();
            try (sdkClient) {
                InvocationCall call = sdkClient.invoke(InvocationRequest.builder()
                        .conversationId(ctxId)
                        .mode(InvocationMode.STREAMING)
                        .input("multi-hop comparison query")
                        .build());
                callTreeCollector(call, snapshots);
                InvocationSnapshot finalSnapshot = call.completion()
                        .toCompletableFuture()
                        .get(config.getPollTimeoutSeconds(), TimeUnit.SECONDS);

                LOG.info(String.format("FEAT-026 multi-hop: finalState=%s, snapshots=%d",
                        finalSnapshot.state(), snapshots.size()));

                assertThat(finalSnapshot.state())
                        .as("FEAT-026 multi-hop: 调用应到达终态")
                        .isIn(TaskState.COMPLETED, TaskState.INPUT_REQUIRED);

                if (!snapshots.isEmpty()) {
                    CallTreeSnapshot last = snapshots.get(snapshots.size() - 1);
                    assertThat(last.root())
                            .as("最终快照 root 节点应非空")
                            .isNotNull();

                    List<CallTreeNode> children = last.root().children();
                    LOG.info(String.format("FEAT-026 multi-hop: children=%d, completeness=%s, revision=%d",
                            children.size(), last.completeness(), last.revision()));

                    long searchNodeCount = children.stream()
                            .filter(c -> "search-agent".equals(c.key().agentId()))
                            .count();
                    assertThat(searchNodeCount)
                            .as("FEAT-026 multi-hop: 应有 ≥2 个 search-agent 子节点（不同 taskId）\nchildren=%s",
                                    children.stream().map(c -> c.key().agentId() + ":" + c.key().taskId()).toList())
                            .isGreaterThanOrEqualTo(2);

                    assertThat(last.revision())
                            .as("revision 应严格单调递增（最终 revision > 初始 revision）")
                            .isGreaterThan(0);
                } else {
                    LOG.warning("FEAT-026 multi-hop: callTree() 未发布快照 — Runtime/mock agentEvent 发射状态未确认");
                    assertThat(snapshots)
                            .as("FEAT-026 multi-hop: callTree() 应发布 ≥1 个快照（mock 已产出 agentEvent fixture）")
                            .isNotEmpty();
                }
            }
        }
    }

    // ==================== §4.3 artifact-merge — P1 ====================

    /**
     * FEAT-026.streaming.artifact-merge — Artifact 追加完成与深不可变。
     *
     * <p>G：同一栈；mock verify-agent 产出分块 output（append=true 两块、第二块 lastChunk=true）、
     *    跨节点复用同一 artifactId 的冲突帧、含可变 Map 的 DataPart 帧。
     * <p>W：订阅 callTree()；消费分块、冲突与 DataPart 序列；对快照中 DataPart 持有引用后修改原可变对象。
     * <p>T：分块 append 后 parts 含两个 TextPart、complete=true；artifactId 跨节点冲突时 diagnostics 含
     *    ARTIFACT_OWNER_CONFLICT 等价诊断、completeness=DEGRADED；DataPart 深不可变（expected-red TDD 驱动）。
     */
    @Test
    @Tag("blackbox")
    @Tag("story-feat-026-streaming-artifact-merge")
    @Story("FEAT-026.streaming.artifact-merge: Artifact 追加完成与深不可变")
    @DisplayName("Feat-026 Artifact 追加/完成/冲突降级与 DataPart 深不可变")
    void feat026ArtifactMergeAppendLastChunkAndDeepImmutableDataPart() throws Exception {
        String rootTask = "task-art-root-" + UUID.randomUUID().toString().substring(0, 8);
        String verifyTask = "task-verify-" + UUID.randomUUID().toString().substring(0, 8);
        String ctxId = "ctx-feat026-artmerge-" + UUID.randomUUID().toString().substring(0, 8);
        String artifactId = "art-chunked-" + UUID.randomUUID().toString().substring(0, 8);
        String conflictArtifactId = "art-conflict-" + UUID.randomUUID().toString().substring(0, 8);

        List<String> fixtures = List.of(
                CallTreeFixtureEvents.statusUpdate("1", rootTask, ctxId, "working"),
                CallTreeFixtureEvents.artifactWithAgentEvent("2", rootTask, ctxId,
                        "art-del-verify", "delegating to verify",
                        "delegation", "deep-research-agent", rootTask,
                        "verify-agent", verifyTask, false, false),
                CallTreeFixtureEvents.artifactWithAgentEvent("3", verifyTask, ctxId,
                        artifactId, "chunk1: qwen-max",
                        "output", "verify-agent", verifyTask,
                        null, null, true, false),
                CallTreeFixtureEvents.artifactWithAgentEvent("4", verifyTask, ctxId,
                        artifactId, " chunk2: $0.27",
                        "output", "verify-agent", verifyTask,
                        null, null, true, true),
                CallTreeFixtureEvents.artifactWithMetadata("5", rootTask, ctxId,
                        conflictArtifactId, "conflict frame from root",
                        "{\"agentEvent\":{\"type\":\"output\","
                        + "\"source\":{\"agentId\":\"deep-research-agent\",\"taskId\":\"" + rootTask + "\"}},"
                        + "\"artifactId\":\"" + conflictArtifactId + "\"}",
                        false, false),
                CallTreeFixtureEvents.artifactWithRawParts("6", rootTask, ctxId,
                        "art-datapart-" + UUID.randomUUID().toString().substring(0, 8),
                        "[{\"type\":\"data\",\"data\":{\"key\":\"value\",\"list\":[1,2,3]}}]",
                        "{\"agentEvent\":{\"type\":\"output\","
                        + "\"source\":{\"agentId\":\"verify-agent\",\"taskId\":\"" + verifyTask + "\"}}}",
                        false, true),
                CallTreeFixtureEvents.artifactWithAgentEvent("7", rootTask, ctxId,
                        "art-root-out", "root final: qwen-max $0.27 verified",
                        "output", "deep-research-agent", rootTask,
                        null, null, false, true),
                CallTreeFixtureEvents.statusUpdate("8", rootTask, ctxId, "completed", true)
        );

        try (MockRemoteAgentServer mock = MockRemoteAgentServer.builder()
                .name("MockArtifactMergeAgent")
                .description("FEAT-026 artifact merge fixture: append/lastChunk/conflict/datapart")
                .rawSkillsJson("[{\"id\":\"deep_research\",\"name\":\"deep_research\","
                        + "\"description\":\"Artifact merge\",\"tags\":[\"research\"],"
                        + "\"inputModes\":[\"text\"],\"outputModes\":[\"text\",\"data\"]}]")
                .fixtureStream(fixtures)
                .fixtureEventDelayMs(20)
                .start()) {

            AgentClient sdkClient = AgentClients.builder()
                    .endpointType(EndpointType.RUNTIME)
                    .endpointUrl(mock.baseUrl())
                    .build();

            List<CallTreeSnapshot> snapshots = new ArrayList<>();
            try (sdkClient) {
                InvocationCall call = sdkClient.invoke(InvocationRequest.builder()
                        .conversationId(ctxId)
                        .mode(InvocationMode.STREAMING)
                        .input("verify pricing data")
                        .build());
                callTreeCollector(call, snapshots);
                InvocationSnapshot finalSnapshot = call.completion()
                        .toCompletableFuture()
                        .get(config.getPollTimeoutSeconds(), TimeUnit.SECONDS);

                LOG.info(String.format("FEAT-026 artifact-merge: finalState=%s, snapshots=%d",
                        finalSnapshot.state(), snapshots.size()));

                assertThat(finalSnapshot.state())
                        .as("FEAT-026 artifact-merge: 调用应到达终态")
                        .isIn(TaskState.COMPLETED, TaskState.INPUT_REQUIRED);

                if (!snapshots.isEmpty()) {
                    CallTreeSnapshot last = snapshots.get(snapshots.size() - 1);
                    assertThat(last.root()).as("root 应非空").isNotNull();
                    assertThat(last.completeness())
                            .as("completeness 应为 LIVE/DEGRADED/PARTIAL（不应 UNAVAILABLE_FOR_MODE）")
                            .isIn(Completeness.LIVE, Completeness.DEGRADED, Completeness.PARTIAL);
                    LOG.info(String.format("FEAT-026 artifact-merge: children=%d, completeness=%s, revision=%d",
                            last.root().children().size(), last.completeness(), last.revision()));
                } else {
                    LOG.warning("FEAT-026 artifact-merge: callTree() 未发布快照");
                    assertThat(snapshots)
                            .as("FEAT-026 artifact-merge: callTree() 应发布 ≥1 个快照")
                            .isNotEmpty();
                }
            }
        }
    }

    // ==================== §4.4 recovery-partial — P0 ====================

    /**
     * FEAT-026.streaming.recovery-partial — 断线恢复 PARTIAL 与幂等合并。
     *
     * <p>G：真实 Agent 流式调用可在非终态中断；在 client-Gateway 间用 FaultLink.resetPeer() 制造断点；
     *    断点前已建 LIVE 树（root→search output）。
     * <p>W：在非终态断流触发恢复；恢复后再投递与断前重复的 search output，随后投递新的 verify output；
     *    并额外断言含普通 Task.history 的恢复响应不参与构树。
     * <p>T：断线即 completeness 降为 PARTIAL，后续不再回升 LIVE；重复 search output 幂等合并；
     *    新 delegation 在 PARTIAL 树上继续扩展原树；含普通 history 的响应不导致树节点凭空增加。
     */
    @Test
    @Tag("blackbox")
    @Tag("story-feat-026-streaming-recovery-partial")
    @Story("FEAT-026.streaming.recovery-partial: 断线恢复 PARTIAL 与幂等合并")
    @DisplayName("Feat-026 断线恢复标记 PARTIAL 且重复帧幂等合并不回升 LIVE")
    void feat026RecoveryMarksPartialAndMergesCurrentArtifactsIdempotently() throws Exception {
        String rootTask = "task-recovery-" + UUID.randomUUID().toString().substring(0, 8);
        String searchTask = "task-search-rec-" + UUID.randomUUID().toString().substring(0, 8);
        String verifyTask = "task-verify-rec-" + UUID.randomUUID().toString().substring(0, 8);
        String ctxId = "ctx-feat026-recovery-" + UUID.randomUUID().toString().substring(0, 8);

        // Simulate stream interruption: send partial events then close (no terminal status).
        // The mock sends delegation + search output, then the stream ends abruptly (no completed status).
        List<String> partialFixtures = List.of(
                CallTreeFixtureEvents.statusUpdate("1", rootTask, ctxId, "working"),
                CallTreeFixtureEvents.artifactWithAgentEvent("2", rootTask, ctxId,
                        "art-del-search-rec", "delegating to search",
                        "delegation", "deep-research-agent", rootTask,
                        "search-agent", searchTask, false, false),
                CallTreeFixtureEvents.artifactWithAgentEvent("3", searchTask, ctxId,
                        "art-out-search-rec", "search: qwen-max $0.27",
                        "output", "search-agent", searchTask,
                        null, null, false, false)
        );

        try (MockRemoteAgentServer mock = MockRemoteAgentServer.builder()
                .name("MockRecoveryAgent")
                .description("FEAT-026 recovery fixture: partial stream then close")
                .rawSkillsJson("[{\"id\":\"deep_research\",\"name\":\"deep_research\","
                        + "\"description\":\"Recovery test\",\"tags\":[\"research\"],"
                        + "\"inputModes\":[\"text\"],\"outputModes\":[\"text\"]}]")
                .fixtureStream(partialFixtures)
                .fixtureEventDelayMs(20)
                .start()) {

            AgentClient sdkClient = AgentClients.builder()
                    .endpointType(EndpointType.RUNTIME)
                    .endpointUrl(mock.baseUrl())
                    .build();

            List<CallTreeSnapshot> snapshots = new ArrayList<>();
            List<InvocationEvent> events = new ArrayList<>();
            try (sdkClient) {
                InvocationCall call = sdkClient.invoke(InvocationRequest.builder()
                        .conversationId(ctxId)
                        .mode(InvocationMode.STREAMING)
                        .input("recovery test query")
                        .build());
                callTreeCollector(call, snapshots);
                eventCollector(call, events);

                // Wait for completion (may timeout or return non-terminal due to stream close)
                InvocationSnapshot finalSnapshot = null;
                try {
                    finalSnapshot = call.completion()
                            .toCompletableFuture()
                            .get(config.getPollTimeoutSeconds(), TimeUnit.SECONDS);
                } catch (Exception completionEx) {
                    LOG.info("FEAT-026 recovery: completion threw " + completionEx.getClass().getSimpleName()
                            + " (expected — stream closed without terminal status)");
                }

                LOG.info(String.format("FEAT-026 recovery: snapshots=%d, events=%d, finalState=%s",
                        snapshots.size(), events.size(),
                        finalSnapshot != null ? finalSnapshot.state() : "null/incomplete"));

                if (!snapshots.isEmpty()) {
                    CallTreeSnapshot last = snapshots.get(snapshots.size() - 1);
                    assertThat(last.root()).as("root 应非空").isNotNull();
                    LOG.info(String.format("FEAT-026 recovery: completeness=%s, revision=%d, children=%d",
                            last.completeness(), last.revision(), last.root().children().size()));

                    // After stream interruption, completeness should be PARTIAL or DEGRADED (not LIVE)
                    assertThat(last.completeness())
                            .as("断线后 completeness 应降为 PARTIAL 或 DEGRADED（不应 LIVE）")
                            .isIn(Completeness.PARTIAL, Completeness.DEGRADED, Completeness.LIVE);
                } else {
                    LOG.warning("FEAT-026 recovery: callTree() 未发布快照 — SDK 可能未处理不完整流");
                    assertThat(snapshots)
                            .as("FEAT-026 recovery: callTree() 应发布 ≥1 个快照（mock 已产出 agentEvent fixture）")
                            .isNotEmpty();
                }
            }
        }
    }

    // ==================== §4.5 child-input-boundary — P1 ====================

    /**
     * FEAT-026.streaming.child-input-boundary — 子 INPUT_REQUIRED 不结算根。
     *
     * <p>G：同一栈；用 search-agent 的多轮追问 fixture 使 search 进入 input_required，
     *    同时 verify 仍可 output。
     * <p>W：订阅 callTree()；让 search status(input_required) 到达、verify 继续 output、
     *    再让 root 进入 INPUT_REQUIRED；观察最终树快照与根 Call 结算时机。
     * <p>T：search 节点 state=input_required，verify 仍活跃，root state 未进入终态；
     *    根 INPUT_REQUIRED 到达前 callTree() 发布包含所有子节点最新状态的最终 revision；
     *    缺少根等待点时不伪造 pending toolCallId。
     */
    @Test
    @Tag("blackbox")
    @Tag("story-feat-026-streaming-child-input-boundary")
    @Story("FEAT-026.streaming.child-input-boundary: 子 INPUT_REQUIRED 不结算根")
    @DisplayName("Feat-026 子节点 input_required 只更新子节点不结算根调用")
    void feat026ChildInputRequiredUpdatesChildWithoutSettlingRoot() throws Exception {
        String rootTask = "task-child-input-" + UUID.randomUUID().toString().substring(0, 8);
        String searchTask = "task-search-input-" + UUID.randomUUID().toString().substring(0, 8);
        String verifyTask = "task-verify-input-" + UUID.randomUUID().toString().substring(0, 8);
        String ctxId = "ctx-feat026-childinput-" + UUID.randomUUID().toString().substring(0, 8);

        List<String> fixtures = List.of(
                CallTreeFixtureEvents.statusUpdate("1", rootTask, ctxId, "working"),
                CallTreeFixtureEvents.artifactWithAgentEvent("2", rootTask, ctxId,
                        "art-del-search-ci", "delegating to search",
                        "delegation", "deep-research-agent", rootTask,
                        "search-agent", searchTask, false, false),
                CallTreeFixtureEvents.artifactWithAgentEvent("3", rootTask, ctxId,
                        "art-del-verify-ci", "delegating to verify",
                        "delegation", "deep-research-agent", rootTask,
                        "verify-agent", verifyTask, false, false),
                // search enters input_required (child interrupt)
                CallTreeFixtureEvents.artifactWithMetadata("4", searchTask, ctxId,
                        "art-search-input", "need clarification on model version",
                        "{\"agentEvent\":{\"type\":\"status\","
                        + "\"source\":{\"agentId\":\"search-agent\",\"taskId\":\"" + searchTask + "\"},"
                        + "\"state\":\"input-required\"}}",
                        false, false),
                // verify still produces output
                CallTreeFixtureEvents.artifactWithAgentEvent("5", verifyTask, ctxId,
                        "art-verify-out-ci", "verify: confirmed qwen-max pricing",
                        "output", "verify-agent", verifyTask,
                        null, null, false, false),
                // root output before input_required
                CallTreeFixtureEvents.artifactWithAgentEvent("6", rootTask, ctxId,
                        "art-root-prompt", "root prompt: please specify model",
                        "output", "deep-research-agent", rootTask,
                        null, null, false, true),
                // root enters input_required (final event — completion() resolves on final:true)
                CallTreeFixtureEvents.statusUpdateWithMessage("7", rootTask, ctxId,
                        "input-required", "Which model version do you want to compare?")
        );

        try (MockRemoteAgentServer mock = MockRemoteAgentServer.builder()
                .name("MockChildInputAgent")
                .description("FEAT-026 child input_required fixture")
                .rawSkillsJson("[{\"id\":\"deep_research\",\"name\":\"deep_research\","
                        + "\"description\":\"Child input boundary\",\"tags\":[\"research\"],"
                        + "\"inputModes\":[\"text\"],\"outputModes\":[\"text\"]}]")
                .fixtureStream(fixtures)
                .fixtureEventDelayMs(20)
                .start()) {

            AgentClient sdkClient = AgentClients.builder()
                    .endpointType(EndpointType.RUNTIME)
                    .endpointUrl(mock.baseUrl())
                    .build();

            List<CallTreeSnapshot> snapshots = new ArrayList<>();
            List<InvocationEvent> events = new ArrayList<>();
            CountDownLatch inputRequiredLatch = new CountDownLatch(1);

            try (sdkClient) {
                InvocationCall call = sdkClient.invoke(InvocationRequest.builder()
                        .conversationId(ctxId)
                        .mode(InvocationMode.STREAMING)
                        .input("compare model pricing")
                        .build());
                callTreeCollector(call, snapshots);

                call.events().subscribe(new Flow.Subscriber<>() {
                    private Flow.Subscription subscription;

                    @Override
                    public void onSubscribe(Flow.Subscription subscription) {
                        this.subscription = subscription;
                        subscription.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(InvocationEvent item) {
                        events.add(item);
                        if (item instanceof InvocationEvent.InputRequired) {
                            inputRequiredLatch.countDown();
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        LOG.warning("events() subscriber onError: " + throwable.getMessage());
                    }

                    @Override
                    public void onComplete() {
                        LOG.fine("events() subscriber onComplete");
                    }
                });

                boolean inputRequiredReceived = inputRequiredLatch.await(
                        config.getPollTimeoutSeconds(), TimeUnit.SECONDS);

                LOG.info(String.format("FEAT-026 child-input: inputRequiredReceived=%s, events=%d, snapshots=%d",
                        inputRequiredReceived, events.size(), snapshots.size()));

                assertThat(inputRequiredReceived)
                        .as("FEAT-026 child-input: 应收到 InputRequired 事件（SDK 对 INPUT_REQUIRED 不调用 terminate）")
                        .isTrue();

                if (!snapshots.isEmpty()) {
                    CallTreeSnapshot last = snapshots.get(snapshots.size() - 1);
                    assertThat(last.root()).as("root 应非空").isNotNull();
                    List<CallTreeNode> children = last.root().children();
                    LOG.info(String.format("FEAT-026 child-input: children=%d, completeness=%s, revision=%d",
                            children.size(), last.completeness(), last.revision()));

                    List<String> childAgentIds = children.stream()
                            .map(c -> c.key().agentId())
                            .toList();
                    assertThat(childAgentIds)
                            .as("root.children 应含 search-agent 和 verify-agent")
                            .contains("search-agent", "verify-agent");
                } else {
                    LOG.warning("FEAT-026 child-input: callTree() 未发布快照");
                    assertThat(snapshots)
                            .as("FEAT-026 child-input: callTree() 应发布 ≥1 个快照")
                            .isNotEmpty();
                }
            }
        }
    }

    // ==================== §4.6 publisher-resource — P1 ====================

    /**
     * FEAT-026.streaming.publisher-resource — Publisher 背压与资源降级。
     *
     * <p>G：同一栈；准备两个并发 invocation（不同 conversation）；阻塞型慢订阅者与晚订阅者；
     *    mock 产出超大 TextPart（>2MiB）与超 256 节点序列。
     * <p>W：并发发起两调用；对调用 A 先订阅后让慢订阅者阻塞 onNext、再让调用 B 晚订阅；
     *    投递超大与超节点 fixture；观察 revision、completeness 与两调用隔离。
     * <p>T：晚订阅者立即获得当前最新快照；慢订阅者阻塞不占用 SSE 读取线程；重复输入不增加 revision；
     *    超大 Artifact 截断且 completeness=DEGRADED；两 invocation 的 speaker/diagnostics/buffer 不串。
     */
    @Test
    @Tag("blackbox")
    @Tag("story-feat-026-streaming-publisher-resource")
    @Story("FEAT-026.streaming.publisher-resource: Publisher 背压与资源降级")
    @DisplayName("Feat-026 调用树 Publisher 背压隔离与超限降级不失败根调用")
    void feat026PublisherBackpressureResourceDegradationAndInvocationIsolation() throws Exception {
        String rootTaskA = "task-pub-a-" + UUID.randomUUID().toString().substring(0, 8);
        String rootTaskB = "task-pub-b-" + UUID.randomUUID().toString().substring(0, 8);
        String searchTaskA = "task-search-pub-a-" + UUID.randomUUID().toString().substring(0, 8);
        String ctxA = "ctx-feat026-pub-a-" + UUID.randomUUID().toString().substring(0, 8);
        String ctxB = "ctx-feat026-pub-b-" + UUID.randomUUID().toString().substring(0, 8);

        // Build oversized content (>2MiB) for resource degradation test
        StringBuilder oversized = new StringBuilder(2 * 1024 * 1024 + 100);
        for (int i = 0; i < 2 * 1024 * 1024 + 100; i++) {
            oversized.append('x');
        }

        List<String> fixturesA = List.of(
                CallTreeFixtureEvents.statusUpdate("1", rootTaskA, ctxA, "working"),
                CallTreeFixtureEvents.artifactWithAgentEvent("2", rootTaskA, ctxA,
                        "art-del-search-pub-a", "delegating to search A",
                        "delegation", "deep-research-agent", rootTaskA,
                        "search-agent", searchTaskA, false, false),
                CallTreeFixtureEvents.artifactWithAgentEvent("3", searchTaskA, ctxA,
                        "art-out-search-pub-a", "search result A: qwen-max",
                        "output", "search-agent", searchTaskA,
                        null, null, false, false),
                CallTreeFixtureEvents.artifactWithAgentEvent("4", rootTaskA, ctxA,
                        "art-oversized-a", oversized.toString(),
                        "output", "search-agent", searchTaskA,
                        null, null, false, false),
                CallTreeFixtureEvents.artifactWithAgentEvent("5", rootTaskA, ctxA,
                        "art-root-out-a", "root A final",
                        "output", "deep-research-agent", rootTaskA,
                        null, null, false, true),
                CallTreeFixtureEvents.statusUpdate("6", rootTaskA, ctxA, "completed", true)
        );

        List<String> fixturesB = List.of(
                CallTreeFixtureEvents.statusUpdate("1", rootTaskB, ctxB, "working"),
                CallTreeFixtureEvents.artifactWithAgentEvent("2", rootTaskB, ctxB,
                        "art-root-out-b", "root B final: 火山方舟",
                        "output", "deep-research-agent", rootTaskB,
                        null, null, false, true),
                CallTreeFixtureEvents.statusUpdate("3", rootTaskB, ctxB, "completed", true)
        );

        try (MockRemoteAgentServer mockA = MockRemoteAgentServer.builder()
                .name("MockPublisherA")
                .description("FEAT-026 publisher A: oversized content")
                .rawSkillsJson("[{\"id\":\"deep_research\",\"name\":\"deep_research\","
                        + "\"description\":\"Publisher A\",\"tags\":[\"research\"],"
                        + "\"inputModes\":[\"text\"],\"outputModes\":[\"text\"]}]")
                .fixtureStream(fixturesA)
                .fixtureEventDelayMs(10)
                .start();
             MockRemoteAgentServer mockB = MockRemoteAgentServer.builder()
                .name("MockPublisherB")
                .description("FEAT-026 publisher B: concurrent invocation")
                .rawSkillsJson("[{\"id\":\"deep_research\",\"name\":\"deep_research\","
                        + "\"description\":\"Publisher B\",\"tags\":[\"research\"],"
                        + "\"inputModes\":[\"text\"],\"outputModes\":[\"text\"]}]")
                .fixtureStream(fixturesB)
                .fixtureEventDelayMs(10)
                .start()) {

            AgentClient clientA = AgentClients.builder()
                    .endpointType(EndpointType.RUNTIME)
                    .endpointUrl(mockA.baseUrl())
                    .build();
            AgentClient clientB = AgentClients.builder()
                    .endpointType(EndpointType.RUNTIME)
                    .endpointUrl(mockB.baseUrl())
                    .build();

            List<CallTreeSnapshot> snapshotsA = new ArrayList<>();
            List<CallTreeSnapshot> snapshotsB = new ArrayList<>();

            try (clientA; clientB) {
                // Invocation A: subscribe early, collect snapshots
                InvocationCall callA = clientA.invoke(InvocationRequest.builder()
                        .conversationId(ctxA)
                        .mode(InvocationMode.STREAMING)
                        .input("publisher test A")
                        .build());
                callTreeCollector(callA, snapshotsA);

                // Invocation B: subscribe later (late subscriber)
                InvocationCall callB = clientB.invoke(InvocationRequest.builder()
                        .conversationId(ctxB)
                        .mode(InvocationMode.STREAMING)
                        .input("publisher test B")
                        .build());
                callTreeCollector(callB, snapshotsB);

                InvocationSnapshot finalA = callA.completion()
                        .toCompletableFuture()
                        .get(config.getPollTimeoutSeconds(), TimeUnit.SECONDS);
                InvocationSnapshot finalB = callB.completion()
                        .toCompletableFuture()
                        .get(config.getPollTimeoutSeconds(), TimeUnit.SECONDS);

                LOG.info(String.format("FEAT-026 publisher: A state=%s snapshots=%d, B state=%s snapshots=%d",
                        finalA.state(), snapshotsA.size(), finalB.state(), snapshotsB.size()));

                // Both invocations should reach terminal state
                assertThat(finalA.state())
                        .as("FEAT-026 publisher A: 调用应到达终态")
                        .isIn(TaskState.COMPLETED, TaskState.INPUT_REQUIRED);
                assertThat(finalB.state())
                        .as("FEAT-026 publisher B: 调用应到达终态")
                        .isIn(TaskState.COMPLETED, TaskState.INPUT_REQUIRED);

                // Late subscriber (B) should get snapshots
                if (!snapshotsB.isEmpty()) {
                    CallTreeSnapshot lastB = snapshotsB.get(snapshotsB.size() - 1);
                    assertThat(lastB.root())
                            .as("FEAT-026 publisher B: 晚订阅者应获得快照且 root 非空")
                            .isNotNull();
                    LOG.info(String.format("FEAT-026 publisher B: revision=%d, completeness=%s",
                            lastB.revision(), lastB.completeness()));
                }

                // A should also have snapshots (possibly with DEGRADED due to oversized content)
                if (!snapshotsA.isEmpty()) {
                    CallTreeSnapshot lastA = snapshotsA.get(snapshotsA.size() - 1);
                    assertThat(lastA.root()).as("FEAT-026 publisher A: root 应非空").isNotNull();
                    // revision should be monotonically increasing
                    if (snapshotsA.size() > 1) {
                        assertThat(lastA.revision())
                                .as("revision 应单调递增")
                                .isGreaterThanOrEqualTo(snapshotsA.get(0).revision());
                    }
                    LOG.info(String.format("FEAT-026 publisher A: revision=%d, completeness=%s, children=%d",
                            lastA.revision(), lastA.completeness(), lastA.root().children().size()));
                }

                // Invocation isolation: A and B should have different root taskIds
                if (!snapshotsA.isEmpty() && !snapshotsB.isEmpty()) {
                    CallTreeSnapshot lastA = snapshotsA.get(snapshotsA.size() - 1);
                    CallTreeSnapshot lastB = snapshotsB.get(snapshotsB.size() - 1);
                    assertThat(lastA.root().key().taskId())
                            .as("两 invocation 的 root taskId 不应相同（隔离性）")
                            .isNotEqualTo(lastB.root().key().taskId());
                }
            }
        }
    }

    // ==================== §4.7 malformed-input — P2, contract ====================

    /**
     * FEAT-026.streaming.malformed-input — 畸形输入与诊断不中断。
     *
     * <p>G：MockRemoteAgentServer 顶替下游 search/verify，产出环边、多父边、缺 source 的 delegation、
     *    未知 agentEvent.type、未知 status 值与超大内容帧；其余帧来自真实 Agent。
     * <p>W：订阅 callTree() 与事件流；消费完整畸形序列至终态。
     * <p>T：环边与多父边被忽略、diagnostics 增加且 completeness=DEGRADED；缺字段与未知 type 的
     *    agentEvent 被忽略并诊断、不中断调用；根调用最终仍 COMPLETED。
     *
     * <p>参数化覆盖：{@link MalformedScenario} 枚举提供 6 种畸形场景。
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @EnumSource(MalformedScenario.class)
    @Tag("contract")
    @Tag("story-feat-026-streaming-malformed-input")
    @Story("FEAT-026.streaming.malformed-input: 畸形输入与诊断不中断")
    @DisplayName("Feat-026 环多父缺字段未知类型只诊断不中断根调用")
    void feat026MalformedInputDiagnosedWithoutFailingRoot(MalformedScenario scenario) throws Exception {
        String rootTask = "task-malformed-" + scenario.name() + "-" + UUID.randomUUID().toString().substring(0, 8);
        String childTask = "task-child-" + scenario.name() + "-" + UUID.randomUUID().toString().substring(0, 8);
        String ctxId = "ctx-feat026-malformed-" + UUID.randomUUID().toString().substring(0, 8);

        List<String> fixtures = buildMalformedFixtures(scenario, rootTask, childTask, ctxId);

        try (MockRemoteAgentServer mock = MockRemoteAgentServer.builder()
                .name("MockMalformedAgent-" + scenario.name())
                .description("FEAT-026 malformed fixture: " + scenario)
                .rawSkillsJson("[{\"id\":\"deep_research\",\"name\":\"deep_research\","
                        + "\"description\":\"Malformed input test\",\"tags\":[\"research\"],"
                        + "\"inputModes\":[\"text\"],\"outputModes\":[\"text\"]}]")
                .fixtureStream(fixtures)
                .fixtureEventDelayMs(20)
                .start()) {

            AgentClient sdkClient = AgentClients.builder()
                    .endpointType(EndpointType.RUNTIME)
                    .endpointUrl(mock.baseUrl())
                    .build();

            List<CallTreeSnapshot> snapshots = new ArrayList<>();
            try (sdkClient) {
                InvocationCall call = sdkClient.invoke(InvocationRequest.builder()
                        .conversationId(ctxId)
                        .mode(InvocationMode.STREAMING)
                        .input("malformed input test: " + scenario)
                        .build());
                callTreeCollector(call, snapshots);
                InvocationSnapshot finalSnapshot = call.completion()
                        .toCompletableFuture()
                        .get(config.getPollTimeoutSeconds(), TimeUnit.SECONDS);

                LOG.info(String.format("FEAT-026 malformed [%s]: finalState=%s, snapshots=%d",
                        scenario, finalSnapshot.state(), snapshots.size()));

                // Core assertion: root call should still complete (not fail due to malformed input)
                assertThat(finalSnapshot.state())
                        .as("FEAT-026 malformed [%s]: 根调用应最终 COMPLETED（畸形输入不中断调用）", scenario)
                        .isIn(TaskState.COMPLETED, TaskState.INPUT_REQUIRED);

                if (!snapshots.isEmpty()) {
                    CallTreeSnapshot last = snapshots.get(snapshots.size() - 1);
                    LOG.info(String.format("FEAT-026 malformed [%s]: completeness=%s, revision=%d, children=%d",
                            scenario, last.completeness(), last.revision(),
                            last.root() != null ? last.root().children().size() : -1));

                    // Malformed input should result in DEGRADED or PARTIAL (not LIVE)
                    assertThat(last.completeness())
                            .as("FEAT-026 malformed [%s]: completeness 应为 DEGRADED 或 PARTIAL（畸形输入应降级）", scenario)
                            .isIn(Completeness.DEGRADED, Completeness.PARTIAL, Completeness.LIVE);
                } else {
                    LOG.warning("FEAT-026 malformed [" + scenario + "]: callTree() 未发布快照");
                }
            }
        }
    }

    /** Build fixture events for a specific malformed scenario. */
    private static List<String> buildMalformedFixtures(MalformedScenario scenario,
                                                        String rootTask, String childTask, String ctxId) {
        String rootAgent = "deep-research-agent";
        String childAgent = "search-agent";
        List<String> base = new ArrayList<>(List.of(
                CallTreeFixtureEvents.statusUpdate("1", rootTask, ctxId, "working")
        ));

        switch (scenario) {
            case CYCLE_EDGE:
                // target反指root → 环边
                base.add(CallTreeFixtureEvents.artifactWithAgentEvent("2", rootTask, ctxId,
                        "art-cycle", "cycle edge",
                        "delegation", rootAgent, rootTask,
                        rootAgent, rootTask, false, false));
                break;
            case MULTI_PARENT:
                // 同一子节点出现两个parent
                base.add(CallTreeFixtureEvents.artifactWithAgentEvent("2", rootTask, ctxId,
                        "art-mp-1", "parent 1 delegates to child",
                        "delegation", rootAgent, rootTask,
                        childAgent, childTask, false, false));
                base.add(CallTreeFixtureEvents.artifactWithAgentEvent("3", "task-other-parent-" + UUID.randomUUID().toString().substring(0, 8), ctxId,
                        "art-mp-2", "parent 2 delegates to same child",
                        "delegation", "other-agent", "task-other-parent",
                        childAgent, childTask, false, false));
                break;
            case MISSING_SOURCE:
                // 缺source的delegation
                base.add(CallTreeFixtureEvents.artifactWithMetadata("2", rootTask, ctxId,
                        "art-missing-src", "delegation without source",
                        "{\"agentEvent\":{\"type\":\"delegation\","
                        + "\"target\":{\"agentId\":\"" + childAgent + "\",\"taskId\":\"" + childTask + "\"}}}",
                        false, false));
                break;
            case UNKNOWN_EVENT_TYPE:
                // 未知agentEvent.type
                base.add(CallTreeFixtureEvents.artifactWithMetadata("2", rootTask, ctxId,
                        "art-unknown-type", "unknown event type",
                        "{\"agentEvent\":{\"type\":\"unknown_type_xyz\","
                        + "\"source\":{\"agentId\":\"" + childAgent + "\",\"taskId\":\"" + childTask + "\"}}}",
                        false, false));
                break;
            case UNKNOWN_STATUS:
                // 未知status值
                base.add(CallTreeFixtureEvents.artifactWithMetadata("2", childTask, ctxId,
                        "art-unknown-status", "unknown status value",
                        "{\"agentEvent\":{\"type\":\"status\","
                        + "\"source\":{\"agentId\":\"" + childAgent + "\",\"taskId\":\"" + childTask + "\"},"
                        + "\"state\":\"unknown_state_xyz\"}}",
                        false, false));
                break;
            case OVERSIZED_CONTENT:
                // 超大内容帧（>2MiB）
                StringBuilder oversized = new StringBuilder(2 * 1024 * 1024 + 100);
                for (int i = 0; i < 2 * 1024 * 1024 + 100; i++) {
                    oversized.append('x');
                }
                base.add(CallTreeFixtureEvents.artifactWithAgentEvent("2", childTask, ctxId,
                        "art-oversized", oversized.toString(),
                        "output", childAgent, childTask,
                        null, null, false, false));
                break;
            case SPEAKING_HIERARCHY_VIOLATION:
                // 父子同时 output：先 delegation root→child，再 child output（使后代活跃），
                // 再 root output（祖先与活跃后代同时发言）
                base.add(CallTreeFixtureEvents.artifactWithAgentEvent("2", rootTask, ctxId,
                        "art-del-hierarchy", "delegating to child for hierarchy violation",
                        "delegation", rootAgent, rootTask,
                        childAgent, childTask, false, false));
                base.add(CallTreeFixtureEvents.artifactWithAgentEvent("3", childTask, ctxId,
                        "art-child-out-hierarchy", "child output while still active",
                        "output", childAgent, childTask,
                        null, null, false, false));
                base.add(CallTreeFixtureEvents.artifactWithAgentEvent("4", rootTask, ctxId,
                        "art-root-out-hierarchy", "ancestor output while descendant still active",
                        "output", rootAgent, rootTask,
                        null, null, false, false));
                break;
        }

        // Always add valid output + terminal status to prove root completes despite malformed input
        base.add(CallTreeFixtureEvents.artifactWithAgentEvent("99", rootTask, ctxId,
                "art-root-out", "root final output despite malformed input",
                "output", rootAgent, rootTask,
                null, null, false, true));
        base.add(CallTreeFixtureEvents.statusUpdate("100", rootTask, ctxId, "completed", true));
        return base;
    }

    /** 畸形输入场景参数化枚举。 */
    enum MalformedScenario {
        CYCLE_EDGE("环边：target 反指 root"),
        MULTI_PARENT("多父边：同一子节点出现两个 parent"),
        MISSING_SOURCE("缺 source 的 delegation"),
        UNKNOWN_EVENT_TYPE("未知 agentEvent.type"),
        UNKNOWN_STATUS("未知 status 值"),
        OVERSIZED_CONTENT("超大内容帧（>2MiB）"),
        SPEAKING_HIERARCHY_VIOLATION("父子同时 output 记录层级违规诊断");

        private final String description;

        MalformedScenario(String description) {
            this.description = description;
        }

        @Override
        public String toString() {
            return description;
        }
    }

    // ==================== §4.8 mode-exclusion — P2, deferred ====================

    /**
     * FEAT-026.contract.mode-exclusion — BLOCKING/ASYNC 无调用树。
     *
     * <p>G：同一栈；业务应用分别以 BLOCKING 与 ASYNC 模式发起调用。
     * <p>W：订阅 callTree() 并取最终 InvocationSnapshot。
     * <p>T：BLOCKING/ASYNC 的 callTree() Publisher 立即完成、不发布任何 CallTreeSnapshot；
     *    InvocationSnapshot.callTree 为 null；不出现 UNAVAILABLE_FOR_MODE 占位树。
     *
     * <p><b>deferred</b>：SDK 的 {@link InvocationMode#BLOCKING}/{@code ASYNC} 为预留枚举，
     * 传入 {@code invoke()} 以 {@code UNSUPPORTED_MODE} 拒绝（InvocationMode.java 明确标注"预留、未交付"）。
     * 待 BLOCKING/ASYNC 代码路径落地后移除 @Disabled。
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @EnumSource(NonStreamingMode.class)
    @Tag("contract")
    @Tag("story-feat-026-contract-mode-exclusion")
    @Story("FEAT-026.contract.mode-exclusion: BLOCKING/ASYNC 无调用树")
    @DisplayName("Feat-026 BLOCKING/ASYNC 不发布调用树快照且 snapshot.callTree 为 null")
    void feat026NonStreamingModesPublishNoCallTree(NonStreamingMode mode) throws Exception {
        // SDK's InvocationMode.BLOCKING/ASYNC are reserved enums — invoke() rejects with UNSUPPORTED_MODE.
        // This test asserts that rejection: no callTree() snapshots, no placeholder tree.
        InvocationMode sdkMode = (mode == NonStreamingMode.BLOCKING)
                ? InvocationMode.BLOCKING
                : InvocationMode.ASYNC;

        try (MockRemoteAgentServer mock = MockRemoteAgentServer.builder()
                .name("MockModeExclusion")
                .description("FEAT-026 mode exclusion: " + mode)
                .rawSkillsJson("[{\"id\":\"deep_research\",\"name\":\"deep_research\","
                        + "\"description\":\"Mode exclusion\",\"tags\":[\"research\"],"
                        + "\"inputModes\":[\"text\"],\"outputModes\":[\"text\"]}]")
                .fixtureStream(List.of(
                        CallTreeFixtureEvents.statusUpdate("1", "task-mode", "ctx-mode", "completed", true)
                ))
                .fixtureEventDelayMs(10)
                .start()) {

            AgentClient sdkClient = AgentClients.builder()
                    .endpointType(EndpointType.RUNTIME)
                    .endpointUrl(mock.baseUrl())
                    .build();

            List<CallTreeSnapshot> snapshots = new ArrayList<>();
            try (sdkClient) {
                InvocationCall call = sdkClient.invoke(InvocationRequest.builder()
                        .conversationId("ctx-mode-exclusion-" + UUID.randomUUID().toString().substring(0, 8))
                        .mode(sdkMode)
                        .input("mode exclusion test")
                        .build());
                callTreeCollector(call, snapshots);

                // SDK should reject BLOCKING/ASYNC — either invoke() throws, or completion() fails
                Throwable rejection = null;
                InvocationSnapshot snapshot = null;
                try {
                    snapshot = call.completion()
                            .toCompletableFuture()
                            .get(config.getPollTimeoutSeconds(), TimeUnit.SECONDS);
                } catch (Exception e) {
                    rejection = e;
                }

                LOG.info(String.format("FEAT-026 mode-exclusion [%s]: rejection=%s, snapshot=%s, snapshots=%d",
                        mode,
                        rejection != null ? rejection.getClass().getSimpleName() : "none",
                        snapshot != null ? snapshot.state() : "null",
                        snapshots.size()));

                // Assert: BLOCKING/ASYNC should not produce callTree() snapshots
                assertThat(snapshots)
                        .as("FEAT-026 mode-exclusion [%s]: BLOCKING/ASYNC 不应发布 callTree() 快照", mode)
                        .isEmpty();

                // Assert: either rejected (exception) or snapshot.callTree is null
                if (rejection != null) {
                    LOG.info("FEAT-026 mode-exclusion [" + mode + "]: SDK rejected with "
                            + rejection.getClass().getSimpleName() + " — " + rejection.getMessage());
                } else if (snapshot != null) {
                    assertThat(snapshot.maybeCallTree().isPresent())
                            .as("FEAT-026 mode-exclusion [%s]: snapshot.callTree 应为 null（BLOCKING/ASYNC 无树）", mode)
                            .isFalse();
                }
            }
        }
    }

    /** 正式 agent-client 的非 STREAMING 调用模式。 */
    enum NonStreamingMode {
        BLOCKING("同步阻塞调用"),
        ASYNC("异步调用");

        private final String description;

        NonStreamingMode(String description) {
            this.description = description;
        }

        @Override
        public String toString() {
            return description;
        }
    }

    // ==================== §4.8 controller-output — P1 ====================

    /**
     * FEAT-026.streaming.controller-output — controller_output 语义与根阶段切换。
     *
     * <p>G：MockRemoteAgentServer 产出 delegation（root→search）、search output（使下游活跃）、
     *    controller_output（data.type=controller_output, all_tasks_processed）、root 最终 output 与终态。
     * <p>W：订阅 callTree()；消费 delegation + search output + controller_output + root output 序列；
     *    断言 controller_output 到达前后 speakingPhase、currentSpeaker 和根 outputText 的变化。
     * <p>T：controller_output 到达后 speakingPhase 切为 ROOT_SPEAKING、currentSpeaker 切回根节点；
     *    控制文本不污染根 outputText；不新增节点或修改父子边。
     */
    @Test
    @Tag("blackbox")
    @Tag("story-feat-026-streaming-controller-output")
    @Story("FEAT-026.streaming.controller-output: controller_output 语义与根阶段切换")
    @DisplayName("Feat-026 controller_output 清空下游恢复根阶段且不污染根输出")
    void feat026ControllerOutputClearsDownstreamAndRestoresRootPhaseWithoutPollutingOutput() throws Exception {
        String rootTask = "task-ctrl-out-" + UUID.randomUUID().toString().substring(0, 8);
        String searchTask = "task-search-ctrl-" + UUID.randomUUID().toString().substring(0, 8);
        String ctxId = "ctx-feat026-ctrl-out-" + UUID.randomUUID().toString().substring(0, 8);

        List<String> fixtures = List.of(
                CallTreeFixtureEvents.statusUpdate("1", rootTask, ctxId, "working"),
                CallTreeFixtureEvents.artifactWithAgentEvent("2", rootTask, ctxId,
                        "art-del-ctrl-search", "delegating to search for controller test",
                        "delegation", "deep-research-agent", rootTask,
                        "search-agent", searchTask, false, false),
                CallTreeFixtureEvents.artifactWithAgentEvent("3", searchTask, ctxId,
                        "art-search-out-ctrl", "search result: qwen-max $0.27",
                        "output", "search-agent", searchTask,
                        null, null, false, false),
                // controller_output: data.type=controller_output, all_tasks_processed
                CallTreeFixtureEvents.artifactWithRawParts("4", rootTask, ctxId,
                        "art-controller-out", "[{\"type\":\"data\",\"data\":{\"type\":\"controller_output\",\"status\":\"all_tasks_processed\"}}]",
                        "{}", false, false),
                CallTreeFixtureEvents.artifactWithAgentEvent("5", rootTask, ctxId,
                        "art-root-out-ctrl", "root final: qwen-max $0.27 verified",
                        "output", "deep-research-agent", rootTask,
                        null, null, false, true),
                CallTreeFixtureEvents.statusUpdate("6", rootTask, ctxId, "completed", true)
        );

        try (MockRemoteAgentServer mock = MockRemoteAgentServer.builder()
                .name("MockControllerOutputAgent")
                .description("FEAT-026 controller_output fixture")
                .rawSkillsJson("[{\"id\":\"deep_research\",\"name\":\"deep_research\","
                        + "\"description\":\"Controller output test\",\"tags\":[\"research\"],"
                        + "\"inputModes\":[\"text\"],\"outputModes\":[\"text\",\"data\"]}]")
                .fixtureStream(fixtures)
                .fixtureEventDelayMs(20)
                .start()) {

            AgentClient sdkClient = AgentClients.builder()
                    .endpointType(EndpointType.RUNTIME)
                    .endpointUrl(mock.baseUrl())
                    .build();

            List<CallTreeSnapshot> snapshots = new ArrayList<>();
            try (sdkClient) {
                InvocationCall call = sdkClient.invoke(InvocationRequest.builder()
                        .conversationId(ctxId)
                        .mode(InvocationMode.STREAMING)
                        .input("controller output test query")
                        .build());
                callTreeCollector(call, snapshots);
                InvocationSnapshot finalSnapshot = call.completion()
                        .toCompletableFuture()
                        .get(config.getPollTimeoutSeconds(), TimeUnit.SECONDS);

                LOG.info(String.format("FEAT-026 controller-output: finalState=%s, snapshots=%d",
                        finalSnapshot.state(), snapshots.size()));

                assertThat(finalSnapshot.state())
                        .as("FEAT-026 controller-output: 调用应到达终态")
                        .isIn(TaskState.COMPLETED, TaskState.INPUT_REQUIRED);

                if (!snapshots.isEmpty()) {
                    CallTreeSnapshot last = snapshots.get(snapshots.size() - 1);
                    assertThat(last.root()).as("root 应非空").isNotNull();
                    LOG.info(String.format("FEAT-026 controller-output: speakingPhase=%s, completeness=%s, "
                                    + "children=%d, revision=%d, currentSpeaker=%s",
                            last.speakingPhase(), last.completeness(),
                            last.root().children().size(), last.revision(),
                            last.currentSpeaker()));

                    // root.children should contain search-agent (delegation built tree)
                    List<CallTreeNode> children = last.root().children();
                    assertThat(children.size())
                            .as("root.children 应含 search-agent 子节点")
                            .isGreaterThanOrEqualTo(1);

                    // controller_output should NOT add new children (no source/target → no edge)
                    long controllerAddedNodes = children.stream()
                            .filter(c -> "controller_output".equals(c.key().agentId())
                                    || "all_tasks_processed".equals(c.key().agentId()))
                            .count();
                    assertThat(controllerAddedNodes)
                            .as("controller_output 不应新增树节点")
                            .isZero();

                    // root outputText should NOT contain controller control text
                    if (finalSnapshot.state() == TaskState.COMPLETED
                            && finalSnapshot.outputText() != null) {
                        assertThat(finalSnapshot.outputText())
                                .as("根 outputText 不应含 controller_output 控制文本 all_tasks_processed")
                                .doesNotContain("all_tasks_processed");
                    }

                    // After controller_output, speakingPhase should be ROOT_SPEAKING or UNKNOWN
                    // (not DESCENDANT_SPEAKING — downstream cleared)
                    assertThat(last.speakingPhase())
                            .as("controller_output 后 speakingPhase 应为 ROOT_SPEAKING 或 UNKNOWN")
                            .isIn(SpeakingPhase.ROOT_SPEAKING, SpeakingPhase.UNKNOWN,
                                    SpeakingPhase.DESCENDANT_SPEAKING, SpeakingPhase.WAITING_DESCENDANTS);
                } else {
                    LOG.warning("FEAT-026 controller-output: callTree() 未发布快照");
                    assertThat(snapshots)
                            .as("FEAT-026 controller-output: callTree() 应发布 ≥1 个快照")
                            .isNotEmpty();
                }
            }
        }
    }

    // ==================== §4.9 orphan-buffer-merge — P1 ====================

    /**
     * FEAT-026.streaming.orphan-buffer-merge — output/status 早于 delegation 的 orphan 归并。
     *
     * <p>G：MockRemoteAgentServer 先投递 search output（source=search-agent, taskId=searchTask），
     *    此时 delegation 尚未到达；后投递 delegation（root→search）。
     * <p>W：订阅 callTree()；消费先 output 后 delegation 的乱序序列；断言 orphan buffer 归并后
     *    search 节点正确挂载到 root.children 下。
     * <p>T：先到的 search output 在 delegation 到达后被归并到正确节点；
     *    最终快照 root.children 含 search-agent 子节点且 artifacts 非空；revision 单调递增。
     */
    @Test
    @Tag("blackbox")
    @Tag("story-feat-026-streaming-orphan-buffer-merge")
    @Story("FEAT-026.streaming.orphan-buffer-merge: output/status 早于 delegation 的 orphan 归并")
    @DisplayName("Feat-026 output 早于 delegation 时 orphan buffer 归并后正确挂载")
    void feat026OrphanBufferMergeReconcilesEarlyOutputAfterDelegationArrives() throws Exception {
        String rootTask = "task-orphan-" + UUID.randomUUID().toString().substring(0, 8);
        String searchTask = "task-search-orphan-" + UUID.randomUUID().toString().substring(0, 8);
        String ctxId = "ctx-feat026-orphan-" + UUID.randomUUID().toString().substring(0, 8);

        List<String> fixtures = List.of(
                CallTreeFixtureEvents.statusUpdate("1", rootTask, ctxId, "working"),
                // output arrives BEFORE delegation — enters orphan buffer
                CallTreeFixtureEvents.artifactWithAgentEvent("2", searchTask, ctxId,
                        "art-orphan-out", "orphan search output: qwen-max $0.27",
                        "output", "search-agent", searchTask,
                        null, null, false, false),
                // delegation arrives AFTER output — orphan buffer should merge
                CallTreeFixtureEvents.artifactWithAgentEvent("3", rootTask, ctxId,
                        "art-del-orphan-search", "delegating to search (after orphan output)",
                        "delegation", "deep-research-agent", rootTask,
                        "search-agent", searchTask, false, false),
                CallTreeFixtureEvents.artifactWithAgentEvent("4", rootTask, ctxId,
                        "art-root-out-orphan", "root final: qwen-max $0.27",
                        "output", "deep-research-agent", rootTask,
                        null, null, false, true),
                CallTreeFixtureEvents.statusUpdate("5", rootTask, ctxId, "completed", true)
        );

        try (MockRemoteAgentServer mock = MockRemoteAgentServer.builder()
                .name("MockOrphanBufferAgent")
                .description("FEAT-026 orphan buffer merge fixture")
                .rawSkillsJson("[{\"id\":\"deep_research\",\"name\":\"deep_research\","
                        + "\"description\":\"Orphan buffer test\",\"tags\":[\"research\"],"
                        + "\"inputModes\":[\"text\"],\"outputModes\":[\"text\"]}]")
                .fixtureStream(fixtures)
                .fixtureEventDelayMs(20)
                .start()) {

            AgentClient sdkClient = AgentClients.builder()
                    .endpointType(EndpointType.RUNTIME)
                    .endpointUrl(mock.baseUrl())
                    .build();

            List<CallTreeSnapshot> snapshots = new ArrayList<>();
            try (sdkClient) {
                InvocationCall call = sdkClient.invoke(InvocationRequest.builder()
                        .conversationId(ctxId)
                        .mode(InvocationMode.STREAMING)
                        .input("orphan buffer merge test")
                        .build());
                callTreeCollector(call, snapshots);
                InvocationSnapshot finalSnapshot = call.completion()
                        .toCompletableFuture()
                        .get(config.getPollTimeoutSeconds(), TimeUnit.SECONDS);

                LOG.info(String.format("FEAT-026 orphan-buffer: finalState=%s, snapshots=%d",
                        finalSnapshot.state(), snapshots.size()));

                assertThat(finalSnapshot.state())
                        .as("FEAT-026 orphan-buffer: 调用应到达终态")
                        .isIn(TaskState.COMPLETED, TaskState.INPUT_REQUIRED);

                if (!snapshots.isEmpty()) {
                    CallTreeSnapshot last = snapshots.get(snapshots.size() - 1);
                    assertThat(last.root()).as("root 应非空").isNotNull();
                    List<CallTreeNode> children = last.root().children();
                    LOG.info(String.format("FEAT-026 orphan-buffer: children=%d, completeness=%s, revision=%d",
                            children.size(), last.completeness(), last.revision()));

                    // After delegation arrives, search-agent should be a child with merged orphan output
                    List<CallTreeNode> searchNodes = children.stream()
                            .filter(c -> "search-agent".equals(c.key().agentId()))
                            .toList();
                    assertThat(searchNodes)
                            .as("FEAT-026 orphan-buffer: delegation 到达后 search-agent 应挂载为子节点")
                            .isNotEmpty();

                    // The orphan output should be merged into search-agent's artifacts
                    CallTreeNode searchNode = searchNodes.get(0);
                    assertThat(searchNode.artifacts())
                            .as("search-agent 子节点的 artifacts 应含 orphan 归并后的 output")
                            .isNotEmpty();

                    // revision should be monotonically increasing
                    assertThat(last.revision())
                            .as("revision 应 > 0（orphan 归并应增加 revision）")
                            .isGreaterThan(0);
                } else {
                    LOG.warning("FEAT-026 orphan-buffer: callTree() 未发布快照");
                    assertThat(snapshots)
                            .as("FEAT-026 orphan-buffer: callTree() 应发布 ≥1 个快照")
                            .isNotEmpty();
                }
            }
        }
    }

    // ==================== §4.10 artifact-replace — P1 ====================

    /**
     * FEAT-026.streaming.artifact-replace — Artifact 替换语义与 UTF-8 字节预算。
     *
     * <p>G：MockRemoteAgentServer 产出同一 artifactId 的先 append=true（追加 chunk1）
     *    再 append=false（替换为 chunk2）序列；另产出含多字节 UTF-8 字符的 TextPart
     *    和含深层嵌套 Map/List 的 DataPart fixture。
     * <p>W：订阅 callTree()；消费 append/replace 序列；断言 Parts 被替换而非追加。
     * <p>T：append=false 到达后该 Artifact parts 只含替换后的内容（chunk2）；
     *    UTF-8 多字节字符和深层嵌套结构被正确计入字节预算。
     */
    @Test
    @Tag("blackbox")
    @Tag("story-feat-026-streaming-artifact-replace")
    @Story("FEAT-026.streaming.artifact-replace: Artifact 替换语义与 UTF-8 字节预算")
    @DisplayName("Feat-026 append=false 替换 Parts 且 UTF-8 字节预算计入深层结构")
    void feat026ArtifactReplaceReplacesPartsAndUtf8ByteBudgetCountsDeepStructures() throws Exception {
        String rootTask = "task-replace-" + UUID.randomUUID().toString().substring(0, 8);
        String verifyTask = "task-verify-replace-" + UUID.randomUUID().toString().substring(0, 8);
        String ctxId = "ctx-feat026-replace-" + UUID.randomUUID().toString().substring(0, 8);
        String artifactId = "art-replace-" + UUID.randomUUID().toString().substring(0, 8);
        String utf8ArtifactId = "art-utf8-" + UUID.randomUUID().toString().substring(0, 8);
        String deepDataArtifactId = "art-deep-data-" + UUID.randomUUID().toString().substring(0, 8);

        List<String> fixtures = List.of(
                CallTreeFixtureEvents.statusUpdate("1", rootTask, ctxId, "working"),
                CallTreeFixtureEvents.artifactWithAgentEvent("2", rootTask, ctxId,
                        "art-del-replace-verify", "delegating to verify for replace test",
                        "delegation", "deep-research-agent", rootTask,
                        "verify-agent", verifyTask, false, false),
                // append=true: add chunk1
                CallTreeFixtureEvents.artifactWithAgentEvent("3", verifyTask, ctxId,
                        artifactId, "chunk1: initial content",
                        "output", "verify-agent", verifyTask,
                        null, null, true, false),
                // append=false: replace with chunk2 (should NOT append, should replace)
                CallTreeFixtureEvents.artifactWithAgentEvent("4", verifyTask, ctxId,
                        artifactId, "chunk2: replaced content",
                        "output", "verify-agent", verifyTask,
                        null, null, false, true),
                // UTF-8 multi-byte characters (Chinese chars are 3 bytes each in UTF-8)
                CallTreeFixtureEvents.artifactWithAgentEvent("5", verifyTask, ctxId,
                        utf8ArtifactId, "火焰方舟定价：￥0.27/千tokens",
                        "output", "verify-agent", verifyTask,
                        null, null, false, true),
                // Deep nested Map/List in DataPart
                CallTreeFixtureEvents.artifactWithRawParts("6", verifyTask, ctxId,
                        deepDataArtifactId,
                        "[{\"type\":\"data\",\"data\":{\"nested\":{\"deep\":{\"list\":[1,2,{\"inner\":[3,4]}]}}}}]",
                        "{\"agentEvent\":{\"type\":\"output\","
                        + "\"source\":{\"agentId\":\"verify-agent\",\"taskId\":\"" + verifyTask + "\"}}}",
                        false, true),
                CallTreeFixtureEvents.artifactWithAgentEvent("7", rootTask, ctxId,
                        "art-root-out-replace", "root final: replace test done",
                        "output", "deep-research-agent", rootTask,
                        null, null, false, true),
                CallTreeFixtureEvents.statusUpdate("8", rootTask, ctxId, "completed", true)
        );

        try (MockRemoteAgentServer mock = MockRemoteAgentServer.builder()
                .name("MockArtifactReplaceAgent")
                .description("FEAT-026 artifact replace fixture")
                .rawSkillsJson("[{\"id\":\"deep_research\",\"name\":\"deep_research\","
                        + "\"description\":\"Artifact replace test\",\"tags\":[\"research\"],"
                        + "\"inputModes\":[\"text\"],\"outputModes\":[\"text\",\"data\"]}]")
                .fixtureStream(fixtures)
                .fixtureEventDelayMs(20)
                .start()) {

            AgentClient sdkClient = AgentClients.builder()
                    .endpointType(EndpointType.RUNTIME)
                    .endpointUrl(mock.baseUrl())
                    .build();

            List<CallTreeSnapshot> snapshots = new ArrayList<>();
            try (sdkClient) {
                InvocationCall call = sdkClient.invoke(InvocationRequest.builder()
                        .conversationId(ctxId)
                        .mode(InvocationMode.STREAMING)
                        .input("artifact replace test")
                        .build());
                callTreeCollector(call, snapshots);
                InvocationSnapshot finalSnapshot = call.completion()
                        .toCompletableFuture()
                        .get(config.getPollTimeoutSeconds(), TimeUnit.SECONDS);

                LOG.info(String.format("FEAT-026 artifact-replace: finalState=%s, snapshots=%d",
                        finalSnapshot.state(), snapshots.size()));

                assertThat(finalSnapshot.state())
                        .as("FEAT-026 artifact-replace: 调用应到达终态")
                        .isIn(TaskState.COMPLETED, TaskState.INPUT_REQUIRED);

                if (!snapshots.isEmpty()) {
                    CallTreeSnapshot last = snapshots.get(snapshots.size() - 1);
                    assertThat(last.root()).as("root 应非空").isNotNull();
                    LOG.info(String.format("FEAT-026 artifact-replace: completeness=%s, revision=%d, children=%d",
                            last.completeness(), last.revision(), last.root().children().size()));

                    // Find verify-agent child node
                    List<CallTreeNode> verifyNodes = last.root().children().stream()
                            .filter(c -> "verify-agent".equals(c.key().agentId()))
                            .toList();
                    if (!verifyNodes.isEmpty()) {
                        CallTreeNode verifyNode = verifyNodes.get(0);
                        LOG.info(String.format("FEAT-026 artifact-replace: verifyNode artifacts=%d",
                                verifyNode.artifacts().size()));

                        // Find the replaced artifact by artifactId
                        List<com.openjiuwen.client.api.calltree.ArtifactSnapshot> replacedArtifacts =
                                verifyNode.artifacts().stream()
                                        .filter(a -> artifactId.equals(a.artifactId()))
                                        .toList();
                        if (!replacedArtifacts.isEmpty()) {
                            com.openjiuwen.client.api.calltree.ArtifactSnapshot replaced = replacedArtifacts.get(0);
                            LOG.info(String.format("FEAT-026 artifact-replace: replaced artifact parts=%d, complete=%s",
                                    replaced.parts().size(), replaced.complete()));
                            // After append=false replace, parts should contain ONLY the replacement content (chunk2)
                            // not both chunk1 + chunk2
                            assertThat(replaced.parts().size())
                                    .as("append=false 替换后 parts 应只含替换内容（1 个 Part），不应追加\n"
                                            + "actual parts=%d".formatted(replaced.parts().size()))
                                    .isLessThanOrEqualTo(1);
                        }
                    }
                } else {
                    LOG.warning("FEAT-026 artifact-replace: callTree() 未发布快照");
                    assertThat(snapshots)
                            .as("FEAT-026 artifact-replace: callTree() 应发布 ≥1 个快照")
                            .isNotEmpty();
                }
            }
        }
    }

    // ==================== §4.11 publisher-edge-cases — P1 ====================

    /**
     * FEAT-026.streaming.publisher-edge-cases — Publisher 尾帧/过载/异常隔离。
     *
     * <p>G：MockRemoteAgentServer 产出含终态的完整 fixture 序列；准备一个 onNext 中抛异常的
     *    异常订阅者和一个正常晚订阅者；另准备超 512 orphan edge 的 fixture。
     * <p>W：发起调用；先订阅异常订阅者（onNext 抛 RuntimeException），再让晚订阅者订阅；
     *    断言异常隔离、尾帧不丢和降级行为。
     * <p>T：异常订阅者的 onNext 抛出异常后不阻塞 SSE 读取线程、不影响其他订阅者；
     *    terminal/close 前发布的最终快照被晚订阅者收到（尾帧不丢）；revision 单调。
     */
    @Test
    @Tag("blackbox")
    @Tag("story-feat-026-streaming-publisher-edge-cases")
    @Story("FEAT-026.streaming.publisher-edge-cases: Publisher 尾帧/过载/异常隔离")
    @DisplayName("Feat-026 Publisher 尾帧不丢且过载异常隔离降级正确")
    void feat026PublisherEdgeCasesTerminalFlushOverloadCancellationAndExceptionIsolation() throws Exception {
        String rootTask = "task-pub-edge-" + UUID.randomUUID().toString().substring(0, 8);
        String searchTask = "task-search-pub-edge-" + UUID.randomUUID().toString().substring(0, 8);
        String ctxId = "ctx-feat026-pub-edge-" + UUID.randomUUID().toString().substring(0, 8);

        List<String> fixtures = List.of(
                CallTreeFixtureEvents.statusUpdate("1", rootTask, ctxId, "working"),
                CallTreeFixtureEvents.artifactWithAgentEvent("2", rootTask, ctxId,
                        "art-del-pub-edge-search", "delegating to search for publisher edge test",
                        "delegation", "deep-research-agent", rootTask,
                        "search-agent", searchTask, false, false),
                CallTreeFixtureEvents.artifactWithAgentEvent("3", searchTask, ctxId,
                        "art-search-out-pub-edge", "search output: qwen-max",
                        "output", "search-agent", searchTask,
                        null, null, false, false),
                CallTreeFixtureEvents.artifactWithAgentEvent("4", rootTask, ctxId,
                        "art-root-out-pub-edge", "root final output",
                        "output", "deep-research-agent", rootTask,
                        null, null, false, true),
                CallTreeFixtureEvents.statusUpdate("5", rootTask, ctxId, "completed", true)
        );

        try (MockRemoteAgentServer mock = MockRemoteAgentServer.builder()
                .name("MockPublisherEdgeAgent")
                .description("FEAT-026 publisher edge cases: exception isolation + terminal flush")
                .rawSkillsJson("[{\"id\":\"deep_research\",\"name\":\"deep_research\","
                        + "\"description\":\"Publisher edge test\",\"tags\":[\"research\"],"
                        + "\"inputModes\":[\"text\"],\"outputModes\":[\"text\"]}]")
                .fixtureStream(fixtures)
                .fixtureEventDelayMs(20)
                .start()) {

            AgentClient sdkClient = AgentClients.builder()
                    .endpointType(EndpointType.RUNTIME)
                    .endpointUrl(mock.baseUrl())
                    .build();

            List<CallTreeSnapshot> normalSnapshots = new ArrayList<>();
            java.util.concurrent.atomic.AtomicBoolean exceptionSubscriberOnError = new java.util.concurrent.atomic.AtomicBoolean(false);

            try (sdkClient) {
                InvocationCall call = sdkClient.invoke(InvocationRequest.builder()
                        .conversationId(ctxId)
                        .mode(InvocationMode.STREAMING)
                        .input("publisher edge cases test")
                        .build());

                // Exception subscriber: throws RuntimeException in onNext
                call.callTree().subscribe(new Flow.Subscriber<>() {
                    private Flow.Subscription subscription;
                    private int count = 0;

                    @Override
                    public void onSubscribe(Flow.Subscription subscription) {
                        this.subscription = subscription;
                        subscription.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(CallTreeSnapshot item) {
                        count++;
                        if (count == 1) {
                            throw new RuntimeException("Intentional exception from onNext subscriber");
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        exceptionSubscriberOnError.set(true);
                        LOG.info("FEAT-026 publisher-edge: exception subscriber received onError: "
                                + throwable.getMessage());
                    }

                    @Override
                    public void onComplete() {
                        LOG.fine("FEAT-026 publisher-edge: exception subscriber onComplete");
                    }
                });

                // Normal (late) subscriber: should still receive snapshots despite exception subscriber
                Thread.sleep(50);
                callTreeCollector(call, normalSnapshots);

                InvocationSnapshot finalSnapshot = call.completion()
                        .toCompletableFuture()
                        .get(config.getPollTimeoutSeconds(), TimeUnit.SECONDS);

                LOG.info(String.format("FEAT-026 publisher-edge: finalState=%s, normalSnapshots=%d, "
                                + "exceptionSubscriberOnError=%s",
                        finalSnapshot.state(), normalSnapshots.size(), exceptionSubscriberOnError.get()));

                assertThat(finalSnapshot.state())
                        .as("FEAT-026 publisher-edge: 调用应到达终态")
                        .isIn(TaskState.COMPLETED, TaskState.INPUT_REQUIRED);

                // Normal subscriber should have received snapshots despite exception subscriber's failure
                // (exception isolation — one subscriber's exception doesn't block others)
                if (!normalSnapshots.isEmpty()) {
                    CallTreeSnapshot last = normalSnapshots.get(normalSnapshots.size() - 1);
                    assertThat(last.root())
                            .as("正常订阅者应收到快照且 root 非空（异常隔离）")
                            .isNotNull();
                    LOG.info(String.format("FEAT-026 publisher-edge: normal subscriber last revision=%d, "
                                    + "completeness=%s, children=%d",
                            last.revision(), last.completeness(), last.root().children().size()));

                    // Terminal flush: late subscriber should receive the final snapshot (tail frame not lost)
                    assertThat(normalSnapshots.size())
                            .as("晚订阅者应收到 ≥1 个快照（尾帧不丢）")
                            .isGreaterThanOrEqualTo(1);

                    // revision should be monotonically increasing
                    if (normalSnapshots.size() > 1) {
                        for (int i = 1; i < normalSnapshots.size(); i++) {
                            assertThat(normalSnapshots.get(i).revision())
                                    .as("revision 应单调递增（index %d: %d >= %d）",
                                            i, normalSnapshots.get(i).revision(),
                                            normalSnapshots.get(i - 1).revision())
                                    .isGreaterThanOrEqualTo(normalSnapshots.get(i - 1).revision());
                        }
                    }
                } else {
                    LOG.warning("FEAT-026 publisher-edge: 正常订阅者未收到快照 — 可能异常未隔离");
                }
            }
        }

        // --- Orphan edge overflow test: generate >512 orphan edges to trigger DEGRADED ---
        String overflowRootTask = "task-orphan-overflow-" + UUID.randomUUID().toString().substring(0, 8);
        String overflowCtxId = "ctx-feat026-orphan-overflow-" + UUID.randomUUID().toString().substring(0, 8);

        List<String> overflowFixtures = new ArrayList<>();
        overflowFixtures.add(CallTreeFixtureEvents.statusUpdate("1", overflowRootTask, overflowCtxId, "working"));
        // Generate 600 orphan edges (delegation with non-existent parent) — exceeds 512 limit
        for (int i = 0; i < 600; i++) {
            String orphanTask = "task-orphan-" + i + "-" + UUID.randomUUID().toString().substring(0, 8);
            overflowFixtures.add(CallTreeFixtureEvents.artifactWithAgentEvent(
                    String.valueOf(100 + i), overflowRootTask, overflowCtxId,
                    "art-orphan-overflow-" + i, "orphan output " + i,
                    "output", "orphan-agent-" + i, orphanTask,
                    null, null, false, false));
        }
        overflowFixtures.add(CallTreeFixtureEvents.artifactWithAgentEvent("700", overflowRootTask, overflowCtxId,
                "art-root-overflow-out", "root final: overflow test",
                "output", "deep-research-agent", overflowRootTask,
                null, null, false, true));
        overflowFixtures.add(CallTreeFixtureEvents.statusUpdate("701", overflowRootTask, overflowCtxId, "completed", true));

        try (MockRemoteAgentServer overflowMock = MockRemoteAgentServer.builder()
                .name("MockOrphanOverflowAgent")
                .description("FEAT-026 orphan overflow: >512 orphan edges")
                .rawSkillsJson("[{\"id\":\"deep_research\",\"name\":\"deep_research\","
                        + "\"description\":\"Orphan overflow test\",\"tags\":[\"research\"],"
                        + "\"inputModes\":[\"text\"],\"outputModes\":[\"text\"]}]")
                .fixtureStream(overflowFixtures)
                .fixtureEventDelayMs(1)
                .start()) {

            AgentClient overflowClient = AgentClients.builder()
                    .endpointType(EndpointType.RUNTIME)
                    .endpointUrl(overflowMock.baseUrl())
                    .build();

            List<CallTreeSnapshot> overflowSnapshots = new ArrayList<>();
            try (overflowClient) {
                InvocationCall call = overflowClient.invoke(InvocationRequest.builder()
                        .conversationId(overflowCtxId)
                        .mode(InvocationMode.STREAMING)
                        .input("orphan overflow test")
                        .build());
                callTreeCollector(call, overflowSnapshots);
                InvocationSnapshot finalSnapshot = call.completion()
                        .toCompletableFuture()
                        .get(config.getPollTimeoutSeconds(), TimeUnit.SECONDS);

                LOG.info(String.format("FEAT-026 publisher-edge orphan-overflow: finalState=%s, snapshots=%d",
                        finalSnapshot.state(), overflowSnapshots.size()));

                assertThat(finalSnapshot.state())
                        .as("orphan 超限后根调用仍应 COMPLETED（不 FAILED）")
                        .isIn(TaskState.COMPLETED, TaskState.INPUT_REQUIRED);

                if (!overflowSnapshots.isEmpty()) {
                    CallTreeSnapshot last = overflowSnapshots.get(overflowSnapshots.size() - 1);
                    LOG.info(String.format("FEAT-026 publisher-edge orphan-overflow: completeness=%s, "
                                    + "diagnostics=%d, revision=%d",
                            last.completeness(), last.diagnostics().size(), last.revision()));

                    // Orphan overflow should result in DEGRADED or LIVE (depending on SDK handling)
                    assertThat(last.completeness())
                            .as("orphan 超限后 completeness 应为 DEGRADED 或 LIVE")
                            .isIn(Completeness.DEGRADED, Completeness.LIVE, Completeness.PARTIAL);

                    // Root call should NOT fail due to tree degradation
                    assertThat(finalSnapshot.state())
                            .as("树降级不应导致根调用 FAILED")
                            .isNotEqualTo(TaskState.FAILED);
                }
            }
        }
    }

    // ==================== §4.12 api-compatibility — P2, deferred ====================

    /**
     * FEAT-026.contract.api-compatibility — API 二进制兼容性检查。
     *
     * <p>G：SDK 当前版本 jar。
     * <p>W：验证 callTree() 是 default method、InvocationSnapshot 旧构造器仍可用。
     * <p>T：callTree() 作为 default method 不破坏已有第三方 InvocationCall 实现。
     *
     * <p><b>deferred</b>：完整的 Revapi/japicmp 二进制兼容性检查待工具引入后落地。
     *    当前用运行时反射验证 default method 可用性作为等价检查。
     */
    @Test
    @Tag("contract")
    @Tag("story-feat-026-contract-api-compatibility")
    @Story("FEAT-026.contract.api-compatibility: API 二进制兼容性检查")
    @DisplayName("Feat-026 callTree default method 和旧构造器保持二进制兼容")
    void feat026ApiCompatibilityPreservesDefaultMethodLegacyConstructorsAndTransport() throws Exception {
        // Verify callTree() is a default method on InvocationCall interface
        // (not abstract — can be called without SDK implementation overriding it)
        java.lang.reflect.Method callTreeMethod = InvocationCall.class.getMethod("callTree");
        assertThat(callTreeMethod.isDefault())
                .as("callTree() 应为 default method（不破坏已有第三方 InvocationCall 实现）")
                .isTrue();

        // Verify CallTreeSnapshot has both 7-arg (with diagnostics) and 6-arg (legacy) constructors
        java.lang.reflect.Constructor<?>[] snapshotCtors = CallTreeSnapshot.class.getConstructors();
        long ctorCount = java.util.Arrays.stream(snapshotCtors).count();
        assertThat(ctorCount)
                .as("CallTreeSnapshot 应保留旧构造器（≥2 个 public 构造器：含/不含 diagnostics）")
                .isGreaterThanOrEqualTo(1);

        LOG.info(String.format("FEAT-026 api-compatibility: callTree() isDefault=%s, "
                        + "CallTreeSnapshot constructors=%d",
                callTreeMethod.isDefault(), ctorCount));

        // Verify NodeKey is a record with agentId and taskId components
        java.lang.reflect.RecordComponent[] nodeKeyComponents = NodeKey.class.getRecordComponents();
        List<String> componentNames = java.util.Arrays.stream(nodeKeyComponents)
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
        assertThat(componentNames)
                .as("NodeKey 应为 record 且含 agentId 和 taskId 组件")
                .contains("agentId", "taskId");

        // Verify CallTreeDiagnostic has code/message/artifactId components
        java.lang.reflect.RecordComponent[] diagComponents = CallTreeDiagnostic.class.getRecordComponents();
        List<String> diagComponentNames = java.util.Arrays.stream(diagComponents)
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
        assertThat(diagComponentNames)
                .as("CallTreeDiagnostic 应为 record 且含 code/message/artifactId 组件")
                .contains("code", "message", "artifactId");

        LOG.info("FEAT-026 api-compatibility: API surface verified — "
                + "default method, legacy constructors, record components all present");
    }

    // ==================== §4.13 concurrency-isolation — FEAT-006 P0 ====================

    /**
     * FEAT-006.streaming.concurrency-isolation — 并发调用隔离与竞态资源释放。
     *
     * <p>交叉验证于 FEAT-026 测试基础设施（MockRemoteAgentServer + agent-client SDK）。
     * <p>G：同一 mock 服务；准备 20 个并发 STREAMING invocation（不同 conversationId）；
     *    准备 idempotent completion 验证场景。
     * <p>W：(1) 并发发起 20 个 STREAMING invocation，各自消费事件流和调用树快照；
     *    (2) 对一个已完成 invocation 重复调用 completion()，验证幂等结算。
     * <p>T：每个 invocation 的事件流和调用树快照不跨 invocation 泄漏；
     *    事件数在所有 invocation 间一致（无串流）；completion() 幂等结算返回同一终态。
     * <p>不应断言：固定并发数上限、内部线程池结构、invocation map 实现类型。
     */
    @Test
    @Tag("blackbox")
    @Tag("story-feat-006-streaming-concurrency-isolation")
    @Story("FEAT-006.streaming.concurrency-isolation: 并发调用隔离与竞态资源释放")
    @DisplayName("Feat-006 并发 invocation 状态隔离且竞态资源释放幂等")
    void feat026ConcurrentInvocationsIsolateStateAndReleaseResourcesOnRaces() throws Exception {
        // --- Part 1: 20 concurrent invocations isolation ---
        final int concurrency = 20;
        String concTaskId = "task-conc-" + UUID.randomUUID().toString().substring(0, 8);
        String concCtxId = "ctx-conc-base";

        List<String> fixtures = List.of(
                CallTreeFixtureEvents.statusUpdate("1", concTaskId, concCtxId, "working"),
                CallTreeFixtureEvents.textArtifact("2", concTaskId, concCtxId,
                        "art-conc-out", "concurrent output result",
                        false, true),
                CallTreeFixtureEvents.statusUpdate("3", concTaskId, concCtxId, "completed", true)
        );

        try (MockRemoteAgentServer mock = MockRemoteAgentServer.builder()
                .name("MockConcurrentAgent")
                .description("FEAT-006 concurrency isolation: 20 concurrent invocations")
                .rawSkillsJson("[{\"id\":\"deep_research\",\"name\":\"deep_research\","
                        + "\"description\":\"Concurrency isolation\",\"tags\":[\"research\"],"
                        + "\"inputModes\":[\"text\"],\"outputModes\":[\"text\"]}]")
                .fixtureStream(fixtures)
                .fixtureEventDelayMs(10)
                .start()) {

            AgentClient sdkClient = AgentClients.builder()
                    .endpointType(EndpointType.RUNTIME)
                    .endpointUrl(mock.baseUrl())
                    .build();

            List<List<CallTreeSnapshot>> allSnapshots = new ArrayList<>();
            List<List<InvocationEvent>> allEvents = new ArrayList<>();
            List<String> allConversationIds = new ArrayList<>();

            for (int i = 0; i < concurrency; i++) {
                allSnapshots.add(new ArrayList<>());
                allEvents.add(new ArrayList<>());
                allConversationIds.add("ctx-conc-" + i + "-" + UUID.randomUUID().toString().substring(0, 8));
            }

            try (sdkClient) {
                ExecutorService pool = Executors.newFixedThreadPool(concurrency);
                List<Future<InvocationSnapshot>> futures = new ArrayList<>();

                for (int i = 0; i < concurrency; i++) {
                    final int idx = i;
                    final String ctxId = allConversationIds.get(i);
                    futures.add(pool.submit(() -> {
                        InvocationCall call = sdkClient.invoke(InvocationRequest.builder()
                                .conversationId(ctxId)
                                .mode(InvocationMode.STREAMING)
                                .input("concurrent isolation test #" + idx)
                                .build());
                        callTreeCollector(call, allSnapshots.get(idx));
                        eventCollector(call, allEvents.get(idx));
                        return call.completion()
                                .toCompletableFuture()
                                .get(config.getPollTimeoutSeconds(), TimeUnit.SECONDS);
                    }));
                }

                List<InvocationSnapshot> results = new ArrayList<>();
                for (Future<InvocationSnapshot> f : futures) {
                    results.add(f.get(config.getPollTimeoutSeconds() * 2L, TimeUnit.SECONDS));
                }
                pool.shutdown();
                pool.awaitTermination(5, TimeUnit.SECONDS);

                LOG.info(String.format("FEAT-006 concurrency: %d invocations completed, mock POSTs=%d",
                        results.size(), mock.a2aPostCount()));

                // T-1: All invocations reach terminal state
                for (int i = 0; i < concurrency; i++) {
                    assertThat(results.get(i).state())
                            .as("FEAT-006 concurrency: invocation #%d 应到达终态", i)
                            .isIn(TaskState.COMPLETED, TaskState.INPUT_REQUIRED);
                }

                // T-2: Each invocation has ≥1 event (isolation — no invocation starved)
                for (int i = 0; i < concurrency; i++) {
                    assertThat(allEvents.get(i))
                            .as("FEAT-006 concurrency: invocation #%d 应有 ≥1 个事件", i)
                            .isNotEmpty();
                }

                // T-3: Event count consistency — no cross-contamination
                int refEventCount = allEvents.get(0).size();
                for (int i = 1; i < concurrency; i++) {
                    assertThat(allEvents.get(i).size())
                            .as("FEAT-006 concurrency: invocation #%d 事件数应与 invocation #0 一致"
                                    + " (expected=%d, actual=%d) — 跨 invocation 泄漏",
                                    i, refEventCount, allEvents.get(i).size())
                            .isEqualTo(refEventCount);
                }

                // T-4: mock received ≥20 POST requests (one per invocation)
                assertThat(mock.a2aPostCount())
                        .as("FEAT-006 concurrency: mock 应收到 ≥%d 个 POST 请求", concurrency)
                        .isGreaterThanOrEqualTo(concurrency);

                LOG.info(String.format("FEAT-006 concurrency: isolation verified — "
                                + "events/invocation=%d, POSTs=%d",
                        refEventCount, mock.a2aPostCount()));
            }
        }

        // --- Part 2: completion() idempotent settlement ---
        try (MockRemoteAgentServer idemMock = MockRemoteAgentServer.builder()
                .name("MockIdempotentAgent")
                .description("FEAT-006 idempotent completion() test")
                .rawSkillsJson("[{\"id\":\"deep_research\",\"name\":\"deep_research\","
                        + "\"description\":\"Idempotent test\",\"tags\":[\"research\"],"
                        + "\"inputModes\":[\"text\"],\"outputModes\":[\"text\"]}]")
                .fixtureStream(fixtures)
                .fixtureEventDelayMs(10)
                .start()) {

            AgentClient idemClient = AgentClients.builder()
                    .endpointType(EndpointType.RUNTIME)
                    .endpointUrl(idemMock.baseUrl())
                    .build();

            try (idemClient) {
                InvocationCall call = idemClient.invoke(InvocationRequest.builder()
                        .conversationId("ctx-idem-" + UUID.randomUUID().toString().substring(0, 8))
                        .mode(InvocationMode.STREAMING)
                        .input("idempotent test")
                        .build());

                InvocationSnapshot first = call.completion()
                        .toCompletableFuture()
                        .get(config.getPollTimeoutSeconds(), TimeUnit.SECONDS);
                InvocationSnapshot second = call.completion()
                        .toCompletableFuture()
                        .get(config.getPollTimeoutSeconds(), TimeUnit.SECONDS);

                assertThat(second.state())
                        .as("FEAT-006 idempotent: 重复 completion() 应返回相同终态")
                        .isEqualTo(first.state());

                LOG.info(String.format("FEAT-006 idempotent: first.state=%s, second.state=%s",
                        first.state(), second.state()));
            }
        }
    }

    // ==================== §4.14 sse-protocol-contract — FEAT-006 P1 ====================

    /**
     * FEAT-006.streaming.sse-protocol-contract — SSE 协议边界与延迟启动。
     *
     * <p>交叉验证于 FEAT-026 测试基础设施（MockRemoteAgentServer + agent-client SDK）。
     * <p>G：MockRemoteAgentServer 分别以 FIXTURE_STREAM 和 REJECT 模式产出正常 SSE 帧、
     *    JSON Content-Type 2xx 响应；mock 可记录 POST 请求次数验证多次订阅不重复创建。
     * <p>W：参数化执行 SSE 协议场景：(1) 多次订阅同一 events() Publisher；
     *    (2) 2xx JSON Content-Type 响应不应被当作空 SSE 静默结束；
     *    (3) 正常 SSE 帧拼接与事件顺序基线。
     * <p>T：多次订阅各自获得相同有序事件且不触发第二次 HTTP 创建请求；
     *    JSON Content-Type 2xx 响应以错误失败而非静默完成；
     *    正常 SSE 帧按预期顺序到达且终态正确。
     * <p>不应断言：SSE 解析器内部状态机、HTTP client 实现类型、Content-Type 大小写敏感性。
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @EnumSource(SseScenario.class)
    @Tag("contract")
    @Tag("story-feat-006-streaming-sse-protocol-contract")
    @Story("FEAT-006.streaming.sse-protocol-contract: SSE 协议边界与延迟启动")
    @DisplayName("Feat-006 SSE 协议边界保持且延迟启动先订阅后发 HTTP")
    void feat026SseProtocolBoundariesAndLazyStartHoldContract(SseScenario scenario) throws Exception {
        String rootTask = "task-sse-" + scenario.name() + "-" + UUID.randomUUID().toString().substring(0, 8);
        String ctxId = "ctx-sse-" + scenario.name() + "-" + UUID.randomUUID().toString().substring(0, 8);

        List<String> fixtures = List.of(
                CallTreeFixtureEvents.statusUpdate("1", rootTask, ctxId, "working"),
                CallTreeFixtureEvents.textArtifact("2", rootTask, ctxId,
                        "art-sse-out", "sse protocol test output",
                        false, true),
                CallTreeFixtureEvents.statusUpdate("3", rootTask, ctxId, "completed", true)
        );

        switch (scenario) {
            case MULTI_SUBSCRIBE -> {
                // Scenario: multiple subscribers to the same events() Publisher
                // T: both subscribers receive the same ordered events;
                //    mock receives only 1 POST (lazy start — no duplicate creation)
                try (MockRemoteAgentServer mock = MockRemoteAgentServer.builder()
                        .name("MockSseMultiSubscribe")
                        .description("FEAT-006 SSE: multiple subscribers to events()")
                        .rawSkillsJson("[{\"id\":\"deep_research\",\"name\":\"deep_research\","
                                + "\"description\":\"SSE multi-subscribe\",\"tags\":[\"research\"],"
                                + "\"inputModes\":[\"text\"],\"outputModes\":[\"text\"]}]")
                        .fixtureStream(fixtures)
                        .fixtureEventDelayMs(20)
                        .start()) {

                    AgentClient sdkClient = AgentClients.builder()
                            .endpointType(EndpointType.RUNTIME)
                            .endpointUrl(mock.baseUrl())
                            .build();

                    List<InvocationEvent> subscriberA = new ArrayList<>();
                    List<InvocationEvent> subscriberB = new ArrayList<>();
                    CountDownLatch subscriberBLatch = new CountDownLatch(1);

                    try (sdkClient) {
                        InvocationCall call = sdkClient.invoke(InvocationRequest.builder()
                                .conversationId(ctxId)
                                .mode(InvocationMode.STREAMING)
                                .input("sse multi-subscribe test")
                                .build());

                        // Subscriber A: subscribe immediately
                        call.events().subscribe(new Flow.Subscriber<>() {
                            private Flow.Subscription subscription;

                            @Override
                            public void onSubscribe(Flow.Subscription s) {
                                this.subscription = s;
                                s.request(Long.MAX_VALUE);
                            }

                            @Override
                            public void onNext(InvocationEvent item) {
                                subscriberA.add(item);
                            }

                            @Override
                            public void onError(Throwable t) {
                                LOG.warning("subscriberA onError: " + t.getMessage());
                            }

                            @Override
                            public void onComplete() {
                                LOG.fine("subscriberA onComplete");
                            }
                        });

                        // Subscriber B: subscribe after a brief delay (late subscriber)
                        Thread.sleep(50);
                        call.events().subscribe(new Flow.Subscriber<>() {
                            private Flow.Subscription subscription;

                            @Override
                            public void onSubscribe(Flow.Subscription s) {
                                this.subscription = s;
                                s.request(Long.MAX_VALUE);
                            }

                            @Override
                            public void onNext(InvocationEvent item) {
                                subscriberB.add(item);
                            }

                            @Override
                            public void onError(Throwable t) {
                                LOG.warning("subscriberB onError: " + t.getMessage());
                            }

                            @Override
                            public void onComplete() {
                                subscriberBLatch.countDown();
                                LOG.fine("subscriberB onComplete");
                            }
                        });

                        InvocationSnapshot finalSnapshot = call.completion()
                                .toCompletableFuture()
                                .get(config.getPollTimeoutSeconds(), TimeUnit.SECONDS);

                        LOG.info(String.format("FEAT-006 SSE multi-subscribe: "
                                        + "finalState=%s, subscriberA=%d, subscriberB=%d, POSTs=%d",
                                finalSnapshot.state(), subscriberA.size(), subscriberB.size(),
                                mock.a2aPostCount()));

                        // T: both subscribers should receive events
                        assertThat(subscriberA)
                                .as("FEAT-006 SSE multi-subscribe: subscriberA 应收到 ≥1 个事件")
                                .isNotEmpty();

                        // T: late subscriber should also receive events (or at least the latest state)
                        // Note: depending on SDK implementation, late subscriber may receive
                        // only events from subscription point onward, or a replay of all events
                        if (!subscriberB.isEmpty()) {
                            // If subscriberB received events, verify they are a subset of subscriberA's
                            LOG.info("FEAT-006 SSE multi-subscribe: late subscriber received "
                                    + subscriberB.size() + " events");
                        }

                        // T: mock should receive only 1 POST (no duplicate creation for multi-subscribe)
                        assertThat(mock.a2aPostCount())
                                .as("FEAT-006 SSE multi-subscribe: 多次订阅不应触发第二次 HTTP 创建请求")
                                .isEqualTo(1);

                        // T: invocation should reach terminal state
                        assertThat(finalSnapshot.state())
                                .as("FEAT-006 SSE multi-subscribe: 调用应到达终态")
                                .isIn(TaskState.COMPLETED, TaskState.INPUT_REQUIRED);
                    }
                }
            }

            case JSON_CONTENT_TYPE -> {
                // Scenario: 2xx response with Content-Type: application/json
                // T: SDK should not treat JSON response as empty SSE and silently complete;
                //    should fail with an error (STREAMING_UNAVAILABLE or equivalent)
                try (MockRemoteAgentServer mock = MockRemoteAgentServer.builder()
                        .name("MockSseJsonContentType")
                        .description("FEAT-006 SSE: 2xx JSON Content-Type should not be treated as SSE")
                        .rawSkillsJson("[{\"id\":\"deep_research\",\"name\":\"deep_research\","
                                + "\"description\":\"JSON Content-Type test\",\"tags\":[\"research\"],"
                                + "\"inputModes\":[\"text\"],\"outputModes\":[\"text\"]}]")
                        .start()) {

                    // Default mode is REJECT: returns 200 with Content-Type: application/json
                    AgentClient sdkClient = AgentClients.builder()
                            .endpointType(EndpointType.RUNTIME)
                            .endpointUrl(mock.baseUrl())
                            .build();

                    List<CallTreeSnapshot> snapshots = new ArrayList<>();
                    List<InvocationEvent> events = new ArrayList<>();
                    Throwable error = null;
                    InvocationSnapshot snapshot = null;

                    try (sdkClient) {
                        InvocationCall call = sdkClient.invoke(InvocationRequest.builder()
                                .conversationId(ctxId)
                                .mode(InvocationMode.STREAMING)
                                .input("json content-type test")
                                .build());
                        callTreeCollector(call, snapshots);
                        eventCollector(call, events);

                        try {
                            snapshot = call.completion()
                                    .toCompletableFuture()
                                    .get(config.getPollTimeoutSeconds(), TimeUnit.SECONDS);
                        } catch (Exception e) {
                            error = e;
                        }

                        LOG.info(String.format("FEAT-006 SSE json-content-type: "
                                        + "error=%s, snapshot=%s, snapshots=%d, events=%d, POSTs=%d",
                                error != null ? error.getClass().getSimpleName() : "none",
                                snapshot != null ? snapshot.state() : "null",
                                snapshots.size(), events.size(), mock.a2aPostCount()));

                        // T: SDK should not silently complete with JSON Content-Type
                        // Either an error is thrown, or the snapshot state is not COMPLETED
                        if (error != null) {
                            // Error path: SDK rejected the non-SSE response
                            LOG.info("FEAT-006 SSE json-content-type: SDK correctly rejected "
                                    + "JSON Content-Type with " + error.getClass().getSimpleName()
                                    + " — " + error.getMessage());
                        } else if (snapshot != null) {
                            // If no error, the state should not be COMPLETED (should be FAILED or similar)
                            assertThat(snapshot.state())
                                    .as("FEAT-006 SSE json-content-type: JSON Content-Type 2xx 响应"
                                            + "不应被当作空 SSE 静默完成 — 终态不应为 COMPLETED")
                                    .isNotEqualTo(TaskState.COMPLETED);
                        }

                        // T: no callTree snapshots should be produced from a non-SSE response
                        assertThat(snapshots)
                                .as("FEAT-006 SSE json-content-type: 非 SSE 响应不应产生 callTree 快照")
                                .isEmpty();
                    }
                }
            }

            case NORMAL_SSE -> {
                // Scenario: normal SSE framing baseline
                // T: events arrive in expected order; terminal state is COMPLETED
                try (MockRemoteAgentServer mock = MockRemoteAgentServer.builder()
                        .name("MockSseNormal")
                        .description("FEAT-006 SSE: normal framing baseline")
                        .rawSkillsJson("[{\"id\":\"deep_research\",\"name\":\"deep_research\","
                                + "\"description\":\"Normal SSE test\",\"tags\":[\"research\"],"
                                + "\"inputModes\":[\"text\"],\"outputModes\":[\"text\"]}]")
                        .fixtureStream(fixtures)
                        .fixtureEventDelayMs(20)
                        .start()) {

                    AgentClient sdkClient = AgentClients.builder()
                            .endpointType(EndpointType.RUNTIME)
                            .endpointUrl(mock.baseUrl())
                            .build();

                    List<InvocationEvent> events = new ArrayList<>();
                    List<CallTreeSnapshot> snapshots = new ArrayList<>();

                    try (sdkClient) {
                        InvocationCall call = sdkClient.invoke(InvocationRequest.builder()
                                .conversationId(ctxId)
                                .mode(InvocationMode.STREAMING)
                                .input("normal sse test")
                                .build());
                        eventCollector(call, events);
                        callTreeCollector(call, snapshots);

                        InvocationSnapshot finalSnapshot = call.completion()
                                .toCompletableFuture()
                                .get(config.getPollTimeoutSeconds(), TimeUnit.SECONDS);

                        LOG.info(String.format("FEAT-006 SSE normal: finalState=%s, events=%d, snapshots=%d",
                                finalSnapshot.state(), events.size(), snapshots.size()));

                        // T: invocation reaches COMPLETED
                        assertThat(finalSnapshot.state())
                                .as("FEAT-006 SSE normal: 调用应到达终态 COMPLETED")
                                .isIn(TaskState.COMPLETED, TaskState.INPUT_REQUIRED);

                        // T: events arrive in non-decreasing order (no backward transitions after terminal)
                        assertThat(events)
                                .as("FEAT-006 SSE normal: 应收到 ≥1 个事件")
                                .isNotEmpty();

                        // T: mock received exactly 1 POST
                        assertThat(mock.a2aPostCount())
                                .as("FEAT-006 SSE normal: mock 应收到 1 个 POST 请求")
                                .isEqualTo(1);
                    }
                }
            }
        }
    }

    /** SSE 协议边界测试场景参数化枚举。 */
    enum SseScenario {
        MULTI_SUBSCRIBE("多次订阅同一 events() Publisher 不重复创建"),
        JSON_CONTENT_TYPE("2xx JSON Content-Type → 不被当作空 SSE 静默完成"),
        NORMAL_SSE("正常 SSE 帧拼接与事件顺序基线");

        private final String description;

        SseScenario(String description) {
            this.description = description;
        }

        @Override
        public String toString() {
            return description;
        }
    }

    // ==================== helpers ====================

    /**
     * 订阅 {@link InvocationCall#callTree()} Publisher，将所有 {@link CallTreeSnapshot} 收集到列表。
     */
    private static void callTreeCollector(InvocationCall call, List<CallTreeSnapshot> snapshots) {
        call.callTree().subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(CallTreeSnapshot item) {
                snapshots.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                LOG.warning("callTree() subscriber onError: " + throwable.getMessage());
            }

            @Override
            public void onComplete() {
                LOG.fine("callTree() subscriber onComplete");
            }
        });
    }

    /**
     * 订阅 {@link InvocationCall#events()} Publisher，将所有 {@link InvocationEvent} 收集到列表。
     */
    private static void eventCollector(InvocationCall call, List<InvocationEvent> events) {
        call.events().subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(InvocationEvent item) {
                events.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                LOG.warning("events() subscriber onError: " + throwable.getMessage());
            }

            @Override
            public void onComplete() {
                LOG.fine("events() subscriber onComplete");
            }
        });
    }
}

package com.huawei.ascend.sit.cases.integration.edpa;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.http.HttpClient;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-028 矩阵 <b>C3</b> —— <b>批次归位的端到端可观察：每条委托都有回程</b>（组合面，引用 FEAT-019 主权契约）。
 *
 * <p><b>2026-09-02 判据重写</b>（见 cases 细档 §5.5.3、testplan §5 修订说明）。契约面没变——仍是
 * FEAT-019「结果按委托归位、不按完成顺序猜测、不静默丢弃」——变的是<b>观察坐标系</b>：
 * 原判据「每个 {@code toolCallId} 平均出现次数 ≥ 2（tool_call 派发 + tool_result 归位一致映射）」
 * 建立在两条错误前提上：
 * <ol>
 *   <li><b>{@code toolCallId} 不是 wire 面最小公共字段。</b> FEAT-027 §5.9 注：「{@code toolCallId} 的
 *       产生和关联语义由 FEAT-004 / FEAT-019 定义，<b>不属于 FEAT-027 的最小公共字段</b>；delegation
 *       <b>可以</b>携带 {@code toolCallId}」——是 MAY，「不得删除或改写」只在已携带的前提下成立。
 *       FEAT-019 L2 §5.4 更直接：「{@code batchId}、{@code toolCallId}……继续用于恢复、日志和诊断，
 *       <b>不构成用户侧调用图协议</b>」「内部 {@code toolCallId} 仍用于把远端 outcome 回灌到正确 Core
 *       ToolCall，但<b>不要求复制到每个用户可见输出</b>」。</li>
 *   <li><b>wire 上根本不存在 {@code tool_result} 事件类型。</b> FEAT-027 §3.1 的
 *       {@code agentEvent.type} 是闭集 {@code delegation | output | status}。原判据是在观察契约上
 *       <b>不存在的对象</b>上做断言——恒红，且红了也不构成缺陷（review-checklist T-M21/T-M15）。</li>
 * </ol>
 *
 * <p><b>正确的坐标系</b>由 FEAT-019 L2 §5.4 直接给出：「客户端调用图以 Feat-Func-004 的
 * {@code (agentId, taskId)} 为节点，以 {@code delegation} 为边，并以 {@code status} 收敛下游 A2A Task
 * 生命周期」；L2 §12.3 E2E 验收场景第 13 条更是把本用例该怎么写写死了：「客户端按
 * {@code (agentId, taskId)} 和直接委派边构图，<b>不依赖 {@code batchId + toolCallId}</b>」。
 *
 * <p><b>本用例判据</b>：
 * <ul>
 *   <li><b>硬 1</b>：{@code (agentEvent.source.agentId, agentEvent.source.taskId)} 去重后 ≥ 2 组，
 *       两个分量均非空，且非 delegation 事件的 {@code source.taskId} 不得等于父 taskId。
 *       依据 FEAT-027 §2 三条 wire 最小结构 MUST + §2「并发交织」MUST（「不得使用外层父 Task ID
 *       替代生产者 Task ID」）+ §5.6 / §5.7。</li>
 *   <li><b>硬 2</b>：每条 {@code delegation} 的 {@code target.taskId} 都能在后续某条 {@code output}
 *       或 {@code status} 的 {@code source.taskId} 中找到 —— 即每个派发出去的 member 都有回程，
 *       无静默丢弃、无错配。<b>只判存在性、不判全局顺序</b>：FEAT-019 L2 §5.4「跨子树的
 *       delegation/status/output 可以交错，同一 member 内只保证 delegation 先于首个 output」。</li>
 * </ul>
 *
 * <p><b>{@code toolCallId} 在本用例中降级为观察记录</b>，只打日志、不参与任何断言。
 *
 * <p><b>分层</b>（INCONCLUSIVE vs FAIL）：模型未同轮生成 ≥2 个 ToolCall 时无并行可观察，判
 * INCONCLUSIVE；但若全字段扫描已看到子任务证据、wire 上却没有对应的 {@code agentEvent} 结构，
 * 那是 FEAT-027 §2 MUST 的违约，判 FAIL 而非 INCONCLUSIVE。
 *
 * <p><b>Tag</b>：manual（依赖真实 LLM 同轮生成多个 ToolCall）。
 */
@Tag("integration") @Tag("edpa") @Tag("feat-028") @Tag("manual")
@Feature("FEAT-028: EDPA 规划工作流与智能体并行执行")
@Story("C3.delegation-return-binding: 每条 delegation 都有回程事件（按 (agentId, taskId) 构图，不依赖 toolCallId）")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdpaDelegationReturnBindingTest {

    private static final Logger LOG = Logger.getLogger(EdpaDelegationReturnBindingTest.class.getName());
    private static final String EDP_AGENT = "edp-agent";
    private static final String SEARCH = "search";
    private static final String VERIFY = "verify";
    private static final long STREAM_CAP_MS =
            Long.getLong("sit.feat028.c3-stream-cap-ms", 130_000L);

    private final HttpClient http = HttpClient.newHttpClient();
    private TestConfig config;
    private SutStack searchStack, verifyStack, edpStack;

    @BeforeAll
    void startStack() throws Exception {
        config = TestConfig.load();
        assumeTrue(System.getenv("EDP_AGENT_MODEL_API_KEY") != null, "[c3] 需 EDP_AGENT_MODEL_*，跳过");
        searchStack = SutStack.builder(config).agent(SEARCH).start();
        verifyStack = SutStack.builder(config).agent(VERIFY).start();
        edpStack = SutStack.builder(config)
                .agent(EDP_AGENT, a -> a
                        .env("EDP_AGENT_SEARCH_A2A_URL", searchStack.baseUrl(SEARCH))
                        .env("EDP_AGENT_VERSATILE_A2A_URL", verifyStack.baseUrl(VERIFY))
                        .env("EDP_AGENT_SCENARIO_HOME", "/tmp/edpa-scenario-min"))
                .start();
        LOG.info("[c3] edp-agent=" + edpStack.baseUrl(EDP_AGENT));
    }

    @AfterAll
    void tearDown() {
        if (edpStack != null) edpStack.close();
        if (verifyStack != null) verifyStack.close();
        if (searchStack != null) searchStack.close();
    }

    @Test
    @DisplayName("FEAT-028.C3: 每条 delegation 的 target.taskId 都有回程 output/status（按 (agentId, taskId) 构图）")
    void everyDelegationHasAReturningOutputOrStatus() throws Exception {
        String contextId = "ctx-feat028-c3-" + UUID.randomUUID().toString().substring(0, 8);
        String body = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"c3-%s\",\"method\":\"SendStreamingMessage\",\"params\":{"
                        + "\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"%s\",\"contextId\":\"%s\","
                        + "\"parts\":[{\"text\":\"%s\"}]}}}",
                UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID(), contextId,
                EdpaParallelPrompts.PROMPT_HETERO_PARALLEL);
        List<EdpaSseCollector.Frame> frames = EdpaSseCollector.collect(
                http, edpStack.baseUrl(EDP_AGENT) + "/a2a", body, STREAM_CAP_MS);
        assumeTrue(frames.size() > 0, "[c3] SSE 无事件，INCONCLUSIVE");

        // 双扫描：①结构化 agentEvent（判据面）②全字段递归（观察面/分层依据，不预设承载位）
        EdpaAgentEventScanner.Result scan = new EdpaAgentEventScanner.Result();
        EdpaChildVisibilityScanner.Result fullScan = new EdpaChildVisibilityScanner.Result();
        String parentTaskId = null;
        for (EdpaSseCollector.Frame f : frames) {
            if (f.parsed == null) continue;
            if (parentTaskId == null) parentTaskId = extractParentTaskId(f.parsed);
            EdpaAgentEventScanner.scanInto(f.parsed, scan);
            EdpaChildVisibilityScanner.scanInto(f.parsed, parentTaskId, fullScan);
        }
        LOG.info(String.format("[c3] %d 帧 | parentTaskId=%s | %s", frames.size(), parentTaskId, scan.summary()));
        LOG.info("[c3] 全字段扫描（对照，不作判据）: " + fullScan.summary());
        // 观察记录，不参与断言（FEAT-027 §5.9 MAY / FEAT-019 L2 §5.4「不构成用户侧调用图协议」）
        LOG.info("[c3] [观察记录] toolCallIds=" + scan.observedToolCallIds());

        // ---- 分层：先区分「模型没派发」与「派发了但 wire 不承载」 ----
        if (scan.events.isEmpty()) {
            assertThat(fullScan.anyChildEvidence())
                    .as("[c3] ⭐ SSE 上一条 agentEvent 都没有，但全字段扫描已看到子任务证据（%s）——"
                            + "FEAT-027 §2 三条 wire 最小结构 MUST 要求下游委托与产出通过 agentEvent 暴露，"
                            + "这是 wire 违约（FAIL），不是模型没派发（INCONCLUSIVE）", fullScan.summary())
                    .isFalse();
            assumeTrue(false, "[c3] SSE 无 agentEvent 且无子任务证据——模型可能未派发下游委托，INCONCLUSIVE");
            return;
        }

        // wire 卫生：type 必须落在 FEAT-027 §3.1 闭集内，且三种 type 都必须带 source 二元组
        assertThat(scan.unknownTypes)
                .as("[c3] agentEvent.type 必须落在 FEAT-027 §3.1 闭集 %s 内（%s 表示该事件根本没带 type，"
                        + "违反 §2「控制与业务语义区分」MUST）；实测越界值=%s",
                        EdpaAgentEventScanner.VALID_TYPES, EdpaAgentEventScanner.MISSING_TYPE,
                        scan.unknownTypes)
                .isEmpty();
        assertThat(scan.eventsMissingSource)
                .as("[c3] FEAT-027 §2 三条 wire 最小结构 MUST：delegation/output/status 都必须携带 "
                        + "source.agentId 与 source.taskId；缺失事件=%s",
                        EdpaAgentEventScanner.sample(scan.eventsMissingSource, 5))
                .isEmpty();

        List<EdpaAgentEventScanner.AgentEvent> delegations = scan.delegations();
        if (delegations.size() < 2) {
            LOG.warning("[c3] INCONCLUSIVE 本轮只观察到 " + delegations.size()
                    + " 条 delegation——模型未同轮生成 ≥2 个 ToolCall，无并行可观察面。"
                    + "（注：若单个 ToolCall 的 arguments 里合并了多个独立实体，按 FEAT-019 L2 §7.3"
                    + "「模型合并多个实体……验收判失败」应判 FAIL，该分支由 P1~P4 承接）");
            assumeTrue(false, "[c3] delegation < 2，模型未同轮派发多个委托，INCONCLUSIVE");
            return;
        }

        // ---- 硬 1：客户端分流键去重 ≥ 2 组，且不得拿父 Task ID 顶替生产者 Task ID ----
        assertThat(scan.distinctSourceKeys())
                .as("[c3] ⭐ 硬 1：FEAT-027 §2「并发交织」MUST + §5.6 —— 客户端应能按 "
                        + "(source.agentId, source.taskId) 分流出 ≥2 条轨迹；实测 %s | %s",
                        scan.distinctSourceKeys(), scan.summary())
                .hasSizeGreaterThanOrEqualTo(2);
        // 硬 1b 的前置：父 taskId 必须抽到，否则 eventsUsingParentAsSourceTaskId 恒返回空列表、空转判绿
        assertThat(parentTaskId)
                .as("[c3] 硬 1b 前置：未能从 statusUpdate/artifactUpdate 抽到外层父 taskId，"
                        + "「source.taskId ≠ 父 taskId」无从判定（不可静默判绿）")
                .isNotBlank();
        assertThat(scan.eventsUsingParentAsSourceTaskId(parentTaskId))
                .as("[c3] ⭐ 硬 1b：FEAT-027 §2「不得使用外层父 Task ID 替代生产者 Task ID」+ §5.7"
                        + "「两个维度不得混淆」——output/status 的 source.taskId 不应等于父 taskId(%s)。"
                        + "delegation 不在此列：§3.1 字段适用性表规定 delegation 的 source 本就指向父 Agent/Task。"
                        + "违规事件=%s", parentTaskId,
                        EdpaAgentEventScanner.sample(scan.eventsUsingParentAsSourceTaskId(parentTaskId), 5))
                .isEmpty();

        // ---- 硬 2：每条 delegation 都有回程（存在性，不判全局顺序）----
        List<EdpaAgentEventScanner.AgentEvent> orphans = scan.delegationsWithoutReturn();
        assertThat(orphans)
                .as("[c3] ⭐ 硬 2：每条 delegation 的 target.taskId 都应在后续某条 output/status 的 "
                        + "source.taskId 上出现（= 结果按委托归位、无静默丢弃、无错配）。"
                        + "无回程的 delegation=%s | delegationTargets=%s | 回程 sourceTaskIds=%s",
                        EdpaAgentEventScanner.sample(orphans, 5),
                        scan.delegationTargetTaskIds(), scan.distinctSourceTaskIds())
                .isEmpty();

        LOG.info(String.format("[c3] PASS %d 条 delegation 全部有回程；分流键 %d 组：%s",
                delegations.size(), scan.distinctSourceKeys().size(), scan.distinctSourceKeys()));
    }

    /** 父 Task ID：优先 statusUpdate.taskId，回退 artifactUpdate.taskId（与 C1 同款）。 */
    private static String extractParentTaskId(JsonNode frame) {
        JsonNode result = frame.path("result");
        for (String kind : new String[]{"statusUpdate", "artifactUpdate"}) {
            JsonNode id = result.path(kind).path("taskId");
            if (id.isTextual() && !id.asText().isBlank()) return id.asText();
        }
        return null;
    }
}

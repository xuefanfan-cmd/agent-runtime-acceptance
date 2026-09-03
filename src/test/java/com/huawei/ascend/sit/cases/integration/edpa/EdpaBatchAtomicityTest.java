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
 * FEAT-028 矩阵 <b>C1</b> —— 同批多委托原子性（组合面，引用 FEAT-019 主权契约）。
 *
 * <p><b>Spec 依据</b>（testplan §5 C1）：`SendStreamingMessage` 发 `PROMPT_HETERO_PARALLEL`；
 * 若模型同轮生成 N ≥ 2 个 ToolCall，**客户端 wire 面**的可观察证据应显示 **N 条指向不同 member 的
 * delegation 边**——不得只保留最后一个中断、不得静默丢弃任何 ToolCall。
 * 这是 FEAT-019 「同轮批量中断聚合」主权契约在 EDPA 场景端到端面的组合体现。
 * （原文写的是"父任务快照的可观察证据应显示批次 items 数 = N"——快照通道随 P0b/P0c 于 2026-08-24
 * 退出范围，且 `items` 属 core→runtime 内部 envelope，不在客户端观察面；见下两条更正。）
 *
 * <p><b>Spec 更正（2026-08-24）</b>：原判据里"batchId 应对客户端可见"是**误读**——FEAT-019
 * 特性档 §3.1「参考批量中断形态」明确"batchId 可以是 core 或 runtime adapter 内部诊断标识，
 * **不要求外部客户端传入**"（L2 §5.2 另有"batchId 是 runtime 内部诊断标识，不是客户端续轮参数"）；
 * FEAT-028 把 `batchId`/`items`/`toolCallId` 三件套定性为 core→runtime **内部** batch
 * interrupt envelope。**batchId 按设计就不对客户端可见**，本用例不应对其做硬断言。
 *
 * <p><b>Spec 更正（2026-09-02，见 cases 细档 §5.5.3）</b>：上一轮更正把 `batchId` 换成了
 * `toolCallId`，但只换掉了半个错误——`toolCallId` 同样不是 wire 面的最小公共字段。
 * FEAT-027 §5.9 注：「`toolCallId` 的产生和关联语义由 FEAT-004 / FEAT-019 定义，
 * **不属于 FEAT-027 的最小公共字段**；delegation **可以**携带 `toolCallId`」——是 MAY 级扩展字段；
 * FEAT-019 L2 §5.4：「`batchId`、`toolCallId`……继续用于恢复、日志和诊断，**不构成用户侧调用图协议**。
 * 客户端调用图以 `(agentId, taskId)` 为节点，以 `delegation` 为边」。
 * 因此硬 A 改按 **delegation 事件与其 target 二元组** 判定——这才是 FEAT-027 §2/§3.1 定义的
 * wire 面 MUST，也正是"派发了 N 个委托、没有静默丢弃"在客户端黑盒面的合法投影。
 * （注：当前实现的 delegation 事件确实携带了 toolCallId，所以旧判据大概率能过——
 * 这类"碰巧能过"的判据比恒红的更危险，因为它不会提醒你依据错了。）
 *
 * <p><b>更新后的观察策略</b>（判据顺序 = 断言顺序，硬 B 先判）：
 * <ul>
 *   <li><b>硬 B（数据面并行汇总覆盖两件事）</b>：最终 artifact 内容覆盖 search + verify 两个主题——
 *       证明批次全部完成后模型汇总了 ≥ 2 个子结果（不是静默丢弃）。
 *       按 testplan §8，这一层在<b>模型任意规划质量下必须绿</b>，故置于硬 A 的
 *       INCONCLUSIVE 早退之前，避免被跳过；</li>
 *   <li><b>硬 A-0（wire 最小结构）</b>：`agentEvent.type` 落在 FEAT-027 §3.1 闭集内
 *       （缺失 `type` 同样违约，见 {@link EdpaAgentEventScanner#MISSING_TYPE}），
 *       且每条事件的 `source` 二元组非空（§2 三条 wire 最小结构 MUST）；</li>
 *   <li><b>硬 A-1/A-2/A-3（delegation 边 ≥ 2 且指向不同 member）</b>：SSE 里
 *       `agentEvent.type=delegation` 的事件 ≥ 2 条，`target` 二元组<b>逐条</b>非空（A-1）、
 *       `target.taskId` <b>两两不同</b>（A-2）、去重后 ≥ 2 个 member（A-3）——
 *       证明 runtime 派发了 ≥ 2 个委托（不是只保留一个）。
 *       依据 FEAT-027 §3.1 字段适用性表（delegation：source 必须、target 必须）
 *       + §2「delegation 生成」MUST（「不得生成空 target Task ID」、对一个下游 Task「生成一次」）。
 *       <b>注意 A-1 与 A-2 不能用 `delegationTargetTaskIds().size() ≥ 2` 代替</b>——
 *       该集合会静默跳过空值、把重复值折叠为一个，3 条 delegation 里混 1 条空 target
 *       或 2 条同 target 都能蒙混过关。</li>
 * </ul>
 * A + B 同时成立 → 批次原子性组合契约在 EDPA 场景端到端可观察 → PASS。
 * `batchId` / `toolCallId` 作为内部诊断字段的可见性诉求均已撤回，仅保留为日志观察记录。
 *
 * <p><b>Tag</b>：manual。
 */
@Tag("integration") @Tag("edpa") @Tag("feat-028") @Tag("manual")
@Feature("FEAT-028: EDPA 规划工作流与智能体并行执行")
@Story("C1.batch-atomicity: 同批多委托原子性——wire 面 delegation 边 ≥ 2 且 target 互不相同 + 汇总覆盖两件事（batchId/toolCallId 内部诊断字段可见性诉求已撤回）")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdpaBatchAtomicityTest {

    private static final Logger LOG = Logger.getLogger(EdpaBatchAtomicityTest.class.getName());
    private static final String EDP_AGENT = "edp-agent";
    private static final String SEARCH = "search";
    private static final String VERIFY = "verify";
    private static final long STREAM_CAP_MS =
            Long.getLong("sit.feat028.c1-stream-cap-ms", 130_000L);

    private final HttpClient http = HttpClient.newHttpClient();
    private TestConfig config;
    private SutStack searchStack, verifyStack, edpStack;

    @BeforeAll
    void startStack() throws Exception {
        config = TestConfig.load();
        assumeTrue(System.getenv("EDP_AGENT_MODEL_API_KEY") != null, "[c1] 需 EDP_AGENT_MODEL_*，跳过");
        searchStack = SutStack.builder(config).agent(SEARCH).start();
        verifyStack = SutStack.builder(config).agent(VERIFY).start();
        edpStack = SutStack.builder(config)
                .agent(EDP_AGENT, a -> a
                        .env("EDP_AGENT_SEARCH_A2A_URL", searchStack.baseUrl(SEARCH))
                        .env("EDP_AGENT_VERSATILE_A2A_URL", verifyStack.baseUrl(VERIFY))
                        .env("EDP_AGENT_SCENARIO_HOME", "/tmp/edpa-scenario-min"))
                .start();
        LOG.info("[c1] edp-agent=" + edpStack.baseUrl(EDP_AGENT));
    }

    @AfterAll
    void tearDown() {
        if (edpStack != null) edpStack.close();
        if (verifyStack != null) verifyStack.close();
        if (searchStack != null) searchStack.close();
    }

    @Test
    @DisplayName("FEAT-028.C1: 同批多委托原子性——wire 面 delegation 边 ≥ 2 且 target 互不相同 + 汇总覆盖两件事")
    void batchAtomicityViaDelegationEdgesAndAggregatedAnswer() throws Exception {
        String contextId = "ctx-feat028-c1-" + UUID.randomUUID().toString().substring(0, 8);
        String body = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"c1-%s\",\"method\":\"SendStreamingMessage\",\"params\":{"
                        + "\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"%s\",\"contextId\":\"%s\","
                        + "\"parts\":[{\"text\":\"%s\"}]}}}",
                UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID(), contextId,
                EdpaParallelPrompts.PROMPT_HETERO_PARALLEL);
        List<EdpaSseCollector.Frame> frames = EdpaSseCollector.collect(
                http, edpStack.baseUrl(EDP_AGENT) + "/a2a", body, STREAM_CAP_MS);
        LOG.info("[c1] SSE frames=" + frames.size());
        assumeTrue(frames.size() > 0, "[c1] SSE 无事件，INCONCLUSIVE");

        // 观察面 1：全字段递归扫描（复用 EdpaChildVisibilityScanner，与 R1/P0b/P0c 同款）
        String parentTaskId = null;
        StringBuilder allText = new StringBuilder();
        for (EdpaSseCollector.Frame f : frames) {
            if (f.parsed == null) continue;
            JsonNode result = f.parsed.path("result");
            if (parentTaskId == null) {
                parentTaskId = result.path("statusUpdate").path("taskId").asText(null);
                if (parentTaskId == null || parentTaskId.isEmpty()) {
                    parentTaskId = result.path("artifactUpdate").path("taskId").asText(null);
                }
            }
            if ("artifactUpdate".equals(f.eventKind)) {
                for (JsonNode part : result.path("artifactUpdate").path("artifact").path("parts")) {
                    allText.append(part.path("text").asText(""));
                    JsonNode content = part.path("data").path("payload").path("content");
                    if (content.isTextual()) allText.append(content.asText());
                }
            }
        }
        // 全字段扫描聚合（对照面，避免"只查预设几个关键字"的过强判定；不作硬判据）
        EdpaChildVisibilityScanner.Result fullScan = new EdpaChildVisibilityScanner.Result();
        // 结构化 agentEvent 扫描（判据面，FEAT-027 §2/§3.1 wire 最小公共契约）
        EdpaAgentEventScanner.Result scan = new EdpaAgentEventScanner.Result();
        for (EdpaSseCollector.Frame f : frames) {
            if (f.parsed == null) continue;
            EdpaChildVisibilityScanner.scanInto(f.parsed, parentTaskId, fullScan);
            EdpaAgentEventScanner.scanInto(f.parsed, scan);
        }
        LOG.info(String.format("[c1] 全字段扫描（%d 帧，对照）: %s", frames.size(), fullScan.summary()));
        LOG.info("[c1] agentEvent 结构化扫描: " + scan.summary());
        // 观察记录，不参与断言（FEAT-027 §5.9 MAY / FEAT-019 L2 §5.4「不构成用户侧调用图协议」）
        LOG.info("[c1] [观察记录] toolCallIds=" + scan.observedToolCallIds());

        // 观察面 2：间接旁证——artifact 内容覆盖两件事（推理侧确实并行汇总了 ≥ 2 个子结果）
        String text = allText.toString();
        boolean coversSearch = containsAny(text, "虚拟线程", "Virtual Thread", "virtual thread");
        boolean coversVerify = containsAny(text, "OOM", "线程池", "验证", "核查", "结论", "准确", "正确", "错误");
        boolean indirectEvidence = coversSearch && coversVerify;
        LOG.info("[c1] 间接旁证: coversSearch=" + coversSearch + " coversVerify=" + coversVerify);

        // ── 硬 B 先判：testplan §8 要求"结果覆盖两件事"在模型任意规划质量下必须绿，
        //    不能被下面硬 A 的 INCONCLUSIVE 早退跳过 ──
        assertThat(indirectEvidence)
                .as("[c1] ⭐ 硬 B：最终 artifact 应覆盖 search + verify 两个主题（证明批次全部完成后汇总）；"
                        + "coversSearch=%s coversVerify=%s", coversSearch, coversVerify)
                .isTrue();

        // ── 硬 A-0：只要 wire 上出现了 agentEvent，最小结构就是无条件 MUST ──
        // （与 C3/P3/P4 同一套分层，避免同一 prompt 下 C1 判 FAIL 而其余判 INCONCLUSIVE 的不自洽）
        assertThat(scan.unknownTypes)
                .as("[c1] agentEvent.type 必须落在 FEAT-027 §3.1 闭集 %s 内（%s 表示该事件根本没带 type，"
                        + "违反 §2「控制与业务语义区分」MUST）；实测越界值=%s",
                        EdpaAgentEventScanner.VALID_TYPES, EdpaAgentEventScanner.MISSING_TYPE,
                        scan.unknownTypes)
                .isEmpty();
        assertThat(scan.eventsMissingSource)
                .as("[c1] FEAT-027 §2 三条 wire 最小结构 MUST——delegation/output/status 都必须携带"
                        + " source.agentId 与 source.taskId；缺失事件=%s",
                        EdpaAgentEventScanner.sample(scan.eventsMissingSource, 5))
                .isEmpty();

        // ── 硬 A：wire 面 delegation 边 ≥ 2 且指向互不相同的 member（不是只保留一个中断、不静默丢弃）──
        List<EdpaAgentEventScanner.AgentEvent> delegations = scan.delegations();
        if (delegations.size() < 2) {
            LOG.warning("[c1] INCONCLUSIVE 只观察到 " + delegations.size() + " 条 delegation——"
                    + "模型可能未同轮生成 ≥2 个 ToolCall（LLM 抖动），无同批多委托可判原子性。"
                    + "注意：若模型确实同轮生成了 ≥2 个 ToolCall 而 wire 上只有 1 条 delegation，"
                    + "那是真缺陷（批次被折叠），但该情形无法从客户端黑盒面与 LLM 抖动区分——"
                    + "服务端 RemoteInvocationBatchCoordinator 日志可核。");
            assumeTrue(false, "[c1] delegation < 2，模型未同轮派发多个委托，INCONCLUSIVE");
            return;
        }
        assertThat(scan.delegationsMissingTarget())
                .as("[c1] ⭐ 硬 A-1：FEAT-027 §3.1 字段适用性表——delegation 的 target 为**必须**字段；"
                        + "§2「delegation 生成」MUST 另明文「不得生成空 target Task ID」。"
                        + "target.agentId 或 target.taskId 为空的事件=%s",
                        EdpaAgentEventScanner.sample(scan.delegationsMissingTarget(), 5))
                .isEmpty();
        assertThat(scan.delegationsWithDuplicateTarget())
                .as("[c1] ⭐ 硬 A-2：%d 条 delegation 的 target.taskId 必须**两两不同**——"
                        + "FEAT-027 §2「delegation 生成」MUST 对一个下游 Task 只「生成一次」delegation，"
                        + "重复即意味着两个 member 被折叠到同一个子 Task 上；重复事件=%s | 去重后 target=%s",
                        delegations.size(),
                        EdpaAgentEventScanner.sample(scan.delegationsWithDuplicateTarget(), 5),
                        scan.delegationTargetTaskIds())
                .isEmpty();
        assertThat(scan.delegationTargetTaskIds())
                .as("[c1] ⭐ 硬 A-3：%d 条 delegation 应指向 ≥ 2 个互不相同的 member；实测 %s | %s",
                        delegations.size(), scan.delegationTargetTaskIds(), scan.summary())
                .hasSizeGreaterThanOrEqualTo(2);
        LOG.info("[c1] PASS 批次原子性组合契约（硬 A + 硬 B 双证）；batchId 与 toolCallId 均为内部诊断字段，"
                + "按设计不构成用户侧调用图协议（FEAT-019 L2 §5.4 / FEAT-027 §5.9），已从判据里撤回");
    }


    private static boolean containsAny(String text, String... needles) {
        String lower = text.toLowerCase();
        for (String n : needles) if (text.contains(n) || lower.contains(n.toLowerCase())) return true;
        return false;
    }
}

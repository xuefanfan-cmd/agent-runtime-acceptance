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
 * FEAT-028 矩阵 <b>P4</b> —— 异构混合并行（SSE）。同 P3 结构，prompt 换 HETERO，断言两件事覆盖异构主题。
 *
 * <p><b>2026-09-02 补强</b>（见 cases 细档 §5.5.3 / testplan §5 修订说明）：原 testplan 把
 * `source.agentId` / `source.taskId` 写成「<b>若存在</b>」——等级漂移的反方向。FEAT-027 §2 三条
 * wire 协议最小结构是 <b>MUST</b>：delegation / output / status 都<b>必须</b>携带
 * `source.agentId` 与 `source.taskId`。本用例在 P3 的硬 1 / 硬 2 之上追加异构专属的硬 3：
 * <ul>
 *   <li><b>硬 1</b>：`agentEvent.type` 落在闭集内，且每条事件的 source 二元组均非空（§2 / §3.1）；</li>
 *   <li><b>硬 2</b>：`(source.agentId, source.taskId)` 去重 ≥ 2 组，且非 delegation 事件的
 *       `source.taskId` ≠ 外层父 taskId（§2「并发交织」MUST + §5.6 + §5.7）；</li>
 *   <li><b>硬 3（异构专属）</b>：去重后的 `source.agentId` 集合应含 <b>2 个不同值</b>——
 *       search 与 verify 是两个不同的下游 agent，异构并行必须在 agentId 维度上可区分，
 *       而不只是 taskId 维度。仅 taskId 不同、agentId 相同说明两个委托打到了同一个下游。</li>
 * </ul>
 *
 * <p><b>Tag</b>：manual —— 依赖真实 LLM、search、versatile。
 */
@Tag("integration") @Tag("edpa") @Tag("feat-028") @Tag("manual")
@Feature("FEAT-028: EDPA 规划工作流与智能体并行执行")
@Story("P4.hetero-parallel-streaming: 异构混合并行 SSE 模式，agentEvent 分流出两个不同下游 agentId + 覆盖搜索与验证")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdpaHeteroParallelStreamingTest {

    private static final Logger LOG = Logger.getLogger(EdpaHeteroParallelStreamingTest.class.getName());
    private static final String EDP_AGENT = "edp-agent";
    private static final String SEARCH = "search";
    private static final String VERIFY = "verify";
    private static final long STREAM_CAP_MS =
            Long.getLong("sit.feat028.p4-stream-cap-ms", 130_000L);
    /**
     * <b>仅用于诊断日志，不参与判定</b>。2026-09-02 前这是「超限即 {@code assumeTrue(false)} 判
     * INCONCLUSIVE」的启发式，已废止——它位于全部硬断言之后，唯一效果是把一次已经全绿的运行改判成
     * skip，理由还与契约无关，属<b>误黄</b>（丢证据）。见 cases §5.5.4。
     */
    private static final long PARALLEL_DIAGNOSTIC_HINT_MS = 90_000L;

    private final HttpClient http = HttpClient.newHttpClient();
    private TestConfig config;
    private SutStack searchStack, verifyStack, edpStack;

    @BeforeAll
    void startStack() throws Exception {
        config = TestConfig.load();
        assumeTrue(System.getenv("EDP_AGENT_MODEL_API_KEY") != null, "[p4] 需 EDP_AGENT_MODEL_*，跳过");
        searchStack = SutStack.builder(config).agent(SEARCH).start();
        verifyStack = SutStack.builder(config).agent(VERIFY).start();
        edpStack = SutStack.builder(config)
                .agent(EDP_AGENT, a -> a
                        .env("EDP_AGENT_SEARCH_A2A_URL", searchStack.baseUrl(SEARCH))
                        .env("EDP_AGENT_VERSATILE_A2A_URL", verifyStack.baseUrl(VERIFY))
                        .env("EDP_AGENT_SCENARIO_HOME", "/tmp/edpa-scenario-min"))
                .start();
        LOG.info("[p4] edp-agent=" + edpStack.baseUrl(EDP_AGENT));
    }

    @AfterAll
    void tearDown() {
        if (edpStack != null) edpStack.close();
        if (verifyStack != null) verifyStack.close();
        if (searchStack != null) searchStack.close();
    }

    @Test
    @DisplayName("FEAT-028.P4: 异构混合并行 SSE——事件流过程 + 终态 + 覆盖搜索与验证结论 + 并行启发式")
    void heteroParallelStreamingCoversSearchAndVerifyViaSse() throws Exception {
        String contextId = "ctx-feat028-p4-" + UUID.randomUUID().toString().substring(0, 8);
        String body = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"p4-%s\",\"method\":\"SendStreamingMessage\",\"params\":{"
                        + "\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"%s\",\"contextId\":\"%s\","
                        + "\"parts\":[{\"text\":\"%s\"}]}}}",
                UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID(), contextId,
                EdpaParallelPrompts.PROMPT_HETERO_PARALLEL);

        long t0 = System.currentTimeMillis();
        List<EdpaSseCollector.Frame> frames = EdpaSseCollector.collect(
                http, edpStack.baseUrl(EDP_AGENT) + "/a2a", body, STREAM_CAP_MS);
        long totalElapsed = System.currentTimeMillis() - t0;
        LOG.info(String.format("[p4] SSE frames=%d totalElapsed=%dms", frames.size(), totalElapsed));

        assertThat(frames.size()).as("[p4] 流式帧应 ≥ 2").isGreaterThanOrEqualTo(2);

        boolean sawTerminal = false;
        int artifactFrames = 0;
        StringBuilder text = new StringBuilder();
        // 结构化 agentEvent 扫描（FEAT-027 §2/§3.1 wire 最小公共契约）
        EdpaAgentEventScanner.Result scan = new EdpaAgentEventScanner.Result();
        String parentTaskId = null;
        for (EdpaSseCollector.Frame f : frames) {
            if (f.parsed == null) continue;
            JsonNode result = f.parsed.path("result");
            EdpaAgentEventScanner.scanInto(f.parsed, scan);
            if ("statusUpdate".equals(f.eventKind)) {
                String state = result.path("statusUpdate").path("status").path("state").asText("");
                if (state.contains("COMPLETED") || state.contains("FAILED")
                        || state.contains("CANCELED") || state.contains("REJECTED")) sawTerminal = true;
                if (parentTaskId == null) {
                    String tid = result.path("statusUpdate").path("taskId").asText("");
                    if (!tid.isEmpty()) parentTaskId = tid;
                }
            } else if ("artifactUpdate".equals(f.eventKind)) {
                artifactFrames++;
                if (parentTaskId == null) {
                    String tid = result.path("artifactUpdate").path("taskId").asText("");
                    if (!tid.isEmpty()) parentTaskId = tid;
                }
                for (JsonNode part : result.path("artifactUpdate").path("artifact").path("parts")) {
                    text.append(part.path("text").asText("")).append("\n");
                    JsonNode content = part.path("data").path("payload").path("content");
                    if (content.isTextual()) text.append(content.asText()).append("\n");
                }
            }
        }
        assertThat(sawTerminal).as("[p4] 应观察到终态帧").isTrue();
        assertThat(artifactFrames).as("[p4] artifactUpdate 帧应 ≥ 1").isGreaterThanOrEqualTo(1);
        LOG.info("[p4] agentEvent 扫描: " + scan.summary());

        // ── 覆盖两件事：testplan §8 要求这一层在模型任意规划质量下必须绿，
        //    故置于下面 delegation<2 的 INCONCLUSIVE 早退之前，避免被跳过 ──
        String t = text.toString();
        boolean coversSearch = containsAny(t, "虚拟线程", "Virtual Thread", "virtual thread");
        boolean coversVerify = containsAny(t, "OOM", "线程池", "验证", "核查", "结论", "准确", "正确", "错误", "存疑");
        assertThat(coversSearch).as("[p4] 未覆盖 search 主题；前 500=%s", truncate(t, 500)).isTrue();
        assertThat(coversVerify).as("[p4] 未覆盖 verify 结论；前 500=%s", truncate(t, 500)).isTrue();

        // ── 硬 1：wire 最小结构（FEAT-027 §2 三条 MUST + §3.1 type 闭集）──
        assertThat(scan.unknownTypes)
                .as("[p4] agentEvent.type 必须落在 FEAT-027 §3.1 闭集 %s 内（%s 表示该事件根本没带 "
                        + "type，违反 §2「控制与业务语义区分」MUST）；实测越界值=%s",
                        EdpaAgentEventScanner.VALID_TYPES, EdpaAgentEventScanner.MISSING_TYPE,
                        scan.unknownTypes)
                .isEmpty();
        assertThat(scan.eventsMissingSource)
                .as("[p4] ⭐ 硬 1：FEAT-027 §2 三条 wire 最小结构 MUST——delegation/output/status 都"
                        + "必须携带 source.agentId 与 source.taskId（不是「若存在」）；缺失事件=%s",
                        EdpaAgentEventScanner.sample(scan.eventsMissingSource, 5))
                .isEmpty();

        // ── 硬 2 / 硬 3：分流出两条轨迹，且分属两个不同下游 agentId ──
        if (scan.delegations().size() < 2) {
            // 2026-09-03：同 P3，按 EDPA L2 §7.3 错误表面验收表分流，不再一律 INCONCLUSIVE。
            // 异构侧的主题词集取「动作意图」而非实体名——P4 两件事都围绕虚拟线程，
            // 用实体名做区分会两边恒命中、判据退化成恒红（见 EdpaParallelPrompts.HETERO_TOPIC_A 注释）。
            List<EdpaToolCallArgumentsAssembler.ToolCall> calls =
                    EdpaToolCallArgumentsAssembler.assemble(frames);
            LOG.info("[p4] delegation=" + scan.delegations().size()
                    + "，进入 L2 §7.3 分流；重组 ToolCall=" + EdpaToolCallArgumentsAssembler.summary(calls));
            EdpaMergedEntityJudge.Verdict v = EdpaMergedEntityJudge.judge(
                    calls, EdpaParallelPrompts.HETERO_TOPIC_A, EdpaParallelPrompts.HETERO_TOPIC_B);
            assertThat(v.kind)
                    .as("[p4] ⭐ EDPA L2 §7.3 错误表面验收表：「模型合并多个实体——单 ToolCall 参数包含"
                            + "多个独立实体时，视为规划质量问题，验收判失败」。异构场景下这还意味着"
                            + "两类不同工具（search / verify）的职责被并进了同一个委托。%n"
                            + "判据详情：%s%n"
                            + "【首次判红请先看上面的参数原文人工复核】", v.detail)
                    .isNotEqualTo(EdpaMergedEntityJudge.Kind.MERGED);
            LOG.warning("[p4] INCONCLUSIVE 只观察到 " + scan.delegations().size()
                    + " 条 delegation，且非合并实体（" + v.kind + "）——模型未同轮生成 ≥2 个 ToolCall，"
                    + "无异构并行可分流。" + v.detail);
            assumeTrue(false, "[p4] delegation < 2 且非合并实体（" + v.kind
                    + "），模型未同轮派发多个委托，INCONCLUSIVE");
            return;
        }
        assertThat(scan.distinctSourceKeys())
                .as("[p4] ⭐ 硬 2：FEAT-027 §2「并发交织」MUST + §5.6——应能按 "
                        + "(source.agentId, source.taskId) 分流出 ≥2 条并行轨迹；实测 %s | %s",
                        scan.distinctSourceKeys(), scan.summary())
                .hasSizeGreaterThanOrEqualTo(2);
        // 硬 2b 的前置：父 taskId 必须抽到，否则 eventsUsingParentAsSourceTaskId 恒返回空列表、空转判绿
        assertThat(parentTaskId)
                .as("[p4] 硬 2b 前置：未能从 statusUpdate/artifactUpdate 抽到外层父 taskId，"
                        + "「source.taskId ≠ 父 taskId」无从判定（不可静默判绿）")
                .isNotBlank();
        assertThat(scan.eventsUsingParentAsSourceTaskId(parentTaskId))
                .as("[p4] ⭐ 硬 2b：FEAT-027 §2「不得使用外层父 Task ID 替代生产者 Task ID」+ §5.7"
                        + "「两个维度不得混淆」——delegation 不在此列（§3.1 字段适用性表规定其 source 本就指向父）；"
                        + "父 taskId=%s，违规事件=%s", parentTaskId,
                        EdpaAgentEventScanner.sample(scan.eventsUsingParentAsSourceTaskId(parentTaskId), 5))
                .isEmpty();
        assertThat(scan.distinctSourceAgentIds())
                .as("[p4] ⭐ 硬 3（异构专属）：去重后的 source.agentId 应含 2 个不同下游 agent"
                        + "（search 与 verify）——只有 taskId 不同、agentId 相同说明两个委托打到了同一下游，"
                        + "不构成异构并行。依据 FEAT-027 §2「agentId 来源」MUST：远端身份取 a2a_delegate "
                        + "上下文中非空的 agentName，「不得用外层父 Task 的 agentId、当前 Runtime 自身 agentId "
                        + "或 tool name 填补」——两个下游配置不同即必须在 agentId 维度可区分。"
                        + "实测 %s | delegations=%s",
                        scan.distinctSourceAgentIds(), EdpaAgentEventScanner.sample(scan.delegations(), 5))
                .hasSizeGreaterThanOrEqualTo(2);
        LOG.info("[p4] [诊断] 各轨迹到达序号窗口（按 source.taskId 分流，FEAT-027 §5.6；"
                + "仅诊断，§2 明文不依赖跨生产者到达顺序）=" + scan.arrivalWindowsSummary());

        // ── 诊断（不判定）：记录总耗时 ──
        // 2026-09-02：原「超限 assumeTrue(false)」已废止，属误黄（把全绿运行改判成 skip，理由与契约无关）。
        // 本用例的并行/异构证据是硬 2（分流键去重）与硬 3（source.agentId 去重），与耗时无关。见 cases §5.5.4。
        LOG.info(String.format("[p4] 诊断（不参与判定）：totalElapsed=%dms，经验参考值 %dms。",
                totalElapsed, PARALLEL_DIAGNOSTIC_HINT_MS));
        LOG.info("[p4] PASS：覆盖两件事 + wire 最小结构合规 + 分流轨迹 ≥2 + source.agentId 异构 ≥2");
    }

    private static boolean containsAny(String text, String... needles) {
        String lower = text.toLowerCase();
        for (String n : needles) if (text.contains(n) || lower.contains(n.toLowerCase())) return true;
        return false;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}

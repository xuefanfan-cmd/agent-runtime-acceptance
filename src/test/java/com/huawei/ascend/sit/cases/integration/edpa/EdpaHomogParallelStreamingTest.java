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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-028 矩阵 <b>P3</b> —— 同类型批量并行（SSE）。
 *
 * <p><b>Spec 依据</b>（testplan §5 P3）：`SendStreamingMessage(PROMPT_HOMOG_PARALLEL)`；从 SSE
 * 事件流观察 statusUpdate/artifactUpdate 帧，硬断言：①流式帧 ≥ 2 帧（存在过程观察）；
 * ②终态状态出现（COMPLETED/FAILED）；③artifactUpdate 帧覆盖两件事（虚拟线程 + GC）；
 * ④见下「2026-09-02 补强」的 agentEvent 分流判据；⑤总耗时符合并行启发式（弱证明）。
 *
 * <p><b>2026-09-02 补强</b>（见 cases 细档 §5.5.3 / testplan §5 修订说明）：原 testplan 把
 * `source.agentId` / `source.taskId` 写成「<b>若存在</b>」——这是等级漂移的<b>反方向</b>：
 * FEAT-027 §2 给出的三条 wire 协议最小结构是 <b>MUST</b>，delegation / output / status
 * 三种 `agentEvent` 都<b>必须</b>携带 `source.agentId` 与 `source.taskId`
 * （「此最小结构为客户端黑盒消费的公共契约，不是 OUT」）。把强制项写成条件项等于给缺失留绿灯通道，
 * 所以本用例把它提为硬断言：
 * <ul>
 *   <li><b>硬 1</b>：`agentEvent.type` 落在闭集 {delegation, output, status} 内（FEAT-027 §3.1），
 *       且每条事件的 `source.agentId` / `source.taskId` 均非空（§2 三条 MUST）；</li>
 *   <li><b>硬 2</b>：`(source.agentId, source.taskId)` 去重 ≥ 2 组，且非 delegation 事件的
 *       `source.taskId` 不得等于外层父 taskId —— FEAT-027 §2「并发交织」MUST
 *       「不得使用外层父 Task ID 替代生产者 Task ID」+ §5.6 分流依据 + §5.7「两个维度不得混淆」。</li>
 * </ul>
 * 注意 `observedOuterTaskIds` 收的是<b>外层</b> `TaskArtifactUpdateEvent.taskId`（父 Task SSE 维度），
 * 与 `agentEvent.source.taskId`（实际生产者维度）是两回事，仅作诊断对照，不作分流依据。
 *
 * <p><b>Tag</b>：manual —— 依赖真实 LLM、search-agent。
 */
@Tag("integration") @Tag("edpa") @Tag("feat-028") @Tag("manual")
@Feature("FEAT-028: EDPA 规划工作流与智能体并行执行")
@Story("P3.homog-parallel-streaming: 同类型批量并行 SSE 模式，事件流承载过程 + agentEvent 可分流出两条轨迹 + 终态覆盖两件事")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdpaHomogParallelStreamingTest {

    private static final Logger LOG = Logger.getLogger(EdpaHomogParallelStreamingTest.class.getName());
    private static final String EDP_AGENT = "edp-agent";
    private static final String SEARCH = "search";
    private static final String VERIFY = "verify";
    private static final long STREAM_CAP_MS =
            Long.getLong("sit.feat028.p3-stream-cap-ms", 130_000L);
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
        assumeTrue(System.getenv("EDP_AGENT_MODEL_API_KEY") != null, "[p3] 需 EDP_AGENT_MODEL_*，跳过");
        searchStack = SutStack.builder(config).agent(SEARCH).start();
        verifyStack = SutStack.builder(config).agent(VERIFY).start();
        edpStack = SutStack.builder(config)
                .agent(EDP_AGENT, a -> a
                        .env("EDP_AGENT_SEARCH_A2A_URL", searchStack.baseUrl(SEARCH))
                        .env("EDP_AGENT_VERSATILE_A2A_URL", verifyStack.baseUrl(VERIFY))
                        .env("EDP_AGENT_SCENARIO_HOME", "/tmp/edpa-scenario-min"))
                .start();
        LOG.info("[p3] edp-agent=" + edpStack.baseUrl(EDP_AGENT));
    }

    @AfterAll
    void tearDown() {
        if (edpStack != null) edpStack.close();
        if (verifyStack != null) verifyStack.close();
        if (searchStack != null) searchStack.close();
    }

    @Test
    @DisplayName("FEAT-028.P3: 同类型批量并行 SSE——事件流过程 + 终态 + 覆盖两件事 + 并行启发式")
    void homogParallelStreamingCoversBothTopicsViaSseWithParallelism() throws Exception {
        String contextId = "ctx-feat028-p3-" + UUID.randomUUID().toString().substring(0, 8);
        String body = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"p3-%s\",\"method\":\"SendStreamingMessage\",\"params\":{"
                        + "\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"%s\",\"contextId\":\"%s\","
                        + "\"parts\":[{\"text\":\"%s\"}]}}}",
                UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID(), contextId,
                EdpaParallelPrompts.PROMPT_HOMOG_PARALLEL);

        long t0 = System.currentTimeMillis();
        List<EdpaSseCollector.Frame> frames = EdpaSseCollector.collect(
                http, edpStack.baseUrl(EDP_AGENT) + "/a2a", body, STREAM_CAP_MS);
        long totalElapsed = System.currentTimeMillis() - t0;
        LOG.info(String.format("[p3] SSE frames=%d totalElapsed=%dms", frames.size(), totalElapsed));

        assertThat(frames.size()).as("[p3] 流式事件帧应 ≥ 2（承载过程观察）").isGreaterThanOrEqualTo(2);

        // 分帧统计
        int statusFrames = 0, artifactFrames = 0;
        boolean sawTerminal = false;
        StringBuilder allText = new StringBuilder();
        // 外层父 Task SSE 维度（FEAT-027 §5.7），仅作诊断对照，不作分流依据
        Set<String> observedOuterTaskIds = new HashSet<>();
        // 结构化 agentEvent 扫描（FEAT-027 §2/§3.1 wire 最小公共契约）——真正的分流面
        EdpaAgentEventScanner.Result scan = new EdpaAgentEventScanner.Result();
        String parentTaskId = null;
        for (EdpaSseCollector.Frame f : frames) {
            if (f.parsed == null) continue;
            JsonNode result = f.parsed.path("result");
            EdpaAgentEventScanner.scanInto(f.parsed, scan);
            if ("statusUpdate".equals(f.eventKind)) {
                statusFrames++;
                String state = result.path("statusUpdate").path("status").path("state").asText("");
                if (state.contains("COMPLETED") || state.contains("FAILED")
                        || state.contains("CANCELED") || state.contains("REJECTED")) sawTerminal = true;
                String tid = result.path("statusUpdate").path("taskId").asText("");
                if (!tid.isEmpty()) { observedOuterTaskIds.add(tid); if (parentTaskId == null) parentTaskId = tid; }
            } else if ("artifactUpdate".equals(f.eventKind)) {
                artifactFrames++;
                String tid = result.path("artifactUpdate").path("taskId").asText("");
                if (!tid.isEmpty()) { observedOuterTaskIds.add(tid); if (parentTaskId == null) parentTaskId = tid; }
                for (JsonNode part : result.path("artifactUpdate").path("artifact").path("parts")) {
                    allText.append(part.path("text").asText("")).append("\n");
                    // llm_reasoning payload 也能算内容
                    JsonNode content = part.path("data").path("payload").path("content");
                    if (content.isTextual()) allText.append(content.asText()).append("\n");
                }
            }
        }
        LOG.info(String.format("[p3] statusFrames=%d artifactFrames=%d sawTerminal=%s observedOuterTaskIds=%s",
                statusFrames, artifactFrames, sawTerminal, observedOuterTaskIds));
        LOG.info("[p3] agentEvent 扫描: " + scan.summary());

        assertThat(sawTerminal).as("[p3] 应观察到终态帧").isTrue();
        assertThat(artifactFrames).as("[p3] artifactUpdate 帧应 ≥ 1（过程输出）").isGreaterThanOrEqualTo(1);

        // ── 覆盖两件事：testplan §8 要求这一层在模型任意规划质量下必须绿，
        //    故置于下面 delegation<2 的 INCONCLUSIVE 早退之前，避免被跳过 ──
        String text = allText.toString();
        boolean coversVT = containsAny(text, "虚拟线程", "Virtual Thread", "virtual thread");
        boolean coversGC = containsAny(text, "GC", "ZGC", "G1", "Shenandoah", "垃圾回收", "垃圾收集");
        assertThat(coversVT).as("[p3] artifact 内容未覆盖虚拟线程主题；前 500 字符=%s",
                truncate(text, 500)).isTrue();
        assertThat(coversGC).as("[p3] artifact 内容未覆盖 GC 主题；前 500 字符=%s",
                truncate(text, 500)).isTrue();

        // ── 硬 1：wire 最小结构（FEAT-027 §2 三条 MUST + §3.1 type 闭集）──
        // 只要 wire 上出现了 agentEvent，这三条就是无条件 MUST；一条都没有则属"模型未派发"的分层分支。
        assertThat(scan.unknownTypes)
                .as("[p3] agentEvent.type 必须落在 FEAT-027 §3.1 闭集 %s 内（%s 表示该事件根本没带 "
                        + "type，违反 §2「控制与业务语义区分」MUST「客户端不得仅依赖 Artifact 文本内容"
                        + "推断事件类型」）；实测越界值=%s",
                        EdpaAgentEventScanner.VALID_TYPES, EdpaAgentEventScanner.MISSING_TYPE,
                        scan.unknownTypes)
                .isEmpty();
        assertThat(scan.eventsMissingSource)
                .as("[p3] ⭐ 硬 1：FEAT-027 §2 三条 wire 最小结构 MUST——delegation/output/status 都"
                        + "必须携带 source.agentId 与 source.taskId（不是「若存在」）；缺失事件=%s",
                        EdpaAgentEventScanner.sample(scan.eventsMissingSource, 5))
                .isEmpty();

        // ── 硬 2：能按 (source.agentId, source.taskId) 分流出 ≥2 条并行轨迹 ──
        if (scan.delegations().size() < 2) {
            // 2026-09-03：原先这里无条件 assumeTrue(false) 判 INCONCLUSIVE，把 EDPA L2 §7.3
            // 错误表面验收表里「模型合并多个实体 → 验收判失败」那一行永久静默了（漏红）。
            // 现按该表分流：合并实体 → FAIL；真单实体 / 跨轮串行 → 容忍 INCONCLUSIVE；
            // 参数串重组不出 → 证据不足，仍判 INCONCLUSIVE（不得据缺失判失败）。
            List<EdpaToolCallArgumentsAssembler.ToolCall> calls =
                    EdpaToolCallArgumentsAssembler.assemble(frames);
            LOG.info("[p3] delegation=" + scan.delegations().size()
                    + "，进入 L2 §7.3 分流；重组 ToolCall=" + EdpaToolCallArgumentsAssembler.summary(calls));
            EdpaMergedEntityJudge.Verdict v = EdpaMergedEntityJudge.judge(
                    calls, EdpaParallelPrompts.HOMOG_TOPIC_A, EdpaParallelPrompts.HOMOG_TOPIC_B);
            assertThat(v.kind)
                    .as("[p3] ⭐ EDPA L2 §7.3 错误表面验收表：「模型合并多个实体——单 ToolCall 参数包含"
                            + "多个独立实体时，视为规划质量问题，验收判失败」。prompt 已显式声明两件事"
                            + "互不依赖、要求并行，模型却把两件事塞进同一个委托。%n"
                            + "判据详情：%s%n"
                            + "【首次判红请先看上面的参数原文人工复核：关键词命中是措辞敏感的近似判据，"
                            + "需确认确为「一个委托办两件事」而非参数里顺带提及另一主题词】",
                            v.detail)
                    .isNotEqualTo(EdpaMergedEntityJudge.Kind.MERGED);
            LOG.warning("[p3] INCONCLUSIVE 只观察到 " + scan.delegations().size()
                    + " 条 delegation，且非合并实体（" + v.kind + "）——模型未同轮生成 ≥2 个 ToolCall，"
                    + "无并行轨迹可分流。" + v.detail);
            assumeTrue(false, "[p3] delegation < 2 且非合并实体（" + v.kind
                    + "），模型未同轮派发多个委托，INCONCLUSIVE");
            return;
        }
        assertThat(scan.distinctSourceKeys())
                .as("[p3] ⭐ 硬 2：FEAT-027 §2「并发交织」MUST + §5.6——客户端应能按 "
                        + "(source.agentId, source.taskId) 分流出 ≥2 条并行轨迹；实测 %s | %s",
                        scan.distinctSourceKeys(), scan.summary())
                .hasSizeGreaterThanOrEqualTo(2);
        // 硬 2b 的前置：父 taskId 必须抽到，否则 eventsUsingParentAsSourceTaskId 恒返回空列表、空转判绿
        assertThat(parentTaskId)
                .as("[p3] 硬 2b 前置：未能从 statusUpdate/artifactUpdate 抽到外层父 taskId，"
                        + "「source.taskId ≠ 父 taskId」无从判定（不可静默判绿）；外层 taskId 观察集=%s",
                        observedOuterTaskIds)
                .isNotBlank();
        assertThat(scan.eventsUsingParentAsSourceTaskId(parentTaskId))
                .as("[p3] ⭐ 硬 2b：FEAT-027 §2「不得使用外层父 Task ID 替代生产者 Task ID」+ §5.7"
                        + "「两个维度不得混淆」——output/status 的 source.taskId 不应等于父 taskId(%s)。"
                        + "delegation 不在此列：§3.1 字段适用性表规定 delegation 的 source 本就指向父 Agent/Task。"
                        + "违规事件=%s", parentTaskId,
                        EdpaAgentEventScanner.sample(scan.eventsUsingParentAsSourceTaskId(parentTaskId), 5))
                .isEmpty();
        LOG.info("[p3] [诊断] 各轨迹到达序号窗口（按 source.taskId 分流，FEAT-027 §5.6；"
                + "仅诊断，§2 明文不依赖跨生产者到达顺序）=" + scan.arrivalWindowsSummary());

        // ── 诊断（不判定）：记录总耗时 ──
        // 2026-09-02：原「若 totalElapsed >= 90s 则 assumeTrue(false) 判 INCONCLUSIVE」已废止。
        // 它置于全部硬断言之后，唯一效果是把一次**已经全绿**的运行改判成 skip——理由还与契约无关
        // （模型慢、网络抖、下游慢都能超限）。这不是漏红也不是误红，是**误黄**：把有效证据丢掉。
        // 本用例的并行证据是硬 2 的分流键去重，与耗时无关。同源清理见 cases §5.5.4。
        LOG.info(String.format("[p3] 诊断（不参与判定）：totalElapsed=%dms，经验参考值 %dms。",
                totalElapsed, PARALLEL_DIAGNOSTIC_HINT_MS));
        LOG.info("[p3] PASS：覆盖两件事 + wire 最小结构合规 + 可分流出 ≥2 条并行轨迹");
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

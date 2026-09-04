package com.huawei.ascend.sit.cases.integration.edpa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-028 <b>金丝雀（第二只）</b> —— C2/S1/P3/P4 三个新判据的<b>可开火性自检</b>。
 *
 * <p><b>为什么要有它</b>：本轮返工的主线发现是「判据看起来绿、其实永远不会红」
 * （C2 终态帧恒真、S1 关键词判据、P3/P4 无条件 INCONCLUSIVE、N1 恒真）。
 * 2026-09-03 新落码的三条判据同样是「难以从绿灯反推它是否还活着」的类型，
 * 所以照 {@link EdpaModeFieldScannerSelfTest}（N1 看守的金丝雀）的先例，
 * 用<b>合成 wire 数据</b>把每条判据的<b>红</b>与<b>绿</b>两个方向都钉住。
 *
 * <p><b>不依赖 SUT / LLM / 网络</b>，非 manual，CI 常驻。它红了说明判据实现被改坏了，
 * 与被测系统无关。
 */
@Tag("edpa") @Tag("feat-028")
@Feature("FEAT-028: EDPA 规划工作流与智能体并行执行")
@Story("judge-canary.self-test: 合并实体判据 / 父段回程判据 / 数据面过滤 的可开火性自检")
class EdpaJudgeSelfTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static EdpaSseCollector.Frame frame(String json) {
        try {
            JsonNode parsed = MAPPER.readTree(json);
            String kind = parsed.path("result").has("artifactUpdate") ? "artifactUpdate"
                    : parsed.path("result").has("statusUpdate") ? "statusUpdate" : "unknown";
            return new EdpaSseCollector.Frame(System.currentTimeMillis(), json, parsed, kind);
        } catch (Exception e) {
            throw new IllegalStateException("自检数据写错了：" + json, e);
        }
    }

    /** 一帧 llm_output delta：携带一个 tool_calls 片段。 */
    private static EdpaSseCollector.Frame toolCallFrame(String taskId, int index, String name,
                                                        String argsFragment, String id) {
        String json = String.format(
                "{\"result\":{\"artifactUpdate\":{\"taskId\":\"parent-1\",\"artifact\":{\"parts\":[{\"data\":"
                        + "{\"type\":\"llm_output\",\"payload\":{\"task_id\":\"%s\",\"tool_calls\":[{"
                        + "\"index\":%d,\"id\":%s,\"type\":\"function\",\"name\":\"%s\",\"arguments\":%s}]}}}]}}}}",
                taskId, index, id == null ? "null" : "\"" + id + "\"", name,
                MAPPER.valueToTree(argsFragment).toString());
        return frame(json);
    }

    /** 一帧带 agentEvent 的透传/控制帧。 */
    private static EdpaSseCollector.Frame agentEventFrame(String type, String srcAgent, String srcTask,
                                                          String tgtAgent, String tgtTask,
                                                          String state, String text) {
        StringBuilder ev = new StringBuilder("{\"type\":\"" + type + "\",\"source\":{\"agentId\":\""
                + srcAgent + "\",\"taskId\":\"" + srcTask + "\"}");
        if (tgtAgent != null) {
            ev.append(",\"target\":{\"agentId\":\"").append(tgtAgent)
                    .append("\",\"taskId\":\"").append(tgtTask).append("\"}");
        }
        if (state != null) ev.append(",\"state\":\"").append(state).append("\"");
        ev.append("}");
        String json = String.format(
                "{\"result\":{\"artifactUpdate\":{\"taskId\":\"parent-1\",\"artifact\":{\"parts\":[{"
                        + "\"text\":%s,\"agentEvent\":%s}]}}}}",
                MAPPER.valueToTree(text == null ? "" : text).toString(), ev);
        return frame(json);
    }

    /** 父 Agent 自身输出帧：无 agentEvent。 */
    private static EdpaSseCollector.Frame parentOutputFrame(String text) {
        return frame(String.format(
                "{\"result\":{\"artifactUpdate\":{\"taskId\":\"parent-1\",\"artifact\":{\"parts\":[{\"text\":%s}]}}}}",
                MAPPER.valueToTree(text).toString()));
    }

    // ──────────────────────────────────────────────────────────
    // 1. arguments 跨帧重组（不重组 → 合并实体判据永远不开火）
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("canary: tool_calls[].arguments 的 token 级片段能按 (task_id,index) 跨帧重组")
    void argumentsFragmentsAreReassembledAcrossFrames() {
        List<EdpaSseCollector.Frame> frames = List.of(
                toolCallFrame("t-1", 0, "search-agent", "", "call_abc"),
                toolCallFrame("t-1", 0, "", "{\"qu", null),
                toolCallFrame("t-1", 0, "", "ery\":\"虚拟线程\"}", null),
                toolCallFrame("t-1", 1, "search-agent", "{\"query\":\"GC\"}", "call_def"));

        List<EdpaToolCallArgumentsAssembler.ToolCall> calls =
                EdpaToolCallArgumentsAssembler.assemble(frames);

        assertThat(calls).as("应重组出 2 个 ToolCall（index 0 与 1）").hasSize(2);
        EdpaToolCallArgumentsAssembler.ToolCall first = calls.get(0);
        assertThat(first.arguments)
                .as("index=0 的三个片段必须拼成完整 JSON——拼不上则每帧都只有几个字符，"
                        + "任何内容级判据都恒不开火")
                .isEqualTo("{\"query\":\"虚拟线程\"}");
        assertThat(first.name).as("name 也是分片的，首片带名").isEqualTo("search-agent");
        assertThat(first.id).as("id 只在首片出现").isEqualTo("call_abc");
        assertThat(first.fragments).as("应记录到 3 个贡献片段").isEqualTo(3);
    }

    // ──────────────────────────────────────────────────────────
    // 2. 合并实体判据：MERGED（红）/ SINGLE（黄）/ UNDECIDABLE（黄）三向都能到达
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("canary: 单 ToolCall 参数同时覆盖两主题 → MERGED（L2 §7.3 判失败方向能开火）")
    void mergedEntityInSingleToolCallIsDetected() {
        List<EdpaSseCollector.Frame> frames = List.of(
                toolCallFrame("t-1", 0, "search-agent", "{\"query\":\"Java 21 虚拟线程特性", "call_x"),
                toolCallFrame("t-1", 0, "", "以及 GC 的核心变化\"}", null));

        EdpaMergedEntityJudge.Verdict v = EdpaMergedEntityJudge.judge(
                EdpaToolCallArgumentsAssembler.assemble(frames),
                EdpaParallelPrompts.HOMOG_TOPIC_A, EdpaParallelPrompts.HOMOG_TOPIC_B);

        assertThat(v.kind).isEqualTo(EdpaMergedEntityJudge.Kind.MERGED);
        assertThat(v.detail).as("判红必须带上命中词与参数原文供人工复核")
                .contains("虚拟线程").contains("GC").contains("参数原文");
    }

    @Test
    @DisplayName("canary: 各 ToolCall 只覆盖单主题 → SINGLE（不误红跨轮串行）")
    void singleTopicPerToolCallIsTolerated() {
        List<EdpaSseCollector.Frame> frames = List.of(
                toolCallFrame("t-1", 0, "search-agent", "{\"query\":\"Java 21 虚拟线程特性\"}", "call_x"));

        EdpaMergedEntityJudge.Verdict v = EdpaMergedEntityJudge.judge(
                EdpaToolCallArgumentsAssembler.assemble(frames),
                EdpaParallelPrompts.HOMOG_TOPIC_A, EdpaParallelPrompts.HOMOG_TOPIC_B);

        assertThat(v.kind).isEqualTo(EdpaMergedEntityJudge.Kind.SINGLE);
    }

    @Test
    @DisplayName("canary: 取不到 arguments → UNDECIDABLE（缺观察面时不得判失败）")
    void missingArgumentsYieldsUndecidableNotFailure() {
        EdpaMergedEntityJudge.Verdict empty = EdpaMergedEntityJudge.judge(
                List.of(), EdpaParallelPrompts.HOMOG_TOPIC_A, EdpaParallelPrompts.HOMOG_TOPIC_B);
        assertThat(empty.kind).isEqualTo(EdpaMergedEntityJudge.Kind.UNDECIDABLE);

        EdpaMergedEntityJudge.Verdict skeleton = EdpaMergedEntityJudge.judge(
                EdpaToolCallArgumentsAssembler.assemble(
                        List.of(toolCallFrame("t-1", 0, "search-agent", "", "call_x"))),
                EdpaParallelPrompts.HOMOG_TOPIC_A, EdpaParallelPrompts.HOMOG_TOPIC_B);
        assertThat(skeleton.kind).as("只有骨架、参数全空也属证据不足")
                .isEqualTo(EdpaMergedEntityJudge.Kind.UNDECIDABLE);
    }

    @Test
    @DisplayName("canary: 两个主题词集互斥——单主题文本不得同时命中甲乙（否则合并实体判据恒红）")
    void topicSetsAreMutuallyExclusive() {
        for (String a : EdpaParallelPrompts.HOMOG_TOPIC_A) {
            assertThat(EdpaParallelPrompts.containsAny(a, EdpaParallelPrompts.HOMOG_TOPIC_B))
                    .as("同类场景主题甲的词 <%s> 不应命中主题乙词集", a).isFalse();
        }
        for (String b : EdpaParallelPrompts.HOMOG_TOPIC_B) {
            assertThat(EdpaParallelPrompts.containsAny(b, EdpaParallelPrompts.HOMOG_TOPIC_A))
                    .as("同类场景主题乙的词 <%s> 不应命中主题甲词集", b).isFalse();
        }
        for (String a : EdpaParallelPrompts.HETERO_TOPIC_A) {
            assertThat(EdpaParallelPrompts.containsAny(a, EdpaParallelPrompts.HETERO_TOPIC_B))
                    .as("异构场景主题甲的词 <%s> 不应命中主题乙词集", a).isFalse();
        }
        for (String b : EdpaParallelPrompts.HETERO_TOPIC_B) {
            assertThat(EdpaParallelPrompts.containsAny(b, EdpaParallelPrompts.HETERO_TOPIC_A))
                    .as("异构场景主题乙的词 <%s> 不应命中主题甲词集", b).isFalse();
        }
        // 异构侧尤其要守：两件事都围绕"虚拟线程"，实体名不能当区分词
        assertThat(EdpaParallelPrompts.containsAny("虚拟线程", EdpaParallelPrompts.HETERO_TOPIC_A))
                .as("『虚拟线程』是 P4 两个主题的共同词，不得进入任一异构词集").isFalse();
        assertThat(EdpaParallelPrompts.containsAny("虚拟线程", EdpaParallelPrompts.HETERO_TOPIC_B))
                .as("『虚拟线程』是 P4 两个主题的共同词，不得进入任一异构词集").isFalse();
    }

    // ──────────────────────────────────────────────────────────
    // 3. C2 父段回程判据：逐成员触发能红、跨轮追加不误红、缺观察面判不可判定
    // ──────────────────────────────────────────────────────────

    /** 甲乙都派发；甲回程后父 Agent 立刻恢复推理（乙仍在途）—— 逐成员触发。 */
    private static List<EdpaSseCollector.Frame> perMemberRecoveryTrace() {
        List<EdpaSseCollector.Frame> f = new ArrayList<>();
        f.add(parentOutputFrame("我来规划一下"));
        f.add(agentEventFrame("delegation", "edp-agent", "parent-1", "search-agent", "child-A", null, ""));
        f.add(agentEventFrame("delegation", "edp-agent", "parent-1", "search-agent", "child-B", null, ""));
        f.add(agentEventFrame("output", "search-agent", "child-A", null, null, null, "甲的结果"));
        f.add(agentEventFrame("status", "search-agent", "child-A", null, null, "TASK_STATE_COMPLETED", ""));
        f.add(parentOutputFrame("甲回来了，我先总结甲"));      // ← 违规：乙未回程
        f.add(agentEventFrame("output", "search-agent", "child-B", null, null, null, "乙的结果"));
        f.add(agentEventFrame("status", "search-agent", "child-B", null, null, "TASK_STATE_COMPLETED", ""));
        f.add(parentOutputFrame("最终汇总"));
        return f;
    }

    /** 两轮各自 all-settled 后才恢复推理 —— 合规的跨轮追加委托。 */
    private static List<EdpaSseCollector.Frame> crossRoundTrace() {
        List<EdpaSseCollector.Frame> f = new ArrayList<>();
        f.add(parentOutputFrame("我来规划一下"));
        f.add(agentEventFrame("delegation", "edp-agent", "parent-1", "search-agent", "child-A", null, ""));
        f.add(agentEventFrame("delegation", "edp-agent", "parent-1", "search-agent", "child-B", null, ""));
        f.add(agentEventFrame("output", "search-agent", "child-A", null, null, null, "甲的结果"));
        f.add(agentEventFrame("output", "search-agent", "child-B", null, null, null, "乙的结果"));
        f.add(agentEventFrame("status", "search-agent", "child-A", null, null, "TASK_STATE_COMPLETED", ""));
        f.add(agentEventFrame("status", "search-agent", "child-B", null, null, "TASK_STATE_COMPLETED", ""));
        f.add(parentOutputFrame("第一轮齐了，还需要补两条"));   // 合法父段：已派发全部回程
        f.add(agentEventFrame("delegation", "edp-agent", "parent-1", "verify-agent", "child-C", null, ""));
        f.add(agentEventFrame("output", "verify-agent", "child-C", null, null, null, "丙的结果"));
        f.add(agentEventFrame("status", "verify-agent", "child-C", null, null, "TASK_STATE_COMPLETED", ""));
        f.add(parentOutputFrame("最终汇总"));                    // 合法父段
        return f;
    }

    @Test
    @DisplayName("canary: 逐成员触发推理恢复 → C2 硬 2 能开火（父段起点仍有成员未回程）")
    void perMemberRecoveryIsCaught() {
        EdpaRecoverySegments.Analysis a = EdpaRecoverySegments.analyze(perMemberRecoveryTrace());

        assertThat(a.decidable).as("这条轨迹信息完整，应当可判定；实际原因=%s", a.undecidableReason).isTrue();
        assertThat(a.parentAgentId).isEqualTo("edp-agent");
        assertThat(a.violations).as("甲回程即恢复推理、乙仍在途 —— 必须抓到 1 处违规").hasSize(1);
        assertThat(a.violations.get(0).outstanding).containsExactly("child-B");
    }

    @Test
    @DisplayName("canary: 跨轮追加委托（多父段但每次都 all-settled）→ C2 硬 2 不误红")
    void crossRoundAppendedDelegationIsNotFlagged() {
        EdpaRecoverySegments.Analysis a = EdpaRecoverySegments.analyze(crossRoundTrace());

        assertThat(a.decidable).as("原因=%s", a.undecidableReason).isTrue();
        assertThat(a.violations)
                .as("跨轮是合规形态：每个父段起点处已派发成员都已回程。误红即说明判据被写成了"
                        + "「父段总数」那种形态。时序=%s", a.timeline)
                .isEmpty();
        assertThat(a.parentSegmentsAfterFirstChild)
                .as("确认这条轨迹确实有 >1 个父段——否则本用例没有守住任何东西")
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("canary: 无子任务终态事件 → C2 硬 2 判不可判定，而不是恒红")
    void missingChildTerminalStatusYieldsUndecidable() {
        List<EdpaSseCollector.Frame> f = new ArrayList<>();
        f.add(agentEventFrame("delegation", "edp-agent", "parent-1", "search-agent", "child-A", null, ""));
        f.add(agentEventFrame("output", "search-agent", "child-A", null, null, null, "甲的结果"));
        f.add(parentOutputFrame("汇总"));

        EdpaRecoverySegments.Analysis a = EdpaRecoverySegments.analyze(f);
        assertThat(a.decidable).isFalse();
        assertThat(a.undecidableReason).contains("终态 status");
    }

    @Test
    @DisplayName("canary: 无 delegation → C2 硬 2 判不可判定")
    void noDelegationYieldsUndecidable() {
        EdpaRecoverySegments.Analysis a = EdpaRecoverySegments.analyze(
                List.of(parentOutputFrame("我直接回答了")));
        assertThat(a.decidable).isFalse();
        assertThat(a.undecidableReason).contains("未观察到 delegation");
    }

    // ──────────────────────────────────────────────────────────
    // 4. S1 的 D_sub 过滤：不过滤就会恒红
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("canary: D_sub 只含子 Agent 透传文本，父 Agent 自身汇总流必须被过滤掉")
    void childPlaneTextExcludesParentOwnOutput() {
        List<EdpaSseCollector.Frame> f = crossRoundTrace();
        String sub = EdpaRecoverySegments.childPlaneText(f, "edp-agent");

        assertThat(sub).as("子 Agent 的透传输出应在 D_sub 内")
                .contains("甲的结果").contains("乙的结果").contains("丙的结果");
        assertThat(sub)
                .as("父 Agent 自身的汇总流**不得**进 D_sub——进了的话控制面 C 天然是 D_sub 的子串，"
                        + "S1 硬 2 会恒红（误红）")
                .doesNotContain("最终汇总").doesNotContain("第一轮齐了");
    }

    @Test
    @DisplayName("canary: 父 agentId 不唯一（嵌套委托）时不猜，返回 null")
    void ambiguousParentAgentIdIsNotGuessed() {
        List<EdpaSseCollector.Frame> f = List.of(
                agentEventFrame("delegation", "edp-agent", "parent-1", "search-agent", "child-A", null, ""),
                agentEventFrame("delegation", "search-agent", "child-A", "sub-agent", "child-A1", null, ""));
        assertThat(EdpaRecoverySegments.parentAgentId(f)).isNull();
    }
}

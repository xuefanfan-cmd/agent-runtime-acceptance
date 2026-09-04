package com.huawei.ascend.sit.cases.integration.edpa;

import java.util.ArrayList;
import java.util.List;

/**
 * FEAT-028 共享判据 —— <b>「模型只派发了 1 个委托」时的分流裁判</b>。
 *
 * <p><b>为什么需要它</b>（testplan §5 P3/P4 行 + cases 细档 §5.5.4）：P3/P4 原先在
 * {@code delegations().size() < 2} 时无条件 {@code assumeTrue(false)} 判 INCONCLUSIVE。
 * 但 EDPA L2 §7.3 错误表面验收表把这一情形<b>明确分成两种、结论相反</b>：
 * <table border="1">
 *   <tr><th>模型行为</th><th>验收结论</th></tr>
 *   <tr><td>模型<b>串行生成</b>调用（本轮只出 1 个，下一轮再出）</td><td>容忍 → INCONCLUSIVE</td></tr>
 *   <tr><td>模型<b>合并多个实体</b>：单 ToolCall 参数包含多个独立实体</td>
 *       <td>「视为规划质量问题，<b>验收判失败</b>」→ FAIL</td></tr>
 * </table>
 * 一律判 INCONCLUSIVE 等于把表里那条 FAIL 行<b>永久静默</b>——本方案要防的漏红。
 *
 * <p><b>观察面</b>：{@link EdpaToolCallArgumentsAssembler} 重组出的完整 {@code arguments}。
 * 「包含多个独立实体」的可复算投影 = <b>同一个 ToolCall 的参数串同时命中两个互斥主题词集</b>
 * （词集见 {@link EdpaParallelPrompts}）。
 *
 * <p><b>判据等级与失败方向</b>（重要）：{@code tool_calls} 是 payload 业务内容，不是 A2A wire MUST
 * （FEAT-029 §1/§3.1：agent-runtime 只透传 payload）。所以本裁判<b>只在证据充分时才判 FAIL</b>：
 * <ul>
 *   <li>取不到任何 ToolCall / 参数串全空（流被 cap 截断、模型未走 function-call 通道） →
 *       {@link Kind#UNDECIDABLE}，判 INCONCLUSIVE，<b>不判 FAIL</b>；</li>
 *   <li>参数串只命中一个主题（真的只有一件事、或跨轮串行） → {@link Kind#SINGLE}，判 INCONCLUSIVE；</li>
 *   <li>某<b>单个</b> ToolCall 参数串同时命中两个主题 → {@link Kind#MERGED}，判 FAIL。</li>
 * </ul>
 *
 * <p><b>首次判红必须人工复核</b>：关键词命中是措辞敏感的近似判据。判红时 {@link Verdict#detail}
 * 会带上命中词与参数串原文（截断），按 pre-flight 纪律，提缺陷前必须先看这段原文确认
 * 「确实是一个委托里塞了两件事」，而不是模型在参数里顺带提了另一个主题词。
 */
final class EdpaMergedEntityJudge {

    private EdpaMergedEntityJudge() {}

    enum Kind {
        /** 单 ToolCall 合并了多个独立实体 —— L2 §7.3 判失败。 */
        MERGED,
        /** 确为单实体 / 跨轮串行 —— L2 §7.3 容忍。 */
        SINGLE,
        /** 证据不足（无 ToolCall payload 或参数串重组不出） —— 不得据此判失败。 */
        UNDECIDABLE
    }

    static final class Verdict {
        final Kind kind;
        final String detail;

        Verdict(Kind kind, String detail) {
            this.kind = kind;
            this.detail = detail;
        }
    }

    /**
     * @param calls  {@link EdpaToolCallArgumentsAssembler#assemble} 的产物（已跨帧重组）
     * @param topicA 主题甲词集
     * @param topicB 主题乙词集（须与甲<b>互斥</b>，否则单主题会被误算成双主题 → 误红）
     */
    static Verdict judge(List<EdpaToolCallArgumentsAssembler.ToolCall> calls,
                         String[] topicA, String[] topicB) {
        List<EdpaToolCallArgumentsAssembler.ToolCall> withArgs = new ArrayList<>();
        for (EdpaToolCallArgumentsAssembler.ToolCall c : calls) {
            if (c.arguments != null && !c.arguments.isBlank()) withArgs.add(c);
        }
        if (withArgs.isEmpty()) {
            return new Verdict(Kind.UNDECIDABLE, String.format(
                    "未重组出任何带 arguments 的 ToolCall（观察到 %d 个 ToolCall 骨架：%s）——"
                            + "可能是流被 cap 截断、或模型未走 function-call 通道。"
                            + "L2 §7.3 的「合并实体」判据在此无观察面，按判据等级判 INCONCLUSIVE 而非 FAIL。",
                    calls.size(), EdpaToolCallArgumentsAssembler.summary(calls)));
        }

        for (EdpaToolCallArgumentsAssembler.ToolCall c : withArgs) {
            List<String> ha = EdpaParallelPrompts.hits(c.arguments, topicA);
            List<String> hb = EdpaParallelPrompts.hits(c.arguments, topicB);
            if (!ha.isEmpty() && !hb.isEmpty()) {
                return new Verdict(Kind.MERGED, String.format(
                        "单个 ToolCall 的参数串同时覆盖两个独立主题——命中主题甲%s、主题乙%s。"
                                + "ToolCall=[task=%s idx=%d name=%s id=%s 由 %d 个增量片段重组]，"
                                + "参数原文=%s",
                        ha, hb, c.taskId, c.index, c.name, c.id, c.fragments, truncate(c.arguments, 800)));
            }
        }

        List<String> per = new ArrayList<>();
        for (EdpaToolCallArgumentsAssembler.ToolCall c : withArgs) {
            per.add(String.format("[idx=%d name=%s 甲%s 乙%s args=%s]", c.index, c.name,
                    EdpaParallelPrompts.hits(c.arguments, topicA),
                    EdpaParallelPrompts.hits(c.arguments, topicB),
                    truncate(c.arguments, 200)));
        }
        String note = withArgs.size() >= 2
                ? "【注意：payload 里重组出 " + withArgs.size() + " 个带参 ToolCall，但 wire 上 delegation "
                        + "事件不足 2 条——两个通道不一致，复跑时请核对 SSE dump，可能是 delegation 事件缺发。"
                        + "本裁判不就此判失败（FEAT-027 delegation 计数不在本判据的观察面内）】"
                : "";
        return new Verdict(Kind.SINGLE, "各 ToolCall 参数串均只覆盖单一主题（真单实体或跨轮串行，L2 §7.3 容忍）："
                + per + note);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}

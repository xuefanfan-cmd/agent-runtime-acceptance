package com.huawei.ascend.sit.cases.integration.edpa;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * FEAT-028 共享判据 —— 把 SSE 帧序列切成 <b>父段 / 子段</b>，用于 C2「all-settled 单次推理恢复」
 * 与 S1「数据面 ⊄ 控制面」。
 *
 * <p><b>为什么原判据形态不成立</b>（2026-09-03 落码时推翻，见 cases 细档 §5.5.4）：
 * <ul>
 *   <li>C2 现役判据「终态 statusUpdate 帧恰好 1 次」<b>近乎恒真</b>——A2A 状态机本就保证父 Task
 *       只有一个终态帧，逐成员触发也只会产生一个。绿灯承载不了「单次恢复」这个含义。</li>
 *   <li>testplan 里写的替代形态「<b>最后一条子回程之后</b>父段恰好 1 段」<b>也是恒真的</b>：
 *       按定义，最后一条子回程之后不再有任何子段，剩下的帧必然连成一段。它唯一能抓的是
 *       「一段都没有」（父 Agent 没出汇总）。这条<b>本次落码时被推翻</b>，见下。</li>
 * </ul>
 *
 * <p><b>本次采用的判据形态</b>：真正区分「逐成员触发」与「合规的跨轮追加委托」的，不是父段的
 * <b>位置</b>，而是父段起点处 <b>本批是否还有成员没回程</b>：
 * <ul>
 *   <li><b>逐成员触发（缺陷）</b>：成员甲回程 → 父 Agent 立刻恢复推理产出父段，而此时成员乙
 *       <b>仍未回程</b> → 违规；</li>
 *   <li><b>跨轮追加委托（合规，实测常见形态：4 条 delegation 分 2 轮）</b>：第一轮两名成员
 *       <b>全部</b>回程 → 父段 → 再派发第二轮 → 全部回程 → 父段。每个父段起点处
 *       <b>已派发的成员都已回程</b> → 不违规。</li>
 * </ul>
 * 于是判据是：<b>每个父段起点处，「已派发 − 已回程」必须为空集</b>。这条既能红（逐成员触发），
 * 又不会误红跨轮，且与父段总数无关。
 *
 * <p><b>分类规则</b>（只看 {@code artifactUpdate} 帧；{@code statusUpdate} 是父 Task 控制面维度，
 * 不参与分段）：
 * <ul>
 *   <li>帧内无 {@code agentEvent} → <b>父段帧</b>。依据：FEAT-027 的 {@code agentEvent} 用于标注
 *       <b>跨 Agent</b> 事件；父 Agent 自身的 llm_output 不带它（实测样本
 *       {@code docs/issues/wire-samples/sse.txt} 即为无 agentEvent 的 llm_output 帧）。</li>
 *   <li>帧内非 delegation 事件的 {@code source.agentId} ≠ 父 → <b>子段帧</b>（数据面透传）。</li>
 *   <li>帧内<b>只有</b> delegation 事件 → <b>控制帧，透明跳过</b>：delegation 的 {@code source}
 *       按 §3.1 字段适用性表本就指向父 Agent/Task，若把它当父段帧会在跨轮场景里凭空切出父段
 *       （误红），也会把两个子段割断（漏红）。</li>
 * </ul>
 *
 * <p><b>父 agentId 不硬编码</b>：取全部 delegation 事件的 {@code source.agentId}——§3.1 规定
 * delegation 的 source 就是发起方（父）。恰好一个才可判；0 个或 ≥2 个（嵌套委托）→ 不可判定。
 *
 * <p><b>四个不可判定出口</b>（宁可 INCONCLUSIVE 也不猜，全部带原因回传）：
 * ① 无 delegation 事件（模型未派发）；② delegation 的 {@code source.agentId} 不唯一；
 * ③ 有 delegation 缺 {@code target.taskId}，「已派发」集合建不全；
 * ④ <b>全程没有子任务终态 status 事件</b>——「已回程」永远是空集，任何父段都会被算成违规（误红）。
 */
final class EdpaRecoverySegments {

    private EdpaRecoverySegments() {}

    enum FrameClass {
        /** 父 Agent 自身输出。 */
        PARENT,
        /** 子 Agent 透传输出。 */
        CHILD,
        /** 仅含 delegation 的控制帧，分段时透明。 */
        CONTROL,
        /** 非 artifactUpdate 帧，不参与分段。 */
        OTHER
    }

    /** 一次违规：某父段在本批仍有成员未回程时就开始了。 */
    static final class Violation {
        final int frameIndex;
        final Set<String> outstanding;
        final Set<String> returnedSoFar;

        Violation(int frameIndex, Set<String> outstanding, Set<String> returnedSoFar) {
            this.frameIndex = frameIndex;
            this.outstanding = outstanding;
            this.returnedSoFar = returnedSoFar;
        }

        @Override
        public String toString() {
            return String.format("[帧#%d 处父段起点：仍有 %d 个成员未回程 %s（此时已回程 %s）]",
                    frameIndex, outstanding.size(), outstanding, returnedSoFar);
        }
    }

    static final class Analysis {
        final boolean decidable;
        final String undecidableReason;
        final String parentAgentId;
        /** 父段起点处仍有成员未回程的全部实例——非空即「逐成员触发」。 */
        final List<Violation> violations;
        /** 首个子段帧之后的父段数（诊断用，不作判据）。 */
        final int parentSegmentsAfterFirstChild;
        final int parentFrames;
        final int childFrames;
        final Set<String> delegatedTaskIds;
        final Set<String> returnedTaskIds;
        /** P=父段帧 C=子段帧 d=控制帧，按到达序，诊断用。 */
        final String timeline;

        Analysis(boolean decidable, String undecidableReason, String parentAgentId,
                 List<Violation> violations, int parentSegmentsAfterFirstChild,
                 int parentFrames, int childFrames,
                 Set<String> delegatedTaskIds, Set<String> returnedTaskIds, String timeline) {
            this.decidable = decidable;
            this.undecidableReason = undecidableReason;
            this.parentAgentId = parentAgentId;
            this.violations = violations;
            this.parentSegmentsAfterFirstChild = parentSegmentsAfterFirstChild;
            this.parentFrames = parentFrames;
            this.childFrames = childFrames;
            this.delegatedTaskIds = delegatedTaskIds;
            this.returnedTaskIds = returnedTaskIds;
            this.timeline = timeline;
        }

        String summary() {
            return String.format("parent=%s 父段帧=%d 子段帧=%d 首个子段后父段数=%d 已派发=%s 已回程=%s%n时序=%s",
                    parentAgentId, parentFrames, childFrames, parentSegmentsAfterFirstChild,
                    delegatedTaskIds, returnedTaskIds, timeline);
        }
    }

    private static Analysis undecidable(String reason) {
        return new Analysis(false, reason, null, List.of(), 0, 0, 0,
                new LinkedHashSet<>(), new LinkedHashSet<>(), "");
    }

    static Analysis analyze(List<EdpaSseCollector.Frame> frames) {
        // ── 逐帧扫 agentEvent ──
        List<EdpaAgentEventScanner.Result> perFrame = new ArrayList<>();
        for (EdpaSseCollector.Frame f : frames) {
            perFrame.add(f.parsed == null
                    ? new EdpaAgentEventScanner.Result()
                    : EdpaAgentEventScanner.scan(f.parsed));
        }

        // ── 出口①②③：确定父 agentId 与「已派发」集合 ──
        Set<String> delegationSourceAgentIds = new LinkedHashSet<>();
        Set<String> delegatedTaskIds = new LinkedHashSet<>();
        int delegationCount = 0, delegationsMissingTarget = 0;
        for (EdpaAgentEventScanner.Result r : perFrame) {
            for (EdpaAgentEventScanner.AgentEvent e : r.events) {
                if (!e.isDelegation()) continue;
                delegationCount++;
                if (e.sourceAgentId != null) delegationSourceAgentIds.add(e.sourceAgentId);
                if (e.targetTaskId == null) delegationsMissingTarget++;
                else delegatedTaskIds.add(e.targetTaskId);
            }
        }
        if (delegationCount == 0) {
            return undecidable("全程未观察到 delegation 事件——模型未派发委托，无「批」可谈，"
                    + "父段/子段分段无从建立");
        }
        if (delegationSourceAgentIds.size() != 1) {
            return undecidable("delegation 的 source.agentId 不唯一（实测 " + delegationSourceAgentIds
                    + "）——可能出现嵌套委托，父 Agent 身份无法唯一确定，不猜");
        }
        if (delegationsMissingTarget > 0) {
            return undecidable("有 " + delegationsMissingTarget + " 条 delegation 缺 target.taskId，"
                    + "「已派发成员」集合建不全（缺失项会被静默当成『已回程』→ 漏红），故判不可判定。"
                    + "注：target 完整性本身由 P3/P4 的 wire 最小结构断言把关，本处只做前置");
        }
        String parentAgentId = delegationSourceAgentIds.iterator().next();

        // ── 分类每一帧 ──
        FrameClass[] classes = new FrameClass[frames.size()];
        int parentFrames = 0, childFrames = 0;
        for (int i = 0; i < frames.size(); i++) {
            EdpaSseCollector.Frame f = frames.get(i);
            if (!"artifactUpdate".equals(f.eventKind)) { classes[i] = FrameClass.OTHER; continue; }
            List<EdpaAgentEventScanner.AgentEvent> events = perFrame.get(i).events;
            if (events.isEmpty()) { classes[i] = FrameClass.PARENT; parentFrames++; continue; }
            boolean anyNonDelegation = false, anyForeign = false;
            for (EdpaAgentEventScanner.AgentEvent e : events) {
                if (e.isDelegation()) continue;
                anyNonDelegation = true;
                if (e.sourceAgentId != null && !e.sourceAgentId.equals(parentAgentId)) anyForeign = true;
            }
            if (!anyNonDelegation) { classes[i] = FrameClass.CONTROL; continue; }
            if (anyForeign) { classes[i] = FrameClass.CHILD; childFrames++; }
            else { classes[i] = FrameClass.PARENT; parentFrames++; }
        }

        // ── 出口④：必须观察到子任务终态，否则「已回程」恒空 → 误红 ──
        Set<String> terminalReturned = new LinkedHashSet<>();
        for (EdpaAgentEventScanner.Result r : perFrame) {
            for (EdpaAgentEventScanner.AgentEvent e : r.events) {
                // 只收「子」Agent 的终态：按 source.agentId 排除父自身
                // （不能拿 sourceTaskId 去比 parentAgentId——那是两个维度，§5.7）
                if (e.isStatus() && isTerminal(e.state) && e.sourceTaskId != null
                        && e.sourceAgentId != null && !e.sourceAgentId.equals(parentAgentId)) {
                    terminalReturned.add(e.sourceTaskId);
                }
            }
        }
        if (terminalReturned.isEmpty()) {
            return undecidable("全程未观察到任何子任务的终态 status 事件（agentEvent.type=status 且 "
                    + "state 为 COMPLETED/FAILED/CANCELED/REJECTED）——「已回程成员」集合恒为空集，"
                    + "任何父段都会被算成『仍有成员未回程』，判据会恒红。已派发=" + delegatedTaskIds);
        }
        if (java.util.Collections.disjoint(delegatedTaskIds, terminalReturned)) {
            return undecidable("delegation 的 target.taskId " + delegatedTaskIds
                    + " 与子任务终态事件的 source.taskId " + terminalReturned
                    + " 完全不相交——两者本应是同一维度（§3.1），既然对不上，"
                    + "「已派发 − 已回程」这个差集就没有意义，不猜");
        }

        // ── 按到达序推进，在每个父段起点处检查未回程成员 ──
        List<Violation> violations = new ArrayList<>();
        Set<String> dispatched = new LinkedHashSet<>();
        Set<String> returned = new LinkedHashSet<>();
        StringBuilder timeline = new StringBuilder();
        FrameClass prevSignificant = null;
        boolean sawChild = false;
        int parentSegmentsAfterFirstChild = 0;

        for (int i = 0; i < frames.size(); i++) {
            FrameClass c = classes[i];
            if (c == FrameClass.OTHER) continue;
            timeline.append(c == FrameClass.PARENT ? 'P' : c == FrameClass.CHILD ? 'C' : 'd');

            if (c == FrameClass.PARENT && prevSignificant != FrameClass.PARENT && sawChild) {
                // 父段起点，且位于首个子段之后 —— 此刻本批是否还有成员没回程？
                parentSegmentsAfterFirstChild++;
                Set<String> outstanding = new LinkedHashSet<>(dispatched);
                outstanding.removeAll(returned);
                if (!outstanding.isEmpty()) {
                    violations.add(new Violation(i, outstanding, new LinkedHashSet<>(returned)));
                }
            }

            // 先判定、后吸收本帧事件：父段起点的"当时"状态不应包含本帧自身
            for (EdpaAgentEventScanner.AgentEvent e : perFrame.get(i).events) {
                if (e.isDelegation()) {
                    if (e.targetTaskId != null) dispatched.add(e.targetTaskId);
                } else if (e.isStatus() && isTerminal(e.state) && e.sourceTaskId != null) {
                    returned.add(e.sourceTaskId);
                }
            }
            if (c == FrameClass.CHILD) sawChild = true;
            if (c != FrameClass.CONTROL) prevSignificant = c;
        }

        return new Analysis(true, null, parentAgentId, violations, parentSegmentsAfterFirstChild,
                parentFrames, childFrames, dispatched, returned, timeline.toString());
    }

    /**
     * 单独取父 agentId（S1 只需要这一项，不需要整套分段分析）。
     *
     * @return 恰好一个 delegation 发起方时返回它；0 个或多个（嵌套委托）返回 {@code null}
     */
    static String parentAgentId(List<EdpaSseCollector.Frame> frames) {
        Set<String> ids = new LinkedHashSet<>();
        for (EdpaSseCollector.Frame f : frames) {
            if (f.parsed == null) continue;
            for (EdpaAgentEventScanner.AgentEvent e : EdpaAgentEventScanner.scan(f.parsed).events) {
                if (e.isDelegation() && e.sourceAgentId != null) ids.add(e.sourceAgentId);
            }
        }
        return ids.size() == 1 ? ids.iterator().next() : null;
    }

    /**
     * 子段帧的文本拼接（S1 的 {@code D_sub}）—— <b>只取 source.agentId ≠ 父的帧</b>。
     *
     * <p><b>为什么必须过滤</b>：不过滤的话，父 Agent 自身的汇总流也会进 {@code D_sub}，
     * 于是控制面文本 C 天然是 {@code D_sub} 的子串，「C ⊄ D_sub」判据恒红（误红）。
     */
    static String childPlaneText(List<EdpaSseCollector.Frame> frames, String parentAgentId) {
        StringBuilder sb = new StringBuilder();
        for (EdpaSseCollector.Frame f : frames) {
            if (f.parsed == null || !"artifactUpdate".equals(f.eventKind)) continue;
            EdpaAgentEventScanner.Result r = EdpaAgentEventScanner.scan(f.parsed);
            boolean foreign = false;
            for (EdpaAgentEventScanner.AgentEvent e : r.events) {
                if (!e.isDelegation() && e.sourceAgentId != null
                        && !e.sourceAgentId.equals(parentAgentId)) { foreign = true; break; }
            }
            if (!foreign) continue;
            for (com.fasterxml.jackson.databind.JsonNode part
                    : f.parsed.path("result").path("artifactUpdate").path("artifact").path("parts")) {
                String text = part.path("text").asText("");
                if (!text.isBlank()) sb.append(text);
                com.fasterxml.jackson.databind.JsonNode content =
                        part.path("data").path("payload").path("content");
                if (content.isTextual() && !content.asText().isBlank()) sb.append(content.asText());
            }
        }
        return sb.toString();
    }

    private static boolean isTerminal(String state) {
        if (state == null) return false;
        return state.contains("COMPLETED") || state.contains("FAILED")
                || state.contains("CANCELED") || state.contains("CANCELLED")
                || state.contains("REJECTED");
    }
}

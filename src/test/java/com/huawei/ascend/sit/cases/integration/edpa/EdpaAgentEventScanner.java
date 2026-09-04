package com.huawei.ascend.sit.cases.integration.edpa;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * FEAT-028 共享判据 —— <b>{@code agentEvent} 结构化扫描</b>（FEAT-027 wire 最小公共契约面）。
 *
 * <p><b>为什么需要它</b>（2026-09-02 判据更正，见 cases 细档 §5.5.3）：
 * {@link EdpaChildVisibilityScanner} 是<b>按字段名</b>的全字段递归扫描，回答的是"客户端能不能看到
 * 子任务信息"这类存在性问题，刻意不预设承载位。但 C1/C3/P3/P4 要断言的是 FEAT-027 定义的
 * <b>结构化契约</b>——事件类型、source/target 二元组、delegation 与回程事件的配对关系——
 * 这些按字段名扁平扫描表达不出来，需要保留 {@code agentEvent} 的对象结构。
 *
 * <p><b>契约依据</b>（{@code Technical-AF/docs}，FEAT-027 标准流式响应数据协议）：
 * <ul>
 *   <li><b>§3.1 agentEvent 最小公共契约</b>：{@code agentEvent} 承载于标准 A2A
 *       {@code TaskArtifactUpdateEvent} 的 {@code Artifact.metadata} 中，<b>不新增 A2A 顶层事件类型</b>；
 *       {@code type} 是闭集 {@code delegation | output | status}。字段适用性：
 *       <table>
 *         <tr><th>type</th><th>source</th><th>target</th><th>state</th></tr>
 *         <tr><td>delegation</td><td>必须（父）</td><td>必须（子）</td><td>不使用</td></tr>
 *         <tr><td>output</td><td>必须（实际生产者）</td><td>不使用</td><td>不使用</td></tr>
 *         <tr><td>status</td><td>必须（状态所属生产者）</td><td>不使用</td><td>必须</td></tr>
 *       </table></li>
 *   <li><b>§2「wire 协议最小结构」三条 MUST</b>：三种 type 的 {@code agentEvent} 都<b>必须</b>包含
 *       {@code source.agentId} 与 {@code source.taskId}——"此最小结构为客户端黑盒消费的公共契约，不是 OUT"。</li>
 *   <li><b>§2「并发交织」MUST + §5.6</b>：客户端通过 {@code source.agentId + source.taskId} 分流交织事件，
 *       <b>不得使用外层父 Task ID 替代生产者 Task ID</b>，且不依赖不同生产者之间的到达顺序。</li>
 *   <li><b>§5.7</b>：外层 {@code TaskArtifactUpdateEvent.taskId} 属当前父 Task SSE，
 *       {@code agentEvent.source.taskId} 是实际生产者 Task —— 两个维度不得混淆。</li>
 * </ul>
 *
 * <p><b>{@code toolCallId} 的地位</b>：本 helper 会解析并保留 {@code agentEvent.toolCallId}，但它是
 * <b>MAY 级扩展字段，只作观察记录，不得单独构成判据</b>——FEAT-027 §5.9 注：「{@code toolCallId} 的产生和
 * 关联语义由 FEAT-004 / FEAT-019 定义，<b>不属于 FEAT-027 的最小公共字段</b>；delegation <b>可以</b>携带
 * {@code toolCallId}」；FEAT-019 L2 §5.4：「{@code batchId}、{@code toolCallId}……继续用于恢复、日志和诊断，
 * <b>不构成用户侧调用图协议</b>。客户端调用图以 {@code (agentId, taskId)} 为节点，以 {@code delegation} 为边」。
 *
 * <p><b>wire 上不存在 {@code tool_result} 事件类型</b>：{@code type} 闭集只有三个值。任何"按
 * {@code toolCallId} 配对 tool_call 与 tool_result"的断言都是在观察契约上不存在的对象，恒红也不构成缺陷。
 * 正确的"归位"端到端投影是 {@link #delegationsWithoutReturn()}：每条 delegation 的 {@code target.taskId}
 * 都应能在后续某条 output / status 的 {@code source.taskId} 中找到（FEAT-019 L2 §5.4：
 * 「同一 member 内只保证 delegation 先于首个 output」；跨子树可交错，故只判存在性、不判全局顺序）。
 */
final class EdpaAgentEventScanner {

    /** FEAT-027 §3.1 定义的 agentEvent.type 闭集。 */
    static final Set<String> VALID_TYPES = Set.of("delegation", "output", "status");

    /**
     * {@code type} 字段缺失时记入 {@link Result#unknownTypes} 的哨兵值。
     *
     * <p>不能让 {@code type} 缺失静默通过闭集校验：FEAT-027 §2「控制与业务语义区分」MUST
     * 要求 delegation / output / status <b>用 {@code agentEvent.type} 区分</b>，且
     * 「客户端不得仅依赖 Artifact 文本内容推断事件类型」——没有 {@code type} 的 agentEvent
     * 等于把类型判定推回文本推断，属 wire 违约，与"取值不在闭集内"同级。
     */
    static final String MISSING_TYPE = "(type 缺失)";

    private EdpaAgentEventScanner() {}

    /** 一条 {@code agentEvent} 的结构化视图（按 FEAT-027 §3.1）。 */
    static final class AgentEvent {
        final String type;
        final String sourceAgentId;
        final String sourceTaskId;
        final String targetAgentId;
        final String targetTaskId;
        final String state;
        /** MAY 级扩展字段，仅观察记录。 */
        final String toolCallId;
        /** 命中路径，便于诊断输出。 */
        final String path;
        /** 在采集序列中的到达序号（跨源不保证业务顺序，仅用于诊断）。 */
        final int arrivalIndex;

        AgentEvent(String type, String sourceAgentId, String sourceTaskId,
                   String targetAgentId, String targetTaskId, String state,
                   String toolCallId, String path, int arrivalIndex) {
            this.type = type;
            this.sourceAgentId = sourceAgentId;
            this.sourceTaskId = sourceTaskId;
            this.targetAgentId = targetAgentId;
            this.targetTaskId = targetTaskId;
            this.state = state;
            this.toolCallId = toolCallId;
            this.path = path;
            this.arrivalIndex = arrivalIndex;
        }

        boolean isDelegation() { return "delegation".equals(type); }
        boolean isOutput()     { return "output".equals(type); }
        boolean isStatus()     { return "status".equals(type); }

        /** FEAT-027 §2/§5.6 指定的客户端分流键。 */
        String sourceKey() { return sourceAgentId + "/" + sourceTaskId; }

        @Override
        public String toString() {
            return String.format("#%d %s src=%s/%s%s%s%s",
                    arrivalIndex, type, sourceAgentId, sourceTaskId,
                    targetTaskId != null ? " tgt=" + targetAgentId + "/" + targetTaskId : "",
                    state != null ? " state=" + state : "",
                    toolCallId != null ? " toolCallId=" + toolCallId : "");
        }
    }

    /** 扫描结果 —— 保留到达顺序，便于诊断。 */
    static final class Result {
        final List<AgentEvent> events = new ArrayList<>();
        /** 出现过但不在 FEAT-027 §3.1 闭集里的 type 值（wire 违约线索）。 */
        final Set<String> unknownTypes = new LinkedHashSet<>();
        /** 缺 source.agentId 或 source.taskId 的事件（违反三条 wire 最小结构 MUST）。 */
        final List<AgentEvent> eventsMissingSource = new ArrayList<>();
        private int arrival = 0;

        List<AgentEvent> delegations() { return filter(AgentEvent::isDelegation); }
        List<AgentEvent> outputs()     { return filter(AgentEvent::isOutput); }
        List<AgentEvent> statuses()    { return filter(AgentEvent::isStatus); }

        private List<AgentEvent> filter(java.util.function.Predicate<AgentEvent> p) {
            List<AgentEvent> out = new ArrayList<>();
            for (AgentEvent e : events) if (p.test(e)) out.add(e);
            return out;
        }

        /**
         * 去重后的客户端分流键 {@code (source.agentId, source.taskId)} 集合。
         * FEAT-027 §2「并发交织」MUST：这是客户端区分并行轨迹的唯一依据。
         */
        Set<String> distinctSourceKeys() {
            Set<String> keys = new LinkedHashSet<>();
            for (AgentEvent e : events) {
                if (e.sourceAgentId != null && e.sourceTaskId != null) keys.add(e.sourceKey());
            }
            return keys;
        }

        /** 去重后的 source.agentId 集合（P4 异构判据：应含两个不同下游 agentName）。 */
        Set<String> distinctSourceAgentIds() {
            Set<String> ids = new LinkedHashSet<>();
            for (AgentEvent e : events) if (e.sourceAgentId != null) ids.add(e.sourceAgentId);
            return ids;
        }

        /** 去重后的 source.taskId 集合。 */
        Set<String> distinctSourceTaskIds() {
            Set<String> ids = new LinkedHashSet<>();
            for (AgentEvent e : events) if (e.sourceTaskId != null) ids.add(e.sourceTaskId);
            return ids;
        }

        /**
         * delegation 的 target.taskId <b>去重</b>集合（= 派发出去的 member 集合）。
         *
         * <p><b>注意</b>：本方法会静默跳过 {@code target.taskId} 为空的 delegation，也会把重复值
         * 折叠为一个。所以它<b>不能</b>单独用来断言"两两不同且非空"——那两条判据分别由
         * {@link #delegationsMissingTarget()} 与 {@code size() == delegations().size()} 承接。
         */
        Set<String> delegationTargetTaskIds() {
            Set<String> ids = new LinkedHashSet<>();
            for (AgentEvent e : delegations()) if (e.targetTaskId != null) ids.add(e.targetTaskId);
            return ids;
        }

        /**
         * {@code target.agentId} 或 {@code target.taskId} 为空的 delegation。
         *
         * <p>FEAT-027 §3.1 字段适用性表：delegation 的 {@code target} 是<b>必须</b>项；
         * §2「delegation 生成」MUST 另有明文「<b>不得生成空 target Task ID</b>」。
         */
        List<AgentEvent> delegationsMissingTarget() {
            List<AgentEvent> out = new ArrayList<>();
            for (AgentEvent e : delegations()) {
                if (e.targetAgentId == null || e.targetTaskId == null) out.add(e);
            }
            return out;
        }

        /**
         * {@code target.taskId} 重复的 delegation（同一子 Task 被派发多次）。
         *
         * <p>FEAT-027 §2「delegation 生成」MUST：调用方 Runtime 对一个下游 Task
         * 「<b>生成一次</b> delegation」。同批 N 个委托应对应 N 个互不相同的子 Task，
         * 重复即意味着两个 member 被折叠到了同一个下游 Task 上。
         */
        List<AgentEvent> delegationsWithDuplicateTarget() {
            Set<String> seen = new LinkedHashSet<>();
            List<AgentEvent> out = new ArrayList<>();
            for (AgentEvent e : delegations()) {
                if (e.targetTaskId != null && !seen.add(e.targetTaskId)) out.add(e);
            }
            return out;
        }

        /**
         * 用 {@code source.taskId} 顶替成父 taskId 的事件——违反 FEAT-027 §2「并发交织」MUST
         * 「不得使用外层父 Task ID 替代生产者 Task ID」与 §5.7「两个维度不得混淆」。
         */
        List<AgentEvent> eventsUsingParentAsSourceTaskId(String parentTaskId) {
            List<AgentEvent> out = new ArrayList<>();
            if (parentTaskId == null || parentTaskId.isBlank()) return out;
            for (AgentEvent e : events) {
                // delegation 的 source 本来就是父 Agent/Task（§3.1 字段适用性表），不算违约
                if (e.isDelegation()) continue;
                if (parentTaskId.equals(e.sourceTaskId)) out.add(e);
            }
            return out;
        }

        /**
         * <b>C3 硬 2 判据</b>：派发出去但没有任何回程事件的 delegation。
         *
         * <p>回程 = 该 delegation 的 {@code target.taskId} 出现在某条 {@code output} 或
         * {@code status} 的 {@code source.taskId} 上。空列表 = 每个 member 都有回程，
         * 无静默丢弃、无错配。
         */
        List<AgentEvent> delegationsWithoutReturn() {
            Set<String> returned = new LinkedHashSet<>();
            for (AgentEvent e : events) {
                if ((e.isOutput() || e.isStatus()) && e.sourceTaskId != null) returned.add(e.sourceTaskId);
            }
            List<AgentEvent> out = new ArrayList<>();
            for (AgentEvent d : delegations()) {
                if (d.targetTaskId == null || !returned.contains(d.targetTaskId)) out.add(d);
            }
            return out;
        }

        /**
         * 每条轨迹（按 {@code source.taskId} 分流）的到达序号窗口 {@code [firstIndex, lastIndex]}。
         * 供时间窗观察器按 FEAT-027 §5.6 的分流依据切分并行轨迹使用。
         *
         * <p><b>仅作诊断，不构成判据</b>：窗口重叠只说明两条轨迹的事件在 SSE 上交错到达，
         * 而 §2「并发交织」MUST 明文「不依赖不同生产者之间的到达顺序」——Runtime 按实际观察顺序
         * 串行写入同一 SSE，交错与否受网络与调度影响，不能反推并行度。
         * 真正的并行判据是 {@link #distinctSourceKeys()} 能分流出 ≥2 条轨迹。
         */
        Map<String, int[]> arrivalWindowsBySourceTaskId() {
            Map<String, int[]> windows = new LinkedHashMap<>();
            for (AgentEvent e : events) {
                if (e.sourceTaskId == null) continue;
                windows.compute(e.sourceTaskId, (k, w) -> w == null
                        ? new int[]{e.arrivalIndex, e.arrivalIndex}
                        : new int[]{Math.min(w[0], e.arrivalIndex), Math.max(w[1], e.arrivalIndex)});
            }
            return windows;
        }

        /** {@link #arrivalWindowsBySourceTaskId()} 的可读形式（诊断日志用）。 */
        String arrivalWindowsSummary() {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, int[]> e : arrivalWindowsBySourceTaskId().entrySet()) {
                parts.add(e.getKey() + "=[" + e.getValue()[0] + "," + e.getValue()[1] + "]");
            }
            return parts.toString();
        }

        /** 观察记录（<b>不作判据</b>）：命中的 toolCallId 去重集合。 */
        Set<String> observedToolCallIds() {
            Set<String> ids = new LinkedHashSet<>();
            for (AgentEvent e : events) if (e.toolCallId != null) ids.add(e.toolCallId);
            return ids;
        }

        /** 简明总结（用于 assertion message 与日志）。 */
        String summary() {
            return String.format(
                    "agentEvents=%d (delegation=%d output=%d status=%d) | distinctSourceKeys=%d %s"
                            + " | sourceAgentIds=%s | delegationTargets=%d | missingSource=%d"
                            + " | unknownTypes=%s | [观察记录] toolCallIds=%d",
                    events.size(), delegations().size(), outputs().size(), statuses().size(),
                    distinctSourceKeys().size(), truncate(distinctSourceKeys()),
                    truncate(distinctSourceAgentIds()), delegationTargetTaskIds().size(),
                    eventsMissingSource.size(), unknownTypes,
                    observedToolCallIds().size());
        }

        private static Set<String> truncate(Set<String> set) {
            if (set.size() <= 4) return set;
            Set<String> out = new LinkedHashSet<>();
            for (String s : set) { if (out.size() >= 4) break; out.add(s); }
            return out;
        }
    }

    /** 对单个 JSON 节点递归查找并解析全部 {@code agentEvent}，聚合到 result 上。多次调用可累积。 */
    static void scanInto(JsonNode node, Result result) {
        walk(node, "", result);
    }

    /** 便捷入口：对单个 JSON 节点扫描并返回新 result。 */
    static Result scan(JsonNode node) {
        Result r = new Result();
        walk(node, "", r);
        return r;
    }

    private static void walk(JsonNode node, String path, Result r) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            java.util.Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String key = e.getKey();
                JsonNode v = e.getValue();
                String childPath = path + "." + key;
                // 不预设 agentEvent 挂在哪一层（FEAT-027 §3.1 说它在 Artifact.metadata 下，
                // 但这里仍按字段名递归查找，避免路径写死后 wire 微调导致漏采）
                if ("agentEvent".equals(key) && v.isObject()) {
                    parse(v, childPath, r);
                }
                walk(v, childPath, r);
            }
        } else if (node.isArray()) {
            int i = 0;
            for (JsonNode c : node) {
                walk(c, path + "[" + i + "]", r);
                i++;
            }
        }
    }

    private static void parse(JsonNode ae, String path, Result r) {
        String type = text(ae, "type");
        // type 缺失与 type 取值越界同级违约，都记入 unknownTypes（否则缺失会静默通过闭集校验）
        if (type == null) {
            r.unknownTypes.add(MISSING_TYPE);
        } else if (!VALID_TYPES.contains(type)) {
            r.unknownTypes.add(type);
        }
        AgentEvent event = new AgentEvent(
                type,
                text(ae.path("source"), "agentId"),
                text(ae.path("source"), "taskId"),
                text(ae.path("target"), "agentId"),
                text(ae.path("target"), "taskId"),
                text(ae, "state"),
                text(ae, "toolCallId"),
                path,
                r.arrival++);
        r.events.add(event);
        if (event.sourceAgentId == null || event.sourceTaskId == null) {
            r.eventsMissingSource.add(event);
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) return null;
        JsonNode v = node.path(field);
        if (!v.isTextual()) return null;
        String s = v.asText();
        return s.isBlank() ? null : s;
    }

    /** 断言消息里列事件样本用。 */
    static String sample(List<AgentEvent> events, int max) {
        List<String> out = new ArrayList<>();
        for (AgentEvent e : events) {
            if (out.size() >= max) { out.add("..."); break; }
            out.add(Objects.toString(e));
        }
        return out.toString();
    }
}

package com.huawei.ascend.sit.cases.integration.edpa;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * FEAT-028 共享判据 —— <b>子任务可见性全字段递归扫描</b>。
 *
 * <p><b>动机</b>（2026-08-24 用户反馈复盘）：早期 P0b/P0c/C1/C3/N1 用例判据"硬编码预设承载位"
 * （只查 4 个预设字段路径），导致以下系统性问题：
 * <ul>
 *   <li>字段级观察被错误扩展为通道级结论（"某路径空" → "整条通道没子任务信息"），Issue #93
 *       追加评论多次撤回/修正即源于此；</li>
 *   <li>R1 用例 {@link EdpaSubscribeToTaskResubscribeTest} 首次采用全字段递归扫描后，一举翻案发现
 *       SSE 帧 {@code agentEvent.state}（与 source/target 平级）已承载子任务 state，之前"SSE state
 *       全空"结论过强。</li>
 * </ul>
 *
 * <p><b>本 helper 用途</b>：作为 FEAT-028 全部 wire 可观察面用例的<b>统一判据入口</b>。
 * 不预设 wire 字段名/结构（承接用户 2026-08-24 明示：wire 承载位归设计定，测试只保证客户端能观察到）。
 *
 * <p><b>命中判据集合</b>：
 * <ol>
 *   <li>{@link Result#childTaskIds}：字段名 {@code taskid} / {@code task_id}，值不等于 parent taskId；</li>
 *   <li>{@link Result#childAgentIds}：字段名 {@code agentid} / {@code agent_id}，值不在
 *       {@link #KNOWN_PARENT_AGENT_IDS}；</li>
 *   <li>{@link Result#subStateValues}：字段名 {@code state}，路径包含 {@code agentEvent} /
 *       {@code delegation} / {@code subtask} / {@code child} / {@code source.} / {@code target.} 之一，
 *       且不是父根 status.state；</li>
 *   <li>{@link Result#toolCallIds}：字段名 {@code toolcallid} / {@code tool_call_id}，去重后返回。</li>
 * </ol>
 *
 * <p><b>用法</b>：
 * <pre>{@code
 * EdpaChildVisibilityScanner.Result r = EdpaChildVisibilityScanner.scan(rootJsonNode, parentTaskId);
 * boolean visible = r.anyChildEvidence();
 * // 用 assertThat(visible).as("...%s...", r.summary()).isTrue();
 * }</pre>
 *
 * <p><b>不做的事</b>：本 helper 不校验字段"应该在哪个路径"——那属于开发方约定，测试只报观察事实。
 */
final class EdpaChildVisibilityScanner {

    /** EDPAgent 自身 agentId 约定值（Agent Card 声明）；全字段扫描时用于排除父身份。 */
    static final Set<String> KNOWN_PARENT_AGENT_IDS =
            Set.of("edp-agent", "edp-agent-engine", "EdpAgent");

    private EdpaChildVisibilityScanner() {}

    /** 扫描结果 —— 各集合按插入顺序保留，方便诊断输出。 */
    static final class Result {
        final Set<String> childTaskIds = new LinkedHashSet<>();
        final Set<String> childAgentIds = new LinkedHashSet<>();
        final Set<String> subStateValues = new LinkedHashSet<>();
        final Set<String> toolCallIds = new LinkedHashSet<>();
        final List<String> hitPaths = new ArrayList<>();

        /** 是否有任一子任务证据（用于 "客户端能否看到子任务" 类硬断言）。 */
        boolean anyChildEvidence() {
            return !childTaskIds.isEmpty() || !childAgentIds.isEmpty() || !subStateValues.isEmpty();
        }

        /** 是否观察到 toolCallId（用于 C1/C3 类"批次原子性 / 归位一致映射"断言）。 */
        boolean anyToolCallId() {
            return !toolCallIds.isEmpty();
        }

        /** 简明总结（用于 assertion message）。 */
        String summary() {
            return String.format("childTaskIds=%d %s | childAgentIds=%d %s | subStates=%d %s"
                            + " | toolCallIds=%d %s | 命中路径样本=%s",
                    childTaskIds.size(), truncateSet(childTaskIds, 3),
                    childAgentIds.size(), truncateSet(childAgentIds, 3),
                    subStateValues.size(), truncateSet(subStateValues, 3),
                    toolCallIds.size(), truncateSet(toolCallIds, 3),
                    truncateList(hitPaths, 8));
        }
    }

    /** 对单个 JSON 节点做全字段扫描，聚合到 result 上。多次调用可累积。 */
    static void scanInto(JsonNode node, String parentTaskId, Result result) {
        walk(node, "", parentTaskId, result);
    }

    /** 对单个 JSON 节点做全字段扫描，返回新 result（便捷入口）。 */
    static Result scan(JsonNode node, String parentTaskId) {
        Result r = new Result();
        walk(node, "", parentTaskId, r);
        return r;
    }

    private static void walk(JsonNode node, String path, String parentTaskId, Result r) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            java.util.Iterator<java.util.Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                java.util.Map.Entry<String, JsonNode> e = it.next();
                String key = e.getKey();
                JsonNode v = e.getValue();
                String childPath = path + "." + key;
                String lkey = key.toLowerCase();
                if (v.isTextual() && !v.asText().isBlank()) {
                    String s = v.asText();
                    // 子 taskId
                    if ((lkey.equals("taskid") || lkey.equals("task_id"))
                            && !s.equals(parentTaskId)) {
                        r.childTaskIds.add(s);
                        r.hitPaths.add(childPath + "=" + s);
                    }
                    // 子 agentId
                    if ((lkey.equals("agentid") || lkey.equals("agent_id"))
                            && !KNOWN_PARENT_AGENT_IDS.contains(s)) {
                        r.childAgentIds.add(s);
                        r.hitPaths.add(childPath + "=" + s);
                    }
                    // 子 state：字段名 state 且在 sub-context 路径下、非根 status.state
                    if (lkey.equals("state")) {
                        String plower = childPath.toLowerCase();
                        boolean inSubContext = plower.contains("agentevent")
                                || plower.contains("delegation")
                                || plower.contains("subtask")
                                || plower.contains("child")
                                || plower.contains("source.")
                                || plower.contains("target.");
                        boolean isRootStatus = plower.equals(".result.status.state")
                                || plower.equals(".result.task.status.state")
                                || plower.equals(".result.statusupdate.status.state");
                        if (inSubContext && !isRootStatus) {
                            r.subStateValues.add(childPath + "=" + s);
                            r.hitPaths.add(childPath + "=" + s);
                        }
                    }
                    // toolCallId：不区分路径，去重收集
                    if (lkey.equals("toolcallid") || lkey.equals("tool_call_id")) {
                        r.toolCallIds.add(s);
                        r.hitPaths.add(childPath + "=" + s);
                    }
                }
                walk(v, childPath, parentTaskId, r);
            }
        } else if (node.isArray()) {
            int i = 0;
            for (JsonNode c : node) {
                walk(c, path + "[" + i + "]", parentTaskId, r);
                i++;
            }
        }
    }

    private static <T> List<T> truncateList(List<T> list, int max) {
        if (list.size() <= max) return list;
        return list.subList(0, max);
    }

    private static Set<String> truncateSet(Set<String> set, int max) {
        if (set.size() <= max) return set;
        Set<String> out = new LinkedHashSet<>();
        for (String s : set) { if (out.size() >= max) break; out.add(s); }
        return out;
    }
}

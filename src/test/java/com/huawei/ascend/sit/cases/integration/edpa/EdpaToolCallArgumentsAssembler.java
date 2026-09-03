package com.huawei.ascend.sit.cases.integration.edpa;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FEAT-028 共享判据 —— <b>把 SSE 流里逐 token 增量到达的 {@code tool_calls[].arguments} 重组成完整参数串</b>。
 *
 * <p><b>为什么必须重组</b>（2026-09-03 实测 wire 事实，见 {@code docs/issues/wire-samples/sse.txt}）：
 * llm_output payload 里的 {@code tool_calls} 是 OpenAI 风格的 <b>delta 片段</b>，不是完整对象。
 * 同一个 ToolCall 会横跨几十上百帧，每帧只带 {@code arguments} 的几个字符，且 {@code name} 也是分片的：
 * <pre>
 * 帧185: tool_calls[0] = {id:"call_84e0...", name:"search-agent", arguments:""}
 * 帧186: tool_calls[0] = {id:null,          name:"",             arguments:"{"}
 * 帧187: tool_calls[0] = {id:null,          name:"",             arguments:"\"qu"}
 * ...
 * </pre>
 *
 * <p><b>不重组会导致漏红</b>：P3/P4 的「模型把多个独立实体合并进同一个 ToolCall → 验收判失败」
 * （EDPA L2 §7.3 错误表面验收表）判据，本质是看<b>单个 ToolCall 的完整参数里是否同时出现两个主题</b>。
 * 逐帧去看，任何单个片段都只有几个字符、永远不可能同时命中两个主题关键词 —— 判据恒不开火，
 * 绿灯承载了它举证不了的含义。这与 N1 旧写法、C2/S1 旧判据是同一类失效（见 cases 细档 §5.5.4）。
 *
 * <p><b>分组键</b>：{@code (payload.task_id, tool_calls[].index)}。{@code index} 是 delta 协议里
 * 标识"这是第几个 ToolCall"的字段；{@code task_id} 用于隔离不同子任务/不同轮次的同序号 ToolCall。
 * 两者任一缺失时降级为空串参与分组（宁可把两个 ToolCall 误并成一个 —— 误并只会让参数串更长、
 * 更容易命中多主题，属<b>偏保守方向的失效</b>，但会在诊断输出里暴露 {@code fragments} 异常大）。
 *
 * <p><b>不预设 payload 路径</b>：按字段名递归查找任何带 {@code tool_calls} 数组的对象，
 * 避免把 {@code result.artifactUpdate.artifact.parts[].data.payload} 这条路径写死后，
 * wire 微调即静默漏采。
 *
 * <p><b>判据等级</b>：{@code tool_calls} 属 <b>payload（业务内容）</b>而非 A2A 事件元数据，
 * FEAT-029 §1/§3.1 明确 agent-runtime 只需<b>透传</b> payload、{@code toolCallId} 与
 * {@code agentEvent} "分处不同层级，是并列关系不是包含关系"。故本 helper 产出的观察面
 * <b>不是 wire MUST</b>：<b>取不到（空列表 / 参数串解析不出 JSON）时判 INCONCLUSIVE，不判 FAIL</b>。
 */
final class EdpaToolCallArgumentsAssembler {

    private EdpaToolCallArgumentsAssembler() {}

    /** 重组后的一个 ToolCall。 */
    static final class ToolCall {
        /** payload.task_id，缺失为空串。 */
        final String taskId;
        /** delta 协议的 ToolCall 序号，缺失为 -1。 */
        final int index;
        /** 拼接后的工具名（片段拼接，通常只有首片非空）。 */
        final String name;
        /** 拼接后的完整参数串（通常是一段 JSON 文本）。 */
        final String arguments;
        /** 首次出现时携带的 id（delta 只在首片给 id）。 */
        final String id;
        /** 贡献过片段的帧数，用于诊断"是否真的重组到了"。 */
        final int fragments;

        ToolCall(String taskId, int index, String name, String arguments, String id, int fragments) {
            this.taskId = taskId;
            this.index = index;
            this.name = name;
            this.arguments = arguments;
            this.id = id;
            this.fragments = fragments;
        }

        @Override
        public String toString() {
            return String.format("ToolCall{task=%s idx=%d name=%s id=%s frags=%d args(%d字符)=%s}",
                    taskId, index, name, id, fragments, arguments.length(),
                    arguments.length() <= 200 ? arguments : arguments.substring(0, 200) + "...");
        }
    }

    /** 可变累加器。 */
    private static final class Acc {
        final String taskId;
        final int index;
        final StringBuilder name = new StringBuilder();
        final StringBuilder args = new StringBuilder();
        String id;
        int fragments;

        Acc(String taskId, int index) {
            this.taskId = taskId;
            this.index = index;
        }
    }

    /**
     * 按到达顺序遍历全部帧，重组出所有 ToolCall。
     *
     * @param frames SSE 采集帧（按到达序）
     * @return 重组后的 ToolCall 列表，按首次出现顺序
     */
    static List<ToolCall> assemble(List<EdpaSseCollector.Frame> frames) {
        Map<String, Acc> byKey = new LinkedHashMap<>();
        for (EdpaSseCollector.Frame f : frames) {
            if (f.parsed == null) continue;
            walk(f.parsed, byKey);
        }
        List<ToolCall> out = new ArrayList<>();
        for (Acc a : byKey.values()) {
            out.add(new ToolCall(a.taskId, a.index, a.name.toString(), a.args.toString(), a.id, a.fragments));
        }
        return out;
    }

    /** 递归查找任何持有 {@code tool_calls} 数组的对象，并把其中的 delta 片段累加。 */
    private static void walk(JsonNode node, Map<String, Acc> byKey) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            JsonNode calls = node.path("tool_calls");
            if (calls.isArray()) {
                String taskId = text(node, "task_id");
                for (JsonNode call : calls) {
                    int index = call.path("index").isInt() ? call.path("index").asInt() : -1;
                    String key = taskId + "#" + index;
                    Acc acc = byKey.computeIfAbsent(key, k -> new Acc(taskId, index));
                    String namePart = text(call, "name");
                    String argsPart = text(call, "arguments");
                    String idPart = text(call, "id");
                    if (!namePart.isEmpty()) acc.name.append(namePart);
                    if (!argsPart.isEmpty()) acc.args.append(argsPart);
                    if (acc.id == null && !idPart.isEmpty()) acc.id = idPart;
                    acc.fragments++;
                }
            }
            java.util.Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if ("tool_calls".equals(e.getKey())) continue; // 已消费，避免重复累加
                walk(e.getValue(), byKey);
            }
        } else if (node.isArray()) {
            for (JsonNode c : node) walk(c, byKey);
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) return "";
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asText() : "";
    }

    /** 诊断输出：所有 ToolCall 的一行摘要。 */
    static String summary(List<ToolCall> calls) {
        if (calls.isEmpty()) return "（未观察到任何 tool_calls payload）";
        List<String> parts = new ArrayList<>();
        for (ToolCall c : calls) {
            parts.add(String.format("[%s#%d name=%s frags=%d args=%d字符]",
                    c.taskId, c.index, c.name, c.fragments, c.arguments.length()));
        }
        return parts.toString();
    }
}

package com.huawei.ascend.sit.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>子任务时间窗观察器</b> —— 按 key（推荐 {@code agentEvent.source.taskId}）聚合每条子委托轨迹的
 * <b>首个可观察时间戳</b>（start）与<b>末个可观察时间戳</b>（end），据此判定时间窗是否重叠
 * （{@code max(start_i) < min(end_i)}）。重叠 ⇒ 并行；不重叠 ⇒ 串行。
 *
 * <p><b>⚠ 当前状态（2026-09-02）：全仓无调用方，属预留组件。</b>
 * 此前唯一的引用在 P1（{@code EdpaHomogParallelBlockingTest}）——但那里是 {@code new} 出来立刻打一行
 * 空 {@code summary()}，从未 {@code record()} 过任何事件，是死代码，已删除。删除原因见 P1 类 javadoc：
 * P1 走 BLOCKING（{@code SendMessage}），特性档 §5.0.1 明写该模式不产生中间流式事件，
 * 这条通道上<b>没有子任务时间戳</b>，本观察器在那里天然喂不进数据。
 *
 * <p><b>保留理由</b>：本类实现的正是待建用例 <b>P5b</b>（依赖任务反证，SSE 面）所需的算法——
 * P5b 的判据恰是本类判定的<b>否定</b>：依赖型场景下两条 {@code delegation} 的时间窗<b>不应</b>重叠。
 * 若 P5b 最终不建，本类应一并删除，不要让它继续以"看起来有观察面"的姿态留在仓里。
 *
 * <p><b>不要把它写进任何用例的"观察面"栏，除非该用例真的调用了 {@link #record}。</b>
 * 文档宣称的判据面与代码实际判据不一致，是本仓 2026-09-02 一轮评审集中清理的问题形态。
 *
 * <p><b>用法</b>：把每条观察到的事件（key + 事件时间戳）feed 进 {@link #record}，
 * 全部消化完调用 {@link #timeWindowsOverlap()} 拿判定结果，或 {@link #summary()} 拿诊断字符串。
 * 窗口数 &lt; 2 时 {@link #timeWindowsOverlap()} 返回 false。
 *
 * <p><b>分流键选择</b>：用 {@code source.taskId} 而非 {@code toolCallId}——后者是 MAY 级扩展字段
 * （FEAT-027 §5.9「不属于最小公共字段」），实现侧按 spec 停止透出时整条观察面会失效。
 *
 * <p><b>lifecycle</b>：非资源型，栈上使用即可。
 */
public final class BatchTimingObserver {

    private static final class Window {
        final String key;
        long start = Long.MAX_VALUE;
        long end = Long.MIN_VALUE;
        int eventCount;

        Window(String key) { this.key = key; }
    }

    private final Map<String, Window> windowsByKey = new HashMap<>();

    /**
     * 记录一次可观察事件。{@code key} 是子任务标识（toolCallId 或 sub-taskId 或 agent_name+序号）。
     * {@code timestampMs} 是事件观察到的 epoch 毫秒。
     */
    public void record(String key, long timestampMs) {
        if (key == null || key.isBlank()) return;
        Window w = windowsByKey.computeIfAbsent(key, Window::new);
        if (timestampMs < w.start) w.start = timestampMs;
        if (timestampMs > w.end) w.end = timestampMs;
        w.eventCount++;
    }

    /** 便捷：从 ISO-8601 时间戳字符串解析后记录（A2A 事件的 status.timestamp 就是 ISO-8601）。 */
    public void recordIso(String key, String isoTimestamp) {
        if (isoTimestamp == null || isoTimestamp.isBlank()) return;
        try {
            record(key, Instant.parse(isoTimestamp).toEpochMilli());
        } catch (DateTimeParseException ignore) {
            // 解析失败静默忽略——防止事件时间戳异常拖累整体判定。
        }
    }

    /** 已记录的子任务数（不同 key 数量）。 */
    public int subtaskCount() {
        return windowsByKey.size();
    }

    /**
     * 并行时间窗重叠判定：`max(start_i) < min(end_i)`（严格重叠，非仅端点相接）。
     * 仅在子任务数 ≥ 2 时有意义；单子任务或空返回 false。
     */
    public boolean timeWindowsOverlap() {
        if (windowsByKey.size() < 2) return false;
        long maxStart = Long.MIN_VALUE;
        long minEnd = Long.MAX_VALUE;
        for (Window w : windowsByKey.values()) {
            if (w.start > maxStart) maxStart = w.start;
            if (w.end < minEnd) minEnd = w.end;
        }
        return maxStart < minEnd;
    }

    /** 判定证据：max(start) 与 min(end)，供诊断输出。 */
    public long maxStart() {
        return windowsByKey.values().stream().mapToLong(w -> w.start).max().orElse(0);
    }

    public long minEnd() {
        return windowsByKey.values().stream().mapToLong(w -> w.end).min().orElse(0);
    }

    /** 每个子任务的窗口摘要（按 key 排序，用于稳定诊断输出）。 */
    public List<String> windowSummaries() {
        List<String> out = new ArrayList<>();
        List<String> keys = new ArrayList<>(windowsByKey.keySet());
        Collections.sort(keys);
        for (String k : keys) {
            Window w = windowsByKey.get(k);
            out.add(String.format("key=%s start=%d end=%d duration=%dms events=%d",
                    k, w.start, w.end, w.end - w.start, w.eventCount));
        }
        return out;
    }

    /** 诊断摘要一行输出。 */
    public String summary() {
        return String.format("subtasks=%d overlap=%s maxStart=%d minEnd=%d gap=%dms | %s",
                subtaskCount(), timeWindowsOverlap(), maxStart(), minEnd(),
                minEnd() - maxStart(), String.join(" ; ", windowSummaries()));
    }
}

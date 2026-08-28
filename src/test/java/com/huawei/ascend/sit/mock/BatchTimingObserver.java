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
 * FEAT-028 并行主线用例（P1~P4）的<b>子任务时间窗观察器</b> —— 从 A2A 事件流（`statusUpdate` /
 * `artifactUpdate`）或后续实测钉死的父任务快照承载位中，按 `toolCallId` 或 `taskId` 聚合每个子任务
 * 的**首次可观察时间戳**（作为 start）和**最后一次可观察时间戳**（作为 end），输出「并行时间窗
 * 重叠」证据（`max(start_i) < min(end_i)` 硬判定）。
 *
 * <p><b>用法</b>：把每条观察到的事件（含 taskId/toolCallId + 事件时间戳）feed 进 {@link #record}，
 * 全部事件消化完调用 {@link #timeWindowsOverlap()} 得到并行判定结果，或 {@link #summary()} 拿到诊断字符串。
 *
 * <p><b>降级模式（8-24 实测）</b>：EDPAgent 当前实现的 GetTask 父快照不承载子任务事件（P0b/P0c 已证），
 * 因此本观察器暂时从**并行子任务在被委托方 runtime 侧的 taskId** 聚合窗口时——即通过 SSE
 * `SendStreamingMessage` 观察父任务事件流里的 `statusUpdate.taskId`（父）+ SDK
 * `source.agentId`/`source.taskId`（子）字段。承载位钉死后可切换到更精准的 wire 面。
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

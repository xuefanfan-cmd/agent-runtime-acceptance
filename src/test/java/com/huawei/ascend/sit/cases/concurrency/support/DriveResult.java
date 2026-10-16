/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.concurrency.support;

import java.util.List;
import java.util.Locale;

/**
 * 一次 A2A 驱动的归一结果：Task 终态 + HTTP 状态 + 原始 SSE {@code data:} 事件 + 解析出的 taskId。
 *
 * @since 0.1.0
 */
public record DriveResult(TaskState state, int httpStatus, List<String> dataEvents, String taskId) {

    /** A2A Task 终态（从 SSE 事件里的 {@code TASK_STATE_*} token 归一而来）。 */
    public enum TaskState {
        COMPLETED,
        INPUT_REQUIRED,
        FAILED,
        CANCELED,
        REJECTED,
        UNKNOWN
    }

    /**
     * 任一原始事件是否包含某子串（去逗号、去空格后忽略大小写匹配）。
     *
     * @param needle
     *            待匹配子串
     * @return 是否命中
     */
    public boolean anyContains(String needle) {
        String normalizedNeedle = normalize(needle);
        return dataEvents.stream().anyMatch(event -> normalize(event).contains(normalizedNeedle));
    }

    /**
     * 全部事件拼接（排障用）。
     *
     * @return 全部事件按行拼接的文本
     */
    public String rawText() {
        return String.join("\n", dataEvents);
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replace(",", "").replace(" ", "");
    }
}

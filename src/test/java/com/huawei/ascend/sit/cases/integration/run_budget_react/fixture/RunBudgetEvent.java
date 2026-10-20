/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.run_budget_react.fixture;

import java.util.Map;

/**
 * One parsed FEAT-057 budget log event. {@link #name} is the canonical enum
 * (never null — events are only emitted into this DTO when their {@code event_type}
 * matched one of the 11 known names); {@link #payload} is the full JSON map
 * (minus the {@code event_type} key).
 *
 * <p>{@link #logLineOffset} is the 1-based index of the matching JSON line in
 * the tailed log file, giving a stable ordinal for sequence assertions without
 * relying on JSON timestamps.
 */
public record RunBudgetEvent(
        BudgetEventName name,
        Map<String, Object> payload,
        int logLineOffset) {

    public Integer intField(String key) {
        Object v = payload.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        return null;
    }

    public Boolean boolField(String key) {
        Object v = payload.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        return null;
    }

    public String stringField(String key) {
        Object v = payload.get(key);
        return v == null ? null : v.toString();
    }
}

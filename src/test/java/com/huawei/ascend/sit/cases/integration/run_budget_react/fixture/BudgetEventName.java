/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.run_budget_react.fixture;

/**
 * 11 FEAT-057 budget event names. Enum constants mirror the agent-core-java
 * {@code LogEventType} constants exactly (upper SNAKE_CASE contract names);
 * {@link #wireValue()} returns the lower snake_case string that
 * {@code BaseLogEvent.toMap} serializes into the {@code event_type} JSON field.
 *
 * <p>Reference anchors (agent-core-java@origin/develop, 2026-10-19):
 * core/src/main/java/com/openjiuwen/core/common/logging/events/LogEventType.java
 * (enum names; search by name, not by line number) and
 * core/.../common/logging/events/BaseLogEvent.toMap:81 (serialization rule).
 */
public enum BudgetEventName {
    TURN_BUDGET_GUARANTEE_EFFECTIVE,
    TURN_BUDGET_CHECKPOINT,
    TURN_BUDGET_EXTENDED,
    TURN_BUDGET_STALL_PROMPTED,
    TURN_BUDGET_GENTLE_REMINDER,
    TURN_BUDGET_SAFETY_VALVE_TRIGGERED,
    TURN_BUDGET_FINAL_ROUND_GRANTED,
    TURN_BUDGET_TERMINATED,
    TIME_BUDGET_EFFECTIVE,
    TIME_BUDGET_NEAR_DEADLINE_PROMPTED,
    TIME_BUDGET_TERMINATED;

    public String wireValue() {
        return name().toLowerCase();
    }

    public static BudgetEventName fromWire(String wire) {
        if (wire == null) {
            return null;
        }
        try {
            return valueOf(wire.toUpperCase());
        } catch (IllegalArgumentException notBudget) {
            return null;
        }
    }
}

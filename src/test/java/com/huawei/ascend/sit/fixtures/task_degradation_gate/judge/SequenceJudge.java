package com.huawei.ascend.sit.fixtures.task_degradation_gate.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.Feat112Contract;
import com.huawei.ascend.sit.judge.EvidenceChecks;
import com.huawei.ascend.sit.judge.EvidenceChecks.Category;
import com.huawei.ascend.sit.judge.EvidenceChecks.Context;
import com.huawei.ascend.sit.judge.EvidenceChecks.Finding;
import com.huawei.ascend.sit.judge.EvidenceChecks.Observation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * W/N families. W: after an observed task terminal, the same instance's window counts and state. N: a refused or
 * pre-validated request leaves no task, no window change and no model call. Pure predicates over captured
 * snapshots, task lists and stub statistics.
 */
public final class SequenceJudge {
    private SequenceJudge() {}

    /** {@code withinWindow}: the evidence was collected inside one declared window, so nothing expired between. */
    public record Window(JsonNode before, JsonNode after, String terminal, boolean withinWindow) {}

    public record SideEffects(JsonNode before, JsonNode after, JsonNode tasksBefore, JsonNode tasksAfter,
                              JsonNode statsBefore, JsonNode statsAfter, boolean withinWindow) {}

    public static Finding window(String id, Context context, Observation<Window> evidence,
                                 String terminal, long samples, long failures, String state) {
        return EvidenceChecks.check(Feat112Contract.rule(id, "window", "§5.1.2/§5.1.3", Category.INTERNAL_STATE), context, evidence,
                "same instance, observed " + terminal + ", samples=" + samples + ", failures=" + failures + ", state=" + state,
                w -> w != null && w.withinWindow() && terminal.equals(w.terminal())
                        && sameInstance(w.before(), w.after()) && integral(w.after(), "sampleCount", samples)
                        && integral(w.after(), "systemFailureCount", failures)
                        && w.after().path("systemFailureRate").isNumber()
                        && Math.abs(w.after().path("systemFailureRate").doubleValue() - (samples == 0 ? 0 : (double) failures / samples)) < 1e-9
                        && state.equals(w.after().path("state").asText()));
    }

    public static List<Finding> noSideEffects(String id, Context context, Observation<SideEffects> evidence) {
        Finding state = EvidenceChecks.check(Feat112Contract.rule(id, "noTaskOrSample", "§3.1/能力表“不制造假任务”",
                        Category.INTERNAL_STATE), context, evidence, "unchanged task ID set and window on the same instance",
                e -> e != null && e.withinWindow() && sameInstance(e.before(), e.after())
                        && sameTasks(e.tasksBefore(), e.tasksAfter())
                        && sameCount(e.before(), e.after(), "sampleCount") && sameCount(e.before(), e.after(), "systemFailureCount"));
        Finding execution = EvidenceChecks.check(Feat112Contract.rule(id, "noModelCall", "§3.1/验收出口8",
                        Category.INTERNAL_EXECUTION_OBSERVABILITY), context, evidence,
                "unchanged stub generation and model call count",
                e -> e != null && e.withinWindow() && sameInstance(e.before(), e.after())
                        && sameCount(e.statsBefore(), e.statsAfter(), "generation")
                        && sameCount(e.statsBefore(), e.statsAfter(), "totalRequests"));
        return List.of(state, execution);
    }

    private static boolean sameTasks(JsonNode before, JsonNode after) {
        Set<String> left = taskIds(before), right = taskIds(after);
        return left != null && right != null && left.equals(right);
    }

    private static Set<String> taskIds(JsonNode tasks) {
        if (tasks == null || !tasks.isArray()) return null;
        Set<String> ids = new HashSet<>();
        for (JsonNode task : tasks) {
            JsonNode id = task.get("id");
            if (id == null || !id.isTextual() || id.asText().isBlank() || !ids.add(id.asText())) return null;
        }
        return ids;
    }

    private static boolean sameInstance(JsonNode a, JsonNode b) {
        return a != null && b != null && a.path("instanceId").isTextual() && !a.path("instanceId").asText().isBlank()
                && a.path("instanceId").equals(b.path("instanceId"));
    }

    private static boolean sameCount(JsonNode a, JsonNode b, String key) {
        return a != null && b != null && a.path(key).isIntegralNumber() && a.path(key).canConvertToLong()
                && b.path(key).isIntegralNumber() && b.path(key).canConvertToLong()
                && a.path(key).longValue() >= 0 && a.path(key).longValue() == b.path(key).longValue();
    }

    private static boolean integral(JsonNode n, String key, long expected) {
        return expected >= 0 && n.path(key).isIntegralNumber() && n.path(key).canConvertToLong() && n.path(key).longValue() == expected;
    }
}

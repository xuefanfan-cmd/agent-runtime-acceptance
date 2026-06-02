package com.huawei.ascend.sit.utils;

import com.huawei.ascend.sit.model.scenario.StepTransition;

import java.util.List;
import java.util.Map;

/**
 * Evaluates transition conditions against the execution context.
 *
 * <p>Supports simple comparison expressions:
 * <ul>
 *   <li>{@code == 'value'} — string equality</li>
 *   <li>{@code != 'value'} — string inequality</li>
 *   <li>{@code != ''} — non-empty check</li>
 * </ul>
 *
 * <p>Evaluation order: conditional transitions first (in order), then default.
 * This matches the YAML definition where the first matching condition wins.</p>
 */
public final class TransitionEvaluator {

    private TransitionEvaluator() {}

    /**
     * Resolve the next step ID by evaluating transitions against the context.
     *
     * @param transitions the list of transition rules (order matters)
     * @param context     the current execution context
     * @return the target step ID, or "END" if the scenario should terminate
     * @throws IllegalStateException if no matching transition is found
     */
    public static String resolveNextStep(List<StepTransition> transitions, Map<String, Object> context) {
        if (transitions == null || transitions.isEmpty()) {
            return "END";
        }

        // Phase 1: Evaluate conditional transitions in order
        for (StepTransition t : transitions) {
            if (t.isConditional()) {
                Object value = resolveContextValue(context, t.source(), t.path());
                if (evaluateCondition(value, t.condition())) {
                    return t.gotoStep();
                }
            }
        }

        // Phase 2: Fall back to default transition
        for (StepTransition t : transitions) {
            if (t.isDefault()) {
                return t.defaultStep();
            }
        }

        throw new IllegalStateException(
                "No matching transition found in step. Transitions: " + transitions);
    }

    /**
     * Resolve a value from the context.
     *
     * @param context the execution context map
     * @param source  currently only "context" is supported
     * @param path    the field name within the context
     * @return the resolved value, or null if not found
     */
    static Object resolveContextValue(Map<String, Object> context, String source, String path) {
        if (!"context".equals(source) || path == null) {
            return null;
        }
        return context.get(path);
    }

    /**
     * Evaluate a condition expression against a value.
     *
     * @param value     the actual value from context
     * @param condition the condition expression (e.g. "== 'confirm_card'", "!= ''")
     * @return true if the condition matches
     */
    static boolean evaluateCondition(Object value, String condition) {
        String strValue = value != null ? String.valueOf(value) : "";

        if (condition.startsWith("== ")) {
            String expected = unquote(condition.substring(3).trim());
            return strValue.equals(expected);
        } else if (condition.startsWith("!= ")) {
            String expected = unquote(condition.substring(3).trim());
            return !strValue.equals(expected);
        } else if (condition.startsWith("contains ")) {
            String expected = unquote(condition.substring(9).trim());
            return strValue.contains(expected);
        }

        throw new IllegalArgumentException("Unsupported condition expression: " + condition);
    }

    private static String unquote(String s) {
        if ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\""))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}

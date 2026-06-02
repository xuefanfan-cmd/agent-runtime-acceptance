package com.huawei.ascend.sit.model.scenario;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A transition rule that determines which step to execute next.
 *
 * <p>Transitions come in two flavours:
 * <ul>
 *   <li><b>Conditional</b>: evaluates a condition against the execution context.
 *       If the condition matches, execution jumps to the specified {@code goto} step.</li>
 *   <li><b>Default</b>: if no conditional transition matches, execution proceeds
 *       to the {@code default} step.</li>
 * </ul>
 *
 * <p>YAML examples:
 * <pre>
 * # Conditional transition
 * - source: "context"
 *   path: "skipTo"
 *   condition: "== 'confirm_card'"
 *   goto: "on_confirm_card"
 *
 * # Default transition
 * - default: "on_payee_input"
 * </pre>
 *
 * <p>Special target: {@code "END"} terminates the scenario.</p>
 *
 * @param source      where to read the value from (currently only "context")
 * @param path        dot-separated field path within the source
 * @param condition   comparison expression (e.g. "== 'value'", "!= ''")
 * @param gotoStep    target step ID when condition matches
 * @param defaultStep target step ID when no condition matches (fallback)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StepTransition(
        String source,
        String path,
        String condition,
        @JsonProperty("goto") String gotoStep,
        @JsonProperty("default") String defaultStep
) {
    /**
     * Whether this is a conditional transition (has source, condition, and goto).
     */
    public boolean isConditional() {
        return source != null && condition != null && gotoStep != null;
    }

    /**
     * Whether this is a default (fallback) transition.
     */
    public boolean isDefault() {
        return defaultStep != null;
    }

    /**
     * Get the target step ID for this transition.
     * For conditional transitions, returns gotoStep; for default, returns defaultStep.
     */
    public String targetStep() {
        if (gotoStep != null) return gotoStep;
        if (defaultStep != null) return defaultStep;
        return null;
    }
}

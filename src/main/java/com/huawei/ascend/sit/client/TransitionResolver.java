package com.huawei.ascend.sit.client;

import com.huawei.ascend.sit.model.scenario.ScenarioStep;
import com.huawei.ascend.sit.utils.TransitionEvaluator;

import java.util.List;
import java.util.Map;

/**
 * Strategy interface for determining the next step in a scenario execution.
 *
 * <p>The backend A2A agent may decide to jump to non-adjacent steps based on
 * the request content and its internal logic. This resolver allows the executor
 * to follow the backend's flow decisions.</p>
 *
 * <h3>Built-in strategies:</h3>
 * <ul>
 *   <li>{@link #yamlTransitions()} — evaluate YAML conditional transitions
 *       via {@link TransitionEvaluator} (default)</li>
 *   <li>{@link #linear(List)} — strict step N → step N+1 progression</li>
 * </ul>
 *
 * <h3>Custom resolver example:</h3>
 * <pre>{@code
 * TransitionResolver resolver = (step, result, ctx) -> {
 *     // Jump based on backend response state
 *     if (result.taskState() == TaskState.TASK_STATE_COMPLETED
 *             && "on_bankcards_input".equals(step.id())) {
 *         return "on_confirm_card";  // backend skipped card selection
 *     }
 *     // Fall back to YAML transitions
 *     return TransitionEvaluator.resolveNextStep(step.transitions(), ctx);
 * };
 * }</pre>
 */
@FunctionalInterface
public interface TransitionResolver {

    /**
     * Determine the next step ID after the current step completes.
     *
     * @param currentStep the step that just completed
     * @param lastResult  the result of the current step (includes task state, events)
     * @param context     mutable execution context (may be updated by resolver)
     * @return the next step ID, or {@code "END"} to terminate the scenario
     */
    String resolve(ScenarioStep currentStep,
                   ScenarioExecutionResult.StepResult lastResult,
                   Map<String, Object> context);

    /**
     * Default resolver: evaluate YAML conditional transitions.
     *
     * <p>Uses the existing {@link TransitionEvaluator} to check conditional
     * transitions in order, then falls back to default transition.</p>
     */
    static TransitionResolver yamlTransitions() {
        return (step, result, ctx) ->
                TransitionEvaluator.resolveNextStep(step.transitions(), ctx);
    }

    /**
     * Linear resolver: always advance to the next step in the given step list.
     *
     * <p>Step N → Step N+1 → ... → END. No conditional jumps.</p>
     *
     * @param steps the ordered step list from the scenario definition
     */
    static TransitionResolver linear(List<ScenarioStep> steps) {
        return (step, result, ctx) -> {
            for (int i = 0; i < steps.size(); i++) {
                if (steps.get(i).id().equals(step.id())) {
                    return i + 1 < steps.size() ? steps.get(i + 1).id() : "END";
                }
            }
            return "END";
        };
    }
}

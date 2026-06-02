package com.huawei.ascend.sit.client;

import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.TaskState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Captures the result of executing a multi-turn interaction scenario.
 *
 * <p>Contains a detailed trace of every step executed, including full event
 * streams and request content. Used by test evaluators to verify the scenario
 * completed as expected.</p>
 *
 * @param scenarioName  the name of the executed scenario
 * @param completed     whether the scenario reached END successfully
 * @param stepResults   ordered list of per-step execution results
 * @param finalContext  the context state after scenario completion
 * @param error         the first error encountered, or null if successful
 */
public record ScenarioExecutionResult(
        String scenarioName,
        boolean completed,
        List<StepResult> stepResults,
        Map<String, Object> finalContext,
        Throwable error
) {
    /**
     * Create a successful result.
     */
    public static ScenarioExecutionResult success(String name, List<StepResult> steps, Map<String, Object> context) {
        return new ScenarioExecutionResult(name, true, steps, context, null);
    }

    /**
     * Create a failed result.
     */
    public static ScenarioExecutionResult failure(String name, List<StepResult> steps,
                                                   Map<String, Object> context, Throwable error) {
        return new ScenarioExecutionResult(name, false, steps, context, error);
    }

    /** Get the last step result, or null if no steps were executed. */
    public StepResult lastStep() {
        return stepResults.isEmpty() ? null : stepResults.get(stepResults.size() - 1);
    }

    /** Find a step result by its step ID. */
    public Optional<StepResult> findStep(String stepId) {
        return stepResults.stream()
                .filter(sr -> stepId.equals(sr.stepId()))
                .findFirst();
    }

    /**
     * Result of executing a single step within a scenario.
     *
     * <p>Enriched with the full event stream snapshot and request content,
     * supporting detailed evaluator inspections.</p>
     *
     * @param stepId         the step identifier
     * @param taskId         the A2A task ID created for this step
     * @param taskState      the final task state observed
     * @param requestContent the text content that was sent
     * @param events         full event stream snapshot from this step
     * @param eventCount     number of events received during this step
     * @param error          error if this step failed
     */
    public record StepResult(
            String stepId,
            String taskId,
            TaskState taskState,
            String requestContent,
            List<ClientEvent> events,
            int eventCount,
            Throwable error
    ) {
        public static StepResult of(String stepId, String taskId, TaskState state,
                                     String requestContent, List<ClientEvent> events, int eventCount) {
            return new StepResult(stepId, taskId, state, requestContent, events, eventCount, null);
        }

        public static StepResult failed(String stepId, Throwable error) {
            return new StepResult(stepId, null, null, null, List.of(), 0, error);
        }
    }

    /**
     * Builder for incrementally constructing the result during execution.
     */
    public static class Builder {
        private final String scenarioName;
        private final List<StepResult> steps = new ArrayList<>();
        private final Map<String, Object> context;
        private Throwable error;

        public Builder(String scenarioName, Map<String, Object> initialContext) {
            this.scenarioName = scenarioName;
            this.context = new HashMap<>(initialContext);
        }

        public Builder addStep(StepResult step) {
            steps.add(step);
            return this;
        }

        public Builder updateContext(String key, Object value) {
            context.put(key, value);
            return this;
        }

        public Builder updateContext(Map<String, Object> updates) {
            context.putAll(updates);
            return this;
        }

        public Builder setError(Throwable error) {
            this.error = error;
            return this;
        }

        public Map<String, Object> context() {
            return context;
        }

        public ScenarioExecutionResult build() {
            if (error != null) {
                return failure(scenarioName, steps, context, error);
            }
            return success(scenarioName, steps, context);
        }
    }
}

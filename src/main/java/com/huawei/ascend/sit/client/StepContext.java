package com.huawei.ascend.sit.client;

import com.huawei.ascend.sit.model.scenario.ScenarioStep;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Accumulated context available to {@link RequestProvider} during request construction.
 *
 * <p>Provides access to:</p>
 * <ul>
 *   <li><b>Current step</b> — identity and metadata from YAML definition</li>
 *   <li><b>Previous results</b> — ordered list of completed step results with full event data</li>
 *   <li><b>Mutable context map</b> — shared state across steps, initialized from YAML
 *       {@code context} and updated by both executor internals and request providers</li>
 * </ul>
 *
 * <p>The context map is <b>intentionally mutable</b> — request providers may store
 * intermediate data (e.g., third-party API responses) for use in later steps.</p>
 */
public class StepContext {

    private final String currentStepId;
    private final int stepIndex;
    private final ScenarioStep currentStep;
    private final Map<String, Object> context;
    private final List<ScenarioExecutionResult.StepResult> completedSteps;

    public StepContext(String currentStepId,
                       int stepIndex,
                       ScenarioStep currentStep,
                       Map<String, Object> context,
                       List<ScenarioExecutionResult.StepResult> completedSteps) {
        this.currentStepId = currentStepId;
        this.stepIndex = stepIndex;
        this.currentStep = currentStep;
        this.context = context;
        this.completedSteps = completedSteps;
    }

    /** Current step identifier. */
    public String currentStepId() {
        return currentStepId;
    }

    /** 0-based index of the current step in the scenario. */
    public int stepIndex() {
        return stepIndex;
    }

    /** Current step's YAML definition (metadata, description, uiHint, etc.). */
    public ScenarioStep currentStep() {
        return currentStep;
    }

    /** Mutable context map shared across all steps. */
    public Map<String, Object> context() {
        return context;
    }

    /** Get a typed value from the context map. */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) context.get(key);
    }

    /** Store a value in the context map for later steps to access. */
    public void put(String key, Object value) {
        context.put(key, value);
    }

    /** Ordered list of results from previously completed steps. */
    public List<ScenarioExecutionResult.StepResult> completedSteps() {
        return Collections.unmodifiableList(completedSteps);
    }

    /** Convenience: the most recent step result, or null if this is the first step. */
    public ScenarioExecutionResult.StepResult lastResult() {
        return completedSteps.isEmpty() ? null
                : completedSteps.get(completedSteps.size() - 1);
    }
}

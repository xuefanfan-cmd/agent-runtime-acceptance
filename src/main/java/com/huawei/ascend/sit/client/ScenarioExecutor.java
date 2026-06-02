package com.huawei.ascend.sit.client;

import com.huawei.ascend.sit.model.scenario.ScenarioDefinition;
import com.huawei.ascend.sit.model.scenario.ScenarioStep;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TaskState;
import org.awaitility.Awaitility;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Step-driven executor for multi-turn A2A agent interaction scenarios.
 *
 * <p>Separates three concerns that were previously entangled:</p>
 * <ol>
 *   <li><b>Request construction</b> — delegated to {@link RequestProvider} (test code)</li>
 *   <li><b>Step progression</b> — delegated to {@link TransitionResolver}
 *       (default: YAML transitions; overridable by test code)</li>
 *   <li><b>Result collection</b> — executor collects full event streams per step,
 *       test code evaluates via {@link ScenarioExecutionResult}</li>
 * </ol>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * ScenarioDefinition scenario = loadScenario("scenario/transfer-scenario.yaml");
 *
 * // Test code constructs requests (may use previous responses or external APIs)
 * RequestProvider requestProvider = (stepId, ctx) -> switch (stepId) {
 *     case "on_transfer_request" -> A2A.toUserMessage("我要进行快速转账");
 *     case "on_payee_input" -> {
 *         String cards = mockService.getCards();
 *         ctx.put("cardData", cards);
 *         yield A2A.toUserMessage("选择收款卡: " + cards);
 *     }
 *     default -> throw new IllegalArgumentException("Unknown step: " + stepId);
 * };
 *
 * // Execute with YAML-driven transitions (default)
 * ScenarioExecutionResult result = new ScenarioExecutor(client)
 *         .execute(scenario, requestProvider);
 *
 * // Evaluate results in test code
 * assertThat(result.completed()).isTrue();
 * result.findStep("on_confirm_card")
 *         .map(StepResult::taskState)
 *         .contains(TaskState.TASK_STATE_COMPLETED);
 * }</pre>
 *
 * @see RequestProvider
 * @see TransitionResolver
 * @see ScenarioExecutionResult
 */
public class ScenarioExecutor {

    private static final Logger LOG = Logger.getLogger(ScenarioExecutor.class.getName());
    private static final String END_STEP = "END";
    private static final int DEFAULT_MAX_STEPS = 50;
    private static final long DEFAULT_TIMEOUT_MS = 30_000;

    private final A2aServiceClient client;
    private long timeoutMs = DEFAULT_TIMEOUT_MS;
    private int maxSteps = DEFAULT_MAX_STEPS;

    public ScenarioExecutor(A2aServiceClient client) {
        this.client = client;
    }

    public ScenarioExecutor withTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
        return this;
    }

    public ScenarioExecutor withMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
        return this;
    }

    /**
     * Execute a scenario with YAML-driven transitions (default).
     *
     * @param scenario       the scenario definition loaded from YAML
     * @param requestProvider test-code-driven request construction
     * @return the execution result with full event streams per step
     */
    public ScenarioExecutionResult execute(ScenarioDefinition scenario,
                                            RequestProvider requestProvider) {
        return execute(scenario, requestProvider, TransitionResolver.yamlTransitions());
    }

    /**
     * Execute a scenario with a custom transition resolver.
     *
     * <p>The resolver determines which step to execute next after each step completes.
     * This enables non-linear progression (e.g., backend-driven jumps from step 2 to step 5).</p>
     *
     * @param scenario          the scenario definition loaded from YAML
     * @param requestProvider   test-code-driven request construction
     * @param transitionResolver strategy for determining next step
     * @return the execution result with full event streams per step
     */
    public ScenarioExecutionResult execute(ScenarioDefinition scenario,
                                            RequestProvider requestProvider,
                                            TransitionResolver transitionResolver) {
        // Build step lookup map: id → step
        Map<String, ScenarioStep> stepMap = scenario.steps().stream()
                .collect(Collectors.toMap(ScenarioStep::id, s -> s));

        // Initialize execution context from scenario
        Map<String, Object> context = scenario.context() != null
                ? new HashMap<>(scenario.context())
                : new HashMap<>();

        ScenarioExecutionResult.Builder resultBuilder =
                new ScenarioExecutionResult.Builder(scenario.name(), context);

        List<ScenarioExecutionResult.StepResult> completedSteps = new java.util.ArrayList<>();

        // Start from first step
        String currentStepId = scenario.steps().get(0).id();
        int stepCount = 0;

        LOG.info("▶ Starting scenario: " + scenario.name());

        try {
            while (!END_STEP.equals(currentStepId) && stepCount < maxSteps) {
                stepCount++;
                ScenarioStep step = stepMap.get(currentStepId);

                if (step == null) {
                    throw new IllegalStateException(
                            "Step not found: " + currentStepId + ". Available: " + stepMap.keySet());
                }

                LOG.info("  → Step " + stepCount + ": [" + step.id() + "] " + step.description());

                // Build StepContext for request provider
                StepContext stepContext = new StepContext(
                        step.id(), stepCount - 1, step, context,
                        List.copyOf(completedSteps));

                // Execute the step
                ScenarioExecutionResult.StepResult stepResult =
                        executeStep(step, requestProvider, stepContext);

                resultBuilder.addStep(stepResult);
                completedSteps.add(stepResult);

                // Check for step failure
                if (stepResult.error() != null) {
                    resultBuilder.setError(stepResult.error());
                    LOG.warning("  ✗ Step failed: " + stepResult.error().getMessage());
                    break;
                }

                LOG.info("  ✓ State: " + stepResult.taskState()
                        + " (task: " + stepResult.taskId()
                        + ", events: " + stepResult.eventCount() + ")");

                // If FAILED or REJECTED, stop execution
                if (stepResult.taskState() == TaskState.TASK_STATE_FAILED
                        || stepResult.taskState() == TaskState.TASK_STATE_REJECTED) {
                    resultBuilder.setError(new AssertionError(
                            "Step [" + step.id() + "] ended with unexpected state: "
                                    + stepResult.taskState()));
                    break;
                }

                // Update context with step result (for transition evaluation)
                context.put("_lastStepId", step.id());
                context.put("_lastTaskId", stepResult.taskId());
                context.put("_lastTaskState", stepResult.taskState() != null
                        ? stepResult.taskState().name() : null);
                resultBuilder.updateContext(context);

                // Resolve next step via transition resolver
                currentStepId = transitionResolver.resolve(step, stepResult, context);
            }

            if (stepCount >= maxSteps) {
                resultBuilder.setError(new IllegalStateException(
                        "Scenario exceeded max step limit (" + maxSteps + "). Possible infinite loop."));
                LOG.warning("  ⚠ Max steps reached!");
            }

        } catch (Exception e) {
            resultBuilder.setError(e);
            LOG.log(Level.WARNING, "  ✗ Scenario execution error: " + e.getMessage(), e);
        }

        ScenarioExecutionResult result = resultBuilder.build();
        LOG.info(result.completed()
                ? "✔ Scenario completed successfully (" + stepCount + " steps)"
                : "✗ Scenario failed at step " + stepCount);
        return result;
    }

    /**
     * Execute a single step: construct request via provider → send → collect events → snapshot.
     */
    private ScenarioExecutionResult.StepResult executeStep(ScenarioStep step,
                                                            RequestProvider requestProvider,
                                                            StepContext stepContext) {
        A2aEventCollector collector = new A2aEventCollector();

        try {
            // Delegate request construction to test code
            Message message = requestProvider.provide(step.id(), stepContext);
            String requestContent = extractTextContent(message);

            // Create consumers and error handler
            List<BiConsumer<ClientEvent, AgentCard>> consumers =
                    List.of(collector.createConsumer());
            Consumer<Throwable> errorHandler = error ->
                    LOG.warning("Stream error during step [" + step.id() + "]: " + error.getMessage());

            // Send the message
            client.sendMessage(message, consumers, errorHandler);

            // Wait for terminal or input-required state
            TaskState finalState;
            try {
                finalState = collector.awaitTerminalState(timeoutMs);
            } catch (AssertionError e) {
                // No terminal state — might be INPUT_REQUIRED or intermediate
                TaskState anyState = collector.awaitAnyTaskState(timeoutMs);
                if (anyState == null) {
                    return ScenarioExecutionResult.StepResult.failed(step.id(),
                            new AssertionError("No task state received within timeout for step: " + step.id()));
                }
                finalState = anyState;
            }

            // Extract task ID (non-destructive)
            String taskId = collector.findFirstTaskId();

            // Snapshot the full event stream
            List<ClientEvent> events = collector.snapshotAllEvents();
            int eventCount = events.size();

            return ScenarioExecutionResult.StepResult.of(
                    step.id(), taskId, finalState, requestContent, events, eventCount);

        } catch (Exception e) {
            return ScenarioExecutionResult.StepResult.failed(step.id(), e);
        }
    }

    /**
     * Extract plain text content from a Message for result recording.
     */
    private static String extractTextContent(Message message) {
        try {
            // Message content is typically a list of content parts
            // Best-effort extraction for logging/recording
            return message.toString();
        } catch (Exception e) {
            return "<message>";
        }
    }
}

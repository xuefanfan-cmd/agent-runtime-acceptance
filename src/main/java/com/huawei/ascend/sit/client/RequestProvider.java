package com.huawei.ascend.sit.client;

import org.a2aproject.sdk.spec.Message;

/**
 * Strategy interface for constructing A2A request messages during scenario execution.
 *
 * <p>Test code implements this interface to provide <b>all requests</b> for every step
 * in the scenario. This enables dynamic request construction based on:</p>
 * <ul>
 *   <li>Previous step responses (via {@link StepContext#lastResult()})</li>
 *   <li>Third-party API responses (injected into context by the provider itself)</li>
 *   <li>Step-specific logic (via {@link StepContext#currentStepId()})</li>
 * </ul>
 *
 * <h3>Usage in test code:</h3>
 * <pre>{@code
 * RequestProvider provider = (stepId, ctx) -> {
 *     return switch (stepId) {
 *         case "on_transfer_request" -> A2A.toUserMessage("我要进行快速转账");
 *         case "on_payee_input" -> {
 *             // Construct request based on previous response
 *             String cardData = mockCardService.getCards();
 *             ctx.put("cardData", cardData);
 *             yield A2A.toUserMessage("选择收款卡: " + cardData);
 *         }
 *         default -> throw new IllegalArgumentException("Unknown step: " + stepId);
 *     };
 * };
 * }</pre>
 *
 * @see StepContext
 * @see ScenarioExecutor
 */
@FunctionalInterface
public interface RequestProvider {

    /**
     * Construct the A2A request message for the given step.
     *
     * @param stepId  the current step identifier (from YAML scenario definition)
     * @param context accumulated context including previous results and mutable state
     * @return the {@link Message} to send to the A2A agent
     */
    Message provide(String stepId, StepContext context);
}

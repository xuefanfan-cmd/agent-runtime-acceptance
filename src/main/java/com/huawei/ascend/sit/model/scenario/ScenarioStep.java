package com.huawei.ascend.sit.model.scenario;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A single step within a multi-turn interaction scenario.
 *
 * <p>Each step represents one round of interaction with the A2A agent:
 * <ol>
 *   <li>Send the request (message text) to the agent</li>
 *   <li>Collect response events via {@link com.huawei.ascend.sit.client.A2aEventCollector}</li>
 *   <li>Evaluate transitions to determine the next step</li>
 * </ol>
 *
 * <p>Steps with {@code interaction = "manual"} expect the agent to enter
 * {@code TASK_STATE_INPUT_REQUIRED}, requiring user input before continuing.</p>
 *
 * @param id              unique step identifier (e.g. "on_transfer_request")
 * @param description     human-readable step description
 * @param interaction     "manual" if step requires user input, null otherwise
 * @param uiHint          UI rendering hint for manual steps (e.g. "card-selector")
 * @param requestTemplate path to an external request template file
 * @param request         inline request text (takes precedence over requestTemplate)
 * @param actions         expected agent response action templates
 * @param transitions     conditional and default transitions to the next step
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScenarioStep(
        String id,
        String description,
        @JsonProperty("interaction") String interaction,
        @JsonProperty("ui_hint") String uiHint,
        @JsonProperty("request_template") String requestTemplate,
        @JsonProperty("request") String request,
        List<StepAction> actions,
        List<StepTransition> transitions
) {
    /**
     * Whether this step requires manual user interaction.
     */
    public boolean isManual() {
        return "manual".equalsIgnoreCase(interaction);
    }

    /**
     * Resolve the request content: inline request takes precedence,
     * then falls back to requestTemplate path.
     */
    public String resolveRequestContent() {
        if (request != null && !request.isBlank()) {
            return request;
        }
        if (requestTemplate != null && !requestTemplate.isBlank()) {
            // Delegate to template loader — return the template path for now
            return requestTemplate;
        }
        // Fallback: use description as a conversational prompt
        return description != null ? description : "";
    }
}

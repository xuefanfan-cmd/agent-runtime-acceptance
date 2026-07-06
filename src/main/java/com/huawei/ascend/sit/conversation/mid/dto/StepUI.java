package com.huawei.ascend.sit.conversation.mid.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StepUI(
        String interaction,                                            // auto | manual
        @JsonProperty("ui_hint") String uiHint,                        // card-selector | confirm-card | null
        @JsonProperty("step_id") String stepId,
        @JsonProperty("step_description") String stepDescription,
        @JsonProperty("step_index") int stepIndex,
        @JsonProperty("total_steps") int totalSteps,
        @JsonProperty("interaction_data") InteractionData interactionData) {

    /** 工作流是否已完成（中台报 total_steps==0 且 step_id 空）。 */
    public boolean isWorkflowComplete() {
        return totalSteps == 0 && (stepId == null || stepId.isBlank());
    }

    /** 该步是否需要客户端选择：manual 且（有 selection_key 或 confirm-card）。 */
    public boolean needsSelection() {
        boolean confirmCard = "confirm-card".equalsIgnoreCase(uiHint);
        boolean hasSelectionKey = interactionData != null
                && interactionData.selectionKey() != null && !interactionData.selectionKey().isBlank();
        return "manual".equalsIgnoreCase(interaction) && (hasSelectionKey || confirmCard);
    }

    /** 测试辅助：构造一个 auto 终态空 StepUI。 */
    public static StepUI deserializeAuto() {
        return new StepUI("auto", null, "", "", 0, 0, null);
    }
}

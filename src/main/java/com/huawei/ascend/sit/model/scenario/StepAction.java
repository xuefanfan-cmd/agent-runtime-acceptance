package com.huawei.ascend.sit.model.scenario;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * An expected agent response action within a step.
 *
 * <p>References a template file that defines the expected structure of
 * the agent's response event (e.g. SSE stream format).</p>
 *
 * @param template path to the action template file (e.g. "sse/transfer_01_qa.yaml")
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StepAction(
        String template
) {}

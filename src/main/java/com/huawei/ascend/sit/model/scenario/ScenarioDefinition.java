package com.huawei.ascend.sit.model.scenario;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Top-level data model for a data-driven multi-turn interaction scenario.
 *
 * <p>Loaded from YAML files under {@code testdata/scenario/}. Each scenario
 * defines a complete multi-step interaction flow with an A2A agent, including
 * the steps to execute, the context to maintain, and the transitions between steps.</p>
 *
 * <p>Example YAML structure:
 * <pre>
 * name: "转账流程"
 * version: "1.0"
 * context:
 *   amount: "0"
 * steps:
 *   - id: "step_1"
 *     request: "我要转账"
 *     transitions:
 *       - default: "step_2"
 * </pre>
 *
 * @param name        scenario display name
 * @param version     scenario version
 * @param description optional scenario description
 * @param intent      intent matching configuration (keywords)
 * @param metadata    scenario-level static metadata
 * @param context     initial global context values (mutable during execution)
 * @param steps       ordered list of interaction steps
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScenarioDefinition(
        String name,
        String version,
        String description,
        ScenarioIntent intent,
        Map<String, Object> metadata,
        Map<String, Object> context,
        List<ScenarioStep> steps
) {}

package com.huawei.ascend.sit.model.scenario;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Intent matching configuration for a scenario.
 *
 * <p>Defines the keywords or patterns that should trigger this scenario's
 * agent interaction flow.</p>
 *
 * @param keywords list of trigger keywords (e.g. "快速转账", "快速汇款")
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScenarioIntent(
        List<String> keywords
) {}

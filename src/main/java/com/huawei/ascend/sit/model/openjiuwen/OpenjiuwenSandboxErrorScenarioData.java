package com.huawei.ascend.sit.model.openjiuwen;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.huawei.ascend.sit.utils.TestDataLoader;

import java.util.List;

/**
 * Data model for OJ-10 hotel sandbox timeout / error paths (streaming).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenjiuwenSandboxErrorScenarioData(
        String timeoutPrompt,
        String errorPrompt,
        List<String> errorKeywords,
        List<String> timeoutKeywords,
        String postProbeInputText,
        String sandboxHealthUrl,
        long timeoutMs
) {

    public static OpenjiuwenSandboxErrorScenarioData loadDefault() {
        return TestDataLoader.load("openjiuwen/integration/oj-10-sandbox-error.json",
                OpenjiuwenSandboxErrorScenarioData.class);
    }
}

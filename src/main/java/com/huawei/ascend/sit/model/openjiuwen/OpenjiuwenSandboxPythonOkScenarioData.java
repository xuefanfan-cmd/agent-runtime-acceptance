package com.huawei.ascend.sit.model.openjiuwen;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.huawei.ascend.sit.utils.TestDataLoader;
import org.a2aproject.sdk.spec.TaskState;

import java.util.List;

/**
 * Data model for OJ-09 hotel sandbox Python execution (streaming).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenjiuwenSandboxPythonOkScenarioData(
        String inputText,
        String expectedTerminalState,
        List<String> expectedResponseSubstrings,
        String sandboxHealthUrl,
        long timeoutMs
) {

    public static OpenjiuwenSandboxPythonOkScenarioData loadDefault() {
        return TestDataLoader.load("component/singleagent/oj-09-sandbox-python-ok.json",
                OpenjiuwenSandboxPythonOkScenarioData.class);
    }

    public TaskState resolvedExpectedTerminalState() {
        return TaskState.valueOf(expectedTerminalState);
    }
}

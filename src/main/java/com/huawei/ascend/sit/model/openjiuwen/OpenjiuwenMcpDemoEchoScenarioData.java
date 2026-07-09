package com.huawei.ascend.sit.model.openjiuwen;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.huawei.ascend.sit.utils.TestDataLoader;
import org.a2aproject.sdk.spec.TaskState;

import java.util.List;

/**
 * Data model for OJ-08 hotel MCP {@code demo_echo} streaming scenario.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenjiuwenMcpDemoEchoScenarioData(
        String inputText,
        String expectedTerminalState,
        List<String> expectedResponseSubstrings,
        int preferredMcpPort,
        long timeoutMs
) {

    public static OpenjiuwenMcpDemoEchoScenarioData loadDefault() {
        return TestDataLoader.load("component/singleagent/oj-08-mcp-demo-echo.json",
                OpenjiuwenMcpDemoEchoScenarioData.class);
    }

    public TaskState resolvedExpectedTerminalState() {
        return TaskState.valueOf(expectedTerminalState);
    }
}

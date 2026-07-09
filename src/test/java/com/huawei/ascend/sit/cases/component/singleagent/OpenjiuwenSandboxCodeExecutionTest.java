package com.huawei.ascend.sit.cases.component.singleagent;

import com.huawei.ascend.sit.cases.support.openjiuwen.OpenjiuwenSandboxGate;
import com.huawei.ascend.sit.cases.support.openjiuwen.OpenjiuwenStackSupport;
import com.huawei.ascend.sit.cases.support.openjiuwen.OpenjiuwenStreamingSingleTurnRunner;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.model.openjiuwen.OpenjiuwenSandboxPythonOkScenarioData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OJ-09 — openjiuwen hotel sandbox Python execution (streaming).
 *
 * <p>Requires jiuwenbox at {@code SANDBOX_HOST:SANDBOX_PORT} (default {@code 127.0.0.1:8321}) and
 * {@code agent-openjiuwen-travel-hotel:0.1.0} with {@code sandbox} profile. See
 * {@code docs/cases/OJ-09-openjiuwen-sandbox-code-execution.md}.</p>
 */
@Tag("component")
@Tag("openjiuwen")
@Tag("nightly")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenjiuwenSandboxCodeExecutionTest {

    private static final Logger LOG = Logger.getLogger(OpenjiuwenSandboxCodeExecutionTest.class.getName());

    private TestConfig config;
    private OpenjiuwenSandboxPythonOkScenarioData scenario;
    private OpenjiuwenSandboxGate.SandboxEndpoint sandbox;
    private SutStack stack;

    @BeforeAll
    void startSandboxProbeAndHotel() throws Exception {
        config = TestConfig.load();
        scenario = OpenjiuwenSandboxPythonOkScenarioData.loadDefault();
        sandbox = OpenjiuwenSandboxGate.resolveSandboxEndpoint(scenario.sandboxHealthUrl());

        OpenjiuwenSandboxGate.assertSandboxHealthy(sandbox.healthUrl());
        LOG.info("OJ-09 jiuwenbox ready at " + sandbox.healthUrl());

        stack = OpenjiuwenStackSupport.hotelSandboxStreaming(config, sandbox.host(), sandbox.port()).start();
        OpenjiuwenSandboxGate.assertHotelUsesSandboxProfile(stack);
        LOG.info("OJ-09 hotel sandbox profile ready");
    }

    @AfterAll
    void tearDown() {
        if (stack != null) {
            stack.close();
        }
    }

    @Test
    @DisplayName("OJ-09: hotel 沙箱 Python print('ok') — 流式 COMPLETED 且输出含 ok")
    void oj09_sandboxPythonOk_streamingCompletedWithStdout() throws Exception {
        OpenjiuwenStreamingSingleTurnRunner.Result result = OpenjiuwenStreamingSingleTurnRunner.run(
                stack.client(OpenjiuwenStackSupport.HOTEL),
                scenario.inputText(),
                scenario.timeoutMs(),
                "OJ-09");

        assertThat(result.terminalState())
                .as("OJ-09.A terminal state")
                .isEqualTo(scenario.resolvedExpectedTerminalState());
        assertThat(result.responseText()).as("OJ-09 response text").isNotBlank();
        LOG.info("OJ-09 reply (truncated): "
                + (result.responseText().length() > 200
                ? result.responseText().substring(0, 200) + "..."
                : result.responseText()));

        for (String expected : scenario.expectedResponseSubstrings()) {
            assertThat(result.responseText())
                    .as("OJ-09.B sandbox stdout substring '%s'", expected)
                    .containsIgnoringCase(expected);
        }

        OpenjiuwenSandboxGate.assertSandboxExecSucceeded(stack, "ok");
    }
}

package com.huawei.ascend.sit.cases.openjiuwen.integration;

import com.huawei.ascend.sit.cases.openjiuwen.OpenjiuwenSandboxGate;
import com.huawei.ascend.sit.cases.openjiuwen.OpenjiuwenStackSupport;
import com.huawei.ascend.sit.cases.openjiuwen.OpenjiuwenStreamingSingleTurnRunner;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.model.openjiuwen.OpenjiuwenSandboxErrorScenarioData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.logging.Logger;

/**
 * OJ-10 — openjiuwen hotel sandbox timeout / illegal command (P2, optional).
 *
 * <p>See {@code docs/cases/OJ-10-openjiuwen-sandbox-timeout-error.md}.</p>
 */
@Tag("integration")
@Tag("openjiuwen")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenjiuwenSandboxErrorTest {

    private static final Logger LOG = Logger.getLogger(OpenjiuwenSandboxErrorTest.class.getName());

    private TestConfig config;
    private OpenjiuwenSandboxErrorScenarioData scenario;
    private OpenjiuwenSandboxGate.SandboxEndpoint sandbox;
    private SutStack stack;

    @BeforeAll
    void startSandboxProbeAndHotel() throws Exception {
        config = TestConfig.load();
        scenario = OpenjiuwenSandboxErrorScenarioData.loadDefault();
        sandbox = OpenjiuwenSandboxGate.resolveSandboxEndpoint(scenario.sandboxHealthUrl());

        OpenjiuwenSandboxGate.assertSandboxHealthy(sandbox.healthUrl());
        LOG.info("OJ-10 jiuwenbox ready at " + sandbox.healthUrl());

        stack = OpenjiuwenStackSupport.hotelSandboxStreaming(config, sandbox.host(), sandbox.port()).start();
        OpenjiuwenSandboxGate.assertHotelUsesSandboxProfile(stack);
        LOG.info("OJ-10 hotel sandbox profile ready");
    }

    @AfterAll
    void tearDown() {
        if (stack != null) {
            stack.close();
        }
    }

    @Test
    @DisplayName("OJ-10a: 沙箱长耗时 — 超时/失败可见且 hotel 进程存活")
    void oj10a_sandboxTimeout_failureVisibleAndProcessAlive() throws Exception {
        OpenjiuwenStreamingSingleTurnRunner.Result result = OpenjiuwenStreamingSingleTurnRunner.run(
                stack.client(OpenjiuwenStackSupport.HOTEL),
                scenario.timeoutPrompt(),
                scenario.timeoutMs(),
                "OJ-10.T");

        LOG.info("OJ-10.T terminal=" + result.terminalState() + " text="
                + (result.responseText().length() > 200
                ? result.responseText().substring(0, 200) + "..."
                : result.responseText()));

        OpenjiuwenSandboxGate.assertTimeoutOrFailureVisible(result, scenario.timeoutKeywords(), "OJ-10.T");
        OpenjiuwenSandboxGate.assertHotelResponds(stack);
    }

    @Test
    @DisplayName("OJ-10b: 沙箱非法命令 — 错误可见且 hotel 进程存活")
    void oj10b_sandboxIllegalCommand_errorVisibleAndProcessAlive() throws Exception {
        OpenjiuwenStreamingSingleTurnRunner.Result result = OpenjiuwenStreamingSingleTurnRunner.run(
                stack.client(OpenjiuwenStackSupport.HOTEL),
                scenario.errorPrompt(),
                scenario.timeoutMs(),
                "OJ-10.E");

        LOG.info("OJ-10.E terminal=" + result.terminalState() + " text="
                + (result.responseText().length() > 200
                ? result.responseText().substring(0, 200) + "..."
                : result.responseText()));

        OpenjiuwenSandboxGate.assertErrorVisible(result, scenario.errorKeywords(), "OJ-10.E");
        OpenjiuwenSandboxGate.assertHotelResponds(stack);

        // Lightweight follow-up send proves the agent still accepts queries after the error path.
        OpenjiuwenStreamingSingleTurnRunner.run(
                stack.client(OpenjiuwenStackSupport.HOTEL),
                scenario.postProbeInputText(),
                scenario.timeoutMs(),
                "OJ-10.P-probe");
    }
}

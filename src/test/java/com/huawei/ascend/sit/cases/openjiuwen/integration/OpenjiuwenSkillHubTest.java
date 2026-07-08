package com.huawei.ascend.sit.cases.openjiuwen.integration;

import com.huawei.ascend.sit.cases.openjiuwen.OpenjiuwenSkillGate;
import com.huawei.ascend.sit.cases.openjiuwen.OpenjiuwenStackSupport;
import com.huawei.ascend.sit.cases.openjiuwen.OpenjiuwenStreamingSingleTurnRunner;
import com.huawei.ascend.sit.cases.openjiuwen.OpenjiuwenTextAssertions;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.model.openjiuwen.OpenjiuwenSkillHubScenarioData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OJ-11 — openjiuwen hotel skill-hub {@code hotel_ranking} dynamic trigger (streaming).
 *
 * <p>Requires {@code agent-openjiuwen-travel-hotel:0.1.0} with {@code skill} profile only
 * (do not combine with {@code sandbox} or {@code mcp}). See
 * {@code docs/cases/OJ-11-openjiuwen-skill-hub-dynamic-trigger.md}.</p>
 */
@Tag("integration")
@Tag("openjiuwen")
@Tag("nightly")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenjiuwenSkillHubTest {

    private static final Logger LOG = Logger.getLogger(OpenjiuwenSkillHubTest.class.getName());

    private TestConfig config;
    private OpenjiuwenSkillHubScenarioData scenario;
    private SutStack stack;

    @BeforeAll
    void startHotelWithSkillProfile() throws Exception {
        config = TestConfig.load();
        scenario = OpenjiuwenSkillHubScenarioData.loadDefault();

        stack = OpenjiuwenStackSupport.hotelSkillStreaming(config).start();
        OpenjiuwenSkillGate.assertHotelUsesSkillProfile(stack);
        LOG.info("OJ-11 hotel skill profile ready");
    }

    @AfterAll
    void tearDown() {
        if (stack != null) {
            stack.close();
        }
    }

    @Test
    @DisplayName("OJ-11: hotel skill-hub hotel_ranking — 流式 COMPLETED 且体现 skill 工作流输出")
    void oj11_skillHotelRanking_streamingCompletedWithSkillWorkflow() throws Exception {
        OpenjiuwenStreamingSingleTurnRunner.Result result = OpenjiuwenStreamingSingleTurnRunner.run(
                stack.client(OpenjiuwenStackSupport.HOTEL),
                scenario.inputText(),
                scenario.timeoutMs(),
                "OJ-11");

        assertThat(result.terminalState())
                .as("OJ-11.A terminal state")
                .isEqualTo(scenario.resolvedExpectedTerminalState());
        assertThat(result.responseText()).as("OJ-11 response text").isNotBlank();
        LOG.info("OJ-11 reply (truncated): "
                + (result.responseText().length() > 300
                ? result.responseText().substring(0, 300) + "..."
                : result.responseText()));

        OpenjiuwenTextAssertions.assertSkillWorkflowTemplate(result.responseText(), scenario, "OJ-11.B");
        OpenjiuwenTextAssertions.assertSkillBusinessContext(result.responseText(), scenario, "OJ-11.C");
        if (scenario.mustNotBeOnlyCapabilityBlurb()) {
            OpenjiuwenTextAssertions.assertNotOnlyCapabilityBlurb(result.responseText(), "OJ-11.D");
        }

        OpenjiuwenSkillGate.assertSkillWorkflowToolsSucceeded(stack);
        OpenjiuwenSkillGate.assertHotelResponds(stack);
    }
}

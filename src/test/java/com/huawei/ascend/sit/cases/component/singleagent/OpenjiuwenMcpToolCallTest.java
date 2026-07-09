package com.huawei.ascend.sit.cases.component.singleagent;

import com.huawei.ascend.sit.cases.support.openjiuwen.MockMcpServerProcess;
import com.huawei.ascend.sit.cases.support.openjiuwen.OpenjiuwenMcpToolCallGate;
import com.huawei.ascend.sit.cases.support.openjiuwen.OpenjiuwenStackSupport;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.A2aStreamErrors;
import com.huawei.ascend.sit.client.TaskTextExtractor;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.model.openjiuwen.OpenjiuwenMcpDemoEchoScenarioData;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * OJ-08 — openjiuwen hotel MCP {@code demo_echo} end-to-end (streaming).
 *
 * <p>Mock MCP must start before hotel ({@code mcp} profile). See
 * {@code docs/cases/OJ-08-openjiuwen-mcp-tool-call.md}.</p>
 *
 * <p>Requires {@code agent-openjiuwen-travel-hotel:0.1.0} and
 * {@code travel-openjiuwen-test-support-0.1.0.jar} in {@code ~/.m2} (hotel jar must include
 * {@code application-mcp.yml}; rebuild with
 * {@code mvn -f examples/travel-openjiuwen/pom.xml -pl :agent-openjiuwen-travel-hotel -am install -DskipTests})
 * and {@code LLM_*} env vars for tool-call capable model.</p>
 */
@Tag("component")
@Tag("openjiuwen")
@Tag("nightly")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenjiuwenMcpToolCallTest {

    private static final Logger LOG = Logger.getLogger(OpenjiuwenMcpToolCallTest.class.getName());

    private TestConfig config;
    private OpenjiuwenMcpDemoEchoScenarioData scenario;
    private MockMcpServerProcess mockMcp;
    private SutStack stack;

    @BeforeAll
    void startMockMcpAndHotel() throws Exception {
        config = TestConfig.load();
        scenario = OpenjiuwenMcpDemoEchoScenarioData.loadDefault();

        mockMcp = MockMcpServerProcess.start(config, scenario.preferredMcpPort());
        OpenjiuwenMcpToolCallGate.assertDemoEchoToolAvailable(mockMcp.host(), mockMcp.port());
        LOG.info("OJ-08 Mock MCP ready at http://" + mockMcp.host() + ":" + mockMcp.port() + "/mcp");

        stack = OpenjiuwenStackSupport.hotelMcpStreaming(
                config, mockMcp.host(), String.valueOf(mockMcp.port())).start();
        OpenjiuwenMcpToolCallGate.assertHotelUsesMcpProfile(stack);
        LOG.info("OJ-08 hotel mcp profile ready");
    }

    @AfterAll
    void tearDown() {
        if (stack != null) {
            stack.close();
        }
        if (mockMcp != null) {
            mockMcp.close();
        }
    }

    @Test
    @DisplayName("OJ-08: hotel MCP demo_echo — 流式 COMPLETED 且回显 demo_echo:hello")
    void oj08_mcpDemoEcho_streamingCompletedWithEcho() {
        A2aServiceClient a2a = stack.client(OpenjiuwenStackSupport.HOTEL);
        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> streamError = new AtomicReference<>();

        a2a.sendMessage(
                A2A.toUserMessage(scenario.inputText()),
                List.of(collector.createConsumer()),
                error -> streamError.set(error));

        TaskState terminalState = collector.awaitTerminalState(scenario.timeoutMs());
        Throwable failure = streamError.get();
        if (failure != null && !A2aStreamErrors.isBenignShutdown(failure)) {
            fail("OJ-08 message/stream failed", failure);
        }

        assertThat(collector.eventCount()).as("OJ-08 stream events").isGreaterThan(0);
        assertThat(terminalState)
                .as("OJ-08.A terminal state")
                .isEqualTo(scenario.resolvedExpectedTerminalState());

        String taskId = collector.findFirstTaskId();
        assertThat(taskId).as("OJ-08 taskId").isNotBlank();

        Task task = a2a.getTask(taskId);
        String responseText = TaskTextExtractor.textOf(task);
        assertThat(responseText).as("OJ-08 response text").isNotBlank();
        LOG.info("OJ-08 reply (truncated): "
                + (responseText.length() > 200 ? responseText.substring(0, 200) + "..." : responseText));

        for (String expected : scenario.expectedResponseSubstrings()) {
            assertThat(responseText)
                    .as("OJ-08.B MCP echo substring '%s'", expected)
                    .contains(expected);
        }
    }
}

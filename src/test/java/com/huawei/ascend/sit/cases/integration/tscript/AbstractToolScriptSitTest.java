package com.huawei.ascend.sit.cases.integration.tscript;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.junit.jupiter.api.BeforeEach;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

abstract class AbstractToolScriptSitTest extends BaseManagedStackTest {
    static final String SUT = "tool-scripts-edpa";
    final ToolScriptWireClient wire = new ToolScriptWireClient();

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return ToolScriptFixtureSupport.stackBuilder(config, sutName(), scenarioName(), providerMode());
    }

    protected String sutName() {
        return SUT;
    }

    protected String scenarioName() {
        return "default";
    }

    protected String providerMode() {
        return "normal";
    }

    @BeforeEach
    void resetProbeCounters() throws Exception {
        wire.clearCounts(stack.baseUrl(sutName()));
    }

    ToolScriptWireClient.Run invoke(String tool, String mode, String bizType) throws Exception {
        String canary = newCanary();
        StringBuilder prompt = new StringBuilder("TSCRIPT tool=").append(tool)
                .append(" mode=").append(mode);
        if (bizType != null) {
            prompt.append(" biz_type=").append(bizType);
        }
        prompt.append(" canary=").append(canary).append('.');
        ToolScriptWireClient.Run run = wire.send(stack.baseUrl(sutName()), "ctx-" + canary, prompt.toString());
        assertThat(run.statusCode()).as(run.raw()).isEqualTo(200);
        return run;
    }

    /** Sends a free-form prompt without the standard TSCRIPT grammar. */
    ToolScriptWireClient.Run send(String prompt) throws Exception {
        return send("ctx-" + newCanary(), prompt);
    }

    /** Sends a prompt in a caller-chosen conversation context, e.g. to chain two requests of one scenario. */
    ToolScriptWireClient.Run send(String contextId, String prompt) throws Exception {
        ToolScriptWireClient.Run run = wire.send(stack.baseUrl(sutName()), contextId, prompt);
        assertThat(run.statusCode()).as(run.raw()).isEqualTo(200);
        return run;
    }

    static String newCanary() {
        return "TS" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    ToolScriptWireClient.Run invokeLegacy(String tool, String queryIntent, String queryDescription)
            throws Exception {
        String canary = newCanary();
        String prompt = "TSCRIPT call exactly tool=" + tool + " once with JSON arguments "
                + legacyArguments(tool, queryIntent, queryDescription + " " + canary)
                + ". Do not call any other tool.";
        ToolScriptWireClient.Run run = wire.send(stack.baseUrl(sutName()), "ctx-" + canary, prompt);
        assertThat(run.statusCode()).as(run.raw()).isEqualTo(200);
        return run;
    }

    void assertPair(ToolScriptWireClient.Run run, String tool, String start, String end) {
        assertContents(run.events(tool, "tool_start"), start);
        assertContents(run.events(tool, "tool_end"), end);
    }

    void assertContents(List<JsonNode> events, String expected) {
        assertThat(events).as("script events").isNotEmpty();
        assertThat(events).as("script events").allSatisfy(event ->
                assertThat(event.path("content").asText()).isEqualTo(expected));
        assertThat(events).extracting(event -> event.path("tool_call_id").asText())
                .doesNotHaveDuplicates();
    }

    Map<String, Integer> counts() throws Exception {
        return wire.counts(stack.baseUrl(sutName()));
    }

    static Path scenarioPath(String name) {
        String override = System.getProperty("tscript.fixture.root", "");
        Path root = override.isBlank()
                ? Path.of("..", "agent-solution", "common", "example", "tool-scripts-sit-demo", "scenarios")
                : Path.of(override);
        return root.resolve(name).toAbsolutePath().normalize();
    }

    private static String legacyArguments(String tool, String intent, String description) {
        if ("call_mcp".equals(tool)) {
            return "{\"script_command\":\"noop\",\"script_params\":{},\"query_intent\":\""
                    + intent + "\",\"query_description\":\"" + description + "\"}";
        }
        if ("call_subagent".equals(tool)) {
            return "{\"agent_name\":\"missing-sit-agent\",\"query_intent\":\"" + intent
                    + "\",\"query_description\":\"" + description + "\"}";
        }
        return "{\"query_intent\":\"" + intent + "\",\"query_description\":\""
                + description + "\"}";
    }
}

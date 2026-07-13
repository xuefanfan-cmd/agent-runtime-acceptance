package com.huawei.ascend.sit.cases.component.singleagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.client.TaskTextExtractor;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.utils.JsonUtils;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OJ-08 — openjiuwen hotel MCP {@code demo_echo} end-to-end (streaming).
 *
 * <p>Uses {@link SutStack} + {@link InteractionFlow} directly (no OJ StackSupport / main
 * ScenarioData). Mock MCP must start before hotel ({@code mcp} profile). Terminal state
 * hard-requires {@code COMPLETED}; reply must contain {@code demo_echo:hello}.</p>
 *
 * <p>See {@code docs/cases/reactagent/OJ-08-openjiuwen-mcp-tool-call.md}.</p>
 */
@Tag("component")
@Tag("openjiuwen")
@Tag("nightly")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenjiuwenMcpToolCallTest {

    private static final Logger LOG = Logger.getLogger(OpenjiuwenMcpToolCallTest.class.getName());

    private static final String HOTEL = "hotel";
    private static final String MOCK_MCP = "mock-mcp";
    private static final String MCP_PROFILE = "mcp";
    private static final ObjectMapper MAPPER = JsonUtils.mapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String TOOLS_LIST_BODY =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}";

    /** Matches {@code testdata/component/singleagent/oj-08-mcp-demo-echo.json}. */
    private static final String INPUT_TEXT = "请调用 demo_echo 工具，输入 text=hello";
    private static final List<String> EXPECTED_SUBSTRINGS = List.of("demo_echo:hello");
    private static final long FLOW_TIMEOUT_MS = 120_000L;

    private TestConfig config;
    private SutStack stack;

    @BeforeAll
    void startMockMcpAndHotel() throws Exception {
        config = TestConfig.load();

        stack = SutStack.builder(config)
                .streaming(true)
                .agent(MOCK_MCP)
                .agent(HOTEL, a -> a
                        .profile(MCP_PROFILE)
                        .downstream(MOCK_MCP,
                                "openjiuwen.service.external.mcp.servers[0].server-path"))
                .start();

        String mockMcpBaseUrl = stack.baseUrl(MOCK_MCP);
        assertDemoEchoToolAvailable(mockMcpBaseUrl);
        LOG.info("OJ-08 Mock MCP ready at " + mockMcpBaseUrl);
        assertHotelUsesMcpProfile(stack);
        LOG.info("OJ-08 hotel mcp profile ready");
    }

    @AfterAll
    void tearDown() {
        if (stack != null) {
            stack.close();
        }
    }

    @Test
    @DisplayName("OJ-08: hotel MCP demo_echo — 流式 COMPLETED 且回显 demo_echo:hello")
    void oj08_mcpDemoEcho_streamingCompletedWithEcho() {
        long timeoutMs = Math.max(config.getPollTimeoutSeconds() * 1000L, FLOW_TIMEOUT_MS);

        InteractionFlow.of(stack.client(HOTEL))
                .withTimeoutMs(timeoutMs)
                .send(INPUT_TEXT)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertTask(task -> {
                        String text = TaskTextExtractor.textOf(task);
                        assertThat(text).as("OJ-08 response text").isNotBlank();
                        LOG.info("OJ-08 reply (truncated): "
                                + (text.length() > 200 ? text.substring(0, 200) + "..." : text));
                        for (String expected : EXPECTED_SUBSTRINGS) {
                            assertThat(text)
                                    .as("OJ-08.B MCP echo substring '%s'", expected)
                                    .contains(expected);
                        }
                    })
                .execute();
    }

    private static void assertDemoEchoToolAvailable(String baseUrl)
            throws IOException, InterruptedException {
        assertThat(toolsListContainsDemoEcho(baseUrl))
                .as("OJ-08.0 Mock MCP tools/list contains demo_echo at %s", baseUrl)
                .isTrue();
    }

    private static void assertHotelUsesMcpProfile(SutStack stack) throws IOException {
        Path log = hotelStdoutLog(stack);
        String content = Files.readString(log, StandardCharsets.UTF_8);
        assertThat(lastLineContaining(content, "profile is active: \"mcp\""))
                .as("OJ-08.C hotel active profile mcp (see %s)", log)
                .isNotNull();
        assertThat(lastLineContaining(content, "Registered external MCP server"))
                .as("OJ-08.C hotel MCP server registration "
                        + "(rebuild agent-openjiuwen-travel-hotel:0.1.0 if missing; see %s)", log)
                .isNotNull();
        assertThat(lastLineContaining(content, "Bound MCP server to hotel agent ability manager"))
                .as("OJ-08.C hotel MCP server binding (see %s)", log)
                .isNotNull();
    }

    private static boolean toolsListContainsDemoEcho(String baseUrl)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(TOOLS_LIST_BODY))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return false;
        }
        JsonNode tools = MAPPER.readTree(response.body()).path("result").path("tools");
        if (!tools.isArray()) {
            return false;
        }
        for (JsonNode tool : tools) {
            if ("demo_echo".equals(tool.path("name").asText())) {
                return true;
            }
        }
        return false;
    }

    private static String lastLineContaining(String logContent, String needle) {
        String last = null;
        for (String line : logContent.split("\n")) {
            if (line.contains(needle)) {
                last = line;
            }
        }
        return last;
    }

    private static Path hotelStdoutLog(SutStack stack) {
        var instance = stack.managedInstance(HOTEL);
        assertThat(instance)
                .as("managed hotel agent for OJ-08.C log gate")
                .isNotNull()
                .isInstanceOf(ManagedSutInstance.class);
        return ((ManagedSutInstance) instance).logFile();
    }
}

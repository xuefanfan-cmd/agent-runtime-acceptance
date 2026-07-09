package com.huawei.ascend.sit.cases.support.openjiuwen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.utils.JsonUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hard gates for OJ-08 Mock MCP + hotel {@code mcp} profile tests.
 */
public final class OpenjiuwenMcpToolCallGate {

    private static final ObjectMapper MAPPER = JsonUtils.mapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String TOOLS_LIST_BODY =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}";

    private OpenjiuwenMcpToolCallGate() {
    }

    /** OJ-08.0 — Mock MCP exposes {@code demo_echo} before hotel starts. */
    public static void assertDemoEchoToolAvailable(String host, int port) throws IOException, InterruptedException {
        assertThat(toolsListContainsDemoEcho(host, port))
                .as("OJ-08.0 Mock MCP tools/list contains demo_echo at http://%s:%d/mcp", host, port)
                .isTrue();
    }

    /** OJ-08.C — hotel started with {@code mcp} profile and registered external MCP server. */
    public static void assertHotelUsesMcpProfile(SutStack stack) throws IOException {
        Path log = hotelStdoutLog(stack);
        String content = Files.readString(log, StandardCharsets.UTF_8);
        assertThat(lastLineContaining(content, "profile is active: \"mcp\""))
                .as("OJ-08.C hotel active profile mcp (see %s)", log)
                .isNotNull();
        assertThat(lastLineContaining(content, "Registered external MCP server"))
                .as("OJ-08.C hotel MCP server registration (rebuild agent-openjiuwen-travel-hotel:0.1.0 if missing; see %s)", log)
                .isNotNull();
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

    static boolean toolsListContainsDemoEcho(String host, int port) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + host + ":" + port + "/mcp"))
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

    private static Path hotelStdoutLog(SutStack stack) {
        var instance = stack.managedInstance(OpenjiuwenStackSupport.HOTEL);
        assertThat(instance)
                .as("managed hotel agent for OJ-08.C log gate")
                .isNotNull()
                .isInstanceOf(ManagedSutInstance.class);
        return ((ManagedSutInstance) instance).logFile();
    }
}

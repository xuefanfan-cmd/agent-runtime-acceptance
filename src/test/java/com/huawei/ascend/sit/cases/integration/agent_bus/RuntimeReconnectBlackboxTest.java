package com.huawei.ascend.sit.cases.integration.agent_bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.ascend.sit.fixtures.reconnect.ReActReconnectFixture;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runtime-facing reconnect contract through the public A2A endpoint of a managed formal SUT.
 */
@Feature("FEAT-001: 标准化智能体服务入口")
@Tag("feat-001")
@Tag("integration")
@Tag("runtime")
@Tag("blackbox")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RuntimeReconnectBlackboxTest {
    private static final Set<String> TASK_STATES = Set.of(
            "TASK_STATE_SUBMITTED",
            "TASK_STATE_WORKING",
            "TASK_STATE_INPUT_REQUIRED",
            "TASK_STATE_COMPLETED",
            "TASK_STATE_FAILED",
            "TASK_STATE_CANCELED",
            "TASK_STATE_REJECTED",
            "TASK_STATE_AUTH_REQUIRED");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private ReActReconnectFixture fixture;
    private String runtimeUrl;

    @BeforeAll
    void startRuntime() {
        fixture = ReActReconnectFixture.runtimeDirect();
        runtimeUrl = fixture.publicUrl();
    }

    @AfterAll
    void stopRuntime() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    @Story("F001-R06: 未知 taskId 返回 TaskNotFound")
    @DisplayName("Feat-001 Runtime 对未知 taskId 返回协议错误且不创建任务")
    void unknownTaskIdReturnsProtocolError() throws Exception {
        String taskId = "runtime-never-owned-" + UUID.randomUUID();
        HttpResponse<String> response = post(operation("GetTask", taskId), "application/json");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode root = AgentBusExternalFixture.JSON.readTree(response.body());
        assertThat(root.path("error").isObject()).as(response.body()).isTrue();
        assertThat(root.at("/error/code").asInt()).as(response.body()).isEqualTo(-32001);
        assertThat(response.body()).doesNotContain("TASK_STATE_WORKING", "TASK_STATE_COMPLETED");
    }

    @Test
    @Story("F001-R01/R02/R05: 断流后原 Task 继续并可重订阅")
    @DisplayName("Feat-001 Runtime 断开 SSE 后原 taskId 可查询并重订阅")
    void disconnectLeavesOriginalTaskQueryableAndSubscribable() throws Exception {
        Assumptions.assumeTrue(
                ReActReconnectFixture.hasLlmCredentials(),
                "blocked/not-run: Runtime reconnect positive path requires LLM_API_KEY");
        String request = AgentBusExternalFixture.create(
                AgentBusExternalFixture.SOURCE_AGENT,
                "reconnect runtime acceptance " + UUID.randomUUID(), true);
        String taskId;
        HttpRequest create = request(runtimeUrl, request, "text/event-stream");
        HttpResponse<InputStream> created = http.send(create, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(created.statusCode()).isEqualTo(200);
        assertThat(contentType(created)).containsIgnoringCase("text/event-stream");
        try (InputStream input = created.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            taskId = readFirstTaskId(reader);
        }
        assertThat(taskId).as("task id from initial stream").isNotBlank();

        HttpResponse<String> snapshot = post(operation("GetTask", taskId), "application/json");
        TaskSnapshot task = requireTaskSnapshot(snapshot, taskId);
        assertThat(task.terminal()).as("task must remain active before SubscribeToTask").isFalse();

        HttpResponse<String> subscription = post(operation("SubscribeToTask", taskId),
                "text/event-stream");
        assertThat(subscription.statusCode()).as(subscription.body()).isEqualTo(200);
        JsonNode subscriptionRoot = jsonOrSsePayload(subscription.body());
        if (subscriptionRoot.path("error").isObject()) {
            assertThat(subscriptionRoot.at("/error/code").asInt())
                    .as(subscription.body()).isEqualTo(-32004);
            requireTaskSnapshot(post(operation("GetTask", taskId), "application/json"), taskId);
        } else {
            assertThat(contentType(subscription)).containsIgnoringCase("text/event-stream");
            assertThat(taskIdOf(subscriptionRoot.path("result")))
                    .as(subscription.body()).isEqualTo(taskId);
        }
    }

    private HttpResponse<String> post(String body, String accept) throws Exception {
        return http.send(request(runtimeUrl, body, accept),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpRequest request(String runtimeUrl, String body, String accept) {
        return HttpRequest.newBuilder(URI.create(runtimeUrl + "/a2a"))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static String operation(String method, String taskId) throws Exception {
        var request = AgentBusExternalFixture.JSON.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", UUID.randomUUID().toString())
                .put("method", method);
        request.putObject("params").put("id", taskId);
        return request.toString();
    }

    private static String readFirstTaskId(BufferedReader reader) throws Exception {
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String payload = line.substring("data:".length()).strip();
            if (payload.isEmpty()) {
                continue;
            }
            JsonNode root = AgentBusExternalFixture.JSON.readTree(payload);
            String taskId = taskIdOf(root.path("result"));
            if (!taskId.isBlank()) {
                return taskId;
            }
        }
        return "";
    }

    private static TaskSnapshot requireTaskSnapshot(
            HttpResponse<String> response, String expectedTaskId) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode root = AgentBusExternalFixture.JSON.readTree(response.body());
        assertThat(root.path("error").isMissingNode()).as(response.body()).isTrue();
        JsonNode result = root.path("result");
        assertThat(result.isObject()).as(response.body()).isTrue();
        assertThat(taskIdOf(result)).as(response.body()).isEqualTo(expectedTaskId);

        JsonNode task = result.path("task").isObject() ? result.path("task") : result;
        String state = task.path("status").path("state").asText();
        assertThat(TASK_STATES).as(response.body()).contains(state);
        return new TaskSnapshot(state);
    }

    private static JsonNode jsonOrSsePayload(String body) throws Exception {
        for (String line : body.lines().toList()) {
            String candidate = line.startsWith("data:")
                    ? line.substring("data:".length()).strip()
                    : line.strip();
            if (candidate.startsWith("{")) {
                return AgentBusExternalFixture.JSON.readTree(candidate);
            }
        }
        throw new AssertionError("response contains no JSON-RPC payload: " + body);
    }

    private static String taskIdOf(JsonNode result) {
        return result.path("id").asText(
                result.path("task").path("id").asText(
                        result.path("statusUpdate").path("taskId").asText()));
    }

    private static String contentType(HttpResponse<?> response) {
        return response.headers().firstValue("content-type").orElse("");
    }

    private record TaskSnapshot(String state) {
        boolean terminal() {
            return Set.of(
                    "TASK_STATE_COMPLETED",
                    "TASK_STATE_FAILED",
                    "TASK_STATE_CANCELED",
                    "TASK_STATE_REJECTED").contains(state);
        }
    }
}

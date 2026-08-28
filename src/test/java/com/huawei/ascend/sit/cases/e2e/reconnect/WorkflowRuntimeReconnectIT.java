package com.huawei.ascend.sit.cases.e2e.reconnect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.fault.FaultLink;
import com.huawei.ascend.sit.fixtures.reconnect.WorkflowReconnectFixture;
import com.openjiuwen.client.api.AgentClient;
import com.openjiuwen.client.api.ContinueInputRequest;
import com.openjiuwen.client.api.InvocationCall;
import com.openjiuwen.client.api.InvocationEvent;
import com.openjiuwen.client.api.InvocationMode;
import com.openjiuwen.client.api.InvocationRequest;
import com.openjiuwen.client.api.InvocationSnapshot;
import com.openjiuwen.client.api.TaskState;
import com.openjiuwen.client.api.calltree.DataPartSnapshot;
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Feature("FEAT-006: 客户端发起标准化智能体调用")
@Tag("feat-001")
@Tag("feat-006")
@Tag("workflow")
@Tag("e2e")
class WorkflowRuntimeReconnectIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private static final String OVER_LIMIT_EXPENSE =
            "帮我审核这笔报销：机票5000，酒店3晚每晚800共2400，客户晚餐800";

    @Test
    @Stories({
            @Story("F006-E03: Workflow INPUT_REQUIRED 断流恢复"),
            @Story("F001-E03: 原 Task 复杂快照查询与续轮")
    })
    @DisplayName("Feat-001/006 Client 断流后恢复原 Workflow INPUT_REQUIRED Task 并续轮")
    void clientReconnectsToWorkflowInputPointAndContinuesOriginalTask() throws Exception {
        Assumptions.assumeTrue(hasWorkflowLlmCredentials(),
                "blocked/not-run: Workflow reconnect E2E requires EXPENSE_REVIEW_API_KEY");
        try (WorkflowReconnectFixture environment = WorkflowReconnectFixture.runtimeDirect();
             AgentClient client = environment.client()) {
            String conversationId = "workflow-reconnect-" + UUID.randomUUID();
            InvocationCall initial = client.invoke(InvocationRequest.runtimeBuilder()
                    .conversationId(conversationId)
                    .invocationId("inv-" + UUID.randomUUID())
                    .mode(InvocationMode.STREAMING)
                    .input(OVER_LIMIT_EXPENSE)
                    .build());
            ReconnectEventProbe probe = new ReconnectEventProbe();
            initial.events().subscribe(probe);

            String taskId = initial.accepted().toCompletableFuture()
                    .get(45, TimeUnit.SECONDS).diagnosticTaskRef();
            assertThat(taskId).as("accepted diagnostic task id").isNotBlank();
            Assumptions.assumeTrue(probe.awaitWorking(45, TimeUnit.SECONDS),
                    "INCONCLUSIVE: Workflow reached no observable WORKING window");

            FaultLink link = environment.faultLink();
            link.resetPeer();
            try {
                Thread.sleep(250);
            } finally {
                link.restore();
            }

            assertThat(probe.awaitInputRequired(60, TimeUnit.SECONDS))
                    .as("Workflow should expose INPUT_REQUIRED after reconnect")
                    .isTrue();
            InvocationSnapshot queriedWaiting = client.getInvocation(initial.invocationRef())
                    .toCompletableFuture().get(30, TimeUnit.SECONDS);

            assertThat(queriedWaiting.diagnosticTaskRef()).isEqualTo(taskId);
            assertThat(queriedWaiting.state()).isEqualTo(TaskState.INPUT_REQUIRED);
            assertThat(queriedWaiting.terminal()).isFalse();
            assertThat(queriedWaiting.maybeRecovery()).isEmpty();
            assertThat(probe.events()).noneMatch(InvocationEvent.Failed.class::isInstance);
            assertThat(firstNonBlank(queriedWaiting.outputText(), queriedWaiting.message()))
                    .as("INPUT_REQUIRED snapshot should retain visible prompt/output")
                    .isNotBlank();

            InvocationCall continuation = client.continueInput(ContinueInputRequest.builder()
                    .conversationId(conversationId)
                    .relatedInvocationRef(initial.invocationRef())
                    .input("approved")
                    .build());
            InvocationSnapshot completed = continuation.completion().toCompletableFuture()
                    .get(240, TimeUnit.SECONDS);
            InvocationSnapshot queriedCompleted = client.getInvocation(initial.invocationRef())
                    .toCompletableFuture().get(30, TimeUnit.SECONDS);

            assertThat(completed.diagnosticTaskRef()).isEqualTo(taskId);
            assertThat(completed.state()).isEqualTo(TaskState.COMPLETED);
            assertThat(completed.terminal()).isTrue();
            assertThat(queriedCompleted.diagnosticTaskRef()).isEqualTo(taskId);
            assertThat(queriedCompleted.state()).isEqualTo(TaskState.COMPLETED);
            assertThat(queriedCompleted.terminal()).isTrue();
            RawTaskShape rawTask = rawTaskShape(link.listenUrl(), taskId);
            assertThat(rawTask.artifactCount())
                    .as("raw GetTask should retain terminal Workflow artifact; shape=%s", rawTask)
                    .isPositive();
            assertThat(rawTask.artifactPartShapes())
                    .as("raw GetTask terminal Workflow artifact should retain structured data")
                    .contains("data");
            assertStructuredApprovalResult(completed, "continuation completion");
            assertStructuredApprovalResult(queriedCompleted, "queried terminal snapshot");
        }
    }

    private static boolean hasWorkflowLlmCredentials() {
        String apiKey = System.getenv("EXPENSE_REVIEW_API_KEY");
        return apiKey != null && !apiKey.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static RawTaskShape rawTaskShape(String runtimeUrl, String taskId) throws Exception {
        var requestBody = JSON.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", "raw-task-shape-" + UUID.randomUUID())
                .put("method", "GetTask");
        requestBody.putObject("params").put("id", taskId);
        HttpRequest request = HttpRequest.newBuilder(URI.create(runtimeUrl + "/a2a"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .build();
        HttpResponse<String> response = HTTP.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as("raw GetTask HTTP status").isEqualTo(200);
        JsonNode root = JSON.readTree(response.body());
        assertThat(root.path("error").isMissingNode()).as("raw GetTask JSON-RPC error").isTrue();
        JsonNode task = root.path("result");
        JsonNode artifacts = task.path("artifacts");
        JsonNode history = task.path("history");
        JsonNode lastHistory = history.isArray() && !history.isEmpty()
                ? history.get(history.size() - 1) : null;
        JsonNode firstArtifact = artifacts.isArray() && !artifacts.isEmpty()
                ? artifacts.get(0) : null;
        return new RawTaskShape(
                artifacts.isArray() ? artifacts.size() : 0,
                partShapes(firstArtifact),
                hasArtifactText(firstArtifact),
                hasPayload(firstArtifact == null ? null : firstArtifact.path("metadata")),
                hasPayload(task.at("/status/message")),
                history.isArray() ? history.size() : 0,
                lastHistory == null ? "" : lastHistory.path("role").asText(),
                hasPayload(lastHistory));
    }

    private static List<String> partShapes(JsonNode artifact) {
        List<String> shapes = new ArrayList<>();
        if (artifact == null || !artifact.path("parts").isArray()) {
            return shapes;
        }
        for (JsonNode part : artifact.path("parts")) {
            List<String> keys = new ArrayList<>();
            part.fieldNames().forEachRemaining(keys::add);
            shapes.add(String.join("+", keys));
        }
        return shapes;
    }

    private static boolean hasArtifactText(JsonNode artifact) {
        if (artifact == null || !artifact.path("parts").isArray()) {
            return false;
        }
        for (JsonNode part : artifact.path("parts")) {
            if (!part.path("text").asText("").isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static void assertStructuredApprovalResult(InvocationSnapshot snapshot, String label) {
        var callTree = snapshot.maybeCallTree();
        assertThat(callTree).as("%s call tree", label).isPresent();
        List<Object> structuredResults = callTree.orElseThrow().root().artifacts().stream()
                .flatMap(artifact -> artifact.parts().stream())
                .filter(DataPartSnapshot.class::isInstance)
                .map(DataPartSnapshot.class::cast)
                .map(DataPartSnapshot::data)
                .toList();
        assertThat(structuredResults)
                .as("%s should expose the Workflow terminal DataPart", label)
                .anyMatch(WorkflowRuntimeReconnectIT::isApprovedResult);
    }

    private static boolean isApprovedResult(Object value) {
        return value instanceof Map<?, ?> map && "approved".equals(map.get("result"));
    }

    private static boolean hasPayload(JsonNode node) {
        return node != null && !node.isMissingNode() && !node.isNull() && !node.isEmpty();
    }

    private record RawTaskShape(int artifactCount, List<String> artifactPartShapes,
                                boolean artifactTextPresent, boolean artifactMetadataPresent,
                                boolean statusMessagePresent,
                                int historyCount, String lastHistoryRole,
                                boolean lastHistoryPayloadPresent) {
    }
}

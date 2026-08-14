package com.huawei.ascend.sit.cases.integration.agent_bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/** FEAT-009/010 black-box acceptance through a real external travel-mainplan process. */
@Tag("integration")
@Tag("blackbox")
@Tag("openjiuwen")
@Execution(ExecutionMode.SAME_THREAD)
class ClientToolRuntimeBlackboxTest extends BaseManagedStackTest {

    private static final String MAINPLAN = "mainplan";
    private static final String A2A_PATH = "/a2a/";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ToolCallingLlmPeer llm = ToolCallingLlmPeer.start();

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config).agent(MAINPLAN, agent -> agent
                .env("LLM_PROVIDER", "OpenAI")
                .env("LLM_API_KEY", "acceptance-local-key")
                .env("LLM_API_BASE", llm.baseUrl())
                .env("LLM_MODEL", "client-tool-blackbox")
                .env("LLM_SSL_VERIFY", "false")
                .property("openjiuwen.travel.mainplan.llm.provider", "OpenAI")
                .property("openjiuwen.travel.mainplan.llm.api-key", "acceptance-local-key")
                .property("openjiuwen.travel.mainplan.llm.api-base", llm.baseUrl())
                .property("openjiuwen.travel.mainplan.llm.model-name", "client-tool-blackbox")
                .property("openjiuwen.travel.mainplan.llm.ssl-verify", "false"));
    }

    @AfterAll
    void closeLlmPeer() {
        llm.close();
    }

    @Test
    @Feature("FEAT-009: 运行时通过响应调用客户端本地工具")
    @Tag("feat-009")
    @Story("FEAT-009.lifecycle.project-resume: client-tool 挂起、投影与恢复")
    @Tag("story-feat-009-lifecycle-project-resume")
    @DisplayName("Feat-009 同步与流式调用均挂起并恢复原 Task")
    void feat009ProjectsAndResumesClientToolAcrossSyncAndStream() throws Exception {
        for (boolean streaming : List.of(false, true)) {
            for (String outcome : List.of("OK", "USER_REJECTED")) {
                String runId = shortId();
                String toolName = "readLocalTripPolicy";
                JsonNode initial = send(initialRequest(
                        streaming, runId, List.of(tool(toolName, "Read local trip policy for a city"))));

                assertInputRequired(initial, toolName);
                String taskId = requiredTaskId(initial);
                String contextId = requiredText(initial, "contextId");
                JsonNode interrupt = requiredInterrupt(initial, toolName);
                String toolCallId = requiredText(interrupt, "toolCallId");
                assertThat(interrupt.toString()).contains("Beijing");

                JsonNode queried = send(getTaskRequest("get-" + runId, taskId));
                assertInputRequired(queried, toolName);
                assertThat(requiredTaskId(queried)).isEqualTo(taskId);

                String canary = "CLIENT_RESULT_" + outcome + "_" + runId;
                JsonNode resumed = send(resumeRequest(
                        streaming, "resume-" + runId, taskId, contextId,
                        List.of(resultPart(canary, toolCallId))));
                assertCompleted(resumed, taskId, canary);
            }
        }
    }

    @Test
    @Feature("FEAT-009: 运行时通过响应调用客户端本地工具")
    @Tag("feat-009")
    @Story("FEAT-009.resume.validation: 结果集合、关联与终态保护")
    @Tag("story-feat-009-resume-validation")
    @DisplayName("Feat-009 非法结果集合不恢复 Agent 或串扰 Task")
    void feat009RejectsInvalidResumeSetsWithoutResumingAgent() throws Exception {
        PendingTask isolated = startMultiPending("isolated-" + shortId());

        PendingTask missingTask = startMultiPending("missing-" + shortId());
        int missingCalls = llm.observationCount(missingTask.runId());
        JsonNode missing = send(resumeRequest(false, "resume-" + missingTask.runId(),
                missingTask.taskId(), missingTask.contextId(),
                List.of(resultPart("CLIENT_RESULT_ONE_" + missingTask.runId(),
                        missingTask.pending().get("readLocalPolicyOne")))));
        assertInvalidContinuationOutcome(missing, missingTask.taskId());
        assertThat(llm.observationCount(missingTask.runId())).isEqualTo(missingCalls);
        assertTaskStillWaiting(isolated.taskId());

        PendingTask unknownTask = startMultiPending("unknown-" + shortId());
        int unknownCalls = llm.observationCount(unknownTask.runId());
        JsonNode unknown = send(resumeRequest(false, "resume-" + unknownTask.runId(),
                unknownTask.taskId(), unknownTask.contextId(), List.of(
                        resultPart("CLIENT_RESULT_ONE_" + unknownTask.runId(),
                                unknownTask.pending().get("readLocalPolicyOne")),
                        resultPart("CLIENT_RESULT_TWO_" + unknownTask.runId(),
                                "unknown-call-" + unknownTask.runId()))));
        assertInvalidContinuationOutcome(unknown, unknownTask.taskId());
        assertThat(llm.observationCount(unknownTask.runId())).isEqualTo(unknownCalls);
        assertTaskStillWaiting(isolated.taskId());

        PendingTask duplicateTask = startMultiPending("duplicate-" + shortId());
        int duplicateCalls = llm.observationCount(duplicateTask.runId());
        String repeatedId = duplicateTask.pending().get("readLocalPolicyOne");
        JsonNode duplicate = send(resumeRequest(false, "resume-" + duplicateTask.runId(),
                duplicateTask.taskId(), duplicateTask.contextId(), List.of(
                        resultPart("CLIENT_RESULT_ONE_" + duplicateTask.runId(), repeatedId),
                        resultPart("CLIENT_RESULT_TWO_" + duplicateTask.runId(), repeatedId))));
        assertInvalidContinuationOutcome(duplicate, duplicateTask.taskId());
        assertThat(llm.observationCount(duplicateTask.runId())).isEqualTo(duplicateCalls);
        assertTaskStillWaiting(isolated.taskId());

        PendingTask completeTask = startMultiPending("complete-" + shortId());
        String runId = completeTask.runId();
        JsonNode complete = send(resumeRequest(false, "resume-" + runId,
                completeTask.taskId(), completeTask.contextId(),
                List.of(
                        resultPart("CLIENT_RESULT_ONE_" + runId,
                                completeTask.pending().get("readLocalPolicyOne")),
                        resultPart("CLIENT_RESULT_TWO_" + runId,
                                completeTask.pending().get("readLocalPolicyTwo")))));
        assertCompleted(complete, completeTask.taskId(), "CLIENT_RESULT_ONE_" + runId);
        assertThat(allText(complete)).contains("CLIENT_RESULT_TWO_" + runId);

        int completedCalls = llm.observationCount(runId);
        JsonNode replay = send(resumeRequest(false, "replay-" + runId,
                completeTask.taskId(), completeTask.contextId(),
                List.of(
                        resultPart("CLIENT_RESULT_ONE_" + runId,
                                completeTask.pending().get("readLocalPolicyOne")),
                        resultPart("CLIENT_RESULT_TWO_" + runId,
                                completeTask.pending().get("readLocalPolicyTwo")))));
        assertThat(hasError(replay) || containsState(replay, "COMPLETED"))
                .as("terminal continuation is rejected or remains the same terminal Task: %s", replay)
                .isTrue();
        assertThat(llm.observationCount(runId)).isEqualTo(completedCalls);

        JsonNode missingContext = send(resumeRequest(false, "missing-context-" + runId,
                "unknown-task-" + runId, completeTask.contextId(),
                List.of(resultPart("CLIENT_RESULT_ORPHAN_" + runId,
                        completeTask.pending().get("readLocalPolicyOne")))));
        assertThat(hasError(missingContext)).as(missingContext.toPrettyString()).isTrue();
    }

    private PendingTask startMultiPending(String runId) throws Exception {
        JsonNode initial = send(initialRequest(false, runId, List.of(
                tool("readLocalPolicyOne", "Read the first local policy"),
                tool("readLocalPolicyTwo", "Read the second local policy"))));
        assertInputRequired(initial, "readLocalPolicyOne");
        assertInputRequired(initial, "readLocalPolicyTwo");
        Map<String, String> pending = pendingCalls(initial);
        assertThat(pending).containsKeys("readLocalPolicyOne", "readLocalPolicyTwo");
        return new PendingTask(runId, requiredTaskId(initial), requiredText(initial, "contextId"), pending);
    }

    @Test
    @Feature("FEAT-010: 任务级动态工具可见性与调用移交")
    @Tag("feat-010")
    @Story("FEAT-010.visibility.handoff-isolation: 动态可见、移交与任务隔离")
    @Tag("story-feat-010-visibility-handoff-isolation")
    @DisplayName("Feat-010 ToolView 仅影响当前 Task 且客户端调用只被移交")
    void feat010ScopesToolViewsAndHandsOffClientCalls() throws Exception {
        llm.clearObservations();

        String policyRun = "policy-" + shortId();
        JsonNode policy = send(initialRequest(false, policyRun,
                List.of(tool("readLocalTripPolicy", "Read local policy"))));
        assertInputRequired(policy, "readLocalTripPolicy");
        assertObservedTools(policyRun, Set.of("readLocalTripPolicy"), Set.of("readLocalCalendar"));
        assertExistingServerToolsRemain(policyRun);
        JsonNode policyInterrupt = requiredInterrupt(policy, "readLocalTripPolicy");
        String policyToolCallId = requiredText(policyInterrupt, "toolCallId");
        assertThat(policyToolCallId).isNotBlank();
        assertThat(policyInterrupt.toString()).contains("Beijing");
        String policyResult = "CLIENT_RESULT_POLICY_" + policyRun;
        JsonNode resumedPolicy = send(resumeRequest(false, "resume-" + policyRun,
                requiredTaskId(policy), requiredText(policy, "contextId"),
                List.of(resultPart(policyResult, policyToolCallId))));
        assertCompleted(resumedPolicy, requiredTaskId(policy), policyResult);

        String noViewRun = "no-view-" + shortId();
        JsonNode noView = send(initialRequest(false, noViewRun, List.of()));
        assertCompleted(noView, requiredTaskId(noView), "NO_CLIENT_TOOL_" + noViewRun);
        assertObservedTools(noViewRun, Set.of(), Set.of("readLocalTripPolicy", "readLocalCalendar"));
        assertExistingServerToolsRemain(noViewRun);

        String calendarRun = "calendar-" + shortId();
        JsonNode calendar = send(initialRequest(false, calendarRun,
                List.of(tool("readLocalCalendar", "Read a local calendar date"))));
        assertInputRequired(calendar, "readLocalCalendar");
        assertObservedTools(calendarRun, Set.of("readLocalCalendar"), Set.of("readLocalTripPolicy"));
        assertExistingServerToolsRemain(calendarRun);
    }

    private void assertTaskStillWaiting(String taskId) throws Exception {
        JsonNode task = send(getTaskRequest("verify-" + shortId(), taskId));
        assertThat(containsState(task, "INPUT_REQUIRED")).as(task.toPrettyString()).isTrue();
    }

    private void assertObservedTools(String runId, Set<String> included, Set<String> excluded) {
        ToolCallingLlmPeer.Observation observation = llm.observationFor(runId);
        assertThat(observation).as("LLM observation for " + runId).isNotNull();
        assertThat(observation.toolNames()).containsAll(included).doesNotContainAnyElementsOf(excluded);
        for (String clientTool : included) {
            JsonNode definition = observation.toolDefinitions().get(clientTool);
            assertThat(definition).as(clientTool + " definition").isNotNull();
            assertThat(definition.path("description").asText()).isNotBlank();
            assertThat(definition.path("parameters").path("type").asText()).isEqualTo("object");
        }
    }

    private void assertExistingServerToolsRemain(String runId) {
        assertThat(llm.observationFor(runId).toolNames())
                .as("existing server-side tools remain visible")
                .contains("request_user_input");
    }

    private JsonNode send(String body) throws Exception {
        boolean streaming = body.contains("SendStreamingMessage");
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(stack.baseUrl(MAINPLAN) + A2A_PATH))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", streaming ? "text/event-stream" : "application/json");
        HttpResponse<String> response = http.send(
                builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return parseResponse(response.body(), streaming);
    }

    private static JsonNode parseResponse(String body, boolean streaming) throws Exception {
        if (!streaming) {
            return JSON.readTree(body);
        }
        ArrayNode events = JSON.createArrayNode();
        for (String line : body.lines().toList()) {
            if (line.startsWith("data:")) {
                String data = line.substring(5).trim();
                if (!data.isBlank() && !"[DONE]".equals(data)) {
                    events.add(JSON.readTree(data));
                }
            }
        }
        assertThat(events).as("SSE events\n" + body).isNotEmpty();
        return events;
    }

    private static String initialRequest(boolean streaming, String runId, List<ObjectNode> tools)
            throws Exception {
        ObjectNode message = JSON.createObjectNode();
        message.put("role", "ROLE_USER");
        message.put("messageId", "message-" + runId);
        message.put("contextId", "context-" + runId);
        message.putArray("parts").addObject().put("text",
                "BLACKBOX_RUN=" + runId + ". Use every client tool supplied in this request exactly once. "
                        + "If no client tool is supplied, answer directly without calling a tool.");

        ObjectNode params = JSON.createObjectNode();
        params.set("message", message);
        if (!tools.isEmpty()) {
            ArrayNode clientTools = params.putObject("metadata").putArray("clientTools");
            tools.forEach(clientTools::add);
        }
        return envelope(streaming ? "SendStreamingMessage" : "SendMessage", "request-" + runId, params);
    }

    private static ObjectNode tool(String name, String description) {
        ObjectNode tool = JSON.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        schema.putObject("properties").putObject("city").put("type", "string");
        schema.putArray("required").add("city");
        schema.put("additionalProperties", false);
        return tool;
    }

    private static ObjectNode resultPart(String text, String toolCallId) {
        ObjectNode part = JSON.createObjectNode().put("text", text);
        part.putObject("metadata").put("toolCallId", toolCallId);
        return part;
    }

    private static String resumeRequest(
            boolean streaming,
            String requestId,
            String taskId,
            String contextId,
            List<ObjectNode> parts) throws Exception {
        ObjectNode message = JSON.createObjectNode();
        message.put("role", "ROLE_USER");
        message.put("messageId", "message-" + requestId);
        message.put("taskId", taskId);
        message.put("contextId", contextId);
        ArrayNode partArray = message.putArray("parts");
        parts.forEach(partArray::add);
        ObjectNode params = JSON.createObjectNode().set("message", message);
        return envelope(streaming ? "SendStreamingMessage" : "SendMessage", requestId, params);
    }

    private static String getTaskRequest(String requestId, String taskId) throws Exception {
        return envelope("GetTask", requestId, JSON.createObjectNode().put("id", taskId));
    }

    private static String envelope(String method, String requestId, JsonNode params) throws Exception {
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", requestId);
        envelope.put("method", method);
        envelope.set("params", params);
        return JSON.writeValueAsString(envelope);
    }

    private static void assertInputRequired(JsonNode response, String toolName) {
        assertThat(hasError(response)).as(response.toPrettyString()).isFalse();
        assertThat(containsState(response, "INPUT_REQUIRED")).as(response.toPrettyString()).isTrue();
        assertThat(requiredInterrupt(response, toolName).path("context").path("_interrupt_kind").asText())
                .isEqualTo("client_tool");
    }

    private static void assertCompleted(JsonNode response, String taskId, String canary) {
        assertThat(hasError(response)).as(response.toPrettyString()).isFalse();
        assertThat(containsState(response, "COMPLETED")).as(response.toPrettyString()).isTrue();
        assertThat(requiredTaskId(response)).isEqualTo(taskId);
        assertThat(allText(response)).contains(canary);
    }

    private static void assertInvalidContinuationOutcome(JsonNode response, String taskId) {
        assertThat(hasError(response) || containsState(response, "FAILED"))
                .as("invalid continuation must be rejected or fail the same Task: %s", response)
                .isTrue();
        if (!hasError(response)) {
            assertThat(requiredTaskId(response)).isEqualTo(taskId);
        }
    }

    private static boolean hasError(JsonNode root) {
        return findNodes(root, "error").stream().anyMatch(node -> !node.isNull() && !node.isMissingNode());
    }

    private static boolean containsState(JsonNode root, String expected) {
        return findNodes(root, "state").stream()
                .map(JsonNode::asText)
                .map(String::toUpperCase)
                .anyMatch(value -> value.contains(expected));
    }

    private static JsonNode requiredInterrupt(JsonNode root, String toolName) {
        return findNodes(root, "_interrupt").stream()
                .flatMap(ClientToolRuntimeBlackboxTest::interruptItems)
                .filter(node -> toolName.equals(node.path("toolName").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing interrupt for " + toolName + ": " + root));
    }

    private static java.util.stream.Stream<JsonNode> interruptItems(JsonNode interrupt) {
        JsonNode items = interrupt.path("items");
        if (items.isArray()) {
            List<JsonNode> nodes = new ArrayList<>();
            items.forEach(nodes::add);
            return nodes.stream();
        }
        return java.util.stream.Stream.of(interrupt);
    }

    private static Map<String, String> pendingCalls(JsonNode root) {
        Map<String, String> pending = new LinkedHashMap<>();
        findNodes(root, "_interrupt").stream()
                .flatMap(ClientToolRuntimeBlackboxTest::interruptItems)
                .filter(node -> "client_tool".equals(node.path("context").path("_interrupt_kind").asText()))
                .forEach(node -> pending.put(node.path("toolName").asText(), node.path("toolCallId").asText()));
        return pending;
    }

    private static String requiredText(JsonNode root, String field) {
        return findText(root, field)
                .orElseThrow(() -> new AssertionError("Missing " + field + ": " + root));
    }

    private static String requiredTaskId(JsonNode root) {
        return findText(root, "taskId")
                .or(() -> root.findValues("task").stream()
                        .filter(JsonNode::isObject)
                        .map(task -> task.path("id").asText())
                        .filter(value -> !value.isBlank())
                        .findFirst())
                .or(() -> findTaskObjects(root).stream()
                        .map(task -> task.path("id").asText())
                        .filter(value -> !value.isBlank())
                        .findFirst())
                .orElseThrow(() -> new AssertionError("Missing Task id: " + root));
    }

    private static List<JsonNode> findTaskObjects(JsonNode root) {
        List<JsonNode> tasks = new ArrayList<>();
        collectTaskObjects(root, tasks);
        return tasks;
    }

    private static void collectTaskObjects(JsonNode node, List<JsonNode> tasks) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            if (node.hasNonNull("id") && node.path("status").isObject()) {
                tasks.add(node);
            }
            node.forEach(child -> collectTaskObjects(child, tasks));
        } else if (node.isArray()) {
            node.forEach(child -> collectTaskObjects(child, tasks));
        }
    }

    private static java.util.Optional<String> findText(JsonNode root, String field) {
        return findNodes(root, field).stream()
                .filter(JsonNode::isValueNode)
                .map(JsonNode::asText)
                .filter(value -> !value.isBlank())
                .findFirst();
    }

    private static List<JsonNode> findNodes(JsonNode root, String field) {
        List<JsonNode> found = new ArrayList<>();
        walk(root, field, found);
        return found;
    }

    private static void walk(JsonNode node, String field, List<JsonNode> found) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (field.equals(entry.getKey())) {
                    found.add(entry.getValue());
                }
                walk(entry.getValue(), field, found);
            }
        } else if (node.isArray()) {
            node.forEach(child -> walk(child, field, found));
        }
    }

    private static String allText(JsonNode root) {
        StringBuilder text = new StringBuilder();
        root.findValues("text").forEach(node -> text.append(node.asText()).append('\n'));
        root.findValues("content").stream().filter(JsonNode::isTextual)
                .forEach(node -> text.append(node.asText()).append('\n'));
        return text.toString();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** External OpenAI-compatible peer; it is not a Runtime, Agent, or client-tool implementation. */
    private static final class ToolCallingLlmPeer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;
        private final List<Observation> observations = new CopyOnWriteArrayList<>();

        private ToolCallingLlmPeer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
        }

        static ToolCallingLlmPeer start() {
            try {
                return new ToolCallingLlmPeer();
            } catch (IOException error) {
                throw new IllegalStateException("Cannot start external LLM peer", error);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        void clearObservations() {
            observations.clear();
        }

        Observation observationFor(String runId) {
            return observations.stream()
                    .filter(observation -> observation.runId().equals(runId))
                    .findFirst()
                    .orElse(null);
        }

        int observationCount(String runId) {
            return (int) observations.stream()
                    .filter(observation -> observation.runId().equals(runId))
                    .count();
        }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                JsonNode request = JSON.readTree(exchange.getRequestBody());
                String runId = findRunId(request.toString());
                Map<String, JsonNode> definitions = toolDefinitions(request.path("tools"));
                observations.add(new Observation(runId, Set.copyOf(definitions.keySet()), Map.copyOf(definitions)));

                List<String> clientTools = definitions.keySet().stream()
                        .filter(name -> name.startsWith("readLocal"))
                        .toList();
                List<String> results = resultCanaries(request.toString());
                boolean streaming = request.path("stream").asBoolean(false);
                if (!results.isEmpty()) {
                    respond(exchange, streaming, finalText(results));
                } else if (!clientTools.isEmpty()) {
                    respond(exchange, streaming, toolCalls(clientTools, runId));
                } else {
                    respond(exchange, streaming, "NO_CLIENT_TOOL_" + runId);
                }
            } catch (Exception error) {
                byte[] body = ("{\"error\":\"" + error.getClass().getSimpleName() + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        }

        private static Map<String, JsonNode> toolDefinitions(JsonNode tools) {
            Map<String, JsonNode> definitions = new LinkedHashMap<>();
            if (tools.isArray()) {
                tools.forEach(tool -> {
                    JsonNode function = tool.path("function");
                    String name = function.path("name").asText();
                    if (!name.isBlank()) {
                        definitions.put(name, function.deepCopy());
                    }
                });
            }
            return definitions;
        }

        private static String findRunId(String text) {
            int marker = text.indexOf("BLACKBOX_RUN=");
            if (marker < 0) {
                return "unknown";
            }
            int start = marker + "BLACKBOX_RUN=".length();
            int end = start;
            while (end < text.length()) {
                char value = text.charAt(end);
                if (!(Character.isLetterOrDigit(value) || value == '-')) {
                    break;
                }
                end++;
            }
            return text.substring(start, end);
        }

        private static List<String> resultCanaries(String text) {
            Set<String> values = new LinkedHashSet<>();
            int offset = 0;
            while ((offset = text.indexOf("CLIENT_RESULT_", offset)) >= 0) {
                int end = offset;
                while (end < text.length()) {
                    char value = text.charAt(end);
                    if (!(Character.isLetterOrDigit(value) || value == '_')) {
                        break;
                    }
                    end++;
                }
                values.add(text.substring(offset, end));
                offset = end;
            }
            return List.copyOf(values);
        }

        private static String finalText(List<String> results) {
            return "FINAL_FROM_CLIENT_OBSERVATION " + String.join(" ", results);
        }

        private static List<ToolCall> toolCalls(List<String> names, String runId) {
            List<ToolCall> calls = new ArrayList<>();
            for (int index = 0; index < names.size(); index++) {
                calls.add(new ToolCall("call-" + runId + "-" + index, names.get(index),
                        "{\"city\":\"Beijing\"}"));
            }
            return calls;
        }

        private static void respond(HttpExchange exchange, boolean streaming, Object answer) throws Exception {
            if (streaming) {
                String sse = streamingBody(answer);
                byte[] body = sse.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            byte[] body = completionBody(answer).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }

        private static String completionBody(Object answer) throws Exception {
            ObjectNode message = JSON.createObjectNode().put("role", "assistant");
            String finishReason;
            if (answer instanceof List<?> list) {
                message.putNull("content");
                ArrayNode calls = message.putArray("tool_calls");
                for (Object value : list) {
                    ToolCall call = (ToolCall) value;
                    ObjectNode node = calls.addObject();
                    node.put("id", call.id());
                    node.put("type", "function");
                    node.putObject("function").put("name", call.name()).put("arguments", call.arguments());
                }
                finishReason = "tool_calls";
            } else {
                message.put("content", String.valueOf(answer));
                finishReason = "stop";
            }
            ObjectNode root = base("chat.completion");
            ObjectNode choice = root.putArray("choices").addObject();
            choice.put("index", 0);
            choice.set("message", message);
            choice.put("finish_reason", finishReason);
            root.putObject("usage").put("prompt_tokens", 1).put("completion_tokens", 1).put("total_tokens", 2);
            return JSON.writeValueAsString(root);
        }

        private static String streamingBody(Object answer) throws Exception {
            List<String> events = new ArrayList<>();
            if (answer instanceof List<?> list) {
                ObjectNode delta = JSON.createObjectNode().put("role", "assistant");
                ArrayNode calls = delta.putArray("tool_calls");
                int index = 0;
                for (Object value : list) {
                    ToolCall call = (ToolCall) value;
                    ObjectNode node = calls.addObject();
                    node.put("index", index++);
                    node.put("id", call.id());
                    node.put("type", "function");
                    node.putObject("function").put("name", call.name()).put("arguments", call.arguments());
                }
                events.add(chunk(delta, null));
                events.add(chunk(JSON.createObjectNode(), "tool_calls"));
            } else {
                events.add(chunk(JSON.createObjectNode().put("role", "assistant")
                        .put("content", String.valueOf(answer)), null));
                events.add(chunk(JSON.createObjectNode(), "stop"));
            }
            return events.stream().map(event -> "data: " + event + "\n\n")
                    .reduce("", String::concat) + "data: [DONE]\n\n";
        }

        private static String chunk(ObjectNode delta, String finishReason) throws Exception {
            ObjectNode root = base("chat.completion.chunk");
            ObjectNode choice = root.putArray("choices").addObject();
            choice.put("index", 0);
            choice.set("delta", delta);
            if (finishReason == null) {
                choice.putNull("finish_reason");
            } else {
                choice.put("finish_reason", finishReason);
            }
            return JSON.writeValueAsString(root);
        }

        private static ObjectNode base(String object) {
            ObjectNode root = JSON.createObjectNode();
            root.put("id", "chatcmpl-blackbox");
            root.put("object", object);
            root.put("created", 1);
            root.put("model", "client-tool-blackbox");
            return root;
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }

        record Observation(String runId, Set<String> toolNames, Map<String, JsonNode> toolDefinitions) {}

        record ToolCall(String id, String name, String arguments) {}
    }

    private record PendingTask(
            String runId,
            String taskId,
            String contextId,
            Map<String, String> pending) {}
}

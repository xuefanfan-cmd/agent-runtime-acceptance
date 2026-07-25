package com.huawei.ascend.sit.cases.integration.react_travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.config.TestEnvironment;
import com.huawei.ascend.sit.lifecycle.AgentConfig;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import com.huawei.ascend.sit.lifecycle.ProcessLauncher;
import com.huawei.ascend.sit.lifecycle.SutAgent;
import com.huawei.ascend.sit.lifecycle.SutInstance;
import com.huawei.ascend.sit.lifecycle.SutLauncher;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.openjiuwen.service.app.custom.rest.CustomRestProtocolAdapter;
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** FEAT-022 black-box acceptance against the real multi-react-travel-demo ReActAgent chain. */
@Feature("FEAT-022: 自定义 REST API 智能体服务入口")
@Tag("feat-022")
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class CustomRestReactAgentBlackboxTest {
    private static final String MAINPLAN = "mainplan";
    private static final String TRIP = "trip";
    private static final String HOTEL = "hotel";
    private static final String CUSTOMER_AGENT = "travel-mainplan";
    private static final String CUSTOM_PATH =
            "/v1/{project_id}/agents/{agent_id}/conversations/{conversation_id}";
    private static final String CONCRETE_PATH_PREFIX = "/v1/project-a/agents/travel-mainplan/conversations/";
    private static final String INTERNAL_CONTEXT_PREFIX = "custom-rest:v1:";
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String INITIAL_INCOMPLETE_INPUT =
            "请帮我规划一次北京出差。目前只确定目的地，出发城市、日期和差标都未确定，请先询问缺失信息。";
    private static final String RESUME_INPUT =
            "计划出差三天，下周二启程；请继续处理这次北京出差。";

    private TestConfig config;
    private LayeredLauncher launcher;
    private SutStack stack;
    private String baseUrl;
    private A2aServiceClient a2a;

    @BeforeAll
    void startRealReactAgentStack() {
        config = TestConfig.load(TestEnvironment.OPENJIUWEN);
        launcher = new LayeredLauncher(config);
        stack = SutStack.builder(config)
                .launcher(launcher)
                .streaming(false)
                .agent(HOTEL)
                .agent(TRIP, agent -> agent.downstream(HOTEL))
                .agent(MAINPLAN, agent -> agent.downstream(TRIP)
                        .property("openjiuwen.service.custom-rest.query-path", CUSTOM_PATH)
                        .property("acceptance.custom-rest.adapter-count", "1"))
                .start();
        baseUrl = stack.baseUrl(MAINPLAN);
        a2a = stack.client(MAINPLAN);
    }

    @AfterAll
    void stopRealReactAgentStack() {
        if (stack != null) {
            stack.close();
        }
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("entryConfigurationCases")
    @Tag("blackbox")
    @Stories(@Story("FEAT-022.config.entry: 入口配置与装配"))
    @DisplayName("Feat-022 Custom REST 入口遵循部署配置")
    void feat022CustomRestEntryFollowsDeploymentConfiguration(EntryConfigurationCase testCase) throws Exception {
        Map<String, String> properties = new LinkedHashMap<>();
        if (testCase.queryPath() != null) {
            properties.put("openjiuwen.service.custom-rest.query-path", testCase.queryPath());
        }
        properties.put("acceptance.custom-rest.adapter-count", Integer.toString(testCase.adapterCount()));
        if (testCase.mappingConflict()) {
            properties.put("acceptance.custom-rest.mapping-conflict", "true");
        }

        Throwable failure = null;
        try (SutStack isolated = startStandalone(properties, Map.of())) {
            if (!testCase.shouldStart()) {
                throw new AssertionError("invalid Custom REST deployment unexpectedly became ready: " + testCase);
            }
            String url = isolated.baseUrl(MAINPLAN);
            assertThat(get(url + "/.well-known/agent.json").statusCode()).isEqualTo(200);
            HttpResponse<String> custom = rawPost(url + CONCRETE_PATH_PREFIX + shortId(), "{}",
                    "application/json", "application/json", Map.of());
            if (testCase.enabled()) {
                assertThat(custom.statusCode()).isEqualTo(400);
                assertError(custom, "invalid_request");
            } else {
                assertThat(custom.statusCode()).isEqualTo(404);
            }
            assertThat(rawPost(url + "/a2a/", "{}", "application/json", "application/json", Map.of())
                    .statusCode()).isNotEqualTo(404);
        } catch (Throwable throwable) {
            failure = throwable;
        }
        if (testCase.shouldStart()) {
            assertThat(failure).as(testCase.label()).isNull();
        } else {
            assertThat(failure).as(testCase.label()).isNotNull();
        }
    }

    @Test
    @Tag("blackbox")
    @Stories(@Story("FEAT-022.entry.coexistence: 单 Agent 与存量入口共存"))
    @DisplayName("Feat-022 Custom REST 与 A2A、v1 query 在同一 ReActAgent 共存")
    void feat022CustomRestCoexistsWithExistingEntrypointsForOneHostedAgent() throws Exception {
        JsonNode card = json(get(baseUrl + "/.well-known/agent.json"));
        assertThat(card.path("name").asText()).containsIgnoringCase("travel");

        HttpResponse<String> custom = postCustom(baseUrl, conversation(), completeInput(), false,
                "application/json", Map.of(), "");
        assertSuccessfulTask(custom, TaskState.TASK_STATE_COMPLETED);

        HttpResponse<String> a2aRoute = rawPost(baseUrl + "/a2a/", "{}", "application/json",
                "application/json", Map.of());
        assertThat(a2aRoute.statusCode()).isNotEqualTo(404);
        assertThat(a2aRoute.body()).containsIgnoringCase("jsonrpc");

        Map<String, Object> query = Map.of("messages", List.of(Map.of("role", "user", "content", completeInput())),
                "conversation_id", conversation(), "stream", false);
        HttpResponse<String> legacy = rawPost(baseUrl + "/v1/query", MAPPER.writeValueAsString(query),
                "application/json", "application/json", Map.of());
        assertThat(legacy.statusCode()).isEqualTo(200);
        assertThat(json(legacy).path("conversation_id").asText()).isNotBlank();

        String unsupported = "/v1/project-a/agents/travel-hotel/conversations/" + conversation();
        HttpResponse<String> rejected = rawPost(baseUrl + unsupported, body(completeInput(), false),
                "application/json", "application/json", Map.of());
        assertThat(rejected.statusCode()).isEqualTo(400);
        assertError(rejected, "agent_not_supported");
    }

    @Test
    @Tag("blackbox")
    @Stories(@Story("FEAT-022.request.mapping: 自定义请求映射"))
    @DisplayName("Feat-022 adapter 按声明规则映射 path、header、query 和 body")
    void feat022AdapterMapsHttpContextUsingDeclaredBusinessRules() throws Exception {
        String conversation = conversation();
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("input", "我要去北京出差，请先询问我缺少的信息。");
        requestBody.put("stream", false);
        requestBody.put("workspace_id", "workspace-a");
        requestBody.put("custom_data", Map.of("source", "feat022-acceptance", "marker", shortId()));

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + CONCRETE_PATH_PREFIX + conversation
                        + "?trace=first&trace=second&mode=full"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Mapping", "alpha")
                .header("X-Mapping", "beta")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(requestBody)))
                .build();
        HttpResponse<String> response = http().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode result = json(response);
        assertThat(result.path("conversation_id").asText()).isEqualTo(conversation);
        assertThat(result.path("request").path("project_id").asText()).isEqualTo("project-a");
        assertThat(result.path("request").path("workspace_id").asText()).isEqualTo("workspace-a");
        assertThat(result.path("request").path("query").path("trace")).containsExactly(
                MAPPER.getNodeFactory().textNode("first"), MAPPER.getNodeFactory().textNode("second"));
        assertThat(result.path("request").path("headers").path("x-mapping")).containsExactly(
                MAPPER.getNodeFactory().textNode("alpha"), MAPPER.getNodeFactory().textNode("beta"));
        assertThat(result.path("request").path("custom_data").path("source").asText())
                .isEqualTo("feat022-acceptance");
        assertNoInternalContext(response.body());
        assertPublicTaskMatches(result);

        Map<String, Object> conflictBody = new LinkedHashMap<>(requestBody);
        conflictBody.put("conversation_id", conversation + "-other");
        HttpResponse<String> conflict = rawPost(baseUrl + CONCRETE_PATH_PREFIX + conversation,
                MAPPER.writeValueAsString(conflictBody), "application/json", "application/json", Map.of());
        assertThat(conflict.statusCode()).isEqualTo(400);
        assertError(conflict, "field_conflict");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("invalidRequestCases")
    @Tag("blackbox")
    @Stories(@Story("FEAT-022.request.invalid: 非法请求"))
    @DisplayName("Feat-022 非法请求返回可诊断客户错误")
    void feat022InvalidRequestsReturnDiagnosableClientErrors(InvalidRequestCase testCase) throws Exception {
        String conversation = testCase.blankConversation() ? "%20" : conversation();
        HttpResponse<String> response = rawPost(baseUrl + CONCRETE_PATH_PREFIX + conversation,
                testCase.body(), testCase.contentType(), testCase.accept(), Map.of());
        assertThat(response.statusCode()).isEqualTo(testCase.status());
        assertError(response, testCase.code());
        assertThat(response.body()).doesNotContain("\"success\":true", "TASK_STATE_COMPLETED");
        assertNoInternalContext(response.body());

        if (!testCase.blankConversation()) {
            HttpResponse<String> retry = postCustom(baseUrl, conversation, "我要去北京出差，请询问缺失信息。",
                    false, "application/json", Map.of(), "");
            assertThat(retry.statusCode()).isEqualTo(200);
            assertThat(json(retry).path("task_id").asText()).isNotBlank();
        }
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("synchronousCases")
    @Tag("blackbox")
    @Stories(@Story("FEAT-022.sync.call: 同步 JSON 调用"))
    @DisplayName("Feat-022 同步调用返回可追溯的正式 Task")
    void feat022SynchronousCallsReturnTraceableTaskSemantics(SynchronousCase testCase) throws Exception {
        HttpResponse<String> response = postCustom(baseUrl, conversation(), testCase.input(), false,
                "application/json", Map.of(), "");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("application/json");
        assertSuccessfulTask(response, testCase.state());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("streamingCases")
    @Tag("blackbox")
    @Stories(@Story("FEAT-022.stream.call: SSE 协商、事件与收束"))
    @DisplayName("Feat-022 流式调用按 Accept 协商并在 Task 终态收束")
    void feat022StreamingCallsNegotiateAndTerminateAsSse(StreamingCase testCase) throws Exception {
        HttpResponse<String> response = postCustom(baseUrl, conversation(), testCase.input(), true,
                testCase.accept(), Map.of(), "");
        if (!testCase.accepted()) {
            assertThat(response.statusCode()).isEqualTo(406);
            assertError(response, "stream_not_acceptable");
            return;
        }
        assertSseHeaders(response);
        List<SseFrame> frames = parseSse(response.body());
        assertThat(frames).isNotEmpty();
        assertThat(frames).allSatisfy(frame -> {
            assertThat(frame.event()).isIn("task", "status", "artifact", "error");
            assertThat(frame.data().path("conversation_id").asText()).isNotBlank();
            assertThat(frame.data().toString()).doesNotContain(INTERNAL_CONTEXT_PREFIX);
        });
        SseFrame terminal = frames.get(frames.size() - 1);
        assertThat(terminal.data().path("final").asBoolean()).isTrue();
        assertThat(terminal.data().path("state").asText()).isEqualTo(testCase.state().name());
        String taskId = firstTaskId(frames);
        assertThat(taskId).isNotBlank();
        assertThat(a2a.getTask(taskId).status().state()).isEqualTo(testCase.state());
    }

    @Test
    @Tag("blackbox")
    @Stories(@Story("FEAT-022.conversation.resume: conversationId 自动恢复 INPUT_REQUIRED Task"))
    @DisplayName("Feat-022 客户端不传 taskId 可恢复 INPUT_REQUIRED Task")
    void feat022ConversationIdAloneResumesInputRequiredTask() throws Exception {
        String conversation = conversation();
        HttpResponse<String> firstResponse = postCustom(baseUrl, conversation, INITIAL_INCOMPLETE_INPUT, false,
                "application/json", Map.of(), "");
        assertThat(firstResponse.statusCode()).isEqualTo(200);
        JsonNode first = json(firstResponse);
        String taskId = first.path("task_id").asText();
        assertThat(taskId).isNotBlank();
        assertThat(TaskState.valueOf(first.path("state").asText()))
                .isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);
        assertThat(a2a.getTask(taskId).status().state()).isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);

        HttpResponse<String> resumedResponse = postCustom(baseUrl, conversation, RESUME_INPUT, false,
                "application/json", Map.of(), "");
        assertThat(resumedResponse.statusCode()).isEqualTo(200);
        JsonNode resumed = json(resumedResponse);
        TaskState resumedState = TaskState.valueOf(resumed.path("state").asText());
        assertThat(resumed.path("task_id").asText()).isEqualTo(taskId);
        assertThat(resumedState).isIn(TaskState.TASK_STATE_INPUT_REQUIRED, TaskState.TASK_STATE_COMPLETED);
        assertThat(a2a.getTask(taskId).status().state()).isEqualTo(resumedState);
        assertNoInternalContext(firstResponse.body() + resumedResponse.body());
    }

    @Test
    @Tag("blackbox")
    @Stories(@Story("FEAT-022.conversation.new-task: 终态后创建新 Task"))
    @DisplayName("Feat-022 conversation 终态后再次发送会创建新 Task")
    void feat022TerminalConversationStartsANewTask() throws Exception {
        String conversation = conversation();
        HttpResponse<String> completedResponse = postCustom(baseUrl, conversation, completeInput(), false,
                "application/json", Map.of(), "");
        assertThat(completedResponse.statusCode()).isEqualTo(200);
        JsonNode completed = json(completedResponse);
        String completedTaskId = completed.path("task_id").asText();
        assertThat(completedTaskId).isNotBlank();
        assertThat(TaskState.valueOf(completed.path("state").asText()))
                .isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertThat(a2a.getTask(completedTaskId).status().state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);

        HttpResponse<String> next = postCustom(baseUrl, conversation,
                "请规划一次去上海的新出差，目前日期和出发地未定，请先询问信息。", false,
                "application/json", Map.of(), "");
        JsonNode nextBody = json(next);
        assertThat(next.statusCode()).isEqualTo(200);
        String nextTaskId = nextBody.path("task_id").asText();
        TaskState nextState = TaskState.valueOf(nextBody.path("state").asText());
        assertThat(nextTaskId).isNotBlank().isNotEqualTo(completedTaskId);
        assertThat(nextState).isIn(TaskState.TASK_STATE_INPUT_REQUIRED, TaskState.TASK_STATE_COMPLETED);
        assertThat(a2a.getTask(completedTaskId).status().state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertThat(a2a.getTask(nextTaskId).status().state()).isEqualTo(nextState);
        assertNoInternalContext(completedResponse.body() + next.body());
    }

    @Test
    @Tag("blackbox")
    @Stories(@Story("FEAT-022.conversation.concurrency: 同会话保护与不同会话隔离"))
    @DisplayName("Feat-022 并发请求按 conversation 隔离")
    void feat022ConcurrentRequestsAreProtectedPerConversation() throws Exception {
        String sameConversation = conversation();
        List<HttpResponse<String>> same = concurrentPosts(List.of(sameConversation, sameConversation));
        assertThat(same).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
        HttpResponse<String> busy = same.stream().filter(response -> response.statusCode() == 409).findFirst().orElseThrow();
        assertError(busy, "conversation_busy");
        assertThat(same.stream().filter(response -> response.statusCode() == 200)
                .map(CustomRestReactAgentBlackboxTest::uncheckedJson)
                .map(node -> node.path("task_id").asText()).filter(value -> !value.isBlank()).distinct().count())
                .isEqualTo(1);

        List<HttpResponse<String>> isolated = concurrentPosts(List.of(conversation(), conversation()));
        assertThat(isolated).extracting(HttpResponse::statusCode).containsOnly(200);
        assertThat(isolated).extracting(response -> uncheckedJson(response).path("task_id").asText())
                .allSatisfy(id -> assertThat(id).isNotBlank()).doesNotHaveDuplicates();
    }

    @Test
    @Disabled("等待runtime补齐非空tenant到ServeRequest.tenantId传播")
    @Tag("blackbox")
    @Tag("known-gap")
    @Stories(@Story("FEAT-022.conversation.tenant: tenant/conversation 外部隔离"))
    @DisplayName("Feat-022 tenant 与 conversation 命名空间不交叉恢复")
    void feat022TenantAndConversationNamespacesDoNotCrossResume() throws Exception {
        String conversation = conversation();
        JsonNode tenantA = json(postCustom(baseUrl, conversation, INITIAL_INCOMPLETE_INPUT, false,
                "application/json", Map.of("X-Tenant-Id", "tenant-a"), ""));
        JsonNode tenantB = json(postCustom(baseUrl, conversation, INITIAL_INCOMPLETE_INPUT, false,
                "application/json", Map.of("X-Tenant-Id", "tenant-b"), ""));
        assertThat(tenantA.path("task_id").asText()).isNotEqualTo(tenantB.path("task_id").asText());
        JsonNode resumedA = json(postCustom(baseUrl, conversation, RESUME_INPUT, false,
                "application/json", Map.of("X-Tenant-Id", "tenant-a"), ""));
        assertThat(resumedA.path("task_id").asText()).isEqualTo(tenantA.path("task_id").asText());
        assertNoInternalContext(tenantA.toString() + tenantB + resumedA);
    }

    @Test
    @Tag("blackbox")
    @Stories(@Story("FEAT-022.stream.disconnect: 客户端断连"))
    @DisplayName("Feat-022 关闭 SSE 订阅不会取消正式 Task")
    void feat022ClosingSseDoesNotCancelTheFormalTask() throws Exception {
        String conversation = conversation();
        HttpRequest request = customRequest(baseUrl, conversation, completeInput(), true,
                "text/event-stream", Map.of(), "");
        HttpResponse<InputStream> response = http().send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        String taskId;
        try (InputStream stream = response.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            taskId = readFirstTaskId(reader);
        }
        assertThat(taskId).isNotBlank();
        Task observed = a2a.getTask(taskId);
        assertThat(observed.status().state()).isNotEqualTo(TaskState.TASK_STATE_CANCELED);

        HttpResponse<String> subsequent = postCustom(baseUrl, conversation(),
                "我要去广州出差，请先询问缺少的信息。", false, "application/json", Map.of(), "");
        assertThat(subsequent.statusCode()).isEqualTo(200);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("failureModes")
    @Tag("blackbox")
    @Stories(@Story("FEAT-022.error.execution: Agent 执行失败"))
    @DisplayName("Feat-022 Agent 失败在同步和流式入口保留失败语义")
    void feat022AgentFailuresPreserveFailureSemanticsForJsonAndSse(FailureMode mode) throws Exception {
        Map<String, String> properties = Map.of(
                "openjiuwen.service.custom-rest.query-path", CUSTOM_PATH,
                "acceptance.custom-rest.adapter-count", "1");
        try (SutStack failedLlm = startStandalone(properties,
                Map.of("LLM_API_BASE", "http://127.0.0.1:1/v1", "LLM_API_KEY", "feat022-invalid"))) {
            HttpResponse<String> response = postCustom(failedLlm.baseUrl(MAINPLAN), conversation(), completeInput(),
                    mode.streaming(), mode.streaming() ? "text/event-stream" : "application/json", Map.of(), "");
            assertNoInternalContext(response.body());
            if (response.statusCode() == 200 && mode.streaming()) {
                List<SseFrame> frames = parseSse(response.body());
                assertThat(frames).isNotEmpty();
                assertThat(frames).anySatisfy(frame -> assertThat(frame.event().equals("error")
                        || frame.data().path("state").asText().equals(TaskState.TASK_STATE_FAILED.name())).isTrue());
                SseFrame terminal = frames.get(frames.size() - 1);
                assertThat(terminal.data().path("success").asBoolean()).isFalse();
                assertThat(terminal.data().path("state").asText()).isNotEqualTo(TaskState.TASK_STATE_COMPLETED.name());
            } else if (response.statusCode() == 200) {
                assertThat(json(response).path("state").asText()).isEqualTo(TaskState.TASK_STATE_FAILED.name());
                assertThat(json(response).path("success").asBoolean()).isFalse();
            } else {
                assertThat(response.statusCode()).isGreaterThanOrEqualTo(400);
                assertThat(json(response).path("error").path("code").asText()).isNotBlank();
            }
        }
    }

    private SutStack startStandalone(Map<String, String> properties, Map<String, String> environment) {
        return SutStack.builder(config).launcher(launcher).streaming(false)
                .agent(MAINPLAN, agent -> {
                    properties.forEach(agent::property);
                    environment.forEach(agent::env);
                }).start();
    }

    private List<HttpResponse<String>> concurrentPosts(List<String> conversations) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(conversations.size());
        try {
            List<CompletableFuture<HttpResponse<String>>> futures = conversations.stream()
                    .map(conversation -> CompletableFuture.supplyAsync(() -> {
                        try {
                            return postCustom(baseUrl, conversation,
                                    "我要去北京出差，目前日期、天数和出发城市都未定，请先询问。", false,
                                    "application/json", Map.of(), "");
                        } catch (Exception exception) {
                            throw new RuntimeException(exception);
                        }
                    }, executor)).toList();
            return futures.stream().map(CompletableFuture::join).toList();
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void assertSuccessfulTask(HttpResponse<String> response, TaskState expectedState) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(body.path("agent_id").asText()).isEqualTo(CUSTOMER_AGENT);
        assertThat(body.path("conversation_id").asText()).isNotBlank();
        assertThat(body.path("task_id").asText()).isNotBlank();
        assertThat(body.path("state").asText()).isEqualTo(expectedState.name());
        assertNoInternalContext(response.body());
        assertPublicTaskMatches(body);
    }

    private void assertPublicTaskMatches(JsonNode response) {
        Task task = a2a.getTask(response.path("task_id").asText());
        assertThat(task.status().state().name()).isEqualTo(response.path("state").asText());
    }

    private static void assertError(HttpResponse<String> response, String code) throws Exception {
        JsonNode body = json(response);
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("error").path("code").asText()).isEqualTo(code);
        assertThat(body.path("error").path("message").asText()).isNotBlank();
    }

    private static void assertSseHeaders(HttpResponse<String> response) {
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(response.statusCode()).isEqualTo(200);
        softly.assertThat(response.headers().firstValue("content-type").orElse(""))
                .startsWith("text/event-stream");
        softly.assertThat(response.headers().firstValue("cache-control").orElse(""))
                .isEqualTo("no-cache, no-transform");
        softly.assertThat(response.headers().firstValue("connection").orElse(""))
                .isEqualTo("keep-alive");
        softly.assertThat(response.headers().firstValue("x-accel-buffering").orElse(""))
                .isEqualTo("no");
        softly.assertAll();
    }

    private static HttpResponse<String> postCustom(String root, String conversation, String input, boolean stream,
                                                    String accept, Map<String, String> headers, String query)
            throws Exception {
        return http().send(customRequest(root, conversation, input, stream, accept, headers, query),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest customRequest(String root, String conversation, String input, boolean stream,
                                             String accept, Map<String, String> headers, String query) throws Exception {
        String suffix = query == null || query.isBlank() ? "" : "?" + query;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(root + CONCRETE_PATH_PREFIX
                        + encodeSegment(conversation) + suffix))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(input, stream)));
        if (accept != null && !accept.isBlank()) {
            builder.header("Accept", accept);
        }
        headers.forEach(builder::header);
        return builder.build();
    }

    private static HttpResponse<String> rawPost(String url, String body, String contentType, String accept,
                                                Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        if (contentType != null && !contentType.isBlank()) {
            builder.header("Content-Type", contentType);
        }
        if (accept != null && !accept.isBlank()) {
            builder.header("Accept", accept);
        }
        headers.forEach(builder::header);
        return http().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return http().send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpClient http() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1).build();
    }

    private static String body(String input, boolean stream) throws Exception {
        return MAPPER.writeValueAsString(Map.of("input", input, "stream", stream,
                "workspace_id", "workspace-a", "custom_data", Map.of("source", "feat022-acceptance")));
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        return MAPPER.readTree(response.body());
    }

    private static JsonNode uncheckedJson(HttpResponse<String> response) {
        try {
            return json(response);
        } catch (Exception exception) {
            throw new AssertionError("response is not JSON: " + response.body(), exception);
        }
    }

    private static List<SseFrame> parseSse(String payload) throws Exception {
        List<SseFrame> frames = new ArrayList<>();
        String event = null;
        StringBuilder data = new StringBuilder();
        for (String line : payload.split("\\R", -1)) {
            if (line.startsWith("event:")) {
                event = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(line.substring(5).trim());
            } else if (line.isEmpty() && !data.isEmpty()) {
                frames.add(new SseFrame(event == null ? "" : event, MAPPER.readTree(data.toString())));
                event = null;
                data.setLength(0);
            }
        }
        if (!data.isEmpty()) {
            frames.add(new SseFrame(event == null ? "" : event, MAPPER.readTree(data.toString())));
        }
        return frames;
    }

    private static String readFirstTaskId(BufferedReader reader) throws Exception {
        StringBuilder data = new StringBuilder();
        long deadline = System.nanoTime() + REQUEST_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            String line = reader.readLine();
            if (line == null) {
                break;
            }
            if (line.startsWith("data:")) {
                data.append(line.substring(5).trim());
            } else if (line.isEmpty() && !data.isEmpty()) {
                String taskId = MAPPER.readTree(data.toString()).path("task_id").asText();
                if (!taskId.isBlank()) {
                    return taskId;
                }
                data.setLength(0);
            }
        }
        return "";
    }

    private static String firstTaskId(List<SseFrame> frames) {
        return frames.stream().map(frame -> frame.data().path("task_id").asText())
                .filter(value -> !value.isBlank()).findFirst().orElse("");
    }

    private static void assertNoInternalContext(String payload) {
        assertThat(payload).doesNotContain(INTERNAL_CONTEXT_PREFIX);
    }

    private static String conversation() {
        return "feat022-" + UUID.randomUUID();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String encodeSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String completeInput() {
        return "请规划从上海到北京的三天出差，下周二出发，每晚预算800元以内，至少四星，国贸附近。";
    }

    private static Stream<EntryConfigurationCase> entryConfigurationCases() {
        return Stream.of(
                new EntryConfigurationCase("合法绝对 path", CUSTOM_PATH, 1, false, true, true),
                new EntryConfigurationCase("未配置 path", null, 0, false, true, false),
                new EntryConfigurationCase("字面量 false", "false", 0, false, true, false),
                new EntryConfigurationCase("空白 path", " ", 1, false, false, false),
                new EntryConfigurationCase("相对 path", "v1/custom", 1, false, false, false),
                new EntryConfigurationCase("mapping 冲突", CUSTOM_PATH, 1, true, false, false),
                new EntryConfigurationCase("零 adapter", CUSTOM_PATH, 0, false, false, false),
                new EntryConfigurationCase("两个 adapter", CUSTOM_PATH, 2, false, false, false));
    }

    private static Stream<InvalidRequestCase> invalidRequestCases() throws Exception {
        return Stream.of(
                new InvalidRequestCase("空 HTTP body", "", "application/json", "application/json", 400,
                        "invalid_request", false),
                new InvalidRequestCase("空 JSON object", "{}", "application/json", "application/json", 400,
                        "invalid_request", false),
                new InvalidRequestCase("null input", "{\"input\":null,\"stream\":false}", "application/json",
                        "application/json", 400, "invalid_request", false),
                new InvalidRequestCase("空白 input", "{\"input\":\"  \",\"stream\":false}", "application/json",
                        "application/json", 400, "invalid_request", false),
                new InvalidRequestCase("stream 非 boolean", "{\"input\":\"hello\",\"stream\":\"yes\"}",
                        "application/json", "application/json", 400, "invalid_request", false),
                new InvalidRequestCase("非 JSON Content-Type", "{\"input\":\"hello\"}", "text/plain",
                        "application/json", 415, "unsupported_media_type", false),
                new InvalidRequestCase("非法 JSON", "{bad", "application/json", "application/json", 400,
                        "invalid_json", false),
                new InvalidRequestCase("非 object JSON", "[]", "application/json", "application/json", 400,
                        "invalid_json", false),
                new InvalidRequestCase("空 conversationId", "{\"input\":\"hello\",\"stream\":false}",
                        "application/json", "application/json", 400, "invalid_custom_request", true),
                new InvalidRequestCase("SSE 不可接受", "{\"input\":\"hello\",\"stream\":true}",
                        "application/json", "application/json", 406, "stream_not_acceptable", false),
                new InvalidRequestCase("非法 Accept", "{\"input\":\"hello\",\"stream\":true}",
                        "application/json", "not/a media type;=", 406, "stream_not_acceptable", false));
    }

    private static Stream<SynchronousCase> synchronousCases() {
        return Stream.of(new SynchronousCase("完整输入完成", completeInput(), TaskState.TASK_STATE_COMPLETED),
                new SynchronousCase("缺失输入等待用户", "我要去北京出差，请先询问缺失信息。",
                        TaskState.TASK_STATE_INPUT_REQUIRED));
    }

    private static Stream<StreamingCase> streamingCases() {
        return Stream.of(
                new StreamingCase("缺失 Accept", "", completeInput(), true, TaskState.TASK_STATE_COMPLETED),
                new StreamingCase("精确 SSE", "text/event-stream", completeInput(), true,
                        TaskState.TASK_STATE_COMPLETED),
                new StreamingCase("text wildcard", "text/*", "我要去北京出差，请先询问缺失信息。", true,
                        TaskState.TASK_STATE_INPUT_REQUIRED),
                new StreamingCase("任意 media", "*/*", completeInput(), true, TaskState.TASK_STATE_COMPLETED),
                new StreamingCase("明确拒绝 SSE", "text/event-stream;q=0, */*;q=1", completeInput(), false, null),
                new StreamingCase("不兼容 media", "application/json", completeInput(), false, null),
                new StreamingCase("非法 Accept", "not/a media type;=", completeInput(), false, null));
    }

    private static Stream<FailureMode> failureModes() {
        return Stream.of(new FailureMode("同步", false), new FailureMode("流式", true));
    }

    private record EntryConfigurationCase(String label, String queryPath, int adapterCount,
                                          boolean mappingConflict, boolean shouldStart, boolean enabled) {
        @Override public String toString() { return label; }
    }

    private record InvalidRequestCase(String label, String body, String contentType, String accept, int status,
                                      String code, boolean blankConversation) {
        @Override public String toString() { return label; }
    }

    private record SynchronousCase(String label, String input, TaskState state) {
        @Override public String toString() { return label; }
    }

    private record StreamingCase(String label, String accept, String input, boolean accepted, TaskState state) {
        @Override public String toString() { return label; }
    }

    private record FailureMode(String label, boolean streaming) {
        @Override public String toString() { return label; }
    }

    private record SseFrame(String event, JsonNode data) { }

    /** Launches mainplan through Boot's PropertiesLauncher so the external extension and test adapter are visible. */
    private static final class LayeredLauncher implements SutLauncher {
        private static final String PROPERTIES_LAUNCHER = "org.springframework.boot.loader.launch.PropertiesLauncher";
        private final TestConfig config;
        private final ProcessLauncher delegate;
        private final String repository;
        private final Duration startupTimeout;

        private LayeredLauncher(TestConfig config) {
            this.config = config;
            this.delegate = new ProcessLauncher(config);
            this.repository = config.getString("sut.m2.repo", System.getProperty("user.home") + "/.m2/repository");
            this.startupTimeout = Duration.ofSeconds(config.getInt("sut.timeout.startup-seconds", 60));
        }

        @Override
        public SutInstance start(SutAgent agent, AgentConfig agentConfig) {
            if (!MAINPLAN.equals(agent.name())) {
                return delegate.start(agent, agentConfig);
            }
            Path mainJar = agent.artifact().jarPath(repository);
            Path extension = Path.of(repository, "com", "openjiuwen", "agent-service-app-custom-rest", "0.1.0",
                    "agent-service-app-custom-rest-0.1.0.jar");
            Path testClasses = Path.of(System.getProperty("basedir", System.getProperty("user.dir")),
                    "target", "test-classes");
            requireFile(mainJar, "travel mainplan");
            requireFile(extension, "Custom REST extension");
            if (!Files.isDirectory(testClasses)) {
                throw new IllegalStateException("compiled FEAT-022 test classes not found at " + testClasses);
            }

            int port = agentConfig.port() > 0 ? agentConfig.port() : freePort();
            agentConfig.port(port);
            fillMissingRemoteUrl(agentConfig);
            Path log = Path.of(System.getProperty("basedir", System.getProperty("user.dir")), "target", "sit-logs",
                    agent.name() + "-" + shortId(), "stdout.log");
            try {
                Files.createDirectories(log.getParent());
            } catch (IOException exception) {
                throw new IllegalStateException("cannot create SUT log directory", exception);
            }

            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            Map<String, String> jvm = new LinkedHashMap<>(config.getStringMap("sut.java.system-properties"));
            jvm.putAll(agentConfig.jvmSystemProperties());
            jvm.putIfAbsent("LOG_HOME", log.getParent().toString());
            jvm.put("loader.main", CustomRestTestApplication.class.getName());
            jvm.forEach((key, value) -> command.add("-D" + key + "=" + value));
            command.add("-Dloader.path=" + extension.toAbsolutePath() + "," + testClasses.toAbsolutePath());
            command.add("-cp");
            command.add(mainJar.toAbsolutePath().toString());
            command.add(PROPERTIES_LAUNCHER);
            command.addAll(agentConfig.toProgramArgs());

            ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
            agentConfig.environment().forEach(processBuilder.environment()::put);
            try {
                Process process = processBuilder.start();
                awaitReady(agent.name(), port, process, log);
                return new ManagedSutInstance(agent.name(), process.pid(), port, "http://localhost:" + port,
                        process, log);
            } catch (IOException exception) {
                throw new IllegalStateException("failed to launch " + agent.name(), exception);
            }
        }

        private void awaitReady(String name, int port, Process process, Path log) {
            long deadline = System.nanoTime() + startupTimeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) {
                    throw new IllegalStateException(name + " exited before readiness\n" + tail(log));
                }
                try {
                    HttpURLConnection connection = (HttpURLConnection) URI.create(
                            "http://127.0.0.1:" + port + "/.well-known/agent.json").toURL().openConnection();
                    connection.setConnectTimeout(500);
                    connection.setReadTimeout(1000);
                    if (connection.getResponseCode() == 200) {
                        connection.disconnect();
                        return;
                    }
                    connection.disconnect();
                } catch (IOException ignored) {
                    // The process has not bound its HTTP connector yet.
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                    throw new IllegalStateException("interrupted while awaiting " + name, exception);
                }
            }
            process.destroyForcibly();
            throw new IllegalStateException(name + " did not become ready on port " + port + "\n" + tail(log));
        }

        private static void fillMissingRemoteUrl(AgentConfig config) {
            for (String key : new ArrayList<>(config.properties().keySet())) {
                if (key.endsWith("].name")) {
                    String urlKey = key.substring(0, key.length() - ".name".length()) + ".url";
                    config.properties().putIfAbsent(urlKey, "http://127.0.0.1:1/a2a/");
                }
            }
        }

        private static int freePort() {
            try (ServerSocket socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            } catch (IOException exception) {
                throw new IllegalStateException("cannot allocate SUT port", exception);
            }
        }

        private static void requireFile(Path file, String description) {
            if (!Files.isRegularFile(file)) {
                throw new IllegalStateException(description + " artifact not found at " + file);
            }
        }

        private static String tail(Path log) {
            try {
                List<String> lines = Files.readAllLines(log);
                return String.join("\n", lines.subList(Math.max(0, lines.size() - 50), lines.size()));
            } catch (IOException exception) {
                return "cannot read " + log + ": " + exception.getMessage();
            }
        }
    }
}

/** Test host main that adds the adapter initializer before starting the unmodified travel application. */
final class CustomRestTestApplication {
    private CustomRestTestApplication() {
    }

    public static void main(String[] args) throws Exception {
        Class<?> travelApplication = Class.forName(
                "com.openjiuwen.example.travel.mainplan.TravelMainplanApplication");
        org.springframework.boot.SpringApplication application =
                new org.springframework.boot.SpringApplication(travelApplication);
        application.addInitializers(new CustomRestTestInitializer());
        application.run(args);
    }
}

/** Loaded by the SUT before context refresh; it registers only the host-owned Custom REST SPI surface. */
final class CustomRestTestInitializer
        implements org.springframework.context.ApplicationContextInitializer<
        org.springframework.context.ConfigurableApplicationContext> {
    @Override
    public void initialize(org.springframework.context.ConfigurableApplicationContext applicationContext) {
        if (!(applicationContext instanceof org.springframework.context.support.GenericApplicationContext context)) {
            throw new IllegalStateException("FEAT-022 initializer requires GenericApplicationContext");
        }
        int count = applicationContext.getEnvironment().getProperty("acceptance.custom-rest.adapter-count",
                Integer.class, 1);
        if (count >= 1) {
            context.registerBean("acceptanceCustomRestAdapter1", TravelCustomRestProtocolAdapter.class);
        }
        if (count >= 2) {
            context.registerBean("acceptanceCustomRestAdapter2", TravelCustomRestProtocolAdapter.class);
        }
        context.registerBean("acceptanceCustomRestValidationAdvice", CustomRestValidationAdvice.class);
        if (applicationContext.getEnvironment().getProperty("acceptance.custom-rest.mapping-conflict",
                Boolean.class, false)) {
            context.registerBean("acceptanceCustomRestConflictController", CustomRestConflictController.class);
        }
    }
}

/** Customer protocol adapter used by the external ReActAgent process. */
final class TravelCustomRestProtocolAdapter implements CustomRestProtocolAdapter {
    private static final String SUPPORTED_AGENT = "travel-mainplan";

    @Override
    public A2ASendCommand toA2ARequest(Context context) {
        String conversation = context.pathVariables().get("conversation_id");
        String agent = context.pathVariables().get("agent_id");
        if (!SUPPORTED_AGENT.equals(agent)) {
            throw new CustomRestValidationException(400, "agent_not_supported", "agent is not hosted", conversation);
        }
        Object bodyConversation = context.body().get("conversation_id");
        if (bodyConversation != null && !String.valueOf(bodyConversation).equals(conversation)) {
            throw new CustomRestValidationException(400, "field_conflict",
                    "body conversation_id conflicts with path", conversation);
        }
        Object inputValue = context.body().get("input");
        if (!(inputValue instanceof String input) || input.isBlank()) {
            throw new CustomRestValidationException(400, "invalid_request", "input must be a non-blank string",
                    conversation);
        }
        Object streamValue = context.body().get("stream");
        if (streamValue != null && !(streamValue instanceof Boolean)) {
            throw new CustomRestValidationException(400, "invalid_request", "stream must be boolean", conversation);
        }
        boolean stream = streamValue == null || (Boolean) streamValue;
        Message message = Message.builder().role(Message.Role.ROLE_USER).messageId(UUID.randomUUID().toString())
                .parts(List.of(new TextPart(input))).build();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("project_id", context.pathVariables().get("project_id"));
        metadata.put("workspace_id", context.body().get("workspace_id"));
        metadata.put("query", context.queryParams());
        metadata.put("headers", context.headers());
        metadata.put("custom_data", context.body().getOrDefault("custom_data", Map.of()));
        String tenant = first(context.headers().get("x-tenant-id"));
        MessageSendParams params = MessageSendParams.builder().message(message).metadata(metadata).tenant(tenant).build();
        return new A2ASendCommand(params, conversation, stream);
    }

    @Override
    public Object fromA2ATask(Task task, Context context) {
        return taskEnvelope(task, context);
    }

    @Override
    public SseEvent fromA2AStreamEvent(StreamingEventKind event, Context context) {
        String eventName;
        String taskId = null;
        TaskState state = null;
        boolean terminal = false;
        String output = null;
        if (event instanceof Task task) {
            eventName = "task";
            taskId = task.id();
            state = state(task.status());
            terminal = terminal(state);
            output = output(task);
        } else if (event instanceof TaskStatusUpdateEvent status) {
            eventName = "status";
            taskId = status.taskId();
            state = state(status.status());
            terminal = status.isFinalOrInterrupted();
            output = output(status.status() == null ? null : status.status().message());
        } else if (event instanceof TaskArtifactUpdateEvent artifact) {
            eventName = "artifact";
            taskId = artifact.taskId();
            output = output(artifact.artifact());
        } else if (event instanceof Message message) {
            eventName = "status";
            taskId = message.taskId();
            output = output(message);
        } else {
            throw new IllegalArgumentException("unsupported A2A stream event: " + event.getClass().getName());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("success", state != TaskState.TASK_STATE_FAILED);
        data.put("conversation_id", context.pathVariables().get("conversation_id"));
        data.put("task_id", taskId);
        data.put("state", state == null ? null : state.name());
        data.put("final", terminal);
        data.put("output", output);
        return new SseEvent(eventName, data);
    }

    @Override
    public Object fromError(CustomRestError error, Context context) {
        return errorEnvelope(error.code(), error.message(), context.pathVariables().get("conversation_id"));
    }

    @Override
    public SseEvent fromStreamError(CustomRestError error, Context context) {
        return new SseEvent("error",
                errorEnvelope(error.code(), error.message(), context.pathVariables().get("conversation_id")));
    }

    private static Map<String, Object> taskEnvelope(Task task, Context context) {
        TaskState state = state(task.status());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", state != TaskState.TASK_STATE_FAILED && state != TaskState.TASK_STATE_REJECTED
                && state != TaskState.TASK_STATE_CANCELED);
        response.put("agent_id", SUPPORTED_AGENT);
        response.put("conversation_id", context.pathVariables().get("conversation_id"));
        response.put("task_id", task.id());
        response.put("state", state == null ? null : state.name());
        response.put("output", output(task));
        response.put("request", requestProjection(context));
        return response;
    }

    private static Map<String, Object> requestProjection(Context context) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("project_id", context.pathVariables().get("project_id"));
        request.put("workspace_id", context.body().get("workspace_id"));
        request.put("query", context.queryParams());
        request.put("headers", context.headers());
        request.put("custom_data", context.body().getOrDefault("custom_data", Map.of()));
        return request;
    }

    static Map<String, Object> errorEnvelope(String code, String message, String conversation) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("conversation_id", conversation);
        response.put("task_id", null);
        response.put("error", Map.of("code", code, "message", message == null ? "request failed" : message));
        return response;
    }

    private static TaskState state(TaskStatus status) {
        return status == null ? null : status.state();
    }

    private static boolean terminal(TaskState state) {
        return state != null && (state.isFinal() || state.isInterrupted());
    }

    private static String output(Task task) {
        if (task == null) return null;
        StringBuilder text = new StringBuilder();
        if (task.artifacts() != null) task.artifacts().forEach(artifact -> append(text, artifact.parts()));
        if (text.isEmpty() && task.status() != null) append(text, task.status().message() == null
                ? List.of() : task.status().message().parts());
        return text.isEmpty() ? null : text.toString();
    }

    private static String output(Artifact artifact) {
        if (artifact == null) return null;
        StringBuilder text = new StringBuilder();
        append(text, artifact.parts());
        return text.isEmpty() ? null : text.toString();
    }

    private static String output(Message message) {
        if (message == null) return null;
        StringBuilder text = new StringBuilder();
        append(text, message.parts());
        return text.isEmpty() ? null : text.toString();
    }

    private static void append(StringBuilder target, List<Part<?>> parts) {
        if (parts == null) return;
        for (Part<?> part : parts) {
            if (part instanceof TextPart text && text.text() != null) target.append(text.text());
        }
    }

    private static String first(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }
}

final class CustomRestValidationException extends RuntimeException {
    private final int status;
    private final String code;
    private final String conversation;

    CustomRestValidationException(int status, String code, String message, String conversation) {
        super(message);
        this.status = status;
        this.code = code;
        this.conversation = conversation;
    }

    int status() { return status; }
    String code() { return code; }
    String conversation() { return conversation; }
}

@org.springframework.web.bind.annotation.RestControllerAdvice
final class CustomRestValidationAdvice {
    @org.springframework.web.bind.annotation.ExceptionHandler(CustomRestValidationException.class)
    org.springframework.http.ResponseEntity<Map<String, Object>> invalid(CustomRestValidationException exception) {
        return org.springframework.http.ResponseEntity.status(exception.status())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(TravelCustomRestProtocolAdapter.errorEnvelope(exception.code(), exception.getMessage(),
                        exception.conversation()));
    }
}

@org.springframework.web.bind.annotation.RestController
final class CustomRestConflictController {
    @org.springframework.web.bind.annotation.PostMapping(
            "/v1/{project_id}/agents/{agent_id}/conversations/{conversation_id}")
    Map<String, Object> conflict() {
        return Map.of("unexpected", true);
    }
}

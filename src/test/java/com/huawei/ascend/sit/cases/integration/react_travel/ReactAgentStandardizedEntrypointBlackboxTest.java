package com.huawei.ascend.sit.cases.integration.react_travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.A2aStreamErrors;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.client.TaskTextExtractor;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.transport.MessageProtocol;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.A2AClientException;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
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
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

/**
 * FEAT-001 ReactAgent standardized service-entrypoint acceptance tests.
 *
 * <p>The SUT is always exercised through its public HTTP/A2A surface. Test-owned HTTP servers act
 * only as external LLM or A2A peers; no runtime class, handler, store, or SPI is accessed.</p>
 */
@Tag("feat-001")
@Tag("integration")
@Tag("openjiuwen")
@Feature("FEAT-001: 标准化智能体服务入口")
@Execution(ExecutionMode.SAME_THREAD)
class ReactAgentStandardizedEntrypointBlackboxTest extends BaseManagedStackTest {

    private static final String MAINPLAN = "mainplan";
    private static final String TRIP = "trip";
    private static final String HOTEL = "hotel";

    private static final String STANDARD_CARD = "/.well-known/agent-card.json";
    private static final String LEGACY_CARD = "/.well-known/agent.json";
    private static final String PREFIXED_CARD = "/a2a/.well-known/agent-card.json";
    private static final String A2A_PATH = "/a2a";
    private static final String CALLBACK_PATH = "/a2a/push-notifications/callback";
    private static final String PUSH_NOTIFICATIONS_PROPERTY =
            "openjiuwen.service.a2a.push-notifications";
    private static final String CALLBACK_TOKEN = "feat001-callback-token";

    private static final Duration SHORT_HTTP_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration CALLBACK_QUIET_PERIOD = Duration.ofSeconds(2);
    private static final long FLOW_TIMEOUT_MS = 300_000L;
    private static final int JSON_RPC_PARSE_ERROR = -32700;
    private static final int JSON_RPC_INVALID_REQUEST = -32600;
    private static final int JSON_RPC_METHOD_NOT_FOUND = -32601;
    private static final int JSON_RPC_INVALID_PARAMS = -32602;
    private static final int A2A_TASK_NOT_FOUND = -32001;
    private static final String CONFIGURED_DESCRIPTION = "FEAT-001 ReactAgent black-box fixture";
    private static final String CALLBACK_LARGE_PAYLOAD_PROMPT =
            "张三从上海到北京出差，2026年8月18日出发，行程3天、住宿2晚，"
                    + "酒店每晚不超过800元，优先国贸附近。"
                    + "请生成包含每日行程、酒店、交通和费用汇总的完整详细方案。";

    private static final String TURN_1 =
            "我要去北京出差。出发地和行程天数都还没定，请先追问缺失信息，不要直接规划。";
    private static final String TURN_2 =
            "出差3天，下周二出发。出发城市还没定，请继续追问，不要调用行程规划。";
    private static final String TURN_3 =
            "从上海出发。住宿2晚，每晚不超过800元，偏好国贸附近，请完成行程规划。";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        int configuredPort = config.getInt("sut.agents." + MAINPLAN + ".port", 0);
        return SutStack.builder(config)
                .streaming(true)
                .agent(MAINPLAN, agent -> {
                    agent.property("openjiuwen.service.a2a.agent-description", CONFIGURED_DESCRIPTION);
                    if (configuredPort > 0) {
                        agent.property(
                                "openjiuwen.service.a2a.public-url",
                                "http://127.0.0.1:" + configuredPort);
                    }
                });
    }

    @Test
    @Tag("blackbox")
    @Tag("smoke")
    @Story("FEAT-001.entry.discovery: Agent Card 发现与能力真实性")
    @DisplayName("Feat-001 三个发现入口返回完整、可调用且能力声明真实的 Agent Card")
    void feat001DiscoveryEndpointsExposeOneTruthfulCard() throws Exception {
        List<HttpResponse<String>> responses = List.of(
                get(MAINPLAN, STANDARD_CARD),
                get(MAINPLAN, LEGACY_CARD),
                get(MAINPLAN, PREFIXED_CARD));

        List<JsonNode> availableCards = new ArrayList<>();
        for (HttpResponse<String> response : responses) {
            assertThat(response.statusCode()).as("Agent Card HTTP status").isIn(200, 400, 404);
            if (response.statusCode() == 200) {
                assertThat(mediaType(response)).as("Agent Card media type").isEqualTo("application/json");
                availableCards.add(mapper.readTree(response.body()));
            }
        }
        assertThat(availableCards).as("at least one available Agent Card endpoint").isNotEmpty();
        assertThat(availableCards).allMatch(availableCards.get(0)::equals);

        AgentCard card = client(MAINPLAN).getAgentCard();
        assertThat(card.name()).as("card.name").isNotBlank();
        assertThat(card.description()).as("card.description").isNotBlank();
        assertThat(card.version()).as("card.version").isNotBlank();
        assertThat(card.capabilities()).as("card.capabilities").isNotNull();
        assertThat(card.capabilities().streaming()).as("capabilities.streaming").isTrue();
        assertThat(card.capabilities().pushNotifications())
                .as("default deployment must not advertise unavailable callback behavior")
                .isFalse();
        assertThat(card.capabilities().extendedAgentCard())
                .as("default deployment extendedAgentCard")
                .isFalse();
        assertThat(card.defaultInputModes()).as("defaultInputModes").isNotEmpty();
        assertThat(card.defaultOutputModes()).as("defaultOutputModes").isNotEmpty();
        assertThat(card.skills()).as("skills is present").isNotNull();
        assertSkillsAreWellFormed(card.skills());

        List<AgentInterface> jsonRpc = card.supportedInterfaces().stream()
                .filter(it -> "JSONRPC".equals(it.protocolBinding()))
                .toList();
        assertThat(jsonRpc).as("JSONRPC supported interface").isNotEmpty();
        for (AgentInterface agentInterface : jsonRpc) {
            URI endpoint = URI.create(agentInterface.url());
            assertThat(endpoint.isAbsolute()).as("JSONRPC endpoint is absolute").isTrue();
            assertThat(endpoint.getScheme()).isIn("http", "https");
            assertThat(endpoint.getHost()).isNotBlank();
            assertThat(endpoint.getPath()).endsWith(A2A_PATH);
        }

        assertThat(card.supportedInterfaces())
                .allSatisfy(agentInterface -> assertThat(agentInterface.protocolBinding()).isEqualTo("JSONRPC"));
        int port = config.getInt("sut.agents." + MAINPLAN + ".port", 0);
        assertThat(port)
                .as("openjiuwen mainplan uses a fixed port so public-url can be asserted")
                .isPositive();
        String publicBase = "http://127.0.0.1:" + port;
        assertThat(card.description()).isEqualTo(CONFIGURED_DESCRIPTION);

        AgentInterface configuredJsonRpc = card.supportedInterfaces().stream()
                .filter(it -> "JSONRPC".equals(it.protocolBinding()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("configured card has no JSONRPC interface"));
        assertThat(configuredJsonRpc.url()).isEqualTo(publicBase + A2A_PATH);

        String requestId = "public-url-" + shortId();
        JsonNode response = mapper.readTree(postAbsolute(
                configuredJsonRpc.url(),
                getTaskRequest(requestId, UUID.randomUUID().toString()),
                SHORT_HTTP_TIMEOUT).body());
        assertJsonRpcEnvelope(response, requestId);
    }

    @Test
    @Tag("blackbox")
    @Tag("smoke")
    @Story("FEAT-001.entry.jsonrpc: 统一入口、标准错误与明确 OUT 边界")
    @DisplayName("Feat-001 /a2a 统一分发并返回标准 JSON-RPC 错误和 unsupported 边界")
    void feat001JsonRpcEntrypointAndErrorsFollowContract() throws Exception {
        String requestId = "slash-" + shortId();
        String request = getTaskRequest(requestId, UUID.randomUUID().toString());

        HttpResponse<String> noSlash = post(MAINPLAN, A2A_PATH, request, SHORT_HTTP_TIMEOUT);
        HttpResponse<String> withSlash = post(MAINPLAN, A2A_PATH + "/", request, SHORT_HTTP_TIMEOUT);

        assertThat(noSlash.statusCode()).isIn(200, 400, 404);
        assertThat(withSlash.statusCode()).isIn(200, 400, 404);
        Optional<JsonNode> first = parseJsonRpcResponseUnlessNotFound(noSlash, requestId);
        Optional<JsonNode> second = parseJsonRpcResponseUnlessNotFound(withSlash, requestId);
        if (first.isPresent() && second.isPresent()) {
            assertThat(first.get().has("error")).isEqualTo(second.get().has("error"));
            assertThat(first.get().path("error").path("code").asInt())
                    .isEqualTo(second.get().path("error").path("code").asInt());
        }

        for (JsonRpcErrorCase testCase : jsonRpcErrorCases()) {
            HttpResponse<String> response = post(
                    MAINPLAN, A2A_PATH, testCase.body(), SHORT_HTTP_TIMEOUT);
            assertThat(response.statusCode())
                    .as("%s HTTP status\nbody: %s", testCase.name(), response.body())
                    .isIn(200, 400, 404);
            if (response.statusCode() == 404) {
                continue;
            }
            JsonNode body = mapper.readTree(response.body());
            assertThat(body.path("jsonrpc").asText()).isEqualTo("2.0");
            assertThat(body.path("error").path("code").asInt())
                    .as("%s error code\nbody: %s", testCase.name(), response.body())
                    .isEqualTo(testCase.expectedCode());
            assertThat(body.has("result")).isFalse();
            if (testCase.expectedId() == null) {
                assertThat(body.has("id")).isTrue();
                assertThat(body.path("id").isNull()).isTrue();
            } else {
                assertThat(body.path("id").asText()).isEqualTo(testCase.expectedId());
            }
        }

        for (String method : unsupportedPushConfigMethods()) {
            String id = "push-crud-" + shortId();
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("jsonrpc", "2.0");
            envelope.put("id", id);
            envelope.put("method", method);
            envelope.put("params", Map.of(
                    "id", "missing-task-" + shortId(),
                    "pushNotificationConfigId", "missing-config-" + shortId()));
            JsonNode body = mapper.readTree(post(
                    MAINPLAN, A2A_PATH, mapper.writeValueAsString(envelope), SHORT_HTTP_TIMEOUT).body());
            assertJsonRpcEnvelope(body, id);
            assertThat(body.path("error").path("code").asInt())
                    .as("%s is outside the current service surface", method)
                    .isEqualTo(JSON_RPC_METHOD_NOT_FOUND);
        }

        assertThat(get(MAINPLAN, STANDARD_CARD).statusCode())
                .as("SUT remains healthy after invalid requests")
                .isEqualTo(200);
    }

    @Test
    @Tag("blackbox")
    @Tag("smoke")
    @Story("FEAT-001.message.blocking-query: 阻塞消息、上下文、查询与有界等待")
    @DisplayName("Feat-001 阻塞 SendMessage 与 GetTask 构成可关联且有界的 Task 契约")
    void feat001BlockingSendAndTaskQueryFollowContract() throws Exception {
        A2aServiceClient a2a = client(MAINPLAN);
        long timeoutMs = pollTimeoutMs();

        Message firstMessage = userMessage("你好，请简短回复。", null);
        SendObservation first = send(a2a, firstMessage, false, timeoutMs);
        assertSuccessfulObservation("first blocking send", first);
        assertThat(first.task().contextId()).as("server-assigned contextId").isNotBlank();

        Message secondMessage = userMessage("请继续简短回复。", first.task().contextId());
        SendObservation second = send(a2a, secondMessage, false, timeoutMs);
        assertSuccessfulObservation("second blocking send", second);
        assertThat(second.task().contextId()).isEqualTo(first.task().contextId());
        assertThat(second.task().id()).isNotEqualTo(first.task().id());

        Task queriedOnce = a2a.getTask(first.task().id());
        Task queriedTwice = a2a.getTask(first.task().id());
        assertThat(queriedOnce.id()).isEqualTo(first.task().id());
        assertThat(queriedOnce.contextId()).isEqualTo(first.task().contextId());
        assertThat(queriedOnce.status().state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertThat(queriedTwice.status().state()).isEqualTo(queriedOnce.status().state());
        assertThat(TaskTextExtractor.textOf(queriedOnce)).isEqualTo(TaskTextExtractor.textOf(queriedTwice));
        assertThat(TaskTextExtractor.textOf(queriedOnce)).isNotBlank();

        String requestId = "task-not-found-" + shortId();
        HttpResponse<String> response = post(
                MAINPLAN,
                A2A_PATH,
                getTaskRequest(requestId, UUID.randomUUID().toString()),
                SHORT_HTTP_TIMEOUT);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode envelope = mapper.readTree(response.body());
        assertJsonRpcEnvelope(envelope, requestId);
        assertThat(envelope.has("result")).isFalse();
        assertThat(envelope.path("error").path("code").asInt()).isEqualTo(A2A_TASK_NOT_FOUND);
        assertThat(envelope.path("error").path("message").asText())
                .containsIgnoringCase("task not found");
        assertThat(get(MAINPLAN, STANDARD_CARD).statusCode())
                .as("SUT remains healthy after GetTask miss")
                .isEqualTo(200);

        String traceId = "trace-feat001-" + shortId();
        String correlatedId = "correlation-" + shortId();
        JsonNode correlated = mapper.readTree(post(
                MAINPLAN,
                A2A_PATH,
                sendRequest(
                        "SendMessage", correlatedId, "你好，请简短回复。", null,
                        Map.of("traceId", traceId, "agentId", MAINPLAN), null),
                Duration.ofMinutes(3)).body());
        assertJsonRpcEnvelope(correlated, correlatedId);
        String correlatedTaskId = requireTaskId(correlated);
        JsonNode correlatedSnapshot = getTaskSnapshot(stack.baseUrl(MAINPLAN), correlatedTaskId);
        boolean responseCarriesTrace = (correlated + "\n" + correlatedSnapshot).contains(traceId);
        assertThat(responseCarriesTrace || awaitLogContainsAny(traceId, correlatedTaskId))
                .as("trace or task id remains externally correlatable")
                .isTrue();

        stack.stop(MAINPLAN);
        try {
            try (DeterministicDelegatingLlmPeer llm = DeterministicDelegatingLlmPeer.start();
                 DelayedCallbackA2aPeer downstream = DelayedCallbackA2aPeer.start();
                 SutStack bounded = SutStack.builder(config)
                         .remoteAgent(TRIP, downstream.baseUrl())
                         .streaming(false)
                         .agent(MAINPLAN, agent -> {
                             agent.downstream(TRIP);
                             applyLlmOverride(agent, llm.baseUrl(), "feat001-callback-key");
                             agent.property(PUSH_NOTIFICATIONS_PROPERTY, "true");
                             agent.property("openjiuwen.service.a2a.task-completion-timeout-seconds", "2");
                         })
                         .start()) {
                String boundedId = "bounded-callback-" + shortId();
                String notificationId = "bounded-notification-" + shortId();
                Map<String, Object> callbackMetadata = Map.of(
                        "runtime.a2a.callbackUrl", bounded.baseUrl(MAINPLAN) + CALLBACK_PATH,
                        "runtime.a2a.callbackToken", CALLBACK_TOKEN,
                        "runtime.a2a.callbackId", notificationId);

                long started = System.nanoTime();
                HttpResponse<String> boundedResponse;
                try {
                    boundedResponse = postAbsolute(
                            bounded.baseUrl(MAINPLAN) + A2A_PATH,
                            sendRequest(
                                    "SendMessage",
                                    boundedId,
                                    "FEAT001_TRIGGER_DELAYED_DOWNSTREAM_CALLBACK",
                                    null,
                                    callbackMetadata,
                                    null),
                            Duration.ofSeconds(12));
                } catch (java.net.http.HttpTimeoutException timeout) {
                    long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
                    throw new AssertionError(
                            "configured task-completion timeout did not return a queryable Task within "
                                    + elapsedMs + " ms",
                            timeout);
                }
                long responseReceivedAt = System.nanoTime();
                long elapsedMs = Duration.ofNanos(responseReceivedAt - started).toMillis();

                DelayedCallbackA2aPeer.OutboundRequest delegated =
                        downstream.awaitRequest(Duration.ofSeconds(5));
                assertThat(llm.toolCallCount()).as("mainplan delegated through the travel-trip tool").isPositive();
                assertThat(delegated.remoteInput()).isEqualTo("FEAT001_DELAYED_CALLBACK_REQUEST");
                assertThat(delegated.callbackUrl()).isEqualTo(bounded.baseUrl(MAINPLAN) + CALLBACK_PATH);
                assertThat(delegated.callbackToken()).isEqualTo(CALLBACK_TOKEN);
                assertThat(delegated.notificationId()).isEqualTo(notificationId);
                assertThat(elapsedMs)
                        .as("bounded SendMessage duration")
                        .isLessThan(12_000L);
                assertThat(Duration.ofNanos(responseReceivedAt - delegated.acceptedAtNanos()).toMillis())
                        .as("SendMessage remains pending after downstream accepts callback-mode work")
                        .isBetween(1_200L, 8_000L);

                assertThat(boundedResponse.statusCode()).isEqualTo(200);
                JsonNode boundedEnvelope = mapper.readTree(boundedResponse.body());
                assertJsonRpcEnvelope(boundedEnvelope, boundedId);
                assertThat(boundedEnvelope.hasNonNull("error"))
                        .as("bounded wait expiry returns a Task rather than a JSON-RPC error: %s", boundedEnvelope)
                        .isFalse();
                String parentTaskId = requireTaskId(boundedEnvelope);
                assertThat(terminalStateOf(boundedEnvelope))
                        .as("bounded wait expiry must not fabricate a terminal Task")
                        .isEmpty();

                JsonNode waitingSnapshot = getTaskSnapshot(bounded.baseUrl(MAINPLAN), parentTaskId);
                assertThat(requireTaskId(waitingSnapshot)).isEqualTo(parentTaskId);
                assertThat(terminalStateOf(waitingSnapshot)).isEmpty();

                HttpResponse<String> callbackResponse = downstream.sendCompletedCallback(
                        "DELAYED_CALLBACK_RESULT");
                assertThat(callbackResponse.statusCode())
                        .as("late downstream callback response: %s", callbackResponse.body())
                        .isBetween(200, 299);

                await().atMost(pollTimeout()).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
                    JsonNode completed = getTaskSnapshot(bounded.baseUrl(MAINPLAN), parentTaskId);
                    assertThat(requireTaskId(completed)).isEqualTo(parentTaskId);
                    assertThat(terminalStateOf(completed)).contains("COMPLETED");
                    assertThat(completed.toString()).contains("DELAYED_CALLBACK_RESULT");
                });
            }
        } finally {
            stack.start(MAINPLAN);
        }
    }

    @Test
    @Tag("blackbox")
    @Tag("smoke")
    @Story("FEAT-001.message.streaming-lifecycle: SSE 生命周期与交互中断")
    @DisplayName("Feat-001 流式消息按 JSON-RPC SSE 呈现生命周期并支持 INPUT_REQUIRED 续接")
    void feat001StreamingLifecycleAndInputRequiredFollowContract() throws Exception {
        String requestId = "stream-" + shortId();
        String request = sendRequest("SendStreamingMessage", requestId, "你好，请简短回复。", null);

        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(stack.baseUrl(MAINPLAN) + A2A_PATH))
                .timeout(Duration.ofMillis(Math.max(pollTimeoutMs(), 180_000L)))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(request))
                .build();
        HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mediaType(response)).isEqualTo("text/event-stream");
        List<SseFrame> frames = parseSse(response.body());
        assertThat(frames).isNotEmpty();
        assertThat(frames).allSatisfy(frame -> assertThat(frame.event()).isEqualTo("jsonrpc"));

        List<String> states = new ArrayList<>();
        for (SseFrame frame : frames) {
            JsonNode envelope = mapper.readTree(frame.data());
            assertJsonRpcEnvelope(envelope, requestId);
            assertThat(envelope.hasNonNull("result")).isTrue();
            collectStateValues(envelope.path("result"), states);
        }
        List<String> normalized = states.stream().map(ReactAgentStandardizedEntrypointBlackboxTest::normalizeState)
                .filter(state -> !state.isBlank())
                .distinct()
                .toList();
        assertStateOrder(normalized, "SUBMITTED", "WORKING", "COMPLETED");

        String marker = "feat001-session-" + shortId();
        stack.stop(MAINPLAN);
        try {
            try (SutStack fullChain = SutStack.builder(config)
                    .streaming(true)
                    .agent(HOTEL)
                    .agent(TRIP, agent -> agent.downstream(HOTEL))
                    .agent(MAINPLAN, agent -> agent.downstream(TRIP))
                    .start()) {
                InteractionFlow.FlowResult result = InteractionFlow.of(fullChain.client(MAINPLAN))
                        .protocol(MessageProtocol.A2A_STREAM)
                        .withTimeoutMs(FLOW_TIMEOUT_MS)
                        .withMetadata(Map.of("sessionId", marker, "agentId", MAINPLAN))
                        .send(TURN_1)
                            .awaitState(TaskState.TASK_STATE_INPUT_REQUIRED)
                            .assertGenerated(text -> assertThat(text).as("turn 1 clarification").isNotBlank())
                        .send(TURN_2)
                            .awaitState(TaskState.TASK_STATE_INPUT_REQUIRED)
                            .assertGenerated(text -> assertThat(text).as("turn 2 clarification").isNotBlank())
                        .send(TURN_3)
                            .awaitState(TaskState.TASK_STATE_COMPLETED)
                            .assertGenerated(text -> assertThat(text).as("turn 3 result").isNotBlank())
                        .execute();

                assertThat(result.roundCount()).isEqualTo(3);
                String contextId = result.round(0).contextId();
                assertThat(contextId).isNotBlank();
                assertThat(result.round(1).contextId()).isEqualTo(contextId);
                assertThat(result.round(2).contextId()).isEqualTo(contextId);
            }
        } finally {
            stack.start(MAINPLAN);
        }
    }

    @Test
    @Tag("blackbox")
    @Tag("fault")
    @Story("FEAT-001.message.failure: 外部执行失败的 A2A 表面")
    @DisplayName("Feat-001 LLM 不可用时同步和流式调用返回 FAILED Task 或结构化内部错误且服务存活")
    void feat001ExecutionFailuresUseObservableTaskSurface() {
        stack.stop(MAINPLAN);
        try {
            try (SutStack failed = SutStack.builder(config)
                    .streaming(true)
                    .agent(MAINPLAN, agent -> applyLlmOverride(
                            agent, "http://127.0.0.1:9/v1", "feat001-invalid-key"))
                    .start()) {
                A2aServiceClient a2a = failed.client(MAINPLAN);
                SendObservation sync = send(a2a, userMessage("你好", null), false, 120_000L);
                SendObservation stream = send(a2a, userMessage("你好", null), true, 120_000L);

                assertFailedObservation("sync", sync);
                assertFailedObservation("stream", stream);
                assertThat(a2a.refreshAgentCard()).as("Agent Card after failed executions").isNotNull();
            }
        } finally {
            stack.start(MAINPLAN);
        }
    }

    @Test
    @Tag("blackbox")
    @Tag("callback")
    @Story("FEAT-001.callback.delivery: 点对点异步完成通知")
    @DisplayName("Feat-001 inline callback 先接受 Task 再投递标准终态结果且与 streaming 分离")
    void feat001CallbackDeliversOnlyStandardTerminalResults() throws Exception {
        try (CallbackReceiver callback = CallbackReceiver.responding(200)) {
            withCallbackEnabledMainplan(agent -> {}, callbackStack -> {
                String requestId = "callback-accepted-" + shortId();
                long started = System.nanoTime();
                JsonNode response = postJson(
                        callbackStack.baseUrl(MAINPLAN) + A2A_PATH,
                        callbackSendRequest(
                                requestId, "你好，请简短回复。", callback.callbackUrl(), CALLBACK_TOKEN),
                        Duration.ofMinutes(2));
                long acceptedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

                assertJsonRpcEnvelope(response, requestId);
                assertThat(response.hasNonNull("error")).as("callback-mode SendMessage").isFalse();
                String taskId = requireTaskId(response);
                assertThat(terminalStateOf(response))
                        .as("initial callback-mode response must be accepted/non-terminal: %s", response)
                        .isEmpty();

                JsonNode immediate = getTaskSnapshot(callbackStack.baseUrl(MAINPLAN), taskId);
                assertThat(immediate.hasNonNull("result"))
                        .as("accepted Task is queryable immediately; acceptedMillis=%s", acceptedMillis)
                        .isTrue();

                CallbackRequest delivered = callback.awaitRequest(pollTimeout());
                assertThat(terminalStateOf(delivered.json(mapper))).contains("COMPLETED");
                assertThat(requireNotificationId(delivered)).isNotBlank();
                assertThat(delivered.json(mapper).path("jsonrpc").asText()).isEqualTo("2.0");
                assertThat(delivered.json(mapper).hasNonNull("result")).isTrue();
                assertThat(delivered.json(mapper).has("payloadRef")).isFalse();
                assertThat(findFirstText(delivered.json(mapper).path("result"))).isNotBlank();
                callback.assertRequestCount(1);
                assertTerminalTaskEventually(callbackStack.baseUrl(MAINPLAN), taskId, "COMPLETED");

                String streamId = "stream-no-callback-" + shortId();
                HttpResponse<String> stream = postSseAbsolute(
                        callbackStack.baseUrl(MAINPLAN) + A2A_PATH,
                        sendRequest("SendStreamingMessage", streamId, "你好，请简短回复。", null),
                        Duration.ofMinutes(3));
                assertThat(mediaType(stream)).isEqualTo("text/event-stream");
                assertThat(parseSse(stream.body())).isNotEmpty();
                callback.assertRequestCountRemains(1, CALLBACK_QUIET_PERIOD);

                postAcceptedCallbackSend(callbackStack, callback, CALLBACK_LARGE_PAYLOAD_PROMPT);
                callback.awaitCount(2, pollTimeout());
                JsonNode largeBody = callback.requests().get(1).json(mapper);
                assertThat(largeBody.hasNonNull("result")).isTrue();
                assertThat(largeBody.has("payloadRef") || largeBody.has("contentUrl")).isFalse();
                assertThat(containsNonEmptyStandardPayload(largeBody.path("result")))
                        .as("large result uses artifact/file/data/metadata/reference surface: %s", largeBody)
                        .isTrue();
            });
        }

        try (CallbackReceiver failed = CallbackReceiver.responding(200)) {
            withCallbackEnabledMainplan(agent -> applyLlmOverride(
                    agent, "http://127.0.0.1:9/v1", "feat001-invalid-key"), callbackStack -> {
                postAcceptedCallbackSend(callbackStack, failed, "你好");
                failed.awaitCount(1, pollTimeout());
                failed.assertExactlyOneTerminal("FAILED", mapper);
                assertThat(failed.requests().get(0).body())
                        .as("failed callback exposes a client-visible reason")
                        .isNotBlank();
            });
        }
    }

    @Test
    @Tag("blackbox")
    @Tag("callback")
    @Tag("fault")
    @Story("FEAT-001.callback.security-failure: 能力门控、安全拒绝与投递失败")
    @DisplayName("Feat-001 callback 对关闭能力、非法 URL 和投递失败保持安全且不改变 Task 终态")
    void feat001CallbackSecurityAndDeliveryFailureFollowContract() throws Exception {
        assertThat(client(MAINPLAN).getAgentCard().capabilities().pushNotifications()).isFalse();
        assertThat(post(MAINPLAN, CALLBACK_PATH, "{}", SHORT_HTTP_TIMEOUT).statusCode()).isIn(404, 501);

        try (CallbackReceiver callback = CallbackReceiver.responding(200)) {
            String disabledId = "disabled-callback-" + shortId();
            JsonNode disabled = mapper.readTree(post(
                    MAINPLAN,
                    A2A_PATH,
                    callbackSendRequest(disabledId, "此请求不得异步执行。", callback.callbackUrl(), CALLBACK_TOKEN),
                    SHORT_HTTP_TIMEOUT).body());
            assertJsonRpcEnvelope(disabled, disabledId);
            assertThat(disabled.hasNonNull("error")).isTrue();
            callback.assertNoRequests(CALLBACK_QUIET_PERIOD);

            withCallbackEnabledMainplan(agent -> {}, callbackStack -> {
                String requestId = "invalid-callback-url-" + shortId();
                JsonNode rejected = postJson(
                        callbackStack.baseUrl(MAINPLAN) + A2A_PATH,
                        callbackSendRequest(
                                requestId,
                                "此请求不得执行。",
                                "ftp://callback.invalid" + CALLBACK_PATH,
                                CALLBACK_TOKEN),
                        SHORT_HTTP_TIMEOUT);
                assertJsonRpcEnvelope(rejected, requestId);
                assertThat(rejected.hasNonNull("error"))
                        .as("non-HTTP callback URL is rejected before Task creation")
                        .isTrue();
                callback.assertNoRequests(CALLBACK_QUIET_PERIOD);

                HttpResponse<String> malformed = postAbsolute(
                        callbackStack.baseUrl(MAINPLAN) + CALLBACK_PATH,
                        "{}",
                        SHORT_HTTP_TIMEOUT,
                        Map.of("X-A2A-Notification-Id", "malformed-" + shortId()));
                assertThat(malformed.statusCode()).isIn(400, 401, 403, 404, 409);

                String notificationId = "mismatch-" + shortId();
                HttpResponse<String> mismatch = postAbsolute(
                        callbackStack.baseUrl(MAINPLAN) + CALLBACK_PATH,
                        unknownBindingCallback("remote-" + shortId(), notificationId + "-body"),
                        SHORT_HTTP_TIMEOUT,
                        Map.of("X-A2A-Notification-Id", notificationId));
                assertThat(mismatch.statusCode()).isIn(400, 401, 403, 409);
            });
        }

        try (CallbackReceiver callback = CallbackReceiver.responding(500)) {
            withCallbackEnabledMainplan(agent -> {}, callbackStack -> {
                JsonNode accepted = postAcceptedCallbackSend(
                        callbackStack, callback, "你好，请简短回复投递失败测试结果。");
                String taskId = requireTaskId(accepted);
                callback.awaitCount(1, pollTimeout());
                assertTerminalTaskEventually(callbackStack.baseUrl(MAINPLAN), taskId, "COMPLETED");

                List<CallbackRequest> observedAttempts = callback.requests();
                if (observedAttempts.size() > 1) {
                    assertThat(observedAttempts.stream().map(this::requireNotificationId).distinct().count())
                            .as("observed retries keep one notification id")
                            .isEqualTo(1);
                    assertThat(observedAttempts.stream().map(request -> request.json(mapper)).distinct().count())
                            .as("observed retries keep an equivalent payload")
                            .isEqualTo(1);
                }
            });
        }
    }

    private long pollTimeoutMs() {
        return Math.max(config.getPollTimeoutSeconds() * 1000L, 120_000L);
    }

    private Duration pollTimeout() {
        return Duration.ofMillis(pollTimeoutMs());
    }

    private static List<JsonRpcErrorCase> jsonRpcErrorCases() {
        return List.of(
                new JsonRpcErrorCase("parse error", "{not-json", JSON_RPC_PARSE_ERROR, null),
                new JsonRpcErrorCase("non-object request", "[]", JSON_RPC_INVALID_REQUEST, null),
                new JsonRpcErrorCase(
                        "missing method", "{\"jsonrpc\":\"2.0\",\"id\":\"missing-method\"}",
                        JSON_RPC_INVALID_REQUEST, "missing-method"),
                new JsonRpcErrorCase(
                        "wrong jsonrpc version",
                        "{\"jsonrpc\":\"1.0\",\"id\":\"wrong-version\",\"method\":\"GetTask\","
                                + "\"params\":{\"id\":\"missing\"}}",
                        JSON_RPC_INVALID_REQUEST, "wrong-version"),
                new JsonRpcErrorCase(
                        "invalid params",
                        "{\"jsonrpc\":\"2.0\",\"id\":\"bad-params\","
                                + "\"method\":\"SendMessage\",\"params\":[]}",
                        JSON_RPC_INVALID_PARAMS, "bad-params"),
                new JsonRpcErrorCase(
                        "GetTask missing id",
                        "{\"jsonrpc\":\"2.0\",\"id\":\"missing-task-id\","
                                + "\"method\":\"GetTask\",\"params\":{}}",
                        JSON_RPC_INVALID_PARAMS, "missing-task-id"),
                new JsonRpcErrorCase(
                        "unknown method",
                        "{\"jsonrpc\":\"2.0\",\"id\":\"unknown-method\","
                                + "\"method\":\"NoSuchMethodEver\",\"params\":{}}",
                        JSON_RPC_METHOD_NOT_FOUND, "unknown-method"));
    }

    private static List<String> unsupportedPushConfigMethods() {
        return List.of(
                "CreateTaskPushNotificationConfig",
                "GetTaskPushNotificationConfig",
                "ListTaskPushNotificationConfig",
                "UpdateTaskPushNotificationConfig",
                "DeleteTaskPushNotificationConfig");
    }

    private void withCallbackEnabledMainplan(
            Consumer<SutStack.AgentBuilder> extraConfiguration,
            ThrowingConsumer<SutStack> testBody) throws Exception {
        stack.stop(MAINPLAN);
        try (SutStack callbackStack = SutStack.builder(config)
                .streaming(true)
                .agent(MAINPLAN, agent -> {
                    configureCallbackAgent(agent);
                    extraConfiguration.accept(agent);
                })
                .start()) {
            assertCallbackCapability(callbackStack, MAINPLAN);
            testBody.accept(callbackStack);
        } finally {
            stack.start(MAINPLAN);
        }
    }

    private static void configureCallbackAgent(SutStack.AgentBuilder agent) {
        agent.property(PUSH_NOTIFICATIONS_PROPERTY, "true");
    }

    private static void assertCallbackCapability(SutStack callbackStack, String agent) {
        assertThat(callbackStack.client(agent).getAgentCard().capabilities().pushNotifications())
                .withFailMessage(
                        "FEAT-001 callback profile is not publicly usable for '%s'. The test enables only "
                                + "the documented %s property; sender, receiver, store/handler and trust policy "
                                + "must be provided by the travel-demo deployment profile.",
                        agent, PUSH_NOTIFICATIONS_PROPERTY)
                .isTrue();
    }

    private JsonNode postAcceptedCallbackSend(
            SutStack callbackStack,
            CallbackReceiver callback,
            String text) throws Exception {
        String requestId = "callback-send-" + shortId();
        JsonNode response = postJson(
                callbackStack.baseUrl(MAINPLAN) + A2A_PATH,
                callbackSendRequest(requestId, text, callback.callbackUrl(), CALLBACK_TOKEN),
                Duration.ofMinutes(2));
        assertJsonRpcEnvelope(response, requestId);
        assertThat(response.hasNonNull("error")).as("callback-mode SendMessage response: %s", response).isFalse();
        requireTaskId(response);
        return response;
    }

    private String callbackSendRequest(
            String requestId,
            String text,
            String callbackUrl,
            String token) throws Exception {
        Map<String, Object> pushConfig = new LinkedHashMap<>();
        pushConfig.put("id", "push-" + shortId());
        pushConfig.put("callbackUrl", callbackUrl);
        pushConfig.put("authentication", Map.of("scheme", "Bearer", "credentials", token));

        return sendRequest(
                "SendMessage",
                requestId,
                text,
                null,
                Map.of("callerRuntime", "agent-runtime-acceptance", "traceId", "trace-" + shortId()),
                pushConfig);
    }

    private String unknownBindingCallback(String remoteTaskId, String notificationId) throws Exception {
        Map<String, Object> statusMessage = Map.of(
                "role", "ROLE_AGENT",
                "messageId", "callback-message-" + shortId(),
                "parts", List.of(Map.of("text", "FEAT001_REMOTE_RESULT")));
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", remoteTaskId);
        task.put("contextId", "remote-context-" + shortId());
        task.put("status", Map.of(
                "state", "TASK_STATE_COMPLETED",
                "message", statusMessage,
                "timestamp", Instant.now().toString()));
        task.put("artifacts", List.of());
        task.put("history", List.of());
        task.put("metadata", Map.of());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", "callback-" + shortId());
        envelope.put("result", task);
        envelope.put("notificationId", notificationId);
        return mapper.writeValueAsString(envelope);
    }

    private JsonNode getTaskSnapshot(String baseUrl, String taskId) throws Exception {
        String requestId = "get-task-" + shortId();
        JsonNode response = postJson(
                baseUrl + A2A_PATH,
                getTaskRequest(requestId, taskId),
                SHORT_HTTP_TIMEOUT);
        assertJsonRpcEnvelope(response, requestId);
        return response;
    }

    private void assertTerminalTaskEventually(String baseUrl, String taskId, String expectedState) {
        await().atMost(pollTimeout()).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            JsonNode snapshot = getTaskSnapshot(baseUrl, taskId);
            assertThat(terminalStateOf(snapshot)).contains(expectedState);
        });
    }

    private static Optional<String> terminalStateOf(JsonNode node) {
        List<String> states = new ArrayList<>();
        collectStateValues(node, states);
        return states.stream()
                .map(ReactAgentStandardizedEntrypointBlackboxTest::normalizeState)
                .filter(state -> Set.of("COMPLETED", "FAILED", "CANCELED", "REJECTED").contains(state))
                .reduce((first, second) -> second);
    }

    private static String requireTaskId(JsonNode envelope) {
        JsonNode result = envelope.path("result");
        for (JsonNode candidate : List.of(
                result.path("id"),
                result.path("task").path("id"),
                result.path("taskId"))) {
            String value = candidate.asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        throw new AssertionError("JSON-RPC result does not expose a Task id: " + envelope);
    }

    private String requireNotificationId(CallbackRequest request) {
        String header = request.firstHeader("x-a2a-notification-id").orElse("");
        String body = request.json(mapper).path("notificationId").asText("");
        String notificationId = !header.isBlank() ? header : body;
        assertThat(notificationId).as("callback notification id").isNotBlank();
        if (!header.isBlank() && !body.isBlank()) {
            assertThat(body).as("header/body notification id consistency").isEqualTo(header);
        }
        return notificationId;
    }

    private static boolean containsNonEmptyStandardPayload(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (Set.of("artifacts", "file", "data", "metadata").contains(field.getKey())
                        && !field.getValue().isNull()
                        && (!field.getValue().isContainerNode() || !field.getValue().isEmpty())) {
                    return true;
                }
                if (containsNonEmptyStandardPayload(field.getValue())) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsNonEmptyStandardPayload(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String findFirstText(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (("text".equals(field.getKey()) || "content".equals(field.getKey()))
                        && field.getValue().isTextual()
                        && !field.getValue().asText().isBlank()) {
                    return field.getValue().asText();
                }
                String nested = findFirstText(field.getValue());
                if (!nested.isBlank()) {
                    return nested;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String nested = findFirstText(child);
                if (!nested.isBlank()) {
                    return nested;
                }
            }
        }
        return "";
    }

    private boolean awaitLogContainsAny(String... values) {
        if (!(stack.managedInstance(MAINPLAN) instanceof ManagedSutInstance managed)) {
            return false;
        }
        AtomicReference<Boolean> found = new AtomicReference<>(false);
        try {
            await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(250)).until(() -> {
                String log = Files.exists(managed.logFile()) ? Files.readString(managed.logFile()) : "";
                boolean present = Arrays.stream(values).anyMatch(log::contains);
                found.set(present);
                return present;
            });
        } catch (RuntimeException timeout) {
            return found.get();
        }
        return found.get();
    }

    private SendObservation send(A2aServiceClient a2a, Message message, boolean streaming, long timeoutMs) {
        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> error = new AtomicReference<>();
        try {
            if (streaming) {
                a2a.sendMessageStreaming(message, null, null,
                        List.of(collector.createConsumer()), error::set);
            } else {
                a2a.sendMessageSync(message, null, null, null,
                        List.of(collector.createConsumer()), error::set);
            }
        } catch (RuntimeException protocolError) {
            error.compareAndSet(null, protocolError);
        }

        await().atMost(Duration.ofMillis(timeoutMs))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> collector.findTerminalEvent().isPresent()
                        || hasNonBenignError(error.get()));

        Task task = collector.findTerminalEvent()
                .flatMap(ReactAgentStandardizedEntrypointBlackboxTest::taskFrom)
                .orElse(null);
        TaskState terminal = task == null ? null : task.status().state();
        return new SendObservation(task, terminal, error.get());
    }

    private static boolean hasNonBenignError(Throwable error) {
        return error != null && !A2aStreamErrors.isBenignShutdown(error);
    }

    private static void assertSuccessfulObservation(String label, SendObservation observation) {
        assertNoUnexpectedTransportError(label, observation.error());
        assertThat(observation.task()).as(label + " task").isNotNull();
        assertThat(observation.terminal()).as(label + " terminal").isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertThat(observation.task().id()).as(label + " taskId").isNotBlank();
        assertThat(TaskTextExtractor.textOf(observation.task())).as(label + " result text").isNotBlank();
    }

    private static void assertFailedObservation(String label, SendObservation observation) {
        if (observation.task() == null) {
            assertStructuredInternalError(label, observation.error());
            return;
        }

        assertNoUnexpectedTransportError(label, observation.error());
        assertThat(observation.terminal()).as(label + " terminal").isEqualTo(TaskState.TASK_STATE_FAILED);
        assertThat(observation.task().id()).as(label + " taskId").isNotBlank();
        assertThat(observation.task().status()).as(label + " status").isNotNull();
        assertThat(observation.task().status().message()).as(label + " failure message").isNotNull();

        String publicError = TaskTextExtractor.fullSnapshotTextOf(observation.task());
        Map<String, Object> metadata = observation.task().status().message().metadata();
        assertThat(!publicError.isBlank() || (metadata != null && !metadata.isEmpty()))
                .as(label + " has a client-visible failure reason")
                .isTrue();
        assertThat(publicError)
                .as(label + " must not leak a Java stack trace")
                .doesNotContain("Caused by:", "\tat ", "java.lang.NullPointerException");
    }

    private static void assertStructuredInternalError(String label, Throwable error) {
        assertThat(error)
                .as(label + " protocol error")
                .isInstanceOf(A2AClientException.class);

        InternalError internalError = findCause(error, InternalError.class);
        assertThat(internalError)
                .as(label + " JSON-RPC internal error")
                .isNotNull();
        assertThat(internalError.getMessage())
                .as(label + " client-visible failure reason")
                .isNotBlank()
                .doesNotContain("Caused by:", "\tat ", "java.lang.NullPointerException");
    }

    private static <T extends Throwable> T findCause(Throwable error, Class<T> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private static void assertNoUnexpectedTransportError(String label, Throwable error) {
        if (error != null && !A2aStreamErrors.isBenignShutdown(error)) {
            fail(label + " transport failed", error);
        }
    }

    private static Message userMessage(String text, String contextId) {
        Message.Builder builder = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .parts(List.of(new TextPart(text)));
        if (contextId != null && !contextId.isBlank()) {
            builder.contextId(contextId);
        }
        return builder.build();
    }

    private String getTaskRequest(String requestId, String taskId) throws Exception {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", requestId);
        envelope.put("method", "GetTask");
        envelope.put("params", Map.of("id", taskId));
        return mapper.writeValueAsString(envelope);
    }

    private String sendRequest(
            String method,
            String requestId,
            String text,
            String contextId) throws Exception {
        return sendRequest(method, requestId, text, contextId, Map.of(), null);
    }

    private String sendRequest(
            String method,
            String requestId,
            String text,
            String contextId,
            Map<String, Object> metadata,
            Map<String, Object> pushNotificationConfig) throws Exception {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "ROLE_USER");
        message.put("messageId", UUID.randomUUID().toString());
        if (contextId != null && !contextId.isBlank()) {
            message.put("contextId", contextId);
        }
        message.put("parts", List.of(Map.of("text", text)));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("message", message);
        if (metadata != null && !metadata.isEmpty()) {
            params.put("metadata", metadata);
        }
        if (pushNotificationConfig != null && !pushNotificationConfig.isEmpty()) {
            params.put("pushNotificationConfig", pushNotificationConfig);
        }

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", requestId);
        envelope.put("method", method);
        envelope.put("params", params);
        return mapper.writeValueAsString(envelope);
    }

    private HttpResponse<String> get(String agent, String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(stack.baseUrl(agent) + path))
                        .timeout(SHORT_HTTP_TIMEOUT)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String agent, String path, String body, Duration timeout)
            throws Exception {
        return postAbsolute(stack.baseUrl(agent) + path, body, timeout);
    }

    private HttpResponse<String> postAbsolute(String url, String body, Duration timeout) throws Exception {
        return postAbsolute(url, body, timeout, Map.of());
    }

    private HttpResponse<String> postAbsolute(
            String url,
            String body,
            Duration timeout,
            Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json");
        headers.forEach(builder::header);
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode postJson(String url, String body, Duration timeout) throws Exception {
        HttpResponse<String> response = postAbsolute(url, body, timeout);
        assertThat(response.statusCode()).as("HTTP status for %s\nbody=%s", url, response.body()).isEqualTo(200);
        return mapper.readTree(response.body());
    }

    private HttpResponse<String> postSseAbsolute(String url, String body, Duration timeout) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private Optional<JsonNode> parseJsonRpcResponseUnlessNotFound(
            HttpResponse<String> response, String requestId) throws Exception {
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        JsonNode body = mapper.readTree(response.body());
        assertJsonRpcEnvelope(body, requestId);
        return Optional.of(body);
    }

    private static void assertJsonRpcEnvelope(JsonNode body, String requestId) {
        assertThat(body.path("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(body.path("id").asText()).isEqualTo(requestId);
        assertThat(body.has("result") && body.has("error"))
                .as("JSON-RPC response cannot contain result and error together")
                .isFalse();
    }

    private static String mediaType(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim();
    }

    private static void assertSkillsAreWellFormed(List<AgentSkill> skills) {
        Set<String> ids = new java.util.HashSet<>();
        for (AgentSkill skill : skills) {
            assertThat(skill.id()).as("skill.id").isNotBlank();
            assertThat(skill.name()).as("skill.name").isNotBlank();
            assertThat(skill.description()).as("skill.description").isNotBlank();
            assertThat(ids.add(skill.id())).as("skill id is unique: %s", skill.id()).isTrue();
        }
    }

    private static Optional<Task> taskFrom(ClientEvent event) {
        if (event instanceof TaskEvent taskEvent) {
            return Optional.of(taskEvent.getTask());
        }
        if (event instanceof TaskUpdateEvent updateEvent) {
            return Optional.of(updateEvent.getTask());
        }
        return Optional.empty();
    }

    private static List<SseFrame> parseSse(String body) {
        List<SseFrame> frames = new ArrayList<>();
        String event = null;
        StringBuilder data = new StringBuilder();
        for (String line : body.lines().toList()) {
            if (line.isBlank()) {
                if (event != null || !data.isEmpty()) {
                    frames.add(new SseFrame(event == null ? "message" : event, data.toString()));
                }
                event = null;
                data.setLength(0);
            } else if (line.startsWith("event:")) {
                event = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(line.substring("data:".length()).trim());
            }
        }
        if (event != null || !data.isEmpty()) {
            frames.add(new SseFrame(event == null ? "message" : event, data.toString()));
        }
        return frames;
    }

    private static void collectStateValues(JsonNode node, List<String> states) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if ("state".equals(field.getKey()) && field.getValue().isValueNode()) {
                    states.add(field.getValue().asText());
                }
                collectStateValues(field.getValue(), states);
            }
        } else if (node.isArray()) {
            node.forEach(child -> collectStateValues(child, states));
        }
    }

    private static String normalizeState(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT)
                .replace("TASK_STATE_", "")
                .replace('-', '_');
    }

    private static void assertStateOrder(List<String> states, String... expected) {
        int previous = -1;
        for (String state : expected) {
            int current = states.indexOf(state);
            assertThat(current).as("state %s in %s", state, states).isGreaterThan(previous);
            previous = current;
        }
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static void applyLlmOverride(SutStack.AgentBuilder agent, String apiBase, String apiKey) {
        agent.env("LLM_API_BASE", apiBase)
                .env("LLM_API_KEY", apiKey)
                .env("LLM_MODEL", "feat001-test-model")
                .property("LLM_API_BASE", apiBase)
                .property("LLM_API_KEY", apiKey)
                .property("LLM_MODEL", "feat001-test-model")
                .property("main-plan-agent.api-base", apiBase)
                .property("main-plan-agent.api-key", apiKey);
    }

    private record JsonRpcErrorCase(String name, String body, int expectedCode, String expectedId) {
        @Override
        public String toString() {
            return name;
        }
    }

    private record SendObservation(Task task, TaskState terminal, Throwable error) {}

    private record SseFrame(String event, String data) {}

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }

    private record CallbackRequest(
            String method,
            String path,
            Map<String, List<String>> headers,
            String body,
            Instant receivedAt) {

        Optional<String> firstHeader(String name) {
            return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of()).stream().findFirst();
        }

        JsonNode json(ObjectMapper mapper) {
            try {
                return mapper.readTree(body);
            } catch (IOException error) {
                throw new IllegalArgumentException("callback body is not JSON: " + body, error);
            }
        }
    }

    /** Test-owned external runtime callback endpoint. */
    private static final class CallbackReceiver implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;
        private final List<CallbackRequest> requests = new CopyOnWriteArrayList<>();
        private final AtomicInteger responseIndex = new AtomicInteger();
        private final int[] responseStatuses;

        private CallbackReceiver(int... responseStatuses) throws IOException {
            this.responseStatuses = responseStatuses.length == 0 ? new int[]{200} : responseStatuses.clone();
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.executor = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(executor);
            server.createContext(CALLBACK_PATH, this::handle);
            server.start();
        }

        static CallbackReceiver responding(int... statuses) throws IOException {
            return new CallbackReceiver(statuses);
        }

        String baseUrl() {
            return "http://127.0.0.1:" + port();
        }

        int port() {
            return server.getAddress().getPort();
        }

        String callbackUrl() {
            return baseUrl() + CALLBACK_PATH;
        }

        List<CallbackRequest> requests() {
            return Collections.unmodifiableList(new ArrayList<>(requests));
        }

        CallbackRequest awaitRequest(Duration timeout) {
            awaitCount(1, timeout);
            return requests.get(0);
        }

        void awaitCount(int expected, Duration timeout) {
            try {
                await().atMost(timeout).pollInterval(Duration.ofMillis(250))
                        .untilAsserted(() -> assertThat(requests).hasSizeGreaterThanOrEqualTo(expected));
            } catch (org.awaitility.core.ConditionTimeoutException timeoutFailure) {
                throw new AssertionError(
                        "expected at least " + expected + " callback request(s) within " + timeout
                                + " but received " + requests.size(),
                        timeoutFailure);
            }
        }

        void assertNoRequests(Duration quietPeriod) {
            await().during(quietPeriod).atMost(quietPeriod.plusSeconds(1))
                    .until(() -> requests.isEmpty());
        }

        void assertRequestCountRemains(int expected, Duration quietPeriod) {
            await().during(quietPeriod).atMost(quietPeriod.plusSeconds(1))
                    .until(() -> requests.size() == expected);
        }

        void assertRequestCount(int expected) {
            assertThat(requests).hasSize(expected);
        }

        void assertExactlyOneTerminal(String expectedState, ObjectMapper mapper) {
            assertThat(requests).hasSize(1);
            assertThat(terminalStateOf(requests.get(0).json(mapper))).contains(expectedState);
        }

        private void handle(HttpExchange exchange) throws IOException {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            Map<String, List<String>> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) ->
                    headers.put(name.toLowerCase(Locale.ROOT), List.copyOf(values)));
            requests.add(new CallbackRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    Collections.unmodifiableMap(headers),
                    new String(requestBody, StandardCharsets.UTF_8),
                    Instant.now()));

            int index = responseIndex.getAndIncrement();
            int status = responseStatuses[Math.min(index, responseStatuses.length - 1)];
            byte[] response = ("{\"status\":\"" + (status < 300 ? "accepted" : "retry") + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    /** Test-owned OpenAI-compatible peer that deterministically delegates to travel-trip. */
    private static final class DeterministicDelegatingLlmPeer implements AutoCloseable {
        private static final ObjectMapper JSON = new ObjectMapper();
        private static final String REMOTE_TOOL = "travel-trip";

        private final HttpServer server;
        private final ExecutorService executor;
        private final AtomicInteger toolCallCount = new AtomicInteger();

        private DeterministicDelegatingLlmPeer() throws IOException {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.executor = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
        }

        static DeterministicDelegatingLlmPeer start() throws IOException {
            return new DeterministicDelegatingLlmPeer();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        int toolCallCount() {
            return toolCallCount.get();
        }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                JsonNode request = JSON.readTree(exchange.getRequestBody());
                String requestText = request.toString();
                ObjectNode message = JSON.createObjectNode().put("role", "assistant");
                String finishReason;
                if (requestText.contains("DELAYED_CALLBACK_RESULT")) {
                    message.put("content", "MAINPLAN_RESUMED_WITH_DELAYED_CALLBACK_RESULT");
                    finishReason = "stop";
                } else {
                    boolean toolAvailable = false;
                    for (JsonNode tool : request.path("tools")) {
                        if (REMOTE_TOOL.equals(tool.path("function").path("name").asText())) {
                            toolAvailable = true;
                            break;
                        }
                    }
                    if (!toolAvailable) {
                        throw new IllegalStateException("OpenAI request does not expose tool " + REMOTE_TOOL);
                    }
                    toolCallCount.incrementAndGet();
                    message.putNull("content");
                    ObjectNode call = message.putArray("tool_calls").addObject();
                    call.put("id", "feat001-delayed-call");
                    call.put("type", "function");
                    call.putObject("function")
                            .put("name", REMOTE_TOOL)
                            .put("arguments", "{\"remoteInput\":\"FEAT001_DELAYED_CALLBACK_REQUEST\"}");
                    finishReason = "tool_calls";
                }

                ObjectNode response = JSON.createObjectNode();
                response.put("id", "chatcmpl-feat001-bounded-wait");
                response.put("object", "chat.completion");
                response.put("created", 1);
                response.put("model", "feat001-test-model");
                ObjectNode choice = response.putArray("choices").addObject();
                choice.put("index", 0);
                choice.set("message", message);
                choice.put("finish_reason", finishReason);
                response.putObject("usage")
                        .put("prompt_tokens", 1)
                        .put("completion_tokens", 1)
                        .put("total_tokens", 2);
                respondJson(exchange, 200, JSON.writeValueAsBytes(response));
            } catch (RuntimeException error) {
                respondJson(exchange, 500, ("{\"error\":\"" + error.getMessage() + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
            } finally {
                exchange.close();
            }
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    /** Test-owned remote A2A peer that accepts a Task and deliberately completes it by late callback. */
    private static final class DelayedCallbackA2aPeer implements AutoCloseable {
        private static final ObjectMapper JSON = new ObjectMapper();

        private final HttpServer server;
        private final ExecutorService executor;
        private final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        private final AtomicReference<OutboundRequest> request = new AtomicReference<>();
        private final AtomicInteger requestCount = new AtomicInteger();
        private final String remoteTaskId = "remote-task-" + shortId();
        private final String remoteContextId = "remote-context-" + shortId();

        private DelayedCallbackA2aPeer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(executor);
            server.createContext(STANDARD_CARD, this::handleCard);
            server.createContext(LEGACY_CARD, this::handleCard);
            server.createContext(A2A_PATH, this::handleA2a);
            server.start();
        }

        static DelayedCallbackA2aPeer start() throws IOException {
            return new DelayedCallbackA2aPeer();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        OutboundRequest awaitRequest(Duration timeout) {
            try {
                await().atMost(timeout).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
                    assertThat(requestCount.get()).isEqualTo(1);
                    assertThat(request.get()).isNotNull();
                });
                return request.get();
            } catch (org.awaitility.core.ConditionTimeoutException timeoutFailure) {
                throw new AssertionError("downstream A2A request was not observed within " + timeout,
                        timeoutFailure);
            }
        }

        HttpResponse<String> sendCompletedCallback(String resultText) throws Exception {
            OutboundRequest outbound = Optional.ofNullable(request.get())
                    .orElseThrow(() -> new IllegalStateException("no downstream A2A request was captured"));

            ObjectNode task = JSON.createObjectNode();
            task.put("id", remoteTaskId);
            task.put("contextId", remoteContextId);
            task.putObject("status")
                    .put("state", "TASK_STATE_COMPLETED")
                    .put("timestamp", Instant.now().toString());
            ObjectNode artifact = task.putArray("artifacts").addObject();
            artifact.put("artifactId", "delayed-answer");
            artifact.putArray("parts").addObject().put("text", resultText);
            task.putArray("history");
            task.putObject("metadata");

            ObjectNode callback = JSON.createObjectNode();
            callback.put("jsonrpc", "2.0");
            callback.putObject("result").set("task", task);
            callback.put("notificationId", outbound.notificationId());

            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(outbound.callbackUrl()))
                    .timeout(SHORT_HTTP_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("X-A2A-Notification-Id", outbound.notificationId())
                    .header("Authorization", "Bearer " + outbound.callbackToken())
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(callback)))
                    .build();
            return http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        }

        private void handleCard(HttpExchange exchange) throws IOException {
            try {
                ObjectNode card = JSON.createObjectNode();
                card.put("name", "travel-trip");
                card.put("description", "Deterministic delayed callback trip agent");
                card.put("version", "1.0.0");
                card.putObject("capabilities")
                        .put("streaming", false)
                        .put("pushNotifications", true)
                        .put("extendedAgentCard", false)
                        .putArray("extensions");
                card.putArray("defaultInputModes").add("text");
                card.putArray("defaultOutputModes").add("text");
                ObjectNode skill = card.putArray("skills").addObject();
                skill.put("id", "delayed-trip-plan");
                skill.put("name", "Delayed trip plan");
                skill.put("description", "Completes a delegated trip request through an A2A callback.");
                skill.putArray("tags").add("travel");
                card.putArray("supportedInterfaces").addObject()
                        .put("protocolBinding", "JSONRPC")
                        .put("url", baseUrl() + A2A_PATH)
                        .put("tenant", "")
                        .put("protocolVersion", "1.0");
                card.put("url", baseUrl() + A2A_PATH);
                card.put("preferredTransport", "JSONRPC");
                respondJson(exchange, 200, JSON.writeValueAsBytes(card));
            } finally {
                exchange.close();
            }
        }

        private void handleA2a(HttpExchange exchange) throws IOException {
            try {
                JsonNode envelope = JSON.readTree(exchange.getRequestBody());
                JsonNode pushConfig = envelope.path("params").path("configuration")
                        .path("taskPushNotificationConfig");
                if (pushConfig.isMissingNode() || pushConfig.isNull()) {
                    pushConfig = envelope.path("params").path("pushNotificationConfig");
                }
                String callbackUrl = firstText(pushConfig, "url", "callbackUrl");
                String callbackToken = firstText(pushConfig, "token");
                String notificationId = firstText(pushConfig, "id");
                String remoteInput = envelope.path("params").path("message").path("parts").path(0)
                        .path("text").asText("");
                requestCount.incrementAndGet();
                request.set(new OutboundRequest(
                        callbackUrl,
                        callbackToken,
                        notificationId,
                        remoteInput,
                        System.nanoTime()));

                ObjectNode task = JSON.createObjectNode();
                task.put("id", remoteTaskId);
                task.put("contextId", remoteContextId);
                task.putObject("status").put("state", "TASK_STATE_WORKING");
                task.putArray("artifacts");
                task.putObject("metadata");

                ObjectNode response = JSON.createObjectNode();
                response.put("jsonrpc", "2.0");
                response.set("id", envelope.path("id").deepCopy());
                response.putObject("result").set("task", task);
                respondJson(exchange, 200, JSON.writeValueAsBytes(response));
            } finally {
                exchange.close();
            }
        }

        private static String firstText(JsonNode node, String... names) {
            for (String name : names) {
                String value = node.path(name).asText("");
                if (!value.isBlank()) {
                    return value;
                }
            }
            return "";
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }

        private record OutboundRequest(
                String callbackUrl,
                String callbackToken,
                String notificationId,
                String remoteInput,
                long acceptedAtNanos) {}
    }

    private static void respondJson(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}

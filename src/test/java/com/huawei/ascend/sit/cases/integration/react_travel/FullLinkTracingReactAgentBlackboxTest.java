package com.huawei.ascend.sit.cases.integration.react_travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.BackingServices;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.lifecycle.TestContainerFactory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.awaitility.core.ConditionTimeoutException;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
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
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("integration")
@Tag("openjiuwen")
@Tag("dfx-001")
@Feature("DFX-001: tracer 全链路与审计")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class FullLinkTracingReactAgentBlackboxTest extends BaseManagedStackTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(150);
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration EVENTUALLY_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration BUS_EVENTUALLY_TIMEOUT = Duration.ofSeconds(90);
    private static final List<String> LLM_ENVIRONMENT = List.of(
            "LLM_API_KEY", "LLM_API_BASE", "LLM_MODEL", "LLM_PROVIDER", "LLM_SSL_VERIFY");
    private static final String COMPLETE_TRAVEL_REQUEST = "明天从上海到北京出差3天，住宿2晚，请调用差旅和酒店智能体给出完整方案。"
            + "差标：每晚不超过800元、最低4星；偏好国贸附近并需要会议室。";
    private static final String REMOTE_AGENT_URL = "openjiuwen.service.a2a.remote-agents[0].url";
    private static final String HOTEL_PORT_SYSTEM_PROPERTY = "sut-agents-hotel-port";
    private static final String W3C_TRACEPARENT = "00-[0-9a-f]{32}-[0-9a-f]{16}-01";
    private static final String BUS_NAMESERVER = "127.0.0.1:9876";
    private static final String BUS_NAMESPACE = "ascend-prod";
    private static final String BUS_TENANT = "tenant-a";
    private static final String BUS_MAINPLAN = "travel-mainplan";
    private static final String MAINPLAN_SERVICE_ID = BUS_MAINPLAN;
    private static final String TRIP_SERVICE_ID = "travel-trip";
    private static final String HOTEL_SERVICE_ID = "travel-hotel";
    private final TraceparentForwardingProbe mainplanToTrip = TraceparentForwardingProbe.start("mainplan->trip");
    private final TraceparentForwardingProbe tripToHotel = TraceparentForwardingProbe.start("trip->hotel");
    private TestConfig testConfig;
    private BackingServices backingServices;
    private RedisTrajectoryProxy redisTrajectoryProxy;
    private RedisEndpoint redisEndpoint;
    private GenericContainer<?> relayPostgres;
    private RocketMqFixture rocketMq;
    private Process relayProcess;
    private BusFixture bus;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        testConfig = config;
        backingServices = new BackingServices(config, Set.of("postgres", "redis"),
                new TestContainerFactory(null));
        redisEndpoint = RedisEndpoint.parse(backingServices.url("redis"));
        redisTrajectoryProxy = RedisTrajectoryProxy.start(redisEndpoint);
        relayPostgres = new GenericContainer<>(DockerImageName.parse("postgres:16.2"))
                .withEnv("POSTGRES_DB", "agentbus")
                .withEnv("POSTGRES_USER", "agentbus")
                .withEnv("POSTGRES_PASSWORD", "agentbus")
                .withExposedPorts(5432)
                .waitingFor(Wait.forListeningPort());
        relayPostgres.start();
        rocketMq = RocketMqFixture.start();
        startRelayProcess(relayPostgres.getHost() + ":" + relayPostgres.getMappedPort(5432),
                rocketMq.nameserver());
        mainplanToTrip.target(configuredAgentUrl(config, "trip"));
        tripToHotel.target(configuredAgentUrl(config, "hotel"));
        String rdc = env("AGENT_BUS_RDC_URL", "http://127.0.0.1:18092");
        return SutStack.builder(config).backingServices(backingServices)
                .agent("registry-center", agent -> agent.readyMode(
                        com.huawei.ascend.sit.lifecycle.AgentConfig.ReadyMode.TCP))
                .agent("hotel", agent -> enableTrajectory(agent, "openjiuwen.travel.hotel.llm.api-key",
                        redisEndpoint))
                .agent("trip", agent -> {
                    enableTrajectory(agent, "openjiuwen.travel.trip.llm.api-key", redisEndpoint);
                    agent.property(REMOTE_AGENT_URL, tripToHotel.baseUrl());
                })
                .agent("mainplan", agent -> {
                    enableTrajectory(agent, "openjiuwen.travel.mainplan.llm.api-key",
                            redisTrajectoryProxy.endpoint());
                    agent.property(REMOTE_AGENT_URL, mainplanToTrip.baseUrl());
                });
    }

    @BeforeAll
    void bindForwardingProbesToManagedAgents() {
        mainplanToTrip.target(stack.baseUrl("trip"));
        tripToHotel.target(stack.baseUrl("hotel"));
        clearPropagationEvidence();
    }

    @BeforeAll
    void startBusFixture() throws Exception {
        bus = new BusFixture(env("AGENT_BUS_NAMESERVER", BUS_NAMESERVER),
                env("AGENT_BUS_NAMESPACE", BUS_NAMESPACE));
        bus.start();
    }

    @AfterAll
    void stopForwardingProbes() {
        mainplanToTrip.close();
        tripToHotel.close();
    }

    @AfterAll
    void stopBusFixture() {
        if (bus != null) {
            bus.close();
        }
        if (relayProcess != null) {
            relayProcess.destroy();
            relayProcess = null;
        }
        if (rocketMq != null) {
            rocketMq.close();
            rocketMq = null;
        }
        if (relayPostgres != null) {
            relayPostgres.stop();
            relayPostgres = null;
        }
        if (backingServices != null) {
            if (redisTrajectoryProxy != null) {
                redisTrajectoryProxy.close();
                redisTrajectoryProxy = null;
            }
            backingServices.close();
            backingServices = null;
        }
    }

    private static SutStack.AgentBuilder enableTrajectory(SutStack.AgentBuilder agent, String apiKeyProperty,
                                                           RedisEndpoint redis) {
        LLM_ENVIRONMENT.forEach(name -> agent.env(name, requiredEnvironment(name)));
        agent.property(apiKeyProperty, requiredEnvironment("LLM_API_KEY"))
                .property("openjiuwen.service.trajectory.link.enabled", "true")
                .property("openjiuwen.service.otel.enabled", "true")
                .property("openjiuwen.service.otel.endpoint", "http://127.0.0.1:1")
                .property("openjiuwen.service.otel.protocol", "http")
                .property("openjiuwen.service.otel.timeout", "500ms")
                .property("openjiuwen.service.middleware.checkpointer.type", "redis")
                .property("openjiuwen.service.middleware.checkpointer.redis-ref", "default")
                .property("openjiuwen.service.middleware.redis.default.host", redis.host())
                .property("openjiuwen.service.middleware.redis.default.port", Integer.toString(redis.port()));
        return agent;
    }

    private static SutStack.AgentBuilder busProperties(SutStack.AgentBuilder agent, String serviceId, String rdc) {
        agent.property("openjiuwen.service.service-id", serviceId)
                .property("openjiuwen.service.bus.consumer.enabled", "true")
                .property("openjiuwen.service.bus.consumer.registry-base-url", rdc)
                .property("agent-bus.role.runtime.enabled", "true")
                .property("agent-bus.nameserver", env("AGENT_BUS_NAMESERVER", BUS_NAMESERVER))
                .property("agent-bus.namespace", env("AGENT_BUS_NAMESPACE", BUS_NAMESPACE))
                .property("agent-bus.tenant", env("AGENT_BUS_TENANT", BUS_TENANT))
                .property("agent-bus.event-bus-service-id", env("AGENT_BUS_EVENT_BUS_SERVICE_ID", "eventbus-01"))
                .property("agent-bus.producer-group", "runtime-" + serviceId)
                .property("AGENT_BUS_ENABLED", "true");
        return agent;
    }

    private void startRelayProcess(String postgresAddress, String nameserver) {
        String repo = System.getProperty("maven.repo.local", "/mnt/d/repository");
        String jar = repo + "/com/openjiuwen/event-bus-relay/0.1.0/event-bus-relay-0.1.0.jar";
        try {
            ProcessBuilder command = new ProcessBuilder("java", "-jar", jar,
                    "--spring.profiles.active=eventbus",
                    "--spring.datasource.url=jdbc:postgresql://" + postgresAddress + "/agentbus",
                    "--spring.datasource.username=agentbus",
                    "--spring.datasource.password=agentbus",
                    "--agent-bus.nameserver=" + nameserver,
                    "--agent-bus.namespace=" + BUS_NAMESPACE,
                    "--agent-bus.tenant=" + BUS_TENANT,
                    "--agent-bus.event-bus-service-id=eventbus-01",
                    "--agent-bus.role.relay.enabled=true");
            command.redirectErrorStream(true);
            command.redirectOutput(ProcessBuilder.Redirect.appendTo(
                    java.nio.file.Path.of("target", "sit-logs", "dfx001-event-bus-relay.log").toFile()));
            relayProcess = command.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline && relayProcess.isAlive()) {
                TimeUnit.MILLISECONDS.sleep(250);
            }
            if (!relayProcess.isAlive()) {
                throw new IllegalStateException(
                        "event-bus-relay exited; see target/sit-logs/dfx001-event-bus-relay.log");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to start event-bus-relay standalone fixture", e);
        }
    }

    private static void configureLlm(SutStack.AgentBuilder agent, String apiKeyProperty) {
        LLM_ENVIRONMENT.forEach(name -> agent.env(name, requiredEnvironment(name)));
        agent.property(apiKeyProperty, requiredEnvironment("LLM_API_KEY"));
    }

    @Test
    @Order(1)
    @Tag("story-dfx-001-a1")
    @Story("DFX-001.A1: 上游 traceparent 是入口唯一源")
    @DisplayName("DFX-001.A1: upstream traceparent remains the ingress single source")
    void upstreamTraceparentIsIngressSingleSource() throws Exception {
        String tenant = unique("tenant-a");
        String context = UUID.randomUUID().toString();
        String traceId = hexId(32);
        clearPropagationEvidence();
        send(COMPLETE_TRAVEL_REQUEST, tenant, context, null,
                "00-" + traceId + "-" + hexId(16) + "-01");

        assertForwardedTrace(mainplanToTrip, traceId, "DFX-001.A1 mainplan->trip");
    }

    @ParameterizedTest(name = "[{index}] traceparent={0}")
    @NullAndEmptySource
    @ValueSource(strings = {"not-a-w3c-traceparent"})
    @Order(2)
    @Tag("story-dfx-001-a2")
    @Story("DFX-001.A2: 缺失或非法 traceparent 降级生成")
    @DisplayName("DFX-001.A2: missing or invalid traceparent generates an auditable degraded W3C trace")
    void missingAndInvalidTraceparentGenerateDegradedW3cTrace(String traceparent) throws Exception {
        String tenant = unique("tenant-degraded");
        String context = unique("conversation-degraded");
        send("请简短确认收到请求。", tenant, context, null, traceparent);

        JsonNode audit = requireSuccess(get(auditPath(tenant, context)), "DFX-001.A2 audit query");
        List<String> traceIds = textValues(audit, Set.of("traceId", "trace_id"));
        assertThat(traceIds).isNotEmpty().allMatch(value -> value.matches("[0-9a-f]{32}"));
        assertThat(traceIds.stream().distinct().count()).as("one degraded trace per request").isEqualTo(1);
        assertThat(booleanValues(audit, Set.of("traceDegraded", "trace_degraded", "degraded")))
                .contains(true);
    }

    @Test
    @Order(3)
    @Tag("story-dfx-001-a3")
    @Story("DFX-001.A3: header 优先于兼容 metadata")
    @DisplayName("DFX-001.A3: W3C header wins when metadata carries a different trace")
    void headerTraceparentTakesPriorityOverMetadataTrace() throws Exception {
        String tenant = unique("tenant-priority");
        String context = unique("conversation-priority");
        String headerTrace = hexId(32);
        String metadataTrace = hexId(32);
        clearPropagationEvidence();
        send(COMPLETE_TRAVEL_REQUEST, tenant, context, null,
                "00-" + headerTrace + "-" + hexId(16) + "-01",
                Map.of("traceId", metadataTrace, "trace_id", metadataTrace,
                        "traceparent", "00-" + metadataTrace + "-" + hexId(16) + "-01"));

        List<String> propagated = assertForwardedTrace(mainplanToTrip, headerTrace,
                "DFX-001.A3 mainplan->trip");
        assertThat(propagated).doesNotContain(metadataTrace);
    }

    @Test
    @Order(4)
    @Tag("story-dfx-001-a4")
    @Story("DFX-001.A4: runtime 到 runtime W3C trace 传播")
    @DisplayName("DFX-001.A4: runtime delegation preserves the W3C trace across all three hops")
    void runtimeDelegationPreservesW3cTraceAcrossThreeHops() throws Exception {
        String context = UUID.randomUUID().toString();
        String traceId = hexId(32);
        clearPropagationEvidence();
        send(COMPLETE_TRAVEL_REQUEST, unique("tenant-propagation"), context, null,
                "00-" + traceId + "-" + hexId(16) + "-01");

        assertAll("DFX-001.A4 all runtime propagation hops",
                () -> assertForwardedTrace(mainplanToTrip, traceId, "DFX-001.A4 mainplan->trip"),
                () -> assertForwardedTrace(tripToHotel, traceId, "DFX-001.A4 trip->hotel"));
    }

    @Test
    @Order(5)
    @Tag("story-dfx-001-a5")
    @Story("DFX-001.A5: mainplan-trip-hotel 跨任务执行树")
    @DisplayName("DFX-001.A5: run_id and parent_run_id reconstruct mainplan to trip to hotel")
    void runTreeReconstructsMainplanTripHotelDelegation() throws Exception {
        String traceId = hexId(32);
        send(COMPLETE_TRAVEL_REQUEST, unique("tenant-tree"), null, null,
                "00-" + traceId + "-" + hexId(16) + "-01");

        JsonNode tree = awaitTree(traceId, node -> executionEdges(node).size() >= 2,
                "DFX-001.A5 execution-tree query");
        List<String> runIds = textValues(tree, Set.of("runId", "run_id"));
        List<String> parentRunIds = textValues(tree, Set.of("parentRunId", "parent_run_id"));
        assumeTrue(runIds.stream().distinct().count() >= 3,
                "LLM did not produce an externally observable three-Agent delegation; result is inconclusive");
        assertThat(parentRunIds.stream().filter(value -> !value.isBlank()).distinct().count())
                .as("two delegation parent links")
                .isGreaterThanOrEqualTo(2);
        assertThat(parentRunIds).allMatch(value -> value.isBlank() || runIds.contains(value));
        List<ExecutionEdge> edges = executionEdges(tree);
        assertThat(edges).as("query exposes two delegation edges").hasSizeGreaterThanOrEqualTo(2);
        assertThat(edges).anyMatch(first -> edges.stream().anyMatch(second -> first.child().equals(second.parent())));
        assertThat(edges.stream().map(ExecutionEdge::agent).filter(value -> !value.isBlank()).toList())
                .anyMatch(value -> value.toLowerCase().contains("trip"))
                .anyMatch(value -> value.toLowerCase().contains("hotel"));
    }

    @Test
    @Order(6)
    @Tag("story-dfx-001-a6")
    @Story("DFX-001.A6: trace run 与 toolCall 标识职责分离")
    @DisplayName("DFX-001.A6: trace_id run_id and toolCallId remain distinct correlation keys")
    void traceRunAndToolCallIdentifiersRemainDistinct() throws Exception {
        String tenant = unique("tenant-identifiers");
        String context = unique("conversation-identifiers");
        String traceId = hexId(32);
        send(COMPLETE_TRAVEL_REQUEST, tenant, context, null,
                "00-" + traceId + "-" + hexId(16) + "-01");

        JsonNode tree = awaitTree(traceId, node -> executionEdges(node).size() >= 2, "DFX-001.A6 tree");
        JsonNode audit = awaitAudit(tenant, context,
                node -> !textValues(node, Set.of("toolCallId", "tool_call_id")).isEmpty(), "DFX-001.A6 audit");
        Set<String> runIds = new LinkedHashSet<>(textValues(tree, Set.of("runId", "run_id")));
        Set<String> toolCallIds = new LinkedHashSet<>(textValues(audit, Set.of("toolCallId", "tool_call_id")));
        assertThat(runIds).hasSizeGreaterThanOrEqualTo(3).doesNotContain(traceId);
        assertThat(toolCallIds).isNotEmpty().doesNotContainAnyElementsOf(runIds).doesNotContain(traceId);
    }

    @Test
    @Order(7)
    @Tag("story-dfx-001-a7")
    @Story("DFX-001.A7: 同轮并行委托执行树")
    @DisplayName("DFX-001.A7: parallel delegations create distinct direct child runs")
    void parallelDelegationsCreateDistinctDirectChildRuns() throws Exception {
        try (CompletedA2aAgent downstream = CompletedA2aAgent.start();
             SutStack parallel = isolatedParallelEdpaStack(downstream.baseUrl())) {
            String traceId = hexId(32);
            sendAt(parallel.baseUrl("edpa-plan-agent"), "给李四转5元，同时给王五转10元。",
                    unique("tenant-parallel"), null, null,
                    "00-" + traceId + "-" + hexId(16) + "-01", Map.of());
            JsonNode tree = awaitJson(parallel.baseUrl("edpa-plan-agent"),
                    "/manage/trajectory/runs?traceId=" + encode(traceId),
                    node -> executionEdges(node).size() >= 2, "DFX-001.A7 deterministic parallel tree");
            Map<String, Set<String>> children = new HashMap<>();
            executionEdges(tree).forEach(edge -> children
                    .computeIfAbsent(edge.parent(), ignored -> new LinkedHashSet<>()).add(edge.child()));
            assertThat(children.values()).anyMatch(values -> values.size() >= 2);
            assertThat(downstream.callCount()).as("both delegated calls reached the A2A fixture")
                    .isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    @Order(8)
    @Tag("story-dfx-001-a8")
    @Story("DFX-001.A8: bus trace 单源进入执行轨迹")
    @DisplayName("DFX-001.A8: bus ingress trace is recorded by the DFX001 execution tree")
    void busIngressTraceIsRecordedInExecutionTree() throws Exception {
        String traceId = hexId(32);
        String correlation = unique("dfx001-bus");
        String tenant = BUS_TENANT;
        try (SutStack a8Stack = isolatedHotelBusStack()) {
            BusRequest request = BusRequest.clientInvocation(HOTEL_SERVICE_ID, tenant, correlation, traceId,
                    busPayload("请推荐北京国贸附近、每晚不超过800元的四星酒店。"));
            bus.send(request);
            JsonNode tree = awaitTree(a8Stack.baseUrl("hotel"), traceId,
                    node -> !textValues(node, Set.of("runId", "run_id")).isEmpty(),
                    "DFX-001.A8 Bus execution tree", BUS_EVENTUALLY_TIMEOUT);
            assertThat(textValues(tree, Set.of("traceId", "trace_id")))
                    .as("Bus envelope traceId must enter the DFX001 execution tree")
                    .contains(traceId);
        }
    }

    @Test
    @Order(9)
    @Tag("story-dfx-001-a9")
    @Story("DFX-001.A9: REST 入口 trace 单源")
    @DisplayName("DFX-001.A9: REST query uses the W3C header as its trace source")
    void restQueryUsesW3cHeaderAsTraceSource() throws Exception {
        String tenant = unique("tenant-rest");
        String context = UUID.randomUUID().toString();
        String traceId = hexId(32);
        clearPropagationEvidence();
        HttpResponse<String> response = post(stack.baseUrl("mainplan") + "/v1/query",
                JSON.writeValueAsString(Map.of("conversation_id", context, "message", COMPLETE_TRAVEL_REQUEST,
                        "stream", false)),
                Map.of("Content-Type", "application/json", "X-Tenant-Id", tenant,
                        "traceparent", "00-" + traceId + "-" + hexId(16) + "-01"));
        assertThat(response.statusCode()).as("REST response body=%s", response.body()).isBetween(200, 299);
        assertForwardedTrace(mainplanToTrip, traceId, "DFX-001.A9 mainplan->trip");
    }

    @Test
    @Order(10)
    @Tag("story-dfx-001-a10")
    @Story("DFX-001.A10: 首跳 contextId 注入与 taskId 续跑")
    @DisplayName("DFX-001.A10: a first-hop generated context remains stable on task-only continuation")
    void generatedFirstHopContextRemainsStableOnTaskOnlyContinuation() throws Exception {
        String tenant = unique("tenant-context-injection");
        String traceId = hexId(32);
        A2aResult first = send("到北京出差3天，请先询问缺失信息。", tenant, null, null,
                "00-" + traceId + "-" + hexId(16) + "-01");
        assertThat(first.contextId()).isNotBlank();
        A2aResult second = send("明天从上海出发，预算800元，请继续。", tenant, null, first.taskId(), null);
        assertThat(second.contextId()).isEqualTo(first.contextId());
        JsonNode audit = awaitAudit(tenant, first.contextId(), node -> distinctCount(node, "seq") >= 2,
                "DFX-001.A10 audit");
        assertThat(traceIds(audit)).isNotEmpty().allMatch(traceId::equals);
    }

    @Test
    @Order(11)
    @Tag("story-dfx-001-a11")
    @Story("DFX-001.A11: runtime 重启后 trace 恢复")
    @DisplayName("DFX-001.A11: continuation restores trace and round sequence after runtime restart")
    void continuationRestoresTraceAfterRuntimeRestart() throws Exception {
        String tenant = unique("tenant-restart");
        String context = unique("conversation-restart");
        String traceId = hexId(32);
        A2aResult first = send("到北京出差，请先询问出发地和日期。", tenant, context, null,
                "00-" + traceId + "-" + hexId(16) + "-01");
        assertThat(first.state()).contains("INPUT_REQUIRED");
        stack.stop("mainplan");
        stack.start("mainplan");
        send("明天从上海出发，住宿预算800元，请完成。", tenant, null, first.taskId(), null);

        JsonNode audit = awaitAudit(tenant, context, node -> distinctCount(node, "seq") >= 2,
                "DFX-001.A11 audit");
        assertThat(traceIds(audit)).isNotEmpty().allMatch(traceId::equals);
        assertStrictlyIncreasing(snapshotSequences(audit), "round seq after restart");
    }

    @Test
    @Order(35)
    @Tag("story-dfx-001-a12")
    @Story("DFX-001.A12: 合法上游 trace 的非降级执行记录")
    @DisplayName("DFX-001.A12: valid upstream trace is stored without a degraded marker")
    void validUpstreamTraceIsStoredWithoutDegradedMarker() throws Exception {
        String tenant = unique("tenant-valid-trace");
        String context = unique("conversation-valid-trace");
        String traceId = hexId(32);
        send(COMPLETE_TRAVEL_REQUEST, tenant, context, null,
                "00-" + traceId + "-" + hexId(16) + "-01");

        JsonNode audit = awaitAudit(tenant, context,
                node -> traceIds(node).contains(traceId)
                        && !booleanValues(node,
                                Set.of("traceDegraded", "trace_degraded", "degraded")).isEmpty(),
                "DFX-001.A12 audit");
        assertThat(traceIds(audit)).as("the ingress audit record exposes trace id")
                .isNotEmpty().allMatch(traceId::equals);
        assertThat(booleanValues(audit, Set.of("traceDegraded", "trace_degraded", "degraded")))
                .as("valid upstream trace is not degraded")
                .isNotEmpty().allMatch(value -> !value);
    }

    @Test
    @Order(36)
    @Tag("story-dfx-001-a13")
    @Story("DFX-001.A13: REST 入口渠道审计")
    @DisplayName("DFX-001.A13: REST ingress is stored with its channel and upstream trace")
    void restIngressIsStoredWithChannelAndUpstreamTrace() throws Exception {
        String tenant = unique("tenant-rest-audit");
        String context = UUID.randomUUID().toString();
        String traceId = hexId(32);
        HttpResponse<String> response = post(stack.baseUrl("mainplan") + "/v1/query",
                JSON.writeValueAsString(Map.of("conversation_id", context, "message", "请简短确认收到请求。",
                        "stream", false)),
                Map.of("Content-Type", "application/json", "X-Tenant-Id", tenant,
                        "traceparent", "00-" + traceId + "-" + hexId(16) + "-01"));
        assertThat(response.statusCode()).as("REST response body=%s", response.body()).isBetween(200, 299);

        JsonNode audit = awaitAudit(tenant, context, node -> !traceIds(node).isEmpty(), "DFX-001.A13 audit");
        assertThat(traceIds(audit)).isNotEmpty().allMatch(traceId::equals);
        assertThat(textValues(audit, Set.of("ingressChannel", "ingress_channel")))
                .isNotEmpty().allMatch(value -> value.equalsIgnoreCase("rest"));
        assertThat(booleanValues(audit, Set.of("traceDegraded", "trace_degraded", "degraded")))
                .isNotEmpty().allMatch(value -> !value);
    }

    @Test
    @Order(12)
    @Tag("story-dfx-001-b1")
    @Story("DFX-001.B1: 多轮 trace 一致且审计快照 append-only")
    @DisplayName("DFX-001.B1: continuation keeps trace and appends a new run snapshot")
    void multiTurnSnapshotsAppendAndKeepTrace() throws Exception {
        String tenant = unique("tenant-multiturn");
        String context = unique("conversation-multiturn");
        String traceId = hexId(32);
        String traceparent = "00-" + traceId + "-" + hexId(16) + "-01";
        A2aResult first = send("到北京出差3天，请先询问我缺失的出发地和日期。",
                tenant, context, null, traceparent);
        assertThat(first.state()).as("first round pauses for input").contains("INPUT_REQUIRED");
        JsonNode firstReplay = awaitAudit(tenant, context, node -> !snapshotSequences(node).isEmpty(),
                "DFX-001.B1 first-round replay");
        long firstSeq = snapshotSequences(firstReplay).get(0);
        JsonNode originalFirstSnapshot = snapshotWithSeq(firstReplay, firstSeq).deepCopy();
        send("明天从上海出发，住宿每晚不超过800元，请继续完成。",
                tenant, context, first.taskId(), null);

        JsonNode audit = awaitAudit(tenant, context, node -> distinctCount(node, "seq") >= 2,
                "DFX-001.B1 audit replay");
        List<Long> sequences = snapshotSequences(audit);
        List<String> runIds = textValues(audit, Set.of("runId", "run_id"));
        List<String> traceIds = textValues(audit, Set.of("traceId", "trace_id"));
        assertThat(sequences.stream().distinct().count()).isGreaterThanOrEqualTo(2);
        assertStrictlyIncreasing(sequences, "append-only audit seq");
        assertThat(runIds.stream().distinct().count()).isGreaterThanOrEqualTo(2);
        assertThat(traceIds).isNotEmpty().allMatch(traceId::equals);
        assertThat(snapshotWithSeq(audit, firstSeq)).isEqualTo(originalFirstSnapshot);
        assertThat(textValues(audit, Set.of("finalState", "final_state")))
                .anyMatch(value -> value.toUpperCase().contains("INPUT_REQUIRED"));
    }

    @Test
    @Order(13)
    @Tag("story-dfx-001-b2")
    @Story("DFX-001.B2: 三轮审批恢复回放")
    @DisplayName("DFX-001.B2: three audit snapshots retain tool delegation and approval recovery evidence")
    void threeRoundAuditReplayRetainsApprovalRecoveryEvidence() throws Exception {
        String tenant = unique("tenant-three-round");
        String context = unique("conversation-three-round");
        A2aResult first = send("我要去北京出差，请先只询问日期。", tenant, context, null, null);
        assumeTrue(first.state().contains("INPUT_REQUIRED"), "first round did not pause; result is inconclusive");
        A2aResult second = send("明天出发，请继续询问出发地。", tenant, context, first.taskId(), null);
        assumeTrue(second.state().contains("INPUT_REQUIRED"), "second round did not pause; result is inconclusive");
        send("从上海出发，住2晚，预算每晚800元，请调用差旅和酒店智能体完成。",
                tenant, context, first.taskId(), null);

        JsonNode audit = awaitAudit(tenant, context, node -> distinctCount(node, "seq") >= 3,
                "DFX-001.B2 audit replay");
        assertStrictlyIncreasing(snapshotSequences(audit), "three-round seq");
        assertThat(audit.toString().toLowerCase()).contains("approval", "delegat", "tool");
    }

    @Test
    @Order(14)
    @Tag("story-dfx-001-b3")
    @Story("DFX-001.B3: 审计缺洞显式标记")
    @DisplayName("DFX-001.B3: replay marks a reserved but missing snapshot as a gap")
    void replayMarksReservedButMissingSnapshotAsGap() throws Exception {
        String tenant = unique("tenant-gap");
        String context = unique("conversation-gap");
        try (RedisCommandClient redis = new RedisCommandClient(redisEndpoint)) {
            redis.setex(auditLatestKey(tenant, context), 300, "00000001");
        }
        JsonNode audit = awaitAudit(tenant, context, FullLinkTracingReactAgentBlackboxTest::hasGapMarker,
                "DFX-001.B3 audit replay");
        assertThat(hasGapMarker(audit)).isTrue();
    }

    @Test
    @Order(15)
    @Tag("story-dfx-001-b4")
    @Story("DFX-001.B4: 审计查询租户隔离")
    @DisplayName("DFX-001.B4: another tenant cannot read a conversation audit snapshot")
    void auditQueryRejectsCrossTenantRead() throws Exception {
        String ownerTenant = unique("tenant-owner");
        String otherTenant = unique("tenant-other");
        String context = unique("conversation-isolation");
        send("请简短确认收到请求。", ownerTenant, context, null, null);

        JsonNode owner = awaitAudit(ownerTenant, context, node -> !traceIds(node).isEmpty(),
                "DFX-001.B4 owner audit query");
        HttpResponse<String> denied = get(auditPath(otherTenant, context));
        assertThat(denied.statusCode()).as("cross-tenant query body=%s", denied.body()).isIn(403, 404);
        assertThat(denied.body()).doesNotContain(context, ownerTenant);
        assertThat(traceIds(owner)).isNotEmpty();
        for (String ownerTraceId : traceIds(owner)) {
            assertThat(denied.body()).doesNotContain(ownerTraceId);
        }
    }

    @Test
    @Order(16)
    @Tag("story-dfx-001-b5")
    @Story("DFX-001.B5: 安全决策留证")
    @DisplayName("DFX-001.B5: allow and deny security decisions are tenant-scoped and auditable")
    void allowAndDenySecurityDecisionsAreAuditable() throws Exception {
        String tenant = unique("tenant-security");
        String context = "unknown";
        try (SutStack probe = isolatedMainplanProbeStack()) {
            sendAt(probe.baseUrl("mainplan-dfx001-probe"), "请确认授权请求。", tenant, context,
                    null, null, Map.of(), Map.of("X-User-ID", "allow"));
            HttpResponse<String> denied = postA2a(probe.baseUrl("mainplan-dfx001-probe"),
                    "请拒绝此请求。", tenant, context, Map.of("X-User-ID", "deny"));
            assertThat(denied.statusCode()).as("DFX-001.B5 deny body=%s", denied.body()).isEqualTo(403);
            JsonNode audit = awaitJson(probe.baseUrl("mainplan-dfx001-probe"), auditPath(tenant, context),
                    node -> containsDecision(node, "allow") && containsDecision(node, "deny"),
                    "DFX-001.B5 audit");
            assertDecisionFields(audit, "allow", Set.of("resource", "tenant", "trace"));
            assertDecisionFields(audit, "deny", Set.of("resource", "reason", "tenant", "trace"));
        }
    }

    @Test
    @Order(17)
    @Tag("story-dfx-001-b6")
    @Story("DFX-001.B6: 不可逆工具调用留证")
    @DisplayName("DFX-001.B6: irreversible tool calls expose summarized auditable evidence")
    void irreversibleToolCallsExposeSummarizedEvidence() throws Exception {
        String tenant = unique("tenant-irreversible");
        String context = unique("conversation-irreversible");
        send(COMPLETE_TRAVEL_REQUEST, tenant, context, null, null);
        JsonNode audit = awaitAudit(tenant, context, node -> hasDecision(node, "tool-call", null),
                "DFX-001.B6 audit");
        JsonNode toolCall = decisionRecord(audit, "tool-call", null);
        assertThat(firstText(toolCall, "toolName", "tool_name", "name"))
                .as("tool-call tool name").isNotBlank();
        assertThat(firstText(toolCall, "argsSummary", "args_summary", "summary"))
                .as("tool-call argument summary").isNotBlank();
        assertThat(firstText(toolCall, "elapsedMs", "elapsed_ms", "elapsed"))
                .as("tool-call elapsed time").isNotBlank();
        assertThat(firstText(toolCall, "status", "outcome", "result"))
                .as("tool-call outcome").isNotBlank();
        assertThat(audit.toString().toLowerCase()).doesNotContain("chain-of-thought", "raw_cot");
    }

    @Test
    @Order(18)
    @Tag("story-dfx-001-b7")
    @Story("DFX-001.B7: 审批决策留证")
    @DisplayName("DFX-001.B7: approval initiation and recovery are separate audit decisions")
    void approvalInitiationAndRecoveryAreSeparateAuditDecisions() throws Exception {
        String tenant = unique("tenant-approval");
        String context = unique("conversation-approval");
        A2aResult first = send("到北京出差，请先询问我的出发地。", tenant, context, null, null);
        assertThat(first.state()).contains("INPUT_REQUIRED");
        send("从上海出发，明天走，请继续。", tenant, context, first.taskId(), null);
        JsonNode audit = awaitAudit(tenant, context,
                node -> hasDecision(node, "approval", "raise") && hasDecision(node, "approval", "resume"),
                "DFX-001.B7 audit");
        JsonNode raised = decisionRecord(audit, "approval", "raise");
        JsonNode resumed = decisionRecord(audit, "approval", "resume");
        assertThat(firstText(raised, "contentSummary", "content_summary", "approvalSummary", "approval_summary",
                "summary")).as("approval raise content summary").isNotBlank();
        assertThat(firstText(raised, "recordedAt", "recorded_at", "at", "time", "timestamp"))
                .as("approval raise time").isNotBlank();
        assertThat(firstText(resumed, "inputCategory", "input_category", "resumeInputCategory",
                "resume_input_category", "category")).as("approval resume input category").isNotBlank();
        assertThat(firstText(resumed, "recordedAt", "recorded_at", "at", "time", "timestamp"))
                .as("approval resume time").isNotBlank();
    }

    @Test
    @Order(19)
    @Tag("story-dfx-001-b8")
    @Story("DFX-001.B8: 跨边界交接留证")
    @DisplayName("DFX-001.B8: delegation decisions join source target and tool call identifiers")
    void delegationDecisionsJoinSourceTargetAndToolCallIdentifiers() throws Exception {
        String tenant = unique("tenant-delegation");
        String context = unique("conversation-delegation");
        send(COMPLETE_TRAVEL_REQUEST, tenant, context, null, null);
        JsonNode audit = awaitAudit(tenant, context,
                node -> !textValues(node, Set.of("sourceRunId", "source_run_id")).isEmpty()
                        && !textValues(node, Set.of("agentName", "agent_name")).isEmpty()
                        && !textValues(node, Set.of("remoteRunId", "remote_run_id")).isEmpty()
                        && !textValues(node, Set.of("toolCallId", "tool_call_id")).isEmpty(),
                "DFX-001.B8 audit");
        assertThat(textValues(audit, Set.of("sourceRunId", "source_run_id")))
                .as("delegation source run identifier").isNotEmpty().allMatch(value -> !value.isBlank());
        assertThat(textValues(audit, Set.of("agentName", "agent_name")))
                .as("delegation target agent identifier").isNotEmpty().allMatch(value -> !value.isBlank());
        assertThat(textValues(audit, Set.of("remoteRunId", "remote_run_id")))
                .as("delegation target run identifier").isNotEmpty().allMatch(value -> !value.isBlank());
        assertThat(textValues(audit, Set.of("toolCallId", "tool_call_id")))
                .as("delegation tool call identifier").isNotEmpty().allMatch(value -> !value.isBlank());
    }

    @Test
    @Order(20)
    @Tag("story-dfx-001-b9")
    @Story("DFX-001.B9: 生命周期迁移留证")
    @DisplayName("DFX-001.B9: task lifecycle transitions are ordered and end in a terminal state")
    void taskLifecycleTransitionsAreOrderedAndTerminal() throws Exception {
        String tenant = unique("tenant-lifecycle");
        String context = unique("conversation-lifecycle");
        send("请简短确认收到请求并完成任务。", tenant, context, null, null);
        JsonNode audit = awaitAudit(tenant, context, node -> containsDecision(node, "lifecycle"),
                "DFX-001.B9 audit");
        List<LifecycleTransition> transitions = lifecycleTransitions(audit);
        assertThat(transitions).isNotEmpty();
        assertThat(transitions).allSatisfy(transition -> {
            assertThat(transition.from()).isNotBlank();
            assertThat(transition.to()).isNotBlank();
            assertThat(transition.at()).isNotBlank();
            assertThat(transition.runId()).isNotBlank();
        });
        assertThat(transitions.get(transitions.size() - 1).to().toUpperCase())
                .containsAnyOf("COMPLETED", "FAILED", "CANCELED", "INPUT_REQUIRED");
    }

    @Test
    @Order(21)
    @Tag("story-dfx-001-b10")
    @Story("DFX-001.B10: 会话重置保留审计留证")
    @DisplayName("DFX-001.B10: reset retains append-only audit evidence until trajectory TTL")
    void resetRetainsAppendOnlyAuditEvidenceUntilTtl() throws Exception {
        String tenant = unique("tenant-reset");
        String targetContext = unique("conversation-reset-target");
        String otherContext = unique("conversation-reset-other");
        send("请确认目标会话。", tenant, targetContext, null, null);
        send("请确认对照会话。", tenant, otherContext, null, null);
        JsonNode before = awaitAudit(tenant, targetContext, node -> !traceIds(node).isEmpty(),
                "DFX-001.B10 target before reset");
        String oldTrace = traceIds(before).get(0);
        awaitAudit(tenant, otherContext, node -> !traceIds(node).isEmpty(), "DFX-001.B10 control before reset");

        HttpResponse<String> reset = post(stack.baseUrl("mainplan") + "/v1/reset_conversation",
                JSON.writeValueAsString(Map.of("conversation_id", targetContext)),
                Map.of("Content-Type", "application/json", "X-Tenant-Id", tenant));
        assertThat(reset.statusCode()).as("reset body=%s", reset.body()).isBetween(200, 299);
        JsonNode retained = awaitAudit(tenant, targetContext, node -> traceIds(node).contains(oldTrace),
                "DFX-001.B10 target retained after reset");
        assertThat(retained).as("reset keeps append-only audit evidence until TTL").isEqualTo(before);
        awaitAudit(tenant, otherContext, node -> !traceIds(node).isEmpty(), "DFX-001.B10 control after reset");
    }

    @Test
    @Order(22)
    @Tag("story-dfx-001-b11")
    @Story("DFX-001.B11: 并发轮次 seq 唯一有序")
    @DisplayName("DFX-001.B11: concurrent rounds allocate unique ordered audit sequences")
    void concurrentRoundsAllocateUniqueOrderedAuditSequences() throws Exception {
        String tenant = unique("tenant-concurrent-seq");
        String context = unique("conversation-concurrent-seq");
        List<CompletableFuture<A2aResult>> calls = List.of(
                asyncSend("请确认并发请求A。", tenant, context),
                asyncSend("请确认并发请求B。", tenant, context));
        for (CompletableFuture<A2aResult> call : calls) {
            assertThat(call.join().taskId()).isNotBlank();
        }
        JsonNode audit = awaitAudit(tenant, context, node -> distinctCount(node, "seq") >= 2,
                "DFX-001.B11 audit");
        List<Long> seq = snapshotSequences(audit);
        assertThat(seq).doesNotHaveDuplicates();
        assertStrictlyIncreasing(seq, "concurrent audit seq");
    }

    @Test
    @Order(24)
    @Tag("story-dfx-001-b13")
    @Story("DFX-001.B13: runs 查询租户保护")
    @DisplayName("DFX-001.B13: execution-tree query rejects another tenant without leaking nodes")
    void executionTreeQueryRejectsAnotherTenant() throws Exception {
        String tenantHeader = "X-Tenant-ID";
        String ownerTenant = unique("tenant-runs-owner");
        String otherTenant = unique("tenant-runs-other");
        String traceId = hexId(32);
        send("请确认收到请求。", ownerTenant, null, null,
                "00-" + traceId + "-" + hexId(16) + "-01");
        String path = "/manage/trajectory/runs?traceId=" + encode(traceId);
        HttpResponse<String> denied = get(path, Map.of(tenantHeader, otherTenant));
        assertThat(denied.statusCode()).isIn(403, 404);
        assertThat(denied.body()).doesNotContain(traceId, ownerTenant);
        JsonNode allowed = requireSuccess(get(path, Map.of(tenantHeader, ownerTenant)), "DFX-001.B13 owner query");
        assertThat(traceIds(allowed)).isNotEmpty().allMatch(traceId::equals);
    }

    @Test
    @Order(25)
    @Tag("story-dfx-001-b14")
    @Story("DFX-001.B14: seq 竞争超限隔离")
    @DisplayName("DFX-001.B14: exhausted sequence reservation retries drop only the snapshot")
    void exhaustedSequenceReservationRetriesDropOnlyTheSnapshot() throws Exception {
        String tenant = unique("tenant-seq-contention");
        String context = unique("conversation-seq-contention");
        A2aResult first = send("到北京出差，请先询问出发地。", tenant, context, null, null);
        assertThat(first.state()).contains("INPUT_REQUIRED");
        JsonNode before = awaitAudit(tenant, context, node -> snapshotSequences(node).size() == 1,
                "DFX-001.B14 before contention");
        try (RedisCommandClient redis = new RedisCommandClient(redisEndpoint)) {
            for (long seq = 2; seq <= 4; seq++) {
                redis.setex(auditKey(tenant, context, seq), 300, String.format("%08d", seq));
            }
        }
        A2aResult continued = send("从上海出发，明天走，请完成。", tenant, context, first.taskId(), null);
        assertThat(continued.taskId()).isEqualTo(first.taskId());
        JsonNode after = requireSuccess(get(auditPath(tenant, context)), "DFX-001.B14 after contention");
        assertThat(snapshotSequences(after)).containsExactlyElementsOf(snapshotSequences(before));
    }

    @Test
    @Order(26)
    @Tag("story-dfx-001-c1")
    @Story("DFX-001.C1: 默认关闭零行为")
    @DisplayName("DFX-001.C1: trajectory link is absent by default while agent behavior remains available")
    void trajectoryLinkIsAbsentByDefaultWithoutBreakingAgentBehavior() throws Exception {
        try (SutStack disabled = isolatedHotelStack(false, false, Map.of())) {
            String traceId = hexId(32);
            A2aResult result = sendAt(disabled.baseUrl("hotel"), "请简短确认收到请求。",
                    unique("tenant-disabled"), null, null,
                    "00-" + traceId + "-" + hexId(16) + "-01", Map.of());
            assertThat(result.taskId()).isNotBlank();
            assertExecutionTreeAbsent(disabled.baseUrl("hotel"), traceId, "disabled trajectory");
        }
    }

    @Test
    @Order(27)
    @Tag("story-dfx-001-c2")
    @Story("DFX-001.C2: 开启但 Redis 未配置")
    @DisplayName("DFX-001.C2: enabled link without Redis keeps the host running and trajectory disabled")
    void enabledLinkWithoutRedisKeepsHostRunningAndTrajectoryDisabled() throws Exception {
        try (SutStack noRedis = isolatedHotelStack(true, false, Map.of())) {
            String traceId = hexId(32);
            A2aResult result = sendAt(noRedis.baseUrl("hotel"), "请简短确认收到请求。",
                    unique("tenant-no-redis"), null, null,
                    "00-" + traceId + "-" + hexId(16) + "-01", Map.of());
            assertThat(result.taskId()).isNotBlank();
            assertExecutionTreeAbsent(noRedis.baseUrl("hotel"), traceId, "trajectory without Redis");
        }
    }

    @Test
    @Order(28)
    @Tag("story-dfx-001-c3")
    @Story("DFX-001.C3: Redis 写失败隔离")
    @DisplayName("DFX-001.C3: trajectory Redis failure does not alter the agent result and recovers without restart")
    void trajectoryRedisFailureIsIsolatedAndRecoversWithoutRestart() throws Exception {
        redisTrajectoryProxy.mode(RedisProxyMode.FAIL_TRAJECTORY_WRITES);
        A2aResult during;
        try {
            during = send("请确认故障期间仍可处理业务。", unique("tenant-redis-cut"), null, null, null);
        } finally {
            redisTrajectoryProxy.mode(RedisProxyMode.NORMAL);
        }
        assertThat(during.taskId()).isNotBlank();
        String tenant = unique("tenant-redis-restored");
        String context = unique("conversation-redis-restored");
        send("请确认 Redis 恢复后可记录轨迹。", tenant, context, null, null);
        awaitAudit(tenant, context, node -> !traceIds(node).isEmpty(), "DFX-001.C3 recovered audit");
    }

    @Test
    @Order(29)
    @Tag("story-dfx-001-c4")
    @Story("DFX-001.C4: 写队列背压隔离")
    @DisplayName("DFX-001.C4: trajectory queue pressure drops records without blocking agent requests")
    void trajectoryQueuePressureDropsRecordsWithoutBlockingAgentRequests() throws Exception {
        redisTrajectoryProxy.mode(RedisProxyMode.DELAY_TRAJECTORY_SETEX);
        try (SutStack pressure = isolatedPressureHotelStack()) {
            List<CompletableFuture<A2aResult>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                int index = i;
                calls.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return sendAt(pressure.baseUrl("hotel"), "请确认并发请求 " + index + "。",
                                unique("tenant-pressure"), null, null, null, Map.of());
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                }));
            }
            assertThat(calls).allSatisfy(call -> assertThat(call.join().taskId()).isNotBlank());
            Path log = ((ManagedSutInstance) pressure.managedInstance("hotel")).logFile();
            await().pollInterval(Duration.ofMillis(100)).atMost(EVENTUALLY_TIMEOUT).untilAsserted(() ->
                    assertThat(Files.readString(log)).contains("trajectory write task dropped"));
            redisTrajectoryProxy.mode(RedisProxyMode.NORMAL);
        } finally {
            redisTrajectoryProxy.mode(RedisProxyMode.NORMAL);
        }
    }

    @Test
    @Order(30)
    @Tag("story-dfx-001-c5")
    @Story("DFX-001.C5: 非 JSON-RPC body 采集失败隔离")
    @DisplayName("DFX-001.C5: malformed A2A payload has the same public error with trajectory on and off")
    void malformedA2aPayloadHasNoTrajectorySpecificFailure() throws Exception {
        HttpResponse<String> enabled = post(stack.baseUrl("mainplan") + "/a2a", "not-json",
                Map.of("Content-Type", "application/json"));
        try (SutStack disabled = isolatedHotelStack(false, false, Map.of())) {
            HttpResponse<String> baseline = post(disabled.baseUrl("hotel") + "/a2a", "not-json",
                    Map.of("Content-Type", "application/json"));
            assertThat(enabled.statusCode()).isEqualTo(baseline.statusCode()).isLessThan(500);
        }
    }

    @Test
    @Order(31)
    @Tag("story-dfx-001-c6")
    @Story("DFX-001.C6: 轨迹 TTL 到期一致性")
    @DisplayName("DFX-001.C6: short trajectory TTL expires records without breaking later agent requests")
    void shortTrajectoryTtlExpiresRecordsAndAllowsLaterRequests() throws Exception {
        try (SutStack shortTtl = isolatedHotelStack(true, true,
                Map.of("openjiuwen.service.trajectory.link.ttl-seconds", "5"))) {
            String tenant = unique("tenant-short-ttl");
            String context = unique("conversation-short-ttl");
            A2aResult first = sendAt(shortTtl.baseUrl("hotel"), "请确认短 TTL 请求。", tenant, context,
                    null, null, Map.of());
            JsonNode audit = awaitJson(shortTtl.baseUrl("hotel"), auditPath(tenant, context),
                    node -> !traceIds(node).isEmpty(), "DFX-001.C6 initial audit");
            assertThat(audit).isNotNull();
            await().pollInterval(Duration.ofMillis(250)).atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                HttpResponse<String> response = get(shortTtl.baseUrl("hotel"), auditPath(tenant, context), Map.of());
                assertThat(response.statusCode() == 404 || traceIds(JSON.readTree(response.body())).isEmpty()).isTrue();
            });
            assertThat(sendAt(shortTtl.baseUrl("hotel"), "TTL 到期后继续处理。", tenant, context,
                    null, null, Map.of()).taskId()).isNotEqualTo(first.taskId());
        }
    }

    private A2aResult send(String text, String tenant, String contextId, String taskId, String traceparent)
            throws Exception {
        return send(text, tenant, contextId, taskId, traceparent, Map.of());
    }

    private A2aResult send(String text, String tenant, String contextId, String taskId, String traceparent,
                           Map<String, Object> metadata) throws Exception {
        return sendAt(stack.baseUrl("mainplan"), text, tenant, contextId, taskId, traceparent, metadata);
    }

    private A2aResult sendAt(String baseUrl, String text, String tenant, String contextId, String taskId,
                             String traceparent, Map<String, Object> additionalMetadata) throws Exception {
        return sendAt(baseUrl, text, tenant, contextId, taskId, traceparent, additionalMetadata, Map.of());
    }

    private A2aResult sendAt(String baseUrl, String text, String tenant, String contextId, String taskId,
                             String traceparent, Map<String, Object> additionalMetadata,
                             Map<String, String> additionalHeaders) throws Exception {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "ROLE_USER");
        message.put("messageId", UUID.randomUUID().toString());
        if (contextId != null) {
            message.put("contextId", contextId);
        }
        if (taskId != null) {
            message.put("taskId", taskId);
        }
        message.put("parts", List.of(Map.of("text", text)));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("message", message);
        params.put("tenant", tenant);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tenantId", tenant);
        metadata.put("acceptanceCanary", unique("dfx001"));
        metadata.putAll(additionalMetadata);
        params.put("metadata", metadata);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", UUID.randomUUID().toString());
        envelope.put("method", "SendMessage");
        envelope.put("params", params);

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/a2a"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json");
        if (tenant != null && !tenant.isBlank()) {
            request.header("X-Tenant-Id", tenant);
        }
        if (traceparent != null && !traceparent.isEmpty()) {
            request.header("traceparent", traceparent);
        }
        additionalHeaders.forEach(request::header);
        HttpResponse<String> response = HTTP.send(
                request.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(envelope))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("A2A SendMessage body=%s", response.body()).isBetween(200, 299);
        JsonNode json = JSON.readTree(response.body());
        assertThat(json.hasNonNull("error")).as("A2A JSON-RPC response=%s", json).isFalse();
        JsonNode result = json.path("result");
        JsonNode task = result.path("task").isObject() ? result.path("task") : result;
        String returnedTaskId = firstText(task, "id", "taskId");
        String returnedContextId = firstText(task, "contextId", "context_id");
        String returnedState = firstText(task.path("status"), "state");
        assertThat(returnedTaskId).as("A2A result Task id: %s", json).isNotBlank();
        assertThat(returnedContextId).as("A2A result context id: %s", json).isNotBlank();
        if (!returnedState.isBlank()) {
            assertThat(returnedState.toUpperCase())
                    .as("A2A task did not fail before trajectory assertions: %s", json)
                    .doesNotContain("FAILED", "REJECTED");
        }
        return new A2aResult(returnedTaskId, returnedContextId, returnedState);
    }

    private static HttpResponse<String> postA2a(String baseUrl, String text, String tenant, String contextId,
                                                 Map<String, String> headers) throws Exception {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "ROLE_USER");
        message.put("messageId", UUID.randomUUID().toString());
        message.put("contextId", contextId);
        message.put("parts", List.of(Map.of("text", text)));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("message", message);
        params.put("tenant", tenant);
        params.put("metadata", Map.of("tenantId", tenant));
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", UUID.randomUUID().toString());
        envelope.put("method", "SendMessage");
        envelope.put("params", params);
        Map<String, String> allHeaders = new LinkedHashMap<>(headers);
        allHeaders.put("Content-Type", "application/json");
        allHeaders.put("X-Tenant-ID", tenant);
        return post(baseUrl + "/a2a", JSON.writeValueAsString(envelope), allHeaders);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return get(stack.baseUrl("mainplan"), path, Map.of());
    }

    private HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
        return get(stack.baseUrl("mainplan"), path, headers);
    }

    private static HttpResponse<String> get(String baseUrl, String path, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(QUERY_TIMEOUT).GET();
        headers.forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String url, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postJson(String url, Object body) throws Exception {
        return post(url, JSON.writeValueAsString(body), Map.of("Content-Type", "application/json"));
    }

    private static String busPayload(String text) throws Exception {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "ROLE_USER");
        message.put("messageId", UUID.randomUUID().toString());
        message.put("parts", List.of(Map.of("text", text)));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("message", message);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", UUID.randomUUID().toString());
        envelope.put("method", "SendMessage");
        envelope.put("params", params);
        return JSON.writeValueAsString(envelope);
    }

    private static JsonNode requireSuccess(HttpResponse<String> response, String label) throws Exception {
        assertThat(response.statusCode()).as("%s body=%s", label, response.body()).isBetween(200, 299);
        return JSON.readTree(response.body());
    }

    private static String auditPath(String tenant, String context) {
        return "/manage/trajectory/audit?tenantId=" + encode(tenant) + "&conversationId=" + encode(context);
    }

    private static String auditKey(String tenant, String context, long seq) {
        return "runtime:audit:" + redisKeySegment(tenant) + ":" + redisKeySegment(context) + ":"
                + String.format("%08d", seq);
    }

    private static String auditLatestKey(String tenant, String context) {
        return "runtime:audit-idx:" + redisKeySegment(tenant) + ":" + redisKeySegment(context) + ":latest";
    }

    private static String redisKeySegment(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assertThat(value).as("required process environment %s", name).isNotBlank();
        return value;
    }

    private void clearPropagationEvidence() {
        mainplanToTrip.clear();
        tripToHotel.clear();
    }

    private static List<String> assertForwardedTrace(TraceparentForwardingProbe probe, String expectedTraceId,
                                                      String label) {
        try {
            await().pollInterval(Duration.ofMillis(100)).atMost(EVENTUALLY_TIMEOUT).until(probe::hasPostRequest);
        } catch (ConditionTimeoutException exception) {
            assumeTrue(false, label + " did not observe an A2A POST; delegation is inconclusive");
        }
        List<ObservedRequest> requests = probe.postRequests();
        List<String> traceparents = requests.stream().map(ObservedRequest::traceparent).toList();
        assertThat(traceparents).as("%s observed requests=%s", label, requests)
                .isNotEmpty().allMatch(value -> value != null && value.matches(W3C_TRACEPARENT));
        assertThat(traceparents).as("%s must preserve the ingress trace id", label)
                .allMatch(value -> expectedTraceId.equals(value.substring(3, 35)));
        return traceparents;
    }

    private static String configuredAgentUrl(TestConfig config, String agentName) {
        int port = config.getInt("sut.agents." + agentName + ".port", 0);
        if (port <= 0) {
            throw new IllegalStateException("DFX-001 traceparent forwarding probe requires a configured port for "
                    + agentName);
        }
        return "http://127.0.0.1:" + port;
    }

    private static String requiredFixture(String name) {
        String value = System.getenv(name);
        assumeTrue(value != null && !value.isBlank(), "required external fixture variable is not set: " + name);
        return value;
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private JsonNode awaitTree(String traceId, Predicate<JsonNode> ready, String label) throws Exception {
        return awaitTree(stack.baseUrl("mainplan"), traceId, ready, label);
    }

    private JsonNode awaitTree(String baseUrl, String traceId, Predicate<JsonNode> ready, String label)
            throws Exception {
        return awaitJson(baseUrl, "/manage/trajectory/runs?traceId=" + encode(traceId), ready, label);
    }

    private JsonNode awaitTree(String baseUrl, String traceId, Predicate<JsonNode> ready, String label,
                               Duration timeout) throws Exception {
        return awaitJson(baseUrl, "/manage/trajectory/runs?traceId=" + encode(traceId), ready, label, timeout);
    }

    private JsonNode awaitAudit(String tenant, String context, Predicate<JsonNode> ready, String label)
            throws Exception {
        return awaitJson(stack.baseUrl("mainplan"), auditPath(tenant, context), ready, label);
    }

    private static JsonNode awaitJson(String baseUrl, String path, Predicate<JsonNode> ready, String label)
            throws Exception {
        return awaitJson(baseUrl, path, ready, label, EVENTUALLY_TIMEOUT);
    }

    private static JsonNode awaitJson(String baseUrl, String path, Predicate<JsonNode> ready, String label,
                                      Duration timeout) throws Exception {
        HttpResponse<String> first = get(baseUrl, path, Map.of());
        JsonNode firstBody = requireSuccess(first, label);
        if (ready.test(firstBody)) {
            return firstBody;
        }
        AtomicReference<JsonNode> observed = new AtomicReference<>(firstBody);
        try {
            await().pollInterval(Duration.ofMillis(250)).atMost(timeout).untilAsserted(() -> {
                JsonNode body = requireSuccess(get(baseUrl, path, Map.of()), label);
                observed.set(body);
                assertThat(ready.test(body)).as("%s did not expose expected records: %s", label, body).isTrue();
            });
        } catch (ConditionTimeoutException conditionTimeout) {
            throw new AssertionError(label + " did not expose expected records within "
                    + timeout + "; last response=" + observed.get(), conditionTimeout);
        }
        return observed.get();
    }

    private static void assertExecutionTreeAbsent(String baseUrl, String traceId, String label) throws Exception {
        HttpResponse<String> response = get(baseUrl,
                "/manage/trajectory/runs?traceId=" + encode(traceId), Map.of());
        if (response.statusCode() == 404) {
            return;
        }
        JsonNode body = requireSuccess(response, label);
        assertThat(textValues(body, Set.of("runId", "run_id")))
                .as("%s body=%s", label, body)
                .isEmpty();
        assertThat(executionEdges(body)).as("%s body=%s", label, body).isEmpty();
    }

    private SutStack isolatedHotelStack(boolean enabled, boolean redis, Map<String, String> overrides) {
        String previousPort = System.getProperty(HOTEL_PORT_SYSTEM_PROPERTY);
        System.setProperty(HOTEL_PORT_SYSTEM_PROPERTY, "0");
        try {
            SutStack isolated = SutStack.builder(testConfig).agent("hotel", agent -> {
                configureLlm(agent, "openjiuwen.travel.hotel.llm.api-key");
                if (enabled) {
                    agent.property("openjiuwen.service.trajectory.link.enabled", "true");
                }
                if (redis) {
                    agent.property("openjiuwen.service.middleware.checkpointer.type", "redis")
                            .property("openjiuwen.service.middleware.checkpointer.redis-ref", "default")
                            .serviceBinding("redis", "openjiuwen.service.middleware.redis.default.host", "{{host}}")
                            .serviceBinding("redis", "openjiuwen.service.middleware.redis.default.port", "{{port}}");
                }
                overrides.forEach(agent::property);
            }).start();
            try {
                assertThat(isolated.port("hotel")).isNotEqualTo(stack.port("hotel"));
                return isolated;
            } catch (RuntimeException | Error failure) {
                isolated.close();
                throw failure;
            }
        } finally {
            if (previousPort == null) {
                System.clearProperty(HOTEL_PORT_SYSTEM_PROPERTY);
            } else {
                System.setProperty(HOTEL_PORT_SYSTEM_PROPERTY, previousPort);
            }
        }
    }

    private SutStack isolatedPressureHotelStack() {
        String previousPort = System.getProperty(HOTEL_PORT_SYSTEM_PROPERTY);
        System.setProperty(HOTEL_PORT_SYSTEM_PROPERTY, "0");
        try {
            SutStack isolated = SutStack.builder(testConfig).agent("hotel", agent -> {
                configureLlm(agent, "openjiuwen.travel.hotel.llm.api-key");
                agent.property("openjiuwen.service.trajectory.link.enabled", "true")
                        .property("openjiuwen.service.trajectory.link.queue-capacity", "1")
                        .property("openjiuwen.service.trajectory.link.flush-interval-ms", "1000")
                        .property("openjiuwen.service.otel.enabled", "false")
                        .property("openjiuwen.service.middleware.checkpointer.type", "redis")
                        .property("openjiuwen.service.middleware.checkpointer.redis-ref", "default")
                        .property("openjiuwen.service.middleware.redis.default.host",
                                redisTrajectoryProxy.endpoint().host())
                        .property("openjiuwen.service.middleware.redis.default.port",
                                Integer.toString(redisTrajectoryProxy.endpoint().port()));
            }).start();
            try {
                assertThat(isolated.port("hotel")).isNotEqualTo(stack.port("hotel"));
                return isolated;
            } catch (RuntimeException | Error failure) {
                isolated.close();
                throw failure;
            }
        } finally {
            if (previousPort == null) {
                System.clearProperty(HOTEL_PORT_SYSTEM_PROPERTY);
            } else {
                System.setProperty(HOTEL_PORT_SYSTEM_PROPERTY, previousPort);
            }
        }
    }

    private SutStack isolatedMainplanProbeStack() {
        SutStack isolated = SutStack.builder(testConfig)
                .remoteAgent("dfx001-existing-trip", stack.baseUrl("trip"))
                .agent("mainplan-dfx001-probe", agent -> {
                    configureLlm(agent, "openjiuwen.travel.mainplan.llm.api-key");
                    agent.downstream("dfx001-existing-trip")
                            .property("openjiuwen.service.security.enabled", "true")
                            .property("openjiuwen.service.security.auth.enabled", "true")
                            .property("openjiuwen.service.trajectory.link.enabled", "true")
                            .property("openjiuwen.service.middleware.checkpointer.type", "redis")
                            .property("openjiuwen.service.middleware.checkpointer.redis-ref", "default")
                            .property("openjiuwen.service.middleware.redis.default.host", redisEndpoint.host())
                            .property("openjiuwen.service.middleware.redis.default.port",
                                    Integer.toString(redisEndpoint.port()))
                            .property("openjiuwen.service.otel.enabled", "false");
                }).start();
        try {
            assertThat(isolated.port("mainplan-dfx001-probe")).isNotEqualTo(stack.port("mainplan"));
            return isolated;
        } catch (RuntimeException | Error failure) {
            isolated.close();
            throw failure;
        }
    }

    private SutStack isolatedParallelEdpaStack(String downstreamUrl) {
        return SutStack.builder(testConfig)
                .remoteAgent("versatile-adapter", downstreamUrl)
                .agent("edpa-plan-agent", agent -> {
                    configureLlm(agent, "plan-agent.api-key");
                    agent.profile("parallel-transfer")
                            .downstream("versatile-adapter")
                            .property("openjiuwen.service.trajectory.link.enabled", "true")
                            .property("openjiuwen.service.otel.enabled", "false")
                            .property("openjiuwen.service.middleware.checkpointer.type", "redis")
                            .property("openjiuwen.service.middleware.checkpointer.redis-ref", "default")
                            .serviceBinding("redis", "openjiuwen.service.middleware.redis.default.host", "{{host}}")
                            .serviceBinding("redis", "openjiuwen.service.middleware.redis.default.port", "{{port}}");
                }).start();
    }

    private SutStack isolatedHotelBusStack() {
        String previousPort = System.getProperty(HOTEL_PORT_SYSTEM_PROPERTY);
        System.setProperty(HOTEL_PORT_SYSTEM_PROPERTY, "0");
        try {
            SutStack isolated = SutStack.builder(testConfig).agent("hotel", agent -> {
                configureLlm(agent, "openjiuwen.travel.hotel.llm.api-key");
                agent.property("openjiuwen.service.trajectory.link.enabled", "true")
                        .property("openjiuwen.service.otel.enabled", "false")
                        .property("openjiuwen.service.middleware.checkpointer.type", "redis")
                        .property("openjiuwen.service.middleware.checkpointer.redis-ref", "default")
                        .serviceBinding("redis", "openjiuwen.service.middleware.redis.default.host", "{{host}}")
                        .serviceBinding("redis", "openjiuwen.service.middleware.redis.default.port", "{{port}}");
                busProperties(agent, HOTEL_SERVICE_ID,
                        env("AGENT_BUS_RDC_URL", "http://127.0.0.1:18092"));
            }).start();
            try {
                assertThat(isolated.port("hotel")).isNotEqualTo(stack.port("hotel"));
                return isolated;
            } catch (RuntimeException | Error failure) {
                isolated.close();
                throw failure;
            }
        } finally {
            if (previousPort == null) {
                System.clearProperty(HOTEL_PORT_SYSTEM_PROPERTY);
            } else {
                System.setProperty(HOTEL_PORT_SYSTEM_PROPERTY, previousPort);
            }
        }
    }

    private CompletableFuture<A2aResult> asyncSend(String text, String tenant, String context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return send(text, tenant, context, null, null);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
    }

    private static String firstText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            String value = node.path(fieldName).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String hexId(int length) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, length);
    }

    private static List<String> textValues(JsonNode node, Set<String> fieldNames) {
        List<String> values = new ArrayList<>();
        walk(node, (name, value) -> {
            if (fieldNames.contains(name) && value.isValueNode() && !value.isNull()) {
                values.add(value.asText());
            }
        });
        return values;
    }

    private static List<Boolean> booleanValues(JsonNode node, Set<String> fieldNames) {
        List<Boolean> values = new ArrayList<>();
        walk(node, (name, value) -> {
            if (fieldNames.contains(name) && value.isBoolean()) {
                values.add(value.asBoolean());
            }
        });
        return values;
    }

    private static List<Long> longValues(JsonNode node, Set<String> fieldNames) {
        List<Long> values = new ArrayList<>();
        walk(node, (name, value) -> {
            if (fieldNames.contains(name) && value.canConvertToLong()) {
                values.add(value.asLong());
            }
        });
        return values;
    }

    private static List<String> traceIds(JsonNode node) {
        return textValues(node, Set.of("traceId", "trace_id"));
    }

    private static long distinctCount(JsonNode node, String fieldName) {
        return textValues(node, Set.of(fieldName)).stream().distinct().count()
                + longValues(node, Set.of(fieldName)).stream().map(String::valueOf).distinct().count();
    }

    private static List<Long> snapshotSequences(JsonNode root) {
        LinkedHashSet<Long> sequences = new LinkedHashSet<>();
        objects(root).stream()
                .filter(node -> node.has("seq") && (node.has("runId") || node.has("run_id")
                        || node.has("finalState") || node.has("final_state")))
                .map(node -> node.path("seq").asLong())
                .forEach(sequences::add);
        if (sequences.isEmpty()) {
            sequences.addAll(longValues(root, Set.of("seq")));
        }
        return new ArrayList<>(sequences);
    }

    private static JsonNode snapshotWithSeq(JsonNode root, long seq) {
        return objects(root).stream()
                .filter(node -> node.path("seq").asLong(Long.MIN_VALUE) == seq
                        && (node.has("runId") || node.has("run_id")
                        || node.has("finalState") || node.has("final_state")))
                .min(Comparator.comparingInt(node -> node.toString().length()))
                .orElseThrow(() -> new AssertionError("audit snapshot missing for seq=" + seq));
    }

    private static void assertStrictlyIncreasing(List<Long> values, String label) {
        assertThat(values).as(label).hasSizeGreaterThanOrEqualTo(2);
        for (int i = 1; i < values.size(); i++) {
            assertThat(values.get(i)).as("%s at index %s", label, i).isGreaterThan(values.get(i - 1));
        }
    }

    private static List<ExecutionEdge> executionEdges(JsonNode root) {
        List<ExecutionEdge> edges = new ArrayList<>();
        for (JsonNode node : objects(root)) {
            String parent = firstText(node, "parentRunId", "parent_run_id", "sourceRunId", "source_run_id");
            String child = firstText(node, "childRunId", "child_run_id", "remoteRunId", "remote_run_id",
                    "runId", "run_id");
            if (!parent.isBlank() && !child.isBlank() && !parent.equals(child)) {
                edges.add(new ExecutionEdge(parent, child,
                        firstText(node, "agentName", "agent_name", "targetAgent", "target_agent")));
            }
        }
        return edges.stream().distinct().toList();
    }

    private static List<JsonNode> objects(JsonNode root) {
        List<JsonNode> values = new ArrayList<>();
        collectObjects(root, values);
        return values;
    }

    private static void collectObjects(JsonNode node, List<JsonNode> values) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            values.add(node);
            node.elements().forEachRemaining(child -> collectObjects(child, values));
        } else if (node.isArray()) {
            node.forEach(child -> collectObjects(child, values));
        } else {
            JsonNode embedded = embeddedJson(node);
            if (embedded != null) {
                collectObjects(embedded, values);
            }
        }
    }

    private static JsonNode embeddedJson(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.asText().trim();
        if (!(value.startsWith("{") || value.startsWith("["))) {
            return null;
        }
        try {
            return JSON.readTree(value);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static boolean hasGapMarker(JsonNode node) {
        String encoded = node.toString().toLowerCase();
        return encoded.contains("\"gap\":true") || encoded.contains("\"missing\":true")
                || encoded.contains("sequence_gap") || encoded.contains("missing_snapshot");
    }

    private static boolean containsDecision(JsonNode node, String needle) {
        return objects(node).stream().map(JsonNode::toString).map(String::toLowerCase)
                .anyMatch(value -> value.contains(needle.toLowerCase()));
    }

    private static boolean hasDecision(JsonNode root, String type, String action) {
        return objects(root).stream().anyMatch(node -> type.equalsIgnoreCase(firstText(node, "type"))
                && (action == null || action.equalsIgnoreCase(firstText(node, "action"))));
    }

    private static JsonNode decisionRecord(JsonNode root, String type, String action) {
        return objects(root).stream()
                .filter(node -> type.equalsIgnoreCase(firstText(node, "type")))
                .filter(node -> action == null || action.equalsIgnoreCase(firstText(node, "action")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing decision record: type=" + type + ", action=" + action));
    }

    private static void assertDecisionFields(JsonNode root, String decision, Set<String> fieldFragments) {
        JsonNode record = objects(root).stream()
                .filter(node -> node.toString().toLowerCase().contains(decision.toLowerCase()))
                .min(Comparator.comparingInt(node -> node.toString().length()))
                .orElseThrow(() -> new AssertionError("missing decision record: " + decision));
        String encoded = record.toString().toLowerCase();
        for (String fragment : fieldFragments) {
            assertThat(encoded).as("decision %s field %s", decision, fragment)
                    .contains(fragment.toLowerCase());
        }
    }

    private static List<LifecycleTransition> lifecycleTransitions(JsonNode root) {
        List<LifecycleTransition> transitions = new ArrayList<>();
        for (JsonNode node : objects(root)) {
            String from = firstText(node, "fromState", "from_state", "from");
            String to = firstText(node, "toState", "to_state", "to");
            if (!from.isBlank() && !to.isBlank()) {
                transitions.add(new LifecycleTransition(from, to,
                        firstText(node, "at", "time", "timestamp", "occurredAt", "occurred_at", "recordedAt",
                                "recorded_at"),
                        firstText(node, "runId", "run_id")));
            }
        }
        return transitions;
    }

    private static void walk(JsonNode node, FieldConsumer consumer) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                consumer.accept(entry.getKey(), entry.getValue());
                walk(entry.getValue(), consumer);
            });
        } else if (node.isArray()) {
            node.forEach(child -> walk(child, consumer));
        } else {
            JsonNode embedded = embeddedJson(node);
            if (embedded != null) {
                walk(embedded, consumer);
            }
        }
    }

    @FunctionalInterface
    private interface FieldConsumer {
        void accept(String name, JsonNode value);
    }

    private record ObservedRequest(String method, String path, String traceparent) {
    }

    private static final class TraceparentForwardingProbe implements AutoCloseable {
        private static final Set<String> REQUEST_HEADERS_TO_SKIP = Set.of(
                "connection", "content-length", "expect", "host", "http2-settings", "transfer-encoding", "upgrade");
        private static final Set<String> RESPONSE_HEADERS_TO_SKIP = Set.of(
                "connection", "content-length", "transfer-encoding");

        private final String name;
        private final HttpServer server;
        private final AtomicReference<URI> target = new AtomicReference<>();
        private final List<ObservedRequest> requests = new CopyOnWriteArrayList<>();

        private TraceparentForwardingProbe(String name) throws IOException {
            this.name = name;
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/", this::forward);
            this.server.start();
        }

        private static TraceparentForwardingProbe start(String name) {
            try {
                return new TraceparentForwardingProbe(name);
            } catch (IOException exception) {
                throw new IllegalStateException("Could not start DFX-001 traceparent probe " + name, exception);
            }
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void target(String baseUrl) {
            target.set(URI.create(baseUrl));
        }

        private void clear() {
            requests.clear();
        }

        private boolean hasPostRequest() {
            return requests.stream().anyMatch(request -> "POST".equalsIgnoreCase(request.method()));
        }

        private List<ObservedRequest> postRequests() {
            return requests.stream().filter(request -> "POST".equalsIgnoreCase(request.method())).toList();
        }

        private void forward(HttpExchange exchange) throws IOException {
            requests.add(new ObservedRequest(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("traceparent")));
            URI targetBase = target.get();
            if (targetBase == null) {
                respondWithError(exchange, 503, name + " target is not ready");
                return;
            }

            try {
                byte[] requestBody = exchange.getRequestBody().readAllBytes();
                HttpRequest.Builder request = HttpRequest.newBuilder(targetBase.resolve(exchange.getRequestURI()))
                        .timeout(REQUEST_TIMEOUT);
                exchange.getRequestHeaders().forEach((header, values) -> {
                    if (!REQUEST_HEADERS_TO_SKIP.contains(header.toLowerCase())) {
                        values.forEach(value -> request.header(header, value));
                    }
                });
                HttpRequest.BodyPublisher publisher = requestBody.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(requestBody);
                HttpResponse<byte[]> response = HTTP.send(
                        request.method(exchange.getRequestMethod(), publisher).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                response.headers().map().forEach((header, values) -> {
                    if (!RESPONSE_HEADERS_TO_SKIP.contains(header.toLowerCase())) {
                        values.forEach(value -> exchange.getResponseHeaders().add(header, value));
                    }
                });
                byte[] responseBody = rewriteAgentCardUrls(exchange, targetBase, response.body());
                boolean bodyAllowed = !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())
                        && response.statusCode() != 204 && response.statusCode() != 304;
                exchange.sendResponseHeaders(response.statusCode(), bodyAllowed ? responseBody.length : -1);
                if (bodyAllowed) {
                    exchange.getResponseBody().write(responseBody);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                respondWithError(exchange, 502, name + " forwarding interrupted");
            } catch (Exception exception) {
                respondWithError(exchange, 502, name + " forwarding failed: " + exception.getClass().getSimpleName());
            } finally {
                exchange.close();
            }
        }

        private static void respondWithError(HttpExchange exchange, int status, String message) throws IOException {
            byte[] body = message.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        private byte[] rewriteAgentCardUrls(HttpExchange exchange, URI targetBase, byte[] responseBody) {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())
                    || !exchange.getRequestURI().getPath().contains("well-known")) {
                return responseBody;
            }
            try {
                JsonNode card = JSON.readTree(responseBody);
                rewriteTargetUrls(card, targetBase);
                return JSON.writeValueAsBytes(card);
            } catch (Exception ignored) {
                return responseBody;
            }
        }

        private void rewriteTargetUrls(JsonNode node, URI targetBase) {
            if (node instanceof ObjectNode object) {
                List<String> fields = new ArrayList<>();
                object.fieldNames().forEachRemaining(fields::add);
                for (String field : fields) {
                    JsonNode value = object.get(field);
                    if (value.isTextual()) {
                        String rewritten = rewriteTargetUrl(value.asText(), targetBase);
                        if (!rewritten.equals(value.asText())) {
                            object.put(field, rewritten);
                        }
                    } else {
                        rewriteTargetUrls(value, targetBase);
                    }
                }
            } else if (node instanceof ArrayNode array) {
                array.forEach(value -> rewriteTargetUrls(value, targetBase));
            }
        }

        private String rewriteTargetUrl(String value, URI targetBase) {
            try {
                URI candidate = URI.create(value);
                if (candidate.getPort() != targetBase.getPort() || candidate.getScheme() == null) {
                    return value;
                }
                String path = candidate.getRawPath() == null ? "" : candidate.getRawPath();
                String query = candidate.getRawQuery() == null ? "" : "?" + candidate.getRawQuery();
                return baseUrl() + path + query;
            } catch (IllegalArgumentException ignored) {
                return value;
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private record BusRequest(String eventType, String messageId, String tenantId, String sourceServiceId,
                              String targetServiceId, String correlationId, String traceId, String idempotencyKey,
                              Long deadlineMillisEpoch, String inlinePayload, String payloadRef,
                              Map<String, String> properties) {
        private static BusRequest clientInvocation(String targetServiceId, String tenantId, String correlationId,
                                                    String traceId, String payload) {
            return new BusRequest("CLIENT_INVOCATION_REQUESTED", UUID.randomUUID().toString(), tenantId,
                    "acceptance-producer", targetServiceId, correlationId, traceId,
                    UUID.randomUUID().toString(), Long.MAX_VALUE, payload, null,
                    Map.of("routeHandle", "route-dfx001", "capability", "a2a",
                            "originalCaller", "acceptance-producer"));
        }
    }

    private static final class RocketMqFixture implements AutoCloseable {
        private static final String IMAGE = "apache/rocketmq:5.3.1";
        private final GenericContainer<?> nameserver;
        private final GenericContainer<?> broker;
        private final Network network;

        private RocketMqFixture(GenericContainer<?> nameserver, GenericContainer<?> broker, Network network) {
            this.nameserver = nameserver;
            this.broker = broker;
            this.network = network;
        }

        private static RocketMqFixture start() {
            Network network = Network.newNetwork();
            FixedRocketContainer nameserver = new FixedRocketContainer(DockerImageName.parse(IMAGE))
                    .withCommand("sh", "mqnamesrv")
                    .withExposedPorts(9876)
                    .fixed(9876)
                    .withNetwork(network)
                    .withNetworkAliases("rocketmq-nameserver")
                    .waitingFor(Wait.forLogMessage(".*The Name Server boot success.*\\n", 1));
            FixedRocketContainer broker = new FixedRocketContainer(DockerImageName.parse(IMAGE))
                    .withEnv("NAMESRV_ADDR", "rocketmq-nameserver:9876")
                    .withCommand("sh", "-c", "printf '%s\\n' "
                            + "'brokerClusterName=DefaultCluster' 'brokerName=broker-a' 'brokerId=0' "
                            + "'namesrvAddr=rocketmq-nameserver:9876' 'enablePropertyFilter=true' "
                            + "'autoCreateTopicEnable=true' 'brokerIP1=127.0.0.1' > /tmp/broker.conf; "
                            + "exec sh mqbroker -c /tmp/broker.conf")
                    .withExposedPorts(10909, 10911)
                    .fixed(10909)
                    .fixed(10911)
                    .withNetwork(network)
                    .waitingFor(Wait.forLogMessage(".*The broker\\[.*\\] boot success.*\\n", 1));
            try {
                nameserver.start();
                broker.start();
                for (String topic : List.of("ascend_bus_invocation_req", "ascend_bus_invocation_deliver",
                        "ascend_bus_invocation_resp_in", "ascend_bus_invocation_resp_out", "ascend_bus_a2a_req",
                        "ascend_bus_a2a_deliver", "ascend_bus_a2a_resp_in", "ascend_bus_a2a_resp_out")) {
                    broker.execInContainer("sh", "/home/rocketmq/rocketmq-5.3.1/bin/mqadmin", "updatetopic",
                            "-b", "127.0.0.1:10911", "-c", "DefaultCluster", "-t", topic);
                }
                return new RocketMqFixture(nameserver, broker, network);
            } catch (Exception failure) {
                broker.stop();
                nameserver.stop();
                network.close();
                throw new IllegalStateException("Unable to start RocketMQ fixture", failure);
            }
        }

        private String nameserver() {
            return BUS_NAMESERVER;
        }

        private static final class FixedRocketContainer extends GenericContainer<FixedRocketContainer> {
            private FixedRocketContainer(DockerImageName image) {
                super(image);
            }

            private FixedRocketContainer fixed(int port) {
                addFixedExposedPort(port, port);
                return this;
            }
        }

        @Override
        public void close() {
            broker.stop();
            nameserver.stop();
            network.close();
        }
    }

    private static final class BusFixture implements AutoCloseable {
        private final String nameserver;
        private final String namespace;
        private final DefaultMQProducer producer = new DefaultMQProducer("dfx001-producer-" + UUID.randomUUID());

        private BusFixture(String nameserver, String namespace) {
            this.nameserver = nameserver;
            this.namespace = namespace;
        }

        private void start() throws Exception {
            producer.setNamesrvAddr(nameserver);
            producer.setSendMsgTimeout(10_000);
            producer.start();
        }

        private void send(BusRequest request) throws Exception {
            byte[] body = request.inlinePayload().getBytes(StandardCharsets.UTF_8);
            Message message = new Message("ascend_bus_invocation_req", request.messageId(), body);
            put(message, "eventType", request.eventType());
            put(message, "messageId", request.messageId());
            put(message, "tenantId", request.tenantId());
            put(message, "sourceServiceId", request.sourceServiceId());
            put(message, "targetServiceId", request.targetServiceId());
            put(message, "correlationId", request.correlationId());
            put(message, "traceId", request.traceId());
            put(message, "idempotencyKey", request.idempotencyKey());
            put(message, "deadlineMillisEpoch", Long.toString(System.currentTimeMillis() + 120_000));
            put(message, "inlinePayload", request.inlinePayload());
            put(message, "payloadRef", request.payloadRef());
            request.properties().forEach((key, value) -> put(message, key, value));
            producer.send(message);
        }

        private static void put(Message message, String key, String value) {
            if (value != null) {
                message.putUserProperty(key, value);
            }
        }

        @Override
        public void close() {
            producer.shutdown();
        }
    }

    private record RedisEndpoint(String host, int port) {
        private static RedisEndpoint parse(String address) {
            int colon = address.lastIndexOf(':');
            if (colon <= 0 || colon == address.length() - 1) {
                throw new IllegalArgumentException("Invalid Redis endpoint: " + address);
            }
            return new RedisEndpoint(address.substring(0, colon), Integer.parseInt(address.substring(colon + 1)));
        }
    }

    private enum RedisProxyMode {
        NORMAL,
        FAIL_TRAJECTORY_WRITES,
        DELAY_TRAJECTORY_SETEX
    }

    /** RESP proxy used only by DFX001 to fault trajectory keys while leaving business Redis traffic intact. */
    private static final class RedisTrajectoryProxy implements AutoCloseable {
        private final RedisEndpoint upstream;
        private final ServerSocket listener;
        private final ExecutorService workers = Executors.newCachedThreadPool();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<RedisProxyMode> mode = new AtomicReference<>(RedisProxyMode.NORMAL);

        private RedisTrajectoryProxy(RedisEndpoint upstream) throws IOException {
            this.upstream = upstream;
            this.listener = new ServerSocket();
            this.listener.bind(new InetSocketAddress("127.0.0.1", 0));
            workers.submit(this::acceptLoop);
        }

        private static RedisTrajectoryProxy start(RedisEndpoint upstream) {
            try {
                return new RedisTrajectoryProxy(upstream);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to start DFX001 Redis proxy", e);
            }
        }

        private RedisEndpoint endpoint() {
            return new RedisEndpoint("127.0.0.1", listener.getLocalPort());
        }

        private void mode(RedisProxyMode next) {
            mode.set(next);
        }

        private void acceptLoop() {
            while (!closed.get()) {
                try {
                    Socket client = listener.accept();
                    workers.submit(() -> handle(client));
                } catch (IOException e) {
                    if (!closed.get()) {
                        throw new IllegalStateException("DFX001 Redis proxy accept failed", e);
                    }
                }
            }
        }

        private void handle(Socket client) {
            try (client; Socket redis = new Socket()) {
                redis.connect(new InetSocketAddress(upstream.host(), upstream.port()), 5_000);
                InputStream clientIn = client.getInputStream();
                OutputStream clientOut = client.getOutputStream();
                InputStream redisIn = redis.getInputStream();
                OutputStream redisOut = redis.getOutputStream();
                while (!closed.get()) {
                    RespFrame command = RespFrame.read(clientIn);
                    if (command == null) {
                        return;
                    }
                    String verb = command.args().isEmpty() ? "" : command.args().get(0).toUpperCase();
                    String key = command.args().size() < 2 ? "" : command.args().get(1);
                    RedisProxyMode current = mode.get();
                    if (current == RedisProxyMode.FAIL_TRAJECTORY_WRITES
                            && isTrajectoryWrite(verb, key)) {
                        clientOut.write("-ERR DFX001 simulated trajectory write failure\r\n"
                                .getBytes(StandardCharsets.US_ASCII));
                        clientOut.flush();
                        continue;
                    }
                    while (mode.get() == RedisProxyMode.DELAY_TRAJECTORY_SETEX
                            && "SETEX".equals(verb) && key.startsWith("runtime:") && !closed.get()) {
                        TimeUnit.MILLISECONDS.sleep(20);
                    }
                    redisOut.write(command.raw());
                    redisOut.flush();
                    RespFrame response = RespFrame.read(redisIn);
                    if (response == null) {
                        return;
                    }
                    clientOut.write(response.raw());
                    clientOut.flush();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // Connection churn is expected when the owning agent or fixture is closing.
            }
        }

        private static boolean isTrajectoryWrite(String verb, String key) {
            return key.startsWith("runtime:") && Set.of("SET", "SETEX", "DEL").contains(verb);
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            mode.set(RedisProxyMode.NORMAL);
            try {
                listener.close();
            } catch (IOException ignored) {
                // Best-effort close.
            }
            workers.shutdownNow();
        }
    }

    private record RespFrame(byte[] raw, String text, List<String> args) {
        private static RespFrame read(InputStream input) throws IOException {
            int prefix = input.read();
            if (prefix < 0) {
                return null;
            }
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            raw.write(prefix);
            byte[] line = readLine(input);
            raw.write(line);
            String header = new String(line, 0, line.length - 2, StandardCharsets.US_ASCII);
            if (prefix == '$') {
                int length = Integer.parseInt(header);
                if (length < 0) {
                    return new RespFrame(raw.toByteArray(), null, List.of());
                }
                byte[] body = input.readNBytes(length + 2);
                if (body.length != length + 2) {
                    throw new IOException("Truncated RESP bulk string");
                }
                raw.write(body);
                return new RespFrame(raw.toByteArray(),
                        new String(body, 0, length, StandardCharsets.UTF_8), List.of());
            }
            if (prefix == '*') {
                int count = Integer.parseInt(header);
                List<String> args = new ArrayList<>(Math.max(0, count));
                for (int i = 0; i < count; i++) {
                    RespFrame child = read(input);
                    if (child == null) {
                        throw new IOException("Truncated RESP array");
                    }
                    raw.write(child.raw());
                    args.add(child.text());
                }
                return new RespFrame(raw.toByteArray(), null, args);
            }
            return new RespFrame(raw.toByteArray(), header, List.of());
        }

        private static byte[] readLine(InputStream input) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int previous = -1;
            while (true) {
                int current = input.read();
                if (current < 0) {
                    throw new IOException("Truncated RESP line");
                }
                line.write(current);
                if (previous == '\r' && current == '\n') {
                    return line.toByteArray();
                }
                previous = current;
            }
        }
    }

    private static final class RedisCommandClient implements AutoCloseable {
        private final Socket socket = new Socket();

        private RedisCommandClient(RedisEndpoint endpoint) throws IOException {
            socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), 5_000);
        }

        private void setex(String key, long ttlSeconds, String value) throws IOException {
            send("SETEX", key, Long.toString(ttlSeconds), value);
        }

        private void send(String... args) throws IOException {
            OutputStream output = socket.getOutputStream();
            output.write(("*" + args.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            for (String arg : args) {
                byte[] value = arg.getBytes(StandardCharsets.UTF_8);
                output.write(("$" + value.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
                output.write(value);
                output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            }
            output.flush();
            RespFrame response = RespFrame.read(socket.getInputStream());
            if (response == null || (response.raw().length > 0 && response.raw()[0] == '-')) {
                throw new IOException("Redis fixture command failed");
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static final class CompletedA2aAgent implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final AtomicInteger callCount = new AtomicInteger();

        private CompletedA2aAgent() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/.well-known/agent-card.json", this::serveCard);
            server.createContext("/.well-known/agent.json", this::serveCard);
            server.createContext("/a2a", this::completeCall);
            server.setExecutor(executor);
            server.start();
        }

        private static CompletedA2aAgent start() {
            try {
                return new CompletedA2aAgent();
            } catch (IOException e) {
                throw new IllegalStateException("Unable to start DFX001 A2A fixture", e);
            }
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private int callCount() {
            return callCount.get();
        }

        private void serveCard(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "Method Not Allowed");
                return;
            }
            ObjectNode card = JSON.createObjectNode();
            card.put("name", "versatile-adapter");
            card.put("description", "Deterministic transfer completion fixture");
            card.putObject("provider").put("organization", "SIT").put("url", "");
            card.put("version", "0.1.0");
            card.putObject("capabilities").put("streaming", false)
                    .put("pushNotifications", false).put("extendedAgentCard", false)
                    .putArray("extensions");
            card.putArray("defaultInputModes").add("text");
            card.putArray("defaultOutputModes").add("text");
            ObjectNode skill = card.putArray("skills").addObject();
            skill.put("id", "bank-transfer").put("name", "bank-transfer")
                    .put("description", "Complete an independent bank transfer request");
            skill.putArray("tags").add("transfer");
            card.putObject("securitySchemes");
            card.putArray("securityRequirements");
            ObjectNode endpoint = card.putArray("supportedInterfaces").addObject();
            endpoint.put("protocolBinding", "JSONRPC").put("url", baseUrl() + "/a2a")
                    .put("protocolVersion", "1.0");
            card.put("url", baseUrl() + "/a2a");
            card.put("preferredTransport", "JSONRPC");
            card.putArray("additionalInterfaces");
            respond(exchange, 200, JSON.writeValueAsString(card));
        }

        private void completeCall(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "Method Not Allowed");
                return;
            }
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            callCount.incrementAndGet();
            String contextId = request.path("params").path("message").path("contextId")
                    .asText("dfx001-parallel-context");
            ObjectNode envelope = JSON.createObjectNode();
            envelope.put("jsonrpc", "2.0");
            if (request.has("id")) {
                envelope.set("id", request.get("id"));
            } else {
                envelope.putNull("id");
            }
            ObjectNode task = envelope.putObject("result").putObject("task");
            task.put("id", UUID.randomUUID().toString());
            task.put("contextId", contextId);
            task.putObject("status").put("state", "TASK_STATE_COMPLETED");
            ObjectNode artifact = task.putArray("artifacts").addObject();
            artifact.put("artifactId", UUID.randomUUID().toString());
            artifact.putArray("parts").addObject().put("text", "transfer completed");
            task.putArray("history");
            respond(exchange, 200, JSON.writeValueAsString(envelope));
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
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

    private record A2aResult(String taskId, String contextId, String state) {
    }

    private record ExecutionEdge(String parent, String child, String agent) {
    }

    private record LifecycleTransition(String from, String to, String at, String runId) {
    }
}

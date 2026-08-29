package com.huawei.ascend.sit.cases.integration.react_travel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.lifecycle.BackingServices;
import com.huawei.ascend.sit.lifecycle.TestContainerFactory;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-017 black-box acceptance tests built from the current six-event delivery slice.
 *
 * <p>The test owns the three-agent travel topology and talks to Agent Bus only through the
 * RocketMQ request/response topics. The event fixture deliberately uses the public broker wire
 * properties instead of importing runtime bridge, TaskStore, relay, or inbox implementation
 * classes.</p>
 */
@Tag("feat-017")
@Tag("integration")
@Tag("openjiuwen")
@Feature("FEAT-017: 运行时订阅消费总线事件消息")
@Execution(ExecutionMode.SAME_THREAD)
class RuntimeBusConsumerBlackboxTest extends BaseManagedStackTest {

    private static final String REGISTRY = "registry-center";
    private static final String RELAY = "event-bus-relay";
    private static final String MAINPLAN = "mainplan";
    private static final String TRIP = "trip";
    private static final String HOTEL = "hotel";
    private static final String ROCKETMQ_NAMESERVER = "127.0.0.1:9876";
    private static final Map<String, String> BUS_SERVICE_IDS = Map.of(
            MAINPLAN, "travel-mainplan", TRIP, "travel-trip", HOTEL, "travel-hotel");
    private static Process relayProcess;
    private BackingServices backingServices;
    private GenericContainer<?> relayPostgres;
    private RocketMqFixture rocketMq;
    private static final String TENANT = "tenant-a";
    private static final String NAMESPACE = "ascend-prod";
    private static final Duration EVENT_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration QUIET_WINDOW = Duration.ofSeconds(2);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private BusFixture bus;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // The relay uses PostgreSQL for its outbox/migrations. Start the managed service first so
        // the relay receives the dynamically mapped Testcontainers address instead of assuming
        // host port 5432 (which is not guaranteed and races SutStack.start()).
        backingServices = new BackingServices(config, Set.of("postgres"), new TestContainerFactory(null));
        relayPostgres = new GenericContainer<>(DockerImageName.parse("postgres:16.2"))
                .withEnv("POSTGRES_DB", "agentbus")
                .withEnv("POSTGRES_USER", "agentbus")
                .withEnv("POSTGRES_PASSWORD", "agentbus")
                .withExposedPorts(5432)
                .waitingFor(Wait.forListeningPort());
        relayPostgres.start();
        rocketMq = RocketMqFixture.start();
        startRelayProcess(relayPostgres.getHost() + ":" + relayPostgres.getMappedPort(5432), rocketMq.nameserver());
        String rdc = env("AGENT_BUS_RDC_URL", "http://127.0.0.1:18092");
        return SutStack.builder(config).backingServices(backingServices)
                .streaming(true)
                .agent(REGISTRY, a -> a.readyMode(com.huawei.ascend.sit.lifecycle.AgentConfig.ReadyMode.TCP))
                .agent(HOTEL, a -> busProperties(a, BUS_SERVICE_IDS.get(HOTEL), rdc))
                .agent(TRIP, a -> {
                    a.downstream(HOTEL);
                    busProperties(a, BUS_SERVICE_IDS.get(TRIP), rdc);
                })
                .agent(MAINPLAN, a -> {
                    a.downstream(TRIP);
                    busProperties(a, BUS_SERVICE_IDS.get(MAINPLAN), rdc);
                });
    }

    private static void busProperties(SutStack.AgentBuilder agent, String serviceId, String rdc) {
        agent.property("openjiuwen.service.bus.consumer.enabled", "true")
                .property("openjiuwen.service.bus.consumer.registry-base-url", rdc)
                .property("agent-bus.role.runtime.enabled", "true")
                .property("agent-bus.role.caller.enabled", "true")
                .property("agent-bus.nameserver", env("AGENT_BUS_NAMESERVER", ROCKETMQ_NAMESERVER))
                .property("agent-bus.namespace", env("AGENT_BUS_NAMESPACE", NAMESPACE))
                .property("agent-bus.tenant", env("AGENT_BUS_TENANT", TENANT))
                .property("agent-bus.event-bus-service-id", env("AGENT_BUS_EVENT_BUS_SERVICE_ID", "eventbus-01"))
                .property("agent-bus.producer-group", "runtime-" + serviceId)
                .property("AGENT_BUS_ENABLED", "true");
        for (String key : List.of("LLM_API_KEY", "LLM_API_BASE", "LLM_MODEL", "LLM_PROVIDER", "LLM_SSL_VERIFY")) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                agent.env(key, value);
            }
        }
        String apiKey = System.getenv("LLM_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            String agentName = serviceId.substring("travel-".length()).toUpperCase(java.util.Locale.ROOT);
            agent.env("OPENJIUWEN_TRAVEL_" + agentName + "_LLM_API_KEY", apiKey);
            // The managed-stack launcher does not map arbitrary environment variables to the
            // demo's Spring key. Inject the public demo property explicitly while keeping the
            // secret sourced only from the caller's environment.
            String springAgentName = serviceId.substring("travel-".length());
            agent.property("openjiuwen.travel." + springAgentName + ".llm.api-key", apiKey);
        }
    }

    @BeforeAll
    void startEventFixture() throws Exception {
        bus = new BusFixture(
                env("AGENT_BUS_NAMESERVER", ROCKETMQ_NAMESERVER),
                env("AGENT_BUS_NAMESPACE", NAMESPACE));
        bus.start();
    }

    @AfterAll
    void stopEventFixture() {
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
            backingServices.close();
            backingServices = null;
        }
    }

    private void startRelayProcess(String postgresAddress, String nameserver) {
        if (relayProcess != null && relayProcess.isAlive()) {
            return;
        }
        String repo = System.getProperty("maven.repo.local", "/mnt/d/repository");
        String jar = repo + "/com/openjiuwen/event-bus-relay/0.1.1/event-bus-relay-0.1.1.jar";
        try {
            ProcessBuilder command = new ProcessBuilder("java", "-jar", jar,
                    "--spring.profiles.active=eventbus",
                    "--spring.datasource.url=jdbc:postgresql://" + postgresAddress + "/agentbus",
                    "--spring.datasource.username=agentbus",
                    "--spring.datasource.password=agentbus",
                    "--agent-bus.nameserver=" + nameserver,
                    "--agent-bus.namespace=" + NAMESPACE,
                    "--agent-bus.tenant=" + TENANT,
                    "--agent-bus.event-bus-service-id=eventbus-01",
                    "--agent-bus.role.relay.enabled=true");
            command.redirectErrorStream(true);
            command.redirectOutput(ProcessBuilder.Redirect.appendTo(
                    java.nio.file.Path.of("target", "sit-logs", "event-bus-relay-standalone.log").toFile()));
            relayProcess = command.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline && relayProcess.isAlive()) {
                TimeUnit.MILLISECONDS.sleep(250);
            }
            if (!relayProcess.isAlive()) {
                throw new IllegalStateException("event-bus-relay exited; see target/sit-logs/event-bus-relay-standalone.log");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to start event-bus-relay standalone fixture", e);
        }
    }

    @Test
    @Tag("blackbox")
    @Tag("smoke")
    @Tag("story-feat-017-client-create-accepted")
    @Story("FEAT-017.client.create.accepted: 客户端创建调用被接受")
    @DisplayName("Feat-017 client invocation request publishes accepted projection")
    void feat017ClientCreateAccepted() throws Exception {
        String correlation = id("client-accepted");
        BusEvent event = sendAndAwait(request("CLIENT_INVOCATION_REQUESTED", MAINPLAN, correlation,
                createPayload("SendMessage", "plan a short Beijing business trip", false)));
        assertAccepted(event, "INVOCATION_ACCEPTED", correlation);
    }

    @Test
    @Tag("blackbox")
    @Tag("smoke")
    @Tag("story-feat-017-a2a-create-accepted")
    @Story("FEAT-017.a2a.create.accepted: A2A 创建调用被接受")
    @DisplayName("Feat-017 A2A call request publishes accepted projection")
    void feat017A2aCreateAccepted() throws Exception {
        String correlation = id("a2a-accepted");
        BusEvent event = sendAndAwait(request("A2A_CALL_REQUESTED", TRIP, correlation,
                createPayload("SendMessage", "plan a hotel stay in Beijing", false)));
        assertAccepted(event, "A2A_CALL_ACCEPTED", correlation);
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-client-create-response-terminal")
    @Story("FEAT-017.client.create.response-terminal: 客户端阻塞调用完成")
    @DisplayName("Feat-017 client call exposes response and terminal projections")
    void feat017ClientCreateResponseTerminal() throws Exception {
        String correlation = id("client-terminal");
        sendAndAwait(request("CLIENT_INVOCATION_REQUESTED", MAINPLAN, correlation,
                createPayload("SendMessage",
                        "从上海到北京出差，出发日期是2026年08月30日，返回日期是2026年09月01日，差标每晚800元",
                        false)),
                "INVOCATION_ACCEPTED");
        BusEvent response = bus.await(correlation, "INVOCATION_RESPONSE", EVENT_TIMEOUT);
        BusEvent terminal = bus.await(correlation, "INVOCATION_TERMINAL", EVENT_TIMEOUT);
        assertThat(response.taskId()).isNotBlank();
        assertThat(terminal.taskId()).isEqualTo(response.taskId());
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-a2a-create-response-terminal")
    @Story("FEAT-017.a2a.create.response-terminal: A2A 阻塞调用完成")
    @DisplayName("Feat-017 A2A call exposes response and terminal projections")
    void feat017A2aCreateResponseTerminal() throws Exception {
        String correlation = id("a2a-terminal");
        sendAndAwait(request("A2A_CALL_REQUESTED", TRIP, correlation,
                createPayload("SendMessage", "Beijing hotel for two nights, 4 star", false)),
                "A2A_CALL_ACCEPTED");
        BusEvent response = bus.await(correlation, "A2A_CALL_RESPONSE", EVENT_TIMEOUT);
        BusEvent terminal = bus.await(correlation, "A2A_CALL_TERMINAL", EVENT_TIMEOUT);
        assertThat(response.taskId()).isNotBlank();
        assertThat(terminal.taskId()).isEqualTo(response.taskId());
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-client-input-required")
    @Story("FEAT-017.client.input-required: 客户端调用等待输入")
    @DisplayName("Feat-017 client call publishes input-required projection")
    void feat017ClientInputRequired() throws Exception {
        String correlation = id("client-input");
        BusEvent event = sendAndAwait(request("CLIENT_INVOCATION_REQUESTED", MAINPLAN, correlation,
                createPayload("SendMessage", "I need a business trip but destination, dates and budget are missing; ask me first", false)),
                "INVOCATION_INPUT_REQUIRED");
        assertThat(event.taskId()).isNotBlank();
        assertThat(event.text()).containsIgnoringCase("input");
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-a2a-input-required")
    @Story("FEAT-017.a2a.input-required: A2A 调用等待输入")
    @DisplayName("Feat-017 A2A call publishes input-required projection")
    void feat017A2aInputRequired() throws Exception {
        String correlation = id("a2a-input");
        BusEvent event = sendAndAwait(request("A2A_CALL_REQUESTED", MAINPLAN, correlation,
                createPayload("SendMessage", "Ask for the missing destination, dates and budget", false)),
                "A2A_CALL_INPUT_REQUIRED");
        assertThat(event.taskId()).isNotBlank();
    }

    @Test
    @Tag("blackbox")
    @Tag("smoke")
    @Tag("story-feat-017-client-query-existing")
    @Story("FEAT-017.client.query.existing: 客户端查询已有 Task")
    @DisplayName("Feat-017 client query returns the existing task snapshot")
    void feat017ClientQueryExisting() throws Exception {
        String correlation = id("client-query");
        BusEvent accepted = sendAndAwait(request("CLIENT_INVOCATION_REQUESTED", MAINPLAN, correlation,
                createPayload("SendMessage", "short Beijing trip", false)), "INVOCATION_ACCEPTED");
        BusEvent query = sendAndAwait(request("CLIENT_INVOCATION_QUERY_REQUESTED", MAINPLAN, id("client-query-request"),
                queryPayload(accepted.taskId())));
        assertThat(query.eventType()).isEqualTo("INVOCATION_RESPONSE");
        assertThat(query.taskId()).isEqualTo(accepted.taskId());
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-a2a-query-existing")
    @Story("FEAT-017.a2a.query.existing: A2A 查询已有 Task")
    @DisplayName("Feat-017 A2A query returns the existing task snapshot")
    void feat017A2aQueryExisting() throws Exception {
        String correlation = id("a2a-query");
        BusEvent accepted = sendAndAwait(request("A2A_CALL_REQUESTED", TRIP, correlation,
                createPayload("SendMessage", "short hotel request", false)), "A2A_CALL_ACCEPTED");
        BusEvent query = sendAndAwait(request("A2A_CALL_QUERY_REQUESTED", TRIP, id("a2a-query-request"),
                queryPayload(accepted.taskId())));
        assertThat(query.eventType()).isEqualTo("A2A_CALL_RESPONSE");
        assertThat(query.taskId()).isEqualTo(accepted.taskId());
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-client-query-not-found")
    @Story("FEAT-017.client.query.not-found: 客户端查询不可见 Task")
    @DisplayName("Feat-017 client query does not create an invisible task")
    void feat017ClientQueryNotFound() throws Exception {
        String correlation = id("client-query-miss");
        BusEvent event = sendAndAwait(request("CLIENT_INVOCATION_QUERY_REQUESTED", MAINPLAN, correlation,
                queryPayload(id("missing-task"))));
        assertFailure(event, "INVOCATION_");
        assertThat(event.taskId()).isBlank();
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-a2a-query-not-found")
    @Story("FEAT-017.a2a.query.not-found: A2A 查询不可见 Task")
    @DisplayName("Feat-017 A2A query does not enumerate another task")
    void feat017A2aQueryNotFound() throws Exception {
        String correlation = id("a2a-query-miss");
        BusEvent event = sendAndAwait(request("A2A_CALL_QUERY_REQUESTED", TRIP, correlation,
                queryPayload(id("missing-task"))));
        assertFailure(event, "A2A_CALL_");
        assertThat(event.taskId()).isBlank();
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-client-subscribe-existing")
    @Story("FEAT-017.client.subscribe.existing: 客户端订阅已有 Task")
    @DisplayName("Feat-017 client stream subscription publishes stream-ready")
    void feat017ClientSubscribeExisting() throws Exception {
        String correlation = id("client-subscribe");
        BusEvent accepted = sendAndAwait(request("CLIENT_INVOCATION_REQUESTED", MAINPLAN, correlation,
                createPayload("SendStreamingMessage", "stream a short Beijing trip", true)), "INVOCATION_ACCEPTED");
        BusEvent ready = bus.await(correlation, "INVOCATION_STREAM_READY", EVENT_TIMEOUT);
        assertThat(ready.taskId()).isEqualTo(accepted.taskId());
        BusEvent subscribed = sendAndAwait(request("CLIENT_STREAM_SUBSCRIBE_REQUESTED", MAINPLAN,
                id("client-subscribe-request"), subscribePayload(accepted.taskId())));
        assertThat(subscribed.eventType()).isEqualTo("INVOCATION_STREAM_READY");
        assertThat(subscribed.streamRef()).isNotBlank();
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-a2a-subscribe-existing")
    @Story("FEAT-017.a2a.subscribe.existing: A2A 订阅已有 Task")
    @DisplayName("Feat-017 A2A stream subscription publishes stream-ready")
    void feat017A2aSubscribeExisting() throws Exception {
        String correlation = id("a2a-subscribe");
        BusEvent accepted = sendAndAwait(request("A2A_CALL_REQUESTED", TRIP, correlation,
                createPayload("SendStreamingMessage", "stream a short hotel search", true)), "A2A_CALL_ACCEPTED");
        bus.await(correlation, "A2A_STREAM_READY", EVENT_TIMEOUT);
        BusEvent subscribed = sendAndAwait(request("A2A_STREAM_SUBSCRIBE_REQUESTED", TRIP,
                id("a2a-subscribe-request"), subscribePayload(accepted.taskId())));
        assertThat(subscribed.eventType()).isEqualTo("A2A_STREAM_READY");
        assertThat(subscribed.streamRef()).isNotBlank();
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-client-subscribe-not-found")
    @Story("FEAT-017.client.subscribe.not-found: 客户端订阅不可见 Task")
    @DisplayName("Feat-017 client subscription rejects a missing task")
    void feat017ClientSubscribeNotFound() throws Exception {
        BusEvent event = sendAndAwait(request("CLIENT_STREAM_SUBSCRIBE_REQUESTED", MAINPLAN, id("client-stream-miss"),
                subscribePayload(id("missing-task"))));
        assertFailure(event, "INVOCATION_");
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-a2a-subscribe-not-available")
    @Story("FEAT-017.a2a.subscribe.not-available: A2A 订阅不可用流")
    @DisplayName("Feat-017 A2A subscription rejects a terminal task")
    void feat017A2aSubscribeNotAvailable() throws Exception {
        String correlation = id("a2a-stream-unavailable");
        BusEvent accepted = sendAndAwait(request("A2A_CALL_REQUESTED", TRIP, correlation,
                createPayload("SendMessage", "complete a short hotel lookup", false)), "A2A_CALL_ACCEPTED");
        bus.await(correlation, "A2A_CALL_TERMINAL", EVENT_TIMEOUT);
        BusEvent event = sendAndAwait(request("A2A_STREAM_SUBSCRIBE_REQUESTED", TRIP, id("a2a-stream-miss"),
                subscribePayload(accepted.taskId())));
        assertFailure(event, "A2A_CALL_");
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-envelope-event-type")
    @Story("FEAT-017.envelope.event-type: 未知事件类型被拒绝")
    @DisplayName("Feat-017 rejects an unknown event type")
    void feat017EnvelopeEventType() throws Exception {
        assertNoProjection(request("UNKNOWN_EVENT", MAINPLAN, id("event-type"),
                createPayload("SendMessage", "unknown event", false)), EVENT_TIMEOUT);
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-envelope-required-fields")
    @Story("FEAT-017.envelope.required-fields: 信封必填字段缺失")
    @DisplayName("Feat-017 rejects envelopes with missing required fields")
    void feat017EnvelopeRequiredFields() throws Exception {
        for (String field : List.of("messageId", "tenantId", "sourceServiceId", "targetServiceId",
                "correlationId", "eventType", "deadlineMillisEpoch")) {
            BusRequest request = request("CLIENT_INVOCATION_REQUESTED", MAINPLAN, id("required"),
                    createPayload("SendMessage", "required field probe", false)).without(field);
            if (List.of("messageId", "tenantId", "sourceServiceId", "targetServiceId", "correlationId", "eventType")
                    .contains(field)) {
                assertNoProjection(request, EVENT_TIMEOUT);
            } else {
                assertThat(sendAndAwait(request).eventType()).endsWith("_REJECTED");
            }
        }
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-envelope-target-mismatch")
    @Story("FEAT-017.envelope.target-mismatch: 目标服务不匹配")
    @DisplayName("Feat-017 filters a target mismatch without fallback")
    void feat017EnvelopeTargetMismatch() throws Exception {
        // The broker/runtime subscription is filtered by tenant + targetServiceId. A message
        // addressed to another service must therefore never reach this Runtime validator and
        // must not produce a response projection or fall back to travel-mainplan.
        BusRequest request = request("CLIENT_INVOCATION_REQUESTED", "not-this-runtime", id("target"),
                createPayload("SendMessage", "target mismatch", false));
        assertNoProjection(request, EVENT_TIMEOUT);
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-envelope-tenant-mismatch")
    @Story("FEAT-017.envelope.tenant-mismatch: 租户范围不匹配")
    @DisplayName("Feat-017 isolates a task from another tenant")
    void feat017EnvelopeTenantMismatch() throws Exception {
        BusRequest request = request("CLIENT_INVOCATION_QUERY_REQUESTED", MAINPLAN, id("tenant"),
                queryPayload(id("foreign-task"))).with("tenantId", "tenant-other");
        assertNoProjection(request, EVENT_TIMEOUT);
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-envelope-deadline")
    @Story("FEAT-017.envelope.deadline: deadline 非法")
    @DisplayName("Feat-017 rejects an expired deadline")
    void feat017EnvelopeDeadline() throws Exception {
        BusRequest request = request("CLIENT_INVOCATION_REQUESTED", MAINPLAN, id("deadline"),
                createPayload("SendMessage", "expired deadline", false)).with("deadlineMillisEpoch", "1");
        BusEvent event = sendAndAwait(request);
        assertThat(event.text()).containsIgnoringCase("DEADLINE_EXCEEDED");
        assertThat(event.taskId()).isBlank();
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-envelope-payload-reference")
    @Story("FEAT-017.envelope.payload-reference: payload 引用描述非法")
    @DisplayName("Feat-017 rejects invalid inline and reference combinations")
    void feat017EnvelopePayloadReference() throws Exception {
        BusRequest both = request("CLIENT_INVOCATION_REQUESTED", MAINPLAN, id("payload-both"),
                createPayload("SendMessage", "payload both", false)).with("payloadRef", "ref://both");
        assertThat(sendAndAwait(both).text()).containsIgnoringCase("PAYLOAD_REFERENCE_INVALID");
        BusRequest neither = request("CLIENT_INVOCATION_REQUESTED", MAINPLAN, id("payload-neither"), "")
                .without("inlinePayload").without("payloadRef");
        assertThat(sendAndAwait(neither).text()).containsIgnoringCase("PAYLOAD_REFERENCE_INVALID");
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-payload-ref-only-current")
    @Story("FEAT-017.payload-ref-only.current: 当前切片处理 payloadRef-only")
    @DisplayName("Feat-017 returns PAYLOAD_EMPTY for payloadRef-only messages")
    void feat017PayloadRefOnlyCurrent() throws Exception {
        BusRequest request = request("CLIENT_INVOCATION_REQUESTED", MAINPLAN, id("payload-ref-only"), "")
                .without("inlinePayload").with("payloadRef", "ref://unsupported");
        BusEvent event = sendAndAwait(request);
        assertThat(event.eventType()).isEqualTo("INVOCATION_FAILED");
        assertThat(event.text()).containsIgnoringCase("PAYLOAD_EMPTY");
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-payload-inline-limit")
    @Story("FEAT-017.payload-inline-limit: inline payload 超限")
    @DisplayName("Feat-017 rejects an oversized inline payload")
    void feat017PayloadInlineLimit() throws Exception {
        String payload = "x".repeat(65_537);
        BusEvent event = sendAndAwait(request("CLIENT_INVOCATION_REQUESTED", MAINPLAN, id("payload-large"), payload));
        assertThat(event.text()).containsIgnoringCase("PAYLOAD_TOO_LARGE");
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-payload-invalid-json")
    @Story("FEAT-017.payload.invalid-json: 非法 JSON 载荷")
    @DisplayName("Feat-017 rejects an inline payload that is not valid A2A JSON")
    void feat017PayloadInvalidJson() throws Exception {
        BusEvent event = sendAndAwait(request("CLIENT_INVOCATION_REQUESTED", MAINPLAN, id("invalid-json"),
                "not-json"));
        assertThat(event.eventType()).isEqualTo("INVOCATION_FAILED");
        assertThat(event.taskId()).isBlank();
        assertThat(event.text()).containsIgnoringCase("PAYLOAD_INVALID");
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-payload-method-compatibility")
    @Story("FEAT-017.payload-method-compatibility: method 与事件族不兼容")
    @DisplayName("Feat-017 rejects an incompatible A2A method")
    void feat017PayloadMethodCompatibility() throws Exception {
        BusEvent event = sendAndAwait(request("CLIENT_INVOCATION_QUERY_REQUESTED", MAINPLAN, id("method"),
                createPayload("SendMessage", "wrong method", false)));
        assertThat(event.eventType()).isEqualTo("INVOCATION_FAILED");
        assertThat(event.taskId()).isBlank();
        assertThat(event.text()).containsAnyOf("PAYLOAD_INVALID", "A2A_ERROR_-32600");
        assertThat(event.text()).containsIgnoringCase("method does not match bus event");
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-delivery-message-redelivery")
    @Story("FEAT-017.delivery.message-redelivery: 相同 messageId 重投")
    @DisplayName("Feat-017 redelivery creates one task and one side effect")
    void feat017DeliveryMessageRedelivery() throws Exception {
        BusRequest request = request("CLIENT_INVOCATION_REQUESTED", MAINPLAN, id("redelivery"),
                createPayload("SendMessage", "redelivery probe", false));
        bus.send(request);
        bus.send(request);
        BusEvent event = bus.await(request.correlationId(), "INVOCATION_ACCEPTED", EVENT_TIMEOUT);
        assertThat(bus.count(request.correlationId(), "INVOCATION_ACCEPTED")).isLessThanOrEqualTo(1);
        assertThat(event.taskId()).isNotBlank();
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-admission-same-key-same-request")
    @Story("FEAT-017.admission.same-key-same-request: 同幂等请求重试")
    @DisplayName("Feat-017 reuses a task for the same idempotency key")
    void feat017AdmissionSameKeySameRequest() throws Exception {
        String key = id("idem");
        BusRequest first = request("CLIENT_INVOCATION_REQUESTED", MAINPLAN, id("idem-first"),
                createPayload("SendMessage", "same idempotent request", false)).with("idempotencyKey", key);
        BusRequest retry = first.with("messageId", id("idem-retry"));
        BusEvent accepted = sendAndAwait(first, "INVOCATION_ACCEPTED");
        BusEvent reused = sendAndAwait(retry, "INVOCATION_ACCEPTED");
        assertThat(reused.taskId()).isEqualTo(accepted.taskId());
        assertThat(reused.correlationId()).isEqualTo(first.correlationId());
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-admission-same-key-conflict")
    @Story("FEAT-017.admission.same-key-conflict: 幂等键冲突")
    @DisplayName("Feat-017 rejects a conflicting idempotency key")
    void feat017AdmissionSameKeyConflict() throws Exception {
        String key = id("idem-conflict");
        sendAndAwait(request("CLIENT_INVOCATION_REQUESTED", MAINPLAN, id("idem-a"),
                createPayload("SendMessage", "first request", false)).with("idempotencyKey", key),
                "INVOCATION_ACCEPTED");
        BusEvent event = sendAndAwait(request("CLIENT_INVOCATION_REQUESTED", MAINPLAN, id("idem-b"),
                createPayload("SendMessage", "different request", false)).with("idempotencyKey", key));
        assertThat(event.eventType()).isEqualTo("INVOCATION_REJECTED");
        assertThat(event.text()).containsIgnoringCase("IDEMPOTENCY_KEY_CONFLICT");
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-projection-republish")
    @Story("FEAT-017.projection.republish: 投影失败后补发")
    @DisplayName("Feat-017 retains task state while a projection is republished")
    void feat017ProjectionRepublish() throws Exception {
        String correlation = id("republish");
        BusRequest request = request("CLIENT_INVOCATION_REQUESTED", MAINPLAN, correlation,
                createPayload("SendMessage", "projection republish probe", false));
        sendAndAwait(request, "INVOCATION_ACCEPTED");
        bus.send(request.with("messageId", id("republish-redelivery")));
        BusEvent terminal = bus.await(correlation, "INVOCATION_TERMINAL", EVENT_TIMEOUT);
        assertThat(terminal.taskId()).isNotBlank();
        assertThat(bus.count(correlation, "INVOCATION_TERMINAL")).isLessThanOrEqualTo(1);
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-ack-long-task")
    @Story("FEAT-017.ack.long-task: 长任务 ACK 不等待终态")
    @DisplayName("Feat-017 accepts a long task before its terminal projection")
    void feat017AckLongTask() throws Exception {
        String correlation = id("long-task");
        BusEvent accepted = sendAndAwait(request("CLIENT_INVOCATION_REQUESTED", MAINPLAN, correlation,
                createPayload("SendMessage", "FEAT017_LONG_TASK keep this task working for a while", false)),
                "INVOCATION_ACCEPTED");
        assertThat(accepted.taskId()).isNotBlank();
        assertNoEvent(correlation, "INVOCATION_TERMINAL", Duration.ofSeconds(1));
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-stream-ready-separation")
    @Story("FEAT-017.stream.ready-separation: accepted 与 stream-ready 分离")
    @DisplayName("Feat-017 keeps accepted and stream-ready as separate projections")
    void feat017StreamReadySeparation() throws Exception {
        String correlation = id("stream-separation");
        sendAndAwait(request("CLIENT_INVOCATION_REQUESTED", MAINPLAN, correlation,
                createPayload("SendStreamingMessage", "stream a short trip", true)), "INVOCATION_ACCEPTED");
        BusEvent ready = bus.await(correlation, "INVOCATION_STREAM_READY", EVENT_TIMEOUT);
        assertThat(ready.streamRef()).isNotBlank();
        assertThat(bus.firstTimestamp(correlation, "INVOCATION_ACCEPTED"))
                .isBeforeOrEqualTo(bus.firstTimestamp(correlation, "INVOCATION_STREAM_READY"));
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-stream-reference-boundary")
    @Story("FEAT-017.stream.reference-boundary: streamRef 不暴露敏感信息")
    @DisplayName("Feat-017 streamRef is opaque and contains no physical endpoint")
    void feat017StreamReferenceBoundary() throws Exception {
        String correlation = id("stream-ref");
        sendAndAwait(request("CLIENT_INVOCATION_REQUESTED", MAINPLAN, correlation,
                createPayload("SendStreamingMessage", "stream ref boundary", true)), "INVOCATION_ACCEPTED");
        BusEvent ready = bus.await(correlation, "INVOCATION_STREAM_READY", EVENT_TIMEOUT);
        assertOpaqueStreamRef(ready.streamRef());
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-stream-sse-off-bus")
    @Story("FEAT-017.stream.sse-off-bus: token/SSE 数据不进 BUS")
    @DisplayName("Feat-017 keeps stream data on SSE and control data on BUS")
    void feat017StreamSseOffBus() throws Exception {
        String correlation = id("sse-off-bus");
        sendAndAwait(request("CLIENT_INVOCATION_REQUESTED", MAINPLAN, correlation,
                createPayload("SendStreamingMessage", "stream a canary response", true)), "INVOCATION_ACCEPTED");
        BusEvent ready = bus.await(correlation, "INVOCATION_STREAM_READY", EVENT_TIMEOUT);
        HttpResponse<String> response = subscribeHttp(MAINPLAN, ready.taskId(), ready.streamRef());
        assertThat(response.statusCode()).isBetween(200, 299);
        assertThat(bus.textFor(correlation)).doesNotContain("canary-token");
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-stream-resubscribe")
    @Story("FEAT-017.stream.resubscribe: 断开后重订阅已有 Task")
    @DisplayName("Feat-017 resubscription does not create a new task")
    void feat017StreamResubscribe() throws Exception {
        String correlation = id("resubscribe");
        sendAndAwait(request("CLIENT_INVOCATION_REQUESTED", MAINPLAN, correlation,
                createPayload("SendStreamingMessage", "resubscribe a short stream", true)), "INVOCATION_ACCEPTED");
        BusEvent ready = bus.await(correlation, "INVOCATION_STREAM_READY", EVENT_TIMEOUT);
        try (InputStream stream = openSubscription(MAINPLAN, ready.taskId(), ready.streamRef()).body()) {
            assertThat(stream).isNotNull();
        }
        BusEvent resubscribe = sendAndAwait(request("CLIENT_STREAM_SUBSCRIBE_REQUESTED", MAINPLAN,
                id("resubscribe-request"), subscribePayload(ready.taskId())));
        assertThat(resubscribe.eventType()).isEqualTo("INVOCATION_STREAM_READY");
        assertThat(resubscribe.streamRef()).isNotBlank();
        assertThat(bus.count(correlation, "INVOCATION_ACCEPTED")).isEqualTo(1);
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-stream-invalid-reference")
    @Story("FEAT-017.stream.invalid-reference: 非法 streamRef 订阅")
    @DisplayName("Feat-017 rejects an invalid stream reference without disclosure")
    void feat017StreamInvalidReference() throws Exception {
        String correlation = id("invalid-ref");
        sendAndAwait(request("CLIENT_INVOCATION_REQUESTED", MAINPLAN, correlation,
                createPayload("SendStreamingMessage", "invalid ref probe", true)), "INVOCATION_ACCEPTED");
        BusEvent ready = bus.await(correlation, "INVOCATION_STREAM_READY", EVENT_TIMEOUT);
        HttpResponse<String> response = subscribeHttp(MAINPLAN, ready.taskId(), "invalid-ref-" + id("x"));
        assertThat(response.statusCode()).isBetween(200, 299);
        assertThat(response.body()).contains("\"error\"");
        assertThat(response.body()).doesNotContain(ready.taskId());
    }

    @Test
    @Tag("blackbox")
    @Tag("story-feat-017-stream-terminal-not-available")
    @Story("FEAT-017.stream.terminal-not-available: 终态 Task 不签发流引用")
    @DisplayName("Feat-017 does not issue a stream reference for a terminal task")
    void feat017StreamTerminalNotAvailable() throws Exception {
        String correlation = id("terminal-stream");
        BusEvent accepted = sendAndAwait(request("CLIENT_INVOCATION_REQUESTED", MAINPLAN, correlation,
                createPayload("SendMessage", "terminal stream probe", false)), "INVOCATION_ACCEPTED");
        bus.await(correlation, "INVOCATION_TERMINAL", EVENT_TIMEOUT);
        BusEvent event = sendAndAwait(request("CLIENT_STREAM_SUBSCRIBE_REQUESTED", MAINPLAN,
                id("terminal-stream-request"), subscribePayload(accepted.taskId())));
        assertFailure(event, "INVOCATION_");
        assertThat(event.streamRef()).isBlank();
    }

    private BusEvent sendAndAwait(BusRequest request, String expectedType) throws Exception {
        bus.send(request);
        return bus.await(request.correlationId(), expectedType, EVENT_TIMEOUT);
    }

    private BusEvent sendAndAwait(BusRequest request) throws Exception {
        bus.send(request);
        return bus.awaitAny(request.correlationId(), EVENT_TIMEOUT);
    }

    private void assertAccepted(BusEvent event, String expectedType, String correlation) {
        assertThat(event.eventType()).isEqualTo(expectedType);
        assertThat(event.correlationId()).isEqualTo(correlation);
        assertThat(event.taskId()).isNotBlank();
    }

    private void assertFailure(BusEvent event, String familyPrefix) {
        assertThat(event.eventType()).startsWith(familyPrefix);
        assertThat(event.eventType()).containsAnyOf("FAILED", "REJECTED");
        assertThat(event.taskId()).isBlank();
    }

    private void assertNoProjection(BusRequest request, Duration timeout) throws Exception {
        bus.send(request);
        bus.assertNoEventForTrace(request.traceId(), timeout);
    }

    private void assertNoEvent(String correlation, String type, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (bus.find(correlation, type) != null) {
                Assertions.fail("unexpected projection for " + correlation + " type=" + type);
            }
            try {
                TimeUnit.MILLISECONDS.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for quiet window", e);
            }
        }
    }

    private HttpResponse<String> subscribeHttp(String owner, String taskId, String streamRef) throws Exception {
        return HTTP.send(subscribeHttpRequest(owner, taskId, streamRef), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<InputStream> openSubscription(String owner, String taskId, String streamRef) throws Exception {
        HttpResponse<InputStream> response = HTTP.send(
                subscribeHttpRequest(owner, taskId, streamRef), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isBetween(200, 299);
        return response;
    }

    private HttpRequest subscribeHttpRequest(String owner, String taskId, String streamRef) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", id("sse"));
        body.put("method", "SubscribeToTask");
        body.put("params", Map.of("id", taskId));
        return HttpRequest.newBuilder(URI.create(stack.baseUrl(owner) + "/a2a"))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .header("X-OpenJiuwen-Stream-Ref", streamRef)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
    }

    private static BusRequest request(String eventType, String target, String correlation, String payload) {
        return new BusRequest(eventType, id("message"), TENANT, "acceptance-producer",
                BUS_SERVICE_IDS.getOrDefault(target, target),
                correlation, id("trace"), id("idempotency"), Long.MAX_VALUE, payload, null,
                Map.of("routeHandle", "route-feat017", "capability", "a2a",
                        "originalCaller", "acceptance-producer"));
    }

    private static String createPayload(String method, String text, boolean streaming) throws Exception {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "ROLE_USER");
        message.put("messageId", id("a2a-message"));
        message.put("parts", List.of(Map.of("text", text)));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("message", message);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", id("rpc"));
        envelope.put("method", method);
        envelope.put("params", params);
        return JSON.writeValueAsString(envelope);
    }

    private static String queryPayload(String taskId) throws Exception {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", id("query"));
        envelope.put("method", "GetTask");
        envelope.put("params", Map.of("id", taskId));
        return JSON.writeValueAsString(envelope);
    }

    private static String subscribePayload(String taskId) throws Exception {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", id("subscribe"));
        envelope.put("method", "SubscribeToTask");
        envelope.put("params", Map.of("id", taskId));
        return JSON.writeValueAsString(envelope);
    }

    private static void assertOpaqueStreamRef(String streamRef) {
        assertThat(streamRef).isNotBlank();
        assertThat(streamRef).doesNotContain("http://", "https://", "token", "topic", "endpoint", "127.0.0.1");
    }

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Minimal real RocketMQ fixture; uses the preloaded apache/rocketmq:5.3.1 image. */
    private static final class RocketMqFixture implements AutoCloseable {
        private static final String IMAGE = "apache/rocketmq:5.3.1";
        private static final String NAMESRV = "127.0.0.1:9876";
        private final GenericContainer<?> nameserver;
        private final GenericContainer<?> broker;
        private final Network network;

        private RocketMqFixture(GenericContainer<?> nameserver, GenericContainer<?> broker, Network network) {
            this.nameserver = nameserver;
            this.broker = broker;
            this.network = network;
        }

        static RocketMqFixture start() {
            Network network = Network.newNetwork();
            FixedRocketContainer ns = new FixedRocketContainer(DockerImageName.parse(IMAGE))
                    .withCommand("sh", "mqnamesrv")
                    .withExposedPorts(9876)
                    .fixed(9876)
                    .withNetwork(network)
                    .withNetworkAliases("rocketmq-nameserver")
                    .waitingFor(Wait.forLogMessage(".*The Name Server boot success.*\\n", 1));
            FixedRocketContainer br = new FixedRocketContainer(DockerImageName.parse(IMAGE))
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
                ns.start();
                br.start();
                for (String topic : List.of("ascend_bus_invocation_req", "ascend_bus_invocation_deliver",
                        "ascend_bus_invocation_resp_in", "ascend_bus_invocation_resp_out", "ascend_bus_a2a_req",
                        "ascend_bus_a2a_deliver", "ascend_bus_a2a_resp_in", "ascend_bus_a2a_resp_out")) {
                    br.execInContainer("sh", "/home/rocketmq/rocketmq-5.3.1/bin/mqadmin", "updatetopic",
                            "-b", "127.0.0.1:10911", "-c", "DefaultCluster", "-t", topic);
                }
                return new RocketMqFixture(ns, br, network);
            } catch (Exception e) {
                br.stop();
                ns.stop();
                network.close();
                throw new IllegalStateException("Unable to start RocketMQ fixture", e);
            }
        }

        String nameserver() {
            return NAMESRV;
        }

        private static final class FixedRocketContainer extends GenericContainer<FixedRocketContainer> {
            FixedRocketContainer(DockerImageName image) { super(image); }
            FixedRocketContainer fixed(int port) { addFixedExposedPort(port, port); return this; }
        }

        @Override
        public void close() {
            broker.stop();
            nameserver.stop();
            network.close();
        }
    }

    private record BusRequest(String eventType, String messageId, String tenantId, String sourceServiceId,
                              String targetServiceId, String correlationId, String traceId, String idempotencyKey,
                              Long deadlineMillisEpoch, String inlinePayload, String payloadRef,
                              Map<String, String> properties) {
        BusRequest with(String key, String value) {
            Map<String, String> copy = new LinkedHashMap<>(properties);
            String nextEventType = eventType;
            String nextMessageId = messageId;
            String nextTenantId = tenantId;
            String nextSourceServiceId = sourceServiceId;
            String nextTargetServiceId = targetServiceId;
            String nextCorrelationId = correlationId;
            String nextTraceId = traceId;
            String nextIdempotencyKey = idempotencyKey;
            Long nextDeadlineMillisEpoch = deadlineMillisEpoch;
            String nextInlinePayload = inlinePayload;
            String nextPayloadRef = payloadRef;
            switch (key) {
                case "eventType" -> nextEventType = value;
                case "messageId" -> nextMessageId = value;
                case "tenantId" -> nextTenantId = value;
                case "sourceServiceId" -> nextSourceServiceId = value;
                case "targetServiceId" -> nextTargetServiceId = value;
                case "correlationId" -> nextCorrelationId = value;
                case "traceId" -> nextTraceId = value;
                case "idempotencyKey" -> nextIdempotencyKey = value;
                case "deadlineMillisEpoch" -> nextDeadlineMillisEpoch = value == null ? null : Long.parseLong(value);
                case "inlinePayload" -> nextInlinePayload = value;
                case "payloadRef" -> nextPayloadRef = value;
                default -> copy.put(key, value);
            }
            return new BusRequest(nextEventType, nextMessageId, nextTenantId, nextSourceServiceId, nextTargetServiceId,
                    nextCorrelationId, nextTraceId, nextIdempotencyKey, nextDeadlineMillisEpoch, nextInlinePayload,
                    nextPayloadRef, copy);
        }

        BusRequest without(String key) {
            return with(key, null);
        }
    }

    private record BusEvent(String eventType, String correlationId, String traceId, String taskId, String streamRef,
                              String text, Instant receivedAt) {
        static BusEvent from(MessageExt message) {
            Map<String, String> properties = message.getProperties();
            String inline = properties.getOrDefault("inlinePayload", "");
            Map<String, String> descriptor = parseDescriptor(inline);
            return new BusEvent(properties.getOrDefault("eventType", ""),
                    properties.getOrDefault("correlationId", descriptor.getOrDefault("correlationId", "")),
                    properties.getOrDefault("traceId", ""),
                    properties.getOrDefault("taskId", descriptor.getOrDefault("taskId", "")),
                    properties.getOrDefault("streamRef", descriptor.getOrDefault("streamRef", "")),
                    propertiesAsText(properties, inline), Instant.now());
        }

        private static String propertiesAsText(Map<String, String> properties, String inline) {
            StringBuilder text = new StringBuilder(inline == null ? "" : inline);
            properties.forEach((key, value) -> text.append(' ').append(key).append('=').append(value));
            return text.toString();
        }

        private static Map<String, String> parseDescriptor(String inline) {
            Map<String, String> values = new LinkedHashMap<>();
            if (inline != null && !inline.isBlank() && inline.trim().startsWith("{")) {
                try {
                    var root = JSON.readTree(inline);
                    for (String field : List.of("taskId", "streamRef", "status", "idempotencyResult",
                            "errorCode", "reason")) {
                        var value = root.get(field);
                        if (value != null && value.isValueNode()) {
                            values.put(field, value.asText());
                        }
                    }
                    return values;
                } catch (Exception ignored) {
                    // Fall through to the legacy descriptor format for request-side fixtures.
                }
            }
            for (String token : inline.split(";")) {
                int separator = token.indexOf('=');
                if (separator > 0) {
                    values.put(token.substring(0, separator), token.substring(separator + 1));
                }
            }
            return values;
        }
    }

    private static final class BusFixture implements AutoCloseable {
        private final String nameserver;
        private final String namespace;
        private final ConcurrentLinkedQueue<BusEvent> events = new ConcurrentLinkedQueue<>();
        private final DefaultMQProducer producer;
        private final DefaultMQPushConsumer observer;

        private BusFixture(String nameserver, String namespace) {
            this.nameserver = nameserver;
            this.namespace = namespace;
            this.producer = new DefaultMQProducer("feat017-producer-" + UUID.randomUUID());
            this.observer = new DefaultMQPushConsumer("feat017-observer-" + UUID.randomUUID());
        }

        private void start() throws Exception {
            producer.setNamesrvAddr(nameserver);
            producer.setSendMsgTimeout(10_000);
            observer.setNamesrvAddr(nameserver);
            observer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
            observer.subscribe("ascend_bus_invocation_resp_out", "*");
            observer.subscribe("ascend_bus_a2a_resp_out", "*");
            observer.registerMessageListener(new MessageListenerConcurrently() {
                @Override
                public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> messages,
                                                                  ConsumeConcurrentlyContext context) {
                    messages.stream().map(BusEvent::from).forEach(events::add);
                    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                }
            });
            observer.start();
            producer.start();
        }

        private void send(BusRequest request) throws Exception {
            String family = request.eventType() != null && request.eventType().startsWith("A2A_")
                    ? "a2a" : "invocation";
            byte[] body = request.inlinePayload() == null || request.inlinePayload().isBlank()
                    ? ("target=" + request.targetServiceId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    : request.inlinePayload().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Message message = new Message("ascend_bus_" + family + "_req", request.messageId(), body);
            put(message, "eventType", request.eventType());
            put(message, "messageId", request.messageId());
            put(message, "tenantId", request.tenantId());
            put(message, "sourceServiceId", request.sourceServiceId());
            put(message, "targetServiceId", request.targetServiceId());
            put(message, "correlationId", request.correlationId());
            put(message, "traceId", request.traceId());
            put(message, "idempotencyKey", request.idempotencyKey());
            put(message, "deadlineMillisEpoch", request.deadlineMillisEpoch() == null ? null
                    : request.deadlineMillisEpoch() == Long.MAX_VALUE
                    ? Long.toString(System.currentTimeMillis() + 120_000)
                    : Long.toString(request.deadlineMillisEpoch()));
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

        private BusEvent await(String correlation, String type, Duration timeout) {
            Instant deadline = Instant.now().plus(timeout);
            while (Instant.now().isBefore(deadline)) {
                BusEvent found = find(correlation, type);
                if (found != null) {
                    return found;
                }
                sleep(100);
            }
            throw new AssertionError("Timed out waiting for " + type + " correlation=" + correlation
                    + " observed=" + textFor(correlation));
        }

        private BusEvent awaitAny(String correlation, Duration timeout) {
            Instant deadline = Instant.now().plus(timeout);
            while (Instant.now().isBefore(deadline)) {
                BusEvent found = find(correlation, null);
                if (found != null) {
                    return found;
                }
                sleep(100);
            }
            throw new AssertionError("Timed out waiting for a projection correlation=" + correlation);
        }

        private BusEvent find(String correlation, String type) {
            return events.stream()
                    .filter(event -> correlation == null || correlation.equals(event.correlationId()))
                    .filter(event -> type == null || type.equals(event.eventType()))
                    .findFirst()
                    .orElse(null);
        }

        private void assertNoEventForTrace(String traceId, Duration timeout) {
            Instant deadline = Instant.now().plus(timeout);
            while (Instant.now().isBefore(deadline)) {
                BusEvent found = events.stream()
                        .filter(event -> traceId.equals(event.traceId()))
                        .findFirst()
                        .orElse(null);
                if (found != null) {
                    Assertions.fail("unexpected projection for trace=" + traceId + " eventType="
                            + found.eventType() + " correlation=" + found.correlationId());
                }
                sleep(100);
            }
        }

        private long count(String correlation, String type) {
            return events.stream().filter(event -> correlation.equals(event.correlationId()))
                    .filter(event -> type.equals(event.eventType())).count();
        }

        private Instant firstTimestamp(String correlation, String type) {
            return events.stream().filter(event -> correlation.equals(event.correlationId()))
                    .filter(event -> type.equals(event.eventType())).map(BusEvent::receivedAt)
                    .findFirst().orElseThrow();
        }

        private String textFor(String correlation) {
            return events.stream().filter(event -> correlation.equals(event.correlationId()))
                    .map(BusEvent::text).reduce("", (left, right) -> left + " " + right);
        }

        private static void sleep(long millis) {
            try {
                TimeUnit.MILLISECONDS.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for broker projection", e);
            }
        }

        @Override
        public void close() {
            observer.shutdown();
            producer.shutdown();
        }
    }
}

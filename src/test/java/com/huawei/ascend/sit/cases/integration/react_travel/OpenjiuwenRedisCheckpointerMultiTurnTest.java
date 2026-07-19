package com.huawei.ascend.sit.cases.integration.react_travel;

import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.BackingServices;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.lifecycle.TestContainerFactory;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OJ-06 — openjiuwen Redis checkpointer multi-turn continuity (full chain, streaming).
 *
 * <p>Uses {@link SutStack} + {@link InteractionFlow} directly (no OJ StackSupport / Runner /
 * main ScenarioData). Turn2 hard-requires {@code COMPLETED}; weak-semantic text is the memory
 * signal alongside contextId continuity.</p>
 *
 * <p>See {@code docs/cases/reactagent/OJ-06-openjiuwen-redis-checkpointer-multi-turn.md}.</p>
 */
@Tag("integration")
@Tag("openjiuwen")
@Tag("nightly")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenjiuwenRedisCheckpointerMultiTurnTest {

    private static final String MAINPLAN = "mainplan";
    private static final String TRIP = "trip";
    private static final String HOTEL = "hotel";
    private static final String REDIS_PROFILE = "redis";
    private static final String CHECKPOINTER_BEGIN_MARKER =
            "Begin to initializing checkpointer with type: ";

    /** Matches {@code testdata/integration/react_travel/oj-06-redis-multi-turn.json}. */
    private static final String TURN1_TEXT = "我要出差";
    /** Enough slots for mainplan to call trip (city + date + days) and reach COMPLETED. */
    private static final String TURN2_TEXT = "去北京，明天出发，3天";
    private static final List<String> TURN2_MUST_MATCH_ANY = List.of("北京", "出差");
    private static final List<String> TURN2_MUST_NOT_MATCH_ANY = List.of(
            "是否要出差", "你想出差吗", "您要去哪里出差", "请告诉我您的目的地");

    private static final List<TaskState> TURN1_ALLOWED_STATES = List.of(
            TaskState.TASK_STATE_COMPLETED,
            TaskState.TASK_STATE_INPUT_REQUIRED);

    private static final long FLOW_TIMEOUT_MS = 120_000L;

    private TestConfig config;
    private BackingServices redisBacking;
    private SutStack stack;
    private String redisHost;
    private String redisPort;

    @BeforeAll
    void startRedisFullChain() throws IOException {
        config = TestConfig.load();
        redisBacking = new BackingServices(config, Set.of("redis"), new TestContainerFactory(null));
        String hostColonPort = redisBacking.url("redis");
        int colon = hostColonPort.lastIndexOf(':');
        assertThat(colon).as("redis url host:port").isGreaterThan(0);
        redisHost = hostColonPort.substring(0, colon);
        redisPort = hostColonPort.substring(colon + 1);

        stack = SutStack.builder(config)
                .streaming(true)
                .backingServices(redisBacking)
                .agent(HOTEL, a -> withRedis(a))
                .agent(TRIP, a -> withRedis(a.downstream(HOTEL)))
                .agent(MAINPLAN, a -> withRedis(a.downstream(TRIP)))
                .start();

        assertAgentsUseCheckpointer(stack, "redis", "OJ-06.0", HOTEL, TRIP, MAINPLAN);
    }

    @AfterAll
    void tearDown() {
        if (stack != null) {
            stack.close();
        }
        if (redisBacking != null) {
            redisBacking.close();
        }
    }

    @Test
    @DisplayName("OJ-06: Redis checkpointer 全链流式 — Turn2 COMPLETED 且理解 Turn1")
    void oj06_redisMultiTurnStreaming_preservesContextAcrossTurns() throws IOException {
        long timeoutMs = Math.max(config.getPollTimeoutSeconds() * 1000L, FLOW_TIMEOUT_MS);
        InteractionFlow.FlowResult result = InteractionFlow.of(client(MAINPLAN))
                .withTimeoutMs(timeoutMs)
                .send(TURN1_TEXT)
                    .mayReachState(TaskState.TASK_STATE_INPUT_REQUIRED)
                    .assertThat(ctx -> assertThat(ctx.taskState())
                            .as("OJ-06.A turn1 state")
                            .isIn(TURN1_ALLOWED_STATES))
                    .assertGenerated(text -> assertThat(text)
                            .as("OJ-06.A turn1 reply")
                            .isNotBlank())
                .send(TURN2_TEXT)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertAnswer(text -> {
                        assertThat(text).as("OJ-06.A turn2 text").isNotBlank();
                        assertThat(TURN2_MUST_MATCH_ANY.stream().anyMatch(text::contains))
                                .as("OJ-06.B turn2MustMatchAny — reflect Turn1 travel intent")
                                .isTrue();
                        for (String forbidden : TURN2_MUST_NOT_MATCH_ANY) {
                            assertThat(text)
                                    .as("OJ-06.B must not reset session with: %s", forbidden)
                                    .doesNotContain(forbidden);
                        }
                    })
                .execute();

        assertThat(result.round(1).contextId())
                .as("OJ-06.C Turn2 contextId matches Turn1")
                .isEqualTo(result.round(0).contextId());

        assertRedisHasCheckpointData(
                redisHost,
                Integer.parseInt(redisPort.trim()),
                "OJ-06 after dialogue contextId=" + result.round(0).contextId());
    }

    private A2aServiceClient client(String name) {
        return stack.client(name);
    }

    /** openjiuwen redis profile + middleware checkpointer wiring (host/port from Testcontainers). */
    private SutStack.AgentBuilder withRedis(SutStack.AgentBuilder builder) {
        return builder
                .profile(REDIS_PROFILE)
                .env("REDIS_HOST", redisHost)
                .env("REDIS_PORT", redisPort)
                .property("openjiuwen.service.middleware.checkpointer.type", "redis")
                .property("openjiuwen.service.middleware.checkpointer.redis-ref", "default")
                .property("openjiuwen.service.middleware.redis.default.host", redisHost)
                .property("openjiuwen.service.middleware.redis.default.port", redisPort)
                .property("openjiuwen.service.middleware.redis.default.database", "0")
                .property("openjiuwen.service.middleware.redis.default.encrypted-password", "");
    }

    private static void assertAgentsUseCheckpointer(
            SutStack stack,
            String expectedType,
            String label,
            String... agentNames) throws IOException {
        List<String> failures = new ArrayList<>();
        for (String agent : agentNames) {
            Path log = agentStdoutLog(stack, agent);
            String lastType = lastCheckpointerType(
                    Files.readString(log, StandardCharsets.UTF_8));
            if (lastType == null) {
                failures.add(agent + ": no '" + CHECKPOINTER_BEGIN_MARKER + "' line in " + log);
            } else if (!expectedType.equals(lastType)) {
                failures.add(agent + ": latest checkpointer type is '" + lastType
                        + "', expected " + expectedType + " (see " + log + ")");
            }
        }
        assertThat(failures)
                .as("%s checkpointer startup gate", label)
                .isEmpty();
    }

    private static void assertRedisHasCheckpointData(
            String redisHost,
            int redisPort,
            String label) throws IOException {
        assertThat(isRedisReachable(redisHost, redisPort))
                .as("%s Redis reachable at %s:%d", label, redisHost, redisPort)
                .isTrue();
        long keyCount = redisDbSize(redisHost, redisPort);
        assertThat(keyCount)
                .as("%s Redis DBSIZE after dialogue", label)
                .isGreaterThan(0);
    }

    private static String lastCheckpointerType(String logContent) {
        String lastType = null;
        for (String line : logContent.split("\n")) {
            int idx = line.indexOf(CHECKPOINTER_BEGIN_MARKER);
            if (idx >= 0) {
                String tail = line.substring(idx + CHECKPOINTER_BEGIN_MARKER.length());
                lastType = tail.split(",", 2)[0].trim();
            }
        }
        return lastType;
    }

    private static Path agentStdoutLog(SutStack stack, String agentName) {
        var instance = stack.managedInstance(agentName);
        assertThat(instance)
                .as("managed agent '%s' for log gate", agentName)
                .isNotNull()
                .isInstanceOf(ManagedSutInstance.class);
        return ((ManagedSutInstance) instance).logFile();
    }

    private static boolean isRedisReachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2_000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static long redisDbSize(String host, int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5_000);
            socket.getOutputStream().write(
                    "*1\r\n$6\r\nDBSIZE\r\n".getBytes(StandardCharsets.UTF_8));
            byte[] buffer = new byte[256];
            int read = socket.getInputStream().read(buffer);
            if (read <= 0) {
                throw new IOException("Empty DBSIZE response from Redis");
            }
            String reply = new String(buffer, 0, read, StandardCharsets.UTF_8).trim();
            if (reply.startsWith("-")) {
                throw new IOException("Redis DBSIZE error: " + reply);
            }
            if (!reply.startsWith(":")) {
                throw new IOException("Unexpected DBSIZE reply: " + reply);
            }
            return Long.parseLong(reply.substring(1).split("\r\n")[0].trim());
        }
    }
}

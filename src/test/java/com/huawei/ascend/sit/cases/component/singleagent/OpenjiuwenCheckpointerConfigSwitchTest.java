package com.huawei.ascend.sit.cases.component.singleagent;

import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.client.TaskTextExtractor;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.BackingServices;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.lifecycle.TestContainerFactory;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OJ-07 — openjiuwen checkpointer config switch InMemory → Redis (B-04 semantic).
 *
 * <p>Single test method, two phases: Phase1 default in_memory mainplan, Phase2 {@code redis}
 * profile + Testcontainers Redis. Same two-turn dialogue each phase via {@link InteractionFlow}.
 * Single-mainplan (no trip): Turn2 may stay {@code INPUT_REQUIRED} — same allowance as OJ-03.</p>
 *
 * <p>See {@code docs/cases/reactagent/OJ-07-openjiuwen-checkpointer-config-switch.md}.</p>
 */
@Tag("component")
@Tag("openjiuwen")
@Tag("nightly")
class OpenjiuwenCheckpointerConfigSwitchTest {

    private static final Logger LOG = Logger.getLogger(OpenjiuwenCheckpointerConfigSwitchTest.class.getName());

    private static final String MAINPLAN = "mainplan";
    private static final String REDIS_PROFILE = "redis";
    private static final String CHECKPOINTER_BEGIN_MARKER =
            "Begin to initializing checkpointer with type: ";

    /** Turn2 keeps destination-only wording; single mainplan often re-asks dates (OJ-03 style). */
    private static final String TURN1_TEXT = "我要出差";
    private static final String TURN2_TEXT = "去北京";
    private static final List<String> TURN2_MUST_MATCH_ANY = List.of("北京", "出差");
    private static final List<String> TURN2_MUST_NOT_MATCH_ANY = List.of(
            "是否要出差", "你想出差吗", "您要去哪里出差", "请告诉我您的目的地");

    /** Single mainplan: Turn2 often re-asks dates rather than COMPLETED (no trip). */
    private static final List<TaskState> TURN_ALLOWED_STATES = List.of(
            TaskState.TASK_STATE_COMPLETED,
            TaskState.TASK_STATE_INPUT_REQUIRED);

    private static final long FLOW_TIMEOUT_MS = 120_000L;

    @Test
    @DisplayName("OJ-07: InMemory → Redis profile 切换 — 两阶段流式对话各自达标")
    void oj07_inMemoryThenRedis_sameDialogue_bothPassSemantics() throws Exception {
        TestConfig config = TestConfig.load();
        long timeoutMs = Math.max(config.getPollTimeoutSeconds() * 1000L, FLOW_TIMEOUT_MS);

        PhaseWiring phase1 = PhaseWiring.inMemory();
        PhaseWiring phase2 = PhaseWiring.redis();
        assertConfigDiffGate(phase1, phase2);

        LOG.info("OJ-07 Phase1 managed in-memory (agent=" + MAINPLAN + ", no redis profile)");
        try (SutStack stack = phase1.toStack(config, null, null).start()) {
            assertAgentsUseCheckpointer(stack, "in_memory", "OJ-07.P1", MAINPLAN);
            runTwoTurnDialogue(stack.client(MAINPLAN), timeoutMs, "OJ-07.P1");
        }

        LOG.info("OJ-07 Phase2 managed redis profile + Testcontainers Redis (agent=" + MAINPLAN + ")");
        try (BackingServices redisBacking = new BackingServices(config, Set.of("redis"), new TestContainerFactory(null))) {
            String hostColonPort = redisBacking.url("redis");
            int colon = hostColonPort.lastIndexOf(':');
            assertThat(colon).as("OJ-07 Phase2 redis url host:port").isGreaterThan(0);
            String redisHost = hostColonPort.substring(0, colon);
            String redisPort = hostColonPort.substring(colon + 1);

            try (SutStack stack = phase2.toStack(config, redisHost, redisPort)
                    .backingServices(redisBacking)
                    .start()) {
                assertAgentsUseCheckpointer(stack, "redis", "OJ-07.P2", MAINPLAN);
                String contextId = runTwoTurnDialogue(stack.client(MAINPLAN), timeoutMs, "OJ-07.P2");
                assertRedisHasCheckpointData(
                        redisHost,
                        Integer.parseInt(redisPort.trim()),
                        "OJ-07.P2 after dialogue contextId=" + contextId);
            }
        }
    }

    /** OJ-07.0c — only profile / redisBacked differ; streaming + agent stay the same. */
    private static void assertConfigDiffGate(PhaseWiring phase1, PhaseWiring phase2) {
        assertThat(phase1.profile()).as("OJ-07.0c Phase1 profile").isBlank();
        assertThat(phase2.profile()).as("OJ-07.0c Phase2 profile").isEqualTo(REDIS_PROFILE);
        assertThat(phase1.redisBacked()).as("OJ-07.0c Phase1 redisBacked").isFalse();
        assertThat(phase2.redisBacked()).as("OJ-07.0c Phase2 redisBacked").isTrue();
        assertThat(phase1.streaming()).as("OJ-07.0c Phase1 streaming").isTrue();
        assertThat(phase2.streaming()).as("OJ-07.0c Phase2 streaming").isTrue();
        assertThat(phase1.agentName()).as("OJ-07.0c agent name").isEqualTo(phase2.agentName());
    }

    /**
     * @return Turn1 contextId for Redis key gate labeling
     */
    private static String runTwoTurnDialogue(A2aServiceClient client, long timeoutMs, String label) {
        InteractionFlow.FlowResult result = InteractionFlow.of(client)
                .withTimeoutMs(timeoutMs)
                .send(TURN1_TEXT)
                    .mayReachState(TaskState.TASK_STATE_INPUT_REQUIRED)
                    .assertThat(ctx -> assertThat(ctx.taskState())
                            .as("%s turn1 state", label)
                            .isIn(TURN_ALLOWED_STATES))
                    .assertTask(task -> assertThat(TaskTextExtractor.textOf(task))
                            .as("%s turn1 reply", label)
                            .isNotBlank())
                .send(TURN2_TEXT)
                    // Prefer INPUT_REQUIRED wait so missing dates / no trip does not hang on
                    // awaitTerminalState; COMPLETED also accepted via mayReachState.
                    .mayReachState(TaskState.TASK_STATE_INPUT_REQUIRED)
                    .assertThat(ctx -> assertThat(ctx.taskState())
                            .as("%s turn2 state (COMPLETED or INPUT_REQUIRED on single mainplan)", label)
                            .isIn(TURN_ALLOWED_STATES))
                    .assertTask(task -> {
                        String text = TaskTextExtractor.textOf(task);
                        assertThat(text).as("%s turn2 text", label).isNotBlank();
                        assertThat(TURN2_MUST_MATCH_ANY.stream().anyMatch(text::contains))
                                .as("%s turn2MustMatchAny — reflect Turn1 travel intent", label)
                                .isTrue();
                        for (String forbidden : TURN2_MUST_NOT_MATCH_ANY) {
                            assertThat(text)
                                    .as("%s must not reset session with: %s", label, forbidden)
                                    .doesNotContain(forbidden);
                        }
                    })
                .execute();

        assertThat(result.round(1).contextId())
                .as("%s Turn2 contextId matches Turn1", label)
                .isEqualTo(result.round(0).contextId());
        return result.round(0).contextId();
    }

    /**
     * Snapshot of per-phase mainplan wiring for OJ-07.0c meta gate.
     */
    private record PhaseWiring(String profile, boolean redisBacked, boolean streaming, String agentName) {

        static PhaseWiring inMemory() {
            return new PhaseWiring("", false, true, MAINPLAN);
        }

        static PhaseWiring redis() {
            return new PhaseWiring(REDIS_PROFILE, true, true, MAINPLAN);
        }

        SutStack.Builder toStack(TestConfig config, String redisHost, String redisPort) {
            if (redisBacked()) {
                assertThat(redisHost).as("OJ-07 Phase2 redis host").isNotBlank();
                assertThat(redisPort).as("OJ-07 Phase2 redis port").isNotBlank();
                return SutStack.builder(config)
                        .streaming(streaming())
                        .agent(agentName(), a -> withRedis(a, redisHost, redisPort));
            }
            return SutStack.builder(config)
                    .streaming(streaming())
                    .agent(agentName());
        }
    }

    private static SutStack.AgentBuilder withRedis(SutStack.AgentBuilder builder,
                                                   String redisHost, String redisPort) {
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

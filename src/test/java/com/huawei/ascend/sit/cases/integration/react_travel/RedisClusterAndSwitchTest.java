package com.huawei.ascend.sit.cases.integration.react_travel;

import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.client.TaskTextExtractor;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.BackingServices;
import com.huawei.ascend.sit.lifecycle.ContainerFactory;
import com.huawei.ascend.sit.lifecycle.ManagedContainer;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.lifecycle.TestContainerFactory;
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** FEAT-003 Redis Cluster equivalence, switching and public SPI contract tests. */
@Feature("FEAT-003: 智能体任务状态缓存")
@Tag("feat-003")
@Tag("integration")
@ResourceLock("feat003-redis-cluster-ports")
class RedisClusterAndSwitchTest {
    private static final String MAINPLAN = "mainplan";
    private static final String TRIP = "trip";
    private static final String HOTEL = "hotel";
    private static final String PREFIX = "openjiuwen.service.middleware.";
    private static final long FLOW_TIMEOUT_MS = 120_000L;

    @Test
    @Tag("blackbox")
    @Tag("env-gated")
    @Stories({
            @Story("FEAT-003.cluster.equivalence: Redis Cluster 与 standalone 业务语义等价")
    })
    @DisplayName("Feat-003 cluster 与 standalone 的全链 Task/checkpoint 语义等价")
    void feat003ClusterMatchesStandaloneBusinessSemantics() throws Exception {
        TestConfig config = TestConfig.load();
        JourneyEvidence standalone;
        try (StandaloneFixture redis = StandaloneFixture.start(config);
             SutStack stack = startStandalone(config, redis)) {
            standalone = journey(stack, "standalone-" + shortId());
        }
        LogOffset offset = LogOffset.capture(config, MAINPLAN);
        try (ClusterFixture fixture = ClusterFixture.start(config);
             SutStack stack = startCluster(config, fixture, Map.of())) {
            ClusterEnvironment cluster = fixture.environment();
            JourneyEvidence actual = journey(stack, "cluster-" + shortId());
            assertThat(actual.state()).isEqualTo(standalone.state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
            assertThat(actual.text()).contains("北京");
            assertThat(stack.client(MAINPLAN).getTask(actual.taskId()).id()).isEqualTo(actual.taskId());
            try (ClusterContract client = ClusterContract.open(cluster)) {
                assertThat((List<Object>) client.call("scanIter", types(String.class), "*"))
                        .as("cluster-aware data plane contains Task/checkpoint keys").isNotEmpty();
            }
            int port = stack.port(MAINPLAN);
            long pid = managed(stack, MAINPLAN).pid();
            stack.stop(MAINPLAN);
            await().atMost(Duration.ofSeconds(15)).until(() -> !stack.isRunning(MAINPLAN));
            stack.start(MAINPLAN);
            assertThat(stack.port(MAINPLAN)).isEqualTo(port);
            assertThat(managed(stack, MAINPLAN).pid()).isNotEqualTo(pid);
            assertThat(stack.client(MAINPLAN).getTask(actual.taskId()).id()).isEqualTo(actual.taskId());

            String log = offset.increment();
            assertThat(log).contains("endpoint-type=cluster", "JedisClusterRuntimeRedisClient",
                    "timeoutMs=", "passwordConfigured=false");
            for (Endpoint node : cluster.nodes()) assertThat(log).doesNotContain(node.toString());
        }
    }

    @Test
    @Tag("blackbox")
    @Tag("env-gated")
    @Stories({
            @Story("FEAT-003.cluster.nodes-priority: cluster nodes 配置优先于 standalone host/port")
    })
    @DisplayName("Feat-003 cluster 使用 nodes 而不是 standalone host/port")
    void feat003ClusterUsesNodesInsteadOfStandaloneHostPort() throws Exception {
        TestConfig config = TestConfig.load();
        try (ClusterFixture fixture = ClusterFixture.start(config);
             SutStack stack = startCluster(config, fixture, Map.of(
                     PREFIX + "redis.default.host", "127.0.0.1",
                     PREFIX + "redis.default.port", "1"))) {
            assertThat(journey(stack, "nodes-priority-" + shortId()).state())
                    .isEqualTo(TaskState.TASK_STATE_COMPLETED);
        }
    }

    @Test
    @Tag("blackbox")
    @Tag("env-gated")
    @Stories({
            @Story("FEAT-003.cluster.database-ignored: cluster 忽略 standalone database 配置")
    })
    @DisplayName("Feat-003 cluster 忽略 standalone database 并输出安全诊断")
    void feat003ClusterIgnoresStandaloneDatabaseSetting() throws Exception {
        TestConfig config = TestConfig.load();
        LogOffset offset = LogOffset.capture(config, MAINPLAN);
        try (ClusterFixture fixture = ClusterFixture.start(config);
             SutStack stack = startCluster(config, fixture,
                     Map.of(PREFIX + "redis.default.database", "3"))) {
            assertThat(journey(stack, "db-ignored-" + shortId()).state())
                    .isEqualTo(TaskState.TASK_STATE_COMPLETED);
            assertThat(offset.increment()).contains("databaseIgnored=3");
        }
    }

    @Test
    @Tag("blackbox")
    @Tag("env-gated")
    @Stories({
            @Story("FEAT-003.cluster.switch: standalone 与 cluster 双向配置切换")
    })
    @DisplayName("Feat-003 standalone 到 cluster 再切回且业务代码不变")
    void feat003SwitchesStandaloneClusterAndBackWithoutBusinessChanges() throws Exception {
        TestConfig config = TestConfig.load();
        TaskState first;
        TaskState middle;
        TaskState last;
        try (StandaloneFixture redis = StandaloneFixture.start(config);
             SutStack stack = startStandalone(config, redis)) {
            first = journey(stack, "switch-a-" + shortId()).state();
        }
        try (ClusterFixture fixture = ClusterFixture.start(config);
             SutStack stack = startCluster(config, fixture, Map.of())) {
            middle = journey(stack, "switch-b-" + shortId()).state();
        }
        try (StandaloneFixture redis = StandaloneFixture.start(config);
             SutStack stack = startStandalone(config, redis)) {
            last = journey(stack, "switch-c-" + shortId()).state();
        }
        assertThat(List.of(first, middle, last)).containsOnly(TaskState.TASK_STATE_COMPLETED);
    }

    @Test
    @Tag("blackbox")
    @Tag("env-gated")
    @Stories({
            @Story("FEAT-003.cluster.node-failure: cluster 单节点故障行为可观测")
    })
    @DisplayName("Feat-003 cluster 单节点故障后行为可观测且诊断脱敏")
    void feat003ClusterNodeFailureHasDefinedObservableBehavior() throws Exception {
        TestConfig config = TestConfig.load();
        try (ClusterFixture fixture = ClusterFixture.start(config)) {
            fixture.requireNodeFailureSupport();
            try (SutStack stack = startCluster(config, fixture, Map.of())) {
                assertThat(journey(stack, "pre-failure-" + shortId()).state())
                        .isEqualTo(TaskState.TASK_STATE_COMPLETED);
                fixture.failOneMaster();
                observeAfterNodeFailure(stack);
            }
        }
    }

    @Nested
    @Tag("contract")
    class RuntimeRedisClusterContract {
        @Test
        @Stories({
                @Story("FEAT-003.contract.cluster: cluster Redis SPI 文本与二进制合同")
        })
        @DisplayName("Feat-003 cluster 文本和二进制重载与 standalone 等价")
        void feat003ClusterTextAndBinaryOperationsFollowContract() throws Exception {
            TestConfig config = TestConfig.load();
            try (ClusterFixture fixture = ClusterFixture.start(config);
                 ClusterContract client = ClusterContract.open(fixture.environment())) {
                String id = shortId();
                byte[] key = bytes("bin:" + id);
                byte[] value = new byte[]{0, 2, -1};
                assertThat(client.call("set", types(String.class, String.class), "text:" + id, "v"))
                        .isEqualTo("OK");
                assertRedisTextValue(client.call("get", types(String.class), "text:" + id), "v");
                assertThat(client.call("set", types(byte[].class, byte[].class), key, value)).isEqualTo("OK");
                assertThat((byte[]) client.call("get", types(byte[].class), key)).containsExactly(value);
                assertThat(client.call("set", types(String.class, byte[].class), "mixed:" + id, value)).isEqualTo("OK");
                assertThat(client.call("setex", types(String.class, long.class, String.class),
                        "ttl:" + id, 30L, "v")).isEqualTo("OK");
                assertThat(client.call("setex", types(byte[].class, long.class, byte[].class),
                        bytes("ttl-bin:" + id), 30L, value)).isEqualTo("OK");
                assertThat(client.call("setnx", types(String.class, String.class), "nx:" + id, "v")).isEqualTo(1L);
                assertThat(client.call("setnx", types(byte[].class, byte[].class),
                        bytes("nx-bin:" + id), value)).isEqualTo(1L);
                assertThat(client.call("exists", types(String.class), "text:" + id)).isEqualTo(true);
                assertThat(client.call("exists", types(byte[].class), key)).isEqualTo(true);
                assertThat(client.call("expire", types(String.class, long.class), "text:" + id, 30L)).isEqualTo(1L);
                assertThat(client.call("expire", types(byte[].class, long.class), key, 30L)).isEqualTo(1L);
            }
        }

        @Test
        @Stories({
                @Story("FEAT-003.contract.cluster: cluster Redis SPI 跨 slot 合同")
        })
        @DisplayName("Feat-003 cluster mget 与批量 delete 支持跨 slot key")
        void feat003ClusterCrossSlotMgetAndDeleteFollowContract() throws Exception {
            TestConfig config = TestConfig.load();
            try (ClusterFixture fixture = ClusterFixture.start(config);
                 ClusterContract client = ClusterContract.open(fixture.environment())) {
                String id = shortId();
                String a = "cross:{a}:" + id;
                String b = "cross:{b}:" + id;
                client.call("set", types(String.class, String.class), a, "A");
                client.call("set", types(String.class, String.class), b, "B");
                List<Object> values = (List<Object>) client.call("mget", types(String[].class),
                        (Object) new String[]{b, "cross:{missing}:" + id, a});
                assertThat(values).hasSize(3);
                assertRedisTextValue(values.get(0), "B");
                assertThat(values.get(1)).isNull();
                assertRedisTextValue(values.get(2), "A");
                assertThat(client.call("del", types(String[].class), (Object) new String[]{a, b})).isEqualTo(2L);
                byte[] ba = bytes("cross-bin:{a}:" + id);
                byte[] bb = bytes("cross-bin:{b}:" + id);
                client.call("set", types(byte[].class, byte[].class), ba, bytes("A"));
                client.call("set", types(byte[].class, byte[].class), bb, bytes("B"));
                assertThat(client.call("del", types(byte[][].class), (Object) new byte[][]{ba, bb})).isEqualTo(2L);
            }
        }

        @Test
        @Stories({
                @Story("FEAT-003.contract.cluster: cluster Redis SPI 多节点 scan 合同")
        })
        @DisplayName("Feat-003 cluster scanIter 汇总所有节点且不使用 KEYS")
        void feat003ClusterScanCoversAllNodes() throws Exception {
            TestConfig config = TestConfig.load();
            try (ClusterFixture fixture = ClusterFixture.start(config);
                 ClusterContract client = ClusterContract.open(fixture.environment())) {
                String prefix = "scan:" + shortId() + ':';
                List<String> keys = List.of(prefix + "{a}", prefix + "{b}", prefix + "{c}");
                for (String key : keys) client.call("set", types(String.class, String.class), key, key);
                assertThat((List<Object>) client.call("scanIter", types(String.class), prefix + "*"))
                        .containsAll(keys);
            }
        }
    }

    private static void triggerExternalNodeFailure() throws Exception {
        String url = System.getProperty("feat003.cluster.node-failure-trigger-url", "").trim();
        assumeTrue(!url.isBlank(),
                "provide an authenticated site fixture hook with -Dfeat003.cluster.node-failure-trigger-url=...");
        java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                        .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isBetween(200, 299);
    }

    private static void observeAfterNodeFailure(SutStack stack) {
        try {
            assertThat(journey(stack, String.valueOf(System.nanoTime())).state())
                    .isEqualTo(TaskState.TASK_STATE_COMPLETED);
        } catch (RuntimeException | AssertionError failure) {
            assertThat(failure.toString()).containsIgnoringCase("redis");
        }
    }

    private static JourneyEvidence journey(SutStack stack, String marker) {
        InteractionFlow.FlowResult result = InteractionFlow.of(stack.client(MAINPLAN))
                .withTimeoutMs(FLOW_TIMEOUT_MS).withContextId("ctx-" + marker)
                .send("我要出差，记住" + marker).mayReachState(TaskState.TASK_STATE_INPUT_REQUIRED)
                .send("从深圳去北京，明天出发，3天，住宿2晚。差标：无差标限制。"
                        + "请按默认规则直接推荐，不要继续追问。")
                .awaitState(TaskState.TASK_STATE_COMPLETED).execute();
        Task task = stack.client(MAINPLAN).getTask(result.lastTaskId());
        return new JourneyEvidence(task.id(), task.status().state(), TaskTextExtractor.textOf(task));
    }

    private static SutStack startStandalone(TestConfig config, StandaloneFixture fixture) {
        Map<String, String> p = baseProperties();
        p.put(PREFIX + "redis.default.type", "standalone");
        p.put(PREFIX + "redis.default.host", fixture.endpoint().host());
        p.put(PREFIX + "redis.default.port", Integer.toString(fixture.endpoint().port()));
        p.put(PREFIX + "redis.default.database", "0");
        return start(config, fixture.backing(), p);
    }

    private static SutStack startCluster(TestConfig config, ClusterFixture fixture, Map<String, String> overrides) {
        Map<String, String> p = baseProperties();
        p.put(PREFIX + "redis.default.type", "cluster");
        for (int i = 0; i < fixture.environment().nodes().size(); i++) {
            p.put(PREFIX + "redis.default.nodes[" + i + "]", fixture.environment().nodes().get(i).toString());
        }
        p.putAll(overrides);
        return start(config, fixture.backing(), p);
    }

    private static Map<String, String> baseProperties() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put(PREFIX + "checkpointer.type", "redis");
        p.put(PREFIX + "checkpointer.redis-ref", "default");
        p.put(PREFIX + "redis.default.encrypted-password", "");
        return p;
    }

    private static SutStack start(TestConfig config, BackingServices backing, Map<String, String> p) {
        return SutStack.builder(config).streaming(true).backingServices(backing)
                .agent(HOTEL, a -> apply(a.profile("redis"), p))
                .agent(TRIP, a -> apply(a.profile("redis").downstream(HOTEL), p))
                .agent(MAINPLAN, a -> apply(a.profile("redis").downstream(TRIP), p)).start();
    }

    private static void apply(SutStack.AgentBuilder builder, Map<String, String> properties) {
        properties.forEach(builder::property);
    }

    private static ManagedSutInstance managed(SutStack stack, String name) {
        assertThat(stack.managedInstance(name)).isInstanceOf(ManagedSutInstance.class);
        return (ManagedSutInstance) stack.managedInstance(name);
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void assertRedisTextValue(Object actual, String expected) {
        assertThat(actual).as("Redis text value").isNotNull();
        if (actual instanceof byte[] binary) {
            assertThat(binary).containsExactly(bytes(expected));
            return;
        }
        assertThat(actual).isInstanceOf(String.class).isEqualTo(expected);
    }
    private static Class<?>[] types(Class<?>... values) { return values; }
    private static String shortId() { return UUID.randomUUID().toString().substring(0, 8); }

    private record JourneyEvidence(String taskId, TaskState state, String text) {}
    private record Endpoint(String host, int port) {
        static Endpoint parse(String value) {
            int colon = value.lastIndexOf(':');
            if (colon <= 0) throw new IllegalArgumentException("Redis endpoint must be host:port");
            return new Endpoint(value.substring(0, colon), Integer.parseInt(value.substring(colon + 1)));
        }
        @Override public String toString() { return host + ':' + port; }
    }

    private record ClusterEnvironment(List<Endpoint> nodes) {
        static ClusterEnvironment configuredOrNull() {
            String configured = System.getProperty("feat003.cluster.nodes", "").trim();
            if (configured.isBlank()) {
                return null;
            }
            List<Endpoint> nodes = java.util.Arrays.stream(configured.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).map(Endpoint::parse).toList();
            assumeTrue(nodes.size() >= 3, "a real multi-node Redis Cluster needs at least three seeds");
            return new ClusterEnvironment(nodes);
        }
    }
    private static final class StandaloneFixture implements AutoCloseable {
        private final BackingServices backing;
        private final Endpoint endpoint;

        private StandaloneFixture(BackingServices backing, Endpoint endpoint) {
            this.backing = backing;
            this.endpoint = endpoint;
        }

        static StandaloneFixture start(TestConfig config) {
            BackingServices backing = new BackingServices(config, Set.of("redis"), new TestContainerFactory(null));
            return new StandaloneFixture(backing, Endpoint.parse(backing.url("redis")));
        }

        BackingServices backing() { return backing; }
        Endpoint endpoint() { return endpoint; }
        @Override public void close() { backing.close(); }
    }

    private static final class ClusterFixture implements AutoCloseable {
        private final ClusterEnvironment environment;
        private final BackingServices backing;
        private final ManagedRedisCluster managedCluster;

        private ClusterFixture(ClusterEnvironment environment, BackingServices backing,
                               ManagedRedisCluster managedCluster) {
            this.environment = environment;
            this.backing = backing;
            this.managedCluster = managedCluster;
        }

        static ClusterFixture start(TestConfig config) throws Exception {
            ClusterEnvironment configured = ClusterEnvironment.configuredOrNull();
            return configured == null ? managed(config) : external(config, configured);
        }

        static ClusterFixture external(TestConfig config, ClusterEnvironment environment) {
            BackingServices backing = new BackingServices(config, Set.of("redis"),
                    new RedisClusterContainerFactory(environment, () -> { }));
            return new ClusterFixture(environment, backing, null);
        }

        private static ClusterFixture managed(TestConfig config) throws Exception {
            String image = System.getProperty("feat003.cluster.image",
                    config.getString("sut.services.redis.image", "redis:7-alpine"));
            int basePort = Integer.getInteger("feat003.cluster.base-port", 7000);
            ManagedRedisCluster cluster = ManagedRedisCluster.start(image, basePort);
            try {
                BackingServices backing = new BackingServices(config, Set.of("redis"),
                        new RedisClusterContainerFactory(cluster.environment(), cluster::close));
                return new ClusterFixture(cluster.environment(), backing, cluster);
            } catch (RuntimeException failure) {
                cluster.close();
                throw failure;
            }
        }

        ClusterEnvironment environment() { return environment; }
        BackingServices backing() { return backing; }

        void requireNodeFailureSupport() {
            if (managedCluster != null) {
                return;
            }
            assumeTrue(Boolean.getBoolean("feat003.cluster.managed-failure-enabled"),
                    "external node-failure needs a site-managed fixture and trigger hook");
            assumeTrue(!System.getProperty("feat003.cluster.node-failure-trigger-url", "").isBlank(),
                    "provide -Dfeat003.cluster.node-failure-trigger-url=... for the external cluster");
        }

        void failOneMaster() throws Exception {
            if (managedCluster != null) {
                managedCluster.failFirstMasterAndAwaitPromotion();
            } else {
                triggerExternalNodeFailure();
            }
        }

        @Override public void close() { backing.close(); }
    }

    /** Adapts either an external or managed routable cluster to the common backing-service lifecycle. */
    private static final class RedisClusterContainerFactory implements ContainerFactory {
        private final ClusterEnvironment environment;
        private final Runnable closeAction;

        private RedisClusterContainerFactory(ClusterEnvironment environment, Runnable closeAction) {
            this.environment = environment;
            this.closeAction = closeAction;
        }

        @Override
        public ManagedContainer start(String name, String image, int port, Map<String, String> env) {
            Endpoint seed = environment.nodes().get(0);
            return new ManagedContainer() {
                @Override public String host() { return seed.host(); }
                @Override public int mappedPort() { return seed.port(); }
                @Override public void close() { closeAction.run(); }
            };
        }
    }

    private static final class ManagedRedisCluster implements AutoCloseable {
        private static final int NODE_COUNT = 6;
        private static final int REPLICA_COUNT = 1;
        private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(60);
        private static final Duration FAILOVER_TIMEOUT = Duration.ofSeconds(40);

        private final FixedPortContainer container;
        private final int basePort;
        private final ClusterEnvironment environment;
        private boolean closed;

        private ManagedRedisCluster(FixedPortContainer container, int basePort) {
            this.container = container;
            this.basePort = basePort;
            this.environment = new ClusterEnvironment(java.util.stream.IntStream.range(0, 3)
                    .mapToObj(index -> new Endpoint("127.0.0.1", basePort + index))
                    .toList());
        }

        static ManagedRedisCluster start(String image, int basePort) throws Exception {
            validateBasePort(basePort);
            FixedPortContainer container = new FixedPortContainer(DockerImageName.parse(image));
            for (int port = basePort; port < basePort + NODE_COUNT; port++) {
                container.bindFixedPort(port);
            }
            container.withCommand("sh", "-c", startupScript(basePort))
                    .waitingFor(Wait.forLogMessage(".*FEAT003_CLUSTER_READY.*\\n", 1))
                    .withStartupTimeout(STARTUP_TIMEOUT);
            try {
                container.start();
                ManagedRedisCluster cluster = new ManagedRedisCluster(container, basePort);
                assertThat(cluster.clusterInfo()).contains("cluster_state:ok", "cluster_slots_ok:16384");
                return cluster;
            } catch (Exception failure) {
                container.stop();
                throw failure;
            } catch (AssertionError failure) {
                container.stop();
                throw failure;
            }
        }

        ClusterEnvironment environment() {
            return environment;
        }

        void failFirstMasterAndAwaitPromotion() throws Exception {
            int observerPort = basePort + 1;
            String before = clusterNodes(observerPort);
            String masterId = nodeId(before, basePort, "master");
            int replicaPort = replicaPort(before, masterId);

            container.execInContainer("redis-cli", "-p", Integer.toString(basePort),
                    "shutdown", "nosave");

            long deadline = System.nanoTime() + FAILOVER_TIMEOUT.toNanos();
            String lastNodes = before;
            while (System.nanoTime() < deadline) {
                lastNodes = clusterNodes(observerPort);
                if (hasRole(lastNodes, replicaPort, "master")
                        && clusterInfo(observerPort).contains("cluster_state:ok")) {
                    return;
                }
                TimeUnit.SECONDS.sleep(1);
            }
            throw new AssertionError("Redis Cluster replica " + replicaPort
                    + " was not promoted after master " + basePort + " stopped:\n" + lastNodes);
        }

        private String clusterInfo() throws Exception {
            return clusterInfo(basePort);
        }

        private String clusterInfo(int port) throws Exception {
            return exec("redis-cli", "-p", Integer.toString(port), "cluster", "info");
        }

        private String clusterNodes(int port) throws Exception {
            return exec("redis-cli", "-p", Integer.toString(port), "cluster", "nodes");
        }

        private String exec(String... command) throws Exception {
            var result = container.execInContainer(command);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("Redis Cluster command failed: "
                        + String.join(" ", command) + "\n" + result.getStderr());
            }
            return result.getStdout();
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                container.stop();
            }
        }

        private static String nodeId(String nodes, int port, String role) {
            for (String line : nodes.lines().toList()) {
                String[] fields = line.trim().split("\\s+");
                if (fields.length > 2 && addressUsesPort(fields[1], port) && hasFlag(fields[2], role)) {
                    return fields[0];
                }
            }
            throw new IllegalStateException("No " + role + " node found on port " + port + ":\n" + nodes);
        }

        private static int replicaPort(String nodes, String masterId) {
            for (String line : nodes.lines().toList()) {
                String[] fields = line.trim().split("\\s+");
                if (fields.length > 3 && hasFlag(fields[2], "slave") && masterId.equals(fields[3])) {
                    return portOf(fields[1]);
                }
            }
            throw new IllegalStateException("No replica found for master " + masterId + ":\n" + nodes);
        }

        private static boolean hasRole(String nodes, int port, String role) {
            return nodes.lines().map(String::trim).map(line -> line.split("\\s+"))
                    .anyMatch(fields -> fields.length > 7 && addressUsesPort(fields[1], port)
                            && hasFlag(fields[2], role) && "connected".equals(fields[7]));
        }

        private static boolean addressUsesPort(String address, int port) {
            return address.startsWith("127.0.0.1:" + port + "@");
        }

        private static boolean hasFlag(String flags, String expected) {
            return java.util.Arrays.asList(flags.split(",")).contains(expected);
        }

        private static int portOf(String address) {
            int colon = address.lastIndexOf(':');
            int bus = address.indexOf('@', colon);
            return Integer.parseInt(address.substring(colon + 1, bus));
        }

        private static void validateBasePort(int basePort) {
            if (basePort < 1024 || basePort + NODE_COUNT - 1 > 55_535) {
                throw new IllegalArgumentException("feat003.cluster.base-port must leave room for six client "
                        + "ports and their +10000 cluster bus ports: " + basePort);
            }
        }

        private static String startupScript(int basePort) {
            return """
                    set -eu
                    base=%d
                    last=$((base + 5))
                    for port in $(seq "$base" "$last"); do
                      mkdir -p "/data/$port"
                      redis-server \
                        --port "$port" \
                        --bind 0.0.0.0 \
                        --protected-mode no \
                        --cluster-enabled yes \
                        --cluster-config-file "/data/$port/nodes.conf" \
                        --cluster-node-timeout 5000 \
                        --cluster-replica-validity-factor 0 \
                        --cluster-announce-ip 127.0.0.1 \
                        --cluster-announce-port "$port" \
                        --cluster-announce-bus-port "$((port + 10000))" \
                        --appendonly no \
                        --daemonize yes \
                        --dir "/data/$port"
                    done
                    for port in $(seq "$base" "$last"); do
                      attempts=0
                      until redis-cli -p "$port" ping >/dev/null 2>&1; do
                        attempts=$((attempts + 1))
                        if [ "$attempts" -ge 30 ]; then
                          echo "Redis node on port $port did not become ready within 30 seconds" >&2
                          exit 1
                        fi
                        sleep 1
                      done
                    done
                    nodes=""
                    for port in $(seq "$base" "$last"); do
                      nodes="$nodes 127.0.0.1:$port"
                    done
                    redis-cli --cluster create $nodes --cluster-replicas %d --cluster-yes
                    master0=$(redis-cli -p "$base" cluster myid)
                    master1=$(redis-cli -p "$((base + 1))" cluster myid)
                    master2=$(redis-cli -p "$((base + 2))" cluster myid)
                    until redis-cli -p "$((base + 5))" cluster replicate "$master0"; do sleep 1; done
                    until redis-cli -p "$((base + 3))" cluster replicate "$master1"; do sleep 1; done
                    until redis-cli -p "$((base + 4))" cluster replicate "$master2"; do sleep 1; done
                    until [ "$(redis-cli -p "$base" cluster nodes | grep -c ' slave ')" -eq 3 ]; do
                      sleep 1
                    done
                    until redis-cli -p "$base" cluster info | grep -q '^cluster_state:ok'; do
                      sleep 1
                    done
                    echo FEAT003_CLUSTER_READY
                    tail -f /dev/null
                    """.formatted(basePort, REPLICA_COUNT);
        }
    }

    private static final class FixedPortContainer extends GenericContainer<FixedPortContainer> {
        private FixedPortContainer(DockerImageName image) {
            super(image);
        }

        private void bindFixedPort(int port) {
            addFixedExposedPort(port, port);
        }
    }

    /** Executes the real test-scope RuntimeRedisClient without changing this repository's pom. */
    private static final class ClusterContract implements AutoCloseable {
        private final Object runtimeClient;
        private ClusterContract(Object runtimeClient) { this.runtimeClient = runtimeClient; }

        static ClusterContract open(ClusterEnvironment environment) throws Exception {
            Class<?> hostAndPort = contractClass("redis.clients.jedis.HostAndPort");
            Class<?> jedisCluster = contractClass("redis.clients.jedis.JedisCluster");
            Class<?> implementation = contractClass(
                    "com.openjiuwen.service.adapters.common.middleware.redis.JedisClusterRuntimeRedisClient");
            Constructor<?> endpointConstructor = hostAndPort.getConstructor(String.class, int.class);
            Set<Object> seeds = new LinkedHashSet<>();
            for (Endpoint node : environment.nodes()) {
                seeds.add(endpointConstructor.newInstance(node.host(), node.port()));
            }
            Object delegate = jedisCluster.getConstructor(Set.class).newInstance(seeds);
            return new ClusterContract(implementation.getConstructor(jedisCluster).newInstance(delegate));
        }

        Object call(String method, Class<?>[] parameterTypes, Object... args) {
            try {
                Method target = runtimeClient.getClass().getMethod(method, parameterTypes);
                return target.invoke(runtimeClient, args);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("RuntimeRedisClient." + method + " cluster contract failed", e);
            }
        }

        @Override
        public void close() throws Exception {
            runtimeClient.getClass().getMethod("close").invoke(runtimeClient);
        }

        private static Class<?> contractClass(String name) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException e) {
assumeTrue(false,name);
                throw new AssertionError(e);
            }
        }
    }

    private static final class LogOffset {
        private final Path path;
        private final long offset;
        private LogOffset(Path path, long offset) { this.path = path; this.offset = offset; }

        static LogOffset capture(TestConfig config, String agent) throws IOException {
            String configured = config.getString("sut.logging.dir", "");
            Path root = configured.isBlank()
                    ? Path.of(System.getProperty("basedir", System.getProperty("user.dir")),
                            "target", "sit-logs")
                    : Path.of(configured);
            Path path = root.resolve(agent).resolve("stdout.log");
            return new LogOffset(path, Files.exists(path) ? Files.size(path) : 0L);
        }

        String increment() throws IOException {
            if (!Files.exists(path)) return "";
            byte[] bytes = Files.readAllBytes(path);
            int from = Math.toIntExact(Math.min(offset, bytes.length));
            return new String(bytes, from, bytes.length - from, StandardCharsets.UTF_8);
        }
    }
}

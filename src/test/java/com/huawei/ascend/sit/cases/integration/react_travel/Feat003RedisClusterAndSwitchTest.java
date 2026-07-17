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
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** FEAT-003 Redis Cluster equivalence, switching and public SPI contract tests. */
@Feature("003")
@Tag("feat-003")
@Tag("integration")
class Feat003RedisClusterAndSwitchTest {
    private static final String MAINPLAN = "mainplan";
    private static final String TRIP = "trip";
    private static final String HOTEL = "hotel";
    private static final String PREFIX = "openjiuwen.service.middleware.";
    private static final long FLOW_TIMEOUT_MS = 120_000L;

    @Test
    @Tag("blackbox")
    @Tag("env-gated")
    @Story("Redis Cluster 业务等价")
    @DisplayName("Feat-003 cluster 与 standalone 的全链 Task/checkpoint 语义等价")
    void feat003ClusterMatchesStandaloneBusinessSemantics() throws Exception {
        TestConfig config = TestConfig.load();
        ClusterEnvironment cluster = ClusterEnvironment.required();
        JourneyEvidence standalone;
        try (StandaloneFixture redis = StandaloneFixture.start(config);
             SutStack stack = startStandalone(config, redis)) {
            standalone = journey(stack, "standalone-" + shortId());
        }
        LogOffset offset = LogOffset.capture(config, MAINPLAN);
        try (ClusterFixture fixture = ClusterFixture.external(config, cluster);
             SutStack stack = startCluster(config, fixture, Map.of())) {
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
    @Story("cluster nodes 优先级")
    @DisplayName("Feat-003 cluster 使用 nodes 而不是 standalone host/port")
    void feat003ClusterUsesNodesInsteadOfStandaloneHostPort() throws Exception {
        TestConfig config = TestConfig.load();
        ClusterEnvironment cluster = ClusterEnvironment.required();
        try (ClusterFixture fixture = ClusterFixture.external(config, cluster);
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
    @Story("cluster database 配置")
    @DisplayName("Feat-003 cluster 忽略 standalone database 并输出安全诊断")
    void feat003ClusterIgnoresStandaloneDatabaseSetting() throws Exception {
        TestConfig config = TestConfig.load();
        ClusterEnvironment cluster = ClusterEnvironment.required();
        LogOffset offset = LogOffset.capture(config, MAINPLAN);
        try (ClusterFixture fixture = ClusterFixture.external(config, cluster);
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
    @Story("standalone/cluster 配置切换")
    @DisplayName("Feat-003 standalone 到 cluster 再切回且业务代码不变")
    void feat003SwitchesStandaloneClusterAndBackWithoutBusinessChanges() throws Exception {
        TestConfig config = TestConfig.load();
        ClusterEnvironment cluster = ClusterEnvironment.required();
        TaskState first;
        TaskState middle;
        TaskState last;
        try (StandaloneFixture redis = StandaloneFixture.start(config);
             SutStack stack = startStandalone(config, redis)) {
            first = journey(stack, "switch-a-" + shortId()).state();
        }
        try (ClusterFixture fixture = ClusterFixture.external(config, cluster);
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
    @Story("cluster 单节点故障")
    @DisplayName("Feat-003 cluster 单节点故障后行为可观测且诊断脱敏")
    void feat003ClusterNodeFailureHasDefinedObservableBehavior() throws Exception {
        ClusterEnvironment cluster = ClusterEnvironment.required();
        assumeTrue(Boolean.getBoolean("feat003.cluster.managed-failure-enabled"),
                "P1 node-failure needs a site-managed fixture with an out-of-band stop/recovery hook");
        TestConfig config = TestConfig.load();
        try (ClusterFixture fixture = ClusterFixture.external(config, cluster);
             SutStack stack = startCluster(config, fixture, Map.of())) {
            assertThat(journey(stack, "pre-failure-" + shortId()).state())
                    .isEqualTo(TaskState.TASK_STATE_COMPLETED);
			triggerManagedNodeFailure();observeAfterNodeFailure(stack);
        }
    }

    @Nested
    @Tag("contract")
    class RuntimeRedisClusterContract {
        @Test
        @Story("cluster Redis SPI 文本与二进制")
        @DisplayName("Feat-003 cluster 文本和二进制重载与 standalone 等价")
        void feat003ClusterTextAndBinaryOperationsFollowContract() throws Exception {
            try (ClusterContract client = ClusterContract.open(ClusterEnvironment.required())) {
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
        @Story("cluster Redis SPI 跨 slot")
        @DisplayName("Feat-003 cluster mget 与批量 delete 支持跨 slot key")
        void feat003ClusterCrossSlotMgetAndDeleteFollowContract() throws Exception {
            try (ClusterContract client = ClusterContract.open(ClusterEnvironment.required())) {
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
        @Story("cluster Redis SPI scan")
        @DisplayName("Feat-003 cluster scanIter 汇总所有节点且不使用 KEYS")
        void feat003ClusterScanCoversAllNodes() throws Exception {
            try (ClusterContract client = ClusterContract.open(ClusterEnvironment.required())) {
                String prefix = "scan:" + shortId() + ':';
                List<String> keys = List.of(prefix + "{a}", prefix + "{b}", prefix + "{c}");
                for (String key : keys) client.call("set", types(String.class, String.class), key, key);
                assertThat((List<Object>) client.call("scanIter", types(String.class), prefix + "*"))
                        .containsAll(keys);
            }
        }
    }

    private static void 			triggerManagedNodeFailure() throws Exception {
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
                .send("从深圳去北京，明天出发，3天，住宿2晚。酒店预算不限，无星级、协议品牌、商圈和设施偏好，"
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
        static ClusterEnvironment required() {
            String configured = System.getProperty("feat003.cluster.nodes", "").trim();
            assumeTrue(!configured.isBlank(),
                    "provide -Dfeat003.cluster.nodes=host:port,host:port,... for cluster tests");
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

        private ClusterFixture(ClusterEnvironment environment, BackingServices backing) {
            this.environment = environment;
            this.backing = backing;
        }

        static ClusterFixture external(TestConfig config, ClusterEnvironment environment) {
            BackingServices backing = new BackingServices(config, Set.of("redis"),
                    new RedisClusterContainerFactory(environment));
            return new ClusterFixture(environment, backing);
        }

        ClusterEnvironment environment() { return environment; }
        BackingServices backing() { return backing; }
        @Override public void close() { backing.close(); }
    }

    /** Adapts a site-owned routable cluster to the common BackingServices lifecycle. */
    private static final class RedisClusterContainerFactory implements ContainerFactory {
        private final ClusterEnvironment environment;
        private RedisClusterContainerFactory(ClusterEnvironment environment) { this.environment = environment; }

        @Override
        public ManagedContainer start(String name, String image, int port, Map<String, String> env) {
            Endpoint seed = environment.nodes().get(0);
            return new ManagedContainer() {
                @Override public String host() { return seed.host(); }
                @Override public int mappedPort() { return seed.port(); }
                @Override public void close() { }
            };
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

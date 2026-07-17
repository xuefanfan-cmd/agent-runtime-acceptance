package com.huawei.ascend.sit.cases.integration.react_travel;

import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.client.TaskTextExtractor;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.BackingServices;
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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** FEAT-003 standalone Task/checkpoint behavior and public RuntimeRedisClient contract tests. */
@Feature("003")
@Tag("feat-003")
@Tag("integration")
class Feat003RedisStandaloneBehaviorTest {
    private static final String MAINPLAN = "mainplan";
    private static final String TRIP = "trip";
    private static final String HOTEL = "hotel";
    private static final String PREFIX = "openjiuwen.service.middleware.";
    private static final long FLOW_TIMEOUT_MS = 120_000L;

    @Test
    @Tag("blackbox")
    @Story("A2A Task TTL")
    @DisplayName("Feat-003 Task 可立即读取并按配置 TTL 过期")
    void feat003TaskIsReadableAndExpiresWithConfiguredTtl() throws Exception {
        TestConfig config = TestConfig.load();
        int ttlSeconds = Integer.getInteger("feat003.short-ttl-seconds", 12);
        try (RedisFixture redis = RedisFixture.start(config);
             SutStack stack = startFullChain(config, redis, Map.of(
                     PREFIX + "checkpointer.ttl-seconds", Integer.toString(ttlSeconds)))) {
            Set<String> before = redis.client(0).scan("*");
            InteractionFlow.FlowResult sent = oneTurn(stack, "我要出差，标志" + shortId(), "ttl");
            String taskId = sent.round(0).taskId();
            Task immediate = stack.client(MAINPLAN).getTask(taskId);
            assertThat(immediate).isNotNull();
            assertThat(immediate.id()).isEqualTo(taskId);

            Set<String> added = redis.client(0).scan("*");
            added.removeAll(before);
            assertThat(added).as("Task/checkpoint data written by this run").isNotEmpty();
            for (String key : added) {
                assertThat(redis.client(0).integer("TTL", key)).isBetween(1L, (long) ttlSeconds);
            }

            await().atMost(Duration.ofSeconds(ttlSeconds + 10L)).pollInterval(Duration.ofMillis(300))
                    .untilAsserted(() -> {
                        assertThat(redis.client(0).scan("*")).doesNotContainAnyElementsOf(added);
                        assertTaskUnavailable(stack, taskId);
                    });
        }
    }

    @Test
    @Tag("blackbox")
    @Story("命名 Redis 引用")
    @DisplayName("Feat-003 命名 ref 选择 DB2 并承载 TaskStore")
    void feat003NamedReferenceSelectsDatabaseForTaskStore() throws Exception {
        TestConfig config = TestConfig.load();
        try (RedisFixture redis = RedisFixture.start(config)) {
            Map<String, String> properties = new LinkedHashMap<>();
            properties.put(PREFIX + "checkpointer.redis-ref", "secondary");
            properties.put(PREFIX + "redis.default.type", "standalone");
            properties.put(PREFIX + "redis.default.host", redis.endpoint().host());
            properties.put(PREFIX + "redis.default.port", Integer.toString(redis.endpoint().port()));
            properties.put(PREFIX + "redis.default.database", "0");
            properties.put(PREFIX + "redis.secondary.type", "standalone");
            properties.put(PREFIX + "redis.secondary.host", redis.endpoint().host());
            properties.put(PREFIX + "redis.secondary.port", Integer.toString(redis.endpoint().port()));
            properties.put(PREFIX + "redis.secondary.database", "2");
            try (SutStack stack = startFullChain(config, redis, properties)) {
                long db0Before = redis.client(0).integer("DBSIZE");
                long db2Before = redis.client(2).integer("DBSIZE");
                InteractionFlow.FlowResult sent = oneTurn(
                        stack, "我要出差，请记住校验码 named-" + shortId(), "named-ref");
                String taskId = sent.lastTaskId();
                assertThat(stack.client(MAINPLAN).getTask(taskId)).extracting(Task::id).isEqualTo(taskId);
                assertThat(redis.client(0).integer("DBSIZE")).isEqualTo(db0Before);
                assertThat(redis.client(2).integer("DBSIZE")).isGreaterThan(db2Before);
                assertThat(agentLog(stack, MAINPLAN)).contains("redis-ref=secondary");
                assertThat(agentLog(stack, TRIP)).contains("redis-ref=secondary");
                assertThat(agentLog(stack, HOTEL)).contains("redis-ref=secondary");
            }
        }
    }

    @Test
    @Tag("blackbox")
    @Story("Task 与 checkpoint 跨 JVM 恢复")
    @DisplayName("Feat-003 Agent 重启后 Task 和上下文仍可恢复")
    void feat003TaskAndCheckpointSurviveAgentRestart() throws Exception {
        TestConfig config = TestConfig.load();
        try (RedisFixture redis = RedisFixture.start(config);
             SutStack stack = startFullChain(config, redis, Map.of())) {
            String token = "restart-" + shortId();
            InteractionFlow.FlowResult first = oneTurn(stack, "我要出差，请记住校验码：" + token, "restart");
            String taskId = first.round(0).taskId();
            String contextId = first.round(0).contextId();
            int port = stack.port(MAINPLAN);
            long oldPid = managed(stack, MAINPLAN).pid();

            stack.stop(MAINPLAN);
            await().atMost(Duration.ofSeconds(15)).until(() -> !stack.isRunning(MAINPLAN));
            stack.start(MAINPLAN);

            assertThat(stack.port(MAINPLAN)).isEqualTo(port);
            assertThat(managed(stack, MAINPLAN).pid()).isNotEqualTo(oldPid);
            assertThat(stack.client(MAINPLAN).getTask(taskId).id()).isEqualTo(taskId);
            InteractionFlow.FlowResult recall = InteractionFlow.of(stack.client(MAINPLAN))
                    .withTimeoutMs(FLOW_TIMEOUT_MS).withContextId(contextId)
                    .send("请返回我刚才让你记住的校验码")
                    .mayReachState(TaskState.TASK_STATE_COMPLETED).execute();
            assertThat(TaskTextExtractor.textOf(stack.client(MAINPLAN).getTask(recall.lastTaskId())))
                    .contains(token);
        }
    }

    @Test
    @Tag("blackbox")
    @Story("reset conversation")
    @DisplayName("Feat-003 reset conversation 删除 Task 与 checkpoint 状态")
    void feat003ResetConversationRemovesTaskAndCheckpointState() throws Exception {
        TestConfig config = TestConfig.load();
        try (RedisFixture redis = RedisFixture.start(config);
             SutStack stack = startFullChain(config, redis, Map.of())) {
            String marker = "reset标志" + shortId();
            String contextId = "ctx-feat003-reset-" + shortId();
            InteractionFlow.FlowResult before = InteractionFlow.of(stack.client(MAINPLAN))
                    .withTimeoutMs(FLOW_TIMEOUT_MS).withContextId(contextId)
                    .send("我要出差，请记住" + marker)
                    .mayReachState(TaskState.TASK_STATE_INPUT_REQUIRED).execute();
            String oldTaskId = before.lastTaskId();

            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(stack.baseUrl(MAINPLAN) + "/v1/reset_conversation"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"conversation_id\":\"" + contextId + "\"}"))
                            .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isBetween(200, 299);

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(catchThrowable(() -> stack.client(MAINPLAN).getTask(oldTaskId))).isNotNull());
            InteractionFlow.FlowResult after = InteractionFlow.of(stack.client(MAINPLAN))
                    .withTimeoutMs(FLOW_TIMEOUT_MS).withContextId(contextId)
                    .send("我之前让你记住了什么标志？")
                    .mayReachState(TaskState.TASK_STATE_COMPLETED).execute();
            assertThat(TaskTextExtractor.textOf(stack.client(MAINPLAN).getTask(after.lastTaskId())))
                    .doesNotContain(marker);
        }
    }

    @Test
    @Tag("blackbox")
    @Story("上下文隔离")
    @DisplayName("Feat-003 两个 context 的 Task 与 checkpoint 不串扰")
    void feat003ContextsRemainIsolatedAcrossSequentialJourneys() throws Exception {
        TestConfig config = TestConfig.load();
        try (RedisFixture redis = RedisFixture.start(config);
             SutStack stack = startFullChain(config, redis, Map.of())) {
            String tokenA = "isolation-a-" + shortId();
            String tokenB = "isolation-b-" + shortId();
            String textA = isolatedJourney(stack, "ctx-a-" + shortId(), "北京", tokenA);
            String textB = isolatedJourney(stack, "ctx-b-" + shortId(), "上海", tokenB);
            assertThat(textA).contains(tokenA).doesNotContain(tokenB);
            assertThat(textB).contains(tokenB).doesNotContain(tokenA);
        }
    }

    @Test
    @Tag("blackbox")
    @Story("Redis 生命周期")
    @DisplayName("Feat-003 重复重启释放 Redis 连接且端口保持不变")
    void feat003RepeatedRestartReleasesRedisConnections() throws Exception {
        TestConfig config = TestConfig.load();
        try (RedisFixture redis = RedisFixture.start(config);
             SutStack stack = startFullChain(config, redis, Map.of())) {
            int port = stack.port(MAINPLAN);
            int baseline = redis.client(0).clientCount();
            Set<Long> pids = new LinkedHashSet<>();
            for (int cycle = 0; cycle < 3; cycle++) {
                pids.add(managed(stack, MAINPLAN).pid());
                oneTurn(stack, "生命周期操作" + cycle + '-' + shortId(), "life-" + cycle);
                stack.stop(MAINPLAN);
                await().atMost(Duration.ofSeconds(15)).until(() -> !stack.isRunning(MAINPLAN));
                int allowed = baseline + 6;
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                        assertThat(redis.client(0).clientCount()).isLessThanOrEqualTo(allowed));
                stack.start(MAINPLAN);
                assertThat(stack.port(MAINPLAN)).isEqualTo(port);
            }
            pids.add(managed(stack, MAINPLAN).pid());
            assertThat(pids).hasSize(4);
        }
    }

    @Nested
    @Tag("contract")
    class RuntimeRedisContract {
        @Test
        @Story("standalone Redis SPI 文本与二进制")
        @DisplayName("Feat-003 standalone 文本与二进制 get/set/setex 合同")
        void feat003StandaloneTextAndBinaryOperationsFollowContract() throws Exception {
            try (StandaloneContract client = StandaloneContract.open()) {
                String id = shortId();
                assertThat(client.call("set", types(String.class, String.class), "text:" + id, "v")).isEqualTo("OK");
                assertRedisTextValue(client.call("get", types(String.class), "text:" + id), "v");
                byte[] binaryKey = bytes("binary:" + id);
                byte[] binaryValue = new byte[]{0, 1, 2, -1};
                assertThat(client.call("set", types(byte[].class, byte[].class), binaryKey, binaryValue)).isEqualTo("OK");
                assertThat((byte[]) client.call("get", types(byte[].class), binaryKey)).containsExactly(binaryValue);
                String mixedKey = "mixed:" + id;
                assertThat(client.call("set", types(String.class, byte[].class), mixedKey, binaryValue))
                        .isEqualTo("OK");
                assertThat((byte[]) client.call("get", types(String.class), mixedKey)).containsExactly(binaryValue);
                assertThat(client.call("setex", types(String.class, long.class, String.class),
                        "ttl-text:" + id, 30L, "v")).isEqualTo("OK");
                assertThat(client.call("setex", types(byte[].class, long.class, byte[].class),
                        bytes("ttl-bin:" + id), 30L, binaryValue)).isEqualTo("OK");
            }
        }

        @Test
        @Story("standalone Redis SPI TTL/exists/delete")
        @DisplayName("Feat-003 standalone TTL、exists 与 delete 合同")
        void feat003StandaloneTtlExistsAndDeleteFollowContract() throws Exception {
            try (StandaloneContract client = StandaloneContract.open()) {
                String text = "life:" + shortId();
                byte[] binary = bytes("life-bin:" + shortId());
                client.call("set", types(String.class, String.class), text, "v");
                client.call("set", types(byte[].class, byte[].class), binary, bytes("v"));
                assertThat(client.call("exists", types(String.class), text)).isEqualTo(true);
                assertThat(client.call("exists", types(byte[].class), binary)).isEqualTo(true);
                assertThat(client.call("expire", types(String.class, long.class), text, 30L)).isEqualTo(1L);
                assertThat(client.call("expire", types(byte[].class, long.class), binary, 30L)).isEqualTo(1L);
                assertThat(client.call("del", types(String[].class), (Object) new String[]{text})).isEqualTo(1L);
                assertThat(client.call("del", types(byte[][].class), (Object) new byte[][]{binary})).isEqualTo(1L);
            }
        }

        @Test
        @Story("standalone Redis SPI setnx")
        @DisplayName("Feat-003 standalone 文本与二进制 setnx 合同")
        void feat003StandaloneSetnxFollowsContract() throws Exception {
            try (StandaloneContract client = StandaloneContract.open()) {
                String text = "nx:" + shortId();
                byte[] binary = bytes("nx-bin:" + shortId());
                assertThat(client.call("setnx", types(String.class, String.class), text, "first")).isEqualTo(1L);
                assertThat(client.call("setnx", types(String.class, String.class), text, "second")).isEqualTo(0L);
                assertThat(client.call("setnx", types(byte[].class, byte[].class), binary, bytes("first")))
                        .isEqualTo(1L);
                assertThat(client.call("setnx", types(byte[].class, byte[].class), binary, bytes("second")))
                        .isEqualTo(0L);
            }
        }

        @Test
        @Story("standalone Redis SPI mget")
        @DisplayName("Feat-003 standalone mget 保序并保留缺失值位置")
        void feat003StandaloneMgetPreservesOrderAndMissingValues() throws Exception {
            try (StandaloneContract client = StandaloneContract.open()) {
                String prefix = "mget:" + shortId() + ':';
                client.call("set", types(String.class, String.class), prefix + "a", "A");
                client.call("set", types(String.class, String.class), prefix + "b", "B");
                List<Object> values = (List<Object>) client.call("mget", types(String[].class),
                        (Object) new String[]{prefix + "b", prefix + "missing", prefix + "a"});
                assertThat(values).hasSize(3);
                assertRedisTextValue(values.get(0), "B");
                assertThat(values.get(1)).isNull();
                assertRedisTextValue(values.get(2), "A");
            }
        }

        @Test
        @Story("standalone Redis SPI scan")
        @DisplayName("Feat-003 standalone scanIter 按模式返回文本 key")
        void feat003StandaloneScanIterReturnsMatchingTextKeys() throws Exception {
            try (StandaloneContract client = StandaloneContract.open()) {
                String prefix = "scan:" + shortId() + ':';
                client.call("set", types(String.class, String.class), prefix + "a", "A");
                client.call("set", types(String.class, String.class), prefix + "b", "B");
                List<String> keys = (List<String>) client.call("scanIter", types(String.class), prefix + "*");
                assertThat(keys).contains(prefix + "a", prefix + "b")
                        .allMatch(key -> key.startsWith(prefix));
            }
        }

        @Test
        @Story("standalone Redis SPI 并发")
        @DisplayName("Feat-003 standalone 单例 client 并发访问独立 key 不串扰")
        void feat003StandaloneClientIsThreadSafeForIndependentKeys() throws Exception {
            try (StandaloneContract client = StandaloneContract.open();
                 ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                String runId = shortId();
                List<Future<Void>> futures = new ArrayList<>();
                for (int index = 0; index < 32; index++) {
                    int current = index;
                    futures.add(executor.submit(() -> {
                        String key = "concurrent:" + runId + ':' + current;
                        String value = "value-" + current;
                        assertThat(client.call("setex", types(String.class, long.class, String.class),
                                key, 30L, value)).isEqualTo("OK");
                        assertRedisTextValue(client.call("get", types(String.class), key), value);
                        return null;
                    }));
                }
                for (Future<Void> future : futures) {
                    future.get();
                }
            }
        }
    }

    private static String isolatedJourney(SutStack stack, String contextId, String destination, String token) {
        InteractionFlow.FlowResult result = InteractionFlow.of(stack.client(MAINPLAN))
                .withTimeoutMs(FLOW_TIMEOUT_MS).withContextId(contextId)
                .send("我要去" + destination + "出差，明天出发，3天，请记住校验码：" + token)
                .mayReachState(TaskState.TASK_STATE_COMPLETED)
                .send("请只复述我之前提供的校验码")
                .mayReachState(TaskState.TASK_STATE_COMPLETED).execute();
        return TaskTextExtractor.textOf(stack.client(MAINPLAN).getTask(result.lastTaskId()));
    }

    private static InteractionFlow.FlowResult oneTurn(SutStack stack, String text, String label) {
        return InteractionFlow.of(stack.client(MAINPLAN)).withTimeoutMs(FLOW_TIMEOUT_MS)
                .withContextId("ctx-feat003-" + label + '-' + shortId())
                .send(text).mayReachState(TaskState.TASK_STATE_INPUT_REQUIRED).execute();
    }

    private static SutStack startFullChain(TestConfig config, RedisFixture redis,
                                           Map<String, String> overrides) {
        Map<String, String> properties = baseRedisProperties(redis.endpoint());
        properties.putAll(overrides);
        return SutStack.builder(config).streaming(true).backingServices(redis.backing())
                .agent(HOTEL, a -> apply(a.profile("redis"), properties))
                .agent(TRIP, a -> apply(a.profile("redis").downstream(HOTEL), properties))
                .agent(MAINPLAN, a -> apply(a.profile("redis").downstream(TRIP), properties))
                .start();
    }

    private static void assertTaskUnavailable(SutStack stack, String taskId) {
        Task[] returned = new Task[1];
        Throwable failure = catchThrowable(() -> returned[0] = stack.client(MAINPLAN).getTask(taskId));
        if (failure == null) {
            assertThat(returned[0]).as("expired Task must not remain readable").isNull();
            return;
        }
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String diagnostic = (root.getClass().getName() + ' ' + String.valueOf(root.getMessage()))
                .toLowerCase(Locale.ROOT);
        assertThat(diagnostic).as("expired Task failure must have not-found semantics")
                .matches(".*(not found|tasknotfound).*");
    }

    private static void assertRedisTextValue(Object actual, String expected) {
        assertThat(actual).as("Redis value").isNotNull();
        if (actual instanceof byte[] binary) {
            assertThat(binary).containsExactly(bytes(expected));
            return;
        }
        assertThat(actual).isInstanceOf(String.class).isEqualTo(expected);
    }

    private static Map<String, String> baseRedisProperties(Endpoint endpoint) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put(PREFIX + "checkpointer.type", "redis");
        properties.put(PREFIX + "checkpointer.redis-ref", "default");
        properties.put(PREFIX + "redis.default.type", "standalone");
        properties.put(PREFIX + "redis.default.host", endpoint.host());
        properties.put(PREFIX + "redis.default.port", Integer.toString(endpoint.port()));
        properties.put(PREFIX + "redis.default.database", "0");
        properties.put(PREFIX + "redis.default.encrypted-password", "");
        return properties;
    }

    private static void apply(SutStack.AgentBuilder builder, Map<String, String> properties) {
        properties.forEach(builder::property);
    }

    private static ManagedSutInstance managed(SutStack stack, String agent) {
        assertThat(stack.managedInstance(agent)).isInstanceOf(ManagedSutInstance.class);
        return (ManagedSutInstance) stack.managedInstance(agent);
    }

    private static String agentLog(SutStack stack, String agent) throws IOException {
        return Files.readString(managed(stack, agent).logFile(), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static Class<?>[] types(Class<?>... types) {
        return types;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record Endpoint(String host, int port) {
        static Endpoint parse(String value) {
            int colon = value.lastIndexOf(':');
            return new Endpoint(value.substring(0, colon), Integer.parseInt(value.substring(colon + 1)));
        }
    }

    private static final class RedisFixture implements AutoCloseable {
        private final BackingServices backing;
        private final Endpoint endpoint;

        private RedisFixture(BackingServices backing, Endpoint endpoint) {
            this.backing = backing;
            this.endpoint = endpoint;
        }

        static RedisFixture start(TestConfig config) {
            BackingServices backing = new BackingServices(config, Set.of("redis"), new TestContainerFactory(null));
            return new RedisFixture(backing, Endpoint.parse(backing.url("redis")));
        }

        BackingServices backing() {
            return backing;
        }

        Endpoint endpoint() {
            return endpoint;
        }

        RespClient client(int database) throws IOException {
            return new RespClient(endpoint, database);
        }

        @Override
        public void close() {
            backing.close();
        }
    }

    /** Reflection keeps the three-file-only rule while executing the real test-scope runtime artifact. */
    private static final class StandaloneContract implements AutoCloseable {
        private final RedisFixture redis;
        private final Object runtimeClient;

        private StandaloneContract(RedisFixture redis, Object runtimeClient) {
            this.redis = redis;
            this.runtimeClient = runtimeClient;
        }

        static StandaloneContract open() throws Exception {
            Class<?> jedisClass = contractClass("redis.clients.jedis.JedisPooled");
            Class<?> implementation = contractClass(
                    "com.openjiuwen.service.adapters.common.middleware.redis.JedisPooledRuntimeRedisClient");
            RedisFixture redis = RedisFixture.start(TestConfig.load());
            try {
                Constructor<?> jedisConstructor = jedisClass.getConstructor(String.class, int.class);
                Object jedis = jedisConstructor.newInstance(redis.endpoint().host(), redis.endpoint().port());
                Object runtime = implementation.getConstructor(jedisClass).newInstance(jedis);
                return new StandaloneContract(redis, runtime);
            } catch (Throwable failure) {
                redis.close();
                throw failure;
            }
        }

        Object call(String method, Class<?>[] parameterTypes, Object... args) {
            try {
                Method target = runtimeClient.getClass().getMethod(method, parameterTypes);
                return target.invoke(runtimeClient, args);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("RuntimeRedisClient." + method + " contract invocation failed", e);
            }
        }

        @Override
        public void close() throws Exception {
            try {
                runtimeClient.getClass().getMethod("close").invoke(runtimeClient);
            } finally {
                redis.close();
            }
        }

        private static Class<?> contractClass(String name) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException e) {
                assumeTrue(false, "contract runtime artifact is not on the test classpath: " + name);
                throw new AssertionError(e);
            }
        }
    }

    private static final class RespClient implements AutoCloseable {
        private final Socket socket = new Socket();
        private final BufferedInputStream input;
        private final BufferedOutputStream output;

        private RespClient(Endpoint endpoint, int database) throws IOException {
            socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), 5_000);
            input = new BufferedInputStream(socket.getInputStream());
            output = new BufferedOutputStream(socket.getOutputStream());
            if (database != 0) {
                command("SELECT", Integer.toString(database));
            }
        }

        long integer(String... command) throws IOException {
            Object value = command(command);
            return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
        }

        int clientCount() throws IOException {
            String clients = String.valueOf(command("CLIENT", "LIST"));
            return clients.isBlank() ? 0 : clients.split("\\r?\\n").length;
        }

        Set<String> scan(String pattern) throws IOException {
            Set<String> keys = new LinkedHashSet<>();
            String cursor = "0";
            do {
                List<Object> reply = (List<Object>) command("SCAN", cursor, "MATCH", pattern, "COUNT", "200");
                cursor = String.valueOf(reply.get(0));
                for (Object key : (List<Object>) reply.get(1)) {
                    keys.add(String.valueOf(key));
                }
            } while (!"0".equals(cursor));
            return keys;
        }

        Object command(String... args) throws IOException {
            output.write(('*' + Integer.toString(args.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
            for (String arg : args) {
                byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
                output.write(('$' + Integer.toString(bytes.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
                output.write(bytes);
                output.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            output.flush();
            return readReply();
        }

        private Object readReply() throws IOException {
            int marker = input.read();
            if (marker < 0) throw new EOFException("Redis closed connection");
            String line = readLine();
            return switch (marker) {
                case '+' -> line;
                case '-' -> throw new IOException("Redis command failed: " + line);
                case ':' -> Long.parseLong(line);
                case '$' -> readBulk(Integer.parseInt(line));
                case '*' -> readArray(Integer.parseInt(line));
                default -> throw new IOException("Unknown RESP marker " + (char) marker);
            };
        }

        private Object readBulk(int length) throws IOException {
            if (length < 0) return null;
            byte[] value = input.readNBytes(length);
            if (value.length != length) throw new EOFException("Truncated Redis bulk reply");
            readLine();
            return new String(value, StandardCharsets.UTF_8);
        }

        private List<Object> readArray(int length) throws IOException {
            List<Object> values = new ArrayList<>(Math.max(length, 0));
            for (int i = 0; i < length; i++) values.add(readReply());
            return values;
        }

        private String readLine() throws IOException {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            int previous = -1;
            for (int current; (current = input.read()) >= 0; previous = current) {
                if (previous == '\r' && current == '\n') {
                    byte[] value = bytes.toByteArray();
                    return new String(value, 0, value.length - 1, StandardCharsets.UTF_8);
                }
                bytes.write(current);
            }
            throw new EOFException("Truncated Redis line");
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}

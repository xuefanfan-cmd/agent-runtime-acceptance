package com.huawei.ascend.sit.cases.integration.react_travel;

import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.BackingServices;
import com.huawei.ascend.sit.lifecycle.ContainerFactory;
import com.huawei.ascend.sit.lifecycle.ManagedContainer;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.lifecycle.TestContainerFactory;
import com.openjiuwen.service.adapters.agentcore.middleware.AgentCoreCheckpointerConfigAssembler;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import com.openjiuwen.service.adapters.common.middleware.redis.RedisMiddlewareAutoConfiguration;
import com.openjiuwen.service.app.controller.a2a.RedisTaskStore;
import com.openjiuwen.service.spec.spi.RuntimeRedisClient;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** FEAT-003 configuration, diagnostics, authentication and adapter-extension acceptance tests. */
@Feature("003")
@Tag("feat-003")
@Tag("integration")
class Feat003RedisConfigurationAndDiagnosticsTest {
    private static final String MAINPLAN = "mainplan";
    private static final String TRIP = "trip";
    private static final String HOTEL = "hotel";
    private static final String PREFIX = "openjiuwen.service.middleware.";
    private static final long FLOW_TIMEOUT_MS = 120_000L;

    @Test
    @Tag("blackbox")
    @Story("standalone 默认配置")
    @DisplayName("Feat-003 standalone 默认 TTL 与策略日志正确")
    void feat003StandaloneUsesDefaultTtlAndEmitsDiagnostics() throws Exception {
        TestConfig config = TestConfig.load();
        LogOffsets offsets = LogOffsets.capture(config);
        try (RedisFixture redis = RedisFixture.start(config);
             SutStack stack = startFullChain(config, redis, Map.of())) {
            Set<String> before = redis.client().scan("*");
            executeTwoTurnJourney(stack, "feat003-default-" + shortId());
            Set<String> added = redis.client().scan("*");
            added.removeAll(before);

            assertThat(added).as("new Task/checkpoint Redis data").isNotEmpty();
            for (String key : added) {
                long ttl = redis.client().integer("TTL", key);
                assertThat(ttl).as("default TTL for a newly written Redis key").isBetween(1L, 604_800L);
            }
            assertThat(offsets.increment())
                    .contains("Runtime Redis datasource selected", "redis-ref=default",
                            "endpoint-type=standalone", "JedisPooledRuntimeRedisClient",
                            "ttl-seconds=604800", "host=", "port=", "database=",
                            "timeoutMs=", "passwordConfigured=false");
        }
    }

    @Test
    @Tag("blackbox")
    @Story("旧配置兼容")
    @DisplayName("Feat-003 旧配置缺省 type 与 redis-ref 时回落 standalone/default")
    void feat003LegacyConfigurationDefaultsToStandalone() throws Exception {
        TestConfig config = TestConfig.load();
        LogOffsets offsets = LogOffsets.capture(config);
        try (RedisFixture redis = RedisFixture.start(config);
             SutStack stack = startFullChainRaw(config, redis, Map.of(
                     PREFIX + "checkpointer.type", "redis",
                     PREFIX + "redis.default.host", redis.endpoint().host(),
                     PREFIX + "redis.default.port", Integer.toString(redis.endpoint().port()),
                     PREFIX + "redis.default.database", "0"))) {
            executeTwoTurnJourney(stack, "feat003-legacy-" + shortId());
            assertThat(offsets.increment()).contains("redis-ref=default", "endpoint-type=standalone");
        }
    }

    @Test
    @Tag("blackbox")
    @Story("in_memory 配置")
    @DisplayName("Feat-003 in_memory 不选择 Redis datasource")
    void feat003InMemoryDoesNotSelectRedisDatasource() throws Exception {
        TestConfig config = TestConfig.load();
        LogOffsets offsets = LogOffsets.capture(config);
        try (SutStack stack = startFullChain(config, null,
                Map.of(PREFIX + "checkpointer.type", "in_memory"))) {
            executeTwoTurnJourney(stack, "feat003-memory-" + shortId());
            assertThat(offsets.increment()).doesNotContain("Runtime Redis datasource selected");
        }
    }

    @Test
    @Tag("blackbox")
    @Story("错误配置诊断")
    @DisplayName("Feat-003 cluster 缺少 nodes 时启动失败并给出诊断")
    void feat003ClusterWithoutNodesFails() {
        assertInvalid(Map.of(
                PREFIX + "checkpointer.type", "redis",
                PREFIX + "redis.default.type", "cluster"), "nodes", "required");
    }

    @Test
    @Tag("blackbox")
    @Story("错误配置诊断")
    @DisplayName("Feat-003 非法 endpoint type 明确列出支持范围")
    void feat003UnsupportedEndpointTypeFails() {
        assertInvalid(Map.of(
                PREFIX + "checkpointer.type", "redis",
                PREFIX + "redis.default.type", "invalid_type"),
                "invalid_type", "standalone", "cluster");
    }

    @Test
    @Tag("blackbox")
    @Story("错误配置诊断")
    @DisplayName("Feat-003 缺失 Redis 引用时启动失败")
    void feat003MissingRedisReferenceFails() {
        assertInvalid(Map.of(
                PREFIX + "checkpointer.type", "redis",
                PREFIX + "checkpointer.redis-ref", "missing"), "redis.missing", "required");
    }

    @Test
    @Tag("blackbox")
    @Story("错误配置诊断")
    @DisplayName("Feat-003 TTL 为零时启动失败")
    void feat003ZeroTtlFails() {
        assertInvalid(Map.of(
                PREFIX + "checkpointer.type", "redis",
                PREFIX + "checkpointer.ttl-seconds", "0"), "greater than 0");
    }

    @Test
    @Tag("blackbox")
    @Story("错误配置诊断")
    @DisplayName("Feat-003 TTL 为负数时启动失败")
    void feat003NegativeTtlFails() {
        assertInvalid(Map.of(
                PREFIX + "checkpointer.type", "redis",
                PREFIX + "checkpointer.ttl-seconds", "-1"), "greater than 0");
    }

    @Test
    @Tag("blackbox")
    @Story("错误配置诊断")
    @DisplayName("Feat-003 非法 checkpointer type 明确列出支持范围")
    void feat003UnsupportedCheckpointerTypeFails() {
        assertInvalid(Map.of(PREFIX + "checkpointer.type", "invalid"), "invalid", "in_memory", "redis");
    }

    @Test
    @Tag("blackbox")
    @Story("错误配置诊断")
    @DisplayName("Feat-003 非法 cluster node 明确提示 host:port 格式")
    void feat003MalformedClusterNodeFails() {
        assertInvalid(Map.of(
                PREFIX + "checkpointer.type", "redis",
                PREFIX + "redis.default.type", "cluster",
                PREFIX + "redis.default.nodes[0]", "not-host-port"), "host:port");
    }

    @Test
    @Tag("blackbox")
    @Story("错误配置诊断")
    @DisplayName("Feat-003 standalone 缺失 host 时按 L2 规格启动失败")
    void feat003StandaloneWithoutHostFails() {
        assertInvalid(Map.of(
                PREFIX + "checkpointer.type", "redis",
                PREFIX + "redis.default.type", "standalone",
                PREFIX + "redis.default.host", ""), "host", "required");
    }

    @Test
    @Tag("blackbox")
    @Tag("env-gated")
    @Story("Redis 认证与日志脱敏")
    @DisplayName("Feat-003 认证 Redis 成功且日志不泄漏密码")
    void feat003AuthenticatedRedisSucceedsWithoutLeakingPassword() throws Exception {
        requireSecurityEnvironment();
        TestConfig config = TestConfig.load();
        String canary = "feat003-auth-" + UUID.randomUUID();
        LogOffsets offsets = LogOffsets.capture(config);
        try (RedisFixture redis = RedisFixture.startAuthenticated(config, canary);
             SutStack stack = startFullChain(config, redis, Map.of(
                     PREFIX + "redis.default.encrypted-password", canary))) {
            Set<String> before = redis.client().scan("*");
            executeAuthenticatedRedisTouch(stack, "feat003-auth-journey-" + shortId());
            Set<String> added = redis.client().scan("*");
            added.removeAll(before);

            assertThat(added).as("authenticated Redis Task/checkpoint data").isNotEmpty();
            for (String key : added) {
                long ttl = redis.client().integer("TTL", key);
                assertThat(ttl).as("TTL for authenticated Redis key").isBetween(1L, 604_800L);
            }
            String increment = offsets.increment();
            assertThat(increment).contains("passwordConfigured=true");
            assertThat(increment).as("no credential canary in incremental logs").doesNotContain(canary);
        }
    }

    @Test
    @Tag("blackbox")
    @Tag("env-gated")
    @Story("Redis 故障不降级")
    @DisplayName("Feat-003 Redis 密码错误时真实操作失败且不降级内存")
    void feat003WrongPasswordFailsWithoutInMemoryFallback() throws Exception {
        requireSecurityEnvironment();
        TestConfig config = TestConfig.load();
        String canary = "feat003-wrong-" + UUID.randomUUID();
        LogOffsets offsets = LogOffsets.capture(config);
        Throwable a2aError;
        try (RedisFixture redis = RedisFixture.startAuthenticated(config, "server-" + shortId());
             SutStack stack = startFullChain(config, redis, Map.of(
                     PREFIX + "redis.default.encrypted-password", canary))) {
            a2aError = executeRedisOperationAndCaptureError(stack, "wrong-password-" + shortId());
        }

        assertNoConditionTimeout(a2aError);
        assertThat(offsets.increment())
                .contains("WRONGPASS", "TaskStore persistence failed",
                        "RuntimeRedisClient=JedisPooledRuntimeRedisClient")
                .doesNotContain(canary);
    }

    @Test
    @Tag("blackbox")
    @Tag("env-gated")
    @Story("Redis 故障不降级")
    @DisplayName("Feat-003 Redis 不可达时真实操作失败且不降级内存")
    void feat003UnreachableRedisFailsWithoutInMemoryFallback() throws Exception {
        requireSecurityEnvironment();
        TestConfig config = TestConfig.load();
        Endpoint unreachable = new Endpoint("127.0.0.1", 1);
        LogOffsets offsets = LogOffsets.capture(config);
        Throwable a2aError;
        try (SutStack stack = startFullChain(config, null, redisProperties(unreachable, Map.of()))) {
            a2aError = executeRedisOperationAndCaptureError(stack, "unreachable-redis-" + shortId());
        }

        assertNoConditionTimeout(a2aError);
        assertThat(offsets.increment())
                .contains("Failed to connect to 127.0.0.1:1", "TaskStore persistence failed",
                        "RuntimeRedisClient=JedisPooledRuntimeRedisClient");
    }

    @Nested
    @Tag("contract")
    class CustomAdapterContract {
        @Test
        @Story("自定义 Redis Adapter 装配")
        @DisplayName("Feat-003 自定义 Adapter 使默认 Bean back-off 并服务内部消费者")
        void feat003CustomAdapterBacksOffDefaultAndServesConsumers() {
            List<String> calls = new CopyOnWriteArrayList<>();
            RuntimeRedisClient fake = (RuntimeRedisClient) Proxy.newProxyInstance(
                    RuntimeRedisClient.class.getClassLoader(), new Class<?>[]{RuntimeRedisClient.class},
                    (proxy, method, args) -> fakeRedisInvocation(proxy, method, args, calls));

            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(RedisMiddlewareAutoConfiguration.class))
                    .withBean("feat003CustomRedisClient", RuntimeRedisClient.class, () -> fake)
                    .withPropertyValues(
                            PREFIX + "checkpointer.type=redis",
                            PREFIX + "redis.default.type=standalone",
                            PREFIX + "redis.default.host=127.0.0.1",
                            PREFIX + "redis.default.port=6379")
                    .run(context -> {
                        Map<String, RuntimeRedisClient> beans = context.getBeansOfType(RuntimeRedisClient.class);
                        assertThat(beans).hasSize(1).containsEntry("feat003CustomRedisClient", fake);

                        RuntimeRedisClient consumerView = context.getBean(RuntimeRedisClient.class);
                        RedisTaskStore taskStore = new RedisTaskStore(consumerView, 604_800L);
                        Task task = Task.builder().id("feat003-adapter-task").contextId("feat003-adapter-context")
                                .status(new TaskStatus(TaskState.TASK_STATE_WORKING)).build();
                        taskStore.save(task, false);
                        taskStore.get(task.id());

                        MiddlewareProperties properties = context.getBean(MiddlewareProperties.class);
                        Map<String, Object> checkpointer = AgentCoreCheckpointerConfigAssembler.build(
                                properties, ciphertext -> ciphertext, consumerView);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> checkpointerConf =
                                (Map<String, Object>) checkpointer.get("conf");
                        @SuppressWarnings("unchecked")
                        Map<String, Object> connection =
                                (Map<String, Object>) checkpointerConf.get("connection");

                        assertThat(connection.get("redis_client")).isSameAs(fake);
                        assertThat(calls).contains("setex", "get");
                    });
        }
    }

    private static Object fakeRedisInvocation(Object proxy, Method method, Object[] args, List<String> calls) {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "toString" -> "Feat003CustomRuntimeRedisClient";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            };
        }
        calls.add(method.getName());
        return switch (method.getReturnType().getName()) {
            case "boolean" -> false;
            case "long" -> 0L;
            case "java.lang.String" -> "OK";
            case "java.util.List" -> List.of();
            default -> null;
        };
    }

    private static void assertInvalid(Map<String, String> properties, String... stableFragments) {
        TestConfig config = TestConfig.load();
        Throwable failure = catchThrowable(() -> {
            SutStack stack = null;
            try {
                stack = SutStack.builder(config)
                        .agent(HOTEL, a -> apply(a.profile("redis"), properties))
                        .start();
                throw new AssertionError("invalid Redis configuration unexpectedly started");
            } finally {
                if (stack != null) {
                    stack.close();
                }
            }
        });
        assertThat(failure).isNotNull().isNotInstanceOf(AssertionError.class);
        String diagnostic = throwableText(failure).toLowerCase();
        for (String fragment : stableFragments) {
            assertThat(diagnostic).contains(fragment.toLowerCase());
        }
    }

    private static SutStack startFullChainRaw(TestConfig config, RedisFixture redis,
                                              Map<String, String> properties) {
        return SutStack.builder(config).streaming(true).backingServices(redis.backing())
                .agent(HOTEL, a -> apply(a.profile("redis"), properties))
                .agent(TRIP, a -> apply(a.profile("redis").downstream(HOTEL), properties))
                .agent(MAINPLAN, a -> apply(a.profile("redis").downstream(TRIP), properties))
                .start();
    }

    private static SutStack startFullChain(TestConfig config, RedisFixture redis,
                                           Map<String, String> overrides) {
        Map<String, String> properties = redis == null
                ? new LinkedHashMap<>(overrides)
                : redisProperties(redis.endpoint(), overrides);
        SutStack.Builder builder = SutStack.builder(config).streaming(true);
        if (redis != null) {
            builder.backingServices(redis.backing());
        }
        return builder
                .agent(HOTEL, a -> apply(a.profile("redis"), properties))
                .agent(TRIP, a -> apply(a.profile("redis").downstream(HOTEL), properties))
                .agent(MAINPLAN, a -> apply(a.profile("redis").downstream(TRIP), properties))
                .start();
    }

    private static Map<String, String> redisProperties(Endpoint endpoint, Map<String, String> overrides) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put(PREFIX + "checkpointer.type", "redis");
        result.put(PREFIX + "checkpointer.redis-ref", "default");
        result.put(PREFIX + "redis.default.type", "standalone");
        result.put(PREFIX + "redis.default.host", endpoint.host());
        result.put(PREFIX + "redis.default.port", Integer.toString(endpoint.port()));
        result.put(PREFIX + "redis.default.database", "0");
        result.put(PREFIX + "redis.default.encrypted-password", "");
        result.putAll(overrides);
        return result;
    }

    private static void apply(SutStack.AgentBuilder builder, Map<String, String> properties) {
        properties.forEach(builder::property);
    }

    private static void executeTwoTurnJourney(SutStack stack, String marker) {
        InteractionFlow.FlowResult result = InteractionFlow.of(stack.client(MAINPLAN))
                .withTimeoutMs(FLOW_TIMEOUT_MS)
                .withContextId("ctx-" + marker)
                .send("我要出差，标志是" + marker)
                    .mayReachState(TaskState.TASK_STATE_INPUT_REQUIRED)
                .send("去北京，明天出发，3天")
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                .execute();
        assertThat(result.round(1).taskState()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
    }

    private static Throwable executeRedisOperationAndCaptureError(SutStack stack, String marker) throws Exception {
        CompletableFuture<Throwable> callbackResult = new CompletableFuture<>();
        Message message = Message.builder(A2A.toUserMessage("trigger Redis persistence " + marker))
                .contextId("ctx-" + marker)
                .build();
        Thread requestThread = Thread.ofVirtual().name("feat003-redis-failure-" + marker).start(() -> {
            try {
                stack.client(MAINPLAN).sendMessage(message, List.of(), callbackResult::complete);
            } catch (Throwable error) {
                callbackResult.complete(error);
            }
        });
        try {
            return callbackResult.get(15, TimeUnit.SECONDS);
        } catch (TimeoutException error) {
            requestThread.interrupt();
            throw new AssertionError("A2A operation did not invoke its completion callback within 15 seconds", error);
        }
    }

    private static void assertNoConditionTimeout(Throwable a2aError) {
        if (a2aError != null) {
            assertThat(throwableText(a2aError)).doesNotContain("ConditionTimeout");
        }
    }

    private static void executeAuthenticatedRedisTouch(SutStack stack, String marker) {
        InteractionFlow.of(stack.client(MAINPLAN))
                .withTimeoutMs(FLOW_TIMEOUT_MS)
                .withContextId("ctx-" + marker)
                .send("我要出差，标志是" + marker)
                    .mayReachState(TaskState.TASK_STATE_INPUT_REQUIRED)
                    .assertThat(ctx -> assertThat(ctx.taskState())
                            .as("authenticated Redis journey state")
                            .isIn(TaskState.TASK_STATE_INPUT_REQUIRED, TaskState.TASK_STATE_COMPLETED))
                .execute();
    }

    private static void requireSecurityEnvironment() {
        assumeTrue(Boolean.getBoolean("feat003.security.enabled"),
                "enable credential tests with -Dfeat003.security.enabled=true");
    }

    private static String throwableText(Throwable throwable) {
        StringBuilder out = new StringBuilder();
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            out.append(current.getClass().getName()).append(':').append(current.getMessage()).append('\n');
        }
        return out.toString();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record Endpoint(String host, int port) {
        static Endpoint parse(String value) {
            int colon = value.lastIndexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("Expected host:port, got " + value);
            }
            return new Endpoint(value.substring(0, colon), Integer.parseInt(value.substring(colon + 1)));
        }
    }

    private static final class RedisFixture implements AutoCloseable {
        private final BackingServices backing;
        private final Endpoint endpoint;
        private final String password;

        private RedisFixture(BackingServices backing, Endpoint endpoint, String password) {
            this.backing = backing;
            this.endpoint = endpoint;
            this.password = password;
        }

        static RedisFixture start(TestConfig config) {
            BackingServices backing = new BackingServices(config, Set.of("redis"), new TestContainerFactory(null));
            return new RedisFixture(backing, Endpoint.parse(backing.url("redis")), null);
        }

        static RedisFixture startAuthenticated(TestConfig config, String password) {
            BackingServices backing = new BackingServices(config, Set.of("redis"),
                    new AuthenticatedRedisFactory(password));
            return new RedisFixture(backing, Endpoint.parse(backing.url("redis")), password);
        }

        BackingServices backing() {
            return backing;
        }

        Endpoint endpoint() {
            return endpoint;
        }

        RespClient client() throws IOException {
            return new RespClient(endpoint, password);
        }

        @Override
        public void close() {
            backing.close();
        }
    }

    private static final class AuthenticatedRedisFactory implements ContainerFactory {
        private final String password;

        private AuthenticatedRedisFactory(String password) {
            this.password = password;
        }

        @Override
        public ManagedContainer start(String name, String image, int port, Map<String, String> env) {
            GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(image))
                    .withExposedPorts(port)
                    .withCommand("redis-server", "--requirepass", password);
            env.forEach(container::withEnv);
            container.start();
            return new ManagedContainer() {
                @Override
                public String host() {
                    return container.getHost();
                }

                @Override
                public int mappedPort() {
                    return container.getMappedPort(port);
                }

                @Override
                public void close() {
                    container.stop();
                }
            };
        }
    }

    /** Captures byte offsets for every current agent log and reads only bytes appended by this test. */
    private static final class LogOffsets {
        private final Path root;
        private final Map<Path, Long> offsets;

        private LogOffsets(Path root, Map<Path, Long> offsets) {
            this.root = root;
            this.offsets = offsets;
        }

        static LogOffsets capture(TestConfig config) throws IOException {
            String configured = config.getString("sut.logging.dir", "");
            Path root = configured.isBlank()
                    ? Path.of(System.getProperty("basedir", System.getProperty("user.dir")), "target", "sit-logs")
                    : Path.of(configured);
            Map<Path, Long> offsets = new LinkedHashMap<>();
            if (Files.exists(root)) {
                try (var paths = Files.walk(root)) {
                    paths.filter(Files::isRegularFile).forEach(path -> offsets.put(path, size(path)));
                }
            }
            return new LogOffsets(root, offsets);
        }

        String increment() throws IOException {
            if (!Files.exists(root)) {
                return "";
            }
            StringBuilder out = new StringBuilder();
            try (var paths = Files.walk(root)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    byte[] bytes = Files.readAllBytes(path);
                    int from = Math.toIntExact(Math.min(offsets.getOrDefault(path, 0L), bytes.length));
                    out.append(new String(bytes, from, bytes.length - from, StandardCharsets.UTF_8));
                }
            }
            return out.toString();
        }

        private static long size(Path path) {
            try {
                return Files.size(path);
            } catch (IOException e) {
                return 0L;
            }
        }
    }

    /** Small RESP2 client used only for black-box Redis data-plane evidence. */
    private static final class RespClient implements AutoCloseable {
        private final Socket socket = new Socket();
        private final BufferedInputStream input;
        private final BufferedOutputStream output;

        private RespClient(Endpoint endpoint, String password) throws IOException {
            socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), 5_000);
            input = new BufferedInputStream(socket.getInputStream());
            output = new BufferedOutputStream(socket.getOutputStream());
            if (password != null) {
                command("AUTH", password);
            }
        }

        long integer(String... command) throws IOException {
            Object value = command(command);
            return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
        }

        Set<String> scan(String pattern) throws IOException {
            Set<String> keys = new java.util.LinkedHashSet<>();
            String cursor = "0";
            do {
                List<?> reply = (List<?>) command("SCAN", cursor, "MATCH", pattern, "COUNT", "200");
                cursor = String.valueOf(reply.get(0));
                for (Object key : (List<?>) reply.get(1)) {
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
            if (marker < 0) {
                throw new EOFException("Redis closed the connection");
            }
            String line = readLine();
            return switch (marker) {
                case '+' -> line;
                case '-' -> throw new IOException("Redis command failed: " + line);
                case ':' -> Long.parseLong(line);
                case '$' -> readBulk(Integer.parseInt(line));
                case '*' -> readArray(Integer.parseInt(line));
                default -> throw new IOException("Unknown RESP marker: " + (char) marker);
            };
        }

        private Object readBulk(int length) throws IOException {
            if (length < 0) {
                return null;
            }
            byte[] bytes = input.readNBytes(length);
            if (bytes.length != length) {
                throw new EOFException("Truncated Redis bulk reply");
            }
            readLine();
            return new String(bytes, StandardCharsets.UTF_8);
        }

        private List<Object> readArray(int length) throws IOException {
            List<Object> values = new ArrayList<>(Math.max(length, 0));
            for (int i = 0; i < length; i++) {
                values.add(readReply());
            }
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

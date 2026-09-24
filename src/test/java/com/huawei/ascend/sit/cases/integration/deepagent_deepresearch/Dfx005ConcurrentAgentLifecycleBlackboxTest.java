package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.AgentConfig;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import com.huawei.ascend.sit.lifecycle.SutAgent;
import com.huawei.ascend.sit.lifecycle.SutInstance;
import com.huawei.ascend.sit.lifecycle.SutLauncher;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Tag("openjiuwen")
@Tag("dfx-005")
@Tag("blackbox")
@Feature("DFX-005: agent-core支持运行时任务级并发")
class Dfx005ConcurrentAgentLifecycleBlackboxTest extends BaseManagedStackTest {
    private static final String EDPA = "edpa-dfx005";
    private static final String CODE = "code-assistant";
    private static final String DATA = "data-assistant";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(180);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        redis.start();
        LlmSettings llm = LlmSettings.fromEnvironment();
        return SutStack.builder(config)
                .launcher(new ExecClassifierLauncher(config))
                .agent(EDPA, agent -> configureMulti(agent, llm));
    }

    @AfterAll
    void closeFixtures() {
        if (redis.isRunning()) {
            redis.stop();
        }
    }

    @Test
    @Tag("story-dfx-005-d005-01")
    @Story("DFX-005.D005-01: 多 Task 并发执行与结果隔离")
    @DisplayName("DFX-005 D005-01 多 Task 并发执行结果只包含自身 canary")
    void d00501ConcurrentTasksKeepCanariesIsolated() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<CompletableFuture<String>> tasks = IntStream.range(0, 4)
                    .mapToObj(index -> CompletableFuture.supplyAsync(
                            () -> callUnchecked(stack, CODE, canary("D005-01-" + index)), executor))
                    .toList();
            List<String> results = tasks.stream().map(this::join).toList();
            for (int i = 0; i < results.size(); i++) {
                assertCanary(results.get(i), "D005-01-" + i, canariesExcept("D005-01-", i, 4));
            }
            assertThat(managed(stack).isAlive()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @Tag("story-dfx-005-d005-02")
    @Story("DFX-005.D005-02: 先完成 Task 销毁不破坏在飞实例")
    @DisplayName("DFX-005 D005-02 先完成 Task 销毁后其余 Task 与后继请求仍成功")
    void d00502CompletedTaskDoesNotBreakPeers() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            List<CompletableFuture<String>> peers = IntStream.range(0, 3)
                    .mapToObj(index -> CompletableFuture.supplyAsync(
                            () -> callUnchecked(stack, CODE, canary("D005-02-PEER-" + index)), executor))
                    .toList();
            String first = join(peers.get(0));
            assertThat(first).contains("D005-02-PEER-0");
            String followUpCanary = canary("D005-02-FOLLOWUP");
            String followUp = call(stack, CODE, followUpCanary);
            assertCanary(followUp, followUpCanary, List.of());
            for (int i = 1; i < peers.size(); i++) {
                assertCanary(join(peers.get(i)), "D005-02-PEER-" + i,
                        canariesExcept("D005-02-PEER-", i, 3));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @Tag("story-dfx-005-d005-03")
    @Story("DFX-005.D005-03: 重复生命周期波次后的服务健康")
    @DisplayName("DFX-005 D005-03 重复并发生命周期波次后服务仍可用")
    void d00503RepeatedWavesLeaveServiceHealthy() throws Exception {
        for (int wave = 0; wave < 3; wave++) {
            ExecutorService executor = Executors.newFixedThreadPool(4);
            try {
                int currentWave = wave;
                List<CompletableFuture<String>> tasks = IntStream.range(0, 4)
                        .mapToObj(index -> CompletableFuture.supplyAsync(
                                () -> callUnchecked(stack, CODE,
                                        canary("D005-03-W" + currentWave + "-" + index)), executor))
                        .toList();
                List<String> results = tasks.stream().map(this::join).toList();
                for (int index = 0; index < results.size(); index++) {
                    assertCanary(results.get(index), "D005-03-W" + currentWave + "-" + index,
                            canariesExcept("D005-03-W" + currentWave + "-", index, 4));
                }
            } finally {
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
            }
        }
        String probe = canary("D005-03-PROBE");
        assertCanary(call(stack, CODE, probe), probe, List.of());
        assertThat(managed(stack).isAlive()).isTrue();
    }

    @Test
    @Tag("story-dfx-005-d005-04")
    @Story("DFX-005.D005-04: 既有单 Agent 路径兼容")
    @DisplayName("DFX-005 D005-04 single profile 的同步和流式入口保持兼容")
    void d00504SingleAgentPathRemainsCompatible() throws Exception {
        try (SutStack single = startSingle(config)) {
            String sync = canary("D005-04-SYNC");
            assertCanary(call(single, null, sync), sync, List.of());
            String stream = canary("D005-04-STREAM");
            HttpResponse<String> response = send(single, null, "SendStreamingMessage", stream,
                    context("D005-04-STREAM"));
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("content-type").orElse(""))
                    .containsIgnoringCase("text/event-stream");
            assertThat(response.body()).contains(stream);
        }
    }

    @Test
    @Tag("story-dfx-005-d005-09")
    @Story("DFX-005.D005-09: 单 Task 异常隔离")
    @DisplayName("DFX-005 D005-09 单 Task 模型故障不传播到并发 peer 和后继请求")
    void d00509SingleTaskFailureDoesNotBreakPeers() throws Exception {
        String faultCanary = canary("D005-09-FAULT");
        String peerOne = canary("D005-09-PEER-1");
        String peerTwo = canary("D005-09-PEER-2");
        try (ModelFaultFixture fixture = ModelFaultFixture.start();
                SutStack faultStack = startFaultStack(fixture)) {
            ExecutorService executor = Executors.newFixedThreadPool(3);
            try {
                List<CompletableFuture<String>> requests = List.of(
                        CompletableFuture.supplyAsync(() -> callAllowingFailure(faultStack, CODE, faultCanary), executor),
                        CompletableFuture.supplyAsync(() -> callUnchecked(faultStack, CODE, peerOne), executor),
                        CompletableFuture.supplyAsync(() -> callUnchecked(faultStack, CODE, peerTwo), executor));
                fixture.awaitRequest(faultCanary);

                String faultResult = join(requests.get(0));
                assertFailureResult(faultResult);
                assertCanary(join(requests.get(1)), peerOne, List.of(faultCanary, peerTwo));
                assertCanary(join(requests.get(2)), peerTwo, List.of(faultCanary, peerOne));

                String followUp = canary("D005-09-FOLLOWUP");
                assertCanary(call(faultStack, CODE, followUp), followUp, List.of(faultCanary));
                assertThat(managed(faultStack).isAlive()).isTrue();
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private String call(SutStack target, String agentId, String canary) throws Exception {
        HttpResponse<String> response = send(target, agentId, "SendMessage", prompt(canary), context(canary));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        if (isTerminal(response.body())) {
            return response.body();
        }
        String taskId = requireTaskId(response.body());
        String owner = agentId == null ? CODE : agentId;
        return Awaitility.await("DFX-005 terminal task " + taskId)
                .atMost(REQUEST_TIMEOUT)
                .pollInterval(Duration.ofMillis(500))
                .until(() -> operation(target, owner, "GetTask", taskId).body(),
                        Dfx005ConcurrentAgentLifecycleBlackboxTest::isTerminal);
    }

    private String callUnchecked(SutStack target, String agentId, String canary) {
        try {
            return call(target, agentId, canary);
        } catch (Exception failure) {
            throw new CompletionException(failure);
        }
    }

    private String callAllowingFailure(SutStack target, String agentId, String canary) {
        try {
            HttpResponse<String> response = send(target, agentId, "SendMessage", prompt(canary), context(canary));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            if (isTerminal(response.body())) {
                return response.body();
            }
            String taskId = requireTaskId(response.body());
            return Awaitility.await("DFX-005 failure task " + taskId)
                    .atMost(REQUEST_TIMEOUT)
                    .pollInterval(Duration.ofMillis(500))
                    .until(() -> operation(target, agentId, "GetTask", taskId).body(),
                            Dfx005ConcurrentAgentLifecycleBlackboxTest::isTerminal);
        } catch (Exception failure) {
            throw new CompletionException(failure);
        }
    }

    private String join(CompletableFuture<String> future) {
        try {
            return future.join();
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof AssertionError assertion) {
                throw assertion;
            }
            throw failure;
        }
    }

    private static void assertCanary(String result, String expected, List<String> forbidden) {
        assertThat(result).as("result for %s", expected).contains(expected);
        for (String token : forbidden) {
            assertThat(result).as("result for %s must not contain %s", expected, token).doesNotContain(token);
        }
    }

    private static void assertFailureResult(String result) {
        assertThat(result).as("failed task result").containsAnyOf(
                "dfx005_fault", "controlled_rejection", "HTTP 500");
        assertThat(result).contains("final_result={error=");
    }

    private static List<String> canariesExcept(String prefix, int excluded, int count) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (i != excluded) {
                values.add(prefix + i);
            }
        }
        return values;
    }

    private static String canary(String label) {
        return "DFX005_" + label + "_" + UUID.randomUUID();
    }

    private static String context(String label) {
        return "dfx005-" + label + "-" + UUID.randomUUID();
    }

    private String prompt(String canary) {
        return "DFX005 concurrency acceptance probe. Return the exact token " + canary
                + " in your final answer. Do not call tools.";
    }

    private SutStack startSingle(TestConfig testConfig) {
        LlmSettings llm = LlmSettings.fromEnvironment();
        return SutStack.builder(testConfig)
                .launcher(new ExecClassifierLauncher(testConfig))
                .agent(EDPA, agent -> {
                    agent.profile("single");
                    configureShared(agent);
                    applyLlm(agent, llm);
                    agent.property("deep-agent.scenario-home", resourcePath("code-review").toString());
                }).start();
    }

    private SutStack startFaultStack(ModelFaultFixture fixture) {
        return SutStack.builder(config)
                .launcher(new ExecClassifierLauncher(config))
                .agent(EDPA, agent -> {
                    configureMulti(agent, null);
                    applyFixtureLlm(agent, fixture);
                }).start();
    }

    private void configureMulti(SutStack.AgentBuilder agent, LlmSettings llm) {
        String run = UUID.randomUUID().toString();
        agent.profile("multi");
        configureShared(agent);
        if (llm != null) {
            applyLlm(agent, llm);
        }
        agent.property("deep-agent.max-iterations", "7")
                .property("deep-agent.instances." + CODE + ".scenario", resourcePath("code-review").toString())
                .property("deep-agent.instances." + CODE + ".workspace",
                        workspaces(run, CODE).toString())
                .property("deep-agent.instances." + DATA + ".scenario", resourcePath("data-analysis").toString())
                .property("deep-agent.instances." + DATA + ".workspace",
                        workspaces(run, DATA).toString());
    }

    private void configureShared(SutStack.AgentBuilder agent) {
        agent.property("openjiuwen.service.middleware.checkpointer.type", "redis")
                .property("openjiuwen.service.middleware.checkpointer.redis-ref", "default")
                .property("openjiuwen.service.middleware.redis.default.type", "standalone")
                .property("openjiuwen.service.middleware.redis.default.host", redis.getHost())
                .property("openjiuwen.service.middleware.redis.default.port",
                        String.valueOf(redis.getMappedPort(6379)))
                .property("openjiuwen.service.a2a.remote-agents[0].name", "unused-fixture-agent")
                .property("openjiuwen.service.a2a.remote-agents[0].url", "http://127.0.0.1:1/a2a");
    }

    private static void applyLlm(SutStack.AgentBuilder agent, LlmSettings llm) {
        agent.env("EDP_AGENT_MODEL_API_KEY", llm.apiKey())
                .env("EDP_AGENT_MODEL_BASE_URL", llm.apiBase())
                .env("EDP_AGENT_MODEL_NAME", llm.model())
                .env("EDP_AGENT_MODEL_PROVIDER", llm.provider())
                .property("deep-agent.backend.verify_ssl", llm.sslVerify())
                .property("deep-agent.backend.max_retries", "0")
                .property("deep-agent.backend.llm_retry_max", "0");
    }

    private static void applyFixtureLlm(SutStack.AgentBuilder agent, ModelFaultFixture fixture) {
        agent.env("EDP_AGENT_MODEL_API_KEY", "dfx005-fixture-key")
                .env("EDP_AGENT_MODEL_BASE_URL", fixture.apiBase())
                .env("EDP_AGENT_MODEL_NAME", "dfx005-fixture-model")
                .env("EDP_AGENT_MODEL_PROVIDER", "OpenAI")
                .property("deep-agent.backend.client_provider", "openai")
                .property("deep-agent.backend.api_key", "dfx005-fixture-key")
                .property("deep-agent.backend.api_base", fixture.apiBase())
                .property("deep-agent.backend.verify_ssl", "false")
                .property("deep-agent.model.model", "dfx005-fixture-model")
                .property("deep-agent.backend.max_retries", "0")
                .property("deep-agent.backend.llm_retry_max", "0");
    }

    private static Path workspaces(String run, String agent) {
        return Path.of(System.getProperty("user.dir"), "target", "dfx005-workspaces", run, agent)
                .toAbsolutePath();
    }

    private HttpResponse<String> send(SutStack target, String id, String method, String text, String context)
            throws Exception {
        String path = id == null ? "/a2a" : "/a2a/agents/" + id;
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(target.baseUrl(EDPA) + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json");
        if ("SendStreamingMessage".equals(method)) {
            request.header("Accept", "text/event-stream");
        }
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(
                        rpc(method, text, context, null))).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> operation(SutStack target, String id, String method, String taskId)
            throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(target.baseUrl(EDPA) + "/a2a/agents/" + id))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(rpc(method, null, null, taskId))).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String rpc(String method, String text, String context, String taskId) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        if ("GetTask".equals(method)) {
            params.put("id", taskId);
        } else {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "ROLE_USER");
            message.put("messageId", UUID.randomUUID().toString());
            message.put("parts", List.of(Map.of("text", text)));
            message.put("contextId", context);
            params.put("message", message);
        }
        return JSON.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "id", UUID.randomUUID().toString(), "method", method, "params", params));
    }

    private static boolean isTerminal(String body) {
        return body != null && (body.contains("TASK_STATE_COMPLETED")
                || body.contains("TASK_STATE_FAILED")
                || body.contains("TASK_STATE_CANCELED")
                || body.contains("TASK_STATE_CANCELLED")
                || body.contains("TASK_STATE_REJECTED"));
    }

    private static String requireTaskId(String body) throws Exception {
        JsonNode root = JSON.readTree(body);
        String taskId = root.path("result").path("task").path("id").asText();
        if (taskId.isBlank()) {
            taskId = root.path("result").path("id").asText();
        }
        assertThat(taskId).as("task id in %s", body).isNotBlank();
        return taskId;
    }

    private ManagedSutInstance managed(SutStack target) {
        return (ManagedSutInstance) target.managedInstance(EDPA);
    }

    private static Path resourcePath(String relative) {
        String root = "testdata/integration/deepagent_deepresearch/feat042/scenarios/" + relative;
        try {
            return Path.of(Dfx005ConcurrentAgentLifecycleBlackboxTest.class.getClassLoader()
                    .getResource(root).toURI()).toAbsolutePath();
        } catch (Exception failure) {
            throw new IllegalStateException("Missing DFX-005 resource: " + root, failure);
        }
    }

    private record LlmSettings(String apiKey, String apiBase, String model, String provider, String sslVerify) {
        static LlmSettings fromEnvironment() {
            return new LlmSettings(require("LLM_API_KEY"), require("LLM_API_BASE"), require("LLM_MODEL"),
                    require("LLM_PROVIDER"), require("LLM_SSL_VERIFY"));
        }

        private static String require(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(name + " is required for DFX-005 acceptance");
            }
            return value;
        }
    }

    private static final class ModelFaultFixture implements AutoCloseable {
        private static final String FAULT_MARKER = "DFX005_D005-09-FAULT";
        private final HttpServer server;
        private final ExecutorService executor;
        private final List<String> requests = new CopyOnWriteArrayList<>();

        private ModelFaultFixture(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
        }

        static ModelFaultFixture start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                ExecutorService executor = Executors.newCachedThreadPool();
                ModelFaultFixture fixture = new ModelFaultFixture(server, executor);
                server.createContext("/", fixture::reply);
                server.setExecutor(executor);
                server.start();
                return fixture;
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot start DFX-005 model fixture", failure);
            }
        }

        String apiBase() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        void awaitRequest(String canary) {
            Awaitility.await("model request " + canary)
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(100))
                    .until(() -> requests.stream().anyMatch(body -> body.contains(canary)));
        }

        private void reply(HttpExchange exchange) throws IOException {
            try (exchange) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requests.add(body);
                JsonNode request = JSON.readTree(body);
                String model = request.path("model").asText("dfx005-fixture-model");
                if (body.contains(FAULT_MARKER)) {
                    byte[] error = JSON.writeValueAsBytes(Map.of("error", Map.of(
                            "message", "controlled DFX-005 model rejection",
                            "type", "controlled_rejection",
                            "code", "dfx005_fault")));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(500, error.length);
                    exchange.getResponseBody().write(error);
                    return;
                }

                String content = userTexts(request.path("messages"));
                Map<String, Object> choice = Map.of(
                        "index", 0,
                        "message", Map.of("role", "assistant", "content", content),
                        "finish_reason", "stop");
                byte[] response = JSON.writeValueAsBytes(Map.of(
                        "id", "dfx005-model-response", "object", "chat.completion", "created", 1,
                        "model", model, "choices", List.of(choice)));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        }

        private static String userTexts(JsonNode messages) {
            List<String> texts = new ArrayList<>();
            if (messages.isArray()) {
                for (JsonNode message : messages) {
                    if (!"user".equalsIgnoreCase(message.path("role").asText())) {
                        continue;
                    }
                    JsonNode content = message.path("content");
                    if (content.isTextual()) {
                        texts.add(content.asText());
                    } else if (content.isArray()) {
                        content.forEach(part -> {
                            String text = part.path("text").asText();
                            if (!text.isBlank()) {
                                texts.add(text);
                            }
                        });
                    }
                }
            }
            String joined = String.join(" ", texts);
            return joined.length() <= 4000 ? joined : joined.substring(joined.length() - 4000);
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static final class ExecClassifierLauncher implements SutLauncher {
        private static final String READY_PATH = "/.well-known/agent.json";
        private final TestConfig config;
        private final HttpClient healthClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).version(HttpClient.Version.HTTP_1_1).build();

        private ExecClassifierLauncher(TestConfig config) {
            this.config = config;
        }

        @Override
        public SutInstance start(SutAgent agent, AgentConfig agentConfig) {
            Path jar = executableJar(agent);
            int port = agentConfig.port() > 0 ? agentConfig.port() : freePort();
            agentConfig.port(port);
            agentConfig.property("openjiuwen.service.a2a.public-url", "http://127.0.0.1:" + port);
            Path log = logFile(agent.name());
            try {
                Files.createDirectories(log.getParent());
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot create DFX-005 log directory", failure);
            }
            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            Map<String, String> properties = new LinkedHashMap<>(config.getStringMap("sut.java.system-properties"));
            properties.putAll(agentConfig.jvmSystemProperties());
            properties.putIfAbsent("LOG_HOME", log.getParent().toString());
            properties.forEach((key, value) -> command.add("-D" + key + "=" + value));
            command.add("-jar");
            command.add(jar.toString());
            command.addAll(agentConfig.toProgramArgs());
            ProcessBuilder processBuilder = new ProcessBuilder(command)
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
            agentConfig.environment().forEach(processBuilder.environment()::put);
            Process process;
            try {
                process = processBuilder.start();
            } catch (IOException failure) {
                throw new IllegalStateException("Failed to start " + agent.name(), failure);
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(
                    Math.max(120, config.getInt("sut.timeout.startup-seconds", 60)));
            try {
                while (System.nanoTime() < deadline) {
                    if (!process.isAlive()) {
                        throw exited(agent, log);
                    }
                    if (ready(port)) {
                        return new ManagedSutInstance(agent.name(), process.pid(), port,
                                "http://localhost:" + port, process, log);
                    }
                    Thread.sleep(250);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IllegalStateException("Interrupted while waiting for " + agent.name(), interrupted);
            } catch (RuntimeException failure) {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                throw failure;
            }
            process.destroyForcibly();
            throw new IllegalStateException(agent.name() + " did not become ready within timeout\n" + tail(log));
        }

        private Path executableJar(SutAgent agent) {
            String repository = config.getString("sut.m2.repo",
                    Path.of(System.getProperty("user.home"), ".m2", "repository").toString());
            String group = agent.artifact().groupId().replace('.', '/');
            String artifact = agent.artifact().artifactId();
            String version = agent.artifact().version();
            Path jar = Path.of(repository, group, artifact, version,
                    artifact + "-" + version + "-exec.jar").toAbsolutePath();
            if (!Files.isRegularFile(jar)) {
                throw new IllegalStateException("Executable SUT classifier artifact not found: " + jar);
            }
            return jar;
        }

        private Path logFile(String agentName) {
            String configured = config.getString("sut.logging.dir");
            Path root = configured == null || configured.isBlank()
                    ? Path.of(System.getProperty("user.dir"), "target", "sit-logs") : Path.of(configured);
            return root.resolve(agentName + "-dfx005-" + UUID.randomUUID()).resolve("stdout.log");
        }

        private boolean ready(int port) {
            try {
                HttpResponse<Void> response = healthClient.send(HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + port + READY_PATH))
                                .timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                return response.statusCode() == 200;
            } catch (Exception notReady) {
                return false;
            }
        }

        private static IllegalStateException exited(SutAgent agent, Path log) {
            return new IllegalStateException(agent.name() + " process exited before becoming ready\n" + tail(log));
        }

        private static int freePort() {
            try (ServerSocket socket = new ServerSocket(0)) {
                socket.setReuseAddress(true);
                return socket.getLocalPort();
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot allocate DFX-005 SUT port", failure);
            }
        }

        private static String tail(Path log) {
            try {
                List<String> lines = Files.readAllLines(log);
                return String.join("\n", lines.subList(Math.max(0, lines.size() - 100), lines.size()));
            } catch (IOException failure) {
                return "(cannot read log: " + failure.getMessage() + ")";
            }
        }
    }
}

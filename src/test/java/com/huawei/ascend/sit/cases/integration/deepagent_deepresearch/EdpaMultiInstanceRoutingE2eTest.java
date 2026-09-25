package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.AgentConfig;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import com.huawei.ascend.sit.lifecycle.MavenArtifact;
import com.huawei.ascend.sit.lifecycle.SutAgent;
import com.huawei.ascend.sit.lifecycle.SutInstance;
import com.huawei.ascend.sit.lifecycle.SutLauncher;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
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
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Tag("openjiuwen")
@Tag("feat-028")
@Tag("feat-042")
@Tag("blackbox")
@Feature("FEAT-028 + FEAT-042: EDPAgent 双实例路由链路")
class EdpaMultiInstanceRoutingE2eTest extends BaseManagedStackTest {
    private static final String RUNTIME = "edpa-multi";
    private static final String UPSTREAM = "edpa-routing-upstream";
    private static final String CODE = "code-assistant";
    private static final String DATA = "data-assistant";
    private static final String CODE_RUNTIME_MARKER = "runtime-probe-code";
    private static final String DATA_RUNTIME_MARKER = "runtime-probe-data";
    private static final String GATEWAY_TOKEN = "feat028-feat042-routing-token";
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(6);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MavenArtifact EDPA_ARTIFACT =
            new MavenArtifact("com.openjiuwen", "edp-agent-engine", "0.1.1");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);
    private LlmSettings llm;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        llm = LlmSettings.fromEnvironment();
        redis.start();
        return builder(config).agent(RUNTIME, this::configureRuntime);
    }

    @AfterAll
    void stopRedis() {
        redis.stop();
    }

    @Test
    @Tag("story-feat-028-feat-042-e1")
    @Story("FEAT-028-FEAT-042.E1: EDPAgent 直连同 Runtime 双实例")
    @DisplayName("E1 EDPAgent 直连同一个 Runtime 的两个 Agent 实例")
    void e1EdpAgentDirectlyCallsBothInstancesOnOneRuntime() throws Exception {
        ManagedSutInstance runtime = runtime();
        long runtimePid = assertRuntimeTopology(runtime);

        try (ManagedSutInstance upstream = startUpstream(RouteMode.DIRECT, null)) {
            ProbeResults results = invokeBoth(upstream);
            assertProbeResponse(results.code(), results.codeCanary(), CODE_RUNTIME_MARKER, DATA_RUNTIME_MARKER);
            assertProbeResponse(results.data(), results.dataCanary(), DATA_RUNTIME_MARKER, CODE_RUNTIME_MARKER);
        }

        assertSameRuntime(runtime, runtimePid);
    }

    @Test
    @Tag("story-feat-028-feat-042-e2")
    @Story("FEAT-028-FEAT-042.E2: EDPAgent 经 WireMock 网关访问同 Runtime 双实例")
    @DisplayName("E2 EDPAgent 经 WireMock 网关访问同一个 Runtime 的两个 Agent 实例")
    void e2EdpAgentCallsBothInstancesThroughWireMockGateway() throws Exception {
        ManagedSutInstance runtime = runtime();
        long runtimePid = assertRuntimeTopology(runtime);
        WireMockServer gateway = new WireMockServer(options().dynamicPort());
        gateway.start();
        try {
            proxyRuntime(gateway, runtime.baseUrl(), CODE);
            proxyRuntime(gateway, runtime.baseUrl(), DATA);
            String gatewayBaseUrl = "http://127.0.0.1:" + gateway.port();

            ProbeResults results;
            try (ManagedSutInstance upstream = startUpstream(RouteMode.GATEWAY, gatewayBaseUrl)) {
                results = invokeBoth(upstream);
                assertProbeResponse(results.code(), results.codeCanary(), CODE_RUNTIME_MARKER, DATA_RUNTIME_MARKER);
                assertProbeResponse(results.data(), results.dataCanary(), DATA_RUNTIME_MARKER, CODE_RUNTIME_MARKER);
            }

            verifyGatewayRequest(gateway, CODE, results.codeCanary());
            verifyGatewayRequest(gateway, DATA, results.dataCanary());
        } finally {
            gateway.stop();
        }

        assertSameRuntime(runtime, runtimePid);
    }

    private SutStack.Builder builder(TestConfig targetConfig) {
        return SutStack.builder(targetConfig).launcher(new ExecClassifierLauncher(targetConfig));
    }

    private void configureRuntime(SutStack.AgentBuilder agent) {
        String runId = UUID.randomUUID().toString();
        Path workspaceRoot = Path.of(System.getProperty("user.dir"), "target",
                "feat028-feat042-workspaces", runId).toAbsolutePath();
        applyLlmEnvironment(agent);
        agent.profile("multi")
                .env("CODE_ASSISTANT_MODEL", llm.model())
                .env("DATA_ASSISTANT_API_KEY", llm.apiKey())
                .env("DATA_ASSISTANT_BASE_URL", llm.apiBase())
                .env("DATA_ASSISTANT_MODEL", llm.model())
                .property("deep-agent.backend.verify_ssl", llm.sslVerify())
                .property("deep-agent.backend.max_retries", "0")
                .property("deep-agent.backend.llm_retry_max", "0")
                .property("deep-agent.versatile.circuit-breaker.enabled", "false")
                .property("deep-agent.instances.code-assistant.scenario",
                        resourcePath("feat028_feat042/runtime-probe-code").toString())
                .property("deep-agent.instances.code-assistant.workspace",
                        workspaceRoot.resolve(CODE_RUNTIME_MARKER).toString())
                .property("deep-agent.instances.code-assistant.max-iterations", "8")
                .property("deep-agent.instances.data-assistant.scenario",
                        resourcePath("feat028_feat042/runtime-probe-data").toString())
                .property("deep-agent.instances.data-assistant.workspace",
                        workspaceRoot.resolve(DATA_RUNTIME_MARKER).toString())
                .property("deep-agent.instances.data-assistant.max-iterations", "8")
                .property("deep-agent.instances.data-assistant.backend.client_provider", llm.provider())
                .property("deep-agent.instances.data-assistant.backend.verify_ssl", llm.sslVerify())
                .property("deep-agent.instances.data-assistant.backend.max_retries", "0")
                .property("deep-agent.instances.data-assistant.backend.llm_retry_max", "0")
                .property("openjiuwen.service.a2a.remote-agents[0].name", "unused-fixture-agent")
                .property("openjiuwen.service.a2a.remote-agents[0].url", "http://127.0.0.1:1/a2a")
                .property("deep-agent.redis.mode", "single")
                .property("deep-agent.redis.host", redis.getHost())
                .property("deep-agent.redis.port", String.valueOf(redis.getMappedPort(6379)))
                .property("deep-agent.redis.connect-timeout-ms", "2000")
                .property("deep-agent.redis.socket-timeout-ms", "5000")
                .property("openjiuwen.service.middleware.checkpointer.type", "redis")
                .property("openjiuwen.service.middleware.checkpointer.redis-ref", "default")
                .property("openjiuwen.service.middleware.redis.default.type", "standalone")
                .property("openjiuwen.service.middleware.redis.default.host", redis.getHost())
                .property("openjiuwen.service.middleware.redis.default.port",
                        String.valueOf(redis.getMappedPort(6379)));
    }

    private ManagedSutInstance startUpstream(RouteMode mode, String gatewayBaseUrl) {
        AgentConfig agent = new AgentConfig()
                .profile("single")
                .property("deep-agent.scenario-home",
                        resourcePath("feat028_feat042/orchestrator").toString())
                .property("deep-agent.backend.verify_ssl", llm.sslVerify())
                .property("deep-agent.backend.max_retries", "0")
                .property("deep-agent.backend.llm_retry_max", "0")
                .property("deep-agent.versatile.circuit-breaker.enabled", "false")
                .property("deep-agent.redis.mode", "single")
                .property("deep-agent.redis.host", redis.getHost())
                .property("deep-agent.redis.port", String.valueOf(redis.getMappedPort(6379)))
                .property("deep-agent.redis.connect-timeout-ms", "2000")
                .property("deep-agent.redis.socket-timeout-ms", "5000")
                .property("openjiuwen.service.middleware.checkpointer.type", "in_memory")
                .property("openjiuwen.service.a2a.remote-agents[0].name", CODE)
                .property("openjiuwen.service.a2a.remote-agents[0].url", runtime().baseUrl() + instancePath(CODE))
                .property("openjiuwen.service.a2a.remote-agents[0].streaming", "true")
                .property("openjiuwen.service.a2a.remote-agents[1].name", DATA)
                .property("openjiuwen.service.a2a.remote-agents[1].url", runtime().baseUrl() + instancePath(DATA))
                .property("openjiuwen.service.a2a.remote-agents[1].streaming", "true");
        applyLlmEnvironment(agent);
        if (mode == RouteMode.GATEWAY) {
            agent.property("deep-agent.a2a-gateway.enabled", "true")
                    .property("deep-agent.a2a-gateway.base-url", gatewayBaseUrl)
                    .property("deep-agent.a2a-gateway.json-rpc-path", "/a2a/agents/{agentCard}")
                    .property("deep-agent.a2a-gateway.token", GATEWAY_TOKEN)
                    .property("deep-agent.a2a-gateway.target.intent-mapping.routing_probe", CODE);
        }
        SutAgent descriptor = new SutAgent(UPSTREAM, EDPA_ARTIFACT, List.of());
        return (ManagedSutInstance) new ExecClassifierLauncher(config).start(descriptor, agent);
    }

    private void applyLlmEnvironment(SutStack.AgentBuilder agent) {
        agent.env("EDP_AGENT_MODEL_API_KEY", llm.apiKey())
                .env("EDP_AGENT_MODEL_BASE_URL", llm.apiBase())
                .env("EDP_AGENT_MODEL_NAME", llm.model())
                .env("EDP_AGENT_MODEL_PROVIDER", llm.provider());
    }

    private void applyLlmEnvironment(AgentConfig agent) {
        agent.env("EDP_AGENT_MODEL_API_KEY", llm.apiKey())
                .env("EDP_AGENT_MODEL_BASE_URL", llm.apiBase())
                .env("EDP_AGENT_MODEL_NAME", llm.model())
                .env("EDP_AGENT_MODEL_PROVIDER", llm.provider());
    }

    private ProbeResults invokeBoth(ManagedSutInstance upstream) throws Exception {
        String codeCanary = "ROUTE_CODE_" + UUID.randomUUID();
        String dataCanary = "ROUTE_DATA_" + UUID.randomUUID();
        HttpResponse<String> code = invokeProbe(upstream, CODE, codeCanary,
                "Deliver the code routing probe " + codeCanary + " to the requested Agent.");
        HttpResponse<String> data = invokeProbe(upstream, DATA, dataCanary,
                "Deliver the data routing probe " + dataCanary + " to the requested Agent.");
        return new ProbeResults(code, data, codeCanary, dataCanary);
    }

    private HttpResponse<String> invokeProbe(ManagedSutInstance upstream, String target, String canary,
            String payload) throws Exception {
        String prompt = """
                ROUTING_ACCEPTANCE_PROBE
                TARGET_AGENT=%s
                ROUTE_CANARY=%s
                PAYLOAD=%s
                Follow the routing acceptance rule. Do not answer the payload locally.
                """.formatted(target, canary, payload);
        String body = rpc(prompt, "feat028-feat042-" + UUID.randomUUID());
        return http.send(HttpRequest.newBuilder(URI.create(upstream.baseUrl() + "/a2a"))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String rpc(String text, String contextId) throws Exception {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "ROLE_USER");
        message.put("messageId", UUID.randomUUID().toString());
        message.put("contextId", contextId);
        message.put("parts", List.of(Map.of("kind", "text", "text", text)));
        return JSON.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", "SendStreamingMessage",
                "params", Map.of("message", message)));
    }

    private static void assertProbeResponse(HttpResponse<String> response, String canary,
            String expectedRuntimeMarker, String peerRuntimeMarker) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse(""))
                .containsIgnoringCase("text/event-stream");
        assertThat(response.body()).contains("TASK_STATE_COMPLETED", canary, expectedRuntimeMarker)
                .doesNotContain(peerRuntimeMarker);
    }

    private long assertRuntimeTopology(ManagedSutInstance runtime) throws Exception {
        assertThat(runtime.isAlive()).isTrue();
        assertThat(runtime.pid()).isPositive();
        HttpResponse<String> listingResponse = get(runtime.baseUrl() + "/a2a/agents");
        assertThat(listingResponse.statusCode()).as(listingResponse.body()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        JSON.readTree(listingResponse.body()).path("agents").forEach(node -> ids.add(node.asText()));
        assertThat(ids).containsExactlyInAnyOrder(CODE, DATA);
        assertInstanceCard(runtime, CODE);
        assertInstanceCard(runtime, DATA);
        return runtime.pid();
    }

    private void assertInstanceCard(ManagedSutInstance runtime, String agentId) throws Exception {
        HttpResponse<String> response = get(runtime.baseUrl() + instancePath(agentId)
                + "/.well-known/agent-card.json");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode card = JSON.readTree(response.body());
        assertThat(card.path("supportedInterfaces").path(0).path("url").asText())
                .endsWith(instancePath(agentId));
    }

    private HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private ManagedSutInstance runtime() {
        return (ManagedSutInstance) stack.managedInstance(RUNTIME);
    }

    private static void assertSameRuntime(ManagedSutInstance runtime, long expectedPid) {
        assertThat(runtime.isAlive()).isTrue();
        assertThat(runtime.pid()).isEqualTo(expectedPid);
    }

    private static void proxyRuntime(WireMockServer gateway, String runtimeBaseUrl, String agentId) {
        gateway.stubFor(post(urlPathEqualTo(instancePath(agentId)))
                .willReturn(aResponse().proxiedFrom(runtimeBaseUrl)));
    }

    private static void verifyGatewayRequest(WireMockServer gateway, String agentId, String canary) {
        gateway.verify(moreThanOrExactly(1), postRequestedFor(urlPathEqualTo(instancePath(agentId)))
                .withHeader("token", equalTo(GATEWAY_TOKEN))
                .withRequestBody(containing(canary)));
    }

    private static String instancePath(String agentId) {
        return "/a2a/agents/" + agentId;
    }

    private static Path resourcePath(String relative) {
        String root = "testdata/integration/deepagent_deepresearch/" + relative;
        try {
            URI uri = EdpaMultiInstanceRoutingE2eTest.class.getClassLoader().getResource(root).toURI();
            return Path.of(uri).toAbsolutePath();
        } catch (Exception failure) {
            throw new IllegalStateException("Missing routing test resource: " + root, failure);
        }
    }

    private enum RouteMode {
        DIRECT,
        GATEWAY
    }

    private record ProbeResults(HttpResponse<String> code, HttpResponse<String> data,
                                String codeCanary, String dataCanary) {
    }

    private record LlmSettings(String apiKey, String apiBase, String model, String provider, String sslVerify) {
        static LlmSettings fromEnvironment() {
            String apiKey = require("LLM_API_KEY");
            String apiBase = require("LLM_API_BASE");
            String model = require("LLM_MODEL");
            String provider = require("LLM_PROVIDER");
            String sslVerify = require("LLM_SSL_VERIFY");
            URI uri = URI.create(apiBase);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalStateException("LLM_API_BASE must be an HTTP(S) URL");
            }
            return new LlmSettings(apiKey, apiBase, model, provider, sslVerify);
        }

        private static String require(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(name + " is required for this acceptance test");
            }
            return value;
        }
    }

    private static final class ExecClassifierLauncher implements SutLauncher {
        private static final String READY_PATH = "/.well-known/agent.json";
        private final TestConfig config;
        private final HttpClient healthClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

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
                throw new IllegalStateException("Cannot create SUT log directory", failure);
            }

            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            Map<String, String> systemProperties = new LinkedHashMap<>(
                    config.getStringMap("sut.java.system-properties"));
            systemProperties.putAll(agentConfig.jvmSystemProperties());
            systemProperties.putIfAbsent("LOG_HOME", log.getParent().toString());
            systemProperties.forEach((key, value) -> command.add("-D" + key + "=" + value));
            command.add("-jar");
            command.add(jar.toString());
            command.addAll(agentConfig.toProgramArgs());

            ProcessBuilder processBuilder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
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
                                "http://127.0.0.1:" + port, process, log);
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
            throw new IllegalStateException(agent.name() + " did not become ready within timeout.\n--- log tail ---\n"
                    + tail(log));
        }

        private Path executableJar(SutAgent agent) {
            String repository = config.getString("sut.m2.repo",
                    Path.of(System.getProperty("user.home"), ".m2", "repository").toString());
            MavenArtifact artifact = agent.artifact();
            Path jar = Path.of(repository, artifact.groupId().replace('.', '/'), artifact.artifactId(),
                    artifact.version(), artifact.artifactId() + "-" + artifact.version() + "-exec.jar")
                    .toAbsolutePath();
            if (!Files.isRegularFile(jar)) {
                throw new IllegalStateException("Executable SUT classifier artifact not found: " + jar);
            }
            return jar;
        }

        private Path logFile(String agentName) {
            String configured = config.getString("sut.logging.dir");
            Path root = configured == null || configured.isBlank()
                    ? Path.of(System.getProperty("user.dir"), "target", "sit-logs")
                    : Path.of(configured);
            return root.resolve(agentName + "-feat028-feat042-" + UUID.randomUUID()).resolve("stdout.log");
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
            return new IllegalStateException(agent.name()
                    + " process exited before becoming ready.\n--- log tail ---\n" + tail(log));
        }

        private static int freePort() {
            try (ServerSocket socket = new ServerSocket(0)) {
                socket.setReuseAddress(true);
                return socket.getLocalPort();
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot allocate SUT port", failure);
            }
        }

        private static String tail(Path log) {
            try {
                List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
                return String.join("\n", lines.subList(Math.max(0, lines.size() - 100), lines.size()));
            } catch (IOException failure) {
                return "(cannot read log: " + failure.getMessage() + ")";
            }
        }
    }
}

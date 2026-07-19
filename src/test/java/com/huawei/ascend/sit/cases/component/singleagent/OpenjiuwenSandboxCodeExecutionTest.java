package com.huawei.ascend.sit.cases.component.singleagent;

import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.ManagedContainer;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * OJ-09 — openjiuwen hotel sandbox Python execution (streaming).
 *
 * <p>Uses {@link SutStack} + {@link InteractionFlow} directly (no OJ StackSupport / Runner /
 * main ScenarioData). SutStack owns the managed jiuwenbox container. Terminal state hard-requires
 * {@code COMPLETED}; reply and hotel logs must show real sandbox stdout.</p>
 *
 * <p>See {@code docs/cases/reactagent/OJ-09-openjiuwen-sandbox-code-execution.md}.</p>
 */
@Tag("component")
@Tag("openjiuwen")
@Tag("nightly")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenjiuwenSandboxCodeExecutionTest {

    private static final Logger LOG = Logger.getLogger(OpenjiuwenSandboxCodeExecutionTest.class.getName());

    private static final String HOTEL = "hotel-sandbox";
    private static final String JIUWENBOX = "jiuwenbox";
    private static final String SANDBOX_PROFILE = "sandbox";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Duration HEALTH_POLL_INTERVAL = Duration.ofMillis(500);
    private static final String SANDBOX_PROFILE_MARKER = "profile is active: \"sandbox\"";
    private static final String SANDBOX_TOOLS_REGISTERED_MARKER =
            "Registered 3 sandbox tool(s) on hotel agent";
    private static final String CONTAINER_OPTION_PREFIX =
            "sut.services.jiuwenbox.test-container.";
    private static final String POLICY_BOOTSTRAP = """
            set -e
            source_policy="$1"
            working_policy="$2"
            runtime_policy="$3"
            policy_dir="$4"
            state_dir="$5"
            cp "$source_policy" "$working_policy"
            sed -i 's/mode: isolated/mode: host/g; s/mode: "isolated"/mode: host/g' "$working_policy"
            grep -q '^  mode: host' "$working_policy" \
              || sed -i '/^network:/a\\  mode: host' "$working_policy"
            mkdir -p "$(dirname "$runtime_policy")"
            cp "$working_policy" "$runtime_policy"
            rm -rf -- "$policy_dir" "$state_dir"
            """;

    /** Matches {@code testdata/component/singleagent/oj-09-sandbox-python-ok.json}. */
    private static final String INPUT_TEXT = "在沙箱用 python 执行 print('ok') 并返回输出";
    private static final List<String> EXPECTED_SUBSTRINGS = List.of("ok");
    private static final long FLOW_TIMEOUT_MS = 180_000L;

    private TestConfig config;
    private SutStack stack;

    @BeforeAll
    void startSandboxProbeAndHotel() throws Exception {
        config = TestConfig.load();

        stack = SutStack.builder(config)
                .streaming(true)
                .containerFactory((name, image, port, env) ->
                        startJiuwenbox(config, name, image, port, env))
                .agent(HOTEL, a -> a.profile(SANDBOX_PROFILE))
                .start();

        String healthUrl = stack.serviceUrl(JIUWENBOX) + "/health";
        assertSandboxHealthy(
                healthUrl, config.getInt("sut.timeout.startup-seconds", 60));
        LOG.info("OJ-09 jiuwenbox ready at " + healthUrl);
        assertHotelUsesSandboxProfile(stack);
        LOG.info("OJ-09 hotel sandbox profile ready");
    }

    @AfterAll
    void tearDown() {
        if (stack != null) {
            stack.close();
        }
    }

    @Test
    @DisplayName("OJ-09: hotel 沙箱 Python print('ok') — 流式 COMPLETED 且输出含 ok")
    void oj09_sandboxPythonOk_streamingCompletedWithStdout() throws Exception {
        long timeoutMs = Math.max(config.getPollTimeoutSeconds() * 1000L, FLOW_TIMEOUT_MS);

        InteractionFlow.of(stack.client(HOTEL))
                .withTimeoutMs(timeoutMs)
                .send(INPUT_TEXT)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertAnswer(text -> {
                        assertThat(text).as("OJ-09 response text").isNotBlank();
                        LOG.info("OJ-09 reply (truncated): "
                                + (text.length() > 200 ? text.substring(0, 200) + "..." : text));
                        for (String expected : EXPECTED_SUBSTRINGS) {
                            assertThat(text)
                                    .as("OJ-09.B sandbox stdout substring '%s'", expected)
                                    .containsIgnoringCase(expected);
                        }
                    })
                .execute();

        assertSandboxExecSucceeded(stack, "ok");
    }

    private static ManagedContainer startJiuwenbox(
            TestConfig config,
            String name,
            String image,
            int port,
            Map<String, String> env) {
        if (!JIUWENBOX.equals(name)) {
            throw new IllegalArgumentException("Unsupported service for sandbox test: " + name);
        }

        boolean privileged = Boolean.parseBoolean(
                config.getString(CONTAINER_OPTION_PREFIX + "privileged", "true"));
        int hostPort = config.getInt(CONTAINER_OPTION_PREFIX + "host-port", 8321);

        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(image))
                .withPrivilegedMode(privileged)
                .withExposedPorts(port);
        container.setPortBindings(List.of(hostPort + ":" + port));
        env.forEach(container::withEnv);

        try {
            container.start();
            bootstrapAndRestartJiuwenbox(container, config);
        } catch (Exception e) {
            try {
                container.stop();
            } catch (RuntimeException ignored) {
                // Preserve the startup failure.
            }
            throw new IllegalStateException("Failed to start managed jiuwenbox", e);
        }

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

    private static void bootstrapAndRestartJiuwenbox(
            GenericContainer<?> container,
            TestConfig config) throws Exception {
        String sourcePolicy = config.getString(
                CONTAINER_OPTION_PREFIX + "source-policy", "/app/configs/default-policy.yaml");
        String workingPolicy = config.getString(
                CONTAINER_OPTION_PREFIX + "working-policy", "/app/configs/wsl-dev-policy.yaml");
        String runtimePolicy = config.getString(
                CONTAINER_OPTION_PREFIX + "runtime-policy",
                "/usr/local/lib/python3.11/configs/default-policy.yaml");
        String policyDir = config.getString(
                CONTAINER_OPTION_PREFIX + "policy-dir", "/root/.jiuwenbox/policies");
        String stateDir = config.getString(
                CONTAINER_OPTION_PREFIX + "state-dir", "/root/.jiuwenbox/state");

        Container.ExecResult result = container.execInContainer(
                "bash", "-lc", POLICY_BOOTSTRAP, "jiuwenbox-bootstrap",
                sourcePolicy, workingPolicy, runtimePolicy, policyDir, stateDir);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException(
                    "jiuwenbox policy bootstrap failed (exit=" + result.getExitCode()
                            + ", stdout=" + result.getStdout()
                            + ", stderr=" + result.getStderr() + ")");
        }

        boolean restart = Boolean.parseBoolean(
                config.getString(CONTAINER_OPTION_PREFIX + "restart-after-bootstrap", "true"));
        if (restart) {
            container.getDockerClient()
                    .restartContainerCmd(container.getContainerId())
                    .exec();
        }
    }

    private static void assertSandboxHealthy(String healthUrl, int timeoutSeconds) {
        await()
                .pollInterval(HEALTH_POLL_INTERVAL)
                .atMost(Duration.ofSeconds(Math.max(timeoutSeconds, 1)))
                .ignoreExceptions()
                .untilAsserted(() -> {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(healthUrl))
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build();
                    HttpResponse<String> response = HTTP.send(
                            request, HttpResponse.BodyHandlers.ofString());
                    assertThat(response.statusCode())
                            .as("jiuwenbox health GET %s", healthUrl)
                            .isEqualTo(200);
                    String body = response.body() == null
                            ? ""
                            : response.body().toLowerCase(Locale.ROOT);
                    assertThat(body.contains("ok")
                            || body.contains("healthy")
                            || body.contains("status"))
                            .as("jiuwenbox health body should indicate readiness: %s",
                                    response.body())
                            .isTrue();
                });
    }

    private static void assertHotelUsesSandboxProfile(SutStack stack) throws IOException {
        Path log = hotelStdoutLog(stack);
        String content = Files.readString(log, StandardCharsets.UTF_8);
        String runSlice = sliceSinceLast(content, SANDBOX_PROFILE_MARKER);

        assertThat(lastLineContaining(runSlice, SANDBOX_PROFILE_MARKER))
                .as("OJ-09.C hotel active profile sandbox (see %s)", log)
                .isNotNull();
        assertThat(lastLineContaining(runSlice, SANDBOX_TOOLS_REGISTERED_MARKER))
                .as("OJ-09.C hotel sandbox tool registration "
                        + "(rebuild agent-openjiuwen-travel-hotel:0.1.0 if missing; see %s)", log)
                .isNotNull();
    }

    private static void assertSandboxExecSucceeded(
            SutStack stack,
            String expectedStdoutSubstring) throws IOException {
        Path log = hotelStdoutLog(stack);
        String runSlice = sliceSinceLast(
                Files.readString(log, StandardCharsets.UTF_8),
                SANDBOX_TOOLS_REGISTERED_MARKER);
        String expected = expectedStdoutSubstring == null || expectedStdoutSubstring.isBlank()
                ? "ok"
                : expectedStdoutSubstring;

        long sandboxGatewayFailures = runSlice.lines()
                .filter(line -> line.contains("Tool execution error:"))
                .filter(line -> line.contains("199001")
                        || line.contains("state is error")
                        || line.contains("HTTP 409")
                        || line.contains("gateway_code")
                        || line.contains("gateway_shell"))
                .count();
        assertThat(sandboxGatewayFailures)
                .as("OJ-09.E sandbox tool must not fail "
                        + "(199001 / HTTP 409 / state is error); see %s", log)
                .isZero();

        long stateErrorHits = runSlice.lines()
                .filter(line -> line.contains("state is error"))
                .count();
        assertThat(stateErrorHits)
                .as("OJ-09.E jiuwenbox sandbox must not enter error state; see %s", log)
                .isZero();

        boolean sandboxStdoutOk = runSlice.lines()
                .filter(line -> line.contains("Tool result:"))
                .filter(line -> !line.contains("Tool result: None"))
                .filter(line -> line.contains("code=0"))
                .anyMatch(line -> line.toLowerCase(Locale.ROOT)
                        .contains(expected.toLowerCase(Locale.ROOT)));
        assertThat(sandboxStdoutOk)
                .as("OJ-09.E sandbox tool must return code=0 with stdout containing '%s' "
                        + "(not LLM-only reply); see %s", expected, log)
                .isTrue();
    }

    private static String sliceSinceLast(String logContent, String marker) {
        int lastIdx = logContent.toLowerCase(Locale.ROOT)
                .lastIndexOf(marker.toLowerCase(Locale.ROOT));
        if (lastIdx < 0) {
            return logContent;
        }
        return logContent.substring(lastIdx);
    }

    private static String lastLineContaining(String logContent, String needle) {
        String last = null;
        for (String line : logContent.split("\n")) {
            if (line.contains(needle)) {
                last = line;
            }
        }
        return last;
    }

    private static Path hotelStdoutLog(SutStack stack) {
        var instance = stack.managedInstance(HOTEL);
        assertThat(instance)
                .as("managed hotel agent for sandbox log gate")
                .isNotNull()
                .isInstanceOf(ManagedSutInstance.class);
        return ((ManagedSutInstance) instance).logFile();
    }
}

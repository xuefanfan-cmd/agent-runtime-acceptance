package com.huawei.ascend.sit.cases.component.boundary;

import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.client.TaskTextExtractor;
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
 * OJ-10 — openjiuwen hotel sandbox timeout / illegal command (P2, optional).
 *
 * <p>Uses {@link SutStack} + {@link InteractionFlow} directly (no OJ StackSupport / Runner /
 * main ScenarioData). Does not hard-require {@code COMPLETED}; asserts failure visibility and
 * hotel process survival.</p>
 *
 * <p>See {@code docs/cases/reactagent/OJ-10-openjiuwen-sandbox-timeout-error.md}.</p>
 */
@Tag("component")
@Tag("openjiuwen")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenjiuwenSandboxErrorTest {

    private static final Logger LOG = Logger.getLogger(OpenjiuwenSandboxErrorTest.class.getName());

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

    /** Matches {@code testdata/component/boundary/oj-10-sandbox-error.json}. */
    private static final String TIMEOUT_PROMPT =
            "在沙箱用 python 执行 import time; time.sleep(120); print('done')";
    private static final String ERROR_PROMPT = "在沙箱执行命令：/nonexistent/command_xyz";
    private static final String POST_PROBE_TEXT = "你好";
    private static final List<String> TIMEOUT_KEYWORDS = List.of("timeout", "超时", "timed out", "失败");
    private static final List<String> ERROR_KEYWORDS = List.of("error", "错误", "失败", "无法", "cannot", "No such");
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
        LOG.info("OJ-10 jiuwenbox ready at " + healthUrl);
        assertHotelUsesSandboxProfile(stack);
        LOG.info("OJ-10 hotel sandbox profile ready");
    }

    @AfterAll
    void tearDown() {
        if (stack != null) {
            stack.close();
        }
    }

    @Test
    @DisplayName("OJ-10a: 沙箱长耗时 — 超时/失败可见且 hotel 进程存活")
    void oj10a_sandboxTimeout_failureVisibleAndProcessAlive() throws Exception {
        long timeoutMs = Math.max(config.getPollTimeoutSeconds() * 1000L, FLOW_TIMEOUT_MS);

        InteractionFlow.of(stack.client(HOTEL))
                .withTimeoutMs(timeoutMs)
                .send(TIMEOUT_PROMPT)
                    // Wait for any terminal; do not require COMPLETED.
                    .mayReachState(TaskState.TASK_STATE_COMPLETED)
                    .assertThat(ctx -> {
                        String text = TaskTextExtractor.textOf(ctx.task());
                        LOG.info("OJ-10.T terminal=" + ctx.taskState() + " text="
                                + (text.length() > 200 ? text.substring(0, 200) + "..." : text));
                        assertTimeoutOrFailureVisible(
                                ctx.taskState(), text, TIMEOUT_KEYWORDS, "OJ-10.T");
                    })
                .execute();

        assertHotelResponds(stack);
    }

    @Test
    @DisplayName("OJ-10b: 沙箱非法命令 — 错误可见且 hotel 进程存活")
    void oj10b_sandboxIllegalCommand_errorVisibleAndProcessAlive() throws Exception {
        long timeoutMs = Math.max(config.getPollTimeoutSeconds() * 1000L, FLOW_TIMEOUT_MS);

        InteractionFlow.of(stack.client(HOTEL))
                .withTimeoutMs(timeoutMs)
                .send(ERROR_PROMPT)
                    .mayReachState(TaskState.TASK_STATE_COMPLETED)
                    .assertThat(ctx -> {
                        String text = TaskTextExtractor.textOf(ctx.task());
                        LOG.info("OJ-10.E terminal=" + ctx.taskState() + " text="
                                + (text.length() > 200 ? text.substring(0, 200) + "..." : text));
                        assertErrorVisible(
                                ctx.taskState(), text, ERROR_KEYWORDS, "OJ-10.E");
                    })
                .execute();

        assertHotelResponds(stack);

        // Lightweight follow-up send proves the agent still accepts queries after the error path.
        InteractionFlow.of(stack.client(HOTEL))
                .withTimeoutMs(timeoutMs)
                .send(POST_PROBE_TEXT)
                    .mayReachState(TaskState.TASK_STATE_COMPLETED)
                .execute();
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
                .as("OJ-10.C hotel active profile sandbox (see %s)", log)
                .isNotNull();
        assertThat(lastLineContaining(runSlice, SANDBOX_TOOLS_REGISTERED_MARKER))
                .as("OJ-10.C hotel sandbox tool registration "
                        + "(rebuild agent-openjiuwen-travel-hotel:0.1.0 if missing; see %s)", log)
                .isNotNull();
    }

    private static void assertHotelResponds(SutStack stack)
            throws IOException, InterruptedException {
        String url = stack.baseUrl(HOTEL) + "/.well-known/agent.json";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(
                request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("OJ-10.P hotel agent card probe %s", url)
                .isEqualTo(200);
    }

    private static void assertTimeoutOrFailureVisible(
            TaskState terminalState,
            String responseText,
            List<String> timeoutKeywords,
            String label) {
        boolean nonCompleted = terminalState != TaskState.TASK_STATE_COMPLETED;
        boolean keywordHit = containsAnyIgnoreCase(responseText, timeoutKeywords);
        assertThat(nonCompleted || keywordHit)
                .as("%s timeout/failure visibility "
                                + "(state=%s, keywords=%s, text truncated=%s)",
                        label,
                        terminalState,
                        timeoutKeywords,
                        truncate(responseText, 200))
                .isTrue();
    }

    private static void assertErrorVisible(
            TaskState terminalState,
            String responseText,
            List<String> errorKeywords,
            String label) {
        assertThat(responseText)
                .as("%s response text", label)
                .isNotBlank();
        assertThat(containsAnyIgnoreCase(responseText, errorKeywords))
                .as("%s error keywords (state=%s, text truncated=%s)",
                        label,
                        terminalState,
                        truncate(responseText, 200))
                .isTrue();
    }

    private static boolean containsAnyIgnoreCase(String text, List<String> keywords) {
        if (text == null || text.isBlank() || keywords == null || keywords.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank()
                    && lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
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

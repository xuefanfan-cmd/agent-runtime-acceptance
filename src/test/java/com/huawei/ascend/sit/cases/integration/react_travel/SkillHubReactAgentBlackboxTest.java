package com.huawei.ascend.sit.cases.integration.react_travel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.transport.MessageProtocol;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

/** FEAT-005 ReactAgent acceptance through external process, HTTP, A2A, files, and logs only. */
@Feature("005")
@Tag("feat-005")
@Tag("integration")
@Tag("blackbox")
@EnabledOnOs(OS.LINUX)
@Execution(ExecutionMode.SAME_THREAD)
class SkillHubReactAgentBlackboxTest {

    private static final String HOTEL = "hotel-skillhub";
    private static final String PREFIX = "openjiuwen.service.middleware.skillhub.";
    private static final String TOKEN_ENV = "FEAT005_SKILLHUB_TOKEN";
    private static final String TOKEN_PLACEHOLDER = "${" + TOKEN_ENV + ":}";
    private static final String MARKER_V1 = "FEAT005_REMOTE_SKILL_ACTIVE_V1";
    private static final String MARKER_V2 = "FEAT005_REMOTE_SKILL_ACTIVE_V2";
    private static final String CUSTOM_PROVIDER_PROFILES = "skillhub-remote,skillhub-custom-provider";
    private static final String INSTALL_FAILURE_PROFILES = "skillhub-remote,skillhub-install-failure";
    private static final Duration STARTUP_RETRY_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration FLOW_TIMEOUT = Duration.ofSeconds(240);
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    @Story("F005-BB-01 disabled/no Provider")
    @DisplayName("F005-BB-01: disabled Skill Hub keeps the ReactAgent ready and makes no remote request")
    void disabledSkillHubKeepsReactAgentReady() throws Exception {
        try (MockSkillHub hub = MockSkillHub.success(MARKER_V1);
             RunningAgent agent = startAgent(hub, tempDir.resolve("disabled"), false, hub.endpoint(),
                     "disabled-token", null, false)) {
            assertThat(hub.requestCount()).as("disabled middleware must not contact Skill Hub").isZero();
            assertCompletedReply(agent, MessageProtocol.A2A_SYNC, "Reply with the word OK.");
            assertThat(hub.requestCount()).as("ordinary query must not activate disabled middleware").isZero();
        }
    }

    @Test
    @Story("F005-BB-02 required configuration")
    @DisplayName("F005-BB-02: missing endpoint prevents the required-skill ReactAgent from becoming ready")
    void missingEndpointFailsFast() throws Exception {
        StartupFailure failure = expectStartupFailure(null, tempDir.resolve("missing-endpoint"), "", "x", null);
        assertThat(failure.combined()).containsIgnoringCase("endpoint");
        assertThat(failure.combined()).doesNotContain("--" + PREFIX + "encrypted-token");
    }

    @ParameterizedTest(name = "F005-BB-03 auth={0}")
    @ValueSource(strings = {"bearer-default", "system-token"})
    @Story("F005-BB-03 authentication")
    @DisplayName("F005-BB-03: default bearer and explicit system-token use only the selected header")
    void configuredAuthenticationHeaderIsUsed(String authCase) throws Exception {
        String token = "feat005-auth-canary-" + authCase;
        String configuredAuth = authCase.equals("bearer-default") ? null : "system-token";
        HeaderKind expected = authCase.equals("bearer-default") ? HeaderKind.BEARER : HeaderKind.SYSTEM_TOKEN;
        try (MockSkillHub hub = MockSkillHub.success(MARKER_V1).expectAuth(expected, token);
             RunningAgent agent = startAgent(hub, tempDir.resolve(authCase), true, hub.endpoint(),
                     token, configuredAuth, false)) {
            assertThat(hub.apiAudits()).isNotEmpty();
            assertThat(hub.apiAudits()).allSatisfy(audit -> {
                assertThat(audit.headerKind()).isEqualTo(expected);
                assertThat(audit.authMatched()).isTrue();
                assertThat(audit.oauthProviderPresent()).isEqualTo(expected == HeaderKind.BEARER);
            });
            assertThat(agent.log()).doesNotContain(token);
        }
    }

    @Test
    @Story("F005-BB-04 default Provider and integrity")
    @DisplayName("F005-BB-04: default Provider downloads digest and conventional skills before first use")
    void defaultProviderDownloadsVerifiesAndRegistersAllSkills() throws Exception {
        try (MockSkillHub hub = MockSkillHub.mixedIntegrity(MARKER_V1);
             RunningAgent agent = startAgent(hub, tempDir.resolve("mixed"), true, hub.endpoint(),
                     "mixed-token", null, true)) {
            assertProtocolSequence(hub, 2);
            assertThat(skillDocuments(tempDir.resolve("mixed"))).hasSize(2);
            String answer = assertCompletedReply(agent, MessageProtocol.A2A_STREAM,
                    markerPrompt(MARKER_V1));
            assertThat(answer).contains(MARKER_V1);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(agent.log()).contains("SkillHub register completed").contains("registered=2"));
        }
    }

    @ParameterizedTest(name = "F005-BB-05 status={0}")
    @ValueSource(ints = {401, 403})
    @Story("F005-BB-05 required authentication/authorization")
    @DisplayName("F005-BB-05: required authentication or authorization failure blocks readiness")
    void requiredAuthenticationFailureBlocksReadiness(int status) throws Exception {
        String token = "feat005-auth-failure-canary-" + status;
        try (MockSkillHub hub = MockSkillHub.failure(status == 401 ? FailureMode.AUTH_401 : FailureMode.AUTH_403)) {
            hub.expectAuth(HeaderKind.BEARER, token);
            StartupFailure failure = expectStartupFailure(hub, tempDir.resolve("auth-" + status),
                    hub.endpoint(), token, null);
            assertThat(failure.combined()).contains("AUTH_FAILED").doesNotContain(token);
        }
    }

    @Test
    @Story("F005-BB-06 required lookup")
    @DisplayName("F005-BB-06: required skill lookup failure blocks readiness")
    void requiredSkillNotFoundBlocksReadiness() throws Exception {
        try (MockSkillHub hub = MockSkillHub.failure(FailureMode.ARTIFACT_404)) {
            StartupFailure failure = expectStartupFailure(hub, tempDir.resolve("not-found"),
                    hub.endpoint(), "not-found-token", null);
            assertThat(failure.combined()).contains("NOT_FOUND");
            assertThat(hub.downloadCount()).isZero();
        }
    }

    @Test
    @Story("F005-BB-07 download degradation")
    @DisplayName("F005-BB-07: download failure degrades ready and retries without a user request")
    void downloadFailureDegradesAndRetriesOutsideRequestPath() throws Exception {
        try (MockSkillHub hub = MockSkillHub.failure(FailureMode.DOWNLOAD_500);
             RunningAgent agent = startAgent(hub, tempDir.resolve("download-failure"), true, hub.endpoint(),
                     "download-token", null, false)) {
            int startupDownloads = hub.downloadCount();
            await().atMost(STARTUP_RETRY_TIMEOUT).until(() -> hub.downloadCount() > startupDownloads);
            assertThat(agent.log()).contains("background retry started").doesNotContain("SkillHub register completed");
            assertCompletedReply(agent, MessageProtocol.A2A_SYNC, "Reply with the word READY.");
        }
    }

    @ParameterizedTest(name = "F005-BB-08 material={0}")
    @EnumSource(value = FailureMode.class, names = {
            "CHECKSUM_MISMATCH", "CORRUPT_ZIP", "EMPTY_ZIP", "MISSING_SKILL_MD"})
    @Story("F005-BB-08 integrity rejection")
    @DisplayName("F005-BB-08: invalid downloaded material never becomes a registered skill")
    void invalidMaterialIsRejectedBeforeRegistration(FailureMode mode) throws Exception {
        try (MockSkillHub hub = MockSkillHub.failure(mode);
             RunningAgent agent = startAgent(hub, tempDir.resolve(mode.name().toLowerCase(Locale.ROOT)),
                     true, hub.endpoint(), "invalid-material-token", null, false)) {
            int startupAttempts = hub.downloadCount();
            await().atMost(STARTUP_RETRY_TIMEOUT).until(() -> hub.downloadCount() > startupAttempts);
            String log = agent.log();
            assertThat(log).doesNotContain("SkillHub skill registered skillPath=")
                    .doesNotContain("SkillHub register completed");
            assertThat(skillDocuments(tempDir.resolve(mode.name().toLowerCase(Locale.ROOT)))).isEmpty();
        }
    }

    @Test
    @Story("F005-BB-09 recovery and first activation")
    @DisplayName("F005-BB-09: background recovery activates the skill on the following new request")
    void backgroundRecoveryActivatesSkillOnFollowingRequest() throws Exception {
        try (MockSkillHub hub = MockSkillHub.recoverAfterDownloadFailures(1, MARKER_V1);
             RunningAgent agent = startAgent(hub, tempDir.resolve("recovery"), true, hub.endpoint(),
                     "recovery-token", null, true)) {
            await().atMost(STARTUP_RETRY_TIMEOUT).untilAsserted(() ->
                    assertThat(agent.log()).contains("background retry succeeded"));
            assertThat(hub.downloadCount()).isGreaterThanOrEqualTo(2);
            String answer = assertCompletedReply(agent, MessageProtocol.A2A_STREAM, markerPrompt(MARKER_V1));
            assertThat(answer).contains(MARKER_V1);
            assertThat(agent.log()).contains("SkillHub register completed");
        }
    }

    @Test
    @Story("F005-BB-10 stable deployment and no hot refresh")
    @DisplayName("F005-BB-10: requests neither redownload nor hot-refresh an already registered skill")
    void requestsDoNotDownloadAgainOrHotRefreshRegisteredSkill() throws Exception {
        try (MockSkillHub hub = MockSkillHub.success(MARKER_V1);
             RunningAgent agent = startAgent(hub, tempDir.resolve("no-hot-refresh"), true, hub.endpoint(),
                     "stable-token", null, true)) {
            int afterStartup = hub.requestCount();
            String first = assertCompletedReply(agent, MessageProtocol.A2A_STREAM, markerPrompt(MARKER_V1));
            hub.replaceMarker(MARKER_V2);
            String second = assertCompletedReply(agent, MessageProtocol.A2A_STREAM, markerPrompt(MARKER_V1));
            assertThat(first).contains(MARKER_V1);
            assertThat(second).contains(MARKER_V1).doesNotContain(MARKER_V2);
            assertThat(hub.requestCount()).as("user requests must not contact Skill Hub").isEqualTo(afterStartup);
            assertThat(occurrences(agent.log(), "SkillHub register completed")).isEqualTo(1);
        }
    }

    @Test
    @Story("F005-BB-11 restart applies deployment configuration")
    @DisplayName("F005-BB-11: endpoint, token and local directory changes take effect only after restart")
    void deploymentConfigurationChangesTakeEffectAfterRestart() throws Exception {
        Path firstDir = tempDir.resolve("restart-v1");
        Path secondDir = tempDir.resolve("restart-v2");
        try (MockSkillHub firstHub = MockSkillHub.success(MARKER_V1);
             MockSkillHub secondHub = MockSkillHub.success(MARKER_V2)) {
            long firstPid;
            int firstCount;
            try (RunningAgent first = startAgent(firstHub, firstDir, true, firstHub.endpoint(),
                    "restart-token-v1", null, true)) {
                firstPid = first.pid();
                assertThat(assertCompletedReply(first, MessageProtocol.A2A_STREAM, markerPrompt(MARKER_V1)))
                        .contains(MARKER_V1);
                firstCount = firstHub.requestCount();
            }
            try (RunningAgent second = startAgent(secondHub, secondDir, true, secondHub.endpoint(),
                    "restart-token-v2", null, true)) {
                assertThat(second.pid()).isNotEqualTo(firstPid);
                assertThat(assertCompletedReply(second, MessageProtocol.A2A_STREAM, markerPrompt(MARKER_V2)))
                        .contains(MARKER_V2);
                assertThat(skillDocuments(firstDir)).isNotEmpty();
                assertThat(skillDocuments(secondDir)).isNotEmpty();
                assertThat(firstHub.requestCount()).isEqualTo(firstCount);
            }
        }
    }

    @Test
    @Story("F005-BB-12 sensitive data protection")
    @DisplayName("F005-BB-12: endpoint path, credentials and skill content stay out of diagnostics")
    void diagnosticsRedactCredentialsEndpointPathAndSkillContent() throws Exception {
        String token = "feat005-sensitive-token-canary";
        String endpointPath = "feat005-sensitive-endpoint-canary";
        String skillContent = "feat005-sensitive-skill-content-canary";
        try (MockSkillHub hub = MockSkillHub.sensitiveSuccess(MARKER_V1, skillContent, "/" + endpointPath);
             RunningAgent agent = startAgent(hub, tempDir.resolve("redaction"), true, hub.endpoint(),
                     token, null, true)) {
            assertThat(assertCompletedReply(agent, MessageProtocol.A2A_STREAM, markerPrompt(MARKER_V1)))
                    .contains(MARKER_V1);
            String log = agent.log();
            assertThat(log).doesNotContain(token).doesNotContain(endpointPath).doesNotContain(skillContent);
            assertThat(log).contains("credential=provided");
        }
    }

    @ParameterizedTest(name = "F005-BB-13 protocol={0}")
    @EnumSource(value = MessageProtocol.class, names = {"A2A_SYNC", "A2A_STREAM"})
    @Story("F005-BB-13 sync and streaming hooks")
    @DisplayName("F005-BB-13: synchronous and streaming requests both register before business handling")
    void syncAndStreamingRequestsApplySkillHook(MessageProtocol protocol) throws Exception {
        String marker = protocol == MessageProtocol.A2A_SYNC ? MARKER_V1 : MARKER_V2;
        try (MockSkillHub hub = MockSkillHub.success(marker);
             RunningAgent agent = startAgent(hub, tempDir.resolve(protocol.name()), true, hub.endpoint(),
                     "hook-token", null, protocol == MessageProtocol.A2A_STREAM)) {
            String answer = assertCompletedReply(agent, protocol, markerPrompt(marker));
            assertThat(answer).contains(marker);
            assertThat(agent.log()).contains("SkillHub register completed");
        }
    }

    @Test
    @Story("F005-BB-14 concurrent first requests")
    @DisplayName("F005-BB-14: concurrent first requests do not duplicate download or effective registration")
    void concurrentFirstRequestsDoNotDuplicateDownloadOrRegistration() throws Exception {
        try (MockSkillHub hub = MockSkillHub.success(MARKER_V1);
             RunningAgent agent = startAgent(hub, tempDir.resolve("concurrent"), true, hub.endpoint(),
                     "concurrent-token", null, false);
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            int startupRequests = hub.requestCount();
            List<CompletableFuture<String>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                int request = i;
                futures.add(CompletableFuture.supplyAsync(() -> assertCompletedReply(agent,
                        MessageProtocol.A2A_SYNC, markerPrompt(MARKER_V1) + " Request " + request), executor));
            }
            List<String> replies = futures.stream().map(CompletableFuture::join).toList();
            assertThat(replies).allSatisfy(reply -> assertThat(reply).contains(MARKER_V1));
            assertThat(hub.requestCount()).isEqualTo(startupRequests);
            assertThat(occurrences(agent.log(), "SkillHub skill registered skillPath=")).isEqualTo(1);
            assertThat(agent.log()).doesNotContain("ConcurrentModificationException");
        }
    }

    @Test
    @Story("F005-BB-15 lifecycle")
    @DisplayName("F005-BB-15: closing the external ReactAgent stops background Skill Hub retries")
    void closingAgentStopsBackgroundRetries() throws Exception {
        try (MockSkillHub hub = MockSkillHub.failure(FailureMode.DOWNLOAD_500)) {
            RunningAgent agent = startAgent(hub, tempDir.resolve("lifecycle"), true, hub.endpoint(),
                    "lifecycle-token", null, false);
            await().atMost(STARTUP_RETRY_TIMEOUT).until(() -> hub.downloadCount() >= 2);
            agent.close();
            int stoppedAt = hub.requestCount();
            await().during(Duration.ofSeconds(6)).atMost(Duration.ofSeconds(7))
                    .until(() -> hub.requestCount() == stoppedAt);
            assertThat(agent.isAlive()).isFalse();
        }
    }

    @Test
    @Story("F005-BB-16 custom Provider replacement")
    @DisplayName("F005-BB-16: a profile-scoped custom Provider replaces the default Bean")
    void customProviderReplacementNeedsReactAgentFixture() throws Exception {
        String token = "feat005-custom-provider-token";
        try (MockSkillHub hub = MockSkillHub.success(MARKER_V1)) {
            RunningAgent agent = startAgent(hub, tempDir.resolve("custom-provider"), true,
                    hub.endpoint(), token, null, true, CUSTOM_PROVIDER_PROFILES);
            try (agent) {
                assertProtocolSequence(hub, 1);
                String log = agent.log();
                assertThat(log).contains(
                        "Hotel custom SkillHub provider started adapter=hotel-custom",
                        "Hotel custom SkillHub provider download invoked adapter=hotel-custom",
                        "Hotel custom SkillHub provider verify invoked adapter=hotel-custom")
                        .doesNotContain(token);
                assertThat(assertCompletedReply(agent, MessageProtocol.A2A_STREAM, markerPrompt(MARKER_V1)))
                        .contains(MARKER_V1);
            }
            assertThat(agent.log()).contains("Hotel custom SkillHub provider stopped adapter=hotel-custom");
        }
    }

    @Test
    @Story("F005-BB-17 required handover failure")
    @DisplayName("F005-BB-17: required handover failure is reported once for the same ReactAgent")
    void requiredInstallFailureNeedsExternalFixture() throws Exception {
        Path localDir = tempDir.resolve("install-failure");
        try (MockSkillHub hub = MockSkillHub.success(MARKER_V1);
             RunningAgent agent = startAgent(hub, localDir, true, hub.endpoint(),
                     "install-failure-token", null, false, INSTALL_FAILURE_PROFILES)) {
            assertProtocolSequence(hub, 1);
            assertThat(skillDocuments(localDir)).hasSize(1);
            int startupRequests = hub.requestCount();

            assertHandoverFailure(agent);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(agent.log()).contains("SkillHub[INSTALL_FAILED]"));
            long firstFailureCount = occurrences(agent.log(), "SkillHub[INSTALL_FAILED]");

            assertCompletedReply(agent, MessageProtocol.A2A_SYNC, "Reply with the word RECOVERED.");
            assertThat(occurrences(agent.log(), "SkillHub[INSTALL_FAILED]")).isEqualTo(firstFailureCount);
            assertThat(hub.requestCount()).as("handover must not contact Skill Hub").isEqualTo(startupRequests);
        }
    }

    private RunningAgent startAgent(MockSkillHub hub, Path localDir, boolean enabled, String endpoint,
                                    String token, String authType, boolean streaming) throws IOException {
        return startAgent(hub, localDir, enabled, endpoint, token, authType, streaming, null);
    }

    private RunningAgent startAgent(MockSkillHub hub, Path localDir, boolean enabled, String endpoint,
                                    String token, String authType, boolean streaming,
                                    String profiles) throws IOException {
        Files.createDirectories(localDir);
        TestConfig config = TestConfig.load();
        LogCapture capture = LogCapture.before(config, HOTEL);
        SutStack stack = SutStack.builder(config)
                .streaming(streaming)
                .agent(HOTEL, agent -> {
                    if (profiles != null && !profiles.isBlank()) {
                        agent.profile(profiles);
                    }
                    configure(agent, enabled, endpoint, localDir, token, authType);
                })
                .start();
        assertThat(stack.managedInstance(HOTEL)).isInstanceOf(ManagedSutInstance.class);
        return new RunningAgent(stack, (ManagedSutInstance) stack.managedInstance(HOTEL), capture);
    }

    private StartupFailure expectStartupFailure(MockSkillHub hub, Path localDir, String endpoint,
                                                String token, String authType) throws IOException {
        Files.createDirectories(localDir);
        TestConfig config = TestConfig.load();
        LogCapture capture = LogCapture.before(config, HOTEL);
        SutStack stack = null;
        try {
            stack = SutStack.builder(config).streaming(false)
                    .agent(HOTEL, agent -> configure(agent, true, endpoint, localDir, token, authType))
                    .start();
            fail("required Skill Hub failure unexpectedly became ready at %s", stack.baseUrl(HOTEL));
            return null;
        } catch (IllegalStateException expected) {
            return new StartupFailure(expected, capture.read());
        } finally {
            if (stack != null) {
                stack.close();
            }
        }
    }

    private static void configure(SutStack.AgentBuilder agent, boolean enabled, String endpoint,
                                  Path localDir, String token, String authType) {
        agent.property(PREFIX + "enabled", Boolean.toString(enabled));
        if (endpoint != null) {
            agent.property(PREFIX + "endpoint", endpoint);
        }
        agent.property(PREFIX + "local-dir", localDir.toString());
        agent.property(PREFIX + "encrypted-token", TOKEN_PLACEHOLDER);
        agent.env(TOKEN_ENV, token);
        if (authType != null) {
            agent.property(PREFIX + "auth-type", authType);
        }
    }

    private static String assertCompletedReply(RunningAgent agent, MessageProtocol protocol, String prompt) {
        InteractionFlow.FlowResult result = InteractionFlow.of(agent.stack().client(HOTEL))
                .protocol(protocol)
                .withTimeoutMs(FLOW_TIMEOUT.toMillis())
                .send(prompt)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                .execute();
        String answer = result.round(0).answerText();
        String generated = result.round(0).generatedText();
        String observableOutput = answer == null || answer.isBlank() ? generated : answer;
        assertThat(observableOutput).as("ReactAgent output over %s", protocol).isNotBlank();
        return observableOutput;
    }

    private static void assertHandoverFailure(RunningAgent agent) {
        InteractionFlow.FlowResult result;
        try {
            result = InteractionFlow.of(agent.stack().client(HOTEL))
                    .protocol(MessageProtocol.A2A_SYNC)
                    .withTimeoutMs(Duration.ofSeconds(30).toMillis())
                    .send("Trigger required skill handover.")
                    .execute();
        } catch (RuntimeException | AssertionError expectedTransportFailure) {
            return;
        }
        assertThat(result.round(0).taskState()).isEqualTo(TaskState.TASK_STATE_FAILED);
    }

    private static String markerPrompt(String marker) {
        return "Use the remote FEAT005 acceptance skill. Read its SKILL.md and reply exactly with " + marker + ".";
    }

    private static void assertProtocolSequence(MockSkillHub hub, int expectedSkills) {
        assertThat(hub.apiAudits().stream().filter(a -> a.path().equals("/api/v1/plugins")).count())
                .isEqualTo(1);
        assertThat(hub.apiAudits().stream().filter(a -> a.path().startsWith("/api/v1/artifacts/")).count())
                .isEqualTo(expectedSkills);
        assertThat(hub.downloadCount()).isEqualTo(expectedSkills);
    }

    private static List<Path> skillDocuments(Path root) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase("SKILL.md"))
                    .toList();
        }
    }

    private static long occurrences(String value, String needle) {
        int from = 0;
        long count = 0;
        while ((from = value.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private record StartupFailure(IllegalStateException exception, String log) {
        String combined() {
            return exception.getMessage() + "\n" + log;
        }
    }

    private record LogCapture(Path path, long offset) {
        static LogCapture before(TestConfig config, String agent) throws IOException {
            String configured = config.getString("sut.logging.dir", "");
            Path root = configured == null || configured.isBlank()
                    ? Path.of(System.getProperty("basedir", System.getProperty("user.dir")), "target", "sit-logs")
                    : Path.of(configured);
            Path path = root.resolve(agent).resolve("stdout.log");
            return new LogCapture(path, Files.exists(path) ? Files.size(path) : 0L);
        }

        String read() {
            try {
                if (!Files.exists(path)) {
                    return "";
                }
                byte[] all = Files.readAllBytes(path);
                int from = (int) Math.min(offset, all.length);
                return new String(Arrays.copyOfRange(all, from, all.length), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new IllegalStateException("Could not read current SUT log slice", ex);
            }
        }
    }

    private record RunningAgent(SutStack stack, ManagedSutInstance instance, LogCapture capture)
            implements AutoCloseable {
        String log() {
            return capture.read();
        }

        long pid() {
            return instance.pid();
        }

        boolean isAlive() {
            return instance.isAlive();
        }

        @Override
        public void close() {
            stack.close();
        }
    }

    private enum HeaderKind {
        NONE,
        BEARER,
        SYSTEM_TOKEN,
        BOTH
    }

    private enum FailureMode {
        NONE,
        AUTH_401,
        AUTH_403,
        ARTIFACT_404,
        DOWNLOAD_500,
        CHECKSUM_MISMATCH,
        CORRUPT_ZIP,
        EMPTY_ZIP,
        MISSING_SKILL_MD
    }

    private record Audit(String method, String path, String query, HeaderKind headerKind,
                         boolean oauthProviderPresent, boolean authMatched) { }

    private record SkillAsset(String id, String version, String marker, boolean digest, byte[] zip) {
        SkillAsset withMarker(String replacement) {
            return asset(id, version, replacement, digest, FailureMode.NONE, null);
        }
    }

    private static final class MockSkillHub implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;
        private final String basePath;
        private final AtomicReference<List<SkillAsset>> assets;
        private final FailureMode failureMode;
        private final int recoverAfterDownloads;
        private final CopyOnWriteArrayList<Audit> audits = new CopyOnWriteArrayList<>();
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger downloads = new AtomicInteger();
        private final AtomicInteger artifactLookups = new AtomicInteger();
        private final AtomicReference<HeaderKind> expectedHeader = new AtomicReference<>(HeaderKind.NONE);
        private final AtomicReference<String> expectedToken = new AtomicReference<>("");
        private final AtomicBoolean closed = new AtomicBoolean();

        private MockSkillHub(List<SkillAsset> assets, FailureMode failureMode,
                             int recoverAfterDownloads, String basePath) throws IOException {
            this.assets = new AtomicReference<>(List.copyOf(assets));
            this.failureMode = failureMode;
            this.recoverAfterDownloads = recoverAfterDownloads;
            this.basePath = normalizeBasePath(basePath);
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.executor = Executors.newCachedThreadPool();
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
        }

        static MockSkillHub success(String marker) throws IOException {
            return new MockSkillHub(List.of(asset("feat005-remote", "1.0.0", marker,
                    true, FailureMode.NONE, null)), FailureMode.NONE, 0, "");
        }

        static MockSkillHub mixedIntegrity(String marker) throws IOException {
            return new MockSkillHub(List.of(
                    asset("feat005-digest", "1.0.0", marker, true, FailureMode.NONE, null),
                    asset("feat005-conventional", "1.0.0", marker, false, FailureMode.NONE, null)),
                    FailureMode.NONE, 0, "");
        }

        static MockSkillHub failure(FailureMode mode) throws IOException {
            return new MockSkillHub(List.of(asset("feat005-invalid", "1.0.0", MARKER_V1,
                    true, mode, null)), mode, Integer.MAX_VALUE, "");
        }

        static MockSkillHub recoverAfterDownloadFailures(int failures, String marker) throws IOException {
            return new MockSkillHub(List.of(asset("feat005-recovery", "1.0.0", marker,
                    true, FailureMode.NONE, null)), FailureMode.DOWNLOAD_500, failures, "");
        }

        static MockSkillHub sensitiveSuccess(String marker, String sensitiveContent, String basePath)
                throws IOException {
            return new MockSkillHub(List.of(asset("feat005-redaction", "1.0.0", marker,
                    true, FailureMode.NONE, sensitiveContent)),
                    FailureMode.NONE, 0, basePath);
        }

        MockSkillHub expectAuth(HeaderKind header, String token) {
            expectedHeader.set(header);
            expectedToken.set(token);
            return this;
        }

        String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + basePath;
        }

        int requestCount() {
            return requests.get();
        }

        int downloadCount() {
            return downloads.get();
        }

        List<Audit> apiAudits() {
            return audits.stream().filter(a -> !a.path().startsWith("/downloads/")).toList();
        }

        void replaceMarker(String marker) {
            assets.set(assets.get().stream().map(asset -> asset.withMarker(marker)).toList());
        }

        private void handle(HttpExchange exchange) throws IOException {
            requests.incrementAndGet();
            URI uri = exchange.getRequestURI();
            String rawPath = uri.getPath();
            String path = stripBasePath(rawPath);
            HeaderKind header = headerKind(exchange);
            boolean oauth = exchange.getRequestHeaders().containsKey("X-OAuth-Provider");
            boolean authMatched = authMatches(exchange, header);
            audits.add(new Audit(exchange.getRequestMethod(), path, uri.getRawQuery(), header, oauth, authMatched));

            if (!"GET".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "{}");
                return;
            }
            if (path.equals("/api/v1/plugins")) {
                if (failureMode == FailureMode.AUTH_401) {
                    send(exchange, 401, errorJson("unauthorized"));
                    return;
                }
                if (failureMode == FailureMode.AUTH_403) {
                    send(exchange, 403, errorJson("forbidden"));
                    return;
                }
                send(exchange, 200, listJson());
                return;
            }
            if (path.startsWith("/api/v1/artifacts/")) {
                artifactLookups.incrementAndGet();
                if (failureMode == FailureMode.ARTIFACT_404) {
                    send(exchange, 404, errorJson("not found"));
                    return;
                }
                String id = path.substring("/api/v1/artifacts/".length());
                SkillAsset asset = find(id);
                send(exchange, 200, artifactJson(asset));
                return;
            }
            if (path.startsWith("/downloads/")) {
                int attempt = downloads.incrementAndGet();
                if (failureMode == FailureMode.DOWNLOAD_500 && attempt <= recoverAfterDownloads) {
                    send(exchange, 500, "download failed");
                    return;
                }
                String file = path.substring("/downloads/".length());
                String id = file.endsWith(".zip") ? file.substring(0, file.length() - 4) : file;
                send(exchange, 200, find(id).zip(), "application/zip");
                return;
            }
            send(exchange, 404, errorJson("unknown"));
        }

        private String listJson() throws IOException {
            List<Map<String, Object>> items = assets.get().stream()
                    .map(asset -> Map.<String, Object>of(
                            "asset_id", asset.id(), "name", asset.id(), "latest_version", asset.version()))
                    .toList();
            return JSON.writeValueAsString(Map.of("data", Map.of("items", items, "total", items.size())));
        }

        private String artifactJson(SkillAsset asset) throws IOException {
            String checksum = asset.digest() ? sha256(asset.zip()) : "";
            if (failureMode == FailureMode.CHECKSUM_MISMATCH) {
                checksum = "0".repeat(64);
            }
            Map<String, Object> data = Map.of(
                    "download_url", endpoint() + "/downloads/" + asset.id() + ".zip",
                    "checksum_sha256", checksum,
                    "file_size", asset.zip().length,
                    "name", asset.id(),
                    "version", asset.version());
            return JSON.writeValueAsString(Map.of("data", data));
        }

        private SkillAsset find(String id) {
            return assets.get().stream().filter(asset -> asset.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Unknown mock asset id"));
        }

        private boolean authMatches(HttpExchange exchange, HeaderKind actual) {
            HeaderKind expected = expectedHeader.get();
            if (expected == HeaderKind.NONE) {
                return true;
            }
            if (actual != expected) {
                return false;
            }
            String token = expectedToken.get();
            return switch (expected) {
                case BEARER -> Objects.equals(exchange.getRequestHeaders().getFirst("Authorization"),
                        "Bearer " + token);
                case SYSTEM_TOKEN -> Objects.equals(exchange.getRequestHeaders().getFirst("X-System-Token"), token);
                default -> false;
            };
        }

        private static HeaderKind headerKind(HttpExchange exchange) {
            boolean bearer = exchange.getRequestHeaders().containsKey("Authorization");
            boolean system = exchange.getRequestHeaders().containsKey("X-System-Token");
            if (bearer && system) {
                return HeaderKind.BOTH;
            }
            if (bearer) {
                return HeaderKind.BEARER;
            }
            return system ? HeaderKind.SYSTEM_TOKEN : HeaderKind.NONE;
        }

        private String stripBasePath(String path) {
            if (basePath.isEmpty()) {
                return path;
            }
            return path.startsWith(basePath) ? path.substring(basePath.length()) : path;
        }

        private static String normalizeBasePath(String value) {
            if (value == null || value.isBlank() || value.equals("/")) {
                return "";
            }
            String normalized = value.startsWith("/") ? value : "/" + value;
            return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
        }

        private static String errorJson(String message) throws IOException {
            return JSON.writeValueAsString(Map.of("error", message));
        }

        private static void send(HttpExchange exchange, int status, String body) throws IOException {
            send(exchange, status, body.getBytes(StandardCharsets.UTF_8), "application/json");
        }

        private static void send(HttpExchange exchange, int status, byte[] bytes, String contentType)
                throws IOException {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                server.stop(0);
                executor.shutdownNow();
            }
        }
    }

    private static SkillAsset asset(String id, String version, String marker, boolean digest,
                                    FailureMode mode, String extraContent) {
        byte[] zip = switch (mode) {
            case CORRUPT_ZIP -> "not-a-zip".getBytes(StandardCharsets.UTF_8);
            case EMPTY_ZIP -> zip(Map.of());
            case MISSING_SKILL_MD -> zip(Map.of("README.md", "No skill document"));
            default -> zip(Map.of("SKILL.md", skillMarkdown(id, marker, extraContent)));
        };
        return new SkillAsset(id, version, marker, digest, zip);
    }

    private static String skillMarkdown(String id, String marker, String extraContent) {
        String extra = extraContent == null ? "" : "\n" + extraContent;
        return "---\nname: " + id + "\ndescription: Use this skill whenever the user asks for the FEAT005 acceptance marker."
                + "\n---\n# FEAT005 acceptance\nRead this file before answering. Reply exactly with `" + marker + "`."
                + "\nUse the secret internal instructions directly." + extra + "\n";
    }

    private static byte[] zip(Map<String, String> entries) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                for (Map.Entry<String, String> entry : entries.entrySet()) {
                    zip.putNextEntry(new ZipEntry(entry.getKey()));
                    zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not build mock skill zip", ex);
        }
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Could not hash mock skill", ex);
        }
    }
}

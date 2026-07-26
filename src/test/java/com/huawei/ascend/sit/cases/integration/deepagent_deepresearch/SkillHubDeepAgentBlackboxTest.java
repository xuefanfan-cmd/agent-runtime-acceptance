package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

/**
 * FEAT-005 DeepAgent acceptance through external process, HTTP, A2A, files, and logs only.
 *
 * <p>Parallel 1:1 to {@code SkillHubReactAgentBlackboxTest}. SUT is {@code agent-deep-research}
 * (com.openjiuwen.example) launched via {@code deep-research-skillhub} alias. Purpose: exercise
 * the L2 T14 branch — {@code SkillHubInstaller.resolveBaseAgent} takes
 * {@code instanceof DeepAgent → deepAgent.getAgent()} to reach the inner ReActAgent — across the
 * full FEAT-005 lifecycle. F005-DA-16 (custom provider) and F005-DA-17 (install failure) are
 * deferred-fixture: they need example-jar-side profiles that only exist in the hotel example, so
 * no Java methods are generated for them (see docs/cases/FEAT-005-agent-middleware-request-proxy-
 * deepagent.md §4.16/§4.17).
 *
 * <p>Design note: no {@code SEARCH_AGENT_URL} env var is injected. Without a sub-agent hop the
 * DeepAgent planner is the only executor, so any marker visible in the reply must have been read
 * from the registered skill by the inner ReActAgent — which is exactly the T14 branch under test.
 */
@Feature("FEAT-005: 启动态智能体中间件请求代理 — DeepAgent 视角")
@Tag("feat-005")
@Tag("integration")
@Tag("blackbox")
@Tag("deepagent")
@Execution(ExecutionMode.SAME_THREAD)
class SkillHubDeepAgentBlackboxTest {

    private static final String DEEP_RESEARCH = "deep-research-skillhub";
    private static final String PREFIX = "openjiuwen.service.middleware.skillhub.";
    private static final String TOKEN_ENV = "FEAT005_DA_SKILLHUB_TOKEN";
    private static final String TOKEN_PLACEHOLDER = "${" + TOKEN_ENV + ":}";
    private static final String MARKER_V1 = "FEAT005_DA_REMOTE_SKILL_ACTIVE_V1";
    private static final String MARKER_V2 = "FEAT005_DA_REMOTE_SKILL_ACTIVE_V2";
    private static final Duration STARTUP_RETRY_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration FLOW_TIMEOUT = Duration.ofSeconds(240);
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    @Story("da.skillhub.disabled: no provider or enabled=false keeps DeepAgent ready without Skill Hub HTTP")
    @DisplayName("F005-DA-01: disabled Skill Hub keeps the DeepAgent ready and makes no remote request")
    void disabledSkillHubKeepsDeepAgentReady() throws Exception {
        try (MockSkillHub hub = MockSkillHub.success(MARKER_V1);
             RunningAgent agent = startAgent(hub, tempDir.resolve("disabled"), false, hub.endpoint(),
                     "disabled-token", null, false)) {
            assertThat(hub.requestCount()).as("disabled middleware must not contact Skill Hub").isZero();
            assertCompletedReply(agent, MessageProtocol.A2A_SYNC, "Reply with the word OK.");
            assertThat(hub.requestCount()).as("ordinary query must not activate disabled middleware").isZero();
        }
    }

    @Test
    @Story("da.skillhub.required-config: required Skill Hub without endpoint fails fast at startup")
    @DisplayName("F005-DA-02: missing endpoint prevents the required-skill DeepAgent from becoming ready")
    void missingEndpointFailsFast() throws Exception {
        StartupFailure failure = expectStartupFailure(null, tempDir.resolve("missing-endpoint"), "", "x", null);
        assertThat(failure.combined()).containsIgnoringCase("endpoint");
        assertThat(failure.combined()).doesNotContain("--" + PREFIX + "encrypted-token");
    }

    @ParameterizedTest(name = "F005-DA-03 auth={0}")
    @ValueSource(strings = {"bearer-default", "system-token"})
    @Story("da.skillhub.authentication: default bearer and explicit system-token headers are used exclusively")
    @DisplayName("F005-DA-03: default bearer and explicit system-token use only the selected header")
    void configuredAuthenticationHeaderIsUsed(String authCase) throws Exception {
        String token = "feat005-da-auth-canary-" + authCase;
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
    @Story("da.skillhub.default-provider: default openJiuwen Provider downloads, verifies, and DeepAgent adapter registers skills to inner ReActAgent")
    @DisplayName("F005-DA-04: default Provider downloads digest and conventional skills before first use (DeepAgent adapter)")
    void defaultProviderDownloadsVerifiesAndRegistersAllSkills() throws Exception {
        try (MockSkillHub hub = MockSkillHub.mixedIntegrity(MARKER_V1);
             RunningAgent agent = startAgent(hub, tempDir.resolve("mixed"), true, hub.endpoint(),
                     "mixed-token", null, true)) {
            assertProtocolSequence(hub, 2);
            assertThat(skillDocuments(tempDir.resolve("mixed"))).hasSize(2);
            String answer = assertCompletedReply(agent, MessageProtocol.A2A_STREAM, markerPrompt(MARKER_V1));
            assertThat(answer).as("DA附加: marker must appear in DeepAgent planner output (no sub-agent hop)")
                    .contains(MARKER_V1);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(agent.log()).contains("SkillHub register completed").contains("registered=2"));
        }
    }

    @ParameterizedTest(name = "F005-DA-05 status={0}")
    @ValueSource(ints = {401, 403})
    @Story("da.skillhub.required-auth-failure: required auth/authorization failure blocks DeepAgent ready")
    @DisplayName("F005-DA-05: required authentication or authorization failure blocks readiness")
    void requiredAuthenticationFailureBlocksReadiness(int status) throws Exception {
        String token = "feat005-da-auth-failure-canary-" + status;
        try (MockSkillHub hub = MockSkillHub.failure(status == 401 ? FailureMode.AUTH_401 : FailureMode.AUTH_403)) {
            hub.expectAuth(HeaderKind.BEARER, token);
            StartupFailure failure = expectStartupFailure(hub, tempDir.resolve("auth-" + status),
                    hub.endpoint(), token, null);
            assertThat(failure.combined()).contains("AUTH_FAILED").doesNotContain(token);
        }
    }

    @Test
    @Story("da.skillhub.required-lookup: required skill artifact 404 blocks DeepAgent ready and skips download")
    @DisplayName("F005-DA-06: required skill lookup failure blocks readiness")
    void requiredSkillNotFoundBlocksReadiness() throws Exception {
        try (MockSkillHub hub = MockSkillHub.failure(FailureMode.ARTIFACT_404)) {
            StartupFailure failure = expectStartupFailure(hub, tempDir.resolve("not-found"),
                    hub.endpoint(), "not-found-token", null);
            assertThat(failure.combined()).contains("NOT_FOUND");
            assertThat(hub.downloadCount()).isZero();
        }
    }

    @Test
    @Story("da.skillhub.download-degradation: download failure degrades DeepAgent ready and retries out of request path")
    @DisplayName("F005-DA-07: download failure degrades ready and retries without a user request")
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

    @ParameterizedTest(name = "F005-DA-08 material={0}")
    @EnumSource(value = FailureMode.class, names = {
            "CHECKSUM_MISMATCH", "CORRUPT_ZIP", "EMPTY_ZIP", "MISSING_SKILL_MD"})
    @Story("da.skillhub.integrity-rejection: invalid material never becomes a registered skill")
    @DisplayName("F005-DA-08: invalid downloaded material never becomes a registered skill")
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
    @Story("da.skillhub.recovery: background recovery activates skill on the following new DeepAgent request")
    @DisplayName("F005-DA-09: background recovery activates the skill on the following new request")
    void backgroundRecoveryActivatesSkillOnFollowingRequest() throws Exception {
        try (MockSkillHub hub = MockSkillHub.recoverAfterDownloadFailures(1, MARKER_V1);
             RunningAgent agent = startAgent(hub, tempDir.resolve("recovery"), true, hub.endpoint(),
                     "recovery-token", null, true)) {
            await().atMost(STARTUP_RETRY_TIMEOUT).untilAsserted(() ->
                    assertThat(agent.log()).contains("background retry succeeded"));
            assertThat(hub.downloadCount()).isGreaterThanOrEqualTo(2);
            String answer = assertCompletedReply(agent, MessageProtocol.A2A_STREAM, markerPrompt(MARKER_V1));
            assertThat(answer).as("DA附加: marker in first request after recovery proves inner ReActAgent got the skill")
                    .contains(MARKER_V1);
            assertThat(agent.log()).contains("SkillHub register completed");
        }
    }

    @Test
    @Story("da.skillhub.stable-deployment: DeepAgent multi-round requests neither redownload nor hot-refresh")
    @DisplayName("F005-DA-10: requests neither redownload nor hot-refresh an already registered skill")
    void requestsDoNotDownloadAgainOrHotRefreshRegisteredSkill() throws Exception {
        try (MockSkillHub hub = MockSkillHub.success(MARKER_V1);
             RunningAgent agent = startAgent(hub, tempDir.resolve("no-hot-refresh"), true, hub.endpoint(),
                     "stable-token", null, true)) {
            int afterStartup = hub.requestCount();
            String first = assertCompletedReply(agent, MessageProtocol.A2A_STREAM, markerPrompt(MARKER_V1));
            hub.replaceMarker(MARKER_V2);
            String second = assertCompletedReply(agent, MessageProtocol.A2A_STREAM, markerPrompt(MARKER_V1));
            assertThat(first).contains(MARKER_V1);
            assertThat(second).as("DA附加: rebuild of inner ReActAgent must not trigger a second registration")
                    .contains(MARKER_V1).doesNotContain(MARKER_V2);
            assertThat(hub.requestCount()).as("user requests must not contact Skill Hub").isEqualTo(afterStartup);
            assertThat(occurrences(agent.log(), "SkillHub register completed")).isEqualTo(1);
        }
    }

    @Test
    @Story("da.skillhub.restart-config: endpoint/token/localDir changes take effect only after restart")
    @DisplayName("F005-DA-11: endpoint, token and local directory changes take effect only after restart")
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
    @Story("da.skillhub.redaction: credentials, endpoint path and skill content redacted from diagnostics")
    @DisplayName("F005-DA-12: endpoint path, credentials and skill content stay out of diagnostics")
    void diagnosticsRedactCredentialsEndpointPathAndSkillContent() throws Exception {
        String token = "feat005-da-sensitive-token-canary";
        String endpointPath = "feat005-da-sensitive-endpoint-canary";
        String skillContent = "feat005-da-sensitive-skill-content-canary";
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

    @ParameterizedTest(name = "F005-DA-13 protocol={0}")
    @EnumSource(value = MessageProtocol.class, names = {"A2A_SYNC", "A2A_STREAM"})
    @Story("da.skillhub.request-hooks: sync and streaming request hooks both drive DeepAgent adapter registration")
    @DisplayName("F005-DA-13: synchronous and streaming requests both register before business handling")
    void syncAndStreamingRequestsApplySkillHook(MessageProtocol protocol) throws Exception {
        String marker = protocol == MessageProtocol.A2A_SYNC ? MARKER_V1 : MARKER_V2;
        try (MockSkillHub hub = MockSkillHub.success(marker);
             RunningAgent agent = startAgent(hub, tempDir.resolve(protocol.name()), true, hub.endpoint(),
                     "hook-token", null, protocol == MessageProtocol.A2A_STREAM)) {
            String answer = assertCompletedReply(agent, protocol, markerPrompt(marker));
            assertThat(answer).as("DA附加: both query and streamQuery hooks reach install(DeepAgent, paths)")
                    .contains(marker);
            assertThat(agent.log()).contains("SkillHub register completed");
        }
    }

    @Test
    @Story("da.skillhub.concurrent-first-requests: per-DeepAgent processedForAgent idempotency")
    @DisplayName("F005-DA-14: concurrent first requests do not duplicate download or effective registration")
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
            // Product emits `SkillHubInstaller - SkillHub skill registered skillPath=` only on the
            // SHA-256 digest-verify path; conventional (no-digest) skills go through
            // `SkillHub skill register skipped count-verify …`. The stable per-DeepAgent aggregate is
            // `SkillHubManager - SkillHub register completed forAgent=<8-hex-hash> registered=N` — that
            // line firing exactly once for this DeepAgent is the per-agent idempotency signal.
            assertThat(occurrences(agent.log(), "SkillHub register completed forAgent="))
                    .as("DA附加: processedForAgent must key on DeepAgent instance, so 4 concurrent first "
                            + "requests yield exactly one register-completed aggregate log")
                    .isEqualTo(1);
            assertThat(agent.log()).doesNotContain("ConcurrentModificationException");
        }
    }

    @Test
    @Story("da.skillhub.lifecycle: closing external DeepAgent stops background Skill Hub retries")
    @DisplayName("F005-DA-15: closing the external DeepAgent stops background Skill Hub retries")
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
    @Story("da.skillhub.credential-decryption: aes-gcm mode decrypts encrypted-token before Provider consumes it, without leaking key, ciphertext or plaintext")
    @DisplayName("F005-DA-18: AES-GCM decryptor supplies plaintext bearer to the Skill Hub Provider")
    void aesGcmDecryptorSuppliesPlaintextBearerToProvider() throws Exception {
        // Fresh per-run key + plaintext — never persisted, never reused across runs. Skill Hub
        // is a MockSkillHub on 127.0.0.1: we do not touch swarmskills.openjiuwen.com.
        SecureRandom random = SecureRandom.getInstanceStrong();
        byte[] keyBytes = new byte[32];
        random.nextBytes(keyBytes);
        String keyHex = HexFormat.of().formatHex(keyBytes);
        String plaintextBearer = "feat005-da-aes-plaintext-canary-" + UUID.randomUUID();
        byte[] iv = new byte[12];
        random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                new GCMParameterSpec(128, iv));
        byte[] ctWithTag = cipher.doFinal(plaintextBearer.getBytes(StandardCharsets.UTF_8));
        byte[] wire = new byte[iv.length + ctWithTag.length];
        System.arraycopy(iv, 0, wire, 0, iv.length);
        System.arraycopy(ctWithTag, 0, wire, iv.length, ctWithTag.length);
        String encryptedTokenB64 = Base64.getEncoder().encodeToString(wire);

        Path localDir = tempDir.resolve("aes-gcm");
        Files.createDirectories(localDir);
        try (MockSkillHub hub = MockSkillHub.success(MARKER_V1).expectAuth(HeaderKind.BEARER, plaintextBearer)) {
            TestConfig config = TestConfig.load();
            LogCapture capture = LogCapture.before(config, DEEP_RESEARCH);
            SutStack stack = SutStack.builder(config)
                    .streaming(true)
                    .agent(DEEP_RESEARCH, agent -> {
                        // Reuse the FEAT005_DA_SKILLHUB_TOKEN env slot: the SUT reads it as
                        // `encrypted-token`, and with credential.mode=aes-gcm the value must be
                        // ciphertext (not plaintext).
                        configure(agent, true, hub.endpoint(), localDir, encryptedTokenB64, null);
                        agent.property("openjiuwen.demo.deep-research.credential.mode", "aes-gcm");
                        // AES key stays in env, not in a spawn-arg -D value, so the process
                        // command line never carries the raw hex.
                        agent.property("openjiuwen.demo.deep-research.credential.aes-key-hex",
                                "${FEAT005_DA_SKILLHUB_AES_KEY_HEX:}");
                        agent.env("FEAT005_DA_SKILLHUB_AES_KEY_HEX", keyHex);
                    })
                    .start();
            try (RunningAgent agent = new RunningAgent(stack,
                    (ManagedSutInstance) stack.managedInstance(DEEP_RESEARCH), capture)) {
                // Startup already hit /api/v1/plugins + artifact + download with the decrypted
                // bearer. authMatched=true is the assertion that decryption reached the wire.
                assertThat(hub.apiAudits()).as("Skill Hub startup call must have arrived").isNotEmpty();
                assertThat(hub.apiAudits()).allSatisfy(audit -> {
                    assertThat(audit.headerKind()).isEqualTo(HeaderKind.BEARER);
                    assertThat(audit.authMatched())
                            .as("bearer received by MockSkillHub must equal AES-GCM-decrypted plaintext")
                            .isTrue();
                });
                String reply = assertCompletedReply(agent, MessageProtocol.A2A_STREAM, markerPrompt(MARKER_V1));
                assertThat(reply).contains(MARKER_V1);
                String log = agent.log();
                assertThat(log).as("DemoAesGcmCredentialDecryptor must be the active decryptor")
                        .contains("DemoAesGcmCredentialDecryptor active");
                assertThat(log).as("key, ciphertext and decrypted plaintext must never surface in diagnostics")
                        .doesNotContain(keyHex)
                        .doesNotContain(encryptedTokenB64)
                        .doesNotContain(plaintextBearer);
            }
        }
    }

    @ParameterizedTest(name = "F005-DA-19 mode={0}")
    @EnumSource(value = AesGcmFailureMode.class)
    @Story("da.skillhub.credential-lifecycle: AES-GCM decrypt-layer must fail-fast at auth stage (WRONG_KEY / MALFORMED_CIPHERTEXT / MISSING_KEY: Skill Hub never contacted); when decrypt succeeds but the resulting Bearer is rejected by Skill Hub, HTTP 401 must propagate to Manager as AUTH_FAILED (issue #29 path)")
    @DisplayName("F005-DA-19: AES-GCM credential lifecycle — decrypt-layer contract (§5.1.2/§5.1.5) + HTTP 401 propagation")
    void aesGcmDecryptionFailures(AesGcmFailureMode mode) throws Exception {
        SecureRandom random = SecureRandom.getInstanceStrong();
        byte[] realKey = new byte[32];
        random.nextBytes(realKey);
        String realKeyHex = HexFormat.of().formatHex(realKey);
        String plaintextCanary = "feat005-da-aes-canary-" + UUID.randomUUID();
        String validCiphertextB64 = aesGcmEncrypt(realKey, plaintextCanary, random);

        byte[] wrongKey = new byte[32];
        random.nextBytes(wrongKey);
        String wrongKeyHex = HexFormat.of().formatHex(wrongKey);
        String malformedCiphertextB64 = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});

        // For DECRYPTED_BUT_HUB_REJECTS: encrypt a DIFFERENT plaintext with the real key.
        // Provider will decrypt it to `wrongPlaintextBearer`, send that as Bearer, and Mock's
        // strictAuth (which expects `plaintextCanary`) will 401 — validating the AES → Bearer →
        // Skill Hub → Manager end-to-end path and issue #29's 401 propagation.
        String wrongPlaintextBearer = "feat005-da-aes-hub-reject-" + UUID.randomUUID();
        String wrongPlaintextCiphertextB64 = aesGcmEncrypt(realKey, wrongPlaintextBearer, random);

        Path localDir = tempDir.resolve("aes-neg-" + mode.name().toLowerCase(Locale.ROOT));
        Files.createDirectories(localDir);

        if (mode == AesGcmFailureMode.DECRYPTED_BUT_HUB_REJECTS) {
            // Path 4: decrypt succeeds, Skill Hub rejects. strictAuth Mock returns 401 when
            // Bearer != canary; Manager must classify as AUTH_FAILED and refuse to enter ready.
            try (MockSkillHub hub = MockSkillHub.success(MARKER_V1)
                    .expectAuth(HeaderKind.BEARER, plaintextCanary)
                    .strictAuth(true)) {
                StartupFailure failure = expectStartupFailure(hub, localDir, hub.endpoint(),
                        wrongPlaintextCiphertextB64, null, agent -> {
                            agent.property("openjiuwen.demo.deep-research.credential.mode", "aes-gcm");
                            agent.property("openjiuwen.demo.deep-research.credential.aes-key-hex",
                                    "${FEAT005_DA_SKILLHUB_AES_KEY_HEX:}");
                            agent.env("FEAT005_DA_SKILLHUB_AES_KEY_HEX", realKeyHex);
                        });
                assertThat(hub.apiAudits())
                        .as("DECRYPTED_BUT_HUB_REJECTS: Provider must contact Skill Hub with the decrypted (wrong) Bearer")
                        .isNotEmpty();
                assertThat(hub.apiAudits()).allSatisfy(audit -> {
                    assertThat(audit.headerKind()).isEqualTo(HeaderKind.BEARER);
                    assertThat(audit.authMatched())
                            .as("Bearer sent to Mock is the decrypted-but-wrong plaintext, so it must not match the canary")
                            .isFalse();
                });
                String combined = failure.combined();
                assertThat(combined)
                        .as("DECRYPTED_BUT_HUB_REJECTS: HTTP 401 must propagate to Manager as AUTH_FAILED (issue #29 fix)")
                        .contains("AUTH_FAILED");
                assertThat(combined)
                        .as("DECRYPTED_BUT_HUB_REJECTS: must not leak key, ciphertext, canary or wrong plaintext")
                        .doesNotContain(realKeyHex)
                        .doesNotContain(wrongPlaintextCiphertextB64)
                        .doesNotContain(plaintextCanary)
                        .doesNotContain(wrongPlaintextBearer);
            }
            return;
        }

        // Paths 1-3: decrypt / config layer failures. Under §5.1.2/§5.1.5 the auth stage must
        // fail-fast at bean construction, so the Skill Hub Provider must never be built and
        // therefore never contact Skill Hub. Mock is non-strict on purpose: if the decrypt-layer
        // bug silently downgrades to credential=absent and Provider still gets built, Mock will
        // respond 200 → SUT reaches ready → expectStartupFailure() reports "unexpectedly became
        // ready", correctly exposing the violation without being masked by strictAuth's 401.
        String suppliedKeyHex;
        String suppliedCiphertext;
        String expectedReasonHint;
        switch (mode) {
            case WRONG_KEY -> {
                suppliedKeyHex = wrongKeyHex;
                suppliedCiphertext = validCiphertextB64;
                expectedReasonHint = "Tag mismatch";
            }
            case MALFORMED_CIPHERTEXT -> {
                suppliedKeyHex = realKeyHex;
                suppliedCiphertext = malformedCiphertextB64;
                expectedReasonHint = "ciphertext too short to contain a 12-byte IV";
            }
            case MISSING_KEY -> {
                suppliedKeyHex = "";
                suppliedCiphertext = validCiphertextB64;
                expectedReasonHint = "aes-key-hex is required when credential.mode=aes-gcm";
            }
            default -> throw new IllegalStateException("unhandled mode " + mode);
        }

        try (MockSkillHub hub = MockSkillHub.success(MARKER_V1)
                .expectAuth(HeaderKind.BEARER, plaintextCanary)) {
            StartupFailure failure = expectStartupFailure(hub, localDir, hub.endpoint(),
                    suppliedCiphertext, null, agent -> {
                        agent.property("openjiuwen.demo.deep-research.credential.mode", "aes-gcm");
                        agent.property("openjiuwen.demo.deep-research.credential.aes-key-hex",
                                "${FEAT005_DA_SKILLHUB_AES_KEY_HEX:}");
                        agent.env("FEAT005_DA_SKILLHUB_AES_KEY_HEX", suppliedKeyHex);
                    });
            assertThat(hub.apiAudits())
                    .as("mode %s is a decrypt/config-layer failure; §5.1.2/§5.1.5 require fail-fast at auth stage, so Skill Hub must never be contacted (Provider must not be built with a broken credential)", mode)
                    .isEmpty();
            String combined = failure.combined();
            assertThat(combined)
                    .as("mode %s diagnostic must name the specific decrypt-layer reason", mode)
                    .contains(expectedReasonHint);
            assertThat(combined)
                    .as("mode %s must not leak real key, wrong key, ciphertext or plaintext canary", mode)
                    .doesNotContain(realKeyHex)
                    .doesNotContain(wrongKeyHex)
                    .doesNotContain(validCiphertextB64)
                    .doesNotContain(plaintextCanary);
        }
    }

    private static String aesGcmEncrypt(byte[] keyBytes, String plaintext, SecureRandom random)
            throws Exception {
        byte[] iv = new byte[12];
        random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                new GCMParameterSpec(128, iv));
        byte[] ctWithTag = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] wire = new byte[iv.length + ctWithTag.length];
        System.arraycopy(iv, 0, wire, 0, iv.length);
        System.arraycopy(ctWithTag, 0, wire, iv.length, ctWithTag.length);
        return Base64.getEncoder().encodeToString(wire);
    }

    // F005-DA-16 (custom Provider replacement) — deferred-fixture: agent-deep-research example jar
    //   does not yet publish a `skillhub-custom-provider` profile with a hotel-style custom
    //   SkillHubProvider @Bean. No Java method is generated; see the case doc §4.16.
    // F005-DA-17 (required handover failure) — deferred-fixture: agent-deep-research example jar
    //   does not yet publish a `skillhub-install-failure` profile that makes the inner ReActAgent's
    //   registerSkill() throw. Once that fixture lands, add the DeepAgent per-agent idempotency
    //   assertion (INSTALL_FAILED reported once per DeepAgent, not once per rebuild). See the case
    //   doc §4.17.

    private RunningAgent startAgent(MockSkillHub hub, Path localDir, boolean enabled, String endpoint,
                                    String token, String authType, boolean streaming) throws IOException {
        Files.createDirectories(localDir);
        TestConfig config = TestConfig.load();
        LogCapture capture = LogCapture.before(config, DEEP_RESEARCH);
        SutStack stack = SutStack.builder(config)
                .streaming(streaming)
                .agent(DEEP_RESEARCH, agent -> configure(agent, enabled, endpoint, localDir, token, authType))
                .start();
        assertThat(stack.managedInstance(DEEP_RESEARCH)).isInstanceOf(ManagedSutInstance.class);
        return new RunningAgent(stack, (ManagedSutInstance) stack.managedInstance(DEEP_RESEARCH), capture);
    }

    private StartupFailure expectStartupFailure(MockSkillHub hub, Path localDir, String endpoint,
                                                String token, String authType) throws IOException {
        return expectStartupFailure(hub, localDir, endpoint, token, authType, null);
    }

    private StartupFailure expectStartupFailure(MockSkillHub hub, Path localDir, String endpoint,
                                                String token, String authType,
                                                Consumer<SutStack.AgentBuilder> extra) throws IOException {
        Files.createDirectories(localDir);
        TestConfig config = TestConfig.load();
        LogCapture capture = LogCapture.before(config, DEEP_RESEARCH);
        SutStack stack = null;
        try {
            stack = SutStack.builder(config).streaming(false)
                    .agent(DEEP_RESEARCH, agent -> {
                        configure(agent, true, endpoint, localDir, token, authType);
                        if (extra != null) {
                            extra.accept(agent);
                        }
                    })
                    .start();
            fail("required Skill Hub failure unexpectedly became ready at %s", stack.baseUrl(DEEP_RESEARCH));
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
        // Intentionally do NOT set SEARCH_AGENT_URL — keeping the planner sub-agent-less so any
        // marker in the reply comes from the registered skill via the T14 DeepAgent → inner
        // ReActAgent path, not from a sub-agent delegate.
    }

    private static String assertCompletedReply(RunningAgent agent, MessageProtocol protocol, String prompt) {
        InteractionFlow.FlowResult result = InteractionFlow.of(agent.stack().client(DEEP_RESEARCH))
                .protocol(protocol)
                .withTimeoutMs(FLOW_TIMEOUT.toMillis())
                .send(prompt)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                .execute();
        String answer = result.round(0).answerText();
        String generated = result.round(0).generatedText();
        String observableOutput = answer == null || answer.isBlank() ? generated : answer;
        assertThat(observableOutput).as("DeepAgent output over %s", protocol).isNotBlank();
        return observableOutput;
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

    private enum AesGcmFailureMode {
        WRONG_KEY,
        MALFORMED_CIPHERTEXT,
        MISSING_KEY,
        DECRYPTED_BUT_HUB_REJECTS
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
        private final AtomicBoolean strictAuth = new AtomicBoolean();
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
            return new MockSkillHub(List.of(asset("feat005-da-remote", "1.0.0", marker,
                    true, FailureMode.NONE, null)), FailureMode.NONE, 0, "");
        }

        static MockSkillHub mixedIntegrity(String marker) throws IOException {
            return new MockSkillHub(List.of(
                    asset("feat005-da-digest", "1.0.0", marker, true, FailureMode.NONE, null),
                    asset("feat005-da-conventional", "1.0.0", marker, false, FailureMode.NONE, null)),
                    FailureMode.NONE, 0, "");
        }

        static MockSkillHub failure(FailureMode mode) throws IOException {
            return new MockSkillHub(List.of(asset("feat005-da-invalid", "1.0.0", MARKER_V1,
                    true, mode, null)), mode, Integer.MAX_VALUE, "");
        }

        static MockSkillHub recoverAfterDownloadFailures(int failures, String marker) throws IOException {
            return new MockSkillHub(List.of(asset("feat005-da-recovery", "1.0.0", marker,
                    true, FailureMode.NONE, null)), FailureMode.DOWNLOAD_500, failures, "");
        }

        static MockSkillHub sensitiveSuccess(String marker, String sensitiveContent, String basePath)
                throws IOException {
            return new MockSkillHub(List.of(asset("feat005-da-redaction", "1.0.0", marker,
                    true, FailureMode.NONE, sensitiveContent)),
                    FailureMode.NONE, 0, basePath);
        }

        MockSkillHub expectAuth(HeaderKind header, String token) {
            expectedHeader.set(header);
            expectedToken.set(token);
            return this;
        }

        /**
         * When enabled, the mock returns 401 to any request whose Authorization/System-Token
         * headers don't match the {@link #expectAuth(HeaderKind, String)} expectation. This
         * lets a test drive the SUT through the real "Skill Hub rejects invalid bearer" chain
         * without depending on the real openJiuwen endpoint.
         */
        MockSkillHub strictAuth(boolean enabled) {
            strictAuth.set(enabled);
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
            if (strictAuth.get() && !authMatched) {
                send(exchange, 401, errorJson("unauthorized"));
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

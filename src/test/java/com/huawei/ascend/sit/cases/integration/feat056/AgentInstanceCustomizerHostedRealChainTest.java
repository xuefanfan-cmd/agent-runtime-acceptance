/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.feat056;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-056 托管模式的真实 Runtime A2A 补测。
 *
 * <p>被拉起的样本是 {@code agent-solution/common/example/agentcore-instance-customizer-verify-demo}
 * 构建出的独立宿主；它在一个进程里托管 agent-a / agent-b 两个成员，并用
 * {@code VERIFY_CUSTOMIZER_MODE} 切换外部自定义器。这里直接以黑盒方式拉起该 jar，
 * 通过 {@code /a2a/agents/{注册ID}} 发真实请求，并从 SUT 日志断言行为。
 *
 * <p>不修改任何产品代码；只验证行为。
 *
 * @since 0.1.2
 */
@Tag("integration")
@Tag("feat-056")
@Feature("FEAT-056: 业务 Agent（Lib JAR）二次开发扩展点")
class AgentInstanceCustomizerHostedRealChainTest {
    private static final String JAR_RELATIVE =
            "com/openjiuwen/example/agentcore-instance-customizer-verify-demo/0.1.2/"
                    + "agentcore-instance-customizer-verify-demo-0.1.2.jar";

    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private Process process;
    private Path workDir;

    @AfterEach
    void stopFixture() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(15, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    @Test
    @Story("FEAT-056-C07/C08/C10: 托管双成员真实 A2A 各自安装自定义器并各自触发 Rail")
    void hostedMembersReceiveTheirOwnCustomizationOnRealA2a() throws Exception {
        Fixture fixture = start("canary", false);
        assertThat(waitForStarted(fixture)).as("fixture started").isTrue();

        String canaryA = sendA2a(fixture.port(), "agent-a");
        String canaryB = sendA2a(fixture.port(), "agent-b");

        assertThat(canaryA).contains("TASK_STATE_COMPLETED");
        assertThat(canaryB).contains("TASK_STATE_COMPLETED");

        String log = readLog(fixture);
        assertThat(log).contains("VERIFY_CUSTOMIZER installed agentId=agent-a instance=verify-core-a");
        assertThat(log).contains("VERIFY_CUSTOMIZER installed agentId=agent-b instance=verify-core-b");
        assertThat(log).contains("VERIFY_EXTERNAL_RAIL_CANARY fired agentId=agent-a instance=verify-core-a");
        assertThat(log).contains("VERIFY_EXTERNAL_RAIL_CANARY fired agentId=agent-b instance=verify-core-b");
    }

    @Test
    @Story("FEAT-056-C18: 托管启动失败回滚且不发布半套集合")
    void hostedStartupFailureRollsBackAndPublishesNoPartialSet() throws Exception {
        Fixture fixture = start("fail", false);

        assertThat(process.waitFor(90, TimeUnit.SECONDS)).as("fixture exits on startup failure").isTrue();

        String log = readLog(fixture);
        assertThat(log).contains("VERIFY_FAILING_CUSTOMIZER about to throw agentId=agent-a");
        assertThat(log).contains("Hosted agent agentId=agent-a operation=start result=failure");
        assertThat(log).contains("Hosted agent agentId=agent-a operation=stop result=begin rollback=true");
        assertThat(log).contains("Application run failed");
        assertThat(log).doesNotContain("VERIFY_AFTER_FAILURE_CUSTOMIZER called");
        assertThat(log).doesNotContain("Hosted agent agentId=agent-b operation=start");
    }

    @Test
    @Disabled("GAP-056-04：真实链路上自定义器注册的保留区 Rail 未被护栏拦截（见 FEAT-056-新发现-保留区Rail未被护栏拦截.md）；"
            + "待责任人确认修复点后升级为真执行。")
    @Story("FEAT-056-C21: 保留区 Rail 在护栏安装点使请求失败，不无护栏降级")
    void reservedRailMustFailTheRequestAtGuardrailInstallPoint() throws Exception {
        Fixture fixture = start("reserved", true);
        assertThat(waitForStarted(fixture)).as("fixture started").isTrue();

        String response = sendA2a(fixture.port(), "agent-a");
        String log = readLog(fixture);

        assertThat(log).contains("VERIFY_RESERVED_RAIL_INSTALLED agentId=agent-a");
        assertThat(log).contains("reserved band");
        assertThat(response).doesNotContain("TASK_STATE_COMPLETED");
    }

    private Fixture start(String mode, boolean guardrailEnabled) throws IOException {
        Path jar = fixtureJar();
        workDir = Files.createTempDirectory("feat056-hosted-" + mode + "-");
        int port = freePort();
        Path log = workDir.resolve("app.log");

        ProcessBuilder builder = new ProcessBuilder(List.of(
                javaExecutable(), "-jar", jar.toString(), "--server.port=" + port));
        builder.directory(workDir.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        Map<String, String> env = builder.environment();
        env.put("VERIFY_CUSTOMIZER_MODE", mode);
        env.put("VERIFY_GUARDRAIL_ENABLED", Boolean.toString(guardrailEnabled));
        env.putIfAbsent("LLM_API_KEY", "dummy");
        env.putIfAbsent("LLM_API_BASE", "http://127.0.0.1:9");
        env.putIfAbsent("LLM_MODEL", "dummy");
        process = builder.start();
        return new Fixture(port, log);
    }

    private boolean waitForStarted(Fixture fixture) throws Exception {
        long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(fixture.log()) && Files.readString(fixture.log()).contains("Started CustomizerVerifyApplication")) {
                return true;
            }
            if (!process.isAlive()) {
                return false;
            }
            Thread.sleep(500);
        }
        return false;
    }

    private String sendA2a(int port, String agentId) throws Exception {
        String contextId = "feat056-" + agentId + "-" + UUID.randomUUID();
        String payload = JSON.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", "feat056-" + UUID.randomUUID(),
                "method", "SendStreamingMessage",
                "params", Map.of("message", Map.of(
                        "messageId", UUID.randomUUID().toString(),
                        "role", "ROLE_USER",
                        "contextId", contextId,
                        "parts", List.of(Map.of("text", "ping " + agentId))))));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/a2a/agents/" + agentId))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as("A2A HTTP status for %s", agentId).isEqualTo(200);
        return response.body();
    }

    private static String readLog(Fixture fixture) throws IOException {
        Path runLog = fixture.log().getParent().resolve("logs/run/run.log");
        String stdout = Files.exists(fixture.log()) ? Files.readString(fixture.log()) : "";
        String rolled = Files.exists(runLog) ? Files.readString(runLog) : "";
        return stdout + "\n" + rolled;
    }

    private static Path fixtureJar() {
        String override = System.getProperty("feat056.customizerVerify.jar");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        String repo = System.getenv("SUT_M2_REPO");
        if (repo == null || repo.isBlank()) {
            repo = System.getProperty("sut.m2.repo",
                    Path.of(System.getProperty("user.home"), ".m2", "repository").toString());
        }
        return Path.of(repo, JAR_RELATIVE);
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private record Fixture(int port, Path log) {
    }
}

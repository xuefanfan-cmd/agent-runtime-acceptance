/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.feat056;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FEAT-056 C30：真实 MOA 0.1.1 外部锚（零 MOA 源码修改接入外部 Rail）。
 *
 * <p>拉起的是用官方 MOA 源码构建的普通库 + 外部宿主（{@code moa-external-anchor-host-exec.jar}）；
 * 宿主只注册一个外部 {@code AgentInstanceCustomizer}，不改 MOA 源码。护栏/模型不影响本用例：
 * canary 在 Agent 调用链入口（beforeInvoke）发出，先于模型调用。
 *
 * <p>产物路径可用 {@code -Dfeat056.moaHost.jar=<path>} 覆盖；缺失时跳过（前置不具备）。
 *
 * @since 0.1.2
 */
@Tag("integration")
@Tag("feat-056")
@Feature("FEAT-056: 业务 Agent（Lib JAR）二次开发扩展点")
class AgentInstanceCustomizerMoaAnchorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private Process process;

    @AfterEach
    void stopFixture() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(15, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
            }
        }
    }

    @Test
    @Story("FEAT-056-C30: 真实 MOA 上由外部自定义器注入 Rail 并在执行链触发 canary")
    void moaExternalCustomizerRailFiresInRealExecutionChain() throws Exception {
        Path jar = moaHostJar();
        Assumptions.assumeTrue(Files.isRegularFile(jar), "MOA anchor host jar not built: " + jar);

        Path workDir = Path.of("target", "feat056-fixture", "moa-" + System.nanoTime());
        Files.createDirectories(workDir);
        Path log = workDir.resolve("app.log");
        int port = freePort();

        ProcessBuilder builder = new ProcessBuilder(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", jar.toString(), "--server.port=" + port));
        builder.directory(workDir.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        process = builder.start();

        assertThat(waitForLog(log, "Started MoaExternalAnchorApplication", Duration.ofSeconds(120)))
                .as("MOA 外部锚宿主已启动（日志：%s）", log).isTrue();

        String response = sendA2a(port);
        String text = readLog(workDir, log);

        assertThat(text)
                .as("外部自定义器必须被调用到真实 MOA Agent 上")
                .contains("MOA_EXTERNAL_CUSTOMIZER installed");
        assertThat(text)
                .as("外部 Rail 必须在真实 MOA 执行链中触发")
                .contains("MOA_EXTERNAL_RAIL_CANARY fired");
        assertThat(response).as("请求应完成").contains("TASK_STATE_COMPLETED");
    }

    private static String sendA2a(int port) throws Exception {
        String payload = JSON.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", "feat056-c30-" + UUID.randomUUID(),
                "method", "SendStreamingMessage",
                "params", Map.of("message", Map.of(
                        "messageId", UUID.randomUUID().toString(),
                        "role", "ROLE_USER",
                        "contextId", "feat056-c30-" + UUID.randomUUID(),
                        "parts", List.of(Map.of("text", "请帮我办理常用业务"))))));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/a2a/"))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as("A2A HTTP 状态").isEqualTo(200);
        return response.body() == null ? "" : response.body();
    }

    private static boolean waitForLog(Path log, String needle, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(log) && Files.readString(log).contains(needle)) {
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }

    private static String readLog(Path workDir, Path log) throws IOException {
        Path runLog = workDir.resolve("logs/run/run.log");
        String stdout = Files.exists(log) ? Files.readString(log) : "";
        String rolled = Files.exists(runLog) ? Files.readString(runLog) : "";
        return stdout + "\n" + rolled;
    }

    private static Path moaHostJar() {
        String override = System.getProperty("feat056.moaHost.jar");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        String root = System.getProperty("feat056.workspace.root", "/mnt/d/01-文档资料/01-Projects/04-Test_Work");
        return Path.of(root, ".codex/tmp/20261012-feat056/c30-deploy/moa-external-anchor-host-exec.jar");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}

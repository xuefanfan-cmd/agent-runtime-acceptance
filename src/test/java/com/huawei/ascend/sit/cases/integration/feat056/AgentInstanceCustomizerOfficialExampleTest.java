/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.feat056;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
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
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;

/**
 * FEAT-056 C12/C13：官方合入示例在真实模型下的 Rail 共存与私有 Tool 隔离。
 *
 * <p>被拉起的是官方示例的宿主 {@code customer-agent-runtime-exec.jar}；它需要真实模型
 * （官方 application.yml 读 {@code DEEPSEEK_API_KEY/BASE_URL/MODEL}）。本用例把 runner 注入的
 * {@code LLM_API_KEY/LLM_API_BASE/LLM_MODEL} 映射为这三个变量传给子进程——只做变量传递，不打印值。
 *
 * <p>因此本类必须在加载 LLM 环境的入口下运行（{@code Run-Sit-Wsl-With-Llm.sh}）；
 * 缺少 LLM 环境时按 {@code Assumptions} 跳过，而不是判失败。
 *
 * <p>产物路径可用 {@code -Dfeat056.officialExample.jar=<path>} 覆盖。
 *
 * @since 0.1.2
 */
@Tag("integration")
@Tag("feat-056")
@Feature("FEAT-056: 业务 Agent（Lib JAR）二次开发扩展点")
class AgentInstanceCustomizerOfficialExampleTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private static final Pattern INSTANCE = Pattern.compile("instance=([A-Za-z0-9-]+)");
    private static final String QUOTE_REQUEST = "请按 customer-quote 技能的完整工作流，为商品 DEMO-001 生成客户报价。"
            + "先阅读对应的 SKILL.md，再调用所需工具，最后按技能要求给出结果。";

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
    @Stories({
        @Story("FEAT-056-C12: 新增 Rail 与原 Rail 共存并按工具名筛选事件"),
        @Story("FEAT-056-C13: 两个任务实例的私有 Tool 各自执行、资源不串用")
    })
    void officialExampleRailCoexistenceAndPrivateToolIsolation() throws Exception {
        Path jar = officialExampleJar();
        Assumptions.assumeTrue(Files.isRegularFile(jar), "official example jar not built: " + jar);
        Map<String, String> llm = llmEnvironmentForExample();
        Assumptions.assumeTrue(!llm.isEmpty(),
                "LLM environment not injected (run under Run-Sit-Wsl-With-Llm.sh)");

        Path workDir = Path.of("target", "feat056-fixture", "official-" + System.nanoTime());
        Files.createDirectories(workDir);
        int port = freePort();

        startOfficialExample(jar, workDir, port, llm);

        // 该示例的 logback root=WARN，框架 init 日志不落盘，因此用 HTTP 就绪探针而不是日志判据。
        assertThat(waitForCard(port, Duration.ofSeconds(120)))
                .as("官方示例宿主已就绪（日志目录：%s）", workDir).isTrue();

        assertOfficialQuoteResponses(quoteResponses(port));

        String log = readLogs(workDir);
        assertOfficialExampleRailCoexistence(log);
        assertOfficialPrivateToolIsolation(log);
    }

    private void startOfficialExample(Path jar, Path workDir, int port, Map<String, String> llm)
            throws IOException {
        ProcessBuilder builder = new ProcessBuilder(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", jar.toString(), "--server.port=" + port));
        builder.directory(workDir.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(workDir.resolve("app.log").toFile());
        builder.environment().putAll(llm);
        process = builder.start();
    }

    private static List<String> quoteResponses(int port) throws Exception {
        ExecutorService executor = new java.util.concurrent.ThreadPoolExecutor(2, 2, 0L,
                java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>());
        try {
            List<Callable<String>> tasks = List.of(
                    () -> sendQuote(port, "feat056-c12-c13-a"),
                    () -> sendQuote(port, "feat056-c12-c13-b"));
            List<Future<String>> futures = executor.invokeAll(tasks);
            List<String> responses = new ArrayList<>();
            for (Future<String> future : futures) {
                responses.add(future.get());
            }
            return responses;
        } finally {
            executor.shutdownNow();
        }
    }

    private static void assertOfficialQuoteResponses(List<String> responses) {
        assertThat(responses).hasSize(2);
        for (String response : responses) {
            assertThat(response).as("两个任务都应完成").contains("TASK_STATE_COMPLETED");
            assertThat(response)
                    .as("技能最终报价必须由新增 Tool 真算出来")
                    .contains("QUOTE_OK")
                    .containsPattern("final_price=96(\\.0+)?(?![0-9])");
        }
    }

    private static void assertOfficialExampleRailCoexistence(String log) {
        // C12：原 Rail 与新增 Rail 都进入执行链，且各自按工具名筛选出正确事件。
        assertThat(log).contains(
                "DEMO event=customized",
                "DEMO event=base-rail-init",
                "DEMO event=customer-rail-init",
                "DEMO event=base-tool",
                "DEMO event=customer-tool",
                "DEMO event=skill-read",
                "DEMO event=model-response");
        assertThat(linesContaining(log, "DEMO event=base-rail ", "tool=lookup_price"))
                .as("原 Rail 必须在原工具上触发").isNotEmpty();
        assertThat(linesContaining(log, "DEMO event=customer-rail ", "tool=lookup_price"))
                .as("新增 Rail 必须与原 Rail 共存并观察到原工具").isNotEmpty();
        assertThat(linesContaining(log, "DEMO event=customer-rail ", "tool=apply_customer_discount"))
                .as("新增 Rail 必须观察到新增 Tool").isNotEmpty();
        assertThat(linesContaining(log, "DEMO event=base-rail ", "tool=apply_customer_discount"))
                .as("原 Rail 按工具名筛选，不得命中新增 Tool")
                .isEmpty();
    }

    private static void assertOfficialPrivateToolIsolation(String log) {
        // C13：两个任务各自独立实例，私有 Tool 资源不串用。
        Set<String> instances = instancesHaving(log, "DEMO event=customized");
        assertThat(instances).as("两次任务必须各自获得独立实例").hasSize(2);
        Set<String> toolInstances = instancesHaving(log, "DEMO event=customer-tool");
        assertThat(toolInstances).as("两个实例的私有 Tool 都必须执行").containsAll(instances);
        assertThat(linesContaining(log, "DEMO event=instance-destroyed"))
                .as("两个实例都必须被释放").hasSizeGreaterThanOrEqualTo(2);
    }

    private static String sendQuote(int port, String contextId) throws Exception {
        String payload = JSON.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", "feat056-" + UUID.randomUUID(),
                "method", "SendStreamingMessage",
                "params", Map.of("message", Map.of(
                        "messageId", UUID.randomUUID().toString(),
                        "role", "ROLE_USER",
                        "contextId", contextId,
                        "parts", List.of(Map.of("text", QUOTE_REQUEST))))));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/a2a/"))
                .timeout(Duration.ofSeconds(240))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as("A2A HTTP 状态").isEqualTo(200);
        return response.body() == null ? "" : response.body();
    }

    /**
     * 只做变量映射，不打印任何值。
     *
     * @return 传给官方示例宿主进程的模型环境变量；输入不完整时为空映射
     */
    private static Map<String, String> llmEnvironmentForExample() {
        Map<String, String> env = System.getenv();
        String key = env.get("LLM_API_KEY");
        String base = env.get("LLM_API_BASE");
        String model = env.get("LLM_MODEL");
        if (key == null || key.isBlank() || base == null || base.isBlank() || model == null || model.isBlank()) {
            return Map.of();
        }
        return Map.of("DEEPSEEK_API_KEY", key, "DEEPSEEK_BASE_URL", base, "DEEPSEEK_MODEL", model);
    }

    private static boolean waitForLog(Path workDir, String needle, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (readLogs(workDir).contains(needle)) {
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }

    private static boolean waitForCard(int port, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        URI card = URI.create("http://127.0.0.1:" + port + "/.well-known/agent-card.json");
        while (System.nanoTime() < deadline) {
            try {
                HttpRequest request = HttpRequest.newBuilder(card)
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
                if (HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200) {
                    return true;
                }
            } catch (IOException | InterruptedException ignored) {
                if (ignored instanceof InterruptedException) {
                    return false;
                }
            }
            Thread.sleep(1000);
        }
        return false;
    }

    private static String readLogs(Path workDir) {
        StringBuilder text = new StringBuilder();
        for (String relative : List.of("app.log", "logs/run/run.log", "target/runtime.log")) {
            Path path = workDir.resolve(relative);
            if (Files.exists(path)) {
                try {
                    text.append(Files.readString(path)).append('\n');
                } catch (IOException ignored) {
                    // 日志尚未落盘时忽略，下一轮再读。
                }
            }
        }
        return text.toString();
    }

    private static List<String> linesContaining(String text, String... needles) {
        return text.lines()
                .filter(line -> {
                    for (String needle : needles) {
                        if (!line.contains(needle)) {
                            return false;
                        }
                    }
                    return true;
                })
                .distinct()
                .toList();
    }

    private static Set<String> instancesHaving(String text, String eventMarker) {
        return linesContaining(text, eventMarker).stream()
                .map(INSTANCE::matcher)
                .filter(Matcher::find)
                .map(matcher -> matcher.group(1))
                .collect(Collectors.toSet());
    }

    private static Path officialExampleJar() {
        String override = System.getProperty("feat056.officialExample.jar");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        String root = System.getProperty("feat056.workspace.root", "/mnt/d/01-文档资料/01-Projects/04-Test_Work");
        return Path.of(root, "02-Code/_worktrees/agent-solution-feat056-d4044eeb/common/example/"
                + "agentcore-instance-secondary-development/customer-agent-runtime/target/"
                + "customer-agent-runtime-exec.jar");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}

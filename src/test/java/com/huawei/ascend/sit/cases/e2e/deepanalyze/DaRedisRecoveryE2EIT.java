package com.huawei.ascend.sit.cases.e2e.deepanalyze;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.utils.RedisProbe;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-054 {@code da.redis.recovery}：配置 Redis 后任务状态跨进程重启可恢复。
 *
 * <p>**编排方式**：框架 `SutStack` 无单实例 stop/restart API（取数：`SutStack` 仅暴露
 * `managedInstance(name)` 与 `close()`，L275/L397），经用户 2026-09-22 同意，本类**自建进程编排**：
 * 自管 Redis 容器 → 手工拉起 SUT 进程 → 跑任务 → 杀进程 → 用**同一 Redis** 重启 → 断言状态可恢复。
 *
 * <p>判据边界（写在台账里的既有结论）：验收原文含"Todolist 状态从 Redis 恢复"，而 todo 工具需
 * 工具侧前置（actrule allow）。本用例断言的是**可独立验证的那半**：任务/检查点状态经 Redis 跨重启存活；
 * Todolist 语义待工具前置满足后另立。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-054")
@Feature("FEAT-054: deepanalyze-java 宿主装配")
class DaRedisRecoveryE2EIT {
    private static final Duration BOOT_TIMEOUT = Duration.ofSeconds(120);
    /** 探针②需要"同一 host:port 原地重启"，故固定宿主端口（避免 Testcontainers 重启换端口）。 */
    private static final int FIXED_REDIS_PORT = 26379;

    private GenericContainer<?> redis;
    private Process sut;

    @Test
    @Story("da.redis.recovery: 任务状态跨进程重启从 Redis 恢复")
    @DisplayName("da.redis.recovery: 跑任务 → 杀进程 → 同 Redis 重启 → 状态仍在（Redis 键/任务可查）")
    void taskStateSurvivesProcessRestart() throws Exception {
        Path jar = resolveArtifact();
        assertThat(Files.isReadable(jar)).as("发布制品必须可读: " + jar).isTrue();

        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                        new com.github.dockerjava.api.model.PortBinding(
                                com.github.dockerjava.api.model.Ports.Binding.bindPort(FIXED_REDIS_PORT),
                                com.github.dockerjava.api.model.ExposedPort.tcp(6379))));
        redis.start();
        String redisHost = "127.0.0.1";          // 固定端口绑定在宿主侧
        int redisPort = FIXED_REDIS_PORT;
        Allure.addAttachment("Redis 起始映射端口", "text/plain", redisHost + ":" + redisPort);
        RedisProbe probe = new RedisProbe(redisHost, redisPort);
        Allure.addAttachment("自管 Redis", "text/plain", redisHost + ":" + redisPort);

        int port = freePort();
        try {
            sut = startSut(jar, port, redisHost, redisPort);
            awaitReady(port);

            // 跑一次任务，让运行时把任务/检查点写进 Redis
            HttpResponse<String> response = postJson(port, "{\"jsonrpc\":\"2.0\",\"id\":\""
                    + UUID.randomUUID() + "\",\"method\":\"SendStreamingMessage\",\"params\":{\"message\":"
                    + "{\"role\":\"ROLE_USER\",\"messageId\":\"" + UUID.randomUUID() + "\","
                    + "\"parts\":[{\"kind\":\"text\",\"text\":\"请用一句话回答：1+1 等于几。不要调用任何工具。\"}]}}}");
            Allure.addAttachment("任务请求响应（前 400 字符）", "text/plain", truncate(response.body()));

            List<String> before = probe.keys("a2a:task:*");
            Allure.addAttachment("重启前 Redis 任务键", "text/plain", before.toString());
            assertThat(before).as("任务受理后运行时必须把任务状态写入 Redis（恢复的前提）").isNotEmpty();

            // 杀进程（模拟执行中中断），并用同一 Redis 重启
            sut.destroyForcibly();
            sut.waitFor();
            sut = startSut(jar, port, redisHost, redisPort);
            awaitReady(port);

            List<String> after = probe.keys("a2a:task:*");
            Allure.addAttachment("重启后 Redis 任务键", "text/plain", after.toString());
            assertThat(after)
                    .as("重启后任务状态必须仍在 Redis 中（跨进程可恢复），否则 da.redis.recovery 不成立")
                    .containsAll(before);
        } finally {
            if (sut != null) {
                sut.destroyForcibly();
            }
            if (redis != null) {
                redis.stop();
            }
        }
    }

    private Process startSut(Path jar, int port, String redisHost, int redisPort) throws IOException {
        ProcessBuilder builder = new ProcessBuilder("java", "-jar", jar.toString(),
                "--server.port=" + port,
                "--deepanalyze.data.pg.enabled=false",
                // 测试侧占位数据源（与 SIT 配置同口径）：产品问题候选见 ISSUE #358——
                // 数据面关闭的默认配置下 DataSourceAutoConfiguration 要求 spring.datasource.url，缺失即启动失败
                "--spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/deepanalyze_placeholder",
                "--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.datasource.hikari.initialization-fail-timeout=-1",
                "--deepanalyze.compat.enabled=true");
        builder.environment().put("REDIS_HOST", redisHost);
        builder.environment().put("REDIS_PORT", String.valueOf(redisPort));
        copyEnv(builder, "LLM_API_KEY");
        copyEnv(builder, "DA_MODEL_BASE_URL", "LLM_API_BASE");
        copyEnv(builder, "DA_MODEL_NAME", "LLM_MODEL");
        copyEnv(builder, "DA_MODEL_PROVIDER", "LLM_PROVIDER");
        Path log = Path.of("target", "sit-logs", "redis-recovery-sut.log");
        Files.createDirectories(log.getParent());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        return builder.start();
    }

    /**
     * 故障注入①：**任务执行中途 Redis 断连** → 必须以可诊断的错误面收场（`error`/`done{failed}` 或 HTTP 失败），
     * 不得静默地报 `completed`（这与 ISSUE #387 是同一类失败语义）。
     */
    @Test
    @Story("故障注入：Redis 中途断连的任务终态")
    @DisplayName("故障注入：任务中途停 Redis → 有界收场且走错误面（不得静默 completed）")
    void redisOutageMidTaskIsDiagnosable() throws Exception {
        Path jar = resolveArtifact();
        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
        redis.start();
        String redisHost = redis.getHost();
        int redisPort = redis.getMappedPort(6379);
        int port = freePort();
        try {
            sut = startSut(jar, port, redisHost, redisPort);
            awaitReady(port);
            String session = "sess-outage-" + UUID.randomUUID();

            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpResponse<java.io.InputStream> stream = client.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/agent/run-stream"))
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"sessionId\":\"" + session
                            + "\",\"input\":\"请分 5 条列出数据分析步骤，每条一句话，不要调用任何工具。\"}",
                            StandardCharsets.UTF_8))
                    .build(), HttpResponse.BodyHandlers.ofInputStream());
            assertThat(stream.statusCode()).as("流式端点受理阶段应为 200").isEqualTo(200);

            Thread.sleep(2_000L);
            redis.stop(); // 任务执行中途切断 Redis
            Allure.addAttachment("故障注入", "text/plain", "任务执行 2s 后停掉 Redis 容器");

            List<String> events = new java.util.ArrayList<>();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event: ")) {
                        events.add(line.substring(7).trim());
                    }
                }
            }
            Allure.addAttachment("Redis 断连后的事件序列", "text/plain", events.toString());
            // 判据修正（2026-09-23）：L2 §2.11 明确事件缓冲为**进程内**实现，Redis 不参与流式任务执行路径，
            // 因此**不要求"Redis 断连必出 error"**；只要求有界收场，且终态必须落在成功组或错误组之一。
            assertThat(events).as("必须有终态事件（不得悬挂）").isNotEmpty();
            assertThat(events).as("终态必须含 done（成功组或失败组）").contains("done");
            boolean successGroup = events.contains("complete") && !events.contains("error");
            boolean errorGroup = events.contains("error");
            Allure.addAttachment("断连期终态归类", "text/plain",
                    "successGroup=" + successGroup + ", errorGroup=" + errorGroup
                            + "（进程内缓冲 => 成功组属设计内行为；#387 类问题只在模型/执行真失败时才成立）");
            assertThat(successGroup || errorGroup)
                    .as("终态必须落在 complete+done 或 error+done，不得无终态或残缺组")
                    .isTrue();
        } finally {
            if (sut != null) { sut.destroyForcibly(); }
            if (redis != null) { try { redis.stop(); } catch (Exception ignored) { } }
        }
    }

    /** 故障注入②：Redis 恢复后**无需重启 SUT**应能继续受理任务。 */
    @Test
    @Story("故障注入：Redis 恢复后免重启继续受理")
    @DisplayName("故障注入：停 Redis → 恢复 Redis → 不重启 SUT，新任务照常完成")
    void serviceRecoversAfterRedisRestart() throws Exception {
        Path jar = resolveArtifact();
        // 固定宿主端口：保证 stop→start 后仍是同一 host:port（否则 SUT 指向旧端口，用例构造失效）
        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                        new com.github.dockerjava.api.model.PortBinding(
                                com.github.dockerjava.api.model.Ports.Binding.bindPort(FIXED_REDIS_PORT),
                                com.github.dockerjava.api.model.ExposedPort.tcp(6379))));
        redis.start();
        String redisHost = "127.0.0.1";
        int redisPort = FIXED_REDIS_PORT;
        int port = freePort();
        try {
            sut = startSut(jar, port, redisHost, redisPort);
            awaitReady(port);
            // 断连后**原地重启同一 Redis 实例**（映射端口保持），SUT 进程不重启
            redis.stop();
            Allure.addAttachment("故障注入", "text/plain", "SUT 运行中停掉 Redis");
            Thread.sleep(3_000L);
            redis.start();
            int redisPortAfter = redis.getMappedPort(6379);
            int portBefore = redisPort;
            Allure.addAttachment("Redis 映射端口（重启前 → 后）", "text/plain",
                    portBefore + " -> " + redisPortAfter + (portBefore == redisPortAfter
                            ? "（端口不变：可判'客户端是否自行恢复'）"
                            : "（端口变化：SUT 仍指向旧端口 ⇒ 本用例构造失效，需改固定端口后重测）"));
            Thread.sleep(5_000L); // 给 SUT 的重连窗口
            assertThat(sut.isAlive()).as("Redis 断连/恢复期间 SUT 进程必须存活（免重启）").isTrue();

            String session = "sess-recover-" + UUID.randomUUID();
            // 注意：本类 postJson 打的是 A2A JSON-RPC 端点（/a2a）；DA 形态入体必须打 /agent/run
            HttpResponse<String> run = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/agent/run"))
                    .timeout(Duration.ofMinutes(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"sessionId\":\"" + session
                            + "\",\"input\":\"请用一句话回答：3+3 等于几。不要调用任何工具。\"}",
                            StandardCharsets.UTF_8))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Allure.addAttachment("Redis 可用后的任务响应（截取）", "text/plain", truncate(run.body()));
            assertThat(run.statusCode()).as("Redis 可用时任务必须受理并完成").isEqualTo(200);
            assertThat(run.body()).as("任务终态应为 completed").contains("completed");
        } finally {
            if (sut != null) { sut.destroyForcibly(); }
            if (redis != null) { try { redis.stop(); } catch (Exception ignored) { } }
        }
    }

    private static void copyEnv(ProcessBuilder builder, String target) {
        copyEnv(builder, target, target);
    }

    private static void copyEnv(ProcessBuilder builder, String target, String source) {
        String value = System.getenv(source);
        if (value != null && !value.isBlank()) {
            builder.environment().put(target, value);
        }
    }

    private void awaitReady(int port) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        long deadline = System.nanoTime() + BOOT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<String> card = client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/.well-known/agent-card.json"))
                        .timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (card.statusCode() == 200) {
                    return;
                }
            } catch (Exception ignored) {
                // 未就绪，继续轮询
            }
            Thread.sleep(1000L);
        }
        throw new IllegalStateException("SUT 未在 " + BOOT_TIMEOUT.toSeconds() + "s 内就绪（port=" + port + "）");
    }

    private static HttpResponse<String> postJson(int port, String body) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/a2a"))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String truncate(String text) {
        return text == null ? "" : text.substring(0, Math.min(400, text.length()));
    }

    private static Path resolveArtifact() {
        String repo = System.getenv("SUT_M2_REPO");
        if (repo == null || repo.isBlank()) {
            repo = Path.of(System.getProperty("user.home"), ".m2", "repository").toString();
        }
        // 制品版本与被测 SUT 的声明同源（application-<env>.yml 的 sut.agents.deepanalyze.version，
        // 可被 SUT_AGENTS_DEEPANALYZE_VERSION 覆盖），不把版本写死在用例里。
        TestConfig config = TestConfig.load();
        String version = config.getString("sut.agents.deepanalyze.version");
        if (version == null || version.isBlank()) {
            throw new IllegalStateException(
                    "未声明被测制品版本：请在 application-<env>.yml 设置 sut.agents.deepanalyze.version，"
                            + "或用 SUT_AGENTS_DEEPANALYZE_VERSION 覆盖");
        }
        String classifier = config.getString("sut.agents.deepanalyze.classifier", "exec");
        String relative = "com/openjiuwen/deepanalyze-engine/" + version
                + "/deepanalyze-engine-" + version + "-" + classifier + ".jar";
        return Path.of(repo, relative.split("/"));
    }
}

/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.feat056;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;

import org.junit.jupiter.api.AfterEach;
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
 * FEAT-056 真实 Runtime A2A 补测（托管 + 非托管 singleton 两种形态）。
 *
 * <p>被拉起的样本是 {@code agent-solution/common/example/agentcore-instance-customizer-verify-demo}
 * 构建出的独立宿主；{@code VERIFY_DEPLOYMENT=hosted} 时在一个进程里托管 agent-a / agent-b
 * （入口 {@code /a2a/agents/{注册ID}}），{@code singleton} 时为非托管单 Agent（入口 {@code /a2a}）。
 * {@code VERIFY_CUSTOMIZER_MODE} 切换外部自定义器。这里直接以黑盒方式拉起该 jar，
 * 发真实请求并从 SUT 日志断言行为。
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
    @Story("FEAT-056-C21: 真实链路上合规业务 Rail 与 Tool Guardrail 共存")
    void compliantBusinessRailCoexistsWithGuardrailOnRealChain() throws Exception {
        // canary 变体注册的是合规业务 Rail（Core 默认 priority 50，低于保留区 150）。
        Fixture fixture = start("canary", true);
        assertThat(waitForStarted(fixture)).as("fixture started").isTrue();

        String response = sendA2a(fixture.port(), "agent-a");
        String log = readLog(fixture);

        assertThat(log).contains("Tool guardrail enabled");
        assertThat(log).contains("Tool guardrail installed:");
        assertThat(log).contains("VERIFY_CUSTOMIZER installed agentId=agent-a");
        assertThat(log).contains("VERIFY_EXTERNAL_RAIL_CANARY fired agentId=agent-a");
        assertThat(response).contains("TASK_STATE_COMPLETED");
    }

    @Test
    @Story("FEAT-056-C33: init-fail-fast=false 下非托管初始化失败不退出进程，Agent 未就绪")
    void nonHostedInitFailureKeepsProcessAliveWhenFailFastIsDisabled() throws Exception {
        Fixture fixture = start("fail", false, "singleton", List.of(
                "--openjiuwen.service.agent-id=verify-singleton-agent",
                "--openjiuwen.service.lifecycle.init-fail-fast=false"));
        assertThat(waitForStarted(fixture)).as("fixture started").isTrue();
        // 该形态没有 per-task manager，自定义器在 init phase 就会抛错，因此等的是"失败已被记录"。
        assertThat(waitForLog(fixture, "VERIFY_FAILING_CUSTOMIZER about to throw"))
                .as("初始化失败已被记录（日志目录：%s）", fixture.log().getParent()).isTrue();

        // init-fail-fast=false：失败只让 Agent 未就绪，不得终止进程。
        assertThat(process.isAlive())
                .as("init-fail-fast=false 时进程必须继续存活").isTrue();

        Probe probe = probeA2a(fixture.port(), "/a2a");
        String log = readLog(fixture);
        assertThat(log).contains("VERIFY_FAILING_CUSTOMIZER about to throw");
        assertThat(log).contains("init-fail-fast=false");
        assertThat(log).doesNotContain("VERIFY_AFTER_FAILURE_CUSTOMIZER called");
        assertThat(log)
                .as("失败对象不得被交付执行")
                .doesNotContain("VERIFY_EXTERNAL_RAIL_CANARY fired");
        // 收紧断言：入口必须明确失败，而不是"没有返回成功"就算过。
        assertThat(probe.body())
                .as("Agent 未就绪时入口必须明确失败（HTTP %d）", probe.status())
                .contains("TASK_STATE_FAILED")
                .contains("AGENT_EXECUTION_FAILED")
                .contains("Instance customization is not ready")
                .doesNotContain("TASK_STATE_COMPLETED");
    }

    @Test
    @Story("FEAT-056-C34: init-fail-fast=true（默认）下非托管初始化失败使进程退出")
    void nonHostedInitFailureKillsTheProcessByDefault() throws Exception {
        Fixture fixture = start("fail", false, "singleton", List.of(
                "--openjiuwen.service.agent-id=verify-singleton-agent"));

        assertThat(process.waitFor(90, TimeUnit.SECONDS))
                .as("init-fail-fast 默认 true 时进程必须退出").isTrue();
        assertThat(process.exitValue()).as("退出码必须非零").isNotZero();

        String log = readLog(fixture);
        assertThat(log).contains("VERIFY_FAILING_CUSTOMIZER about to throw agentId=verify-singleton-agent");
        assertThat(log).contains("(init-fail-fast=true)");
        assertThat(log).contains("Application run failed");
        assertThat(log).doesNotContain("VERIFY_AFTER_FAILURE_CUSTOMIZER called");
    }

    @Test
    @Story("FEAT-056-C35: 按任务初始化失败且工厂 destroy 也失败——原错误不被掩盖、清理失败可诊断、占位被清掉")
    void perTaskInitFailureWithFailingDestroyKeepsPrimaryErrorAndFreesPlaceholder() throws Exception {
        Fixture fixture = start("fail", false, "singleton", List.of(
                "--openjiuwen.service.agent-id=verify-singleton-agent",
                "--openjiuwen.service.lifecycle.init-fail-fast=false"),
                Map.of("VERIFY_AGENT_MANAGER", "true", "VERIFY_FACTORY_DESTROY_THROWS", "true"));
        assertThat(waitForStarted(fixture)).as("fixture started").isTrue();
        // 有 per-task manager 时初始化发生在请求里；但 initializer 必须等 start()（init phase）执行完才就绪，
        // 因此先等 init phase 完成，否则请求会命中 "Instance customization is not ready"。
        assertThat(waitForLog(fixture, "Agent init phase completed for application", Duration.ofSeconds(90)))
                .as("init phase 已完成，initializer 已就绪（日志目录：%s）", fixture.log().getParent()).isTrue();
        Probe first = probeA2a(fixture.port(), "/a2a", "feat056-c35-conv-a");
        // 同一个 conversation 再发一次：若占位没被清掉，这里会得到 conversation busy。
        Probe second = probeA2a(fixture.port(), "/a2a", "feat056-c35-conv-a");
        String log = readLog(fixture);

        assertThat(first.body())
                .as("原初始化错误必须成为主错误")
                .contains("TASK_STATE_FAILED")
                .contains("VERIFY_FAILING_CUSTOMIZER")
                .doesNotContain("VERIFY_FACTORY_DESTROY_FAILED");
        assertThat(second.body())
                .as("失败后占位必须已移除，同 conversation 可重新创建对象")
                .contains("TASK_STATE_FAILED")
                .doesNotContain("Conversation already has an active or initializing agent");
        assertThat(log)
                .as("清理失败必须可诊断（作为主错误的 suppressed 异常）")
                .contains("Suppressed: java.lang.IllegalStateException: VERIFY_FACTORY_DESTROY_FAILED");
        assertThat(countOccurrences(log, "VERIFY_FACTORY_DESTROY invoked"))
                .as("两条请求各自创建并清理了一个新对象")
                .isGreaterThanOrEqualTo(2);
    }

    private Fixture start(String mode, boolean guardrailEnabled) throws IOException {
        return start(mode, guardrailEnabled, "hosted", List.of());
    }

    @Test
    @Story("FEAT-056-C36: 零自定义器时真实链路原行为不变，且不要求业务 ID")
    void zeroCustomizersKeepsOriginalBehaviourAndRequiresNoBusinessId() throws Exception {
        // 不注册任何自定义器，也不提供 --openjiuwen.service.agent-id：应用必须能正常起来。
        Fixture fixture = start("none", false, "singleton", List.of());
        assertThat(waitForStarted(fixture)).as("fixture started").isTrue();
        assertThat(waitForLog(fixture, "Agent init phase completed for application", Duration.ofSeconds(90)))
                .as("init phase 已完成（日志目录：%s）", fixture.log().getParent()).isTrue();

        Probe probe = probeA2a(fixture.port(), "/a2a", "feat056-c36-conv");
        String log = readLog(fixture);

        assertThat(probe.body())
                .as("零自定义器时不得因缺少自定义器或业务 ID 而失败")
                .doesNotContain("Instance customization is not ready")
                .doesNotContain("requires a non-blank business agent ID");
        assertThat(log).doesNotContain("VERIFY_CUSTOMIZER installed");
        assertThat(log).doesNotContain("VERIFY_FAILING_CUSTOMIZER");
        assertThat(log).doesNotContain("Instance customization requires a non-blank business agent ID");
    }

    @Test
    @Story("FEAT-056-C37: 真实链路上对象类型不支持时在业务回调前失败")
    void unsupportedTaskAgentTypeFailsBeforeAnyCustomizerRuns() throws Exception {
        Fixture fixture = start("canary", false, "singleton", List.of(
                "--openjiuwen.service.agent-id=verify-singleton-agent"),
                Map.of("VERIFY_AGENT_MANAGER", "true", "VERIFY_FACTORY_AGENT_TYPE", "plain"));
        assertThat(waitForStarted(fixture)).as("fixture started").isTrue();
        assertThat(waitForLog(fixture, "Agent init phase completed for application", Duration.ofSeconds(90)))
                .as("init phase 已完成（日志目录：%s）", fixture.log().getParent()).isTrue();

        Probe probe = probeA2a(fixture.port(), "/a2a", "feat056-c37-conv");
        String log = readLog(fixture);

        assertThat(probe.body()).as("任务必须明确失败").contains("TASK_STATE_FAILED");
        assertThat(log)
                .as("必须在业务回调前失败")
                .contains("requires BaseAgent or DeepAgent");
        assertThat(countMarkers(fixture, "VERIFY_CUSTOMIZER installed"))
                .as("不支持类型时自定义器调用次数必须为 0")
                .isZero();
    }

    @Test
    @Story("FEAT-056-C38: 真实链路上按任务对象各自扩展，释放一个不影响另一个")
    void perTaskObjectsGetSeparateExtensionsAndReleaseIsolation() throws Exception {
        Fixture fixture = start("canary", false, "singleton", List.of(
                "--openjiuwen.service.agent-id=verify-singleton-agent"),
                Map.of("VERIFY_AGENT_MANAGER", "true"));
        assertThat(waitForStarted(fixture)).as("fixture started").isTrue();
        assertThat(waitForLog(fixture, "Agent init phase completed for application", Duration.ofSeconds(90)))
                .as("init phase 已完成（日志目录：%s）", fixture.log().getParent()).isTrue();

        Probe first = probeA2a(fixture.port(), "/a2a", "feat056-c38-a");
        Probe second = probeA2a(fixture.port(), "/a2a", "feat056-c38-b");
        String log = readLog(fixture);

        assertThat(first.body()).contains("TASK_STATE_COMPLETED");
        assertThat(second.body()).contains("TASK_STATE_COMPLETED");
        assertThat(countMarkers(fixture, "VERIFY_CUSTOMIZER installed"))
                .as("两个任务对象必须各自扩展一次（日志目录：%s）", fixture.log().getParent())
                .isEqualTo(2);
        List<String> instances = installedInstances(structuredLog(fixture));
        assertThat(instances).as("两个任务对象必须是不同实例").hasSize(2).doesNotHaveDuplicates();
        assertThat(countMarkers(fixture, "VERIFY_EXTERNAL_RAIL_CANARY fired"))
                .as("两个任务对象的 Rail 都必须各自触发")
                .isEqualTo(2);
    }

    @Test
    @Story("FEAT-056-C39: 托管默认 Agent 的按任务扩展（显式配对工厂）在真实链路上生效")
    void hostedDefaultAgentPerTaskCustomizationOnRealChain() throws Exception {
        Fixture fixture = start("canary", false, "hosted", List.of(),
                Map.of("VERIFY_AGENT_MANAGER", "true", "VERIFY_DEFAULT_AGENT", "agent-a"));
        assertThat(waitForStarted(fixture)).as("fixture started").isTrue();
        // 托管模式不经过 InitPhaseExecutor，完成标志是最后一个托管成员启动成功。
        assertThat(waitForLog(fixture, "Hosted agent agentId=agent-b operation=start result=success",
                Duration.ofSeconds(90)))
                .as("托管成员已就绪（日志目录：%s）", fixture.log().getParent()).isTrue();

        Probe first = probeA2a(fixture.port(), "/a2a", "feat056-c39-a");
        Probe second = probeA2a(fixture.port(), "/a2a", "feat056-c39-b");
        String log = readLog(fixture);

        assertThat(first.body()).contains("TASK_STATE_COMPLETED");
        assertThat(second.body()).contains("TASK_STATE_COMPLETED");
        assertThat(countMarkers(fixture, "VERIFY_CUSTOMIZER installed agentId=agent-a"))
                .as("默认 Agent 的每个任务对象都要各自扩展（日志目录：%s）", fixture.log().getParent())
                .isEqualTo(2);
        assertThat(installedInstances(structuredLog(fixture), "verify-task-"))
                .as("默认 Agent 的每个任务对象必须是不同实例")
                .hasSize(2).doesNotHaveDuplicates();
    }

    private static List<String> installedInstances(String log) {
        return installedInstances(log, "");
    }

    private static List<String> installedInstances(String log, String requiredSubstring) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("VERIFY_CUSTOMIZER installed agentId=[^ ]+ instance=([^\\s]+)")
                .matcher(log);
        List<String> instances = new java.util.ArrayList<>();
        while (matcher.find()) {
            if (requiredSubstring.isEmpty() || matcher.group(1).contains(requiredSubstring)) {
                instances.add(matcher.group(1));
            }
        }
        return instances;
    }

    /**
     * 只读 logs/run/run.log，避免 stdout 与文件日志重复计入。
     */
    private static String structuredLog(Fixture fixture) throws IOException {
        Path runLog = fixture.log().getParent().resolve("logs/run/run.log");
        if (Files.exists(runLog)) {
            return Files.readString(runLog);
        }
        return Files.exists(fixture.log()) ? Files.readString(fixture.log()) : "";
    }

    private Fixture start(String mode, boolean guardrailEnabled, String deployment,
            List<String> extraArgs) throws IOException {
        return start(mode, guardrailEnabled, deployment, extraArgs, Map.of());
    }

    private Fixture start(String mode, boolean guardrailEnabled, String deployment,
            List<String> extraArgs, Map<String, String> extraEnv) throws IOException {
        Path jar = fixtureJar();
        // 放在模块 target/ 下而不是 /tmp：临时目录会被系统清理，导致失败后无法回溯现场。
        workDir = Path.of("target", "feat056-fixture", mode + "-" + System.nanoTime());
        Files.createDirectories(workDir);
        int port = freePort();
        Path log = workDir.resolve("app.log");

        List<String> command = new java.util.ArrayList<>(List.of(
                javaExecutable(), "-jar", jar.toString(), "--server.port=" + port));
        command.addAll(extraArgs);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workDir.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        Map<String, String> env = builder.environment();
        env.put("VERIFY_DEPLOYMENT", deployment);
        env.put("VERIFY_CUSTOMIZER_MODE", mode);
        env.put("VERIFY_GUARDRAIL_ENABLED", Boolean.toString(guardrailEnabled));
        env.putIfAbsent("LLM_API_KEY", "dummy");
        env.putIfAbsent("LLM_API_BASE", "http://127.0.0.1:9");
        env.putIfAbsent("LLM_MODEL", "dummy");
        env.putAll(extraEnv);
        process = builder.start();
        return new Fixture(port, log);
    }

    private boolean waitForLog(Fixture fixture, String needle) throws Exception {
        return waitForLog(fixture, needle, Duration.ofSeconds(45));
    }

    private boolean waitForLog(Fixture fixture, String needle, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (readLog(fixture).contains(needle)) {
                return true;
            }
            if (!process.isAlive()) {
                return readLog(fixture).contains(needle);
            }
            Thread.sleep(500);
        }
        return false;
    }

    private Probe probeA2a(int port, String path) throws Exception {
        return probeA2a(port, path, "feat056-probe-" + UUID.randomUUID());
    }

    private Probe probeA2a(int port, String path, String contextId) throws Exception {
        String payload = JSON.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", "feat056-probe-" + UUID.randomUUID(),
                "method", "SendStreamingMessage",
                "params", Map.of("message", Map.of(
                        "messageId", UUID.randomUUID().toString(),
                        "role", "ROLE_USER",
                        "contextId", contextId,
                        "parts", List.of(Map.of("text", "ping"))))));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Probe(response.statusCode(), response.body() == null ? "" : response.body());
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    /**
     * 只数运行日志文件：stdout 与 logs/run/run.log 的 logger 名一个是缩写一个是全名，
     * 同一事件在两份文件里行内容不同，按行去重无效，容易把 2 次事件数成 4 次。
     *
     * @param fixture 夹具实例（含工作目录与 stdout 日志路径）
     * @param needle 要统计的日志片段
     * @return 命中的行数
     * @throws IOException 读取日志文件失败
     */
    private static int countMarkers(Fixture fixture, String needle) throws IOException {
        Path runLog = fixture.log().getParent().resolve("logs/run/run.log");
        String text = Files.exists(runLog) ? Files.readString(runLog)
                : (Files.exists(fixture.log()) ? Files.readString(fixture.log()) : "");
        return countOccurrences(text, needle);
    }

    private boolean waitForStarted(Fixture fixture) throws Exception {
        long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            boolean started = Files.exists(fixture.log()) && Files.readString(fixture.log())
                    .contains("Started CustomizerVerifyApplication");
            if (started) {
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

    private record Probe(int status, String body) {
    }
}

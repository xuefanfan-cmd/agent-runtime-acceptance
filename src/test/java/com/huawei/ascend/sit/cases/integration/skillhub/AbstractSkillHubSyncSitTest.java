package com.huawei.ascend.sit.cases.integration.skillhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import com.huawei.ascend.sit.lifecycle.SutStack;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * FEAT-072（动态 Skill 获取与运行时热加载）SIT 用例公共基座。
 *
 * <p>被测 SUT：{@code com.openjiuwen.example:skillhub-runtime-demo}（agent-solution
 * {@code common/example}，经 {@code application-openjiuwen.yml} 的 {@code sut.agents.skillhub-demo}
 * 从本地 Maven 仓拉起）。测试用 {@link SkillHubSyncMockServer} 替身 Hub 驱动同步链路，
 * 断言面只走 SUT 的对外 HTTP（管理端点 {@code /manage/skillhub/*}、业务端点 {@code /v1/query}）、
 * 进程日志与本地磁盘 —— 与 2026-10-27/29 在 ECS 139 上的手工执行口径一致
 * （执行记录见 docs/cases/FEAT-072-*.md §执行策略）。
 *
 * <p>每条用例自行管理 {@link SutStack} 生命周期（同 FEAT-005 的
 * {@code SkillHubReactAgentBlackboxTest} 模式）：FEAT-072 场景矩阵需要在同一用例内以
 * 不同配置反复拉起 SUT（开关组合/规则文件/死端点/多实例），{@code BaseManagedStackTest}
 * 的单栈模型不适合。
 *
 * <p>LLM 绑定沿用 {@code DaModelEnvironment} 的结论：全局
 * {@code sut.java.system-properties.LLM_API_KEY} 是空串且优先级高于环境变量，
 * 因此 key 必须以 Spring {@code --} 参数注入（值只在运行时从环境读取，不落文件、不落日志）。
 * 无 key 时注入占位值保证启动期链路可测；需要真实模型的用例（提示词探针）自行
 * {@code Assumptions} 门控。
 */
abstract class AbstractSkillHubSyncSitTest {

    protected static final String AGENT = "skillhub-demo";
    protected static final String AGENT_B = "skillhub-demo-b";
    protected static final String PREFIX = "openjiuwen.service.middleware.skillhub.";
    protected static final String ADMIN_TOKEN = "sk072-sit-admin";
    protected static final String ADMIN_TOKEN_HEADER = "X-SkillHub-Admin-Token";
    protected static final String OUTBOUND_TOKEN = "sit-outbound-token-072";
    /** encrypted-token 经环境变量注入的占位符名（值不落仓）。 */
    protected static final String TOKEN_ENV = "SIT_SKILLHUB_TOKEN";
    protected static final String TOKEN_PLACEHOLDER = "${" + TOKEN_ENV + ":}";
    protected static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(90);
    protected static final Duration ROUND_TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 拉起一个指向替身 Hub 的 SUT 实例；extra 追加场景专属配置覆盖。 */
    protected RunningAgent startAgent(SkillHubSyncMockServer hub, Path localDir,
                                      Consumer<SutStack.AgentBuilder> extra) throws IOException {
        Files.createDirectories(localDir);
        TestConfig config = TestConfig.load();
        LogCapture capture = LogCapture.before(config, agentNameFor(extra));
        SutStack stack = SutStack.builder(config)
                .streaming(false)
                .agent(AGENT, agent -> {
                    configureSkillHub(agent, hub.endpoint(), localDir);
                    extra.accept(agent);
                })
                .start();
        assertThat(stack.managedInstance(AGENT)).as("SUT 应以受管进程方式拉起").isInstanceOf(ManagedSutInstance.class);
        return new RunningAgent(stack, stack.baseUrl(AGENT), capture);
    }

    /** 期望启动失败（快速失败场景）：返回 异常信息+日志 合并文本。 */
    protected String expectStartupFailure(SkillHubSyncMockServer hub, Path localDir,
                                          Consumer<SutStack.AgentBuilder> extra) throws IOException {
        Files.createDirectories(localDir);
        TestConfig config = TestConfig.load();
        LogCapture capture = LogCapture.before(config, agentNameFor(extra));
        SutStack stack = null;
        try {
            stack = SutStack.builder(config)
                    .streaming(false)
                    .agent(AGENT, agent -> {
                        configureSkillHub(agent, hub == null ? "http://127.0.0.1:1" : hub.endpoint(), localDir);
                        extra.accept(agent);
                    })
                    .start();
            throw new IllegalStateException("SUT unexpectedly became ready at " + stack.baseUrl(AGENT));
        } catch (IllegalStateException | AssertionError expected) {
            return expected.getMessage() + "\n" + capture.read();
        } finally {
            if (stack != null) {
                stack.close();
            }
        }
    }

    /** 两个实例同栈（SC-INST-01 多副本收敛 / owner-lock 互斥）。 */
    protected SutStack startTwoAgentStack(Path localDirA, Path localDirB,
                                          SkillHubSyncMockServer hub) throws IOException {
        Files.createDirectories(localDirA);
        Files.createDirectories(localDirB);
        TestConfig config = TestConfig.load();
        return SutStack.builder(config)
                .streaming(false)
                .agent(AGENT, agent -> configureSkillHub(agent, hub.endpoint(), localDirA))
                .agent(AGENT_B, agent -> configureSkillHub(agent, hub.endpoint(), localDirB))
                .start();
    }

    private static void configureSkillHub(SutStack.AgentBuilder agent, String endpoint, Path localDir) {
        agent.property(PREFIX + "endpoint", endpoint);
        agent.property(PREFIX + "local-dir", localDir.toString());
        agent.property(PREFIX + "encrypted-token", TOKEN_PLACEHOLDER);
        agent.env(TOKEN_ENV, OUTBOUND_TOKEN);
        agent.env("SKILLHUB_ADMIN_TOKEN", ADMIN_TOKEN);
        bindLlm(agent);
    }

    /**
     * LLM 绑定：key/base/model 全部走 Spring {@code --} 参数。
     * 全局 {@code sut.java.system-properties} 的 {@code LLM_API_KEY=""}/{@code LLM_MODEL=qwen3.8-max}/
     * {@code LLM_API_BASE=<workspace>} 是 -D 系统属性，优先级高于环境变量——
     * env 透传会被它们覆盖（2026-10-29 实测 403 "Workspace endpoint access denied"）。
     * Spring 命令行参数优先级最高，值只在运行时从环境读取，不落文件、不落日志。
     */
    private static void bindLlm(SutStack.AgentBuilder agent) {
        String apiKey = System.getenv("LLM_API_KEY");
        agent.property("LLM_API_KEY", apiKey == null || apiKey.isBlank() ? "sit-placeholder-llm-key" : apiKey);
        bindIfPresent(agent, "LLM_API_BASE");
        bindIfPresent(agent, "LLM_MODEL");
    }

    private static void bindIfPresent(SutStack.AgentBuilder agent, String name) {
        String value = System.getenv(name);
        if (value != null && !value.isBlank()) {
            agent.property(name, value);
        }
    }

    /** 本轮是否有真实 LLM（提示词探针类用例的门控）。 */
    protected static boolean realLlmConfigured() {
        String key = System.getenv("LLM_API_KEY");
        return key != null && !key.isBlank();
    }

    // -------------------------------------------------------------- HTTP 面

    /**
     * 手动触发一次同步，返回 (http status, body)。
     *
     * <p>统一冷却语义（SC-MANUAL-05）：轮次之间存在冷却窗，手动触发命中冷却返回
     * 429+state=COOLDOWN+retryAfterSeconds。本方法对 429 自动等待后重试（上限 3 次），
     * 供不关心冷却本身的用例使用；冷却契约本身由 {@code SkillHubManualSyncSitTest} 用
     * {@link #postSyncOnce} 判定。
     */
    protected SyncResponse postSync(String base) throws IOException, InterruptedException {
        SyncResponse response = postSyncOnce(base);
        for (int attempt = 0; attempt < 3
                && response.status() == 429
                && response.json().path("retryAfterSeconds").asInt(0) > 0; attempt++) {
            long retryAfter = response.json().path("retryAfterSeconds").asLong(0);
            Thread.sleep(Math.min(retryAfter, 40) * 1000 + 500);
            response = postSyncOnce(base);
        }
        return response;
    }

    /** 单次手动触发，不重试（冷却/单飞契约判定的原始观察点）。 */
    protected SyncResponse postSyncOnce(String base) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/manage/skillhub/sync"))
                .timeout(Duration.ofSeconds(20))
                .header(ADMIN_TOKEN_HEADER, ADMIN_TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> response = httpClient().send(request, HttpResponse.BodyHandlers.ofString());
        return new SyncResponse(response.statusCode(), response.body());
    }

    /** 读取同步报告原文（含 running 与 lastCompleted 两键）。 */
    protected String getReportRaw(String base) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/manage/skillhub/report"))
                .timeout(Duration.ofSeconds(20))
                .header(ADMIN_TOKEN_HEADER, ADMIN_TOKEN)
                .GET()
                .build();
        return httpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    /** 等待指定 syncId 的轮次完成并返回其 lastCompleted 报告节点。 */
    protected JsonNode awaitCompletedReport(String base, String syncId, Duration timeout) {
        await().atMost(timeout).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            JsonNode completed = lastCompleted(base);
            assertThat(completed).as("报告应出现 syncId=%s 的完成结果", syncId).isNotNull();
            assertThat(completed.path("syncId").asText()).isEqualTo(syncId);
        });
        return lastCompleted(base);
    }

    /** 等待任意一轮完成（触发后不关心 syncId 的场景），返回 lastCompleted 节点。 */
    protected JsonNode awaitAnyCompletedReport(String base, Duration timeout) {
        await().atMost(timeout).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(lastCompleted(base)).as("应至少有一轮完成结果").isNotNull());
        return lastCompleted(base);
    }

    protected JsonNode lastCompleted(String base) {
        try {
            return JSON.readTree(getReportRaw(base)).path("lastCompleted");
        } catch (IOException e) {
            throw new IllegalStateException("could not parse report", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while reading report", e);
        }
    }

    /** 业务查询（/v1/query，阻塞式）；返回 (http status, result.content)。 */
    protected QueryResponse postQuery(String base, String conversationId, String message)
            throws IOException, InterruptedException {
        String body = "{\"conversation_id\":\"" + conversationId + "\",\"message\":"
                + JSON.valueToTree(message) + ",\"stream\":false}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/v1/query"))
                .timeout(Duration.ofSeconds(180))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient().send(request, HttpResponse.BodyHandlers.ofString());
        String content = "";
        try {
            content = JSON.readTree(response.body()).path("result").path("content").asText("");
        } catch (IOException ignored) {
            content = "";
        }
        return new QueryResponse(response.statusCode(), content, response.body());
    }

    private static HttpClient httpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    // -------------------------------------------------------------- 磁盘面

    /** 受管 active 目录下已落盘的技能目录数（一层 directoryKey）。 */
    protected static long activeSkillDirCount(Path localDir) throws IOException {
        Path active = localDir.resolve(".skillhub/active");
        if (!Files.exists(active)) {
            return 0;
        }
        try (var paths = Files.walk(active, 1)) {
            return paths.filter(Files::isDirectory).filter(p -> !p.equals(active)).count();
        }
    }

    /**
     * 受管 active 树里真正的技能目录（{@code active/<directoryKey>/<localName>/}，含 SKILL.md）。
     * 适配器落盘是两层结构（SkillPathSafety.directoryKey/localName），不是按资产 ID 直铺。
     */
    protected static List<Path> activeSkillPaths(Path localDir) throws IOException {
        Path active = localDir.resolve(".skillhub/active");
        if (!Files.exists(active)) {
            return List.of();
        }
        try (var paths = Files.walk(active, 2)) {
            return paths.filter(Files::isDirectory)
                    .filter(dir -> {
                        try (var children = Files.list(dir)) {
                            return children.anyMatch(child -> child.getFileName().toString()
                                    .equalsIgnoreCase("SKILL.md"));
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .toList();
        }
    }

    /** manifest.json 的 generation / records 数量（读不到返回 null）。 */
    protected static int[] manifestGenerationAndRecords(Path localDir) throws IOException {
        Path manifest = localDir.resolve(".skillhub/manifest.json");
        if (!Files.exists(manifest)) {
            return null;
        }
        JsonNode node = JSON.readTree(Files.readString(manifest, StandardCharsets.UTF_8));
        return new int[]{node.path("generation").asInt(-1), node.path("records").size()};
    }

    // -------------------------------------------------------------- 记录类型

    protected record SyncResponse(int status, String body) {
        JsonNode json() {
            try {
                return JSON.readTree(body);
            } catch (IOException e) {
                throw new IllegalStateException("could not parse sync response", e);
            }
        }
    }

    protected record QueryResponse(int status, String content, String rawBody) {
    }

    protected record RunningAgent(SutStack stack, String base, LogCapture capture) implements AutoCloseable {
        String log() {
            return capture.read();
        }

        @Override
        public void close() {
            stack.close();
        }
    }

    /** SUT stdout 日志切片捕获（沿用 FEAT-005 SkillHubReactAgentBlackboxTest 的机制）。 */
    protected record LogCapture(Path path, long offset) {
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
                throw new IllegalStateException("could not read current SUT log slice", ex);
            }
        }
    }

    /** 日志目录按 agent 名区分（基座固定 AGENT；由调用侧场景决定）。 */
    private static String agentNameFor(Consumer<SutStack.AgentBuilder> extra) {
        return AGENT;
    }
}

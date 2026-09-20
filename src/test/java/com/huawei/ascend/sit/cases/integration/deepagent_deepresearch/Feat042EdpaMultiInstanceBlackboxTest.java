package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.AgentConfig;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import com.huawei.ascend.sit.lifecycle.SutAgent;
import com.huawei.ascend.sit.lifecycle.SutInstance;
import com.huawei.ascend.sit.lifecycle.SutLauncher;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
@Tag("openjiuwen")
@Tag("feat-042")
@Tag("blackbox")
@Feature("FEAT-042: EDPA 多实例配置装配")
class Feat042EdpaMultiInstanceBlackboxTest extends BaseManagedStackTest {
    private static final String EDPA = "edpa-multi";
    private static final String CODE = "code-assistant";
    private static final String DATA = "data-assistant";
    private static final String CODE_IDENTITY = "FEAT042_CODE_SCENARIO_IDENTITY";
    private static final String DATA_IDENTITY = "FEAT042_DATA_SCENARIO_IDENTITY";
    private static final String CODE_MODEL = "feat042-code-model";
    private static final String DATA_MODEL = "feat042-data-model";
    private static final String CODE_CREDENTIAL = "feat042-code-credential-canary";
    private static final String DATA_CREDENTIAL = "feat042-data-credential-canary";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(180);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ModelAuditFixture model = ModelAuditFixture.start();
    private final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        redis.start();
        return builder(config).agent(EDPA, agent -> configureMulti(agent, true));
    }

    @AfterAll
    void closeFixtures() {
        model.close();
        redis.stop();
    }

    @Test
    @Tag("story-feat-042-f042-01")
    @Story("FEAT-042.F042-01: 双实例声明、目录与顺序")
    @DisplayName("FEAT-042 F042-01 EDPA 双实例在同一进程按声明顺序发布")
    void f04201DualInstancesPublishInDeclarationOrder() throws Exception {
        JsonNode listing = catalog(stack);
        assertThat(agentIds(listing)).containsExactly(CODE, DATA);
        assertThat(listing.path("defaultAgent").asText()).isEqualTo(CODE);
        assertCardRoute(stack, CODE);
        assertCardRoute(stack, DATA);

        ManagedSutInstance process = managed(stack);
        assertThat(process.isAlive()).isTrue();
        assertThat(process.pid()).isPositive();
    }

    @Test
    @Tag("story-feat-042-f042-02")
    @Story("FEAT-042.F042-02: 全局继承与实例覆盖日志")
    @DisplayName("FEAT-042 F042-02 启动日志逐实例输出五字段最终配置且不泄露凭据")
    void f04202EffectiveConfigurationIsObservableWithoutCredentials() throws Exception {
        String log = Files.readString(managed(stack).logFile());
        String codeLine = requireLine(log, "[EDPA-MULTI] instance '" + CODE + "'");
        String dataLine = requireLine(log, "[EDPA-MULTI] instance '" + DATA + "'");

        assertThat(codeLine)
                .contains(resourcePath("scenarios/code-review").toString())
                .contains("maxIterations=3", CODE_MODEL, "/code/v1");
        assertThat(dataLine)
                .contains(resourcePath("scenarios/data-analysis").toString())
                .contains("workspace=workspaces/data-assistant")
                .contains("maxIterations=7", DATA_MODEL, "/data/v1");
        assertThat(log).doesNotContain(CODE_CREDENTIAL, DATA_CREDENTIAL);
    }

    @Test
    @Tag("story-feat-042-f042-03")
    @Story("FEAT-042.F042-03: 场景、工作空间与人设差异")
    @DisplayName("FEAT-042 F042-03 两实例执行各自场景并保持人设与工作空间差异")
    void f04203ScenarioPersonaAndWorkspaceDifferByInstance() throws Exception {
        String codeCanary = canary("F042-03-CODE");
        String dataCanary = canary("F042-03-DATA");
        String codeResult = sendAndAwait(stack, CODE, "SendMessage", codeCanary, context("f042-03-code"));
        String dataResult = sendAndAwait(stack, DATA, "SendMessage", dataCanary, context("f042-03-data"));
        ModelCall codeCall = model.awaitCall(codeCanary);
        ModelCall dataCall = model.awaitCall(dataCanary);

        assertThat(codeCall.body()).contains(CODE_IDENTITY).doesNotContain(DATA_IDENTITY);
        assertThat(dataCall.body()).contains(DATA_IDENTITY).doesNotContain(CODE_IDENTITY);
        assertThat(codeResult).contains(CODE_IDENTITY, codeCanary).doesNotContain(DATA_IDENTITY);
        assertThat(dataResult).contains(DATA_IDENTITY, dataCanary).doesNotContain(CODE_IDENTITY);
    }

    @Test
    @Tag("story-feat-042-f042-04")
    @Story("FEAT-042.F042-04: 模型连接与模型参数深度生效")
    @DisplayName("FEAT-042 F042-04 两实例实际调用各自模型端点和模型名")
    void f04204ModelEndpointAndModelNameReachExecutionLayer() throws Exception {
        String codeCanary = canary("F042-04-CODE");
        String dataCanary = canary("F042-04-DATA");
        sendAndAwait(stack, CODE, "SendMessage", codeCanary, context("f042-04-code"));
        sendAndAwait(stack, DATA, "SendMessage", dataCanary, context("f042-04-data"));

        ModelCall codeCall = model.awaitCall(codeCanary);
        ModelCall dataCall = model.awaitCall(dataCanary);
        assertThat(codeCall.path()).contains("/code/v1/");
        assertThat(codeCall.model()).isEqualTo(CODE_MODEL);
        assertThat(dataCall.path()).contains("/data/v1/");
        assertThat(dataCall.model()).isEqualTo(DATA_MODEL);
    }

    @Test
    @Tag("story-feat-042-f042-05")
    @Story("FEAT-042.F042-05: 最大迭代轮数深度生效")
    @DisplayName("FEAT-042 F042-05 最大迭代轮数在执行层分别生效")
    void f04205MaxIterationsReachExecutionLayer() {
        Assumptions.assumeTrue(false,
                "blocked: no stable public execution trace and deterministic multi-tool loop fixture are available");
    }

    @Test
    @Tag("story-feat-042-f042-06")
    @Story("FEAT-042.F042-06: 空实例名 fail-fast")
    @DisplayName("FEAT-042 F042-06 空实例名在进程就绪前失败")
    void f04206EmptyInstanceNameFailsBeforeReady() {
        assertStartupFails(Map.of(
                "spring.config.additional-location", configResource("empty-instance-name.yml")),
                "EDPA_INSTANCE_NAME_EMPTY", "instance name", "实例名");
    }

    @ParameterizedTest(name = "invalid name: {0}")
    @ValueSource(strings = {"code assistant", "bad/id", "-bad"})
    @Tag("story-feat-042-f042-07")
    @Story("FEAT-042.F042-07: 非法实例名 fail-fast")
    @DisplayName("FEAT-042 F042-07 非法实例名在进程就绪前失败")
    void f04207InvalidInstanceNameFailsBeforeReady(String invalidName) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("deep-agent.instances." + invalidName + ".scenario", resourcePath("scenarios/code-review").toString());
        assertStartupFails(properties, "EDPA_INSTANCE_NAME_INVALID", "不合法", invalidName);
    }

    @Test
    @Tag("story-feat-042-f042-08")
    @Story("FEAT-042.F042-08: 重复实例名 fail-fast")
    @DisplayName("FEAT-042 F042-08 重复 YAML 实例 key 在进程就绪前失败")
    void f04208DuplicateInstanceNameFailsBeforeReady() {
        assertStartupFails(Map.of(
                "spring.config.additional-location", configResource("duplicate-instance-name.yml")),
                "duplicate key", "duplicate-instance-name.yml", "duplicate-agent");
    }

    @Test
    @Tag("story-feat-042-f042-09")
    @Story("FEAT-042.F042-09: 数字开头实例名非法")
    @DisplayName("FEAT-042 F042-09 数字开头实例名在进程就绪前失败")
    void f04209NumericLeadingInstanceNameFailsBeforeReady() {
        assertStartupFails(thirdInstance("1code", "NumericCode"),
                "EDPA_INSTANCE_NAME_INVALID", "1code", "须以字母开头");
    }

    @Test
    @Tag("story-feat-042-f042-10")
    @Story("FEAT-042.F042-10: 三个实例无二实例硬上限")
    @DisplayName("FEAT-042 F042-10 三个 EDPA 实例全部发布且顺序保持")
    void f04210ThreeInstancesPublishWithoutTwoInstanceLimit() throws Exception {
        try (SutStack three = startMulti(thirdInstance("audit-assistant", "AuditAssistant"), false)) {
            assertThat(agentIds(catalog(three))).containsExactly(CODE, DATA, "audit-assistant");
            assertCardRoute(three, CODE);
            assertCardRoute(three, DATA);
            assertCardRoute(three, "audit-assistant");
        }
    }

    @Test
    @Tag("story-feat-042-f042-11")
    @Story("FEAT-042.F042-11: 后置实例装配失败原子回滚")
    @DisplayName("FEAT-042 F042-11 后置实例装配失败使整个进程在 ready 前退出")
    void f04211LaterAssemblyFailureDoesNotPublishPartialService() {
        Path missing = Path.of(System.getProperty("user.dir"), "target", "feat042-missing-" + UUID.randomUUID());
        assertStartupFails(Map.of("deep-agent.instances.data-assistant.scenario", missing.toString()),
                "EDPA_INSTANCE_ASSEMBLY_FAILED", "EDPA_SCENARIO_LOAD_FAILED", "CARD_SKILLS_MISMATCH");
    }

    @Test
    @Tag("story-feat-042-f042-12")
    @Story("FEAT-042.F042-12: 工作空间缺省推导")
    @DisplayName("FEAT-042 F042-12 显式与缺省工作空间按实例区分")
    void f04212WorkspaceDefaultIsDerivedFromInstanceName() throws Exception {
        String log = Files.readString(managed(stack).logFile());
        String codeLine = requireLine(log, "[EDPA-MULTI] instance '" + CODE + "'");
        String dataLine = requireLine(log, "[EDPA-MULTI] instance '" + DATA + "'");
        assertThat(codeLine).contains("workspace=").contains("feat042-workspaces").contains("code");
        assertThat(dataLine).contains("workspace=workspaces/data-assistant");
        assertThat(codeLine).isNotEqualTo(dataLine);
    }

    @Test
    @Tag("story-feat-042-f042-13")
    @Story("FEAT-042.F042-13: 跨实例工作空间拒绝")
    @DisplayName("FEAT-042 F042-13 跨实例工作空间访问返回明确错误")
    void f04213CrossInstanceWorkspaceAccessIsRejected() {
        Assumptions.assumeTrue(false,
                "blocked: Feature/L2 expose no public workspace read/write operation or stable error contract");
    }

    @Test
    @Tag("story-feat-042-f042-14")
    @Story("FEAT-042.F042-14: 凭据不进入对端响应和日志")
    @DisplayName("FEAT-042 F042-14 实例凭据仅到达目标模型且不进入响应或日志")
    void f04214CredentialsStayOutOfPeerResponsesAndLogs() throws Exception {
        String codeCanary = canary("F042-14-CODE");
        String dataCanary = canary("F042-14-DATA");
        String codeResult = sendAndAwait(stack, CODE, "SendMessage", codeCanary, context("f042-14-code"));
        String dataResult = sendAndAwait(stack, DATA, "SendMessage", dataCanary, context("f042-14-data"));
        ModelCall codeCall = model.awaitCall(codeCanary);
        ModelCall dataCall = model.awaitCall(dataCanary);

        assertThat(codeCall.authorization()).contains(CODE_CREDENTIAL).doesNotContain(DATA_CREDENTIAL);
        assertThat(dataCall.authorization()).contains(DATA_CREDENTIAL).doesNotContain(CODE_CREDENTIAL);
        assertThat(codeResult).doesNotContain(CODE_CREDENTIAL, DATA_CREDENTIAL);
        assertThat(dataResult).doesNotContain(CODE_CREDENTIAL, DATA_CREDENTIAL);
        assertThat(Files.readString(managed(stack).logFile())).doesNotContain(CODE_CREDENTIAL, DATA_CREDENTIAL);
    }

    @Test
    @Tag("story-feat-042-f042-15")
    @Story("FEAT-042.F042-15: 凭据不进入对端错误")
    @DisplayName("FEAT-042 F042-15 受控模型错误不泄露任何实例凭据")
    void f04215CredentialsStayOutOfPublicErrorSurface() throws Exception {
        String marker = "F042_FORCE_401_" + UUID.randomUUID();
        String result = sendAndAwait(stack, DATA, "SendMessage", marker, context("f042-15"));
        ModelCall call = model.awaitCall(marker);

        assertThat(call.authorization()).contains(DATA_CREDENTIAL).doesNotContain(CODE_CREDENTIAL);
        assertThat(result).containsAnyOf("FAILED", "failed", "error", "ERROR");
        assertThat(result).doesNotContain(CODE_CREDENTIAL, DATA_CREDENTIAL);
        assertThat(Files.readString(managed(stack).logFile())).doesNotContain(CODE_CREDENTIAL, DATA_CREDENTIAL);
    }

    @Test
    @Tag("story-feat-042-f042-16")
    @Story("FEAT-042.F042-16: 实例 Card 技能真实性")
    @DisplayName("FEAT-042 F042-16 每张实例 Card 只声明本场景真实技能")
    void f04216CardsExposeOnlyScenarioSkills() throws Exception {
        JsonNode codeCard = card(stack, CODE);
        JsonNode dataCard = card(stack, DATA);

        assertThat(skillIds(codeCard)).containsExactly("code_review_skill");
        assertThat(skillIds(dataCard)).containsExactly("analyze_data_skill");
        assertThat(codeCard.path("skills").toString()).contains("Code Review").doesNotContain("Analyze Data");
        assertThat(dataCard.path("skills").toString()).contains("Analyze Data").doesNotContain("Code Review");
    }

    @Test
    @Tag("story-feat-042-f042-20")
    @Story("FEAT-042.F042-20: Redis 同步会话隔离")
    @DisplayName("FEAT-042 F042-20 Redis 重启恢复后同步会话按实例隔离")
    void f04220RedisSyncSessionsRemainIsolatedAfterRestart() throws Exception {
        redisHistoryIsolation("SendMessage", "F042-20");
    }

    @Test
    @Tag("story-feat-042-f042-21")
    @Story("FEAT-042.F042-21: Redis 流式会话隔离")
    @DisplayName("FEAT-042 F042-21 Redis 重启恢复后流式会话按实例隔离")
    void f04221RedisStreamingSessionsRemainIsolatedAfterRestart() throws Exception {
        redisHistoryIsolation("SendStreamingMessage", "F042-21");
    }

    @Test
    @Tag("story-feat-042-f042-22")
    @Story("FEAT-042.F042-22: Redis 异步任务隔离")
    @DisplayName("FEAT-042 F042-22 Redis 重启恢复后任务归属按实例隔离")
    void f04222RedisAsyncTasksRemainIsolatedAfterRestart() throws Exception {
        try (SutStack target = startMulti(Map.of(), false)) {
            String shared = context("f042-22-shared");
            String taskA = requireTaskId(send(target, CODE, "SendMessage", canary("F042-22-A"), shared).body());
            String taskB = requireTaskId(send(target, DATA, "SendMessage", canary("F042-22-B"), shared).body());
            assertThat(taskA).isNotEqualTo(taskB);
            target.stop(EDPA);
            target.start(EDPA);

            assertTaskVisible(operation(target, CODE, "GetTask", taskA), taskA);
            assertTaskVisible(operation(target, DATA, "GetTask", taskB), taskB);
            assertRpcError(operation(target, DATA, "GetTask", taskA));
            assertRpcError(operation(target, CODE, "GetTask", taskB));
        }
    }

    @Test
    @Tag("story-feat-042-f042-23")
    @Story("FEAT-042.F042-23: A2A metadata 身份冲突")
    @DisplayName("FEAT-042 F042-23 A2A metadata 按裁决后的实例身份合同验证")
    void f04223ResponseMetadataCarriesReviewedIdentityContract() {
        Assumptions.assumeTrue(false,
                "blocked: Feature requires identity metadata while L2 explicitly forbids custom metadata");
    }

    @Test
    @Tag("story-feat-042-f042-24")
    @Story("FEAT-042.F042-24: 首实例为默认实例")
    @DisplayName("FEAT-042 F042-24 根 A2A 请求进入首个声明实例")
    void f04224RootA2aUsesFirstDeclaredInstance() throws Exception {
        assertThat(catalog(stack).path("defaultAgent").asText()).isEqualTo(CODE);
        String marker = canary("F042-24");
        String result = sendAndAwait(stack, null, "SendMessage", marker, context("f042-24"));
        ModelCall call = model.awaitCall(marker);
        assertThat(call.path()).contains("/code/v1/");
        assertThat(call.body()).contains(CODE_IDENTITY).doesNotContain(DATA_IDENTITY);
        assertThat(result).contains(CODE_IDENTITY, marker).doesNotContain(DATA_IDENTITY);
    }

    @Test
    @Tag("story-feat-042-f042-25")
    @Story("FEAT-042.F042-25: 空 instances 单实例回退")
    @DisplayName("FEAT-042 F042-25 显式空 instances 保持单实例 Card 与同步流式入口")
    void f04225EmptyInstancesMapUsesLegacySingleInstanceMode() throws Exception {
        try (SutStack single = startSingle(Map.of(
                "spring.config.additional-location", configResource("empty-instances.yml")))) {
            assertLegacySurface(single, "F042-25");
        }
    }

    @Test
    @Tag("story-feat-042-f042-26")
    @Story("FEAT-042.F042-26: 缺省 instances 单实例兼容")
    @DisplayName("FEAT-042 F042-26 缺省 instances 与显式空 Map 的单实例合同一致")
    void f04226MissingInstancesMatchesEmptyMapLegacyContract() throws Exception {
        try (SutStack empty = startSingle(Map.of(
                "spring.config.additional-location", configResource("empty-instances.yml")));
                SutStack missing = startSingle(Map.of())) {
            JsonNode emptyCard = rootCard(empty);
            JsonNode missingCard = rootCard(missing);
            for (String field : List.of("name", "description", "version", "capabilities",
                    "defaultInputModes", "defaultOutputModes", "skills")) {
                assertThat(missingCard.path(field)).as(field).isEqualTo(emptyCard.path(field));
            }
            assertLegacySurface(missing, "F042-26");
        }
    }

    private SutStack.Builder builder(TestConfig config) {
        return SutStack.builder(config).launcher(new ExecClassifierLauncher(config));
    }

    private SutStack startMulti(Map<String, String> extra, boolean preserveDataWorkspaceDefault) {
        return builder(config).agent(EDPA, agent -> {
            configureMulti(agent, preserveDataWorkspaceDefault);
            extra.forEach(agent::property);
        }).start();
    }

    private SutStack startSingle(Map<String, String> extra) {
        return builder(config).agent(EDPA, agent -> {
            agent.profile("single");
            configureShared(agent);
            agent.property("deep-agent.scenario-home", resourcePath("scenarios/code-review").toString())
                    .property("deep-agent.backend.client_provider", "openai")
                    .property("deep-agent.backend.api_key", CODE_CREDENTIAL)
                    .property("deep-agent.backend.api_base", model.apiBase("code"))
                    .property("deep-agent.backend.verify_ssl", "false")
                    .property("deep-agent.model.model", CODE_MODEL);
            extra.forEach(agent::property);
        }).start();
    }

    private void configureMulti(SutStack.AgentBuilder agent, boolean preserveDataWorkspaceDefault) {
        String run = UUID.randomUUID().toString();
        Path codeWorkspace = Path.of(System.getProperty("user.dir"), "target", "feat042-workspaces", run, "code")
                .toAbsolutePath();
        agent.profile("multi");
        configureShared(agent);
        agent.property("deep-agent.max-iterations", "7")
                .property("deep-agent.backend.client_provider", "openai")
                .property("deep-agent.backend.api_key", CODE_CREDENTIAL)
                .property("deep-agent.backend.api_base", model.apiBase("code"))
                .property("deep-agent.backend.verify_ssl", "false")
                .property("deep-agent.backend.max_retries", "0")
                .property("deep-agent.backend.llm_retry_max", "0")
                .property("deep-agent.model.model", "feat042-global-model")
                .property("deep-agent.instances.code-assistant.scenario",
                        resourcePath("scenarios/code-review").toString())
                .property("deep-agent.instances.code-assistant.workspace", codeWorkspace.toString())
                .property("deep-agent.instances.code-assistant.max-iterations", "3")
                .property("deep-agent.instances.code-assistant.model.model", CODE_MODEL)
                .property("deep-agent.instances.data-assistant.scenario",
                        resourcePath("scenarios/data-analysis").toString())
                .property("deep-agent.instances.data-assistant.backend.client_provider", "openai")
                .property("deep-agent.instances.data-assistant.backend.api_key", DATA_CREDENTIAL)
                .property("deep-agent.instances.data-assistant.backend.api_base", model.apiBase("data"))
                .property("deep-agent.instances.data-assistant.backend.verify_ssl", "false")
                .property("deep-agent.instances.data-assistant.backend.max_retries", "0")
                .property("deep-agent.instances.data-assistant.backend.llm_retry_max", "0")
                .property("deep-agent.instances.data-assistant.model.model", DATA_MODEL);
        if (!preserveDataWorkspaceDefault) {
            Path workspace = Path.of(System.getProperty("user.dir"), "target", "feat042-workspaces", run, "data")
                    .toAbsolutePath();
            agent.property("deep-agent.instances.data-assistant.workspace", workspace.toString());
        }
    }

    private void configureShared(SutStack.AgentBuilder agent) {
        String redisHost = redis.getHost();
        String redisPort = String.valueOf(redis.getMappedPort(6379));
        agent.property("openjiuwen.service.a2a.remote-agents[0].name", "unused-fixture-agent")
                .property("openjiuwen.service.a2a.remote-agents[0].url", "http://127.0.0.1:1/a2a")
                .property("deep-agent.redis.host", redisHost)
                .property("deep-agent.redis.port", redisPort)
                .property("deep-agent.redis.connect-timeout-ms", "2000")
                .property("deep-agent.redis.socket-timeout-ms", "5000")
                .property("openjiuwen.service.middleware.checkpointer.type", "redis")
                .property("openjiuwen.service.middleware.checkpointer.redis-ref", "default")
                .property("openjiuwen.service.middleware.redis.default.type", "standalone")
                .property("openjiuwen.service.middleware.redis.default.host", redisHost)
                .property("openjiuwen.service.middleware.redis.default.port", redisPort);
    }

    private Map<String, String> thirdInstance(String id, String cardName) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("deep-agent.instances." + id + ".scenario", resourcePath("scenarios/code-review").toString());
        properties.put("deep-agent.instances." + id + ".workspace",
                Path.of(System.getProperty("user.dir"), "target", "feat042-workspaces", UUID.randomUUID().toString(), id)
                        .toAbsolutePath().toString());
        properties.put("deep-agent.instances." + id + ".max-iterations", "3");
        properties.put("deep-agent.instances." + id + ".model.model", CODE_MODEL);
        String card = "openjiuwen.service.a2a.agents." + id;
        properties.put(card + ".agent-name", cardName);
        properties.put(card + ".agent-description", "FEAT042 third instance");
        properties.put(card + ".skills[0].id", "code_review_skill");
        properties.put(card + ".skills[0].name", "Code Review");
        properties.put(card + ".skills[0].description", "Review code for correctness and maintainability");
        properties.put(card + ".skills[0].tags[0]", "code");
        properties.put(card + ".skills[0].input-modes[0]", "text");
        properties.put(card + ".skills[0].output-modes[0]", "text");
        return properties;
    }

    private void assertStartupFails(Map<String, String> properties, String... expectedMarkers) {
        assertThatThrownBy(() -> {
            try (SutStack ignored = startMulti(properties, false)) {
                throw new AssertionError("negative variant unexpectedly became ready");
            }
        }).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("process exited before becoming ready")
                .satisfies(error -> {
                    String message = String.valueOf(error.getMessage()).toLowerCase();
                    assertThat(List.of(expectedMarkers).stream()
                            .map(String::toLowerCase)
                            .anyMatch(message::contains))
                            .as("startup error must contain one target marker %s, but was:%n%s",
                                    List.of(expectedMarkers), error.getMessage())
                            .isTrue();
                });
    }

    private void redisHistoryIsolation(String method, String caseId) throws Exception {
        try (SutStack target = startMulti(Map.of(), false)) {
            String shared = context(caseId.toLowerCase() + "-shared");
            String markerA = canary(caseId + "-A");
            String markerB = canary(caseId + "-B");
            sendAndAwait(target, CODE, method, markerA, shared);
            sendAndAwait(target, DATA, method, markerB, shared);
            target.stop(EDPA);
            target.start(EDPA);

            String recallA = canary(caseId + "-RECALL-A");
            String recallB = canary(caseId + "-RECALL-B");
            String resultA = sendAndAwait(target, CODE, method, recallA, shared);
            String resultB = sendAndAwait(target, DATA, method, recallB, shared);
            ModelCall callA = model.awaitCall(recallA);
            ModelCall callB = model.awaitCall(recallB);
            assertThat(callA.body()).contains(markerA).doesNotContain(markerB);
            assertThat(callB.body()).contains(markerB).doesNotContain(markerA);
            assertThat(resultA).contains(markerA).doesNotContain(markerB);
            assertThat(resultB).contains(markerB).doesNotContain(markerA);
        }
    }

    private void assertLegacySurface(SutStack target, String caseId) throws Exception {
        JsonNode card = rootCard(target);
        assertThat(card.path("name").asText()).isNotBlank();
        assertThat(card.path("version").asText()).isNotBlank();
        assertThat(card.path("capabilities").isObject()).isTrue();
        assertThat(get(target, "/a2a/agents").statusCode()).isEqualTo(404);

        String sync = canary(caseId + "-SYNC");
        String stream = canary(caseId + "-STREAM");
        assertThat(sendAndAwait(target, null, "SendMessage", sync, context(caseId + "-sync"))).contains(sync);
        HttpResponse<String> streamed = send(target, null, "SendStreamingMessage", stream,
                context(caseId + "-stream"));
        assertThat(streamed.statusCode()).isEqualTo(200);
        assertThat(contentType(streamed)).containsIgnoringCase("text/event-stream");
        assertThat(streamed.body()).contains(stream);
    }

    private JsonNode rootCard(SutStack target) throws Exception {
        HttpResponse<String> response = get(target, "/.well-known/agent-card.json");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private JsonNode catalog(SutStack target) throws Exception {
        HttpResponse<String> response = get(target, "/a2a/agents");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private JsonNode card(SutStack target, String id) throws Exception {
        HttpResponse<String> response = get(target, "/a2a/agents/" + id + "/.well-known/agent-card.json");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private void assertCardRoute(SutStack target, String id) throws Exception {
        JsonNode card = card(target, id);
        assertThat(card.path("name").asText()).isNotBlank();
        assertThat(card.path("supportedInterfaces").path(0).path("url").asText())
                .endsWith("/a2a/agents/" + id);
    }

    private HttpResponse<String> get(SutStack target, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(target.baseUrl(EDPA) + path))
                        .timeout(REQUEST_TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> send(SutStack target, String id, String method, String text, String context)
            throws Exception {
        String path = id == null ? "/a2a" : "/a2a/agents/" + id;
        boolean streaming = "SendStreamingMessage".equals(method);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(target.baseUrl(EDPA) + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json");
        if (streaming) {
            request.header("Accept", "text/event-stream");
        }
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(rpc(method, text, context, null))).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> operation(SutStack target, String id, String method, String taskId)
            throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(target.baseUrl(EDPA) + "/a2a/agents/" + id))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(rpc(method, null, null, taskId))).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String sendAndAwait(SutStack target, String id, String method, String text, String context)
            throws Exception {
        HttpResponse<String> response = send(target, id, method, text, context);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        if ("SendStreamingMessage".equals(method) || isTerminal(response.body())) {
            return response.body();
        }
        String taskId = requireTaskId(response.body());
        String owner = id == null ? CODE : id;
        return Awaitility.await("FEAT042 terminal task " + taskId)
                .atMost(REQUEST_TIMEOUT)
                .pollInterval(Duration.ofMillis(500))
                .until(() -> operation(target, owner, "GetTask", taskId).body(),
                        Feat042EdpaMultiInstanceBlackboxTest::isTerminal);
    }

    private static String rpc(String method, String text, String context, String taskId) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        if ("GetTask".equals(method)) {
            params.put("id", taskId);
        } else {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "ROLE_USER");
            message.put("messageId", UUID.randomUUID().toString());
            message.put("parts", List.of(Map.of("text", text)));
            message.put("contextId", context);
            params.put("message", message);
        }
        return JSON.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "id", UUID.randomUUID().toString(), "method", method, "params", params));
    }

    private static boolean isTerminal(String body) {
        return body != null && (body.contains("TASK_STATE_COMPLETED")
                || body.contains("TASK_STATE_FAILED")
                || body.contains("TASK_STATE_CANCELED")
                || body.contains("TASK_STATE_CANCELLED")
                || body.contains("TASK_STATE_REJECTED"));
    }

    private static String requireTaskId(String body) throws Exception {
        String trimmed = body.strip();
        if (trimmed.startsWith("{")) {
            String id = taskId(JSON.readTree(trimmed));
            assertThat(id).as(body).isNotBlank();
            return id;
        }
        for (JsonNode frame : ssePayloads(body)) {
            String id = taskId(frame);
            if (!id.isBlank()) {
                return id;
            }
        }
        throw new AssertionError("No task ID in response: " + body);
    }

    private static String taskId(JsonNode root) {
        String id = root.path("result").path("task").path("id").asText();
        if (id.isBlank()) {
            id = root.path("result").path("id").asText();
        }
        if (id.isBlank()) {
            id = root.path("result").path("statusUpdate").path("taskId").asText();
        }
        if (id.isBlank()) {
            id = root.path("result").path("artifactUpdate").path("taskId").asText();
        }
        return id;
    }

    private static List<JsonNode> ssePayloads(String body) throws Exception {
        List<JsonNode> frames = new ArrayList<>();
        for (String line : body.lines().toList()) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String payload = line.substring("data:".length()).strip();
            if (!payload.isEmpty() && !"[DONE]".equals(payload)) {
                frames.add(JSON.readTree(payload));
            }
        }
        return frames;
    }

    private static void assertTaskVisible(HttpResponse<String> response, String taskId) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains(taskId).doesNotContain("\"error\"");
    }

    private static void assertRpcError(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode payload = response.body().strip().startsWith("{")
                ? JSON.readTree(response.body()) : ssePayloads(response.body()).get(0);
        assertThat(payload.path("error").isObject()).as(response.body()).isTrue();
    }

    private static List<String> agentIds(JsonNode listing) {
        List<String> ids = new ArrayList<>();
        listing.path("agents").forEach(node -> ids.add(node.asText()));
        return ids;
    }

    private static List<String> skillIds(JsonNode card) {
        List<String> ids = new ArrayList<>();
        card.path("skills").forEach(skill -> ids.add(skill.path("id").asText()));
        return ids;
    }

    private static String requireLine(String text, String marker) {
        return text.lines().filter(line -> line.contains(marker)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing log marker '" + marker + "' in:\n" + text));
    }

    private ManagedSutInstance managed(SutStack target) {
        return (ManagedSutInstance) target.managedInstance(EDPA);
    }

    private static String contentType(HttpResponse<?> response) {
        return response.headers().firstValue("content-type").orElse("");
    }

    private static String canary(String label) {
        return "FEAT042_" + label + "_" + UUID.randomUUID();
    }

    private static String context(String label) {
        return "feat042-" + label + "-" + UUID.randomUUID();
    }

    private static Path resourcePath(String relative) {
        String root = "testdata/integration/deepagent_deepresearch/feat042/" + relative;
        try {
            URI uri = Feat042EdpaMultiInstanceBlackboxTest.class.getClassLoader().getResource(root).toURI();
            return Path.of(uri).toAbsolutePath();
        } catch (Exception failure) {
            throw new IllegalStateException("Missing FEAT042 test resource: " + root, failure);
        }
    }

    private static String configResource(String name) {
        return resourcePath("config/" + name).toUri().toString();
    }

    private record ModelCall(String path, String authorization, String model, String body) {
    }

    private static final class ModelAuditFixture implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;
        private final List<ModelCall> calls = new CopyOnWriteArrayList<>();

        private ModelAuditFixture(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
        }

        static ModelAuditFixture start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                ExecutorService executor = Executors.newCachedThreadPool();
                ModelAuditFixture fixture = new ModelAuditFixture(server, executor);
                server.createContext("/", fixture::reply);
                server.setExecutor(executor);
                server.start();
                return fixture;
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot start FEAT042 model fixture", failure);
            }
        }

        String apiBase(String instance) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + instance + "/v1";
        }

        ModelCall awaitCall(String canary) {
            return Awaitility.await("model request " + canary)
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(100))
                    .until(() -> calls.stream().filter(call -> call.body().contains(canary)).findFirst().orElse(null),
                            call -> call != null);
        }

        private void reply(HttpExchange exchange) throws IOException {
            try (exchange) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                JsonNode request = JSON.readTree(body);
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                String modelName = request.path("model").asText();
                calls.add(new ModelCall(exchange.getRequestURI().getPath(),
                        authorization == null ? "" : authorization, modelName, body));

                if (body.contains("F042_FORCE_401")) {
                    byte[] error = JSON.writeValueAsBytes(Map.of("error", Map.of(
                            "message", "controlled model rejection", "type", "authentication_error",
                            "code", "controlled_rejection")));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(401, error.length);
                    exchange.getResponseBody().write(error);
                    return;
                }

                String identity = body.contains(CODE_IDENTITY) ? CODE_IDENTITY
                        : body.contains(DATA_IDENTITY) ? DATA_IDENTITY : "FEAT042_UNKNOWN_SCENARIO";
                String userText = userTexts(request.path("messages"));
                String content = identity + " " + userText;
                if (request.path("stream").asBoolean()) {
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    String first = JSON.writeValueAsString(envelope("chat.completion.chunk", modelName,
                            Map.of("index", 0,
                                    "delta", Map.of("role", "assistant", "content", content))));
                    String last = JSON.writeValueAsString(envelope("chat.completion.chunk", modelName,
                            Map.of("index", 0, "delta", Map.of(), "finish_reason", "stop")));
                    byte[] response = ("data: " + first + "\n\ndata: " + last + "\n\ndata: [DONE]\n\n")
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                } else {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    byte[] response = JSON.writeValueAsBytes(envelope("chat.completion", modelName,
                            Map.of("index", 0,
                                    "message", Map.of("role", "assistant", "content", content),
                                    "finish_reason", "stop")));
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                }
            }
        }

        private static Map<String, Object> envelope(String object, String modelName, Map<String, Object> choice) {
            return Map.of("id", "feat042-model", "object", object, "created", 1,
                    "model", modelName, "choices", List.of(choice));
        }

        private static String userTexts(JsonNode messages) {
            List<String> texts = new ArrayList<>();
            if (messages.isArray()) {
                for (JsonNode message : messages) {
                    if (!"user".equalsIgnoreCase(message.path("role").asText())) {
                        continue;
                    }
                    JsonNode content = message.path("content");
                    if (content.isTextual()) {
                        texts.add(content.asText());
                    } else if (content.isArray()) {
                        content.forEach(part -> {
                            String text = part.path("text").asText();
                            if (!text.isBlank()) {
                                texts.add(text);
                            }
                        });
                    }
                }
            }
            String joined = String.join(" ", texts);
            return joined.length() <= 4000 ? joined : joined.substring(joined.length() - 4000);
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static final class ExecClassifierLauncher implements SutLauncher {
        private static final String WELL_KNOWN = "/.well-known/agent.json";
        private final TestConfig config;
        private final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        private ExecClassifierLauncher(TestConfig config) {
            this.config = config;
        }

        @Override
        public SutInstance start(SutAgent agent, AgentConfig agentConfig) {
            Path jar = executableJar(agent);
            int port = agentConfig.port() > 0 ? agentConfig.port() : freePort();
            agentConfig.port(port);
            agentConfig.property("openjiuwen.service.a2a.public-url", "http://127.0.0.1:" + port);
            Path log = logFile(agent.name());
            try {
                Files.createDirectories(log.getParent());
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot create FEAT042 log directory " + log.getParent(), failure);
            }

            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            Map<String, String> systemProperties = new LinkedHashMap<>(
                    config.getStringMap("sut.java.system-properties"));
            systemProperties.putAll(agentConfig.jvmSystemProperties());
            systemProperties.putIfAbsent("LOG_HOME", log.getParent().toString());
            systemProperties.forEach((key, value) -> command.add("-D" + key + "=" + value));
            command.add("-jar");
            command.add(jar.toString());
            command.addAll(agentConfig.toProgramArgs());

            ProcessBuilder processBuilder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
            agentConfig.environment().forEach(processBuilder.environment()::put);
            Process process;
            try {
                process = processBuilder.start();
            } catch (IOException failure) {
                throw new IllegalStateException("Failed to start " + agent.name() + " from executable artifact", failure);
            }

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(
                    Math.max(120, config.getInt("sut.timeout.startup-seconds", 60)));
            try {
                while (System.nanoTime() < deadline) {
                    if (!process.isAlive()) {
                        throw exited(agent, log);
                    }
                    if (ready(port)) {
                        return new ManagedSutInstance(agent.name(), process.pid(), port,
                                "http://localhost:" + port, process, log);
                    }
                    Thread.sleep(250);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IllegalStateException("Interrupted while waiting for " + agent.name(), interrupted);
            } catch (RuntimeException failure) {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                throw failure;
            }
            process.destroyForcibly();
            throw new IllegalStateException(agent.name() + " did not become ready within timeout.\n--- log tail ---\n"
                    + tail(log));
        }

        private Path executableJar(SutAgent agent) {
            String repository = config.getString("sut.m2.repo",
                    Path.of(System.getProperty("user.home"), ".m2", "repository").toString());
            String group = agent.artifact().groupId().replace('.', '/');
            String artifact = agent.artifact().artifactId();
            String version = agent.artifact().version();
            Path jar = Path.of(repository, group, artifact, version,
                    artifact + "-" + version + "-exec.jar").toAbsolutePath();
            if (!Files.isRegularFile(jar)) {
                throw new IllegalStateException("Executable SUT classifier artifact not found: " + jar);
            }
            return jar;
        }

        private Path logFile(String agentName) {
            String configured = config.getString("sut.logging.dir");
            Path root = configured == null || configured.isBlank()
                    ? Path.of(System.getProperty("user.dir"), "target", "sit-logs") : Path.of(configured);
            return root.resolve(agentName + "-feat042-" + UUID.randomUUID()).resolve("stdout.log");
        }

        private boolean ready(int port) {
            try {
                HttpResponse<Void> response = http.send(HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + port + WELL_KNOWN))
                                .timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                return response.statusCode() == 200;
            } catch (Exception notReady) {
                return false;
            }
        }

        private static IllegalStateException exited(SutAgent agent, Path log) {
            return new IllegalStateException(agent.name()
                    + " process exited before becoming ready.\n--- log tail ---\n" + tail(log));
        }

        private static int freePort() {
            try (ServerSocket socket = new ServerSocket(0)) {
                socket.setReuseAddress(true);
                return socket.getLocalPort();
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot allocate FEAT042 SUT port", failure);
            }
        }

        private static String tail(Path log) {
            try {
                List<String> lines = Files.readAllLines(log);
                return String.join("\n", lines.subList(Math.max(0, lines.size() - 100), lines.size()));
            } catch (IOException failure) {
                return "(cannot read log: " + failure.getMessage() + ")";
            }
        }
    }
}

package com.huawei.ascend.sit.cases.integration.workflow_call;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.config.TestEnvironment;
import com.huawei.ascend.sit.lifecycle.ContainerFactory;
import com.huawei.ascend.sit.lifecycle.ManagedContainer;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.utils.RedisProbe;
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 费用报销审核的 <b>认证 redis 中间件变体</b>。流程、断言、协议参数化（4 种线协议）全部继承自
 * {@link AbstractExpenseReviewAcceptanceTest}；本类把类级栈整体改成<b>密码场景</b> —— redis 容器 {@code --requirepass}，
 * 两 agent 用同一 canary 鉴权 —— 并加两个 hermetic 门禁。<b>拉起逻辑与 {@link PlanAgentDirectStreamingRedisTest} 同型</b>
 * （profile+env），叠加一层密码（容器侧 requirepass + agent 侧 REDIS_PASSWORD env）。
 *
 * <p><b>redis 激活机制 = balance 同款 profile+env + 密码</b>：expense-review jar（workflow + main 双 profile）内置
 * {@code application-redis.yml}（与 edpa overlay 逐字节相同），一块 {@code openjiuwen.service.middleware.*} 把 checkpointer
 * 与 A2A TaskStore 切到 redis，host/port/password 留 {@code ${REDIS_HOST:}}/{@code ${REDIS_PORT:}}/{@code ${REDIS_PASSWORD:}}
 * 占位符。{@link #withRedis} 做 {@code .profile("redis")} + 两条 host/port {@code serviceBinding}（动态 Testcontainers Redis 的
 * {@code {{host}}}/{@code {{port}}} → {@code --REDIS_HOST}/{@code --REDIS_PORT}）+ 一条 {@code REDIS_PASSWORD} env（canary）。
 * redis 容器由 {@link AuthenticatedRedisFactory} 拉起，带 {@code --requirepass <canary>}。
 *
 * <p><b>canary</b>（{@link #redisPassword}）= {@code "expense-redis-auth-" + UUID}，buildStack 时生成、本次运行唯一：
 * <ul>
 *   <li>喂给 redis 容器（requirepass）+ 两 agent（REDIS_PASSWORD env）⇒ SUT 用它 AUTH。</li>
 *   <li>boot 门禁的 AUTH 探针用它（{@link RedisProbe} 3 参构造）⇒ 证明 redis 确实要求密码且 canary 正确（AUTH 端到端生效）。</li>
 *   <li>脱敏门禁查它<b>不</b>出现在任何 agent 日志（唯一串 ⇒ 全日志 doesNotContain 即成立）。</li>
 * </ul>
 *
 * <p><b>两个 hermetic 门禁（均无需 LLM，启动即发）</b>：
 * <ul>
 *   <li>{@link #redisMiddlewareActivatesOnBoot()} —— redis 中间件激活：profile 行 + checkpointer/datasource 证据
 *       （agent-specific，同 balance 教训）+ <b>AUTH 探针可达</b>（端到端证明密码生效，非仅配置态）。</li>
 *   <li>{@link #redisPasswordDoesNotLeakInLogs()} —— <b>密码日志脱敏</b>（FEAT-003 §5.1.5 MUST 的 acceptance 落点）：
 *       正证据 {@code passwordConfigured=true}（openjiuwen {@code RedisConnectionAssembler} 脱敏布尔诊断，不含密码本身），
 *       负证据 canary 不进任何 agent 日志。env（非命令行参）携带密码 ⇒ Spring INFO 不回显，降低框架侧泄漏面。
 *       对应 Feat003 {@code feat003AuthenticatedRedisSucceedsWithoutLeakingPassword}。</li>
 * </ul>
 *
 * @see ExpenseReviewAcceptanceTest in-memory 变体
 * @see PlanAgentDirectStreamingRedisTest edpa 族 redis 变体（拉起逻辑同型，无密码）
 */
@Tag("integration")
@Feature("FEAT-003: 智能体任务状态缓存")
@Stories({
        @Story("wf.password-desensitize: 认证 Redis 密码日志脱敏(§5.1.5 MUST)"),
        @Story("wf.redis-standalone-activate: 单机接入+启动策略日志"),
        @Story("wf.task-state-reuse: A2A Task 与 agent 状态复用同一 Redis"),
        @Story("wf.input-required: 审批中断恢复经 redis 持久化")
})
class ExpenseReviewRedisAcceptanceTest extends AbstractExpenseReviewAcceptanceTest {

    private static final String CHECKPOINTER_MARKER = "Begin to initializing checkpointer with type: ";
    /** workflow agent 的 Redis 激活证据：RedisDatasourceDiagnostics 的数据源已选行（checkpointer 行可能不打印）。 */
    private static final String REDIS_DATASOURCE_MARKER = "Runtime Redis datasource selected:";
    private static final String REDIS_CLIENT_MARKER = "RuntimeRedisClient=";
    /** Spring Boot 启动行：两个 agent 都会在 redis profile 下打这行（额外的低成本置信度）。 */
    private static final String PROFILE_ACTIVE_MARKER = "The following 1 profile is active: \"redis\"";
    /** openjiuwen RedisConnectionAssembler 的脱敏布尔诊断（passwordConfigured=true 表示密码已配置，不含密码本身）。 */
    private static final String PASSWORD_CONFIGURED_MARKER = "passwordConfigured=true";

    /** 本次运行的 Redis 密码 canary（buildStack 时生成；boot 门禁用它 AUTH 探针，脱敏门禁查它不进 agent 日志）。 */
    private String redisPassword;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // 第一行 gate：非 openjiuwen 在基类 .start() 之前 abort，不拉容器。
        Assumptions.assumeTrue(TestEnvironment.current() == TestEnvironment.OPENJIUWEN,
                "openjiuwen profile only — re-run with -Dtest.env=openjiuwen (需 LLM_API_KEY 等)");
        // canary 本次运行唯一：喂给 redis 容器（requirepass）+ 两 agent（REDIS_PASSWORD env）。
        redisPassword = "expense-redis-auth-" + UUID.randomUUID();
        // redis 经 serviceBinding("redis",...) 引用 → 栈自管拉起（这里走 AuthenticatedRedisFactory 加 --requirepass）；
        // expense-review jar 内置 application-redis.yml（同 edpa overlay），profile=redis + REDIS_HOST/PORT/PASSWORD 占位符接住。
        return SutStack.builder(config)
                .containerFactory(new AuthenticatedRedisFactory(redisPassword))
                .agent(WORKFLOW_AGENT, a -> withRedis(a, redisPassword))
                .agent(ENTRY_AGENT, a -> withRedis(a, redisPassword).downstream(WORKFLOW_AGENT));
    }

    /**
     * Hermetic, no-LLM gate：两 agent 都在 redis profile 下启动、Redis 中间件激活、AUTH 探针可达。
     *
     * <p>激活证据因 agent 而异（同 balance 教训，<b>不假设两 agent 同打一行</b>）：
     * <ul>
     *   <li>{@code expense-review-main}（ReActAgent，有 Runner/checkpointer）—— checkpointer 启动行。</li>
     *   <li>{@code expense-review-workflow}（DAG agent，checkpointer 行可能不打印）—— 数据源诊断行。</li>
     * </ul>
     * 两者都打 Spring profile 激活行。AUTH 探针（{@link RedisProbe} 3 参构造）用 canary 鉴权读 DBSIZE ——
     * 证明 redis 确实要求密码且 canary 正确（端到端 AUTH 生效，非仅 {@code passwordConfigured=true} 配置态）。
     */
    @Test
    @DisplayName("redis 中间件在 workflow + main 上激活（启动日志 + AUTH 探针可达）")
    void redisMiddlewareActivatesOnBoot() {
        assertProfileActive(stack, WORKFLOW_AGENT);
        assertProfileActive(stack, ENTRY_AGENT);
        assertCheckpointerLog(stack, ENTRY_AGENT, "redis");
        assertRedisDatasourceWired(stack, WORKFLOW_AGENT);
        assertThat(probe().dbsize()).as("redis DBSIZE reachable (AUTHed)").isGreaterThanOrEqualTo(0);
    }

    /**
     * Hermetic, no-LLM 密码脱敏门禁：canary 不泄漏进任何 agent 日志。双证据：
     * <b>正</b>：两 agent 日志含 {@code passwordConfigured=true}（脱敏布尔诊断，证明 SUT 识别到密码配置）；
     * <b>负</b>：canary（本次运行 UUID 唯一串）<b>不</b>出现在任何 agent 日志（证明未泄漏）。
     * canary 唯一 ⇒ 全日志 doesNotContain 即成立，无需 LogOffsets 增量。env 携带密码 ⇒ Spring INFO 不回显。
     */
    @Test
    @DisplayName("认证 Redis 密码 canary 不泄漏进任何 agent 日志（passwordConfigured=true）")
    void redisPasswordDoesNotLeakInLogs() {
        assertPasswordConfigured(stack, WORKFLOW_AGENT);
        assertPasswordConfigured(stack, ENTRY_AGENT);
        assertNoPasswordLeak(stack, redisPassword);
    }

    // --- 助手 ---

    /**
     * 给 agent 加 {@code redis} profile + host/port service-binding + {@code REDIS_PASSWORD} env（canary）。
     * env（非命令行参）携带密码：Spring INFO 不回显 env，降低框架侧泄漏面。
     */
    private static SutStack.AgentBuilder withRedis(SutStack.AgentBuilder b, String password) {
        return b.profile("redis")
                .serviceBinding("redis", "REDIS_HOST", "{{host}}")
                .serviceBinding("redis", "REDIS_PORT", "{{port}}")
                .env("REDIS_PASSWORD", password);
    }

    private RedisProbe probe() {
        // stack.serviceUrl 正常化为 http://host:port；用 URI 拆出 host/port；3 参构造带 canary AUTH。
        URI u = URI.create(stack.serviceUrl("redis"));
        return new RedisProbe(u.getHost(), u.getPort(), redisPassword);
    }

    private Path logOf(SutStack s, String agentName) {
        var instance = s.managedInstance(agentName);
        assertThat(instance)
                .as("managed agent '%s' for log gate", agentName)
                .isNotNull()
                .isInstanceOf(ManagedSutInstance.class);
        return ((ManagedSutInstance) instance).logFile();
    }

    private void assertProfileActive(SutStack s, String agentName) {
        assertThat(readLog(agentName, logOf(s, agentName)))
                .as(agentName + " 启动须激活 redis profile: " + PROFILE_ACTIVE_MARKER)
                .contains(PROFILE_ACTIVE_MARKER);
    }

    private void assertCheckpointerLog(SutStack s, String agentName, String expectedType) {
        Path log = logOf(s, agentName);
        String last = null;
        try {
            for (String line : Files.readAllLines(log)) {
                int idx = line.indexOf(CHECKPOINTER_MARKER);
                if (idx >= 0) {
                    last = line.substring(idx + CHECKPOINTER_MARKER.length()).split(",", 2)[0].trim();
                }
            }
        } catch (IOException e) {
            throw new AssertionError("无法读取 " + agentName + " 日志 " + log + ": " + e.getMessage(), e);
        }
        assertThat(last)
                .as(agentName + " 日志须含 checkpointer 启动行 " + CHECKPOINTER_MARKER.trim())
                .isNotNull();
        assertThat(last).as(agentName + " checkpointer type").isEqualTo(expectedType);
    }

    /**
     * workflow agent 侧的 Redis 激活断言：须出现 {@code RedisDatasourceDiagnostics} 的「Runtime Redis datasource selected」
     * 行并附带非空 {@code RuntimeRedisClient=}（证明 Redis 客户端已实例化）。workflow 的 checkpointer 行可能不打印，故用此稳定行。
     */
    private void assertRedisDatasourceWired(SutStack s, String agentName) {
        String blob = readLog(agentName, logOf(s, agentName));
        assertThat(blob)
                .as(agentName + " 须含 Redis 数据源诊断行: " + REDIS_DATASOURCE_MARKER)
                .contains(REDIS_DATASOURCE_MARKER);
        assertThat(blob)
                .as(agentName + " Redis 数据源行须含已选 RuntimeRedisClient")
                .contains(REDIS_CLIENT_MARKER);
    }

    /** 正证据：agent 日志含 {@code passwordConfigured=true}（脱敏布尔诊断，证明 SUT 识别到密码配置）。 */
    private void assertPasswordConfigured(SutStack s, String agentName) {
        assertThat(readLog(agentName, logOf(s, agentName)))
                .as(agentName + " 须识别到 Redis 密码配置: " + PASSWORD_CONFIGURED_MARKER)
                .contains(PASSWORD_CONFIGURED_MARKER);
    }

    /** 负证据：canary（本次运行 UUID 唯一串）不得出现在 agent 日志。两 agent 都查（不同 agent 类型，日志路径可能不同）。 */
    private void assertNoPasswordLeak(SutStack s, String canary) {
        for (String agent : new String[] {WORKFLOW_AGENT, ENTRY_AGENT}) {
            assertThat(readLog(agent, logOf(s, agent)))
                    .as(agent + " 日志不得泄漏 Redis 密码 canary")
                    .doesNotContain(canary);
        }
    }

    private static String readLog(String agentName, Path log) {
        try {
            return Files.readString(log);
        } catch (IOException e) {
            throw new AssertionError("无法读取 " + agentName + " 日志 " + log + ": " + e.getMessage(), e);
        }
    }

    /**
     * 给 redis 容器加 {@code --requirepass <password>}（按名守卫，仅 {@code redis} 服务加，不误伤其它 backing service）。
     * 其余与 {@link com.huawei.ascend.sit.lifecycle.TestContainerFactory} 一致：GenericContainer + exposedPort + env。
     * 不挂 ContainerLogAppender（脱敏门禁只查 SUT agent 日志，不查 redis 容器日志；AUTH 失败会从 agent 侧 Jedis 报错可见）。
     */
    private static final class AuthenticatedRedisFactory implements ContainerFactory {
        private final String password;

        AuthenticatedRedisFactory(String password) {
            this.password = password;
        }

        @Override
        public ManagedContainer start(String name, String image, int port, Map<String, String> env) {
            GenericContainer<?> raw = new GenericContainer<>(DockerImageName.parse(image))
                    .withExposedPorts(port);
            if ("redis".equals(name)) {
                raw = raw.withCommand("redis-server", "--requirepass", password);
            }
            env.forEach(raw::withEnv);
            raw.start();
            final GenericContainer<?> c = raw;
            return new ManagedContainer() {
                @Override public String host() { return c.getHost(); }
                @Override public int mappedPort() { return c.getMappedPort(port); }
                @Override public void close() { c.stop(); }
            };
        }
    }
}

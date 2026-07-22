package com.huawei.ascend.sit.cases.integration.workflow_call;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.config.TestEnvironment;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.transport.MessageProtocol;
import com.huawei.ascend.sit.utils.RedisProbe;
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * balanceThenTransfers 的<b>直连 + Redis 中间件</b>变体。流程与核心断言继承自 {@link AbstractBalanceThenTransfersTest}；
 * 本类在直连栈基础上让两个 agent 都加载 {@code redis} profile，并 override {@link #afterCheck} 追加 Redis 键空间门禁与完成态硬断言。
 *
 * <p><b>Redis 单开关双激活</b>：{@code openjiuwen.service.middleware.checkpointer.type=redis} 同时激活 Redis Checkpointer 与
 * Redis A2A TaskStore。经 {@code serviceBinding("redis", ...)} 把动态 Testcontainers Redis 的 {@code {{host}}}/{@code {{port}}}
 * 注入为 {@code --REDIS_HOST}/{@code --REDIS_PORT} Spring 启动参（edpa 的 {@code application-redis.yml} 用 {@code ${REDIS_HOST:...}} 占位符接住）。
 * Redis 容器由框架按 service-binding 引用自动拉起、栈自管，无需测试显式声明 BackingServices / 关闭 / 自建 backingUrl 解析。
 *
 * <p><b>本类专属</b>（不参与模板，本地保留）：
 * <ul>
 *   <li>{@link #redisMiddlewareActivatesOnBoot()} —— Hermetic no-LLM 门禁：两 agent 启动日志证明 Redis 中间件激活 + Redis 可达。
 *       激活证据因 agent 而异：<b>spec 原假设「两 agent 同打 checkpointer 行」是错的</b>——plan-agent（RunnerImpl）有 checkpointer，
 *       断言 {@code Begin to initializing checkpointer with type: redis}；adapter（VersatileAdapterApplication，无 Runner/checkpointer，
 *       设计上是无状态桥）改断言 {@code RedisDatasourceDiagnostics: Runtime Redis datasource selected: ... RuntimeRedisClient=...}
 *       （A2A TaskStore 的 Redis 客户端已选好，语义等价）。两者都额外校验 Spring profile 激活行。</li>
 *   <li>{@link #afterCheck} override —— G2 键空间门禁：checkpointer 状态键（agent/workflow）与 {@code a2a:task:} 键同时出现，
 *       证明 redis 的两个角色都落盘；另把转账完成态标记由基类软捕获提升为硬断言（两轮真机实跑稳定命中 4/5）。</li>
 * </ul>
 *
 * <p><b>需真机 LLM</b>：已对 {@code deepseek-v4-pro} 实跑通过（A2A_STREAM / REST_QUERY 两协议，G2 键空间门禁全绿）。
 *
 * @see PlanAgentDirectStreamingTest 纯直连变体（无中间件）
 */
@Tag("integration")
@Feature("FEAT-003: 智能体任务状态缓存")
@Stories({
        @Story("wf.redis-two-role: 两端激活(checkpointer+A2A TaskStore)双角色落盘"),
        @Story("wf.redis-standalone-activate: 单机接入+启动策略日志"),
        @Story("wf.task-state-reuse: A2A Task 与 agent 状态复用同一 Redis")
})
class PlanAgentDirectStreamingRedisTest extends AbstractBalanceThenTransfersTest {

    private static final String ADAPTER = "edpa-adapter";
    private static final String CHECKPOINTER_MARKER = "Begin to initializing checkpointer with type: ";
    /** Adapter (VersatileAdapterApplication) 没有 Runner/checkpointer；它的 Redis 激活证据是 RedisDatasourceDiagnostics。 */
    private static final String ADAPTER_REDIS_DATASOURCE_MARKER = "Runtime Redis datasource selected:";
    private static final String ADAPTER_REDIS_CLIENT_MARKER = "RuntimeRedisClient=";
    /** Spring Boot 启动行：两个 agent 都会在 redis profile 下打这行（额外的低成本置信度）。 */
    private static final String PROFILE_ACTIVE_MARKER = "The following 1 profile is active: \"redis\"";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // 第一行 gate：非 openjiuwen 在基类 .start() 之前 abort，不拉容器。
        Assumptions.assumeTrue(TestEnvironment.current() == TestEnvironment.OPENJIUWEN,
                "openjiuwen profile only — re-run with -Dtest.env=openjiuwen (需 LLM_API_KEY 等)");
        // redis 经 serviceBinding("redis",...) 引用 → collectReferencedServices 自动收录 → 栈自管拉起；
        // envexplorer 由 edpa-adapter 的 YAML service-bindings 收录。故无需显式 .backingServices(...)。
        return SutStack.builder(config)
                .agent(ADAPTER, PlanAgentDirectStreamingRedisTest::withRedis)
                .agent(PLAN_AGENT, a -> withRedis(a).downstream(ADAPTER));
    }

    /**
     * Hermetic, no-LLM gate：两 agent 都在 redis profile 下启动且 Redis 中间件激活，Redis 可达。
     *
     * <p>激活证据因 agent 而异（<b>spec 原假设「两 agent 同打 checkpointer 行」是错的</b>）：
     * <ul>
     *   <li>{@code edpa-plan-agent}（RunnerImpl）—— 有 checkpointer，断言 {@code Begin to initializing checkpointer with type: redis}。</li>
     *   <li>{@code edpa-adapter}（VersatileAdapterApplication）—— <b>无 Runner/checkpointer</b>（设计上是无状态桥），
     *       改断言 {@code RedisDatasourceDiagnostics: Runtime Redis datasource selected: ... RuntimeRedisClient=...}，
     *       即 A2A TaskStore 的 Redis 客户端已选好（语义等价：「redis 中间件已激活」）。</li>
     * </ul>
     * 两者都会打 Spring profile 激活行（额外校验）。
     */
    @Test
    @DisplayName("redis 中间件在 plan-agent + adapter 上激活（启动日志 + Redis 可达）")
    void redisMiddlewareActivatesOnBoot() {
        assertProfileActive(PLAN_AGENT);
        assertProfileActive(ADAPTER);
        assertCheckpointerLog(PLAN_AGENT, "redis");
        assertAdapterRedisWired(ADAPTER);
        assertThat(probe().dbsize()).as("redis DBSIZE reachable").isGreaterThanOrEqualTo(0);
    }

    /**
     * 追加 Redis 键空间门禁（G2：两个角色都落盘）+ 转账完成态硬断言（由基类软捕获提升）。不调用 super（基类默认仅打印，避免重复）。
     */
    @Override
    protected void afterCheck(String blob, MessageProtocol protocol) {
        // 局部命名 redisProbe（不与 probe() 方法同名），避免读混淆。
        RedisProbe redisProbe = probe();
        assertThat(redisProbe.dbsize()).as("Redis DBSIZE > 0").isGreaterThan(0);
        assertThat(redisProbe.keysAny("*agent_state_blobs", "*workflow_state_blobs"))
                .as("checkpointer 角色：agent/workflow 状态键已落盘")
                .isNotEmpty();
        assertThat(redisProbe.keys("a2a:task:*"))
                .as("A2A task-store 角色：tasks 已落盘")
                .isNotEmpty();

        // —— 转账完成态硬断言（两次真机运行稳定命中 4/5 标记，已由首轮软捕获提升）——
        // 保留命中清单日志，便于将来 flaky 时定位实际命中项。
        List<String> hit = TRANSFER_DONE.stream().filter(blob::contains).toList();
        System.out.println("[transfer-completion markers hit][" + protocol + "] " + hit);
        assertThat(hit)
                .as("转账完成态标记须命中其一（候选: " + TRANSFER_DONE + "）")
                .isNotEmpty();
    }

    // --- helpers ---

    /**
     * 给 agent 加 {@code redis} profile，并把动态 Testcontainers Redis 的 host/port 经两条 service-binding
     * 注入为 {@code --REDIS_HOST}/{@code --REDIS_PORT} Spring 启动参（edpa 的 {@code application-redis.yml}
     * 用 {@code ${REDIS_HOST:...}} 占位符接住）。{@code {{host}}}/{@code {{port}}} 由框架从 backing-service
     * 的 {@code host:mappedPort} 拆出；{@code redis} 服务被引用即由栈自管拉起（无需测试显式声明 BackingServices）。
     */
    private static SutStack.AgentBuilder withRedis(SutStack.AgentBuilder b) {
        return b.profile("redis")
                .serviceBinding("redis", "REDIS_HOST", "{{host}}")
                .serviceBinding("redis", "REDIS_PORT", "{{port}}");
    }

    private RedisProbe probe() {
        // stack.serviceUrl 正常化为 http://host:port；用 URI 拆出 host/port 给 RedisProbe。
        URI u = URI.create(stack.serviceUrl("redis"));
        return new RedisProbe(u.getHost(), u.getPort());
    }

    private Path logOf(String agentName) {
        var instance = stack.managedInstance(agentName);
        assertThat(instance)
                .as("managed agent '%s' for log gate", agentName)
                .isNotNull()
                .isInstanceOf(ManagedSutInstance.class);
        return ((ManagedSutInstance) instance).logFile();
    }

    private void assertProfileActive(String agentName) {
        Path log = logOf(agentName);
        String blob = readLog(agentName, log);
        assertThat(blob)
                .as(agentName + " 启动须激活 redis profile: " + PROFILE_ACTIVE_MARKER)
                .contains(PROFILE_ACTIVE_MARKER);
    }

    private void assertCheckpointerLog(String agentName, String expectedType) {
        Path log = logOf(agentName);
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
     * Adapter 侧的 Redis 激活断言：须出现 {@code RedisDatasourceDiagnostics} 的「Runtime Redis datasource selected」
     * 行并附带非空 {@code RuntimeRedisClient=}（证明 A2A TaskStore 的 Redis 客户端已实例化）。
     */
    private void assertAdapterRedisWired(String agentName) {
        Path log = logOf(agentName);
        String blob = readLog(agentName, log);
        assertThat(blob)
                .as(agentName + " 须含 Redis 数据源诊断行: " + ADAPTER_REDIS_DATASOURCE_MARKER)
                .contains(ADAPTER_REDIS_DATASOURCE_MARKER);
        assertThat(blob)
                .as(agentName + " Redis 数据源行须含已选 RuntimeRedisClient")
                .contains(ADAPTER_REDIS_CLIENT_MARKER);
    }

    private static String readLog(String agentName, Path log) {
        try {
            return Files.readString(log);
        } catch (IOException e) {
            throw new AssertionError("无法读取 " + agentName + " 日志 " + log + ": " + e.getMessage(), e);
        }
    }
}

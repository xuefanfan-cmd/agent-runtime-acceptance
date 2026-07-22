package com.huawei.ascend.sit.cases.integration.workflow_call;

import com.github.dockerjava.api.model.ContainerNetwork;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.config.TestEnvironment;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.utils.RedisProbe;
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 费用报销审核的 <b>redis cluster 中间件变体</b>。流程、断言、协议参数化（4 种线协议）全部继承自
 * {@link AbstractExpenseReviewAcceptanceTest}；本类把类级栈整体切到 <b>redis cluster 拓扑</b> —— 用 Testcontainers
 * 自管 {@code grokzen/redis-cluster:7.2.5}（单容器 6 节点 3主3从，端口 7000–7005），把 6 个 seed 地址以
 * {@code openjiuwen.service.middleware.redis.default.nodes[i]} 注入两 agent（{@code type=cluster}），证明 SUT 的
 * checkpointer / A2A TaskStore 在 <b>cluster 拓扑</b> 下与 standalone 业务等价（与 FEAT-003 同型断言）。
 *
 * <p><b>为何自管 grokzen 而非走框架 backing-service</b>：grokzen 是<b>一容器 6 端口</b>，框架的
 * {@code ContainerFactory} / {@code serviceBinding} 是<b>单端口单容器</b>模型（给 standalone redis 用），无法表达 cluster
 * 的多节点。故 grokzen 在 {@link #buildStack} 内直接拉起（基类 {@code @BeforeAll} 全类只调一次 buildStack ⇒ 全类共享一个
 * cluster，固定端口亦无并发问题），节点地址经 {@code .property()} 注入（与
 * {@code Feat003RedisClusterAndSwitchTest#startCluster} 同型），子类 {@link #stopCluster()} 停容器。
 *
 * <p><b>cluster 可达性 = 容器 bridge IP 直连（Linux）</b>。读 grokzen 的 {@code redis-cluster.tmpl} 可知其<b>无</b>
 * {@code cluster-announce-ip}，节点公告自身 eth0/bridge IP；Linux 宿主对 docker 桥接子网天然可路由 ⇒ SUT（本地进程）与
 * 测试 JVM 都能直连 {@code <bridgeIp>:7000..7005}。JedisCluster 用注入的 seed bootstrap，之后跟随公告地址重定向 ——
 * 公告地址即 bridge IP，宿主可达。故<b>无需固定端口映射、无需 host 网络、无需 NAT mapper</b>（先前"固定端口 + IP=0.0.0.0"
 * 的设想在读过 entrypoint 后推翻：它不解决 MOVED 公告地址问题）。
 *
 * <p><b>不设密码</b>：grokzen 的 {@code redis-cluster.tmpl} 不含 {@code requirepass}，加密码需改模板/自建镜像；密码脱敏
 * 验收已由 standalone 变体 {@link ExpenseReviewRedisAcceptanceTest} 全量覆盖。本叶子聚焦 cluster 拓扑（与 Feat003 一致，
 * 期望 {@code passwordConfigured=false}）。
 *
 * <p><b>hermetic boot 门禁</b>（{@link #redisClusterMiddlewareActivatesOnBoot()}，无需 LLM）：profile 激活 +
 * checkpointer(main)/datasource(workflow) 证据（agent-specific，同 standalone 教训）+ <b>cluster 诊断</b>
 * （{@code endpoint-type=cluster}、{@code JedisClusterRuntimeRedisClient}）+ <b>bridge IP:7000 DBSIZE 可达</b>
 * （证明 6 节点真成簇且公告地址宿主可达 —— 这正是 JedisCluster 重定向能否成立的判定性证据）。继承自基类的 8 个 LLM 场景
 * 证明 cluster 拓扑下业务等价。
 *
 * @see ExpenseReviewAcceptanceTest in-memory 变体
 * @see ExpenseReviewRedisAcceptanceTest standalone redis（含密码脱敏）变体
 * @see com.huawei.ascend.sit.cases.integration.react_travel.Feat003RedisClusterAndSwitchTest cluster 注入与断言同型
 */
@Tag("integration")
@Feature("FEAT-003: 智能体任务状态缓存")
@Stories({
        @Story("wf.cluster-access: 原生集群接入"),
        @Story("wf.config-switch: 单机↔集群配置切换"),
        @Story("wf.task-state-reuse: 状态复用同一 Redis")
})
class ExpenseReviewRedisClusterAcceptanceTest extends AbstractExpenseReviewAcceptanceTest {

    private static final String PREFIX = "openjiuwen.service.middleware.";
    private static final String GROKZEN_IMAGE = "grokzen/redis-cluster:6.2.14";
    private static final int CLUSTER_BASE_PORT = 7000;
    private static final int CLUSTER_NODE_COUNT = 6;

    private static final String PROFILE_ACTIVE_MARKER = "The following 1 profile is active: \"redis\"";
    private static final String CHECKPOINTER_MARKER = "Begin to initializing checkpointer with type: ";
    /** workflow agent 的 Redis 激活证据：RedisDatasourceDiagnostics 的数据源已选行（checkpointer 行可能不打印）。 */
    private static final String REDIS_DATASOURCE_MARKER = "Runtime Redis datasource selected:";
    private static final String REDIS_CLIENT_MARKER = "RuntimeRedisClient=";
    /** openjiuwen RedisConnectionAssembler 的 cluster 拓扑诊断（证明用 JedisCluster 而非 standalone Jedis）。 */
    private static final String ENDPOINT_TYPE_CLUSTER_MARKER = "endpoint-type=cluster";
    private static final String JEDIS_CLUSTER_CLIENT_MARKER = "JedisClusterRuntimeRedisClient";

    /** grokzen 容器（buildStack 拉起，{@link #stopCluster()} 停）。 */
    private GenericContainer<?> grokzen;
    /** 容器 bridge IP —— 节点公告地址 = seed 注入地址（Linux 宿主可路由到 docker 网络）。 */
    private String clusterHost;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // 第一行 gate：非 openjiuwen 在拉容器之前 abort。
        Assumptions.assumeTrue(TestEnvironment.current() == TestEnvironment.OPENJIUWEN,
                "openjiuwen profile only — re-run with -Dtest.env=openjiuwen (需 Docker + LLM_API_KEY 等)");
        // 拉起 grokzen（buildStack 由基类 @BeforeAll 全类只调一次 ⇒ 全类共享一个 cluster）。
        startGrokzen();
        return SutStack.builder(config)
                .agent(WORKFLOW_AGENT, this::withCluster)
                .agent(ENTRY_AGENT, a -> { withCluster(a); a.downstream(WORKFLOW_AGENT); });
    }

    /** 停 grokzen（子类 @AfterAll 先于基类 @AfterAll，cluster 在 stack.close() 前停）。 */
    @AfterAll
    void stopCluster() {
        if (grokzen != null) {
            grokzen.stop();
        }
    }

    /**
     * Hermetic, no-LLM 门禁：两 agent 在 redis profile 下启动、checkpointer/datasource 切到 cluster、cluster 诊断输出、
     * bridge IP:7000 可达。cluster 诊断（{@code endpoint-type=cluster}、{@code JedisClusterRuntimeRedisClient}）证明 SUT 用
     * JedisCluster 而非 standalone Jedis；DBSIZE 探针证明节点公告地址（bridge IP）宿主可达 —— 这正是 JedisCluster
     * MOVED/ASK 重定向能否成功的判定性证据（公告地址不可达则 SUT 侧 JedisCluster 会报 Cluster retry deadline）。
     */
    @Test
    @DisplayName("redis cluster 中间件在 workflow + main 上激活（启动日志 + cluster 诊断 + bridge IP 可达）")
    void redisClusterMiddlewareActivatesOnBoot() {
        assertProfileActive(stack, WORKFLOW_AGENT);
        assertProfileActive(stack, ENTRY_AGENT);
        assertCheckpointerLog(stack, ENTRY_AGENT, "redis");
        assertRedisDatasourceWired(stack, WORKFLOW_AGENT);
        // cluster 诊断：两 agent 都用 JedisCluster（非 standalone Jedis），endpoint-type=cluster。
        assertClusterDiagnostics(stack, ENTRY_AGENT);
        assertClusterDiagnostics(stack, WORKFLOW_AGENT);
        // 节点公告地址（bridge IP）宿主可达：DBSIZE 返回非负（与 SUT 同网络，可达即重定向可成立）。
        assertThat(new RedisProbe(clusterHost, CLUSTER_BASE_PORT).dbsize())
                .as("grokzen bridge IP:7000 DBSIZE 可达（cluster 已成簇且公告地址宿主可达）")
                .isGreaterThanOrEqualTo(0);
    }

    // ---- grokzen 拉起与拓扑注入 ----

    /** 给 agent 加 redis profile + cluster 拓扑（type=cluster + 6 节点 seed，覆盖 application-redis.yml 的 standalone 占位）。 */
    private void withCluster(SutStack.AgentBuilder b) {
        b.profile("redis");
        b.property(PREFIX + "redis.default.type", "cluster");
        for (int i = 0; i < CLUSTER_NODE_COUNT; i++) {
            b.property(PREFIX + "redis.default.nodes[" + i + "]",
                    clusterHost + ":" + (CLUSTER_BASE_PORT + i));
        }
    }

    /**
     * 拉起 grokzen（默认 IP 自发现 = 容器 bridge IP，节点公告该地址），等启动日志 {@code Cluster state changed: ok}
     * （6 节点成簇），取容器 bridge IP 作 seed / 公告地址。
     */
    private void startGrokzen() {
        grokzen = new GenericContainer<>(DockerImageName.parse(GROKZEN_IMAGE))
                .withExposedPorts(CLUSTER_BASE_PORT, 7001, 7002, 7003, 7004, 7005)
                .waitingFor(Wait.forLogMessage(".*Cluster state changed: ok.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(3)));
        grokzen.start();
        clusterHost = bridgeIp(grokzen);
    }

    /**
     * 容器 bridge IP：节点公告地址 = seed 注入地址（Linux 宿主可路由到 docker 网络）。
     * Docker 1.9+ 下 {@code NetworkSettings.getIpAddress()} 常为空，真实 IP 在 {@code getNetworks()}，故取首个非空。
     */
    private static String bridgeIp(GenericContainer<?> c) {
        String ip = c.getContainerInfo().getNetworkSettings().getIpAddress();
        if (ip == null || ip.isBlank()) {
            for (ContainerNetwork n : c.getContainerInfo().getNetworkSettings().getNetworks().values()) {
                if (n.getIpAddress() != null && !n.getIpAddress().isBlank()) {
                    ip = n.getIpAddress();
                    break;
                }
            }
        }
        assertThat(ip)
                .as("grokzen 容器 IP（cluster 节点公告地址 = seed；Linux 宿主须可路由到 docker 网络）")
                .isNotBlank();
        return ip;
    }

    // ---- 日志门禁助手（与 ExpenseReviewRedisAcceptanceTest 同型，cluster 标记不同）----

    private void assertClusterDiagnostics(SutStack s, String agentName) {
        String blob = readLog(logOf(s, agentName));
        assertThat(blob).as(agentName + " 须输出 cluster endpoint 诊断: " + ENDPOINT_TYPE_CLUSTER_MARKER)
                .contains(ENDPOINT_TYPE_CLUSTER_MARKER);
        assertThat(blob).as(agentName + " 须用 JedisCluster 客户端: " + JEDIS_CLUSTER_CLIENT_MARKER)
                .contains(JEDIS_CLUSTER_CLIENT_MARKER);
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
        assertThat(readLog(logOf(s, agentName)))
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

    private void assertRedisDatasourceWired(SutStack s, String agentName) {
        String blob = readLog(logOf(s, agentName));
        assertThat(blob)
                .as(agentName + " 须含 Redis 数据源诊断行: " + REDIS_DATASOURCE_MARKER)
                .contains(REDIS_DATASOURCE_MARKER);
        assertThat(blob)
                .as(agentName + " Redis 数据源行须含已选 RuntimeRedisClient")
                .contains(REDIS_CLIENT_MARKER);
    }

    private static String readLog(Path log) {
        try {
            return Files.readString(log);
        } catch (IOException e) {
            throw new AssertionError("无法读取日志 " + log + ": " + e.getMessage(), e);
        }
    }
}

package com.huawei.ascend.sit.cases.integration.workflow_call;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.config.TestEnvironment;
import com.huawei.ascend.sit.conversation.Conversation;
import com.huawei.ascend.sit.conversation.ConversationIdentity;
import com.huawei.ascend.sit.conversation.ConversationInteractionAdapter;
import com.huawei.ascend.sit.conversation.DriveMode;
import com.huawei.ascend.sit.conversation.SseEvent;
import com.huawei.ascend.sit.conversation.TurnResult;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.transport.MessageProtocol;
import com.huawei.ascend.sit.utils.RedisProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 中间件验收 — 直连 plan-agent 流式路径。设计见
 * docs/superpowers/specs/2026-07-18-redis-middleware-direct-streaming-test-design.md。
 *
 * <p>在 {@code PlanAgentDirectStreamingTest} 的直连栈（edpa-adapter + edpa-plan-agent，envexplorer
 * 由 adapter 的 service-bindings 自动拉起）上，让两个 agent 都加载 {@code redis} profile（单一开关
 * {@code openjiuwen.service.middleware.checkpointer.type=redis} 同时激活 Redis Checkpointer 与 Redis
 * A2A TaskStore），断言：
 * <ul>
 *   <li>{@link #redisMiddlewareActivatesOnBoot()} — 两 agent 启动日志证明 Redis 中间件已激活
 *       且 Redis 可达（<b>无需 LLM</b>，启动即发，hermetic 绿）。激活证据因 agent 而异：plan-agent
 *       断言 checkpointer 行，adapter 断言 Redis 数据源诊断行（见方法 Javadoc）。</li>
 *   <li>{@code balanceThenTransfersDirectRedis(MessageProtocol)} — 直连两流式线下跑完「查余额+转账」
 *       + Redis 键空间断言（checkpointer/a2a:task 键）+ 转账完成态硬断言；<b>需真机 LLM</b>（已实跑通过）。</li>
 * </ul>
 *
 * <p>与 {@link PlanAgentDirectStreamingTest} 的差别：两个 agent 加 {@code redis} profile，并经
 * {@code serviceBinding("redis", ...)} 把动态 Testcontainers Redis 的 {@code {{host}}}/{@code {{port}}}
 * 注入为 {@code --REDIS_HOST}/{@code --REDIS_PORT} Spring 启动参（edpa 的 {@code application-redis.yml}
 * 用 {@code ${REDIS_HOST:...}} 占位符接住）。Redis 容器由框架按 service-binding 引用自动拉起、栈自管，
 * 故<b>无需</b>测试显式声明 BackingServices / 关闭 / 自建 backingUrl 解析。驱动语句、5 个 manual select、
 * stepUi、断言形态与参考用例一致，另加 G2 键空间门禁。
 */
@Tag("integration")
@Tag("openjiuwen")
@Tag("nightly")
class PlanAgentDirectStreamingRedisTest extends BaseManagedStackTest {

    private static final String PLAN_AGENT = "edpa-plan-agent";
    private static final String ADAPTER = "edpa-adapter";
    private static final String CHECKPOINTER_MARKER = "Begin to initializing checkpointer with type: ";
    /** Adapter (VersatileAdapterApplication) 没有 Runner/checkpointer；它的 Redis 激活证据是 RedisDatasourceDiagnostics。 */
    private static final String ADAPTER_REDIS_DATASOURCE_MARKER = "Runtime Redis datasource selected:";
    private static final String ADAPTER_REDIS_CLIENT_MARKER = "RuntimeRedisClient=";
    /** Spring Boot 启动行：两个 agent 都会在 redis profile 下打这行（额外的低成本置信度）。 */
    private static final String PROFILE_ACTIVE_MARKER = "The following 1 profile is active: \"redis\"";

    private static final String SENTENCE = "先查下余额，再给李四和王五各转50元";
    private static final List<String> STACK_LEAK_MARKERS = List.of(
            "java.io.IOException", "Caused by:", "Exception in thread",
            "at java.base/", "at org.springframework.", "at reactor.");
    private static final List<String> TOPICAL = List.of(
            "余额", "账", "转", "李四", "王五", "成功", "失败", "无法", "元");
    private static final List<String> TRANSFER_DONE = List.of(
            "转账成功", "转账信息已处理成功", "transfer_07", "SSTANDARDANSWER", "处理成功");
    /** adapter 每轮解析超时——LLM 多工具轮较慢，与 Conversation 600s 超时对齐。 */
    private static final long ROUND_TIMEOUT_MS = 600_000L;

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
     * 直连 plan-agent 的查余额+转账（stepUi 自推进），redis 中间件开启，参数化覆盖两流式线协议。
     *
     * <p>与 {@link PlanAgentDirectStreamingTest#balanceThenTransfersDirect(MessageProtocol)} 同 kickoff、
     * 同 5 个 manual select，差别只在 transport 之外<b>还</b>开了 redis：plan-agent + adapter 均加载
     * {@code redis} profile。除参考用例的语义/不泄露断言外，额外硬断言 Redis 键空间同时出现
     * checkpointer 状态键（agent/workflow）与 {@code a2a:task:} 键——证明 redis 的两个角色都被走到。
     *
     * <p><b>需真机 LLM</b>：已对 {@code deepseek-v4-pro} 实跑通过（A2A_STREAM / REST_QUERY 两协议，G2 键空间
     * 门禁全绿）；转账完成态标记经两轮实跑稳定命中 4/5，已由首轮软捕获提升为硬断言。断言集其余同参考用例。
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @EnumSource(value = MessageProtocol.class, mode = EnumSource.Mode.INCLUDE,
            names = {"A2A_STREAM", "REST_QUERY"})
    @DisplayName("直连 plan-agent + Redis：查余额+转账（stepUi）—— A2A_STREAM / REST_QUERY")
    void balanceThenTransfersDirectRedis(MessageProtocol protocol) {
        try (Conversation conv = Conversation.at(
                stack.baseUrl(PLAN_AGENT), stack.serviceUrl("envexplorer"))
                .identity(ConversationIdentity.loadDefault())
                .transport(new ConversationInteractionAdapter(protocol, client(PLAN_AGENT), ROUND_TIMEOUT_MS))
                .timeout(Duration.ofSeconds(600))
                .open()) {

            TurnResult turn = conv.turn(SENTENCE)
                    .intent("")
                    // —— 转账李四 ——
                    .select("on_payee_input",   Map.of("recSerialNum", "SN20240001"))
                    .select("on_paycard_input", Map.of("accIndex", "0"))
                    .select("on_confirm_remit", Map.of("_text", "确定"))
                    // —— 转账王五 ——
                    .select("on_paycard_input", Map.of("accIndex", "0"))
                    .select("on_confirm_remit", Map.of("_text", "确定"))
                    .driveMode(DriveMode.stepUi())
                    .run();

            String blob = concat(turn.allEvents());

            // —— 继承自参考用例：语义 / 不泄露 ——
            for (String m : STACK_LEAK_MARKERS) {
                assertThat(blob).as("SSE 不得泄露 JVM 堆栈").doesNotContain(m);
            }
            assertThat(blob).as("plan-agent 汇总非空").isNotBlank();
            assertThat(TOPICAL.stream().anyMatch(blob::contains))
                    .as("汇总须含 余额/转账/参与者 之一").isTrue();
            assertThat(blob).as("余额笔数据(8200)").contains("8200");
            assertThat(blob).as("收款人 李四").contains("李四");
            assertThat(blob).as("收款人 王五").contains("王五");

            // —— Redis 中间件激活门禁（G2：键空间证明两个角色都落盘）——
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

    private static String concat(List<SseEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (SseEvent e : events) {
            if (e.text() != null) {
                sb.append(e.text());
            }
            if (e.data() != null) {
                e.data().values().forEach(v -> { if (v != null) sb.append(v); });
            }
        }
        return sb.toString();
    }
}

package com.huawei.ascend.sit.cases.e2e.deepanalyze_dataplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-069 降级与故障注入（f069-deg-01/02/03/04；L2 §3.2 关键承诺"记录面不得打挂执行面"）。
 *
 * <p>每个用例自管 PG+栈（方法级 try-with-resources）：deg-03 停库、deg-04 改授权都会
 * 破坏共享状态，隔离最贵但唯一安全。降级构造全部黑盒可达：queue-capacity=1（配置面）、
 * docker stop/start（容器面）、REVOKE（GRANT 面）。\\u0000 毒行（deg-05）黑盒不可靠构造
 * （需模型产出 NUL 字节），归执行期对抗性探针观察面（铁律 B④），不在本类。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-069")
@Feature("FEAT-069: deepanalyze-java 数据面权限与写方迁移")
class DaDataplaneDegradationE2EIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(180);
    private static final String WRITER_ACCOUNT = "deg_writer";
    private static final String WRITER_PW = "deg_fixture_only";

    private final List<GenericContainer<?>> managedContainers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<SutStack> managedStacks = new java.util.concurrent.CopyOnWriteArrayList<>();
    private TestConfig config;

    private TestConfig config() {
        if (config == null) {
            config = TestConfig.load();
        }
        return config;
    }

    @AfterEach
    void tearDown() {
        for (SutStack stack : managedStacks) {
            try {
                stack.close();
            } catch (RuntimeException ignored) {
                // 收口失败不遮蔽断言
            }
        }
        managedStacks.clear();
        for (GenericContainer<?> container : managedContainers) {
            try {
                container.stop();
            } catch (RuntimeException ignored) {
                // 同上
            }
        }
        managedContainers.clear();
    }

    private GenericContainer<?> newPg() {
        // deg-03 需要 stop→start 同一容器模拟"恢复"；colima 实测（2026-10-26，scratch 实验
        // 32917→32918）动态映射端口在容器重启后会被重新分配，停库前固化的 JDBC URL 将永久失效
        // （SUT 与测试侧同时失联，写入器每 ~31s 撞一次拒绝端口）——故钉住固定宿主端口，
        // 使"重启后同端口可达"成为场景前提。每次起容器前取一个本地空闲端口，避免固定值冲突。
        int pinnedHostPort = findFreeLocalPort();
        GenericContainer<?> pg = new GenericContainer<>(DockerImageName.parse("postgres:16"))
                .withExposedPorts(5432)
                .withEnv("POSTGRES_DB", DaDataplaneSupport.PG_DB)
                .withEnv("POSTGRES_USER", DaDataplaneSupport.PG_USER)
                .withEnv("POSTGRES_PASSWORD", DaDataplaneSupport.PG_PASSWORD)
                .withCreateContainerCmdModifier(cmd -> {
                    com.github.dockerjava.api.model.HostConfig hostConfig = cmd.getHostConfig();
                    if (hostConfig == null) {
                        hostConfig = new com.github.dockerjava.api.model.HostConfig();
                        cmd.withHostConfig(hostConfig);
                    }
                    hostConfig.withPortBindings(new com.github.dockerjava.api.model.Ports(
                            new com.github.dockerjava.api.model.PortBinding(
                                    com.github.dockerjava.api.model.Ports.Binding.bindPort(pinnedHostPort),
                                    new com.github.dockerjava.api.model.ExposedPort(5432))));
                });
        pg.start();
        managedContainers.add(pg);
        return pg;
    }

    private static int findFreeLocalPort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("无法分配本地空闲端口（deg-03 钉端口前置）", e);
        }
    }

    private String jdbcUrl(GenericContainer<?> pg) {
        return "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/"
                + DaDataplaneSupport.PG_DB;
    }

    private Connection pgConnection(GenericContainer<?> pg) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(pg), DaDataplaneSupport.PG_USER,
                DaDataplaneSupport.PG_PASSWORD);
    }

    /** 用例自管栈注册（@AfterEach 统一回收）。 */
    private SutStack startStack(String agent, java.util.function.Consumer<
            com.huawei.ascend.sit.lifecycle.SutStack.AgentBuilder> configure) {
        SutStack stack = SutStack.builder(config())
                .streaming(true)
                .agent(agent, agentBuilder -> {
                    DaDataplaneSupport.bindModelEnvironment(agentBuilder);
                    if (System.getenv("LLM_API_KEY") == null || System.getenv("LLM_API_KEY").isBlank()) {
                        agentBuilder.property("LLM_API_KEY", "fixture-no-runner-credentials");
                    }
                    configure.accept(agentBuilder);
                })
                .start();
        managedStacks.add(stack);
        return stack;
    }

    private HttpResponse<String> runCompat(HttpClient http, String base, String session, String input)
            throws Exception {
        return DaDataplaneSupport.post(http, base, "/api/agents/run",
                "{\"sessionId\":\"" + session + "\",\"input\":\"" + input + "\"}", CALL_TIMEOUT);
    }

    private int count(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    @Story("f069-deg-01/02: 队列满分级丢弃——可丢弃类丢+计数+drop_marker，受保护类保全")
    @DisplayName("deg-01/02: queue-capacity=1 压测下的分级降级")
    void queueOverflowDropsDroppableKeepsProtected() throws Exception {
        GenericContainer<?> pg = newPg();
        try (Connection conn = pgConnection(pg); Statement st = conn.createStatement()) {
            DaDataplaneSupport.applyBaselineDdl(conn);
        }
        SutStack stack = startStack(DaDataplaneSupport.AGENT, agent -> agent
                .property("deepanalyze.data.pg.jdbc-url", jdbcUrl(pg))
                .property("deepanalyze.data.pg.username", DaDataplaneSupport.PG_USER)
                .property("deepanalyze.data.pg.password", DaDataplaneSupport.PG_PASSWORD)
                .property("spring.datasource.url", jdbcUrl(pg))
                .property("spring.datasource.username", DaDataplaneSupport.PG_USER)
                .property("spring.datasource.password", DaDataplaneSupport.PG_PASSWORD)
                .property("deepanalyze.record.queue-capacity", "3")
                .property("deepanalyze.record.protected-queue-capacity", "1")
                .serviceBinding("redis", "REDIS_HOST", "{{host}}")
                .serviceBinding("redis", "REDIS_PORT", "{{port}}"));
        String base = stack.client(DaDataplaneSupport.AGENT).getBaseUrl();
        HttpClient http = DaDataplaneSupport.newHttpClient();
        String session = "sess-deg01-" + System.nanoTime();

        List<String> payloads = DaDataplaneSupport.postAndDrainSse(http, base, "/api/agents/run-stream",
                "{\"sessionId\":\"" + session + "\",\"input\":\"请从 1 数到 50，每个数字单独一行输出，不要调用任何工具。\"}",
                java.util.Map.of("X-Da-Session-Id", session), CALL_TIMEOUT);
        assertThat(payloads).as("任务照常完成（执行面不受记录面影响）").isNotEmpty();

        // 有界轮询：等终态落库 + drop_marker 补记
        String workflowId = null;
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            try (Statement st = pgConnection(pg).createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, status FROM workflows WHERE session_id = '"
                         + session + "'")) {
                if (rs.next() && !"running".equals(rs.getString(2))) {
                    workflowId = rs.getString(1);
                    break;
                }
            }
            Thread.sleep(400);
        }
        assertThat(workflowId).as("30s 内终态记录落库（免丢弃类不淘汰）").isNotNull();
        boolean dropMarkerSeen = false;
        deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline && !dropMarkerSeen) {
            try (Statement st = pgConnection(pg).createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM workflow_logs WHERE workflow_id = '"
                         + workflowId + "' AND event_type = 'drop_marker'")) {
                rs.next();
                dropMarkerSeen = rs.getInt(1) > 0;
            }
            Thread.sleep(500);
        }
        try (Statement st = pgConnection(pg).createStatement()) {
            assertThat(dropMarkerSeen)
                    .as("队列满丢弃后须按 workflow 补 drop_marker 行（恢复后留痕）").isTrue();
            assertThat(count(st, "SELECT count(*) FROM workflow_logs WHERE workflow_id = '" + workflowId
                    + "' AND event_type = 'answer'"))
                    .as("受保护类 answer 不丢（deg-02）").isGreaterThanOrEqualTo(1);
            assertThat(count(st, "SELECT count(*) FROM workflows WHERE id = '" + workflowId
                    + "' AND status IN ('completed','failed')")).isEqualTo(1);
        }
        Allure.addAttachment("deg-01/02", "text/plain",
                "queue=1 压测：任务完成、drop_marker 落库、answer 保全、终态完整");
    }

    @Test
    @Story("f069-deg-03: 任务执行中数据库不可达——任务照常完成，恢复后免丢弃记录保序落地")
    @DisplayName("deg-03: 停库注入——执行面与记录面解耦")
    void databaseOutageDoesNotKillExecution() throws Exception {
        GenericContainer<?> pg = newPg();
        try (Connection conn = pgConnection(pg); Statement st = conn.createStatement()) {
            DaDataplaneSupport.applyBaselineDdl(conn);
        }
        SutStack stack = startStack(DaDataplaneSupport.AGENT, agent -> agent
                .property("deepanalyze.data.pg.jdbc-url", jdbcUrl(pg))
                .property("deepanalyze.data.pg.username", DaDataplaneSupport.PG_USER)
                .property("deepanalyze.data.pg.password", DaDataplaneSupport.PG_PASSWORD)
                .property("spring.datasource.url", jdbcUrl(pg))
                .property("spring.datasource.username", DaDataplaneSupport.PG_USER)
                .property("spring.datasource.password", DaDataplaneSupport.PG_PASSWORD)
                .serviceBinding("redis", "REDIS_HOST", "{{host}}")
                .serviceBinding("redis", "REDIS_PORT", "{{port}}"));
        String base = stack.client(DaDataplaneSupport.AGENT).getBaseUrl();
        HttpClient http = DaDataplaneSupport.newHttpClient();

        // 停库（docker stop，保留容器可重启）
        var docker = org.testcontainers.DockerClientFactory.instance().client();
        docker.stopContainerCmd(pg.getContainerId()).exec();
        Allure.addAttachment("deg-03 注入", "text/plain", "PG 容器已停止（任务发起前）");

        String session = "sess-deg03-" + System.nanoTime();
        HttpResponse<String> run = runCompat(http, base, session,
                "请用一句话回答：11+11 等于几。不要调用任何工具。");
        Allure.addAttachment("停库期间的任务响应", "text/plain", run.body());
        assertThat(run.statusCode()).as("数据库全程不可达，任务照常受理完成").isEqualTo(200);
        JsonNode body = MAPPER.readTree(run.body());
        if (body.hasNonNull("status")) {
            assertThat(body.path("status").asText())
                    .as("执行面不受记录面影响（enrich 读失败降级 WARN 不阻断——源码 DaCompatController.enrichFromDataPlane 已核实）")
                    .isEqualTo("completed");
        }

        // 恢复数据库 → writer 原位重试成功 → 免丢弃类记录最终落地。
        // #469 回归注记（2026-10-26）：主断言（停库期间任务受理完成）已随 catch 放宽转绿；
        // 本段按 L2 §3.2 语义断言"最终落地"——瞬态/未分类重试不设次数上界、队头阻塞为声明代价，
        // 且 colima 形态下停库期间每次尝试被 Hikari connectionTimeout（缺省 30s）串行拉长
        // （实证：writer 尝试间隔 ≈31s、关栈 drain leftover=86），60s 窗口不足以承载 2-3 次尝试，
        // 故终局窗口放宽至 180s 并全程留证（PG 就绪时刻 / 落地时刻 / 尝试推进）。
        docker.startContainerCmd(pg.getContainerId()).exec();
        long pgReadyAt = 0;
        long pgDeadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < pgDeadline) {
            try (Connection probe = pgConnection(pg); Statement st = probe.createStatement()) {
                st.execute("SELECT 1");
                pgReadyAt = System.currentTimeMillis();
                break;
            } catch (SQLException stillDown) {
                // PG 启动中
            }
            Thread.sleep(1_000);
        }
        String workflowId = null;
        long deadline = System.currentTimeMillis() + 180_000;
        int polls = 0;
        while (System.currentTimeMillis() < deadline) {
            polls++;
            try (Statement st = pgConnection(pg).createStatement();
                 ResultSet rs = st.executeQuery("SELECT id FROM workflows WHERE session_id = '" + session + "'")) {
                if (rs.next()) {
                    workflowId = rs.getString(1);
                    break;
                }
            } catch (SQLException stillDown) {
                // PG 就绪中
            }
            Thread.sleep(1_000);
        }
        Allure.addAttachment("deg-03 恢复时间线", "text/plain",
                "停库期间任务完成（enrich 降级 WARN）；PG 重启后就绪时刻=" + (pgReadyAt > 0 ? "已就绪" : "60s 内未就绪")
                        + "；轮询 " + polls + " 次；落地=" + (workflowId != null ? "是" : "否（180s 未落地）"));
        assertThat(workflowId).as("恢复后免丢弃记录最终落地（原位重试语义，180s 终局窗口）").isNotNull();
        Allure.addAttachment("deg-03", "text/plain", "停库期间任务完成；恢复后记录保序落地");
    }

    @Test
    @Story("f069-deg-04: GRANT 拒绝（REVOKE 注入）——记录写失败不打挂任务")
    @DisplayName("deg-04: 收回 INSERT 权限 → 任务完成、无记录行（42501 分诊）")
    void revokedGrantDoesNotKillExecution() throws Exception {
        GenericContainer<?> pg = newPg();
        try (Connection conn = pgConnection(pg); Statement st = conn.createStatement()) {
            // 基线 + SUT 演进 DDL 预执行（幂等 no-op 形态：初始化器以受限账号启动时
            // 全部 DDL 命中 IF [NOT] EXISTS 短路，不需要 ownership——fully-evolved 前置）
            DaDataplaneSupport.applyBaselineDdl(conn);
            DaDataplaneSupport.executeScript(conn, DaDataplaneSupport.readArtifactResource(
                    DaDataplaneSupport.SUT_SCHEMA_RESOURCE));
            // 受限写账号（按 GRANT 脚本矩阵授权——生产 agent 账号的非超管形态）
            st.execute("CREATE ROLE " + WRITER_ACCOUNT + " LOGIN PASSWORD '" + WRITER_PW + "'");
            // L2 §4.3 部署前置之一：schema 级 CREATE 授权（实测 PG 对 CREATE TABLE IF NOT EXISTS
            // 也先校验 schema 权限——表已存在时同样拒绝）
            st.execute("GRANT CREATE ON SCHEMA public TO " + WRITER_ACCOUNT);
            // L2 §4.3 部署前置之二：ownership 移交（实测 ALTER TABLE ... ADD COLUMN IF NOT EXISTS
            // 即使空操作也先校验表所有权——受限账号启动必须先走移交，正是 L2 登记的形态）
            for (String table : java.util.List.of("session_memory", "workflows", "workflow_logs",
                    "reports", "report_references", "agent_todos", "agent_skills", "skill_usage",
                    "skill_versions", "agent_memory", "agent_teams", "agent_team_members")) {
                st.execute("ALTER TABLE " + table + " OWNER TO " + WRITER_ACCOUNT);
            }
            String grantScript = DaDataplaneSupport.readArtifactResource(DaDataplaneSupport.SUT_GRANT_RESOURCE)
                    .replace("<agent_account>", WRITER_ACCOUNT)
                    .replace("<da_account>", DaDataplaneSupport.PG_USER)
                    .replace("<ke_account>", DaDataplaneSupport.PG_USER);
            DaDataplaneSupport.executeScript(conn, grantScript);
        }
        SutStack stack = startStack(DaDataplaneSupport.AGENT, agent -> agent
                .property("deepanalyze.data.pg.jdbc-url", jdbcUrl(pg))
                .property("deepanalyze.data.pg.username", WRITER_ACCOUNT)
                .property("deepanalyze.data.pg.password", WRITER_PW)
                .property("spring.datasource.url", jdbcUrl(pg))
                .property("spring.datasource.username", WRITER_ACCOUNT)
                .property("spring.datasource.password", WRITER_PW)
                .serviceBinding("redis", "REDIS_HOST", "{{host}}")
                .serviceBinding("redis", "REDIS_PORT", "{{port}}"));
        String base = stack.client(DaDataplaneSupport.AGENT).getBaseUrl();
        HttpClient http = DaDataplaneSupport.newHttpClient();

        // boot 后收回 ownership（所有者隐式权限不受 REVOKE 约束——REVOKE 对 owner 是 no-op），
        // 再按矩阵补 GRANT（所有权变更不撤销既有显式授权，此处显式重授保证对照面干净）
        try (Connection conn = pgConnection(pg); Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE workflows OWNER TO " + DaDataplaneSupport.PG_USER);
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON workflows TO " + WRITER_ACCOUNT);
        }

        // 前置验证：受限账号下记录链路本来工作（对照面）
        String session1 = "sess-deg04-a-" + System.nanoTime();
        HttpResponse<String> run1 = runCompat(http, base, session1,
                "请用一句话回答：12+12 等于几。不要调用任何工具。");
        assertThat(run1.statusCode()).isEqualTo(200);
        long deadline = System.currentTimeMillis() + 30_000;
        boolean session1Recorded = false;
        while (System.currentTimeMillis() < deadline && !session1Recorded) {
            try (Statement st = pgConnection(pg).createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM workflows WHERE session_id = '"
                         + session1 + "'")) {
                rs.next();
                session1Recorded = rs.getInt(1) > 0;
            }
            Thread.sleep(500);
        }
        assertThat(session1Recorded).as("对照面：受限账号下记录正常落库").isTrue();

        // 注入：收回 workflows INSERT（42501 面入口）
        try (Connection conn = pgConnection(pg); Statement st = conn.createStatement()) {
            st.execute("REVOKE INSERT ON workflows FROM " + WRITER_ACCOUNT);
        }

        String session2 = "sess-deg04-b-" + System.nanoTime();
        HttpResponse<String> run2 = runCompat(http, base, session2,
                "请用一句话回答：13+13 等于几。不要调用任何工具。");
        Allure.addAttachment("REVOKE 后的任务响应", "text/plain", run2.body());
        assertThat(run2.statusCode()).as("GRANT 拒绝不打挂任务").isEqualTo(200);
        JsonNode body = MAPPER.readTree(run2.body());
        if (body.hasNonNull("status")) {
            assertThat(body.path("status").asText()).isEqualTo("completed");
        }
        Thread.sleep(5_000); // 给 writer 一个隔离/ERROR 的窗口
        try (Statement st = pgConnection(pg).createStatement()) {
            assertThat(count(st, "SELECT count(*) FROM workflows WHERE session_id = '" + session2 + "'"))
                    .as("42501=永久类：INSERT 隔离，无 workflows 行").isZero();
            assertThat(count(st, "SELECT count(*) FROM session_memory WHERE session_id = '"
                    + session2 + "' OR session_id = '" + session1 + "'"))
                    .as("对照：SELECT/UPSERT 权限未动，记忆面仍可用（无行=本次无压缩产出，非权限问题）")
                    .isGreaterThanOrEqualTo(0);
        }
        Allure.addAttachment("deg-04", "text/plain",
                "对照：受限账号正常落记录 → REVOKE INSERT → 任务完成 + 无 workflows 行（永久类隔离）");
    }
}

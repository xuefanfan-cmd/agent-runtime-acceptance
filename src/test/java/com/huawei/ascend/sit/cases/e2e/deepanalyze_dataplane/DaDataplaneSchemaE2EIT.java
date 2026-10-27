package com.huawei.ascend.sit.cases.e2e.deepanalyze_dataplane;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FEAT-069 schema 演进（f069-schema-01/02/03/04/05/06；L2 §4.3/§5.1）。
 *
 * <p>本类自管生命周期（不走 BaseManagedStackTest 的类级栈）：FK 预置与僵尸行
 * 必须发生在 SUT 启动前，而基类的 @BeforeAll 会先启动栈——因此用类级手工 PG 容器
 * + 方法级 SutStack（try-with-resources，jdbc-url 直连属性注入，不经 serviceBinding）。
 *
 * <p>SUT 启动期幂等 DDL（agent_todos 建表 / workflows.a2a_task_id 加列 / 两处 FK 拆除）
 * 是被测对象本身：FK 预置存在（约束名 001 内联自动命名 / 026 显式命名），不预置则
 * 拆除断言空洞通过（细档 §3 前置条件）。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-069")
@Feature("FEAT-069: deepanalyze-java 数据面权限与写方迁移")
class DaDataplaneSchemaE2EIT {

    @SuppressWarnings("resource")
    private static final GenericContainer<?> PG = new GenericContainer<>(
            DockerImageName.parse("postgres:16"))
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", DaDataplaneSupport.PG_DB)
            .withEnv("POSTGRES_USER", DaDataplaneSupport.PG_USER)
            .withEnv("POSTGRES_PASSWORD", DaDataplaneSupport.PG_PASSWORD);

    private static TestConfig config;

    @BeforeAll
    static void setUp() throws Exception {
        PG.start();
        config = TestConfig.load();
        try (Connection conn = superConnection()) {
            DaDataplaneSupport.applyBaselineDdl(conn);
            // 僵尸行预置（f069-schema-05）：上次崩溃残留的 running 行
            try (Statement st = conn.createStatement()) {
                st.execute("INSERT INTO workflows (id, session_id, status) VALUES ('wf-zombie', 'sess-schema', 'running')");
            }
        }
    }

    @AfterAll
    static void tearDown() {
        PG.stop();
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/"
                + DaDataplaneSupport.PG_DB;
    }

    private static Connection superConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), DaDataplaneSupport.PG_USER,
                DaDataplaneSupport.PG_PASSWORD);
    }

    private static SutStack.Builder builderWithPg() {
        return SutStack.builder(config)
                .streaming(true)
                .agent(DaDataplaneSupport.AGENT, agent -> {
                    DaDataplaneSupport.bindModelEnvironment(agent);
                    if (System.getenv("LLM_API_KEY") == null || System.getenv("LLM_API_KEY").isBlank()) {
                        // 本类不执行任务（零 LLM 调用）；宿主 boot 对 api_key 只做非空 fail-fast
                        // （DaModelEnvironment 注释口径），占位值仅为满足启动前置，非伪造执行条件
                        agent.property("LLM_API_KEY", "fixture-schema-boot-only");
                    }
                    agent.property("deepanalyze.data.pg.jdbc-url", jdbcUrl())
                            .property("deepanalyze.data.pg.username", DaDataplaneSupport.PG_USER)
                            .property("deepanalyze.data.pg.password", DaDataplaneSupport.PG_PASSWORD)
                            .property("spring.datasource.url", jdbcUrl())
                            .property("spring.datasource.username", DaDataplaneSupport.PG_USER)
                            .property("spring.datasource.password", DaDataplaneSupport.PG_PASSWORD);
                });
    }

    @Test
    @Story("f069-schema-01/02/05: 启动建表加列、FK 拆除、僵尸行收口")
    @DisplayName("schema-01/02/05: 启动演进幂等形态与 FK 拆除、僵尸行收口")
    void bootEvolvesSchemaAndDropsForeignKeys() throws Exception {
        try (SutStack stack = builderWithPg().start()) {
            try (Connection conn = superConnection(); Statement st = conn.createStatement()) {
                // 01: agent_todos 表（SUT 自建，基线未预置）+ expires_at 列
                assertThat(DaDataplaneSupport.tableExists(conn, "agent_todos"))
                        .as("SUT 启动必须建出 agent_todos（agent-schema.sql）").isTrue();
                assertThat(DaDataplaneSupport.columnExists(conn, "agent_todos", "expires_at"))
                        .as("agent_todos 须含 expires_at TTL 承载列").isTrue();
                // 01: workflows.a2a_task_id 加列 + 索引
                assertThat(DaDataplaneSupport.columnExists(conn, "workflows", "a2a_task_id"))
                        .as("workflows 须新增 a2a_task_id 列").isTrue();
                try (ResultSet rs = st.executeQuery(
                        "SELECT 1 FROM pg_indexes WHERE indexname = 'idx_workflows_a2a_task_id'")) {
                    assertThat(rs.next()).as("a2a_task_id 索引须在场").isTrue();
                }
                // 02: 两处对 sessions 的 FK 拆除（约束名漂移残留即启动失败的设计前提）
                assertThat(DaDataplaneSupport.constraintExists(conn, "session_memory",
                        "session_memory_session_id_fkey"))
                        .as("session_memory 对 sessions 的 FK 必须已拆除").isFalse();
                assertThat(DaDataplaneSupport.constraintExists(conn, "reports",
                        "reports_session_id_fkey"))
                        .as("reports 对 sessions 的 FK 必须已拆除").isFalse();
                // 02 对照组：自有表之间 FK 保留
                assertThat(DaDataplaneSupport.constraintExists(conn, "report_references",
                        "report_references_report_id_fkey"))
                        .as("report_references 对 reports 的自有表 FK 须保留").isTrue();
                // 05: 僵尸行收口
                try (ResultSet rs = st.executeQuery(
                        "SELECT status, coalesce(error, '') FROM workflows WHERE id = 'wf-zombie'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).as("僵尸 running 行须收口").isEqualTo("failed");
                    assertThat(rs.getString(2))
                            .as("收口 error 须标注 writer interrupted at restart（词表内取值）")
                            .contains("writer interrupted at restart");
                }
                Allure.addAttachment("schema 演进断言", "text/plain",
                        "agent_todos=在场, a2a_task_id=在场, 两处 FK=已拆, 自有 FK=保留, 僵尸行=failed");
            }
        }
    }

    @Test
    @Story("f069-schema-06: 重复启动幂等不报错")
    @DisplayName("schema-06: 同库二次启动幂等")
    void secondBootIsIdempotent() throws Exception {
        try (SutStack first = builderWithPg().start()) {
            // 首次启动完成演进
        }
        try (SutStack second = builderWithPg().start()) {
            try (Connection conn = superConnection()) {
                assertThat(DaDataplaneSupport.tableExists(conn, "agent_todos")).isTrue();
                assertThat(DaDataplaneSupport.constraintExists(conn, "session_memory",
                        "session_memory_session_id_fkey")).isFalse();
            }
        }
    }

    @Test
    @Story("f069-schema-03: 约束名漂移残留即启动失败")
    @DisplayName("schema-03: FK 约束名漂移 → 启动失败且指明实际约束名")
    void driftedConstraintFailsStartup() throws Exception {
        // 自建漂移态（顺序无关）：先清掉两个 canonical 约束，再造一个 _old 命名的残留
        try (Connection conn = superConnection(); Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE session_memory DROP CONSTRAINT IF EXISTS session_memory_session_id_fkey");
            st.execute("ALTER TABLE reports DROP CONSTRAINT IF EXISTS reports_session_id_fkey");
            st.execute("ALTER TABLE session_memory ADD CONSTRAINT session_memory_session_id_fkey_old "
                    + "FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE");
        }
        try {
            assertThatThrownBy(() -> {
                SutStack stack = builderWithPg().start();
                stack.close();
            }).as("约束名漂移残留必须 fail-fast（L2 §4.3：防 DROP IF EXISTS 静默 no-op）")
                    .isInstanceOf(Exception.class)
                    .hasMessageContaining("session_memory_session_id_fkey");
        } finally {
            // 恢复 canonical 状态（顺序无关：无论先跑哪个用例，退出时两表回到 canonical 形态）
            try (Connection conn = superConnection(); Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE session_memory DROP CONSTRAINT IF EXISTS session_memory_session_id_fkey_old");
                st.execute("ALTER TABLE session_memory DROP CONSTRAINT IF EXISTS session_memory_session_id_fkey");
                st.execute("ALTER TABLE session_memory ADD CONSTRAINT session_memory_session_id_fkey "
                        + "FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE");
                st.execute("ALTER TABLE reports DROP CONSTRAINT IF EXISTS reports_session_id_fkey");
                st.execute("ALTER TABLE reports ADD CONSTRAINT reports_session_id_fkey "
                        + "FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE");
            } catch (SQLException restoreFailure) {
                // canonical 约束若已被并行启动的 SUT 演进拆掉，ADD 会报 duplicate——该形态下状态仍正确
                Allure.addAttachment("schema-03 恢复说明", "text/plain", String.valueOf(restoreFailure.getMessage()));
            }
        }
    }

    @Test
    @Story("f069-schema-04: jdbc-url 缺失启动失败（现状语义）")
    @DisplayName("schema-04: pg.enabled=true 且 jdbc-url 空 → fail-fast")
    void missingJdbcUrlFailsStartup() {
        SutStack.Builder noUrl = SutStack.builder(config)
                .streaming(true)
                .agent(DaDataplaneSupport.AGENT, agent -> DaDataplaneSupport.bindModelEnvironment(agent));
        assertThatThrownBy(() -> {
            SutStack stack = noUrl.start();
            stack.close();
        }).as("数据面开启且无连接串必须启动失败（L2 §6.2 现状语义）")
                .isInstanceOf(Exception.class);
    }
}

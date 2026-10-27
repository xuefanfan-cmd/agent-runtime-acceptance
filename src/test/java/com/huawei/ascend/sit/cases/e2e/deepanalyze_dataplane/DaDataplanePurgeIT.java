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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-069 保留期自洁（f069-rec-06/07；需求验收第 3 项子项，L2 §3.8 数据生命周期）。
 *
 * <p>零 LLM：自洁由 @Scheduled 节拍驱动（purge-interval-ms 调短），超期数据直接 SQL 构造
 * （需求 §3.4："验收按配置值构造超期数据判定"）。两个 boot 共用一个类级 PG：
 * boot1 retention=30d 断言超期删/未超期留（rec-06）；boot2 retention=0 断言关闭态不清
 * （rec-07）。agent_todos 判据（"会话无 workflows 行才删"）一并覆盖——有行会话的待办保留、
 * 无行会话的待办清除（record.enabled=true 门控默认开）。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-069")
@Feature("FEAT-069: deepanalyze-java 数据面权限与写方迁移")
class DaDataplanePurgeIT {

    @SuppressWarnings("resource")
    private static final GenericContainer<?> PG = new GenericContainer<>(
            DockerImageName.parse("postgres:16"))
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", DaDataplaneSupport.PG_DB)
            .withEnv("POSTGRES_USER", DaDataplaneSupport.PG_USER)
            .withEnv("POSTGRES_PASSWORD", DaDataplaneSupport.PG_PASSWORD);

    private static TestConfig config;
    private static Path materializeRoot;

    @BeforeAll
    static void setUp() throws Exception {
        PG.start();
        config = TestConfig.load();
        materializeRoot = Files.createTempDirectory("feat069-purge-skills");
        try (Connection conn = pgConnection(); Statement st = conn.createStatement()) {
            DaDataplaneSupport.applyBaselineDdl(conn);
            // agent_todos 由 SUT 启动期建表；本类在首个 boot 前就要播种待办行，
            // 故预执行 SUT 交付的演进 DDL（幂等，SUT 稍后自行重放无副作用）
            DaDataplaneSupport.executeScript(conn, DaDataplaneSupport.readArtifactResource(
                    DaDataplaneSupport.SUT_SCHEMA_RESOURCE));
            // rec-06 构造：超期（40 天前）与未超期（1 天前）两组数据
            st.execute("INSERT INTO sessions (id, title) VALUES ('sess-old', '超期组') ON CONFLICT (id) DO NOTHING");
            st.execute("INSERT INTO sessions (id, title) VALUES ('sess-fresh', '未超期组') ON CONFLICT (id) DO NOTHING");
            st.execute("INSERT INTO sessions (id, title) VALUES ('sess-todo-kept', '待办保留组') ON CONFLICT (id) DO NOTHING");
            st.execute("INSERT INTO workflows (id, session_id, status, started_at, completed_at) "
                    + "VALUES ('wf-old', 'sess-old', 'completed', now() - interval '41 days', now() - interval '40 days')");
            st.execute("INSERT INTO workflow_logs (id, workflow_id, event_type, turn) "
                    + "VALUES ('wlog-old', 'wf-old', 'llm_output', 0)");
            st.execute("INSERT INTO workflows (id, session_id, status, started_at, completed_at) "
                    + "VALUES ('wf-fresh', 'sess-fresh', 'completed', now() - interval '2 days', now() - interval '1 days')");
            st.execute("INSERT INTO workflows (id, session_id, status, started_at, completed_at) "
                    + "VALUES ('wf-todo-kept', 'sess-todo-kept', 'completed', now() - interval '2 days', now() - interval '1 days')");
            st.execute("INSERT INTO workflow_logs (id, workflow_id, event_type, turn) "
                    + "VALUES ('wlog-fresh', 'wf-fresh', 'llm_output', 0)");
            st.execute("INSERT INTO reports (id, session_id, title, created_at) "
                    + "VALUES ('rpt-old', 'sess-old', '超期报告', now() - interval '40 days')");
            st.execute("INSERT INTO report_references (report_id, ref_index, doc_id) VALUES ('rpt-old', 0, 'doc-old')");
            st.execute("INSERT INTO reports (id, session_id, title, created_at) "
                    + "VALUES ('rpt-fresh', 'sess-fresh', '未超期报告', now() - interval '1 days')");
            st.execute("INSERT INTO session_memory (id, session_id, content, updated_at) "
                    + "VALUES ('mem-old', 'sess-old', '超期记忆', now() - interval '40 days')");
            st.execute("INSERT INTO session_memory (id, session_id, content, updated_at) "
                    + "VALUES ('mem-fresh', 'sess-fresh', '未超期记忆', now() - interval '1 days')");
            // agent_todos 判据：sess-old 已无 workflows 行（wf-old 会被本轮自洁删除）→ 待办应删；
            // sess-todo-kept 有未超期 workflows 行 → 待办保留
            st.execute("INSERT INTO agent_todos (k, v) VALUES ('sess-old:todo', '[\"old\"]')");
            st.execute("INSERT INTO agent_todos (k, v) VALUES ('sess-todo-kept:todo', '[\"kept\"]')");
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

    private static Connection pgConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), DaDataplaneSupport.PG_USER,
                DaDataplaneSupport.PG_PASSWORD);
    }

    private static SutStack.Builder builder(int retentionDays) {
        return SutStack.builder(config)
                .streaming(true)
                .agent(DaDataplaneSupport.AGENT, agent -> {
                    DaDataplaneSupport.bindModelEnvironment(agent);
                    if (System.getenv("LLM_API_KEY") == null || System.getenv("LLM_API_KEY").isBlank()) {
                        agent.property("LLM_API_KEY", "fixture-purge-boot-only");
                    }
                    agent.property("deepanalyze.data.pg.jdbc-url", jdbcUrl())
                            .property("deepanalyze.data.pg.username", DaDataplaneSupport.PG_USER)
                            .property("deepanalyze.data.pg.password", DaDataplaneSupport.PG_PASSWORD)
                            .property("spring.datasource.url", jdbcUrl())
                            .property("spring.datasource.username", DaDataplaneSupport.PG_USER)
                            .property("spring.datasource.password", DaDataplaneSupport.PG_PASSWORD)
                            .property("deepanalyze.record.retention-days", String.valueOf(retentionDays))
                            .property("deepanalyze.record.purge-interval-ms", "1000")
                            .property("deepanalyze.skills.local-fallback-dir",
                                    materializeRoot.toAbsolutePath().toString());
                });
    }

    private static int count(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    @Story("f069-rec-06: 保留期自洁——超期删除（含级联）、未超期保留")
    @DisplayName("rec-06: 按配置值构造超期数据，自洁后超期删/未超期留")
    void expiredRowsPurgedFreshKept() throws Exception {
        try (SutStack stack = builder(30).start()) {
            // 有界轮询：等自洁节拍处理完超期组（删除量记 INFO；构造数据固定，轮询至断言点成立或超时）
            boolean oldWorkflowGone = false;
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline) {
                try (Statement st = pgConnection().createStatement()) {
                    if (count(st, "SELECT count(*) FROM workflows WHERE id = 'wf-old'") == 0) {
                        oldWorkflowGone = true;
                        break;
                    }
                }
                Thread.sleep(500);
            }
            assertThat(oldWorkflowGone).as("20s 内超期 workflows 行须被自洁（purge 节拍=1s）").isTrue();
            try (Statement st = pgConnection().createStatement()) {
                assertThat(count(st, "SELECT count(*) FROM workflow_logs WHERE id = 'wlog-old'"))
                        .as("超期 workflow_logs 随 workflows 删除").isZero();
                assertThat(count(st, "SELECT count(*) FROM reports WHERE id = 'rpt-old'"))
                        .as("超期 reports 删除").isZero();
                assertThat(count(st, "SELECT count(*) FROM report_references WHERE report_id = 'rpt-old'"))
                        .as("超期 report_references 随 reports 删除").isZero();
                assertThat(count(st, "SELECT count(*) FROM session_memory WHERE id = 'mem-old'"))
                        .as("超期 session_memory 删除").isZero();
                // 未超期组全部保留
                assertThat(count(st, "SELECT count(*) FROM workflows WHERE id = 'wf-fresh'")).isEqualTo(1);
                assertThat(count(st, "SELECT count(*) FROM workflow_logs WHERE id = 'wlog-fresh'")).isEqualTo(1);
                assertThat(count(st, "SELECT count(*) FROM reports WHERE id = 'rpt-fresh'")).isEqualTo(1);
                assertThat(count(st, "SELECT count(*) FROM session_memory WHERE id = 'mem-fresh'")).isEqualTo(1);
                // agent_todos 判据：无行会话删除、有行会话保留
                assertThat(count(st, "SELECT count(*) FROM agent_todos WHERE k = 'sess-old:todo'"))
                        .as("所属会话已无执行记录的待办应被清除（L2 §3.8 判据）").isZero();
                assertThat(count(st, "SELECT count(*) FROM agent_todos WHERE k = 'sess-todo-kept:todo'"))
                        .as("所属会话仍有执行记录的待办保留").isEqualTo(1);
            }
            Allure.addAttachment("rec-06 结果", "text/plain", "超期组(workflows/logs/reports/references/memory/todos)全删；未超期组全保留");
        }
    }

    @Test
    @Story("f069-rec-07: retention<=0 关闭自洁")
    @DisplayName("rec-07: retention-days=0 时不执行自洁")
    void retentionDisabledSkipsPurge() throws Exception {
        try (Connection conn = pgConnection(); Statement st = conn.createStatement()) {
            // 重新构造一条超期行（rec-06 轮可能已清掉旧组；本条独立验证关闭态）
            st.execute("INSERT INTO sessions (id, title) VALUES ('sess-off', '关闭态组') ON CONFLICT (id) DO NOTHING");
            st.execute("INSERT INTO workflows (id, session_id, status, started_at, completed_at) "
                    + "VALUES ('wf-off', 'sess-off', 'completed', now() - interval '41 days', now() - interval '40 days')");
        }
        try (SutStack stack = builder(0).start()) {
            Thread.sleep(6_000); // 覆盖多个 purge 节拍（1s）
            try (Statement st = pgConnection().createStatement()) {
                assertThat(count(st, "SELECT count(*) FROM workflows WHERE id = 'wf-off'"))
                        .as("retention=0 关闭态：超期行不清理（L2 §6.2）").isEqualTo(1);
            }
            Allure.addAttachment("rec-07 结果", "text/plain", "retention-days=0：6s（6 个节拍）后超期行仍在");
        }
    }
}

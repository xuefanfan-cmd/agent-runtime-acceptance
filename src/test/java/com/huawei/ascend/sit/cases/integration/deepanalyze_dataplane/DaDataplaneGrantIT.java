package com.huawei.ascend.sit.cases.integration.deepanalyze_dataplane;

import com.huawei.ascend.sit.cases.e2e.deepanalyze_dataplane.DaDataplaneSupport;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * FEAT-069 三账号 GRANT 脚本语义验证（f069-grant-01~05；需求验收第 1 项承载）。
 *
 * <p>不是 SIT FAIL 发现：以被测制品 0.1.3-feat069 内的交付物
 * {@code db/grant-three-accounts.sql} 为被测对象，在本机 testcontainers PostgreSQL 上
 * 重建需求 §3.1 矩阵的三账号面，验证"权限内操作成功、越权被数据库层拒绝（42501，
 * 非应用层拦截）"。证明的是脚本语义，不是客户环境部署正确性（预览 §7 知悉点 2）。
 *
 * <p>抽样规则照需求原文（L71）：agent 属主表域（会话/执行/技能）每域抽一张 agent 属主表
 * + 一张作战部属主表；知识库/系统域各至少抽一张作战部属主表。
 */
@Tag("integration")
// 2026-10-26：补 e2e 标签——pom failsafe groups=e2e 为显式配置（-Dgroups 压不过，探针类同例），
// 不加则本类永远进不了批次 e2e 跑道（基线 5/5 来自早期专项跑，全量口径不统一）。
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-069")
@Feature("FEAT-069: deepanalyze-java 数据面权限与写方迁移")
class DaDataplaneGrantIT {

    @SuppressWarnings("resource")
    private static final GenericContainer<?> PG = new GenericContainer<>(
            DockerImageName.parse("postgres:16"))
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", DaDataplaneSupport.PG_DB)
            .withEnv("POSTGRES_USER", DaDataplaneSupport.PG_USER)
            .withEnv("POSTGRES_PASSWORD", DaDataplaneSupport.PG_PASSWORD);

    /** 三账号角色名（占位符替换后）。 */
    private static final String AGENT_ACCOUNT = "dp_agent";
    private static final String DA_ACCOUNT = "dp_da";
    private static final String KE_ACCOUNT = "dp_ke";
    private static final String ACCOUNT_PW = "dp_fixture_only";

    @BeforeAll
    static void setUpDatabase() throws Exception {
        PG.start();
        try (Connection superconn = superConnection()) {
            // 1) 基线 schema（不含 agent_todos/a2a_task_id —— 由 SUT 交付的 DDL 补齐）
            DaDataplaneSupport.applyBaselineDdl(superconn);
            // 2) SUT 交付的 schema 演进脚本（agent_todos 建表）
            DaDataplaneSupport.executeScript(superconn, DaDataplaneSupport.readArtifactResource(
                    DaDataplaneSupport.SUT_SCHEMA_RESOURCE));
            // 3) 三账号
            try (Statement st = superconn.createStatement()) {
                st.execute("CREATE ROLE " + AGENT_ACCOUNT + " LOGIN PASSWORD '" + ACCOUNT_PW + "'");
                st.execute("CREATE ROLE " + DA_ACCOUNT + " LOGIN PASSWORD '" + ACCOUNT_PW + "'");
                st.execute("CREATE ROLE " + KE_ACCOUNT + " LOGIN PASSWORD '" + ACCOUNT_PW + "'");
            }
            // 4) SUT 交付的 GRANT 脚本（占位符替换）
            String grantScript = DaDataplaneSupport.readArtifactResource(
                            DaDataplaneSupport.SUT_GRANT_RESOURCE)
                    .replace("<agent_account>", AGENT_ACCOUNT)
                    .replace("<da_account>", DA_ACCOUNT)
                    .replace("<ke_account>", KE_ACCOUNT);
            DaDataplaneSupport.executeScript(superconn, grantScript);
        }
    }

    @AfterAll
    static void tearDown() {
        PG.stop();
    }

    private static Connection superConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), DaDataplaneSupport.PG_USER, DaDataplaneSupport.PG_PASSWORD);
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/" + DaDataplaneSupport.PG_DB;
    }

    private static Connection accountConnection(String account) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), account, ACCOUNT_PW);
    }

    @Test
    @Story("f069-grant-01: 按需求抽样规则，各账号权限内操作成功")
    @DisplayName("grant-01: 权限内操作成功（抽样矩阵正例）")
    void grantedOperationsSucceed() throws Exception {
        // 先以 superuser 预置宿主账号无法自建的外键依赖行
        try (Connection superconn = superConnection();
             Statement st = superconn.createStatement()) {
            st.execute("INSERT INTO sessions (id, title) VALUES ('sess-grant01', 'grant 抽样正例前置会话') ON CONFLICT (id) DO NOTHING");
        }
        // 会话域 agent 属主表：session_memory 全四操作
        try (Connection conn = accountConnection(AGENT_ACCOUNT)) {
            exec(conn, "INSERT INTO session_memory (id, session_id, content) VALUES ('mem-g01', 'sess-grant01', 'grant-01 正例') ");
            exec(conn, "SELECT content FROM session_memory WHERE id = 'mem-g01'");
            exec(conn, "UPDATE session_memory SET content = 'grant-01 更新' WHERE id = 'mem-g01'");
        }
        // 执行域 agent 属主表：workflows 插/删、reports 插
        try (Connection conn = accountConnection(AGENT_ACCOUNT)) {
            exec(conn, "INSERT INTO workflows (id, session_id) VALUES ('wf-g01', 'sess-grant01')");
            exec(conn, "DELETE FROM workflows WHERE id = 'wf-g01'");
            exec(conn, "INSERT INTO reports (id, session_id, title) VALUES ('rpt-g01', 'sess-grant01', 'grant-01 正例报告')");
        }
        // 技能域 agent 属主表：agent_skills 全四操作（RLS fail-open：未设租户 GUC 放行）
        try (Connection conn = accountConnection(AGENT_ACCOUNT)) {
            exec(conn, "INSERT INTO agent_skills (id, name, prompt, source) VALUES ('sk-g01', 'grant01-skill', 'grant 正例技能', 'manual')");
            exec(conn, "UPDATE agent_skills SET description = 'grant-01 更新' WHERE id = 'sk-g01'");
            exec(conn, "SELECT name FROM agent_skills WHERE id = 'sk-g01'");
            exec(conn, "DELETE FROM agent_skills WHERE id = 'sk-g01'");
        }
        // agent 属主表的执行装配只读面
        try (Connection conn = accountConnection(AGENT_ACCOUNT)) {
            exec(conn, "SELECT id FROM agent_teams");
            exec(conn, "SELECT id FROM agent_team_members");
        }
        // DA 账号正例：session_memory 全四操作（运维纠错）、workflows 读+删、agent_teams 写
        try (Connection superconn = superConnection();
             Statement st = superconn.createStatement()) {
            st.execute("INSERT INTO sessions (id, title) VALUES ('sess-grant01-da', 'da 正例会话') ON CONFLICT (id) DO NOTHING");
            st.execute("INSERT INTO workflows (id, session_id) VALUES ('wf-g01-da', 'sess-grant01-da')");
            st.execute("INSERT INTO agent_teams (id, name) VALUES ('team-g01', 'grant01 团队')");
            st.execute("INSERT INTO agent_skills (id, name, prompt, source) VALUES ('sk-g01-da', 'grant01-da-skill', 'da 正例', 'manual')");
        }
        try (Connection conn = accountConnection(DA_ACCOUNT)) {
            exec(conn, "INSERT INTO session_memory (id, session_id, content) VALUES ('mem-g01-da', 'sess-grant01-da', 'da 正例')");
            exec(conn, "UPDATE session_memory SET content = 'da 直改' WHERE id = 'mem-g01-da'");
            exec(conn, "SELECT title FROM reports WHERE id = 'rpt-g01'");
            exec(conn, "DELETE FROM workflows WHERE id = 'wf-g01-da'");
            exec(conn, "SELECT id FROM agent_teams WHERE id = 'team-g01'");
            exec(conn, "UPDATE agent_teams SET description = 'da 界面直写' WHERE id = 'team-g01'");
        }
        Allure.addAttachment("grant-01 正例矩阵", "text/plain",
                "agent: session_memory 全四操作 + workflows 插删 + reports 插 + agent_skills 全四 + teams 只读；"
                        + "da: session_memory 全四 + workflows 读删 + agent_teams 写 + skills 写");
    }

    @Test
    @Story("f069-grant-02: 越权操作被数据库层拒绝（42501，非应用层拦截）")
    @DisplayName("grant-02: 越权一律 SQLState 42501")
    void deniedOperationsAreDatabaseLevel() throws Exception {
        try (Connection superconn = superConnection();
             Statement st = superconn.createStatement()) {
            st.execute("INSERT INTO sessions (id, title) VALUES ('sess-grant02', 'grant 越权前置会话') ON CONFLICT (id) DO NOTHING");
            st.execute("INSERT INTO workflows (id, session_id) VALUES ('wf-g02', 'sess-grant02')");
        }
        // agent 账号对作战部属主表（会话/执行/知识库/系统域抽样）：写一律 42501
        try (Connection conn = accountConnection(AGENT_ACCOUNT)) {
            assertDenied(conn, "INSERT INTO sessions (id) VALUES ('x')");
            assertDenied(conn, "INSERT INTO messages (id, session_id, role) VALUES ('x', 'sess-grant02', 'user')");
            assertDenied(conn, "INSERT INTO agent_tasks (id) VALUES ('x')");
            assertDenied(conn, "INSERT INTO settings (key, value) VALUES ('k', '{}')");
            assertDenied(conn, "INSERT INTO knowledge_bases (id, name) VALUES ('x', 'n')");
            assertDenied(conn, "INSERT INTO cron_jobs (id, name) VALUES ('x', 'n')");
            assertDenied(conn, "DELETE FROM sessions WHERE id = 'sess-grant02'");
        }
        // DA 账号对 agent 属主表：只读+删，不得写内容
        try (Connection conn = accountConnection(DA_ACCOUNT)) {
            assertDenied(conn, "INSERT INTO workflows (id, session_id) VALUES ('x', 'sess-grant02')");
            assertDenied(conn, "UPDATE workflows SET status = 'completed' WHERE id = 'wf-g02'");
            assertDenied(conn, "INSERT INTO workflow_logs (id, workflow_id, event_type) VALUES ('x', 'wf-g02', 'llm_output')");
            assertDenied(conn, "INSERT INTO reports (id, session_id) VALUES ('x', 'sess-grant02')");
            assertDenied(conn, "INSERT INTO agent_todos (k, v) VALUES ('x', 'y')");
        }
        // 知识工程组账号一期全负例的域内代表：知识库域写
        try (Connection conn = accountConnection(KE_ACCOUNT)) {
            assertDenied(conn, "INSERT INTO wiki_pages (id) VALUES ('x')");
        }
    }

    @Test
    @Story("f069-grant-03: agent 账号对作战部属主表默认无权限（GRANT 未列=无权限）")
    @DisplayName("grant-03: agent 账号对作战部属主表零授权（information_schema 全表核对）")
    void agentAccountHasNoPrivilegesOnUpstreamTables() throws Exception {
        try (Connection conn = superConnection()) {
            List<String> upstreamTables = List.of("sessions", "messages", "agent_tasks", "settings",
                    "knowledge_bases", "documents", "embeddings", "anchors", "tags", "document_tags",
                    "search_cache", "users", "audit_log", "cron_jobs", "module_states",
                    "config_versions", "extension_requests", "plugins", "skills");
            for (String table : upstreamTables) {
                assertThat(hasAnyGrant(conn, AGENT_ACCOUNT, table))
                        .as("agent 账号对作战部属主表 %s 必须零授权（GRANT 脚本未列=无权限）", table)
                        .isFalse();
            }
        }
    }

    @Test
    @Story("f069-grant-04: KB 一期直读例外：wiki_pages/wiki_links 只读")
    @DisplayName("grant-04: wiki 两表 SELECT 成功、写 42501")
    void kbExceptionTablesAreReadOnly() throws Exception {
        try (Connection superconn = superConnection();
             Statement st = superconn.createStatement()) {
            st.execute("INSERT INTO wiki_pages (id, title) VALUES ('wp-g04', '一期直读例外页') ON CONFLICT (id) DO NOTHING");
            st.execute("INSERT INTO wiki_links (id, source_page_id, target_page_id) VALUES ('wl-g04', 'wp-g04', 'wp-g04') ON CONFLICT (id) DO NOTHING");
        }
        try (Connection conn = accountConnection(AGENT_ACCOUNT)) {
            exec(conn, "SELECT title FROM wiki_pages WHERE id = 'wp-g04'");
            exec(conn, "SELECT source_page_id FROM wiki_links WHERE id = 'wl-g04'");
        }
        try (Connection conn = accountConnection(AGENT_ACCOUNT)) {
            assertDenied(conn, "INSERT INTO wiki_pages (id, title) VALUES ('wp-x', 't')");
            assertDenied(conn, "UPDATE wiki_pages SET title = 'x' WHERE id = 'wp-g04'");
            assertDenied(conn, "DELETE FROM wiki_pages WHERE id = 'wp-g04'");
            assertDenied(conn, "INSERT INTO wiki_links (id) VALUES ('x')");
        }
    }

    @Test
    @Story("f069-grant-05: 知识工程组账号一期全负例（矩阵一期全\"无\"）")
    @DisplayName("grant-05: ke 账号抽样表四操作全 42501")
    void keAccountHasNoPrivilegesInPhaseOne() throws Exception {
        // 矩阵一期：ke 账号对全部 33 表权限均为"无"（正例集为空的显式声明，预览 §7）
        // 抽样：每域一张代表表 × 四操作
        List<String> sampled = List.of("sessions", "workflow_logs", "agent_skills", "knowledge_bases",
                "wiki_pages", "users");
        try (Connection conn = accountConnection(KE_ACCOUNT)) {
            for (String table : sampled) {
                assertDenied(conn, "SELECT * FROM " + table);
                assertDenied(conn, "INSERT INTO " + table + " (" + pkColumn(table) + ") VALUES ('x')");
                assertDenied(conn, "DELETE FROM " + table);
            }
        }
        Allure.addAttachment("grant-05 抽样", "text/plain",
                "ke 账号一期正例集为空（矩阵全\"无\"），全负例判定；抽样=" + sampled);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            if (st.execute(sql)) {
                try (ResultSet rs = st.getResultSet()) {
                    while (rs.next()) {
                        // 消费结果集即可
                    }
                }
            }
        }
    }

    private static void assertDenied(Connection conn, String sql) {
        PSQLException ex = catchThrowableOfType(() -> exec(conn, sql), PSQLException.class);
        assertThat((Throwable) ex).as("越权必须以 PSQLException 形式被数据库层拒绝: %s", sql).isNotNull();
        assertThat(ex.getSQLState()).as("越权 SQLState 必须是 42501（GRANT 层拒绝）: %s", sql).isEqualTo("42501");
    }

    private static boolean hasAnyGrant(Connection conn, String account, String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM information_schema.role_table_grants "
                     + "WHERE grantee = '" + account + "' AND table_name = '" + table + "'")) {
            return rs.next();
        }
    }

    private static String pkColumn(String table) {
        return switch (table) {
            case "sessions", "workflow_logs", "agent_skills", "knowledge_bases", "wiki_pages", "users" -> "id";
            default -> "id";
        };
    }
}

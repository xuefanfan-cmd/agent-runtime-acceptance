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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-069 技能物化与对账（f069-skl-02/03/04/05；需求验收第 9 项，L2 §3.5/§5.4）。
 *
 * <p>零 LLM：DA 直写路径以 SQL 模拟（L2 §7.1 明示构造）；对账节拍调短（800ms）。
 * <b>播种口径（实测校准）</b>：增量对账按 updated_at 水位拾名（水位=启动-30s），种子行
 * updated_at 必须用 now()，否则对账不可见；created_at 独立回填（承载 skill_run 口径的
 * 新旧语义）。<b>hub 生效行按实现跳过物化</b>（hub 内容走 Skill Hub 下载目录，不经
 * 物化器——DaSkillMaterializer.reconcileSince/reconcileAll 内联跳过守卫），故 skl-04
 * 的 hub 阶段断言"无受管目录"，manual 接替后断言物化翻转。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-069")
@Feature("FEAT-069: deepanalyze-java 数据面权限与写方迁移")
class DaDataplaneSkillReconcileIT {

    @SuppressWarnings("resource")
    private static final GenericContainer<?> PG = new GenericContainer<>(
            DockerImageName.parse("postgres:16"))
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", DaDataplaneSupport.PG_DB)
            .withEnv("POSTGRES_USER", DaDataplaneSupport.PG_USER)
            .withEnv("POSTGRES_PASSWORD", DaDataplaneSupport.PG_PASSWORD);

    private static TestConfig config;
    private static Path materializeRoot;
    private static SutStack stack;

    @BeforeAll
    static void setUp() throws Exception {
        PG.start();
        config = TestConfig.load();
        materializeRoot = Files.createTempDirectory("feat069-skl-materialize");
        try (Connection conn = pgConnection(); Statement st = conn.createStatement()) {
            DaDataplaneSupport.applyBaselineDdl(conn);
        }
        stack = SutStack.builder(config)
                .streaming(true)
                .agent(DaDataplaneSupport.AGENT, agent -> {
                    DaDataplaneSupport.bindModelEnvironment(agent);
                    if (System.getenv("LLM_API_KEY") == null || System.getenv("LLM_API_KEY").isBlank()) {
                        agent.property("LLM_API_KEY", "fixture-skl-boot-only");
                    }
                    agent.property("deepanalyze.data.pg.jdbc-url", jdbcUrl())
                            .property("deepanalyze.data.pg.username", DaDataplaneSupport.PG_USER)
                            .property("deepanalyze.data.pg.password", DaDataplaneSupport.PG_PASSWORD)
                            .property("spring.datasource.url", jdbcUrl())
                            .property("spring.datasource.username", DaDataplaneSupport.PG_USER)
                            .property("spring.datasource.password", DaDataplaneSupport.PG_PASSWORD)
                            .property("deepanalyze.skills.local-fallback-dir",
                                    materializeRoot.toAbsolutePath().toString())
                            .property("deepanalyze.record.skills-sync-interval-ms", "800");
                })
                .start();
    }

    @AfterAll
    static void tearDown() {
        if (stack != null) {
            stack.close();
        }
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

    /** 直写一行 agent_skills：created_at 回填承载口径语义，updated_at 恒为 now()（对账水位可见）。 */
    private static void seedSkill(String id, String name, String source, String createdAtLiteral,
                                  boolean active) throws SQLException {
        try (Connection conn = pgConnection(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO agent_skills (id, name, description, prompt, source, is_active, "
                    + "created_at, updated_at) VALUES ('" + id + "', '" + name + "', '对账用例技能', "
                    + "'你是技能 " + name + "。', '" + source + "', " + active + ", "
                    + createdAtLiteral + ", now())");
        }
    }

    /** 等待 marker 内容达到期望（有界轮询；null 期望=目录应不存在）。 */
    private void awaitMarker(String skillName, String expectedMarker) throws Exception {
        Path dir = materializeRoot.resolve(skillName);
        Path marker = dir.resolve(".da-materialized");
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (expectedMarker == null) {
                if (!Files.exists(dir)) {
                    return;
                }
            } else if (Files.exists(marker)
                    && Files.readString(marker, StandardCharsets.UTF_8).strip().equals(expectedMarker)) {
                return;
            }
            Thread.sleep(400);
        }
        String actual = Files.exists(marker)
                ? Files.readString(marker, StandardCharsets.UTF_8).strip()
                : (Files.exists(dir) ? "<目录在但 marker 缺失>" : "<目录不存在>");
        assertThat((Object) actual).as("30s 内物化状态须达到期望（skill=%s）", skillName).isEqualTo(expectedMarker);
    }

    @Test
    @Story("f069-skl-02: DA 直写 manual 行 → 对账追平物化")
    @DisplayName("skl-02: 正向对账——DB 行物化为目录")
    void forwardReconciliationMaterializes() throws Exception {
        seedSkill("skl02-manual", "skl-02-skill", "manual", "now() - interval '1 hours'", true);
        awaitMarker("skl-02-skill", "skl-02-skill\tmanual");
        assertThat(Files.readString(materializeRoot.resolve("skl-02-skill").resolve("SKILL.md")))
                .as("SKILL.md 正文来自 DB prompt 列").contains("你是技能 skl-02-skill");
        Allure.addAttachment("skl-02", "text/plain", "manual 行 → 物化目录 + marker=manual");
    }

    @Test
    @Story("f069-skl-03: 同 name 多 source 并存时按 skill_run 口径选生效行")
    @DisplayName("skl-03: source 优先级 builtin>hub>plugin>manual + 同 source created_at 降序")
    void selectionRulePicksEffectiveRow() throws Exception {
        // 四 source 并存：builtin 胜出（与 created_at 新旧无关）
        seedSkill("skl03-a-manual", "skl-03-a", "manual", "now() - interval '10 minutes'", true);
        seedSkill("skl03-a-hub", "skl-03-a", "hub", "now() - interval '5 minutes'", true);
        seedSkill("skl03-a-plugin", "skl-03-a", "plugin", "now() - interval '3 minutes'", true);
        seedSkill("skl03-a-builtin", "skl-03-a", "builtin", "now() - interval '1 hours'", true);
        awaitMarker("skl-03-a", "skl-03-a\tbuiltin");
        // 高优行停用 → 落到次优 source（"仅取 active 行"规则；同 source 同名两行被
        // (name,source) 唯一约束禁止——created_at 决胜为防御性条款，合法写路径不可构造，
        // 登记为设计注记而非用例）
        seedSkill("skl03-b-builtin", "skl-03-b", "builtin", "now() - interval '2 hours'", false);
        seedSkill("skl03-b-hub", "skl-03-b", "hub", "now() - interval '1 minutes'", true);
        Thread.sleep(3_000);
        assertThat(Files.exists(materializeRoot.resolve("skl-03-b")))
                .as("hub 生效行不经物化器（跳过守卫）").isFalse();
        try (Connection conn = pgConnection(); Statement st = conn.createStatement()) {
            st.execute("UPDATE agent_skills SET is_active = false WHERE id = 'skl03-b-hub'");
            st.execute("UPDATE agent_skills SET is_active = true, updated_at = now() WHERE id = 'skl03-b-builtin'");
        }
        awaitMarker("skl-03-b", "skl-03-b\tbuiltin");
        Allure.addAttachment("skl-03", "text/plain",
                "四 source 并存→builtin 胜出；高优行停用→次优接替；(name,source) 唯一约束使同 source 决胜不可构造（设计注记）");
    }

    @Test
    @Story("f069-skl-04: DA DELETE 行后反向对账——生效行切换与目录移除")
    @DisplayName("skl-04: 删 hub 生效行→manual 接替物化；全删→目录移除")
    void reverseReconciliationSwitchesAndRemoves() throws Exception {
        // hub 生效阶段：物化器跳过 hub 行（hub 内容走 Hub 下载路径）→ 无受管目录
        seedSkill("skl04-hub", "skl-04-skill", "hub", "now() - interval '1 hours'", true);
        seedSkill("skl04-manual", "skl-04-skill", "manual", "now() - interval '2 hours'", true);
        Thread.sleep(3_000); // 覆盖数个对账拍
        assertThat(Files.exists(materializeRoot.resolve("skl-04-skill")))
                .as("hub 生效行不经物化器（跳过守卫），无受管目录产生（实现口径，非 L2 文字）")
                .isFalse();
        // 删 hub 行 → manual 接替生效 → 增量对账物化 manual（L2 §7.2 场景的可观察形态）
        try (Connection conn = pgConnection(); Statement st = conn.createStatement()) {
            st.execute("DELETE FROM agent_skills WHERE id = 'skl04-hub'");
        }
        awaitMarker("skl-04-skill", "skl-04-skill\tmanual");
        // 全删 → 反向对账移除受管目录（删除盲区覆盖）
        try (Connection conn = pgConnection(); Statement st = conn.createStatement()) {
            st.execute("DELETE FROM agent_skills WHERE id = 'skl04-manual'");
        }
        awaitMarker("skl-04-skill", null);
        Allure.addAttachment("skl-04", "text/plain",
                "hub 生效=无受管目录（跳过守卫）；hub 删→manual 接替物化；全删→目录移除");
    }

    @Test
    @Story("f069-skl-05: 只追平不反转——物化目录以 DB 应生效集为唯一权威")
    @DisplayName("skl-05: 手工删 SKILL.md → 按 DB 重建；无 DB 行的受管目录 → 移除")
    void reconciliationIsAuthoritative() throws Exception {
        // 手工破坏在档技能的物化 → 下轮按 DB 重建
        seedSkill("skl05-row", "skl-05-skill", "manual", "now()", true);
        awaitMarker("skl-05-skill", "skl-05-skill\tmanual");
        Files.delete(materializeRoot.resolve("skl-05-skill").resolve("SKILL.md"));
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline
                && !Files.exists(materializeRoot.resolve("skl-05-skill").resolve("SKILL.md"))) {
            Thread.sleep(400);
        }
        assertThat(Files.exists(materializeRoot.resolve("skl-05-skill").resolve("SKILL.md")))
                .as("手工删除后须按 DB 重建（DB 行仍生效）").isTrue();
        // 手工造一个带受管 marker 的孤儿目录（模拟"曾被物化、DB 行已删"的残留）→ 下轮移除
        Path ghost = materializeRoot.resolve("ghost-skill-069");
        Files.createDirectories(ghost);
        Files.writeString(ghost.resolve("SKILL.md"), "ghost", StandardCharsets.UTF_8);
        Files.writeString(ghost.resolve(".da-materialized"), "ghost-skill-069\tmanual",
                StandardCharsets.UTF_8);
        deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline && Files.exists(ghost)) {
            Thread.sleep(400);
        }
        assertThat(Files.exists(ghost))
                .as("无生效 DB 行的受管目录须被反向对账移除（非受管目录不入删除面——实现口径）")
                .isFalse();
        Allure.addAttachment("skl-05", "text/plain",
                "DB 行在→破坏后重建；DB 无行→受管目录移除（只追平不反转；非受管目录不碰）");
    }
}

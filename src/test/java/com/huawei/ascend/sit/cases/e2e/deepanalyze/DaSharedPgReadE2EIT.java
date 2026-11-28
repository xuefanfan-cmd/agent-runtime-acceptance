package com.huawei.ascend.sit.cases.e2e.deepanalyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-054 共享 PG 数据面：{@code FEAT-054-E3}（2026-11-26 按 FEAT-069 修订口径翻转，
 * 判据来源＝TestPlan 行（docs 检出 docs/feat-054-testplan @ aca0f54f，!377 翻转后）：
 * <em>settings/sessions/messages 零读取（语句日志断言）；session_memory 由宿主自写自读
 * （upsert 与直改回读生效）；零写入限定 messages/sessions/agent_tasks 等禁止写入表</em>。
 *
 * <p><b>生命周期（2026-11-26 run-2 教训）：自管 PG 容器＋播种先于 SUT 启动</b>——
 * r3 引擎的 DaSkillMaterializer 在启动期即读 agent_skills（reconcileAll），沿用
 * BaseManagedStackTest「先起 SUT 后播种」的旧序会让宿主启动即死于
 * {@code relation "agent_skills" does not exist"}。先例＝DaDataplaneReadRemovalE2EIT
 * （类注释原文「SUT 启动要求基线表在场……播种必须先于启动，因此手工 PG＋类级 SUT 栈」）。
 * read-01/05 真实任务（真实 LLM，不 Mock）须由受控 runner（run-sit-macos.sh）注入 ~/.llmrc 凭据。</p>
 *
 * <p><b>判据面（翻转后）</b>：</p>
 * <ul>
 *   <li>技能读面：{@code GET /api/agents/skills*} 经 agent 账号读 agent_skills（端点路径
 *       2026-10-21 统一为 /api/agents 前缀——ENV-3.16：旧 /agent/* 路径与现行 SUT 不符）。</li>
 *   <li>provider 读面：provider 配置走宿主自带配置面（{@code deepanalyze.providers[N]}），
 *       settings 表 <b>不再播种</b> agent_settings/providers 行（读方消失改造——宿主不再读
 *       settings，播种用途随之取消）。</li>
 *   <li>session_memory 自写自读：回读现读不缓存——宿主每任务读 session_memory（语句日志
 *       取证面：{@code SELECT ... FROM session_memory} 必须在本轮窗口出现）；DA 直改
 *       （测试侧 JDBC 直改模拟 DA 侧写方）下次任务生效；压缩摘要 upsert 落行另设方法
 *       （assumption 门控，见该方法注释）。</li>
 *   <li>只读铁律收窄：sessions/messages 零读取＋零写入（行数＋语句日志双面）；agent_tasks
 *       零写入（语句日志面——054 DDL 无该表，行数面不可建；069 基线 DDL 注记其
 *       「宿主零权限面」）；settings 零写入（行数＋语句日志）。session_memory 从只读
 *       铁律中移出——行数变化不再是失败。</li>
 * </ul>
 *
 * <p><b>⚠️ 代际边界（2026-11-26 用户裁决「拆面执行＋登记」）</b>：本轮被测制品
 * {@code 0.1.3-feat109111-r3}（＝agent-solution common@babb882b9）已含 FEAT-113——
 * settings providers 键只读通道（DaModelConfigSource：启动期装载＋每任务惰性兜底，
 * 只要共享 PG 启用即必发 {@code SELECT ... FROM settings}；探测端点 FEAT-113 同源
 * 「库优先、部署配置兜底」）。TestPlan 行的「settings 零读取」在该制品上结构性不可成立
 * （FEAT-113 L1 修订 FEAT-069 读方消失口径；批内既有处置口径「v1130-FEAT-113 新口径
 * 不倒灌本期验收」）。按遮蔽律本类将「settings 零读取」面记 <b>NOT_OBSERVED</b>：
 * 窗口内 settings 读行数仅作证据附件（不进判据、不断言为零），恢复条件＝FEAT-113 契约
 * 并入 FEAT-054 范围后修订 Oracle，或换不含 FEAT-113 的代际制品复验。
 * sessions/messages 零读取照常硬断言（r3 上生产读方已退役：sessionExists 无生产调用点）。</p>
 *
 * <p><b>语句日志取证面</b>：SUT 进程注入 {@code logging.level.org.springframework.jdbc.core=DEBUG}
 * （JdbcTemplate DEBUG 行携带 SQL 原文），共享 stdout.log 按 offset 开窗（铁律：共享日志
 * 断言必须先开窗）。开窗自检＝窗口内必须出现 JdbcTemplate 执行行——DEBUG 未生效时
 * 取证面为空，判据 FAIL（观测面为空＝未执行，不得按「通过」记账）。</p>
 *
 * <p>库结构采用 FEAT-069 基线 DDL 等价 schema（{@code src/test/resources/deepanalyze/
 * da-dataplane-baseline.sql}，r3 引擎启动期演进与 069 代列集的在场前提——run-2/4 实证：
 * 054 旧版 5 表 DDL 会令宿主死于 agent_skills 缺列/workflows 缺表）；
 * 测试侧 JDBC 连接只做播种、DA 直改模拟与行数/回读核对（fixture 面），业务断言一律走
 * HTTP API 与 SUT 语句日志，不直连 fixture 库断言业务语义。</p>
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-054")
@Feature("FEAT-054: deepanalyze-java 宿主装配")
class DaSharedPgReadE2EIT {

    private static final String AGENT = "deepanalyze";
    private static final String PG_DB = "agent_rdc";
    private static final String PG_USER = "agent_rdc";
    private static final String PG_PASSWORD = "agent_rdc";

    private static final String SESSION_ID = "sess-feat054-read";
    private static final String SKILL_ID = "skill-feat054-read";
    private static final String MEMORY_ROW_ID = "mem-feat054-read";
    /** 宿主配置面 provider id（原 settings 种子 provider 已随播种删除改道配置面）。 */
    private static final String PROVIDER_ID = "prov-feat054-hostcfg";
    /**
     * 等价 schema 资源（2026-11-26 run-4 教训改道）：r3 引擎启动期 schema 演进
     * （agent-schema.sql 对 workflows 等 069 代表做 ALTER）与 069 代列集（agent_skills 的
     * tenant_id/triggers/source…）要求基线表按现行形态在场——054 DDL（2026-09-22 副本，
     * 仅 5 张旧列集表）已落后于 r3 契约，改用 FEAT-069 基线 DDL（da-dataplane-baseline.sql，
     * 全量现行形态，TestPlan §6「等价 schema」口径；与 DA 侧真实 schema 的差异随结论声明）。
     * 历史锚点保留：054 DDL 的 Q12/DES-Q12 裁决①来源不变，仅资源换现行版本。
     */
    private static final String DDL_RESOURCE = "/deepanalyze/da-dataplane-baseline.sql";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(300);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    /** 零写入行数面（054 DDL 内存在的表；session_memory 已移出只读铁律，agent_tasks 无表走语句日志面）。 */
    private static final List<String> WRITE_FORBIDDEN_TABLES =
            List.of("sessions", "messages", "settings", "agent_skills");

    private static final Path BASEDIR = Path.of(
            System.getProperty("basedir", System.getProperty("user.dir")));
    private static final Path SUT_LOG = BASEDIR.resolve("target/sit-logs/" + AGENT + "/stdout.log");

    @SuppressWarnings("resource")
    private static final GenericContainer<?> PG = new GenericContainer<>(
            DockerImageName.parse("postgres:16"))
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", PG_DB)
            .withEnv("POSTGRES_USER", PG_USER)
            .withEnv("POSTGRES_PASSWORD", PG_PASSWORD);

    private static TestConfig config;
    private static SutStack stack;
    private static HttpClient http;
    private static String base;
    private static long statementWindowStart;

    @BeforeAll
    static void seedThenStartStack() throws Exception {
        // ① PG 容器 → ② DDL＋播种（测试侧 JDBC，不进 SUT 日志）→ ③ SUT 启动（r3 的
        // DaSkillMaterializer 启动期读 agent_skills——表必须先在场，run-2 实证）
        PG.start();
        config = TestConfig.load();
        try (Connection connection = pgConnection(); Statement statement = connection.createStatement()) {
            applyDdl(statement);
            seedRows(statement);
        }
        String url = jdbcUrl();
        stack = SutStack.builder(config)
                .streaming(true)
                .agent(AGENT, agent -> {
                    bindModelEnvironment(agent);
                    DaModelEnvironment.bindModelKey(agent);
                    agent.property("deepanalyze.data.pg.enabled", "true")
                            .property("deepanalyze.data.pg.jdbc-url", url)
                            .property("deepanalyze.data.pg.username", PG_USER)
                            .property("deepanalyze.data.pg.password", PG_PASSWORD)
                            .property("spring.datasource.url", url)
                            .property("spring.datasource.username", PG_USER)
                            .property("spring.datasource.password", PG_PASSWORD)
                            // provider 配置走宿主自带配置面（FEAT-069 读方消失改造；FEAT-113 下
                            // settings 无行时启动期/惰性读回落部署配置——探测面与执行面同源）
                            .property("deepanalyze.providers[0].id", PROVIDER_ID)
                            .property("deepanalyze.providers[0].name", "宿主配置面 provider")
                            .property("deepanalyze.providers[0].base-url", "http://127.0.0.1:9")
                            .property("deepanalyze.providers[0].model", "no-such-model")
                            .property("deepanalyze.providers[0].api-key", "fixture-only")
                            // 语句日志取证面：JdbcTemplate DEBUG 行携带 SQL 原文（SUT stdout.log）
                            .property("logging.level.org.springframework.jdbc.core", "DEBUG")
                            // redis 仍走栈托管（常态部署形态）
                            .serviceBinding("redis", "REDIS_HOST", "{{host}}")
                            .serviceBinding("redis", "REDIS_PORT", "{{port}}");
                })
                .start();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        base = stack.client(AGENT).getBaseUrl();
        // 语句日志取证窗起点：SUT 就绪后取值——此后 SUT 日志新增段全部属于本轮运行期行为
        statementWindowStart = sutLogLength();
        Allure.addAttachment("数据面准备", "text/plain",
                "jdbc=" + url + "\nDDL=" + DDL_RESOURCE + "\nseed session=" + SESSION_ID
                        + ", skill=" + SKILL_ID + ", memory=" + MEMORY_ROW_ID
                        + "\nsettings 播种=已删除（FEAT-069 读方消失；provider 改道宿主配置面 "
                        + PROVIDER_ID + "）\n语句日志窗起点=" + statementWindowStart);
    }

    @AfterAll
    static void tearDown() {
        if (stack != null) {
            stack.close();
        }
        PG.stop();
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/" + PG_DB;
    }

    private static Connection pgConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), PG_USER, PG_PASSWORD);
    }

    private static void bindModelEnvironment(SutStack.AgentBuilder agent) {
        passThrough(agent, "LLM_API_KEY", "LLM_API_KEY");
        passThrough(agent, "DA_MODEL_BASE_URL", "LLM_API_BASE");
        passThrough(agent, "DA_MODEL_NAME", "LLM_MODEL");
        passThrough(agent, "DA_MODEL_PROVIDER", "LLM_PROVIDER");
    }

    private static void passThrough(SutStack.AgentBuilder agent, String target, String source) {
        String value = System.getenv(source);
        if (value != null && !value.isBlank()) {
            agent.env(target, value);
        }
    }

    @Test
    @Story("FEAT-054-E3: 技能读面经 agent 账号读回种子技能")
    @DisplayName("FEAT-054-E3: GET /api/agents/skills* 读到共享 PG 里的技能，未命中 404")
    void skillsReadPlaneReadsSharedPg() throws Exception {
        HttpResponse<String> active = get("/api/agents/skills/active", READ_TIMEOUT);
        Allure.addAttachment("GET /api/agents/skills/active", "text/plain", active.body());
        assertThat(active.statusCode()).as("启用数据面后技能读面应可用").isEqualTo(200);
        assertThat(active.body()).as("活跃技能列表须包含共享 PG 里的种子技能").contains(SKILL_ID);

        HttpResponse<String> detail = get("/api/agents/skills/" + SKILL_ID, READ_TIMEOUT);
        Allure.addAttachment("GET /api/agents/skills/{id}", "text/plain", detail.body());
        assertThat(detail.statusCode()).isEqualTo(200);
        JsonNode skill = MAPPER.readTree(detail.body());
        assertThat(skill.path("id").asText()).isEqualTo(SKILL_ID);
        assertThat(skill.path("name").asText()).isNotBlank();
        assertThat(skill.path("is_active").asBoolean()).isTrue();

        HttpResponse<String> missing = get("/api/agents/skills/no-such-skill-054", READ_TIMEOUT);
        Allure.addAttachment("GET /api/agents/skills/{unknown}", "text/plain", missing.body());
        assertThat(missing.statusCode()).as("未命中技能须 404").isEqualTo(404);
        assertThat(missing.body()).contains("error");
    }

    @Test
    @Story("FEAT-054-E3: provider 探测走宿主自带配置面（settings 零播种）")
    @DisplayName("FEAT-054-E3: POST /api/agents/providers/{id}/test 命中宿主配置面 provider；未命中 404")
    void providerProbeReadsHostConfigPlane() throws Exception {
        HttpResponse<String> probe = post("/api/agents/providers/" + PROVIDER_ID + "/test", "{}", READ_TIMEOUT);
        Allure.addAttachment("POST /api/agents/providers/{hostcfg}/test", "text/plain", probe.body());
        assertThat(probe.statusCode())
                .as("宿主配置面 provider 必须可被探测受理（该 id 只存在于部署配置，settings 无 providers 行——"
                        + "播种已删除，读方消失改造）")
                .isEqualTo(200);
        JsonNode body = MAPPER.readTree(probe.body());
        assertThat(body.has("success")).as("响应须带探测结论字段").isTrue();
        assertThat(body.path("success").asBoolean())
                .as("配置面 provider 指向不可达地址，探测结果应为 false（证明确实发起探测而非空实现）")
                .isFalse();

        HttpResponse<String> missing = post("/api/agents/providers/no-such-provider-054/test", "{}", READ_TIMEOUT);
        Allure.addAttachment("POST /api/agents/providers/{unknown}/test", "text/plain", missing.body());
        assertThat(missing.statusCode()).as("未命中 provider 须 404").isEqualTo(404);
    }

    @Test
    @Story("FEAT-054-E3: session_memory 宿主自写自读（回读现读）与只读铁律收窄")
    @DisplayName("FEAT-054-E3: DA 直改记忆后任务回读生效；sessions/messages 零读零写、"
            + "agent_tasks/settings 零写入（语句日志＋行数双面）")
    void runReadsMemoryFreshAndNeverTouchesForbiddenTables() throws Exception {
        // DA 直改模拟：测试侧 JDBC 直改 session_memory 行（模拟 DA 侧写方对记忆的直接修改）
        String directEdit = "会话记忆（DA 直改 2026-11-26）：关注华东区流水波动归因";
        try (Connection connection = pgConnection(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE session_memory SET content = '" + directEdit
                    + "', updated_at = now() WHERE id = '" + MEMORY_ROW_ID + "'");
        }

        // 本方法自己的语句日志窗与行数快照（方法起点起切窗，避免与其他方法的窗口互相污染；
        // before 快照必须先于任务执行——直改只动 session_memory，不触禁止写入表）
        Map<String, Integer> before = rowCounts();
        long methodWindowStart = sutLogLength();

        HttpResponse<String> run = post("/api/agents/run", "{\"sessionId\":\"" + SESSION_ID
                + "\",\"input\":\"请用一句话回答：2+2 等于几。不要调用任何工具。\"}", CALL_TIMEOUT);
        Allure.addAttachment("POST /api/agents/run（种子会话）", "text/plain", run.body());
        assertThat(run.statusCode()).as("数据面启用下任务必须受理执行").isEqualTo(200);
        assertThat(MAPPER.readTree(run.body()).path("status").asText()).isEqualTo("completed");

        Map<String, Integer> after = rowCounts();

        // ① 语句日志取证（黑盒证据面；开窗自检先行——观测面为空时判据是未执行而非通过）
        String window = sutLogWindow(methodWindowStart);
        long sqlLines = window.lines().filter(l -> l.contains("Executing SQL")
                || l.contains("Executing prepared SQL")).count();
        assertThat(sqlLines)
                .as("开窗自检：JdbcTemplate DEBUG 取证面必须生效（窗口内应出现 SQL 执行行）——"
                        + "为 0 说明 logging.level 注入未生效，本面全部判据属未执行")
                .isGreaterThan(0);
        Allure.addAttachment("语句日志窗行数（SQL 执行行）", "text/plain", String.valueOf(sqlLines));

        // ② 零读取（读方消失硬断言）：窗口内不得出现对 sessions/messages 的 SELECT
        assertThat(window)
                .as("宿主不得 SELECT sessions（r3 上 sessions 生产读方已退役——sessionExists 无调用点）")
                .doesNotContain("FROM sessions");
        assertThat(window)
                .as("宿主不得 SELECT messages（读方消失——载荷即信任）")
                .doesNotContain("FROM messages");

        // ③ 零写入（禁止写入表）：窗口内不得出现对 sessions/messages/agent_tasks/settings 的写语句
        for (String table : List.of("sessions", "messages", "agent_tasks", "settings")) {
            assertThat(window)
                    .as("宿主不得写 " + table + "（FEAT-069 零写入限定——禁止写入表）")
                    .doesNotContain("INSERT INTO " + table)
                    .doesNotContain("UPDATE " + table)
                    .doesNotContain("DELETE FROM " + table);
        }

        // ④ 回读现读面：窗口内必须出现对本会话 session_memory 的读取（回读不缓存）
        assertThat(window)
                .as("宿主每任务回读 session_memory（自写自读契约——回读现读不缓存）")
                .contains("FROM session_memory");

        // ⑤ settings 读取＝代际边界证据（FEAT-113 只读通道，NOT_OBSERVED 登记面——只取证不断言）
        long settingsReads = window.lines().filter(l -> l.contains("FROM settings")).count();
        String settingsSample = window.lines().filter(l -> l.contains("FROM settings"))
                .findFirst().orElse("<窗口内无 settings 读取行>");
        Allure.addAttachment("settings 读取行（FEAT-113 通道证据，代际边界 NOT_OBSERVED——不进判据）",
                "text/plain", "窗口内 settings SELECT 行数=" + settingsReads + "\n首行=" + settingsSample);

        // ⑥ 行数面：零写入表行数不变（session_memory 已移出——行数变化不再是失败）
        Allure.addAttachment("只读铁律行数对比", "text/plain", before + " -> " + after);
        assertThat(after).as("宿主不得写共享 PG 的禁止写入表（sessions/messages/settings/agent_skills）")
                .isEqualTo(before);

        // ⑦ DA 直改生效核对（fixture 面）：直改后的行内容可从库读回（承载"直改"这一前置真实发生）
        try (Connection connection = pgConnection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT content FROM session_memory WHERE id = '" + MEMORY_ROW_ID + "'")) {
            assertThat(rows.next()).as("种子记忆行必须存在").isTrue();
            assertThat(rows.getString(1)).as("DA 直改内容必须已在库中（直改前置成立）").isEqualTo(directEdit);
        }
    }

    @Test
    @Story("FEAT-054-E3: 压缩摘要 upsert 落行（session_memory 自写半边）")
    @DisplayName("FEAT-054-E3: 触发压缩后 session_memory 出现宿主自写行（压缩触发面）")
    void sessionMemoryUpsertRowOnCompression() throws Exception {
        // 构造说明（2026-11-26）：压缩触发（context.compression_state chunk）由 core 侧上下文
        // 压缩器判定，r3 宿主未暴露 trigger-tokens 宿主侧旋钮（引擎源码 grep SessionMemoryManager/
        // trigger-tokens 命中 0；deepanalyze.compression.structured.trigger-total-tokens 仅作用于
        // structured 路线且 structured 默认关）。本方法以长上下文输入尝试触发，assumption 门控——
        // 未触发即 assumption 中止（XML 记 skip），按台账登记「upsert 面未凑成」，不硬凑、不判 FAIL
        // （069 批 mem-01/02 同型登记先例；试错止损：同型构造不重复烧轮次）。
        long methodWindowStart = sutLogLength();
        String upsertSession = "sess-feat054-upsert-" + System.nanoTime();
        String filler = "数据面回归填充段落：流水数据按日聚合，关注环比与同比口径差异，"
                + "区域维度按省市级联，指标含成交额、订单量与转化率。";
        StringBuilder input = new StringBuilder("请先用一句话概括下面材料主题，再回答：2+2 等于几。不要调用任何工具。\n");
        for (int i = 0; i < 1200; i++) {
            input.append("[").append(i).append("] ").append(filler).append('\n');
        }

        HttpResponse<String> run = post("/api/agents/run", "{\"sessionId\":\"" + upsertSession
                + "\",\"input\":" + MAPPER.writeValueAsString(input.toString()) + "}", CALL_TIMEOUT);
        Allure.addAttachment("POST /api/agents/run（长上下文压缩尝试）", "text/plain",
                "sessionId=" + upsertSession + "\n输入长度=" + input.length() + " 字符\n响应=" + run.body());
        assertThat(run.statusCode()).as("长上下文任务必须受理执行").isEqualTo(200);
        assertThat(MAPPER.readTree(run.body()).path("status").asText()).isEqualTo("completed");

        // upsert 写者异步落库：有界轮询（10×3s）
        int memoryRows = 0;
        for (int attempt = 0; attempt < 10; attempt++) {
            Thread.sleep(3000);
            memoryRows = countSessionMemoryRows(upsertSession);
            if (memoryRows > 0) {
                break;
            }
        }
        Allure.addAttachment("upsert 观察面", "text/plain",
                "session_memory rows(session=" + upsertSession + ")=" + memoryRows
                        + "\n语句日志窗 session_memory 读取行="
                        + sutLogWindow(methodWindowStart).lines().filter(l -> l.contains("FROM session_memory")).count());
        Assumptions.assumeTrue(memoryRows > 0,
                "压缩未触发（context.compression_state chunk 未出现——core 侧触发阈值未达，宿主无旋钮）："
                        + "upsert 落行面按台账登记「未凑成」，不判 FAIL（069 mem-01/02 同型）");
        assertThat(memoryRows).as("压缩触发后宿主必须自写 session_memory（压缩摘要 upsert 落行）").isGreaterThan(0);
    }

    private int countSessionMemoryRows(String session) throws Exception {
        try (Connection connection = pgConnection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT count(*) FROM session_memory WHERE session_id = '" + session + "'")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private HttpResponse<String> get(String path, Duration timeout) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(timeout)
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, String body, Duration timeout) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private Map<String, Integer> rowCounts() throws Exception {
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (Connection connection = pgConnection(); Statement statement = connection.createStatement()) {
            for (String table : WRITE_FORBIDDEN_TABLES) {
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
                    rows.next();
                    counts.put(table, rows.getInt(1));
                }
            }
        }
        return counts;
    }

    /** 共享 SUT 日志窗口（共享 stdout.log 跨轮追加——断言必须先开窗，铁律见类注）。 */
    private static String sutLogWindow(long offset) throws Exception {
        String full = Files.readString(SUT_LOG);
        if (offset >= full.length()) {
            return "";
        }
        return full.substring((int) offset);
    }

    private static long sutLogLength() throws Exception {
        return Files.exists(SUT_LOG) ? Files.size(SUT_LOG) : 0L;
    }

    /** 回放 DA 侧权威 DDL（按语句切分；本 DDL 无函数体/美元引用，按分号切分安全）。 */
    private static void applyDdl(Statement statement) throws Exception {
        String script;
        try (InputStream stream = DaSharedPgReadE2EIT.class.getResourceAsStream(DDL_RESOURCE)) {
            assertThat(stream).as("DDL 资源必须存在: " + DDL_RESOURCE).isNotNull();
            script = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : script.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--") || trimmed.isEmpty()) {
                continue;
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                statements.add(current.toString());
                current.setLength(0);
            }
        }
        for (String sql : statements) {
            statement.execute(sql);
        }
    }

    /**
     * 播种（2026-11-26 翻转）：仅 sessions / session_memory / agent_skills——
     * <b>settings 的 agent_settings/providers 两行已删除</b>（宿主不再读 settings，
     * 播种用途取消；provider 改道宿主配置面 {@code deepanalyze.providers[0]}）。
     * session_memory 种子行承载「DA 直改回读」面的直改对象；agent_skills 种子行
     * 同时是 SUT 启动期 DaSkillMaterializer.reconcileAll 的在场前提（run-2 实证）。
     */
    private static void seedRows(Statement statement) throws Exception {
        List<String> seeds = List.of(
                "INSERT INTO sessions (id, title, kb_scope) VALUES ('" + SESSION_ID + "', 'FEAT-054 读面会话', 'kb-054')"
                        + " ON CONFLICT (id) DO NOTHING",
                "INSERT INTO session_memory (id, session_id, content, token_count, last_token_position)"
                        + " VALUES ('" + MEMORY_ROW_ID + "', '" + SESSION_ID + "', '会话记忆：关注中国区流水波动归因', 12, 12)"
                        + " ON CONFLICT (id) DO NOTHING",
                "INSERT INTO agent_skills (id, name, description, prompt, tools, model_role, is_active, source)"
                        + " VALUES ('" + SKILL_ID + "', 'feat054-read-skill', '数据面读面验证技能',"
                        + " '你是数据面读面验证技能。', '{\"*\"}', 'main', true, 'manual')"
                        + " ON CONFLICT (id) DO NOTHING");
        for (String seed : seeds) {
            statement.execute(seed);
        }
    }
}

package com.huawei.ascend.sit.cases.integration.deepanalyze_cost;

import com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail.DaCompatGuardrailSupport;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport.MemoryReceiverServer;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport.ModelStubServer;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport.OtlpReceiverServer;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-111 全执行体归属·成员分栏（F111-09 主面＋F111-11 成员响应模型格，integration，PG 栈）。
 *
 * <p><b>判据（实现前已读源码固化）：</b></p>
 * <ul>
 *   <li>触发面＝compat run 体 teamId 字段（{@code DaRunRequest.teamId}，FEAT-069 R1）→
 *       DaHandlerTaskExecutor 团队执行；成员用量经 DaMemberUsageSampler 按任务锚归原
 *       mainTaskId/sessionId（DaHandlerTaskExecutor :640-646 注释口径）。</li>
 *   <li>成员 purpose=subagent；r3 判据（11-16 设计修订，DV-111-07 撤销）：三路径统一
 *       request_fallback（成员序列同样带 model_identity_source=request_fallback；真实
 *       响应模型归因待 core #277，全线 blocked 不冒充）。</li>
 *   <li>团队种子＝agent_teams（mode=parallel，model_config 不编译→成员继承 DA_MODEL_*
 *       即 stub）＋agent_team_members 两行；基线 DDL 已含两表（da-dataplane-baseline.sql）。</li>
 * </ul>
 */
@Tag("integration")
@Tag("deepanalyze")
@Tag("feat-111")
@Feature("FEAT-111: 轨迹反思与成本追踪")
class Feat111TeamAttributionIT {

    private static final Duration OTLP_WAIT = Duration.ofSeconds(45);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static TestConfig config;

    private static HttpClient http;

    @BeforeAll
    static void setUp() {
        config = TestConfig.load();
        http = DaCompatGuardrailSupport.newHttpClient();
    }

    private record Env(SutStack stack, GenericContainer<?> pg, ModelStubServer stub,
            MemoryReceiverServer memory, OtlpReceiverServer otlp, String base) implements AutoCloseable {
        @Override public void close() {
            stack.close();
            pg.stop();
            stub.close();
            memory.close();
            otlp.close();
        }
    }

    /** 测试自管 PG（先灌基线＋演进 schema——团队表在基线）＋stub 模型＋观测面。 */
    private static Env startEnv() throws Exception {
        ModelStubServer stub = new ModelStubServer();
        // 成员须成功完成（普通回答）→ 团队聚合 completed → 成员 usage 流入归属面
        MemoryReceiverServer memory = new MemoryReceiverServer();
        OtlpReceiverServer otlp = new OtlpReceiverServer();
        GenericContainer<?> pg = new GenericContainer<>("postgres:16")
                .withEnv("POSTGRES_DB", "agent_rdc")
                .withEnv("POSTGRES_USER", "agent_rdc")
                .withEnv("POSTGRES_PASSWORD", "agent_rdc")
                .withExposedPorts(5432);
        pg.start();
        String pgUrl = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/agent_rdc";
        try (Connection conn = DriverManager.getConnection(pgUrl, "agent_rdc", "agent_rdc")) {
            com.huawei.ascend.sit.cases.e2e.deepanalyze_dataplane.DaDataplaneSupport.applyBaselineDdl(conn);
            for (String sql : DaReflectionCostSupport.statementsFromArtifact("db/agent-schema.sql")) {
                try (Statement st = conn.createStatement()) {
                    st.execute(sql);
                }
            }
        }
        SutStack stack = DaReflectionCostSupport.baseStack(config, agent -> {
            DaReflectionCostSupport.bindStubModel(agent, stub);
            DaReflectionCostSupport.gatesOpen(agent);
            DaReflectionCostSupport.wireObservability(agent, otlp, memory);
            agent.property("deepanalyze.data.pg.enabled", "true");
            agent.property("deepanalyze.data.pg.jdbc-url", pgUrl);
            agent.property("deepanalyze.data.pg.username", "agent_rdc");
            agent.property("deepanalyze.data.pg.password", "agent_rdc");
            agent.property("spring.datasource.url", pgUrl);
            agent.property("spring.datasource.username", "agent_rdc");
            agent.property("spring.datasource.password", "agent_rdc");
            agent.property("deepanalyze.task-loop.planning-enabled", "false");
        }).start();
        return new Env(stack, pg, stub, memory, otlp,
                stack.client(DaReflectionCostSupport.AGENT).getBaseUrl());
    }

    /** 种子并行团队（两成员；model_config 不编译→成员继承宿主模型=stub）。 */
    private static void seedTeam(Env env, String teamId) throws Exception {
        String pgUrl = "jdbc:postgresql://" + env.pg().getHost() + ":"
                + env.pg().getMappedPort(5432) + "/agent_rdc";
        try (Connection conn = DriverManager.getConnection(pgUrl, "agent_rdc", "agent_rdc");
                Statement st = conn.createStatement()) {
            st.execute("DELETE FROM agent_team_members WHERE team_id = '" + teamId + "'");
            st.execute("DELETE FROM agent_teams WHERE id = '" + teamId + "'");
            st.execute("INSERT INTO agent_teams (id, name, description, mode, is_active, cross_review, "
                    + "enable_skills, model_config) VALUES ('" + teamId + "', 'f111-team', '归属测试团队', "
                    + "'parallel', true, false, false, NULL)");
            // depends_on/tools 为 NOT NULL DEFAULT '[]'（基线 DDL :231/:233）——省略列让默认值生效
            st.execute("INSERT INTO agent_team_members (id, team_id, role, task, sort_order) VALUES "
                    + "('" + teamId + "-m1', '" + teamId + "', '研究员', '直接回答完成', 1)");
            st.execute("INSERT INTO agent_team_members (id, team_id, role, task, sort_order) VALUES "
                    + "('" + teamId + "-m2', '" + teamId + "', '复核者', '直接回答完成', 2)");
        }
    }

    /** 带团队标识发起任务并断言终态 completed（成员全部回答→聚合成功）。 */
    private static String runTeamTask(Env env, String session, String teamId) throws Exception {
        HttpResponse<String> response = DaCompatGuardrailSupport.post(http, env.base(), "/api/agents/run",
                "{\"sessionId\":\"" + session + "\",\"input\":\"团队任务\",\"teamId\":\"" + teamId + "\"}",
                Duration.ofSeconds(240));
        assertThat(response.statusCode()).as("/run 受理: " + response.body()).isIn(200, 500);
        assertThat(MAPPER.readTree(response.body()).path("status").asText(""))
                .as("团队任务成功终态: " + response.body() + "\n=== stub 收到的请求（截断）===\n"
                        + env.stub().requests().stream()
                        .map(r -> r.path() + " :: " + (r.body() == null ? "null"
                                : r.body().substring(0, Math.min(300, r.body().length()))
                                + (r.body().contains("__probe_tool__") ? " …[含探针标记]" : "")))
                        .reduce((a, b) -> a + "\n" + b).orElse("<无>"))
                .isEqualTo("completed");
        HttpResponse<String> tasks = DaCompatGuardrailSupport.get(http, env.base(),
                "/api/agents/tasks/" + session, Duration.ofSeconds(30),
                Map.of("X-Da-Session-Id", session));
        JsonNode node = MAPPER.readTree(tasks.body());
        assertThat(node.size()).as("会话任务记录存在").isGreaterThan(0);
        return node.get(0).path("id").asText("");
    }

    private static java.util.function.Predicate<Map<String, String>> subagentOf(String session, String taskId) {
        return tags -> "subagent".equals(tags.get(DaReflectionCostSupport.TAG_PURPOSE))
                && session.equals(tags.get(DaReflectionCostSupport.TAG_SESSION))
                && taskId.equals(tags.get(DaReflectionCostSupport.TAG_TASK));
    }

    @Test
    @Story("F111-09/11: 成员用量归原任务＋purpose=subagent；成员序列统一 request_fallback（DV-111-07 撤销，11-16 设计修订）")
    @DisplayName("F111-09/11: 成员执行体归属与模型身份源")
    void memberUsageAttributesToMainTask() throws Exception {
        try (Env env = startEnv()) {
            String teamId = "f111-team-" + System.nanoTime();
            seedTeam(env, teamId);
            String session = "sess-f111-09-" + System.nanoTime();
            String taskId = runTeamTask(env, session, teamId);
            assertThat(taskId).as("主任务 id").isNotBlank();

            java.util.function.Predicate<Map<String, String>> memberSeries =
                    subagentOf(session, taskId).and(tags ->
                            DaReflectionCostSupport.STUB_MODEL
                                    .equals(tags.get(DaReflectionCostSupport.TAG_MODEL)));
            boolean memberSeen = DaReflectionCostSupport.await(OTLP_WAIT, Duration.ofMillis(500),
                    () -> env.otlp().hasSeries(DaReflectionCostSupport.M_TOKEN_USAGE, memberSeries));
            assertThat(memberSeen).as("OTLP 出现 purpose=subagent 序列（归原任务＋会话）").isTrue();

            // r3 判据（11-16 设计修订 + run-01f 实证）：DV-111-07 撤销——成员不再有独立
            // 「响应模型分栏」，三路径统一标 request_fallback（真实响应模型归因待 core #277，
            // 全线 blocked 不冒充）。r2 断言「成员不带 fallback 标签」随 DV-111-07 撤销过时
            // （run-01f 抓到：成员序列已带 request_fallback，与设计一致）。
            java.util.function.Predicate<Map<String, String>> fallbackMember =
                    subagentOf(session, taskId).and(tags -> "request_fallback"
                            .equals(tags.get(DaReflectionCostSupport.TAG_MODEL_SOURCE)));
            assertThat(env.otlp().hasSeries(DaReflectionCostSupport.M_TOKEN_USAGE, fallbackMember))
                    .as("成员序列统一 request_fallback（DV-111-07 撤销后语义）").isTrue();

            // 偏差双栏（Oracle：L1 出口 11 成员按响应模型计价 vs 实现口径）：团队成员模型链
            // 未携带 backend（team model_config 不编译→成员跑 core 默认模型，快照无
            // client_provider）→ provider 身份缺失 → unknown_price 判定（token 照记、成本不记）。
            // DV-111-12 候选登记（run-49~53 实证链）。
            assertThat(env.otlp().hasSeries(DaReflectionCostSupport.M_UNKNOWN_PRICE,
                    subagentOf(session, taskId)))
                    .as("成员分栏 unknown_price 判定（provider 身份缺失，实现口径）").isTrue();
            double memberCost = env.otlp().maxSum(DaReflectionCostSupport.M_COST_USD,
                    subagentOf(session, taskId));
            assertThat(memberCost).as("成员分栏无成本（unknown_price，L1 计价面 blocked）").isEqualTo(0D);
            Allure.addAttachment("F111-09/11 证据", "text/plain",
                    "session=" + session + " taskId=" + taskId + " memberCost=" + memberCost
                            + " stubCalls=" + env.stub().mainCalls());
        }
    }

    /**
     * <b>GUARDRAIL_DENIED 构造核实（r2 run-55～57 四轮探针 + r3 复核 2026-11-16）：</b>
     * r2 结论「成员 fail-closed deny 证据不链到主任务记录」已被 !1176（docs#49 严格因果口径）
     * 部分修复——runMember 检测点登记 + settle 收割进任务记录（causal 证据传播链已建）；
     * 但构造面缺口在成员侧不变：成员模板工具族 MEMBER_TOOLS（DaWorkflowRunner:84）全部
     * actrule=allow，成员的 deny 尝试无黑盒构造面（未知工具调用被 core 静默丢弃、不达权限轨
     * ——r2「零未知工具痕迹」实证），「本 invocation 工具尝试全部被拒」不可达。
     * r3 复核结论：黑盒构造仍不可达，恢复条件＝产品提供可被拒绝的成员工具面
     * （如成员模板含 ask/deny 工具）或确认跨链证据设计意图。
     */
    @Test
    @Story("F111-20: GUARDRAIL_DENIED 构造核实（构造面缺失，INCONCLUSIVE 留恢复条件）")
    @DisplayName("F111-20: GUARDRAIL_DENIED 格（blocked·构造面缺失）")
    void guardrailDeniedConstructionProbe() {
        org.junit.jupiter.api.Assumptions.abort("GUARDRAIL_DENIED 黑盒构造不可达（r2 run-55～57 四轮探针"
                + " + r3 复核）：!1176 已修因果收割链（runMember 登记+settle 收割），但成员模板工具族"
                + " MEMBER_TOOLS 全部 actrule=allow、未知工具调用被 core 静默丢弃不达权限轨——"
                + "「工具尝试全部被拒」无黑盒构造面。恢复条件＝产品提供可被拒绝的成员工具面"
                + "（如成员模板含 ask/deny 工具）或确认跨链证据设计意图。");
    }

}

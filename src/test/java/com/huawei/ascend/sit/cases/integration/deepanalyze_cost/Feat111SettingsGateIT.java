package com.huawei.ascend.sit.cases.integration.deepanalyze_cost;

import com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail.DaCompatGuardrailSupport;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport.MemoryReceiverServer;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport.ModelStubServer;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport.OtlpReceiverServer;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport.ReflectionRecord;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-111 settings 读面负向验证（F111-17，integration，PG 栈；#44 裁决已落地——Oracle 单栏化）。
 * 裁决口径：settings 读面/自进化门/自进化 YAML 键已全部移除，恢复 L1 双门
 * （reflection.enabled＋单租户/身份）。本类负向验证：settings 表存在 self_evolution_config
 * 键（值 enabled=false——若读面残留，门将关闭）时反思行为不受 settings 影响：
 * 双门全开下任务失败仍产生 succeeded 处置与记忆写入，即证「不读 settings」。
 */
@Tag("integration")
@Tag("deepanalyze")
@Tag("feat-111")
@Feature("FEAT-111: 轨迹反思与成本追踪")
class Feat111SettingsGateIT {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static TestConfig config;
    private static HttpClient http;

    @org.junit.jupiter.api.BeforeAll
    static void setUp() {
        config = TestConfig.load();
        http = DaCompatGuardrailSupport.newHttpClient();
    }

    private record Env(SutStack stack, org.testcontainers.containers.GenericContainer<?> pg,
            ModelStubServer stub, MemoryReceiverServer memory, OtlpReceiverServer otlp,
            String base) implements AutoCloseable {
        @Override public void close() {
            stack.close();
            pg.stop();
            stub.close();
            memory.close();
            otlp.close();
        }
    }

    /** 测试自管 PG（先灌 DDL 再起 SUT——DaSkillMaterializer 启动即查 agent_skills，
     * run-27 实证；时序沿 DaDataplaneDegradationE2EIT 先例）＋数据面栈＋三门全开＋stub。 */
    private static Env startEnv() throws Exception {
        ModelStubServer stub = new ModelStubServer();
        DaReflectionCostSupport.credentialFailure(stub);
        stub.reflectionCode("TOOL_INPUT_INVALID");
        MemoryReceiverServer memory = new MemoryReceiverServer();
        OtlpReceiverServer otlp = new OtlpReceiverServer();
        org.testcontainers.containers.GenericContainer<?> pg =
                new org.testcontainers.containers.GenericContainer<>("postgres:16")
                        .withEnv("POSTGRES_DB", "agent_rdc")
                        .withEnv("POSTGRES_USER", "agent_rdc")
                        .withEnv("POSTGRES_PASSWORD", "agent_rdc")
                        .withExposedPorts(5432);
        pg.start();
        String pgUrl = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/agent_rdc";
        try (Connection conn = DriverManager.getConnection(pgUrl, "agent_rdc", "agent_rdc")) {
            // 基线 DDL（dataplane 支撑公开方法）＋ SUT 自带演进 schema（幂等；本批制品内
            // db/agent-schema.sql）——agent_skills/workflows 等表在基线，settings 在演进脚本
            // （run-27：DaSkillMaterializer 启动即查；run-28：workflows 属基线）
            com.huawei.ascend.sit.cases.e2e.deepanalyze_dataplane.DaDataplaneSupport
                    .applyBaselineDdl(conn);
            for (String sql : DaReflectionCostSupport.statementsFromArtifact("db/agent-schema.sql")) {
                try (java.sql.Statement st = conn.createStatement()) {
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
        }).start();
        return new Env(stack, pg, stub, memory, otlp,
                stack.client(DaReflectionCostSupport.AGENT).getBaseUrl());
    }

    /** 种子/清除 settings 行（setup 态操作走 DB 客户端，断言仍全黑盒）。 */
    private static void execSql(Env env, String sql) throws Exception {
        String pgUrl = "jdbc:postgresql://" + env.pg().getHost() + ":"
                + env.pg().getMappedPort(5432) + "/agent_rdc";
        try (Connection c = DriverManager.getConnection(pgUrl, "agent_rdc", "agent_rdc");
                java.sql.Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static void runBusinessFailure(Env env, String session) throws Exception {
        HttpResponse<String> response = DaCompatGuardrailSupport.post(http, env.base(), "/api/agents/run",
                "{\"sessionId\":\"" + session + "\",\"input\":\"触发模型凭据失败\"}", Duration.ofSeconds(240));
        assertThat(response.statusCode()).as("/run 受理").isIn(200, 500);
        assertThat(MAPPER.readTree(response.body()).path("status").asText(""))
                .as("凭据失败终态（stub 401 构造）").isEqualTo("failed");
    }

    private static List<ReflectionRecord> awaitRecords(String session, Duration timeout) {
        List<ReflectionRecord> found = new java.util.concurrent.CopyOnWriteArrayList<>();
        DaReflectionCostSupport.await(timeout, Duration.ofMillis(300), () -> {
            found.clear();
            found.addAll(DaReflectionCostSupport.recordsForSession(session));
            return !found.isEmpty();
        });
        return List.copyOf(found);
    }

    @Test
    @Story("F111-17: settings 键在场（enabled=false）时反思行为不受 settings 影响——读面已移除的负向验证")
    @DisplayName("F111-17: settings 读面移除（#44 裁决，Oracle 单栏）")
    void settingsIgnoredAfterRuling() throws Exception {
        try (Env env = startEnv()) {
            // 种子强信号：self_evolution_config enabled=false——若读面残留，门将关闭（disabled）
            execSql(env, "DELETE FROM settings WHERE key = 'self_evolution_config'");
            execSql(env, "INSERT INTO settings (key, value, updated_at) VALUES "
                    + "('self_evolution_config', '{\"enabled\": false}', now())");
            String session = "sess-f111-17-" + System.nanoTime();
            runBusinessFailure(env, session);
            List<ReflectionRecord> records = awaitRecords(session, WAIT);
            assertThat(records).as("唯一处置记录（settings 在场不改变行为）").hasSize(1);
            assertThat(records.get(0).outcome())
                    .as("双门全开下照常 succeeded——即证不读 settings（#44 裁决单栏）")
                    .isEqualTo("succeeded");
            assertThat(records.get(0).memoryId()).as("记忆写入不受 settings 影响").isNotBlank();
            assertThat(env.memory().writeCount()).as("写面恰一次").isOne();
            Allure.addAttachment("F111-17 证据", "text/plain",
                    "session=" + session + " record=" + records.get(0)
                            + "（#44 裁决：settings 读面/自进化门/YAML 键移除，L1 双门恢复）");
        }
    }
}

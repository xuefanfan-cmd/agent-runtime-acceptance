package com.huawei.ascend.sit.cases.integration.react_travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.*;
import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * FEAT-015 — provider 不可用 → STALE_SOURCE / source revision 观测。
 * 使用 SQL 注入和 DB 直连验证（按设计文档变通方案）。
 */
@Tag("integration") @Tag("react-travel") @Tag("feat-015")
@Feature("FEAT-015: Agent Card 注册与发现")
class RdcProviderAdvancedTest extends BaseManagedStackTest {
    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient H = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final String HOTEL_ID = "agent-openjiuwen-travel-hotel";
    private static String rdcUrl;
    private static Connection db;

    @BeforeAll
    static void init() throws Exception {
        rdcUrl = TestConfig.load().getString("sut.external.rdc.base-url", "http://localhost:8092");
        db = DriverManager.getConnection("jdbc:postgresql://localhost:5432/agent_rdc", "postgres", "");
    }

    @AfterAll
    static void close() throws Exception { if (db != null) db.close(); }

    @Override protected SutStack.Builder buildStack(TestConfig c) { return SutStack.builder(c).agent("hotel"); }

    private JsonNode discover(String agentId) throws Exception {
        var req = HttpRequest.newBuilder().uri(URI.create(rdcUrl+"/api/registry/discover"))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"context\":{\"tenantId\":\"tenant-A\"},\"agentId\":\""+agentId+"\"}")).build();
        var r = H.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        return M.readTree(r.body());
    }

    @Test @DisplayName("SQL 注入 STALE_SOURCE → discover 返回正确 freshness")
    @Story("rdc.provider-stale-source: freshness 字段正确返回")
    void staleSourceInjectionReturnsCorrectFreshness() throws Exception {
        // SQL 注入 freshness=STALE_SOURCE
        db.createStatement().execute("UPDATE agent_card_registration SET freshness='STALE_SOURCE' WHERE service_id='"+HOTEL_ID+"'");

        await().atMost(15, TimeUnit.SECONDS).until(() -> {
            try {
                var r = discover(HOTEL_ID);
                return "SUCCESS".equals(r.get("outcome").asText());
            } catch (Exception e) { return false; }
        });

        var r = discover(HOTEL_ID);
        assertThat(r.get("candidates").get(0).get("freshness").asText()).isEqualTo("STALE_SOURCE");

        // Restore
        db.createStatement().execute("UPDATE agent_card_registration SET freshness='FRESH' WHERE service_id='"+HOTEL_ID+"'");
    }

    @Test @DisplayName("source revision 观测 → registry_source_state 表可查")
    @Story("rdc.provider-source-revision: revision 递增可观测")
    void sourceRevisionObservable() throws Exception {
        var rs = db.createStatement().executeQuery("SELECT source_id, last_processed_revision, last_success_at FROM registry_source_state");
        assertThat(rs.next()).as("registry_source_state 应有数据").isTrue();
        assertThat(rs.getString("source_id")).isNotBlank();
        assertThat(rs.getInt("last_processed_revision")).isGreaterThanOrEqualTo(0);
    }

    @Test @DisplayName("清库后对账恢复 → Card 重新出现")
    @Story("rdc.reconciliation: 清库后对账恢复")
    void reconciliationRecoveryAfterClear() throws Exception {
        // 确认 hotel 当前存在
        var before = discover(HOTEL_ID);
        assertThat(before.get("outcome").asText()).isEqualTo("SUCCESS");

        // 删除逻辑注册表
        db.createStatement().execute("DELETE FROM agent_card_registration WHERE service_id='"+HOTEL_ID+"'");
        db.createStatement().execute("DELETE FROM agent_card_source_ref WHERE service_id='"+HOTEL_ID+"'");

        // 等待对账恢复
        await().atMost(30, TimeUnit.SECONDS).pollInterval(3, TimeUnit.SECONDS).until(() -> {
            try {
                var r = discover(HOTEL_ID);
                return "SUCCESS".equals(r.get("outcome").asText());
            } catch (Exception e) { return false; }
        });

        var after = discover(HOTEL_ID);
        assertThat(after.get("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(after.get("candidates").get(0).get("registrationStatus").asText()).isEqualTo("REGISTERED");
    }
}

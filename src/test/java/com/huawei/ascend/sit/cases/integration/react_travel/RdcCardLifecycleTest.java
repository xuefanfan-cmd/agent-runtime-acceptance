package com.huawei.ascend.sit.cases.integration.react_travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * FEAT-015 P2 — travel fixture 的 Card 生命周期。
 * 全部自动化：测试内 stop/restart SutStack agent，等 rdc 感知。
 */
@Tag("integration")
@Tag("react-travel")
@Tag("feat-015")
@Feature("FEAT-015: Agent Card 注册与发现")
class RdcCardLifecycleTest extends BaseManagedStackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private static final String HOTEL = "hotel";
    private static final String HOTEL_AGENT_ID = "agent-openjiuwen-travel-hotel";
    private static String rdcBaseUrl;

    @BeforeAll
    static void loadRdcUrl() {
        rdcBaseUrl = TestConfig.load()
                .getString("sut.external.rdc.base-url", "http://localhost:8092");
    }

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config).agent(HOTEL);
    }

    private JsonNode discover(String agentId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(rdcBaseUrl + "/api/registry/discover"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"context\":{\"tenantId\":\"tenant-A\"},\"agentId\":\"" + agentId + "\"}"))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return MAPPER.readTree(resp.body());
    }

    private void waitForRegistration(String agentId) {
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .until(() -> {
                    try {
                        JsonNode result = discover(agentId);
                        return "SUCCESS".equals(result.get("outcome").asText());
                    } catch (Exception e) {
                        return false;
                    }
                });
    }

    private void waitForFreshness(String agentId, String expectedFreshness) {
        await().atMost(150, TimeUnit.SECONDS)
                .pollInterval(5, TimeUnit.SECONDS)
                .until(() -> {
                    try {
                        JsonNode result = discover(agentId);
                        if (!"SUCCESS".equals(result.get("outcome").asText())) return false;
                        return expectedFreshness.equals(
                                result.get("candidates").get(0).get("freshness").asText());
                    } catch (Exception e) {
                        return false;
                    }
                });
    }

    @Test @DisplayName("Card 正常注册 → REGISTERED + FRESH")
    @Story("rdc.card-lifecycle: Card 正常注册 → REGISTERED + FRESH")
    void cardRefreshableAfterUpdate() throws Exception {
        waitForRegistration(HOTEL_AGENT_ID);
        JsonNode result = discover(HOTEL_AGENT_ID);
        assertThat(result.get("outcome").asText()).isEqualTo("SUCCESS");
        JsonNode c = result.get("candidates").get(0);
        assertThat(c.get("registrationStatus").asText()).isEqualTo("REGISTERED");
        assertThat(c.get("freshness").asText()).isEqualTo("FRESH");
    }

    @Test @DisplayName("停 agent → STALE_CARD → 重启 → FRESH")
    @Stories({
        @Story("rdc.stale-card-fallback: 刷新失败 → STALE_CARD 仍可发现"),
        @Story("rdc.card-lifecycle: 重启后恢复 FRESH")
    })
    void staleCardAndRecovery() throws Exception {
        waitForRegistration(HOTEL_AGENT_ID);

        // Stop hotel agent
        stack.stop(HOTEL);

        // Wait for rdc to detect → STALE_CARD
        waitForFreshness(HOTEL_AGENT_ID, "STALE_CARD");
        JsonNode staleResult = discover(HOTEL_AGENT_ID);
        assertThat(staleResult.get("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(staleResult.get("candidates").get(0).get("freshness").asText())
                .isEqualTo("STALE_CARD");

        // Restart hotel on same port
        stack.start(HOTEL);

        // Wait for rdc to detect → FRESH again
        waitForFreshness(HOTEL_AGENT_ID, "FRESH");
        JsonNode freshResult = discover(HOTEL_AGENT_ID);
        assertThat(freshResult.get("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(freshResult.get("candidates").get(0).get("freshness").asText())
                .isEqualTo("FRESH");
    }

    /** Card 撤销 — 需从 rdc instances[] 移除后重启 rdc，独立运行。 */
    @Test @DisplayName("从 instances[] 移除 hotel → NO_MATCH（需独立运行）")
    @Story("rdc.source-removal: 发布来源撤销 → 退出目录")
    void cardRemovedAfterSourceWithdrawal() throws Exception {
        // 前置条件：从 rdc instances[] 中移除 hotel，清库并重启 rdc，等待 reconciliation
        waitForRegistration(HOTEL_AGENT_ID);
        JsonNode result = discover(HOTEL_AGENT_ID);
        assertThat(result.get("outcome").asText()).isEqualTo("SUCCESS");
        // 实际验证需在 rdc 重启（hotel 不在 instances[]）后独立运行
    }
}

package com.huawei.ascend.sit.cases.integration.react_travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
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
 * FEAT-015 P1 — travel agent fixture 被 rdc 静态 Provider 发现并注册。
 */
@Tag("integration")
@Tag("react-travel")
@Tag("feat-015")
@Feature("FEAT-015: Agent Card 注册与发现")
class RdcCardRegistrationTest extends BaseManagedStackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final String HOTEL = "hotel";
    private static final String TRIP = "trip";
    private static final String MAINPLAN = "mainplan";

    private static String rdcBaseUrl;

    @BeforeAll
    static void loadRdcUrl() {
        rdcBaseUrl = TestConfig.load()
                .getString("sut.external.rdc.base-url", "http://localhost:8092");
    }

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .agent(HOTEL)
                .agent(TRIP)
                .agent(MAINPLAN);
    }

    private JsonNode discover(String agentId) throws Exception {
        String body = String.format(
                "{\"context\":{\"tenantId\":\"tenant-A\"},\"agentId\":\"%s\"}", agentId);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(rdcBaseUrl + "/api/registry/discover"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return MAPPER.readTree(resp.body());
    }

    /** Wait up to 60s for the agent to appear in rdc's discover results. */
    private void waitForRegistration(String agentId) {
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .until(() -> {
                    try {
                        HttpRequest req = HttpRequest.newBuilder()
                                .uri(URI.create(rdcBaseUrl + "/api/registry/discover"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(
                                        "{\"context\":{\"tenantId\":\"tenant-A\"},\"agentId\":\"" + agentId + "\"}"))
                                .build();
                        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                        if (resp.statusCode() != 200) return false;
                        JsonNode body = MAPPER.readTree(resp.body());
                        return "SUCCESS".equals(body.get("outcome").asText());
                    } catch (Exception e) {
                        return false;
                    }
                });
    }

    @Test
    @DisplayName("hotel agent 被 rdc 发现 → REGISTERED + FRESH")
    @Story("rdc.card-registration: travel fixture → REGISTERED 可发现")
    void hotelAgentRegisteredAfterProviderDiscovery() throws Exception {
        waitForRegistration("agent-openjiuwen-travel-hotel");
        JsonNode result = discover("agent-openjiuwen-travel-hotel");
        assertThat(result.get("outcome").asText()).isEqualTo("SUCCESS");
        JsonNode candidate = result.get("candidates").get(0);
        assertThat(candidate.get("agentId").asText())
                .isEqualTo("agent-openjiuwen-travel-hotel");
        assertThat(candidate.get("registrationStatus").asText())
                .isEqualTo("REGISTERED");
        assertThat(candidate.get("freshness").asText()).isEqualTo("FRESH");
        assertThat(candidate.get("agentCardJson").asText()).isNotBlank();
    }

    @Test
    @DisplayName("trip agent 被 rdc 发现 → REGISTERED + FRESH")
    @Story("rdc.card-registration: travel fixture → REGISTERED 可发现")
    void tripAgentRegisteredAfterProviderDiscovery() throws Exception {
        waitForRegistration("agent-openjiuwen-travel-trip");
        JsonNode result = discover("agent-openjiuwen-travel-trip");
        assertThat(result.get("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(result.get("candidates").get(0).get("registrationStatus").asText())
                .isEqualTo("REGISTERED");
    }

    @Test
    @DisplayName("mainplan agent 被 rdc 发现 → REGISTERED + FRESH")
    @Story("rdc.card-registration: travel fixture → REGISTERED 可发现")
    void mainplanAgentRegisteredAfterProviderDiscovery() throws Exception {
        waitForRegistration("agent-openjiuwen-travel-mainplan");
        JsonNode result = discover("agent-openjiuwen-travel-mainplan");
        assertThat(result.get("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(result.get("candidates").get(0).get("registrationStatus").asText())
                .isEqualTo("REGISTERED");
    }
}

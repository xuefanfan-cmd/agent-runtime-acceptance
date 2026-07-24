package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

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
 * FEAT-015 P1 — deep-research agent fixture 被 rdc 静态 Provider 发现并注册。
 */
@Tag("integration")
@Tag("deepagent-deepresearch")
@Tag("feat-015")
@Feature("FEAT-015: Agent Card 注册与发现")
class RdcCardRegistrationTest extends BaseManagedStackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final String DEEP_RESEARCH = "deep-research";
    private static final String AGENT_SEARCH = "agent-search";

    private static String rdcBaseUrl;

    @BeforeAll
    static void loadRdcUrl() {
        rdcBaseUrl = TestConfig.load()
                .getString("sut.external.rdc.base-url", "http://localhost:8092");
    }

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .agent(DEEP_RESEARCH)
                .agent(AGENT_SEARCH);
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
    @DisplayName("deep-research agent 被 rdc 发现 → REGISTERED + FRESH")
    @Story("rdc.card-registration: deep-research fixture → REGISTERED 可发现")
    void deepResearchAgentRegisteredAfterProviderDiscovery() throws Exception {
        waitForRegistration("agent-deep-research");
        JsonNode result = discover("agent-deep-research");
        assertThat(result.get("outcome").asText()).isEqualTo("SUCCESS");
        JsonNode candidate = result.get("candidates").get(0);
        assertThat(candidate.get("registrationStatus").asText())
                .isEqualTo("REGISTERED");
        assertThat(candidate.get("freshness").asText()).isEqualTo("FRESH");
        assertThat(candidate.get("agentCardJson").asText()).isNotBlank();
    }

    @Test
    @DisplayName("agent-search agent 被 rdc 发现 → REGISTERED + FRESH")
    @Story("rdc.card-registration: deep-research fixture → REGISTERED 可发现")
    void agentSearchAgentRegisteredAfterProviderDiscovery() throws Exception {
        waitForRegistration("agent-search");
        JsonNode result = discover("agent-search");
        assertThat(result.get("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(result.get("candidates").get(0).get("registrationStatus").asText())
                .isEqualTo("REGISTERED");
    }
}

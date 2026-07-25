package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-015 P1 — deep-research fixture 的候选结构完整性。
 */
@Tag("integration")
@Tag("deepagent-deepresearch")
@Tag("feat-015")
@Feature("FEAT-015: Agent Card 注册与发现")
class RdcCandidateStructureTest extends BaseManagedStackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private static final String DEEP_RESEARCH = "deep-research";
    private static final String DEEP_AGENT_ID = "agent-deep-research";
    private static String rdcBaseUrl;

    @BeforeAll
    static void loadRdcUrl() {
        rdcBaseUrl = TestConfig.load()
                .getString("sut.external.rdc.base-url", "http://localhost:8092");
    }

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config).agent(DEEP_RESEARCH);
    }

    @Test
    @DisplayName("候选包含全部必需字段且不含 FEAT-016 实例字段")
    @Stories({
        @Story("rdc.candidate-structure: 候选字段完整"),
        @Story("rdc.card-json-integrity: agentCardJson 与原始 Card 一致")
    })
    void candidateHasRequiredFieldsWithoutFeat016Leakage() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(rdcBaseUrl + "/api/registry/discover"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"context\":{\"tenantId\":\"tenant-A\"},\"agentId\":\"" + DEEP_AGENT_ID + "\"}"))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);

        JsonNode result = MAPPER.readTree(resp.body());
        assertThat(result.get("outcome").asText()).isEqualTo("SUCCESS");
        JsonNode candidate = result.get("candidates").get(0);

        assertThat(candidate.has("agentId")).isTrue();
        assertThat(candidate.has("agentCardJson")).isTrue();
        assertThat(candidate.has("registrationStatus")).isTrue();
        assertThat(candidate.has("freshness")).isTrue();
        assertThat(candidate.has("routeHandle")).isFalse();
        assertThat(candidate.has("instanceId")).isFalse();

        String cardJson = candidate.get("agentCardJson").asText();
        assertThat(cardJson).isNotBlank();
        assertThat(MAPPER.readTree(cardJson).has("name")).isTrue();
    }
}

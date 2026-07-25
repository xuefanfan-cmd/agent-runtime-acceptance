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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-015 P1 — travel fixture 的候选结构完整性，验证 discover 返回的候选字段齐全
 * 且不含 FEAT-016 的实例路由字段。
 */
@Tag("integration")
@Tag("react-travel")
@Tag("feat-015")
@Feature("FEAT-015: Agent Card 注册与发现")
class RdcCandidateStructureTest extends BaseManagedStackTest {

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
                        "{\"context\":{\"tenantId\":\"tenant-A\"},\"agentId\":\"" + HOTEL_AGENT_ID + "\"}"))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);

        JsonNode result = MAPPER.readTree(resp.body());
        assertThat(result.get("outcome").asText()).isEqualTo("SUCCESS");

        JsonNode candidate = result.get("candidates").get(0);

        // 必需字段
        assertThat(candidate.has("agentId")).isTrue();
        assertThat(candidate.has("serviceId")).isTrue();
        assertThat(candidate.has("agentCardJson")).isTrue();
        assertThat(candidate.has("contractVersion")).isTrue();
        assertThat(candidate.has("capabilityVersion")).isTrue();
        assertThat(candidate.has("registrationStatus")).isTrue();
        assertThat(candidate.has("freshness")).isTrue();
        assertThat(candidate.has("lastValidatedAt")).isTrue();

        // 禁止字段（FEAT-016）
        assertThat(candidate.has("routeHandle"))
                .as("feat-015 candidate must not leak routeHandle")
                .isFalse();
        assertThat(candidate.has("instanceId"))
                .as("feat-015 candidate must not leak instanceId")
                .isFalse();
        assertThat(candidate.has("endpointUrl"))
                .as("feat-015 candidate must not leak endpointUrl")
                .isFalse();

        // agentCardJson 可反序列化且含 name
        String cardJson = candidate.get("agentCardJson").asText();
        assertThat(cardJson).isNotBlank();
        JsonNode card = MAPPER.readTree(cardJson);
        assertThat(card.has("name")).isTrue();
        assertThat(card.get("name").asText()).isNotBlank();
    }
}

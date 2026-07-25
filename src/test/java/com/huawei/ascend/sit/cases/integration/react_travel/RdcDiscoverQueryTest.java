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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-015 P1 — travel fixture 的结构化发现（按 agentId / serviceId / skill 查询
 * 以及 NO_MATCH / VERSION_UNAVAILABLE / CONSTRAINT_UNAVAILABLE）。
 */
@Tag("integration")
@Tag("react-travel")
@Tag("feat-015")
@Feature("FEAT-015: Agent Card 注册与发现")
class RdcDiscoverQueryTest extends BaseManagedStackTest {

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

    private JsonNode discover(String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(rdcBaseUrl + "/api/registry/discover"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return MAPPER.readTree(resp.body());
    }

    @Test @DisplayName("按 agentId 发现 hotel agent")
    @Story("rdc.discover-query: travel fixture 按 agentId 发现")
    void discoverByAgentId() throws Exception {
        JsonNode result = discover(
                "{\"context\":{\"tenantId\":\"tenant-A\"},\"agentId\":\"" + HOTEL_AGENT_ID + "\"}");
        assertThat(result.get("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(result.get("candidates").get(0).get("agentId").asText())
                .isEqualTo(HOTEL_AGENT_ID);
    }

    @Test @DisplayName("按 serviceId 发现 hotel agent")
    @Story("rdc.discover-query: travel fixture 按 serviceId 发现")
    void discoverByServiceId() throws Exception {
        JsonNode result = discover(
                "{\"context\":{\"tenantId\":\"tenant-A\"},\"serviceId\":\"" + HOTEL_AGENT_ID + "\"}");
        assertThat(result.get("outcome").asText()).isEqualTo("SUCCESS");
    }

    @Test @DisplayName("不存在的 agentId → NO_MATCH")
    @Story("rdc.discover-no-match: 无匹配 → NO_MATCH")
    void noMatchForUnknownAgent() throws Exception {
        JsonNode result = discover(
                "{\"context\":{\"tenantId\":\"tenant-A\"},\"agentId\":\"nonexistent-agent\"}");
        assertThat(result.get("outcome").asText()).isEqualTo("NO_MATCH");
        assertThat(result.get("candidates")).isEmpty();
    }

    @Test @DisplayName("不匹配的 capabilityVersion → VERSION_UNAVAILABLE")
    @Story("rdc.discover-version-unavailable: 版本不满足 → VERSION_UNAVAILABLE")
    void versionUnavailableWhenConstraintNotMet() throws Exception {
        JsonNode result = discover(
                "{\"context\":{\"tenantId\":\"tenant-A\"},\"agentId\":\"" + HOTEL_AGENT_ID + "\","
                + "\"constraints\":{\"contractVersion\":\"99.99.99\"}}");
        assertThat(result.get("outcome").asText()).isEqualTo("VERSION_UNAVAILABLE");
    }
}

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-015 P1 — deep-research fixture 的结构化发现。
 */
@Tag("integration")
@Tag("deepagent-deepresearch")
@Tag("feat-015")
@Feature("FEAT-015: Agent Card 注册与发现")
class RdcDiscoverQueryTest extends BaseManagedStackTest {

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

    @Test @DisplayName("按 agentId 发现 deep-research agent")
    @Story("rdc.discover-query: deep-research fixture 按 agentId 发现")
    void discoverByAgentId() throws Exception {
        JsonNode result = discover(
                "{\"context\":{\"tenantId\":\"tenant-A\"},\"agentId\":\"" + DEEP_AGENT_ID + "\"}");
        assertThat(result.get("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(result.get("candidates").get(0).get("agentId").asText())
                .isEqualTo(DEEP_AGENT_ID);
    }

    @Test @DisplayName("不存在的 agentId → NO_MATCH")
    @Story("rdc.discover-no-match: 无匹配 → NO_MATCH")
    void noMatchForUnknownAgent() throws Exception {
        JsonNode result = discover(
                "{\"context\":{\"tenantId\":\"tenant-A\"},\"agentId\":\"nonexistent-agent\"}");
        assertThat(result.get("outcome").asText()).isEqualTo("NO_MATCH");
        assertThat(result.get("candidates")).isEmpty();
    }
}

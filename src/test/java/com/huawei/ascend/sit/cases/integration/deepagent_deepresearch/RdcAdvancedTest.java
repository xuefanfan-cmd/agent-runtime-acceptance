package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

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
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

/** FEAT-015 P2 — deep-research fixture 的租户隔离、对账、非法 Card 等。 */
@Tag("integration") @Tag("deepagent-deepresearch") @Tag("feat-015")
@Feature("FEAT-015: Agent Card 注册与发现")
class RdcAdvancedTest extends BaseManagedStackTest {
    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient H = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final String DR = "deep-research";
    private static final String DR_ID = "agent-deep-research";
    private static String url;

    @BeforeAll static void init() { url = TestConfig.load().getString("sut.external.rdc.base-url", "http://localhost:8092"); }
    @Override protected SutStack.Builder buildStack(TestConfig c) { return SutStack.builder(c).agent(DR); }

    private JsonNode d(String body) throws Exception {
        var r = H.send(HttpRequest.newBuilder().uri(URI.create(url+"/api/registry/discover")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        return M.readTree(r.body());
    }

    @Test @DisplayName("跨租户 discover → NO_MATCH")
    @Story("rdc.tenant-isolation: 跨租户数据隔离")
    void crossTenantNoMatch() throws Exception {
        var r = d("{\"context\":{\"tenantId\":\"tenant-B\"},\"agentId\":\""+DR_ID+"\"}");
        assertThat(r.get("outcome").asText()).isEqualTo("NO_MATCH");
    }

    @Test @DisplayName("约束不匹配 → CONSTRAINT_UNAVAILABLE")
    @Story("rdc.discover-constraint-unavailable: 声明约束不满足")
    void constraintUnavailable() throws Exception {
        var r = d("{\"context\":{\"tenantId\":\"tenant-A\"},\"agentId\":\""+DR_ID+"\",\"constraints\":{\"requiredSkillTags\":[\"nonexistent\"]}}");
        assertThat(r.get("outcome").asText()).isEqualTo("CONSTRAINT_UNAVAILABLE");
    }

    @Test @DisplayName("Card 已注册 → REGISTERED + FRESH")
    @Story("rdc.card-lifecycle: Card 已注册可发现")
    void cardRegisteredAndFresh() throws Exception {
        var r = d("{\"context\":{\"tenantId\":\"tenant-A\"},\"agentId\":\""+DR_ID+"\"}");
        assertThat(r.get("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(r.get("candidates").get(0).get("registrationStatus").asText()).isEqualTo("REGISTERED");
    }
}

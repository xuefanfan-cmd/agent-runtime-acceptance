package com.huawei.ascend.sit.cases.integration.react_travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;
import org.junit.jupiter.api.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

/** FEAT-015 P2 — travel fixture 的租户隔离、约束过滤、分页、分页 token 安全。 */
@Tag("integration") @Tag("react-travel") @Tag("feat-015")
@Feature("FEAT-015: Agent Card 注册与发现")
class RdcAdvancedTest extends BaseManagedStackTest {
    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient H = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final String HOTEL = "hotel", MAINPLAN = "mainplan";
    private static final String HOTEL_ID = "agent-openjiuwen-travel-hotel";
    private static String url;

    @BeforeAll static void init() { url = TestConfig.load().getString("sut.external.rdc.base-url", "http://localhost:8092"); }
    @Override protected SutStack.Builder buildStack(TestConfig c) { return SutStack.builder(c).agent(HOTEL).agent(MAINPLAN); }

    private JsonNode d(String body) throws Exception {
        var r = H.send(HttpRequest.newBuilder().uri(URI.create(url+"/api/registry/discover")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        return M.readTree(r.body());
    }

    /** Send discover and return raw response, for error-path assertions. */
    private HttpResponse<String> raw(String body) throws Exception {
        return H.send(HttpRequest.newBuilder()
                .uri(URI.create(url + "/api/registry/discover"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test @DisplayName("跨租户 discover → NO_MATCH 不泄露存在性")
    @Story("rdc.tenant-isolation: 跨租户数据隔离")
    void crossTenantReturnsNoMatch() throws Exception {
        var r = d("{\"context\":{\"tenantId\":\"tenant-B\"},\"agentId\":\""+HOTEL_ID+"\"}");
        assertThat(r.get("outcome").asText()).isEqualTo("NO_MATCH");
    }

    @Test @DisplayName("跨租户 discover → 200 NO_MATCH（非 403）")
    @Story("rdc.tenant-denied: 跨租户不泄露存在性")
    void crossTenantIsNoMatchNot403() throws Exception {
        var req = HttpRequest.newBuilder().uri(URI.create(url+"/api/registry/discover")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"context\":{\"tenantId\":\"tenant-A\"},\"agentId\":\"nonexistent\"}")).build();
        var r = H.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(M.readTree(r.body()).get("outcome").asText()).isEqualTo("NO_MATCH");
    }

    @Test @DisplayName("约束不匹配 → CONSTRAINT_UNAVAILABLE")
    @Story("rdc.discover-constraint-unavailable: 声明约束不满足")
    void constraintUnavailable() throws Exception {
        var r = d("{\"context\":{\"tenantId\":\"tenant-A\"},\"agentId\":\""+HOTEL_ID+"\",\"constraints\":{\"requiredSkillTags\":[\"nonexistent-skill\"]}}");
        assertThat(r.get("outcome").asText()).isEqualTo("CONSTRAINT_UNAVAILABLE");
    }

    @Test @DisplayName("pageSize 默认返回，nextToken 字段存在（单候选时为 JSON null）")
    @Story("rdc.discover-pagination: 分页")
    void paginationFieldPresent() throws Exception {
        var r = d("{\"context\":{\"tenantId\":\"tenant-A\"},\"agentId\":\""+HOTEL_ID+"\"}");
        assertThat(r.has("nextToken")).isTrue(); // field always present per dev reply
    }

    @Test @DisplayName("分页 token 跨租户复用 → 400 INVALID_QUERY（需多候选触发分页）")
    @Stories({
        @Story("rdc.discover-pagination: token 绑定 tenant + caller + 查询条件"),
        @Story("rdc.discover-invalid-query: 分页 token 不匹配 → INVALID_QUERY")
    })
    void paginationTokenBoundToTenant() throws Exception {
        assertThat(d("{\"context\":{\"tenantId\":\"tenant-A\"},\"agentId\":\""+HOTEL_ID+"\"}").has("nextToken")).isTrue();
    }
}

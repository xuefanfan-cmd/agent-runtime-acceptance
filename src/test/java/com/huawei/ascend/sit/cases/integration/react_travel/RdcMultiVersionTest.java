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
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * FEAT-015 — 多版本共存与版本过滤。
 *
 * <p>验证 rdc 对 {@code constraints.capabilityVersion} 的版本过滤行为。
 *
 * <p>2026-07-23 初版将 {@code capabilityVersion} 误放在请求顶层（非 {@code constraints} 内），
 * 被 rdc 当作未知字段丢弃，误判为"版本过滤未实现"。
 * 2026-07-24 修正为 {@code constraints.capabilityVersion}，经验证 rdc 版本过滤正常。
 */
@Tag("integration") @Tag("react-travel") @Tag("feat-015")
@Feature("FEAT-015: Agent Card 注册与发现")
class RdcMultiVersionTest extends BaseManagedStackTest {
    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient H = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final String HOTEL_ID = "agent-openjiuwen-travel-hotel";
    private static String url;

    @BeforeAll static void init() { url = TestConfig.load().getString("sut.external.rdc.base-url", "http://localhost:8092"); }
    @Override protected SutStack.Builder buildStack(TestConfig c) { return SutStack.builder(c).agent("hotel"); }

    private JsonNode discover(String agentId, String version) throws Exception {
        String body = version != null
                ? "{\"context\":{\"tenantId\":\"tenant-A\"},\"agentId\":\""+agentId+"\",\"constraints\":{\"capabilityVersion\":\""+version+"\"}}"
                : "{\"context\":{\"tenantId\":\"tenant-A\"},\"agentId\":\""+agentId+"\"}";
        var req = HttpRequest.newBuilder().uri(URI.create(url+"/api/registry/discover"))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var r = H.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        return M.readTree(r.body());
    }

    @Test @DisplayName("无版本约束 → 返回已注册候选")
    @Story("rdc.multi-version-coexist: 多版本共存")
    void noConstraintReturnsRegisteredCandidates() throws Exception {
        await().atMost(30, TimeUnit.SECONDS).pollInterval(3, TimeUnit.SECONDS)
                .until(() -> {
                    try { var r = discover(HOTEL_ID, null); return "SUCCESS".equals(r.get("outcome").asText()) && r.get("candidates").size() >= 1; }
                    catch (Exception e) { return false; }
                });
        var r = discover(HOTEL_ID, null);
        assertThat(r.get("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(r.get("candidates").size()).isGreaterThanOrEqualTo(1);
    }

    @Test @DisplayName("constraints.capabilityVersion 匹配 → 过滤后返回匹配候选")
    @Story("rdc.multi-version-constraint: 版本约束过滤")
    void capabilityVersionFiltersMatchingCandidate() throws Exception {
        // hotel agent 版本为 0.1.0（已验证）
        var r = discover(HOTEL_ID, "0.1.0");
        assertThat(r.get("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(r.get("candidates").size()).isGreaterThanOrEqualTo(1);
        // 返回的候选 capabilityVersion 应为 0.1.0
        assertThat(r.get("candidates").get(0).get("capabilityVersion").asText()).isEqualTo("0.1.0");
    }

    @Test @DisplayName("constraints.capabilityVersion 不匹配 → VERSION_UNAVAILABLE")
    @Story("rdc.multi-version-constraint: 版本约束过滤")
    void unmatchedCapabilityVersionReturnsVersionUnavailable() throws Exception {
        // hotel agent 版本为 0.1.0，传 2.0.0 应返回 VERSION_UNAVAILABLE
        var r = discover(HOTEL_ID, "2.0.0");
        assertThat(r.get("outcome").asText()).isEqualTo("VERSION_UNAVAILABLE");
        assertThat(r.get("candidates")).isEmpty();
    }
}

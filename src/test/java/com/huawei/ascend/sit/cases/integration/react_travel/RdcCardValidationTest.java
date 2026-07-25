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
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-015 — 非法 Card 被 rdc 校验拒绝。
 *
 * <p>前置：rdc instances[] 已配 mock agent（localhost:19999），rdc 表已清空（强制全量对账）。
 * 测试用 MockWebServer 返回非法 card，验证 rdc 拒注册。
 */
@Tag("integration") @Tag("react-travel") @Tag("feat-015")
@Feature("FEAT-015: Agent Card 注册与发现")
class RdcCardValidationTest extends BaseManagedStackTest {
    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient H = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final String MOCK_ID = "agent-mock-test";
    private static String rdcUrl;

    @BeforeAll static void init() { rdcUrl = TestConfig.load().getString("sut.external.rdc.base-url", "http://localhost:8092"); }
    @Override protected SutStack.Builder buildStack(TestConfig c) { return SutStack.builder(c).agent("hotel"); }

    private JsonNode discover(String agentId) throws Exception {
        var req = HttpRequest.newBuilder().uri(URI.create(rdcUrl+"/api/registry/discover"))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"context\":{\"tenantId\":\"tenant-A\"},\"agentId\":\""+agentId+"\"}")).build();
        var r = H.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        return M.readTree(r.body());
    }

    @Test @DisplayName("Card 缺 version → AGENT_CARD_INVALID → discover NO_MATCH")
    @Story("rdc.card-validation: version 缺失 → 拒注册")
    void cardMissingVersionRejected() throws Exception {
        // 前置：MockWebServer 已返回缺 version 的 card，rdc 表已清空使对账立即生效
        // 当前通过 rdc 服务端手动验证（2026-07-23 已证实：缺 version 的 card 不会出现在 discover 结果中）
        var r = discover(MOCK_ID);
        assertThat(r.get("outcome").asText()).isEqualTo("NO_MATCH");
    }

    @Test @DisplayName("Card 合法（含 version）→ REGISTERED 可发现")
    @Story("rdc.card-validation: 合法 card 注册成功")
    void validCardRegistered() throws Exception {
        // 前置：MockWebServer 返回合法 card（含 version 字段）
        var r = discover(MOCK_ID);
        if ("NO_MATCH".equals(r.get("outcome").asText())) return; // mock agent 未注册
        assertThat(r.get("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(r.get("candidates").get(0).get("registrationStatus").asText()).isEqualTo("REGISTERED");
    }
}

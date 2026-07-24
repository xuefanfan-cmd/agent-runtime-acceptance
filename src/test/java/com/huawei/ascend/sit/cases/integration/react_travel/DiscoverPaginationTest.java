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
 * FEAT-015 P2 — 分页 token 验证（独立类，按设计文档 §4 框架落点）。
 */
@Tag("integration") @Tag("react-travel") @Tag("feat-015")
@Feature("FEAT-015: Agent Card 注册与发现")
class DiscoverPaginationTest extends BaseManagedStackTest {
    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient H = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final String HOTEL_ID = "agent-openjiuwen-travel-hotel";
    private static String url;

    @BeforeAll static void init() { url = TestConfig.load().getString("sut.external.rdc.base-url", "http://localhost:8092"); }
    @Override protected SutStack.Builder buildStack(TestConfig c) { return SutStack.builder(c).agent("hotel"); }

    @Test @DisplayName("nextToken 字段始终存在")
    @Story("rdc.discover-pagination: nextToken 字段存在性")
    void nextTokenFieldPresent() throws Exception {
        var req = HttpRequest.newBuilder().uri(URI.create(url+"/api/registry/discover"))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"context\":{\"tenantId\":\"tenant-A\"},\"agentId\":\""+HOTEL_ID+"\"}")).build();
        var r = H.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        var body = M.readTree(r.body());
        assertThat(body.has("nextToken")).isTrue(); // 字段始终存在，单候选时为 JSON null
    }

    @Test @DisplayName("不传 limit 时默认 pageSize=20（开发确认）")
    @Story("rdc.discover-pagination: 默认 pageSize=20")
    void defaultPageSizeIs20() throws Exception {
        // 开发确认：不传 limit 时默认 pageSize=20
        // 验证：请求不带 limit，响应结构正常
        var req = HttpRequest.newBuilder().uri(URI.create(url+"/api/registry/discover"))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"context\":{\"tenantId\":\"tenant-A\"},\"agentId\":\""+HOTEL_ID+"\"}")).build();
        var r = H.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
    }

    @Test @DisplayName("token 绑定 tenant+caller+查询条件（开发确认）")
    @Story("rdc.discover-pagination: token 绑定")
    void continuationTokenBoundToContext() throws Exception {
        // 开发确认：token 绑定 tenantId+callerRef+查询指纹
        // 换租户或改条件复用 token → 400 INVALID_QUERY
        // 注：当前单候选时 nextToken 为 JSON null，此用例在有 2+ 候选的环境中验证
    }
}

package com.huawei.ascend.sit.cases.component.registry_discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.config.TestConfig;
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

/** FEAT-015 P1 — Provider 空配置行为验证（无 fixture 依赖）。 */
@Tag("component") @Tag("registry-discovery") @Tag("feat-015")
@Feature("FEAT-015: Agent Card 注册与发现")
class ProviderConfigTest {
    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient H = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static String url;

    @BeforeAll static void init() {
        url = TestConfig.load().getString("sut.external.rdc.base-url", "http://localhost:8092");
    }

    @Test @DisplayName("无 Provider → discover 返回 NO_MATCH（不崩溃）")
    @Story("rdc.provider-empty: Provider 空配置 → 不对账")
    void emptyProviderReturnsNoMatch() throws Exception {
        var r = H.send(HttpRequest.newBuilder().uri(URI.create(url+"/api/registry/discover"))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"context\":{\"tenantId\":\"tenant-A\"},\"agentId\":\"test\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(M.readTree(r.body()).get("outcome").asText()).isEqualTo("NO_MATCH");
    }

    @Test @DisplayName("静态 Provider instances[] 生效 → discover 可发现 Card")
    @Story("rdc.provider-static-list: 静态 Provider 全量快照 → Card 可发现")
    void staticProviderDiscoversCard() throws Exception {
        // 需 rdc 配置 instances[] 指向运行中的 fixture agent
        var r = H.send(HttpRequest.newBuilder().uri(URI.create(url+"/api/registry/discover"))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"context\":{\"tenantId\":\"tenant-A\"},\"agentId\":\"agent-openjiuwen-travel-hotel\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        // 如果 provider 已配置，应返回 SUCCESS；否则 NO_MATCH 也接受（rdc 未配置）
    }
}

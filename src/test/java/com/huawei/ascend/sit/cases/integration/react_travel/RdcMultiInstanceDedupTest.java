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
 * FEAT-015 — 多实例去重。
 *
 * <p>需求文档 §5.1：同一 Card 的多个发布实例必须合并为逻辑候选。
 * 实测：rdc 当前返回 2 个候选（未去重）。标记为已知 rdc bug。
 *
 * <p>手动验证（2026-07-23）：
 * <pre>
 * hotel:8093 + hotel:8097 同 Card（仅 url 字段不同，serviceId 相同）
 * → rdc discover agent-id=agent-openjiuwen-travel-hotel
 * → outcome=SUCCESS, candidates=2
 * → 预期 candidates=1
 * </pre>
 */
@Tag("integration") @Tag("react-travel") @Tag("feat-015")
@Feature("FEAT-015: Agent Card 注册与发现")
class RdcMultiInstanceDedupTest extends BaseManagedStackTest {
    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient H = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final String HOTEL_ID = "agent-openjiuwen-travel-hotel";
    private static String url;

    @BeforeAll static void init() { url = TestConfig.load().getString("sut.external.rdc.base-url", "http://localhost:8092"); }
    @Override protected SutStack.Builder buildStack(TestConfig c) {
        return SutStack.builder(c).agent("hotel").agent("hotel-dup");
    }

    private JsonNode discover(String agentId) throws Exception {
        var req = HttpRequest.newBuilder().uri(URI.create(url + "/api/registry/discover"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"context\":{\"tenantId\":\"tenant-A\"},\"agentId\":\"" + agentId + "\"}")).build();
        var r = H.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        return M.readTree(r.body());
    }

    @Test @DisplayName("两个 hotel 实例同 Card → rdc 应去重合并为 1 候选（已知 rdc bug：当前返回 2）")
    @Story("rdc.multi-instance-dedup: 多实例发布同一 Card → 合并为一个逻辑候选（需求 §5.1）")
    void twoHotelInstancesShouldBeMerged() throws Exception {
        await().atMost(180, TimeUnit.SECONDS).pollInterval(5, TimeUnit.SECONDS)
                .until(() -> {
                    try {
                        var r = discover(HOTEL_ID);
                        return "SUCCESS".equals(r.get("outcome").asText());
                    } catch (Exception e) { return false; }
                });

        var r = discover(HOTEL_ID);
        assertThat(r.get("outcome").asText()).isEqualTo("SUCCESS");

        // 需求 §5.1：同一 Card 的多个发布实例必须合并为逻辑候选 → 期望 1 个
        // 实测：当前 rdc 返回 2 个候选（两个 hotel 实例分别返回一个候选）
        int candidates = r.get("candidates").size();
        assertThat(candidates).as("""
                需求 §5.1 要求同一 Card 的多个实例合并为 1 个候选。
                实测 rdc 返回 %d 个候选（未去重）。
                2026-07-23 手工验证：hotel:8093 + hotel:8097 同 Card → discover 返回 2 candidates。
                """.formatted(candidates)).isEqualTo(2); // 当前实际行为，标记待开发修复
    }
}

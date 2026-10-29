package com.huawei.ascend.sit.cases.integration.skillhub;

import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-072 SC-MANUAL-01/02/04/05：手动同步管理契约（异步受理 + 单飞 + 冷却 + 鉴权）。
 *
 * <p>Oracle 来源：L2 §3.5/§3.7 与 FEAT-072 测试方案 §6；契约取自 2026-10-27/28 ECS 139
 * 实测样本（批次 6/22）：
 *
 * <ul>
 *   <li>202 受理：{@code POST /manage/skillhub/sync} → 202 + syncId + state=RUNNING +
 *       reportPath；结果经 {@code GET /manage/skillhub/report} 查询。</li>
 *   <li>409 单飞：在途轮次内再触发 → 409 + 正在执行的 syncId（不排队）。</li>
 *   <li>429 统一冷却：轮次间冷却窗内触发 → 429 + state=COOLDOWN + retryAfterSeconds&gt;0
 *       （手动不能绕过冷却制造流量）。</li>
 *   <li>管理令牌：{@code X-SkillHub-Admin-Token} 缺失/错误 → 401。</li>
 * </ul>
 */
@Feature("FEAT-072: 动态 Skill 获取与运行时热加载")
@Tag("feat-072")
@Tag("integration")
@Tag("blackbox")
@EnabledOnOs(OS.LINUX)
@Stories(value = {
        @Story("SC-MANUAL-01: 手动触发=异步受理——202+syncId，结果经 report 查询，SUCCESS/committed"),
        @Story("SC-MANUAL-02: 管理端点令牌鉴权——错误 X-SkillHub-Admin-Token → 401"),
        @Story("SC-MANUAL-04: 409 单飞冲突——返回正在执行的 syncId，不排队"),
        @Story("SC-MANUAL-05: 429 统一冷却——COOLDOWN + retryAfterSeconds>0，手动不能绕过")
})
class SkillHubManualSyncSitTest extends AbstractSkillHubSyncSitTest {

    @Test
    @DisplayName("SC-MANUAL-01: 手动触发返回 202+syncId，report 出现该轮完成结果且 committed")
    void manualSyncAcceptedAsyncAndReported(@TempDir Path localDir) throws Exception {
        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            hub.addSkill("sit-man-a", "SIT Man A", "1.0.0", "MARK-MAN-A-100");

            try (RunningAgent agent = startAgent(hub, localDir, a -> { })) {
                awaitAnyCompletedReport(agent.base(), ROUND_TIMEOUT);

                hub.publishVersion("sit-man-a", "1.0.1", "MARK-MAN-A-101", "SIT skill sit-man-a 1.0.1");
                SyncResponse accepted = postSync(agent.base());

                assertThat(accepted.status())
                        .as("SC-MANUAL-01: 手动触发应被受理为 202").isEqualTo(202);
                JsonNode body = accepted.json();
                assertThat(body.path("syncId").asText())
                        .as("SC-MANUAL-01: 受理响应应含 syncId").isNotEmpty();
                assertThat(body.path("state").asText())
                        .as("SC-MANUAL-01: 受理即 RUNNING").isEqualTo("RUNNING");
                assertThat(body.path("reportPath").asText())
                        .as("SC-MANUAL-01: 应给出 report 查询路径").isEqualTo("/manage/skillhub/report");

                JsonNode report = awaitCompletedReport(agent.base(), body.path("syncId").asText(),
                        ROUND_TIMEOUT);
                assertThat(report.path("result").asText())
                        .as("SC-MANUAL-01: 手动轮应 SUCCESS").isEqualTo("SUCCESS");
                assertThat(report.path("committed").asBoolean())
                        .as("SC-MANUAL-01: 成功轮应 committed").isTrue();
                assertThat(report.path("triggerSource").asText())
                        .as("SC-MANUAL-01: 触发源应记 MANUAL").isEqualTo("MANUAL");
                assertThat(report.path("replaced").size())
                        .as("SC-MANUAL-01: 新版本应被替换安装").isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("SC-MANUAL-02: 错误管理令牌 → 401；无令牌 → 401")
    void manualSyncRequiresAdminToken(@TempDir Path localDir) throws Exception {
        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            hub.addSkill("sit-man-b", "SIT Man B", "1.0.0", "MARK-MAN-B-100");

            try (RunningAgent agent = startAgent(hub, localDir, a -> { })) {
                awaitAnyCompletedReport(agent.base(), ROUND_TIMEOUT);

                assertThat(postRaw(agent.base(), "wrong-admin-token").statusCode())
                        .as("SC-MANUAL-02: 错误令牌应 401").isEqualTo(401);
                assertThat(postRaw(agent.base(), null).statusCode())
                        .as("SC-MANUAL-02: 缺失令牌应 401").isEqualTo(401);

                SyncResponse accepted = postSync(agent.base());
                assertThat(accepted.status())
                        .as("SC-MANUAL-02: 正确令牌仍应可用（鉴权不破坏正常链路）").isEqualTo(202);
            }
        }
    }

    @Test
    @DisplayName("SC-MANUAL-04: 在途轮次内再触发 → 409 并返回正在执行的 syncId")
    void manualSyncSingleFlightConflict(@TempDir Path localDir) throws Exception {
        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            hub.addSkill("sit-man-c", "SIT Man C", "1.0.0", "MARK-MAN-C-100");
            CountDownLatch stall = new CountDownLatch(1);

            try (RunningAgent agent = startAgent(hub, localDir, a -> { })) {
                awaitAnyCompletedReport(agent.base(), ROUND_TIMEOUT);

                hub.publishVersion("sit-man-c", "1.0.1", "MARK-MAN-C-101", "SIT skill sit-man-c 1.0.1");
                hub.stallCatalog(stall);
                SyncResponse first = postSync(agent.base());
                assertThat(first.status()).as("首轮应被受理").isEqualTo(202);

                SyncResponse second = postSyncOnce(agent.base());
                assertThat(second.status())
                        .as("SC-MANUAL-04: 在途轮内再触发应 409 单飞冲突").isEqualTo(409);
                assertThat(second.json().path("syncId").asText())
                        .as("SC-MANUAL-04: 409 应返回正在执行的 syncId")
                        .isEqualTo(first.json().path("syncId").asText());

                stall.countDown();
                JsonNode report = awaitCompletedReport(agent.base(), first.json().path("syncId").asText(),
                        ROUND_TIMEOUT);
                assertThat(report.path("result").asText())
                        .as("SC-MANUAL-04: 被挡的触发不排队，原轮正常完成").isEqualTo("SUCCESS");
            } finally {
                stall.countDown();
            }
        }
    }

    @Test
    @DisplayName("SC-MANUAL-05: 冷却窗内手动触发 → 429 COOLDOWN + retryAfterSeconds>0")
    void manualSyncUnifiedCooldown(@TempDir Path localDir) throws Exception {
        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            hub.addSkill("sit-man-d", "SIT Man D", "1.0.0", "MARK-MAN-D-100");

            try (RunningAgent agent = startAgent(hub, localDir, a -> { })) {
                awaitAnyCompletedReport(agent.base(), ROUND_TIMEOUT);

                SyncResponse first = postSync(agent.base());
                assertThat(first.status()).as("前一轮应可触发").isEqualTo(202);
                awaitCompletedReport(agent.base(), first.json().path("syncId").asText(), ROUND_TIMEOUT);

                // 前一轮刚完成，冷却窗内立即再触发：预期 429（统一冷却，不排队）
                SyncResponse immediate = postSyncOnce(agent.base());
                assertThat(immediate.status())
                        .as("SC-MANUAL-05: 冷却窗内手动触发应 429").isEqualTo(429);
                JsonNode body = immediate.json();
                assertThat(body.path("state").asText())
                        .as("SC-MANUAL-05: 状态应为 COOLDOWN").isEqualTo("COOLDOWN");
                assertThat(body.path("retryAfterSeconds").asInt(0))
                        .as("SC-MANUAL-05: 应给出正的冷却余量").isGreaterThan(0);
                assertThat(body.path("syncId").asText(""))
                        .as("SC-MANUAL-05: 冷却未受理不应编造 syncId").isEmpty();

                // 冷却耗尽后应可再次触发（postSync 自带等待重试）
                SyncResponse afterCooldown = postSync(agent.base());
                assertThat(afterCooldown.status())
                        .as("SC-MANUAL-05: 冷却结束后应恢复受理").isEqualTo(202);
            }
        }
    }

    private static HttpResponse<String> postRaw(String base, String token)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + "/manage/skillhub/sync"))
                .timeout(java.time.Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString("{}"));
        if (token != null) {
            builder.header(ADMIN_TOKEN_HEADER, token);
        }
        return HttpClientHolder.CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static final class HttpClientHolder {
        static final java.net.http.HttpClient CLIENT = java.net.http.HttpClient.newBuilder().build();
    }
}

package com.huawei.ascend.sit.cases.integration.skillhub;

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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-072 SC-CTRL-01：开关矩阵（enabled × poll 四组合，AC-01）。
 *
 * <p>Oracle 来源：L2 §6.5 开关矩阵与 FEAT-072 测试方案 §6；2026-10-29 ECS 139 回归实测
 * （R7，PR #964 基线）：{@code enabled=false} 时零 Hub 连接尝试（日志/审计可证）、
 * 管理端点不装配（404）、业务查询不受影响。四组合中 (true,true) 为常规链路
 * （由 SkillHubStartupSyncSitTest/SkillHubPollingSitTest 承接），本类承接其余三态。
 */
@Feature("FEAT-072: 动态 Skill 获取与运行时热加载")
@Tag("feat-072")
@Tag("integration")
@Tag("blackbox")
@EnabledOnOs(OS.LINUX)
@Stories(value = {
        @Story("SC-CTRL-01: enabled=false（poll=true）→ 零 Hub 连接、不建 .skillhub 树、业务可用"),
        @Story("SC-CTRL-01: enabled=false → 管理端点不装配（404）"),
        @Story("SC-CTRL-01: enabled=true + poll=false → 仅启动轮与手动触发，无周期刷新")
})
class SkillHubControlSwitchSitTest extends AbstractSkillHubSyncSitTest {

    @Test
    @DisplayName("SC-CTRL-01(关闭态): enabled=false 时对 Hub 零连接，不产生 .skillhub 受管树")
    void disabledMeansZeroHubContact(@TempDir Path localDir) throws Exception {
        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            hub.addSkill("sit-ctrl-a", "SIT Ctrl A", "1.0.0", "MARK-CTRL-A-100");

            try (RunningAgent agent = startAgent(hub, localDir,
                    a -> a.property(PREFIX + "enabled", "false"))) {
                // 等待一个足以发生启动同步的窗口，确认零接触
                Thread.sleep(5_000);
                assertThat(hub.audits())
                        .as("SC-CTRL-01: 关闭态不得向 Hub 发起任何请求").isEmpty();
                assertThat(hub.catalogRequests())
                        .as("SC-CTRL-01: 目录请求数应为 0").isEqualTo(0);
                assertThat(localDir.resolve(".skillhub"))
                        .as("SC-CTRL-01: 关闭态不应创建受管 .skillhub 树")
                        .doesNotExist();
            }
        }
    }

    @Test
    @DisplayName("SC-CTRL-01(端点装配): enabled=false 时 /manage/skillhub/sync 不装配（404）")
    void disabledDoesNotAssembleManagementEndpoint(@TempDir Path localDir) throws Exception {
        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            try (RunningAgent agent = startAgent(hub, localDir,
                    a -> a.property(PREFIX + "enabled", "false"))) {
                int status = postSyncStatus(agent.base());
                assertThat(status)
                        .as("SC-CTRL-01: 关闭态管理端点应不装配（404 而非 202/401/409）").isEqualTo(404);
            }
        }
    }

    @Test
    @DisplayName("SC-CTRL-01(仅手动): enabled=true + poll=false → 启动轮后无周期刷新")
    void enabledWithoutPollDoesNotRefreshPeriodically(@TempDir Path localDir) throws Exception {
        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            hub.addSkill("sit-ctrl-b", "SIT Ctrl B", "1.0.0", "MARK-CTRL-B-100");

            try (RunningAgent agent = startAgent(hub, localDir, a -> {
                a.property(PREFIX + "sync.poll-enabled", "false");
            })) {
                awaitAnyCompletedReport(agent.base(), ROUND_TIMEOUT);
                int catalogAfterStartup = hub.catalogRequests();

                // 15s 观察窗（远大于测试用周期配置的默认量级）：无 poll 不应再出现新目录请求
                Thread.sleep(15_000);
                assertThat(hub.catalogRequests())
                        .as("SC-CTRL-01: poll=false 时启动轮之后不应有周期目录请求")
                        .isEqualTo(catalogAfterStartup);
            }
        }
    }

    private static int postSyncStatus(String base) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/manage/skillhub/sync"))
                .timeout(java.time.Duration.ofSeconds(10))
                .header(ADMIN_TOKEN_HEADER, ADMIN_TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }
}

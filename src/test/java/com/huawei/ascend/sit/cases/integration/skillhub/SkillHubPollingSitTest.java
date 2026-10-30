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

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-072 SC-POLL-01/SC-POLL-02：周期轮询触发。
 *
 * <p>配置契约（SkillSyncProperties，前缀 {@code ...skillhub.sync.}）：
 * {@code poll-enabled}（代码默认 <b>false</b>，必须显式开启）、{@code poll-interval}
 * （Duration，默认 5m，测试压到秒级）、{@code poll-jitter}（double，默认 0.2，测试置 0）、
 * {@code poll-initial-delay}（默认 30s，测试压到 1s）。
 * 间隔为静态配置——修改需重启生效；热读仅限外部规则/可见清单文件（L2 §6.2.1）。
 *
 * <p>Oracle：FEAT-072 测试方案 §6；2026-10-28 ECS 139 执行 PASS-D
 * （5s 轮询 32s 内 5 轮 SUCCESS——批次 6）。
 */
@Feature("FEAT-072: 动态 Skill 获取与运行时热加载")
@Tag("feat-072")
@Tag("integration")
@Tag("blackbox")
@EnabledOnOs(OS.LINUX)
@Stories(value = {
        @Story("SC-POLL-01: 周期触发生效——Hub 侧变化无手动触发即被下一轮收敛"),
        @Story("SC-POLL-02: 间隔为静态配置——按所配间隔执行轮次（修改需重启的限制记录于场景矩阵）")
})
class SkillHubPollingSitTest extends AbstractSkillHubSyncSitTest {

    private static final String POLL_INTERVAL = "4s";

    @Test
    @DisplayName("SC-POLL-01: poll 开启时 Hub 发布新版本，无需手动触发即被周期轮同步")
    void periodicPollConvergesHubChangeWithoutManualTrigger(@TempDir Path localDir) throws Exception {
        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            hub.addSkill("sit-poll-a", "SIT Poll A", "1.0.0", "MARK-POLL-A-100");

            try (RunningAgent agent = startAgent(hub, localDir, a -> {
                a.property(PREFIX + "sync.poll-enabled", "true");
                a.property(PREFIX + "sync.poll-interval", POLL_INTERVAL);
                a.property(PREFIX + "sync.poll-jitter", "0");
                a.property(PREFIX + "sync.poll-initial-delay", "1s");
            })) {
                awaitAnyCompletedReport(agent.base(), ROUND_TIMEOUT);

                hub.publishVersion("sit-poll-a", "1.0.1", "MARK-POLL-A-101", "SIT skill sit-poll-a 1.0.1");

                // 不做任何手动触发：等待周期轮把 1.0.1 收敛进报告与磁盘
                java.time.Duration window = java.time.Duration.ofSeconds(30);
                org.awaitility.Awaitility.await().atMost(window)
                        .pollInterval(java.time.Duration.ofMillis(500)).untilAsserted(() -> {
                            JsonNode report = lastCompleted(agent.base());
                            assertThat(report.path("replaced").size())
                                    .as("SC-POLL-01: 周期轮应替换到 1.0.1").isEqualTo(1);
                            assertThat(report.path("triggerSource").asText())
                                    .as("SC-POLL-01: 周期轮触发源应为 POLL").isEqualTo("POLL");
                        });
                assertThat(getReportRaw(agent.base()))
                        .as("SC-POLL-01: 报告应反映 1.0.1").contains("1.0.1");
            }
        }
    }

    @Test
    @DisplayName("SC-POLL-02: 相邻周期轮起始间隔不小于所配静态间隔（4s）")
    void periodicRoundsHonorStaticInterval(@TempDir Path localDir) throws Exception {
        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            hub.addSkill("sit-poll-b", "SIT Poll B", "1.0.0", "MARK-POLL-B-100");

            try (RunningAgent agent = startAgent(hub, localDir, a -> {
                a.property(PREFIX + "sync.poll-enabled", "true");
                a.property(PREFIX + "sync.poll-interval", POLL_INTERVAL);
                a.property(PREFIX + "sync.poll-jitter", "0");
                a.property(PREFIX + "sync.poll-initial-delay", "1s");
            })) {
                // 采集 ≥3 个周期轮的 startedAt（报告只留最近一次完成结果，滚动采集）。
                // 只统计 triggerSource=POLL 的轮次：启动轮(STARTUP)与首个周期轮的间隔
                // 由 poll-initial-delay 决定（本测试压到 1s），不属于 poll-interval 契约面。
                java.util.List<String> startedAt = new java.util.ArrayList<>();
                java.util.List<String> seen = new java.util.ArrayList<>();
                long deadline = System.currentTimeMillis() + 25_000;
                while (System.currentTimeMillis() < deadline && startedAt.size() < 3) {
                    JsonNode report = lastCompleted(agent.base());
                    String syncId = report.path("syncId").asText("");
                    if (!syncId.isEmpty() && !seen.contains(syncId)
                            && "POLL".equals(report.path("triggerSource").asText(""))) {
                        seen.add(syncId);
                        startedAt.add(report.path("startedAt").asText(""));
                    }
                    Thread.sleep(300);
                }
                assertThat(startedAt.size())
                        .as("SC-POLL-02: 25s 内应观察到至少 3 个周期轮（POLL）").isGreaterThanOrEqualTo(3);

                for (int i = 1; i < startedAt.size(); i++) {
                    Instant prev = Instant.parse(startedAt.get(i - 1));
                    Instant curr = Instant.parse(startedAt.get(i));
                    long gapSeconds = java.time.Duration.between(prev, curr).getSeconds();
                    assertThat(gapSeconds)
                            .as("SC-POLL-02: 相邻轮起始间隔应不小于配置的 4s（实际 %ds）", gapSeconds)
                            .isGreaterThanOrEqualTo(3);
                }
            }
        }
    }
}

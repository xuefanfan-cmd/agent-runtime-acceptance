package com.huawei.ascend.sit.cases.integration.skillhub;

import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-072 SC-READY-01：冷启动未就绪与恢复退避链路。
 *
 * <p>Oracle 来源：L2 §3.7（启动失败恢复：30s 起指数退避至 5m 带抖动，恢复成功即停止；
 * 与持续轮询是同一入口的临时重试，不是第二个下载循环）与 FEAT-072 测试方案 §6。
 * 2026-10-29 ECS 139 回归实测（reg8111，PR #950 基线）：Hub 不可用时实例仍可用
 * （管理面/业务面在线），RECOVERY 轮间隔 32s→65s→135s（指数退避），恢复后收敛 SUCCESS。
 *
 * <p>断言：① Hub 目录故障时启动轮 FAILED/failureStage=CATALOG，但 SUT 实例仍就绪；
 * ② RECOVERY 轮间隔 ≥30s（退避，不是高频重试）；③ Hub 恢复后下一轮 RECOVERY
 * SUCCESS、技能落盘、指标停止重试。
 */
@Feature("FEAT-072: 动态 Skill 获取与运行时热加载")
@Tag("feat-072")
@Tag("integration")
@Tag("blackbox")
@EnabledOnOs(OS.LINUX)
@Story("SC-READY-01: 冷启动无快照+Hub 不可用 → 未就绪可观察 + 恢复退避(≥30s) + 恢复后收敛")
class SkillHubNotReadySitTest extends AbstractSkillHubSyncSitTest {

    @Test
    @DisplayName("SC-READY-01: 目录故障启动 FAILED 但实例可用；恢复退避≥30s；Hub 恢复后收敛")
    void notReadyThenRecoveryWithBackoff(@TempDir Path localDir) throws Exception {
        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            hub.addSkill("sit-ready-a", "SIT Ready A", "1.0.0", "MARK-READY-A-100");
            hub.setCatalogStatus(500);

            try (RunningAgent agent = startAgent(hub, localDir, a -> { })) {
                // ① 未就绪可观察：启动轮 FAILED（failureStage=CATALOG），但实例已就绪（管理面可答）
                JsonNode failed = awaitAnyCompletedReport(agent.base(), ROUND_TIMEOUT);
                assertThat(failed.path("result").asText())
                        .as("SC-READY-01: Hub 故障时启动轮应 FAILED").isEqualTo("FAILED");
                assertThat(failed.path("failureStage").asText())
                        .as("SC-READY-01: 失败阶段应定位到 CATALOG").isEqualTo("CATALOG");
                assertThat(failed.path("committed").asBoolean())
                        .as("SC-READY-01: 失败轮不得提交").isFalse();
                assertThat(activeSkillDirCount(localDir))
                        .as("SC-READY-01: 未就绪时不得把空技能当成功落盘").isEqualTo(0);

                // ② 恢复退避：滚动采集 RECOVERY 轮 startedAt。实测退避 30s 起步（30/60/120 指数），
                // 70s 观察窗可稳定采到启动轮 + 首个恢复轮（间隔 ≥30s 即证明退避而非高频重试）
                List<Instant> starts = collectRoundStarts(agent.base(), Duration.ofSeconds(70), 2);
                assertThat(starts.size())
                        .as("SC-READY-01: 观察窗内应至少出现 2 轮（启动失败轮+首个恢复轮）")
                        .isGreaterThanOrEqualTo(2);
                for (int i = 1; i < starts.size(); i++) {
                    long gap = Duration.between(starts.get(i - 1), starts.get(i)).getSeconds();
                    assertThat(gap)
                            .as("SC-READY-01: 相邻恢复轮间隔应 ≥30s（退避，非高频重试；实际 %ds）", gap)
                            .isGreaterThanOrEqualTo(25);
                }
                assertThat(agent.log())
                        .as("SC-READY-01: 日志应包含 SkillHub API 故障告警（未就绪可观察）")
                        .containsAnyOf("SkillHub api", "skillhub", "SkillHub");

                // ③ Hub 恢复 → 下一 RECOVERY 轮 SUCCESS，技能落盘
                hub.setCatalogStatus(200);
                org.awaitility.Awaitility.await()
                        .atMost(Duration.ofSeconds(90))
                        .pollInterval(Duration.ofMillis(500))
                        .untilAsserted(() -> {
                            JsonNode recovered = lastCompleted(agent.base());
                            assertThat(recovered.path("result").asText())
                                    .as("SC-READY-01: Hub 恢复后应收敛 SUCCESS").isEqualTo("SUCCESS");
                        });
                assertThat(activeSkillDirCount(localDir))
                        .as("SC-READY-01: 恢复后技能应落盘").isEqualTo(1);
            }
        }
    }

    /** 报告只保留最近一次完成结果，滚动采集不同 syncId 的 startedAt。 */
    private List<Instant> collectRoundStarts(String base, Duration window, int minRounds)
            throws InterruptedException {
        List<Instant> starts = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        long deadline = System.currentTimeMillis() + window.toMillis();
        while (System.currentTimeMillis() < deadline && seen.size() < minRounds + 1) {
            JsonNode report = lastCompleted(base);
            String syncId = report.path("syncId").asText("");
            if (!syncId.isEmpty() && !seen.contains(syncId)) {
                seen.add(syncId);
                String startedAt = report.path("startedAt").asText("");
                if (startedAt.startsWith("2")) {
                    starts.add(Instant.parse(startedAt));
                }
            }
            Thread.sleep(300);
        }
        return starts;
    }
}

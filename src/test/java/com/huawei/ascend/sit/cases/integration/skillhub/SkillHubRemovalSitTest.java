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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-072 SC-DEL-01/SC-DEL-02：删除计划与删除守卫。
 *
 * <p>Oracle 来源：L2 §3.3（删除守卫 AC-12）与 FEAT-072 测试方案 §6；2026-10-28 ECS 139
 * 执行 PASS（批次 7/22）+ agent-solution PR #950 回归（删除复查）。
 *
 * <ul>
 *   <li>删除守卫：{@code sync.max-removals-per-sync}（默认 10）——计划删除数超限时拦截
 *       本轮全部删除，新增/更新照常提交，报告 PARTIAL + removalBlocked 逐项登记。</li>
 *   <li>删除复查（PR #950）：待删 id 在复查时仍可解析（如仅转私有、目录列表消失但
 *       artifacts 直达端点仍 200）→ 取消删除，保留本地。</li>
 * </ul>
 */
@Feature("FEAT-072: 动态 Skill 获取与运行时热加载")
@Tag("feat-072")
@Tag("integration")
@Tag("blackbox")
@EnabledOnOs(OS.LINUX)
@Stories(value = {
        @Story("SC-DEL-01: 删除下线 + 守卫——单技能删除成功；12>10 时本轮删除全拦、新增照常、removalBlocked"),
        @Story("SC-DEL-02: 删除复查——待删技能复查仍可解析（转私有）→ 取消删除保留本地")
})
class SkillHubRemovalSitTest extends AbstractSkillHubSyncSitTest {

    @Test
    @DisplayName("SC-DEL-01(常规): 技能从 Hub 消失 → removed=1，磁盘与 manifest 收敛")
    void removedSkillIsUninstalled(@TempDir Path localDir) throws Exception {
        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            hub.addSkill("sit-del-a", "SIT Del A", "1.0.0", "MARK-DEL-A-100");
            hub.addSkill("sit-del-keep", "SIT Del Keep", "1.0.0", "MARK-DEL-KEEP-100");

            try (RunningAgent agent = startAgent(hub, localDir, a -> { })) {
                awaitAnyCompletedReport(agent.base(), ROUND_TIMEOUT);
                assertThat(activeSkillDirCount(localDir)).as("前置：两个技能已落盘").isEqualTo(2);

                hub.removeSkill("sit-del-a");
                SyncResponse trigger = postSync(agent.base());
                JsonNode report = awaitCompletedReport(agent.base(), trigger.json().path("syncId").asText(),
                        ROUND_TIMEOUT);

                assertThat(report.path("result").asText())
                        .as("SC-DEL-01: 删除轮应 SUCCESS").isEqualTo("SUCCESS");
                assertThat(report.path("removed").size())
                        .as("SC-DEL-01: 应记 removed=1").isEqualTo(1);
                assertThat(getReportRaw(agent.base()))
                        .as("SC-DEL-01: 删除项应指向 sit-del-a").contains("sit-del-a");
                assertThat(activeSkillDirCount(localDir))
                        .as("SC-DEL-01: 被删技能应从 active 树移除").isEqualTo(1);
                assertThat(manifestGenerationAndRecords(localDir)[1])
                        .as("SC-DEL-01: manifest 记录同步收敛为 1").isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("SC-DEL-01(守卫): 计划删除 12 > 上限 10 → 本轮删除全拦，新增照常提交，removalBlocked")
    void removalGuardBlocksOversizedRemovalBatch(@TempDir Path localDir) throws Exception {
        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            for (int i = 1; i <= 12; i++) {
                hub.addSkill("sit-del-g" + i, "SIT Del Guard " + i, "1.0.0", "MARK-DEL-G" + i + "-100");
            }

            try (RunningAgent agent = startAgent(hub, localDir, a -> { })) {
                awaitAnyCompletedReport(agent.base(), ROUND_TIMEOUT);
                assertThat(activeSkillDirCount(localDir)).as("前置：12 个技能已落盘").isEqualTo(12);

                for (int i = 1; i <= 12; i++) {
                    hub.removeSkill("sit-del-g" + i);
                }
                hub.addSkill("sit-del-new", "SIT Del New", "1.0.0", "MARK-DEL-NEW-100");

                SyncResponse trigger = postSync(agent.base());
                JsonNode report = awaitCompletedReport(agent.base(), trigger.json().path("syncId").asText(),
                        ROUND_TIMEOUT);

                assertThat(report.path("result").asText())
                        .as("SC-DEL-01: 守卫触发轮应为 PARTIAL 而非 SUCCESS").isEqualTo("PARTIAL");
                assertThat(report.path("removed").size())
                        .as("SC-DEL-01: 超限时本轮删除应全部拦截").isEqualTo(0);
                assertThat(report.path("removalBlocked").size())
                        .as("SC-DEL-01: 被拦的 12 个待删项应逐项登记").isEqualTo(12);
                assertThat(report.path("added").size())
                        .as("SC-DEL-01: 守卫只拦删除——新增应照常提交").isEqualTo(1);
                assertThat(report.path("committed").asBoolean())
                        .as("SC-DEL-01: 新增部分应已提交").isTrue();
                assertThat(activeSkillDirCount(localDir))
                        .as("SC-DEL-01: 磁盘上 12 旧技能保留 + 1 新技能落地").isEqualTo(13);
            }
        }
    }

    @Test
    @DisplayName("SC-DEL-02: 技能转私有后复查仍可解析 → 取消删除，本地保留")
    void removalRecheckCancelsDeleteWhenStillResolvable(@TempDir Path localDir) throws Exception {
        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            hub.addSkill("sit-del-r", "SIT Del Recheck", "1.0.0", "MARK-DEL-R-100");

            try (RunningAgent agent = startAgent(hub, localDir, a -> { })) {
                awaitAnyCompletedReport(agent.base(), ROUND_TIMEOUT);
                assertThat(activeSkillDirCount(localDir)).as("前置：技能已落盘").isEqualTo(1);

                // 转私有：目录列表消失（进入删除计划），但 artifacts 直达端点复查仍可解析
                hub.setVisibility("sit-del-r", SkillHubSyncMockServer.Visibility.PRIVATE);
                SyncResponse trigger = postSync(agent.base());
                JsonNode report = awaitCompletedReport(agent.base(), trigger.json().path("syncId").asText(),
                        ROUND_TIMEOUT);

                assertThat(report.path("removed").size())
                        .as("SC-DEL-02: 复查可解析时不应删除").isEqualTo(0);
                assertThat(activeSkillDirCount(localDir))
                        .as("SC-DEL-02: 本地技能应保留").isEqualTo(1);
                assertThat(manifestGenerationAndRecords(localDir)[1])
                        .as("SC-DEL-02: manifest 记录保留").isEqualTo(1);
            }
        }
    }
}

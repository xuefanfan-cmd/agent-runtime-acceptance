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
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-072 SC-CRASH-01/02：进程崩溃恢复（AC-14/15）。
 *
 * <p>Oracle 来源：L2 §3.6（崩溃恢复：重启按 commitId 恢复完整旧态或新态，
 * 不服务半状态；commit 后 trash 清理失败不回滚已提交）与 FEAT-072 测试方案 §6；
 * 2026-10-28 ECS 139 执行 PASS（批次 7）+ agent-solution PR #958 自带
 * {@code SkillManifestStoreTest}（FaultInjector 按 kill -9 精确到目录移动/manifest
 * 替换位置注入，PR-佐证——本黑盒类的进程级注入以受管进程强停近似）。
 */
@Feature("FEAT-072: 动态 Skill 获取与运行时热加载")
@Tag("feat-072")
@Tag("integration")
@Tag("blackbox")
@EnabledOnOs(OS.LINUX)
@Stories(value = {
        @Story("SC-CRASH-01: 在途轮进程死亡 → 重启恢复到完整新态，不出现半状态"),
        @Story("SC-CRASH-02: 提交后重启 → 幂等收敛（unchanged、零重下载、generation 不变）")
})
class SkillHubCrashRecoverySitTest extends AbstractSkillHubSyncSitTest {

    @Test
    @DisplayName("SC-CRASH-01: 同步轮进行中进程死亡 → 重启后恢复完整状态（磁盘=manifest=报告）")
    void crashDuringRoundRecoversToConsistentState(@TempDir Path localDir) throws Exception {
        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            hub.addSkill("sit-crash-a", "SIT Crash A", "1.0.1", "MARK-CRASH-A-101");

            try (RunningAgent agent = startAgent(hub, localDir, a -> { })) {
                JsonNode first = awaitAnyCompletedReport(agent.base(), ROUND_TIMEOUT);
                int generation = first.path("generation").asInt();
                assertThat(activeSkillDirCount(localDir)).as("前置：旧态已提交（1 技能）").isEqualTo(1);

                // 制造在途轮：触发升级同步，并把目录请求挂起在 Hub 一侧
                CountDownLatch stall = new CountDownLatch(1);
                hub.publishVersion("sit-crash-a", "1.0.2", "MARK-CRASH-A-102", "SIT skill sit-crash-a 1.0.2");
                hub.stallCatalog(stall);
                SyncResponse trigger = postSync(agent.base());
                assertThat(trigger.status()).as("在途轮应已受理").isEqualTo(202);
                Thread.sleep(1_000);

                // 轮未提交时进程死亡（受管进程强停近似 kill -9）
                agent.stack().stop(AGENT);
                stall.countDown();
                hub.clearCatalogStall();

                // 重启（同端口复活）→ 恢复轮应把状态收敛到完整新态
                agent.stack().start(AGENT);
                JsonNode recovered = awaitAnyCompletedReport(agent.base(), ROUND_TIMEOUT);

                assertThat(recovered.path("result").asText())
                        .as("SC-CRASH-01: 重启恢复轮应 SUCCESS").isEqualTo("SUCCESS");
                assertThat(getReportRaw(agent.base()))
                        .as("SC-CRASH-01: 恢复轮应收敛到新版本 1.0.2").contains("1.0.2");

                // 完整状态（非半状态）：磁盘 = manifest = 报告 installedCount
                long disk = activeSkillDirCount(localDir);
                int[] manifest = manifestGenerationAndRecords(localDir);
                assertThat(disk).as("SC-CRASH-01: 磁盘恰一个技能目录").isEqualTo(1);
                assertThat(manifest).as("SC-CRASH-01: manifest 存在").isNotNull();
                assertThat(manifest[1]).as("SC-CRASH-01: manifest 记录数与磁盘一致").isEqualTo((int) disk);
                assertThat(recovered.path("installedCount").asInt(-1))
                        .as("SC-CRASH-01: 报告已装数与磁盘一致").isEqualTo((int) disk);
                assertThat(manifest[0]).as("SC-CRASH-01: generation 单调不回退").isGreaterThanOrEqualTo(generation);
            }
        }
    }

    @Test
    @DisplayName("SC-CRASH-02: 提交完成后重启 → 幂等（unchanged、零重下载、generation 不变）")
    void restartAfterCommitIsIdempotent(@TempDir Path localDir) throws Exception {
        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            hub.addSkill("sit-crash-b", "SIT Crash B", "1.0.0", "MARK-CRASH-B-100");

            try (RunningAgent agent = startAgent(hub, localDir, a -> { })) {
                JsonNode first = awaitAnyCompletedReport(agent.base(), ROUND_TIMEOUT);
                int generation = first.path("generation").asInt();
                int downloads = hub.downloadRequests();
                assertThat(first.path("result").asText()).as("前置：首轮已提交").isEqualTo("SUCCESS");

                agent.stack().stop(AGENT);
                agent.stack().start(AGENT);

                JsonNode second = awaitAnyCompletedReport(agent.base(), ROUND_TIMEOUT);
                assertThat(second.path("result").asText())
                        .as("SC-CRASH-02: 重启轮应 SUCCESS").isEqualTo("SUCCESS");
                assertThat(second.path("unchanged").size())
                        .as("SC-CRASH-02: 已提交内容应 unchanged").isEqualTo(1);
                assertThat(second.path("generation").asInt())
                        .as("SC-CRASH-02: 幂等重启不推进 generation").isEqualTo(generation);
                assertThat(hub.downloadRequests())
                        .as("SC-CRASH-02: 幂等重启不重下载技能包").isEqualTo(downloads);
                assertThat(activeSkillDirCount(localDir))
                        .as("SC-CRASH-02: 磁盘状态不变").isEqualTo(1);
            }
        }
    }
}

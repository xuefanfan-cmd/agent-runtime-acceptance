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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-072 SC-MIGR-01：存量（legacy）布局清点。
 *
 * <p>Oracle 来源：L2 §3.8（AC-25 存量迁移：非破坏清点——不预搬不搬空、未知归属保留；
 * 接管成功后切扫描根）与 FEAT-072 测试方案 §6；2026-10-28 ECS 139 执行 PASS
 * （批次 7，报告/manifest 记 UNKNOWN_PRESERVED）+ agent-solution PR #958 自带
 * {@code LegacyLayoutMigratorTest}（迁移全链路，PR-佐证）。
 *
 * <p>本黑盒类承接判定点：旧布局 {@code {localDir}/{assetId}/}（FEAT-005 直铺形态）
 * 在新链路首同步后被非破坏清点——目录仍在原位（未被搬走/清空/覆盖），新技能进入
 * 受管 {@code .skillhub/active} 树；两棵树不互相污染。
 */
@Feature("FEAT-072: 动态 Skill 获取与运行时热加载")
@Tag("feat-072")
@Tag("integration")
@Tag("blackbox")
@Story("SC-MIGR-01: 旧布局非破坏清点——未知归属保留原位（UNKNOWN_PRESERVED），新链路接管不搬不删")
class SkillHubLegacyLayoutSitTest extends AbstractSkillHubSyncSitTest {

    @Test
    @DisplayName("SC-MIGR-01: 旧布局技能目录在首同步后被清点保留，新技能落 .skillhub/active")
    void legacyLayoutIsInventoriedNonDestructively(@TempDir Path localDir) throws Exception {
        // 旧（FEAT-005）布局：localDir 根下直铺技能目录
        Path legacySkill = localDir.resolve("legacy-ghost");
        Files.createDirectories(legacySkill);
        Files.writeString(legacySkill.resolve("SKILL.md"),
                "---\nname: legacy-ghost\ndescription: legacy skill kept in place\n---\n"
                        + "# legacy-ghost\nReply with the marker `MARK-LEGACY-GHOST`.\n",
                StandardCharsets.UTF_8);
        String legacyContent = Files.readString(legacySkill.resolve("SKILL.md"), StandardCharsets.UTF_8);

        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            hub.addSkill("sit-migr-a", "SIT Migr A", "1.0.0", "MARK-MIGR-A-100");

            try (RunningAgent agent = startAgent(hub, localDir, a -> { })) {
                JsonNode report = awaitAnyCompletedReport(agent.base(), ROUND_TIMEOUT);

                assertThat(report.path("result").asText())
                        .as("SC-MIGR-01: 存量布局存在时同步应正常 SUCCESS").isEqualTo("SUCCESS");

                // 非破坏：未知归属（Hub 无对应资产）的旧目录保留原位、内容不变
                assertThat(legacySkill).as("SC-MIGR-01: 旧布局目录不得被搬走/删除").exists();
                assertThat(Files.readString(legacySkill.resolve("SKILL.md"), StandardCharsets.UTF_8))
                        .as("SC-MIGR-01: 旧技能内容不得被改写").isEqualTo(legacyContent);

                // 新链路接管：新技能进受管 .skillhub/active 树（两层 directoryKey 布局），不与旧目录混铺
                List<Path> managed = activeSkillPaths(localDir);
                assertThat(managed)
                        .as("SC-MIGR-01: 新技能应落受管 active 树").hasSize(1);
                assertThat(Files.readString(managed.get(0).resolve("SKILL.md"), StandardCharsets.UTF_8))
                        .as("SC-MIGR-01: 受管树内容应为新技能（marker 落地）")
                        .contains("MARK-MIGR-A-100");

                // 重启后幂等：旧目录仍保留，受管树不重复安装
                agent.stack().stop(AGENT);
                agent.stack().start(AGENT);
                JsonNode second = awaitAnyCompletedReport(agent.base(), ROUND_TIMEOUT);
                assertThat(second.path("unchanged").size())
                        .as("SC-MIGR-01: 重启后受管技能应 unchanged（不重复安装）").isEqualTo(1);
                assertThat(legacySkill).as("SC-MIGR-01: 重启后旧目录仍保留").exists();
            }
        }
    }
}

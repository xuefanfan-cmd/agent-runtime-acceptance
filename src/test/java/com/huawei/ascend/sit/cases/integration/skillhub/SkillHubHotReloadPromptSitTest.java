package com.huawei.ascend.sit.cases.integration.skillhub;

import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-072 SC-REPL-01：运行时热加载——提示词层证据。
 *
 * <p>Oracle 来源：FEAT-072 测试方案 §6；2026-10-27/28 ECS 139 执行（批次 2/5）。
 * 报告/磁盘层的版本替换由 {@code SkillHubVersionRulesSitTest}（SC-VER-02）承接，本类断言
 * 端到端最后一公里的两端：提示词层——技能注册项（名称/路径）在热替换前后持续在场
 * （不因替换出现提示词空窗）；磁盘层——升级后 active 树里的 SKILL.md 已是新内容，
 * 且目录路径不漂移（热替换而非卸载重装）。
 *
 * <p>提示词探针口径（与手工批次 5/20 一致的"不要调用工具"手法）：本 SUT 的提示词只注入
 * 技能注册项，SKILL.md 内容按需经 read_file 工具读取，而 demo 未接线该工具
 * （SUT 日志 {@code react_skill_read_tool_missing available_tool_count=0}，批次 2 的
 * q102.json 手工实测同现象——模型只能输出工具调用文本）。故"新 marker 出现在回答中"
 * 在此 SUT 不可达，新内容以磁盘断言承接。
 *
 * <p>提示词断言依赖真实 LLM（{@code LLM_API_KEY}）——未配置时跳过（场景矩阵标注 LLM 门控）。
 */
@Feature("FEAT-072: 动态 Skill 获取与运行时热加载")
@Tag("feat-072")
@Tag("integration")
@Tag("blackbox")
@EnabledOnOs(OS.LINUX)
@Story("SC-REPL-01: 热加载端到端——提示词技能注册项持续在场，磁盘新内容落地、路径不漂移")
class SkillHubHotReloadPromptSitTest extends AbstractSkillHubSyncSitTest {

    @Test
    @DisplayName("SC-REPL-01: 1.0.1→1.0.2 升级后，提示词技能不中断、SKILL.md 换新 marker、路径不漂移")
    void hotReloadReachesPromptAfterUpgrade(@TempDir Path localDir) throws Exception {
        Assumptions.assumeTrue(realLlmConfigured(), "SC-REPL-01 需要 LLM_API_KEY（提示词探针）");

        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            hub.addSkill("sit-hot-skill", "SIT Hot Skill", "1.0.1", "MARK-HOT-OLD-101");

            try (RunningAgent agent = startAgent(hub, localDir, a -> { })) {
                awaitAnyCompletedReport(agent.base(), ROUND_TIMEOUT);
                // 受管树是两层布局 active/<directoryKey>/<localName>/（SkillPathSafety 命名）
                Path skillDir = activeSkillPaths(localDir).get(0);
                assertThat(skillDir).as("前置：技能已落盘").exists();

                QueryResponse before = postQuery(agent.base(), "hot-reload-1",
                        "不要调用任何工具，直接回答：你现在有哪些技能？说出技能名字即可。");
                assertThat(before.status()).as("升级前查询应 200").isEqualTo(200);
                assertThat(before.content())
                        .as("SC-REPL-01: 升级前提示词应携带技能注册项（技能名可见）")
                        .contains("sit-hot-skill");

                hub.publishVersion("sit-hot-skill", "1.0.2", "MARK-HOT-NEW-102", "SIT skill sit-hot-skill 1.0.2");
                SyncResponse trigger = postSync(agent.base());
                JsonNode report = awaitCompletedReport(agent.base(), trigger.json().path("syncId").asText(),
                        ROUND_TIMEOUT);
                assertThat(report.path("replaced").size())
                        .as("前置：报告已替换到 1.0.2").isEqualTo(1);

                QueryResponse after = postQuery(agent.base(), "hot-reload-2",
                        "不要调用任何工具，直接回答：你现在有哪些技能？说出技能名字即可。");
                assertThat(after.status()).as("SC-REPL-01: 升级后查询应 200").isEqualTo(200);
                assertThat(after.content())
                        .as("SC-REPL-01: 热替换后提示词技能注册项应持续在场（无空窗）")
                        .contains("sit-hot-skill");

                // 内容层：热替换后 active 树里的 SKILL.md 已是新版本（新 marker、旧 marker 不残留）
                assertThat(Files.readString(skillDir.resolve("SKILL.md"), StandardCharsets.UTF_8))
                        .as("SC-REPL-01: 热加载后磁盘内容应切换为新版 marker")
                        .contains("MARK-HOT-NEW-102")
                        .doesNotContain("MARK-HOT-OLD-101");

                assertThat(skillDir)
                        .as("SC-REPL-01: 热替换后技能目录路径不漂移（同 directoryKey）").exists();
                assertThat(activeSkillPaths(localDir))
                        .as("SC-REPL-01: 升级不产生第二个目录（替换而非旁挂）").hasSize(1);
            }
        }
    }
}

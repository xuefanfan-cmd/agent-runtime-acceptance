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

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-072 SC-REPL-01：运行时热加载——提示词层证据。
 *
 * <p>Oracle 来源：FEAT-072 测试方案 §6；2026-10-27/28 ECS 129 执行 PASS
 * （批次 5：版本升级后新内容到达提示词、路径稳定）。报告/磁盘层的版本替换由
 * {@code SkillHubVersionRulesSitTest}（SC-VER-02）承接，本类只断言端到端最后一公里：
 * 同一技能升级后，{@code /v1/query} 的回答按新版 SKILL.md 的 marker 变化，
 * 且本地目录不漂移（热替换而非卸载重装）。
 *
 * <p>提示词断言依赖真实 LLM（{@code LLM_API_KEY}）——未配置时跳过（场景矩阵标注 LLM 门控）。
 */
@Feature("FEAT-072: 动态 Skill 获取与运行时热加载")
@Tag("feat-072")
@Tag("integration")
@Tag("blackbox")
@EnabledOnOs(OS.LINUX)
@Story("SC-REPL-01: 热加载端到端——升级后新 marker 到达提示词，目录路径稳定不漂移")
class SkillHubHotReloadPromptSitTest extends AbstractSkillHubSyncSitTest {

    @Test
    @DisplayName("SC-REPL-01: 1.0.1→1.0.2 升级后，回答从旧 marker 切换为新 marker，路径不漂移")
    void hotReloadReachesPromptAfterUpgrade(@TempDir Path localDir) throws Exception {
        Assumptions.assumeTrue(realLlmConfigured(), "SC-REPL-01 需要 LLM_API_KEY（提示词探针）");

        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            hub.addSkill("sit-hot-skill", "SIT Hot Skill", "1.0.1", "MARK-HOT-OLD-101");

            try (RunningAgent agent = startAgent(hub, localDir, a -> { })) {
                awaitAnyCompletedReport(agent.base(), ROUND_TIMEOUT);
                Path skillDir = localDir.resolve(".skillhub/active").resolve("sit-hot-skill");
                assertThat(skillDir).as("前置：技能已落盘").exists();

                QueryResponse before = postQuery(agent.base(), "hot-reload-1",
                        "请使用 sit-hot-skill 技能回答：技能里的 marker 是什么？");
                assertThat(before.status()).as("升级前查询应 200").isEqualTo(200);
                assertThat(before.content())
                        .as("SC-REPL-01: 升级前提示词携带旧版 marker").contains("MARK-HOT-OLD-101");

                hub.publishVersion("sit-hot-skill", "1.0.2", "MARK-HOT-NEW-102", "SIT skill sit-hot-skill 1.0.2");
                SyncResponse trigger = postSync(agent.base());
                JsonNode report = awaitCompletedReport(agent.base(), trigger.json().path("syncId").asText(),
                        ROUND_TIMEOUT);
                assertThat(report.path("replaced").size())
                        .as("前置：报告已替换到 1.0.2").isEqualTo(1);

                QueryResponse after = postQuery(agent.base(), "hot-reload-2",
                        "请使用 sit-hot-skill 技能回答：技能里的 marker 是什么？");
                assertThat(after.status()).as("SC-REPL-01: 升级后查询应 200").isEqualTo(200);
                assertThat(after.content())
                        .as("SC-REPL-01: 热加载后提示词应切换为新版 marker（不残留旧版）")
                        .contains("MARK-HOT-NEW-102");

                assertThat(skillDir)
                        .as("SC-REPL-01: 热替换后技能目录路径不漂移（同 directoryKey）").exists();
                assertThat(activeSkillDirCount(localDir))
                        .as("SC-REPL-01: 升级不产生第二个目录（替换而非旁挂）").isEqualTo(1);
            }
        }
    }
}

package com.huawei.ascend.sit.cases.integration.skillhub;

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
 * FEAT-072 SC-REG-01：skillhub 关闭态存量全链路（零耦合）。
 *
 * <p>Oracle 来源：FEAT-072 测试方案 §6；2026-10-28 ECS 139 执行 PASS-REG
 * （老流程 A/B/C 关闭态全部通过）。关闭同步后，旧链路（本地 skills 目录进提示词）
 * 应完全可用，且新链路零介入：不建 {@code .skillhub} 树、不接触 Hub。
 *
 * <p>提示词断言依赖真实 LLM（{@code LLM_API_KEY} 环境变量）——未配置时本用例跳过
 * （跳过≠通过，场景矩阵标注 LLM 门控）。
 */
@Feature("FEAT-072: 动态 Skill 获取与运行时热加载")
@Tag("feat-072")
@Tag("integration")
@Tag("blackbox")
@EnabledOnOs(OS.LINUX)
@Story("SC-REG-01: 关闭态存量全链路——本地技能进提示词可答，零 Hub 接触、零 .skillhub 介入")
class SkillHubDisabledLegacySitTest extends AbstractSkillHubSyncSitTest {

    @Test
    @DisplayName("SC-REG-01: enabled=false 时本地 legacy 技能经提示词生效，新链路零介入")
    void legacySkillPathUsableWhenDisabled(@TempDir Path localDir) throws Exception {
        Assumptions.assumeTrue(realLlmConfigured(), "SC-REG-01 需要 LLM_API_KEY（提示词探针）");

        Path legacySkill = localDir.resolve("legacy-reg-skill");
        Files.createDirectories(legacySkill);
        Files.writeString(legacySkill.resolve("SKILL.md"),
                "---\nname: legacy-reg-skill\ndescription: legacy regression skill\n---\n"
                        + "# legacy-reg-skill\nRead this before answering. "
                        + "Reply exactly with the marker `MARK-REG-LEGACY-777`.\n",
                StandardCharsets.UTF_8);

        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            try (RunningAgent agent = startAgent(hub, localDir,
                    a -> a.property(PREFIX + "enabled", "false"))) {
                Thread.sleep(3_000);

                QueryResponse answer = postQuery(agent.base(), "reg-legacy-1",
                        "请使用 legacy-reg-skill 技能回答：技能里的 marker 是什么？");
                assertThat(answer.status())
                        .as("SC-REG-01: 关闭态业务查询应 200").isEqualTo(200);
                assertThat(answer.content())
                        .as("SC-REG-01: 旧链路本地技能应进入提示词（marker 出现在回答中）")
                        .contains("MARK-REG-LEGACY-777");

                assertThat(hub.audits())
                        .as("SC-REG-01: 关闭态零 Hub 接触").isEmpty();
                assertThat(localDir.resolve(".skillhub"))
                        .as("SC-REG-01: 关闭态不应创建受管树").doesNotExist();
            }
        }
    }
}

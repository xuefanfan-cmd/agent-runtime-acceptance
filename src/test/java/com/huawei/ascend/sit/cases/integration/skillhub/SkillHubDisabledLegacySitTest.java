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
 * FEAT-072 SC-REG-01：skillhub 关闭态存量业务全链路（零耦合）。
 *
 * <p>Oracle 来源：FEAT-072 测试方案 §5.23 老流程回归 A 案；2026-10-28 ECS 139 执行
 * PASS-REG（8093 实例 SKILLHUB_ENABLED=false + 死端点）。判定点（对齐实测口径）：
 * 关闭同步后业务面照常可用（阻塞对话 200、同 conversation 续轮上下文保持）、
 * 技能问答回"无技能"（优雅空，不 500 不挂起）、{@code /manage/skillhub/*} 不装配（404）、
 * 对 Hub 零连接、不建 {@code .skillhub} 树、磁盘上预置的 legacy 技能目录不被消费也不被动。
 *
 * <p>注：本 SUT（skillhub-runtime-demo）的技能只经 SkillHubManager 注册进提示词，没有
 * 本地 legacy 扫描器——关闭态即优雅无技能，"本地 skills 目录直进提示词"是老版（FEAT-005
 * 基线 demo）链路，不在本 SUT 断言面（L2 §3.8 的存量布局属磁盘清点语义，见 SC-MIGR-01）。
 *
 * <p>提示词断言依赖真实 LLM（{@code LLM_API_KEY}）——未配置时本用例跳过
 * （跳过≠通过，场景矩阵标注 LLM 门控）。
 */
@Feature("FEAT-072: 动态 Skill 获取与运行时热加载")
@Tag("feat-072")
@Tag("integration")
@Tag("blackbox")
@EnabledOnOs(OS.LINUX)
@Story("SC-REG-01: 关闭态业务照常+优雅无技能——上下文保持、管理面 404、零 Hub 接触、零 .skillhub 介入")
class SkillHubDisabledLegacySitTest extends AbstractSkillHubSyncSitTest {

    @Test
    @DisplayName("SC-REG-01: enabled=false 时业务对话/上下文/技能问答优雅可用，管理面 404、零 Hub 接触")
    void disabledStateBusinessAsUsualWithZeroHubContact(@TempDir Path localDir) throws Exception {
        Assumptions.assumeTrue(realLlmConfigured(), "SC-REG-01 需要 LLM_API_KEY（提示词探针）");

        // 预置 legacy 布局技能目录：验证关闭态零介入（不被消费、不被改写、不建受管树）
        Path legacySkill = localDir.resolve("legacy-reg-skill");
        Files.createDirectories(legacySkill);
        Files.writeString(legacySkill.resolve("SKILL.md"),
                "---\nname: legacy-reg-skill\ndescription: legacy regression skill\n---\n"
                        + "# legacy-reg-skill\nRead this before answering. "
                        + "Reply exactly with the marker `MARK-REG-LEGACY-777`.\n",
                StandardCharsets.UTF_8);
        String legacyContent = Files.readString(legacySkill.resolve("SKILL.md"), StandardCharsets.UTF_8);

        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            try (RunningAgent agent = startAgent(hub, localDir,
                    a -> a.property(PREFIX + "enabled", "false"))) {
                Thread.sleep(3_000);

                // ① 业务面照常：阻塞对话 200，同 conversation 续轮上下文保持（A 案"紫珊瑚42"口径）
                QueryResponse remember = postQuery(agent.base(), "reg-legacy-1",
                        "请记住暗号紫珊瑚42，稍后我会问你。");
                assertThat(remember.status())
                        .as("SC-REG-01: 关闭态阻塞对话应 200").isEqualTo(200);
                QueryResponse recall = postQuery(agent.base(), "reg-legacy-1",
                        "刚才我请你记住的暗号是什么？直接回答暗号本身。");
                assertThat(recall.status())
                        .as("SC-REG-01: 同 conversation 续轮应 200").isEqualTo(200);
                assertThat(recall.content())
                        .as("SC-REG-01: 关闭态上下文应保持（暗号可召回）")
                        .contains("紫珊瑚42");

                // ② 技能问答优雅空：不 500 不挂起，且 legacy 技能内容不进提示词（零介入）
                QueryResponse skillQuery = postQuery(agent.base(), "reg-legacy-2",
                        "请使用 legacy-reg-skill 技能回答：技能里的 marker 是什么？");
                assertThat(skillQuery.status())
                        .as("SC-REG-01: 关闭态技能问答应 200（优雅空，非 500/挂起）").isEqualTo(200);
                assertThat(skillQuery.content())
                        .as("SC-REG-01: legacy 技能内容不得进入提示词（新链路零介入）")
                        .doesNotContain("MARK-REG-LEGACY-777");

                // ③ 管理面不装配：关闭态管理端点应 404
                SyncResponse manage = postSyncOnce(agent.base());
                assertThat(manage.status())
                        .as("SC-REG-01: 关闭态 /manage/skillhub/sync 应 404（端点不装配）")
                        .isEqualTo(404);

                // ④ 零 Hub 接触、零 .skillhub 介入、legacy 目录原样
                assertThat(hub.audits())
                        .as("SC-REG-01: 关闭态零 Hub 接触").isEmpty();
                assertThat(localDir.resolve(".skillhub"))
                        .as("SC-REG-01: 关闭态不应创建受管树").doesNotExist();
                assertThat(Files.readString(legacySkill.resolve("SKILL.md"), StandardCharsets.UTF_8))
                        .as("SC-REG-01: legacy 技能内容不得被改写").isEqualTo(legacyContent);
            }
        }
    }
}

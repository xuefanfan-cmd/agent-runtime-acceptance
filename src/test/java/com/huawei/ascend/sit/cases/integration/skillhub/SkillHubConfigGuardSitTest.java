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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-072 配置守卫（SC-FAIL 配置类失败面，agent-solution #521 BUG-4 修复回归）。
 *
 * <p>Oracle 来源：L2 §6.4（规则加载失败语义）与 2026-10-29 ECS 139 回归实测
 * （reg8112/reg8113，PR #958 基线，样本存档于执行记录）：非法规则文件
 * （顶层非列表）→ 轮次 FAILED、failureStage=CONFIG、
 * failureReason=CONFIG_INVALID_VERSION_RULES / CONFIG_INVALID_VISIBLE_RULES，
 * 不得笼统记 ROUND_FAILED；空文件/空列表语义为无 pin、全部 latest。
 */
@Feature("FEAT-072: 动态 Skill 获取与运行时热加载")
@Tag("feat-072")
@Tag("integration")
@Tag("blackbox")
@EnabledOnOs(OS.LINUX)
@Stories(value = {
        @Story("SC-FAIL(BUG-4): 非法 version-rules → FAILED/CONFIG/CONFIG_INVALID_VERSION_RULES"),
        @Story("SC-FAIL(BUG-4b): 非法 visible-skills → FAILED/CONFIG/CONFIG_INVALID_VISIBLE_RULES"),
        @Story("SC-VER(空规则): 空规则文件 = 无 pin，全部按 latest 安装")
})
class SkillHubConfigGuardSitTest extends AbstractSkillHubSyncSitTest {

    @Test
    @DisplayName("配置守卫: version-rules-file 顶层非列表 → CONFIG_INVALID_VERSION_RULES 定位失败")
    void invalidVersionRulesFileFailsAtConfigStage(@TempDir Path localDir) throws Exception {
        Path badRules = writeFile(localDir, "bad-rules.yaml", "not-a-list: true\n");

        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            hub.addSkill("sit-cfg-a", "SIT Cfg A", "1.0.0", "MARK-CFG-A-100");

            try (RunningAgent agent = startAgent(hub, localDir,
                    a -> a.property(PREFIX + "sync.version-rules-file", badRules.toString()))) {
                JsonNode report = awaitAnyCompletedReport(agent.base(), ROUND_TIMEOUT);

                assertThat(report.path("result").asText())
                        .as("非法规则文件：轮次应 FAILED").isEqualTo("FAILED");
                assertThat(report.path("failureStage").asText())
                        .as("失败阶段应定位到 CONFIG 而非笼统 ROUND").isEqualTo("CONFIG");
                assertThat(report.path("failureReason").asText())
                        .as("失败原因应为 CONFIG_INVALID_VERSION_RULES")
                        .isEqualTo("CONFIG_INVALID_VERSION_RULES");
                assertThat(report.path("failureDetail").asText())
                        .as("失败详情应携带规则文件路径").contains("bad-rules.yaml");
                assertThat(report.path("committed").asBoolean())
                        .as("配置失败轮不得提交").isFalse();
                assertThat(activeSkillDirCount(localDir))
                        .as("配置失败时不得安装任何技能").isEqualTo(0);
            }
        }
    }

    @Test
    @DisplayName("配置守卫: visible-skills-file 顶层非列表 → CONFIG_INVALID_VISIBLE_RULES")
    void invalidVisibleSkillsFileFailsAtConfigStage(@TempDir Path localDir) throws Exception {
        Path badVisible = writeFile(localDir, "bad-visible.yaml", "visible: not-a-list\n");

        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            hub.addSkill("sit-cfg-b", "SIT Cfg B", "1.0.0", "MARK-CFG-B-100");

            try (RunningAgent agent = startAgent(hub, localDir,
                    a -> a.property(PREFIX + "visible-skills-file", badVisible.toString()))) {
                JsonNode report = awaitAnyCompletedReport(agent.base(), ROUND_TIMEOUT);

                assertThat(report.path("result").asText())
                        .as("非法可见清单：轮次应 FAILED").isEqualTo("FAILED");
                assertThat(report.path("failureStage").asText())
                        .as("失败阶段应定位到 CONFIG").isEqualTo("CONFIG");
                assertThat(report.path("failureReason").asText())
                        .as("失败原因应为 CONFIG_INVALID_VISIBLE_RULES")
                        .isEqualTo("CONFIG_INVALID_VISIBLE_RULES");
                assertThat(report.path("committed").asBoolean())
                        .as("配置失败轮不得提交").isFalse();
            }
        }
    }

    @Test
    @DisplayName("空规则文件语义: 空 version-rules-file = 无 pin，全部按 latest")
    void emptyRulesFileMeansNoPin(@TempDir Path localDir) throws Exception {
        Path emptyRules = writeFile(localDir, "empty-rules.yaml", "");

        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            hub.addSkill("sit-cfg-c", "SIT Cfg C", "1.0.0", "MARK-CFG-C-100");

            try (RunningAgent agent = startAgent(hub, localDir,
                    a -> a.property(PREFIX + "sync.version-rules-file", emptyRules.toString()))) {
                JsonNode report = awaitAnyCompletedReport(agent.base(), ROUND_TIMEOUT);

                assertThat(report.path("result").asText())
                        .as("空规则文件不应失败（语义=无 pin）").isEqualTo("SUCCESS");
                assertThat(report.path("added").size())
                        .as("空规则下应按 latest 全量安装").isEqualTo(1);
                assertThat(activeSkillDirCount(localDir)).isEqualTo(1);
            }
        }
    }

    private static Path writeFile(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}

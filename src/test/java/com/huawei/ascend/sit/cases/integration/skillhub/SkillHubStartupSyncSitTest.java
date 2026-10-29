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
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-072 SC-ADD-01/SC-ADD-05：启动期全量同步。
 *
 * <p>Oracle 来源：FEAT-072 测试方案 §6 SC-ADD-01（启动批量下载，全部成功）与
 * SC-ADD-05（“按默认最新安装”事后可见标记），2026-10-27/29 ECS 139 手工执行 PASS
 * （证据见 docs/cases/FEAT-072-*.md 场景矩阵）。断言面：同步报告字段
 * （result/committed/added/generation/items 的 previousVersion/targetVersion/appliedVersion/
 * changeReason/validationMode）、`{localDir}/.skillhub/` 落盘树与 manifest.json。
 */
@Feature("FEAT-072: 动态 Skill 获取与运行时热加载")
@Tag("feat-072")
@Tag("integration")
@Tag("blackbox")
@EnabledOnOs(OS.LINUX)
@Stories(value = {
        @Story("SC-ADD-01: 启动期全量同步 SUCCESS + manifest 落盘"),
        @Story("SC-ADD-05: 默认最新安装的事后可见标记（previous/target/applied + changeReason）")
})
class SkillHubStartupSyncSitTest extends AbstractSkillHubSyncSitTest {

    @Test
    @DisplayName("SC-ADD-01: 启动即全量同步两个技能，SUCCESS/committed，active 树与 manifest 一致")
    void startupSyncInstallsAllVisibleSkills(@TempDir Path localDir) throws Exception {
        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            hub.addSkill("sit-add-a", "SIT Add A", "1.0.0", "MARK-ADD-A-100");
            hub.addSkill("sit-add-b", "SIT Add B", "1.0.0", "MARK-ADD-B-100");

            try (RunningAgent agent = startAgent(hub, localDir, a -> { })) {
                JsonNode report = awaitAnyCompletedReport(agent.base(), ROUND_TIMEOUT);

                assertThat(report.path("result").asText())
                        .as("SC-ADD-01: 首轮同步应为 SUCCESS").isEqualTo("SUCCESS");
                assertThat(report.path("committed").asBoolean())
                        .as("SC-ADD-01: 首轮应提交（committed=true）").isTrue();
                assertThat(report.path("added").size())
                        .as("SC-ADD-01: added 应含两个技能").isEqualTo(2);
                assertThat(report.path("triggerSource").asText())
                        .as("SC-ADD-01: 首轮触发源应为 STARTUP").isEqualTo("STARTUP");
                assertThat(report.path("failureStage").asText(""))
                        .as("SC-ADD-01: 成功轮不应有失败阶段").isEmpty();

                assertThat(activeSkillDirCount(localDir))
                        .as("SC-ADD-01: active 目录应落盘两个技能目录").isEqualTo(2);
                int[] manifest = manifestGenerationAndRecords(localDir);
                assertThat(manifest).as("SC-ADD-01: manifest 应存在").isNotNull();
                assertThat(manifest[0]).as("SC-ADD-01: 首轮 generation 应为 1").isEqualTo(1);
                assertThat(manifest[1]).as("SC-ADD-01: manifest 记录数应为 2").isEqualTo(2);
                assertThat(hub.catalogRequests()).as("SC-ADD-01: Hub 应收到目录请求").isGreaterThan(0);
                assertThat(hub.downloadRequests())
                        .as("SC-ADD-01: 两个技能各应产生一次下载").isGreaterThanOrEqualTo(2);
            }
        }
    }

    @Test
    @DisplayName("SC-ADD-05: 报告 items 携带 previous/target/applied 与 changeReason/validationMode 标记")
    void startupReportCarriesFieldLevelMarkers(@TempDir Path localDir) throws Exception {
        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            hub.addSkill("sit-add-c", "SIT Add C", "1.0.0", "MARK-ADD-C-100");

            try (RunningAgent agent = startAgent(hub, localDir, a -> { })) {
                JsonNode report = awaitAnyCompletedReport(agent.base(), ROUND_TIMEOUT);
                String raw = getReportRaw(agent.base());

                assertThat(raw).as("SC-ADD-05: 报告应含技能 ID").contains("sit-add-c");
                assertThat(raw).as("SC-ADD-05: 应标记 appliedVersion").contains("appliedVersion");
                assertThat(raw).as("SC-ADD-05: 应标记 targetVersion").contains("targetVersion");
                assertThat(raw).as("SC-ADD-05: 应标记 changeReason").contains("changeReason");
                assertThat(raw).as("SC-ADD-05: 校验模式应为 PACKAGE_SHA256").contains("PACKAGE_SHA256");
                assertThat(report.path("items").size())
                        .as("SC-ADD-05: items 应逐项展开").isEqualTo(1);
                JsonNode item = report.path("items").get(0);
                assertThat(item.path("appliedVersion").asText())
                        .as("SC-ADD-05: 默认规则下 applied 应为最新版").isEqualTo("1.0.0");
            }
        }
    }

    @Test
    @DisplayName("SC-ADD-01(私有排除): Hub 私有资产不进目录列表，不参与启动同步")
    void startupSyncExcludesPrivateAssets(@TempDir Path localDir) throws Exception {
        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            hub.addSkill("sit-add-pub", "SIT Add Pub", "1.0.0", "MARK-ADD-PUB-100");
            hub.addSkill("sit-add-priv", "SIT Add Priv", "1.0.0", "MARK-ADD-PRIV-100");
            hub.setVisibility("sit-add-priv", SkillHubSyncMockServer.Visibility.PRIVATE);

            try (RunningAgent agent = startAgent(hub, localDir, a -> { })) {
                JsonNode report = awaitAnyCompletedReport(agent.base(), ROUND_TIMEOUT);
                assertThat(report.path("result").asText())
                        .as("仅公开资产时首轮仍应 SUCCESS").isEqualTo("SUCCESS");
                assertThat(report.path("added").size())
                        .as("私有资产不应被同步").isEqualTo(1);
                assertThat(activeSkillDirCount(localDir))
                        .as("磁盘上只应落公开技能").isEqualTo(1);
            }
        }
    }
}

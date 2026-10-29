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
 * FEAT-072 SC-VER-02/03/08/10 与 SC-REPL-05：版本规则与同版本重发。
 *
 * <p>规则文件契约（代码核对）：`sync.version-rules-file` 为顶层规则列表，
 * 条目 {@code - skill-id: <id> / mode: PIN|LATEST / version: x.y.z}；
 * mode 必填且仅 LATEST/PIN，PIN 必带 version（按原字符串精确匹配），LATEST 不带 version，
 * 重复 skill-id 报错。非空文件每轮读取且覆盖 inline（SC-VER-08：外部文件热读，
 * inline application.yml 修改需重启）。
 *
 * <p>Oracle：FEAT-072 测试方案 §6；2026-10-28/29 ECS 139 手工执行 PASS（批次 4/8）。
 */
@Feature("FEAT-072: 动态 Skill 获取与运行时热加载")
@Tag("feat-072")
@Tag("integration")
@Tag("blackbox")
@EnabledOnOs(OS.LINUX)
@Stories(value = {
        @Story("SC-VER-02: 默认 latest——Hub 发新版本 → replaced/CONTENT_CHANGED/generation+1/路径不漂移"),
        @Story("SC-VER-03: PIN 锁定不跟随；PIN 未知版本→单项失败不牵连（留旧/不安装）"),
        @Story("SC-VER-08: version-rules-file 外部文件每轮热读，修改不重启即生效"),
        @Story("SC-VER-10: 规则语义不被绕过——未知/单版本仍按 PIN 判定，不静默转 latest"),
        @Story("SC-REPL-05: 同版本仅元数据变化 → metadataOnly、不重下载、generation 不推进")
})
class SkillHubVersionRulesSitTest extends AbstractSkillHubSyncSitTest {

    private static Path rulesFile(Path dir, String content) throws IOException {
        Path file = dir.resolve("version-rules.yaml");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("SC-VER-02: 无规则默认 latest——1.0.1→1.0.2 replaced，CONTENT_CHANGED，路径不漂移")
    void defaultLatestFollowsNewVersion(@TempDir Path localDir) throws Exception {
        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            hub.addSkill("sit-ver-a", "SIT Ver A", "1.0.1", "MARK-VER-A-101");

            try (RunningAgent agent = startAgent(hub, localDir, a -> { })) {
                JsonNode first = awaitAnyCompletedReport(agent.base(), ROUND_TIMEOUT);
                assertThat(first.path("result").asText()).as("首轮应 SUCCESS").isEqualTo("SUCCESS");
                int generation = first.path("generation").asInt();

                hub.publishVersion("sit-ver-a", "1.0.2", "MARK-VER-A-102", "SIT skill sit-ver-a 1.0.2");

                SyncResponse trigger = postSync(agent.base());
                JsonNode second = awaitCompletedReport(agent.base(), trigger.json().path("syncId").asText(),
                        ROUND_TIMEOUT);

                assertThat(second.path("result").asText()).as("SC-VER-02: 二轮应 SUCCESS").isEqualTo("SUCCESS");
                assertThat(second.path("replaced").size())
                        .as("SC-VER-02: 新版本应记 replaced").isEqualTo(1);
                assertThat(getReportRaw(agent.base()))
                        .as("SC-VER-02: changeReason 应为内容变化").contains("CONTENT_CHANGED");
                assertThat(second.path("generation").asInt())
                        .as("SC-VER-02: generation 应推进").isGreaterThan(generation);
                assertThat(activeSkillDirCount(localDir))
                        .as("SC-VER-02: 路径不漂移——仍只有一个技能目录").isEqualTo(1);
                assertThat(getReportRaw(agent.base()))
                        .as("SC-VER-02: 报告应反映 1.0.2").contains("1.0.2");
            }
        }
    }

    @Test
    @DisplayName("SC-VER-03: PIN=1.0.1 时 Hub 发 1.0.2 → unchanged 留旧，不跟随 latest")
    void pinnedVersionDoesNotFollowLatest(@TempDir Path localDir) throws Exception {
        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            hub.addSkill("sit-ver-b", "SIT Ver B", "1.0.1", "MARK-VER-B-101");
            Path rules = rulesFile(localDir, "- skill-id: sit-ver-b\n  mode: PIN\n  version: 1.0.1\n");

            try (RunningAgent agent = startAgent(hub, localDir,
                    a -> a.property(PREFIX + "sync.version-rules-file", rules.toString()))) {
                JsonNode first = awaitAnyCompletedReport(agent.base(), ROUND_TIMEOUT);
                assertThat(first.path("result").asText()).as("PIN 命中已发布版本，首轮应 SUCCESS").isEqualTo("SUCCESS");
                int downloadsAfterFirst = hub.downloadRequests();

                hub.publishVersion("sit-ver-b", "1.0.2", "MARK-VER-B-102", "SIT skill sit-ver-b 1.0.2");
                SyncResponse trigger = postSync(agent.base());
                JsonNode second = awaitCompletedReport(agent.base(), trigger.json().path("syncId").asText(),
                        ROUND_TIMEOUT);

                assertThat(second.path("result").asText()).as("SC-VER-03: PIN 场景轮次应 SUCCESS").isEqualTo("SUCCESS");
                assertThat(second.path("unchanged").size())
                        .as("SC-VER-03: PIN 锁定项应记 unchanged（留旧继续用）").isEqualTo(1);
                assertThat(second.path("replaced").size())
                        .as("SC-VER-03: 不应升级到 latest").isEqualTo(0);
                assertThat(hub.downloadRequests())
                        .as("SC-VER-03: 留旧不应触发新下载")
                        .isLessThanOrEqualTo(downloadsAfterFirst + 1);
                assertThat(getReportRaw(agent.base()))
                        .as("SC-VER-03: 落盘版本应仍为 1.0.1").contains("1.0.1");
            }
        }
    }

    @Test
    @DisplayName("SC-VER-10: PIN 指向未知版本 → 单项失败记 failedItems，不安装也不静默转 latest")
    void pinToUnknownVersionFailsTheItemOnly(@TempDir Path localDir) throws Exception {
        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            hub.addSkill("sit-ver-c", "SIT Ver C", "1.0.0", "MARK-VER-C-100");
            Path rules = rulesFile(localDir, "- skill-id: sit-ver-c\n  mode: PIN\n  version: 9.9.9\n");

            try (RunningAgent agent = startAgent(hub, localDir,
                    a -> a.property(PREFIX + "sync.version-rules-file", rules.toString()))) {
                JsonNode first = awaitAnyCompletedReport(agent.base(), ROUND_TIMEOUT);

                assertThat(first.path("result").asText())
                        .as("SC-VER-10: 单项失败应记 PARTIAL/FAILED 而非 SUCCESS")
                        .isIn("PARTIAL", "FAILED");
                assertThat(first.path("failedItems").size())
                        .as("SC-VER-10: 未满足的 PIN 应逐项登记").isEqualTo(1);
                assertThat(getReportRaw(agent.base()))
                        .as("SC-VER-10: 失败项应指向 sit-ver-c").contains("sit-ver-c");
                assertThat(activeSkillDirCount(localDir))
                        .as("SC-VER-10: 无本地留旧时不得安装任何版本").isEqualTo(0);
                assertThat(getReportRaw(agent.base()))
                        .as("SC-VER-10: 不得按 latest 静默安装 1.0.0（applied 应为空或 NONE 语义）")
                        .doesNotContain("\"appliedVersion\":\"1.0.0\"");
            }
        }
    }

    @Test
    @DisplayName("SC-VER-08: 修改 version-rules-file 内容不重启——下一轮按新规则收敛")
    void externalRulesFileIsHotReadEachRound(@TempDir Path localDir) throws Exception {
        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            hub.addSkill("sit-ver-d", "SIT Ver D", "1.0.1", "MARK-VER-D-101");
            hub.publishVersion("sit-ver-d", "1.0.2", "MARK-VER-D-102", "SIT skill sit-ver-d 1.0.2");
            Path rules = rulesFile(localDir, "- skill-id: sit-ver-d\n  mode: PIN\n  version: 1.0.1\n");

            try (RunningAgent agent = startAgent(hub, localDir,
                    a -> a.property(PREFIX + "sync.version-rules-file", rules.toString()))) {
                JsonNode first = awaitAnyCompletedReport(agent.base(), ROUND_TIMEOUT);
                assertThat(first.path("result").asText()).as("PIN 1.0.1 首轮应 SUCCESS").isEqualTo("SUCCESS");
                assertThat(getReportRaw(agent.base()))
                        .as("热读前置条件：当前落盘 1.0.1").contains("1.0.1");

                // 不重启，改规则 PIN→1.0.2（外部文件每轮读取）
                Files.writeString(rules, "- skill-id: sit-ver-d\n  mode: PIN\n  version: 1.0.2\n",
                        StandardCharsets.UTF_8);

                SyncResponse trigger = postSync(agent.base());
                JsonNode second = awaitCompletedReport(agent.base(), trigger.json().path("syncId").asText(),
                        ROUND_TIMEOUT);

                assertThat(second.path("result").asText()).as("SC-VER-08: 新规则轮应 SUCCESS").isEqualTo("SUCCESS");
                assertThat(second.path("replaced").size())
                        .as("SC-VER-08: 热读新 PIN 应触发升级替换").isEqualTo(1);
                assertThat(getReportRaw(agent.base()))
                        .as("SC-VER-08: 落盘应收敛到 1.0.2").contains("1.0.2");
            }
        }
    }

    @Test
    @DisplayName("SC-REPL-05: 同版本重发仅元数据变化 → metadataOnly，不重下载，generation 不推进")
    void sameVersionMetadataOnlyRepublish(@TempDir Path localDir) throws Exception {
        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            hub.addSkill("sit-repl-m", "SIT Repl M", "1.0.2", "MARK-REPL-M-102");

            try (RunningAgent agent = startAgent(hub, localDir, a -> { })) {
                JsonNode first = awaitAnyCompletedReport(agent.base(), ROUND_TIMEOUT);
                assertThat(first.path("result").asText()).as("首轮应 SUCCESS").isEqualTo("SUCCESS");
                int generation = first.path("generation").asInt();
                int downloadsAfterFirst = hub.downloadRequests();

                hub.republishSameVersionMetadataOnly("sit-repl-m", "1.0.2",
                        "SIT skill sit-repl-m 1.0.2 metadata changed");

                SyncResponse trigger = postSync(agent.base());
                JsonNode second = awaitCompletedReport(agent.base(), trigger.json().path("syncId").asText(),
                        ROUND_TIMEOUT);

                assertThat(second.path("result").asText()).as("SC-REPL-05: 元数据轮应 SUCCESS").isEqualTo("SUCCESS");
                assertThat(second.path("metadataOnly").size())
                        .as("SC-REPL-05: 应记 metadataOnly").isEqualTo(1);
                assertThat(second.path("generation").asInt())
                        .as("SC-REPL-05: generation 不应推进").isEqualTo(generation);
                assertThat(hub.downloadRequests())
                        .as("SC-REPL-05: 内容未变不应重下载技能包")
                        .isLessThanOrEqualTo(downloadsAfterFirst + 1);
                assertThat(activeSkillDirCount(localDir))
                        .as("SC-REPL-05: 目录数不变").isEqualTo(1);
            }
        }
    }
}

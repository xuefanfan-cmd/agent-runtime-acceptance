package com.huawei.ascend.sit.cases.integration.skillhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
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
 * FEAT-072 SC-INST-01：多副本一致性（AC-30，P2）。
 *
 * <p>Oracle 来源：L2 §3.11（多副本：单次手动仅命中一个实例（instanceId 可定位）；
 * 各实例最终内容指纹收敛——不承诺代数相同）与 FEAT-072 测试方案 §6；
 * 2026-10-28 ECS 139 执行 PASS（批次 22）。
 */
@Feature("FEAT-072: 动态 Skill 获取与运行时热加载")
@Tag("feat-072")
@Tag("integration")
@Tag("blackbox")
@Story("SC-INST-01: 双实例各自同步收敛——installedFingerprint 一致；手动触发仅命中目标实例")
class SkillHubMultiInstanceSitTest extends AbstractSkillHubSyncSitTest {

    @Test
    @DisplayName("SC-INST-01: 双实例同步后内容指纹一致；手动触发只作用于目标实例")
    void twoInstancesConvergeAndManualSyncTargetsOneInstance(@TempDir Path localDirA,
                                                             @TempDir Path localDirB) throws Exception {
        try (SkillHubSyncMockServer hub = SkillHubSyncMockServer.start()) {
            hub.addSkill("sit-inst-a", "SIT Inst A", "1.0.0", "MARK-INST-A-100");
            hub.addSkill("sit-inst-b", "SIT Inst B", "1.0.0", "MARK-INST-B-100");

            try (SutStack stack = startTwoAgentStack(localDirA, localDirB, hub)) {
                String baseA = stack.baseUrl(AGENT);
                String baseB = stack.baseUrl(AGENT_B);

                JsonNode reportA = awaitAnyCompletedReport(baseA, ROUND_TIMEOUT);
                JsonNode reportB = awaitAnyCompletedReport(baseB, ROUND_TIMEOUT);

                assertThat(reportA.path("result").asText())
                        .as("SC-INST-01: 实例 A 应同步 SUCCESS").isEqualTo("SUCCESS");
                assertThat(reportB.path("result").asText())
                        .as("SC-INST-01: 实例 B 应同步 SUCCESS").isEqualTo("SUCCESS");
                assertThat(reportA.path("instanceId").asText())
                        .as("SC-INST-01: 两个实例的 instanceId 应可区分（可定位）")
                        .isNotEqualTo(reportB.path("instanceId").asText());

                // 内容指纹收敛（不比较 generation——不承诺代数相同）
                String fingerprintA = reportA.path("installedFingerprint").asText("");
                String fingerprintB = reportB.path("installedFingerprint").asText("");
                assertThat(fingerprintA).as("SC-INST-01: 实例 A 应有安装指纹").isNotEmpty();
                assertThat(fingerprintB)
                        .as("SC-INST-01: 相同 Hub 内容下双实例指纹应收敛一致").isEqualTo(fingerprintA);
                assertThat(activeSkillDirCount(localDirA))
                        .as("SC-INST-01: 实例 A 落盘两个技能").isEqualTo(2);
                assertThat(activeSkillDirCount(localDirB))
                        .as("SC-INST-01: 实例 B 落盘两个技能").isEqualTo(2);

                // 单次手动仅命中一个实例：触发 A，B 的最近完成轮不应变化
                String syncIdBBefore = reportB.path("syncId").asText();
                SyncResponse trigger = postSync(baseA);
                assertThat(trigger.status()).as("实例 A 的手动触发应被受理").isEqualTo(202);
                awaitCompletedReport(baseA, trigger.json().path("syncId").asText(), ROUND_TIMEOUT);

                assertThat(lastCompleted(baseB).path("syncId").asText())
                        .as("SC-INST-01: 手动触发不应命中实例 B")
                        .isEqualTo(syncIdBBefore);
            }
        }
    }
}

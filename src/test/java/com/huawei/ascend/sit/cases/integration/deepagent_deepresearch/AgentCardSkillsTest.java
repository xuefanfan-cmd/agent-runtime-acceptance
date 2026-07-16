package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentSkill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-001.agent-card-skills — Agent Card skills 声明真实性与结构完整性.
 *
 * <p>FEAT-001 §2「Agent Card skills」+ §5.1.1「skills 是跨 Agent 工具发现事实入口」。
 * 断言：
 * <ul>
 *   <li>skills[] 非空 —— deep-research 至少要暴露 {@code deep_research} 主 skill；</li>
 *   <li>每个 skill 的必填字段（id / name / description）非空；</li>
 *   <li>id 唯一（否则跨 Agent 工具发现会撞名）；</li>
 *   <li>{@code deep_research} skill 存在且带业务相关 tags。</li>
 * </ul>
 *
 * <p><b>与 {@link AgentCardDiscoveryTest#deepResearchCardMatchesManualContract()} 的分工</b>：
 * DA-01.C 只断言 {@code skills[0].id="deep_research"} + 部分 tags。本用例在 FEAT-001 视角下补齐
 * skills 结构完整性 + id 唯一性 + 每个 skill 字段可用性，防止 SUT 后续新增 skill 时结构不完整。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
class AgentCardSkillsTest extends BaseManagedStackTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final String EXPECTED_MAIN_SKILL_ID = "deep_research";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config).agent(DEEP_RESEARCH);
    }

    @Test
    @DisplayName("FEAT-001.agent-card-skills: skills[] 非空且每个 skill 字段完整、id 唯一，含 deep_research")
    void skillsDeclarationIsWellFormedAndTruthful() {
        A2aServiceClient a2a = client(DEEP_RESEARCH);
        AgentCard card = a2a.getAgentCard();

        List<AgentSkill> skills = card.skills();
        assertThat(skills)
                .as("FEAT-001.agent-card-skills: skills 不应为 null 或空 —— agent 至少要声明一个可发现工具")
                .isNotNull()
                .isNotEmpty();

        // 结构完整性：id / name / description 必填字段每个 skill 都非空
        for (int i = 0; i < skills.size(); i++) {
            AgentSkill s = skills.get(i);
            assertThat(s.id())
                    .as("FEAT-001.agent-card-skills: skills[%d].id 非空", i)
                    .isNotBlank();
            assertThat(s.name())
                    .as("FEAT-001.agent-card-skills: skills[%d].name 非空 (id=%s)", i, s.id())
                    .isNotBlank();
            assertThat(s.description())
                    .as("FEAT-001.agent-card-skills: skills[%d].description 非空 (id=%s)", i, s.id())
                    .isNotBlank();
        }

        // id 唯一性 —— 允许 tags / inputModes 等重叠，但 id 必须唯一（否则工具发现无从分派）
        long uniqueIds = skills.stream().map(AgentSkill::id).distinct().count();
        assertThat(uniqueIds)
                .as("FEAT-001.agent-card-skills: skills id 应唯一\nids=%s",
                        skills.stream().map(AgentSkill::id).toList())
                .isEqualTo(skills.size());

        // 业务契约：deep-research 必须声明 deep_research 主 skill（§1 手工契约）
        AgentSkill mainSkill = skills.stream()
                .filter(s -> EXPECTED_MAIN_SKILL_ID.equals(s.id()))
                .findFirst()
                .orElse(null);
        assertThat(mainSkill)
                .as("FEAT-001.agent-card-skills: 必须存在 id='%s' 的主 skill\n所有 id=%s",
                        EXPECTED_MAIN_SKILL_ID,
                        skills.stream().map(AgentSkill::id).toList())
                .isNotNull();

        // tags 用于跨 agent 工具发现 —— deep_research 至少要带一个语义 tag
        assertThat(mainSkill.tags())
                .as("FEAT-001.agent-card-skills: deep_research skill 应至少带一个 tag")
                .isNotNull()
                .isNotEmpty();
    }
}
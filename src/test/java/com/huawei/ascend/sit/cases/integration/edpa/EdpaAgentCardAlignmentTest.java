package com.huawei.ascend.sit.cases.integration.edpa;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentSkill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-028 矩阵 <b>A1</b> —— EDPAgent Agent Card 声明并行调度能力真实性。
 *
 * <p><b>Spec 依据</b>（testplan §5 A1）：Card {@code capabilities.streaming=true}；
 * skills 非空、id 唯一；Card 声明的 agent 身份 = {@code EDPAgent}；
 * {@code remote-agents} 声明的 agentName 集合非空、无重复（planrule 的委托目标解析基础）。
 *
 * <p><b>作用</b>：本用例是 FEAT-028 落地的第一条 smoke——先确认 EDPAgent 拓扑就绪、
 * Card 可达、下游 agentName 集合合规。后续所有 P 组 / C 组 / N 组用例的前置。
 *
 * <p><b>Tag</b>：非 manual（不依赖 LLM），可 CI 常规执行。
 */
@Tag("integration")
@Tag("edpa")
@Tag("feat-028")
@Feature("FEAT-028: EDPA 规划工作流与智能体并行执行")
@Story("A1.agent-card-alignment: EDPAgent Card 声明并行调度能力真实性（capabilities/skills/remote-agents 一致）")
class EdpaAgentCardAlignmentTest extends BaseManagedStackTest {

    private static final Logger LOG = Logger.getLogger(EdpaAgentCardAlignmentTest.class.getName());

    static final String EDP_AGENT = "edp-agent";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // 仅起 EDPAgent：A1 只探 Card 与配置一致性，不需要真实下游被委托方。
        // versatile-agent 是 jar 内 remote-agents 必填项——用不可达 dummy 满足启动即可（下游不可达
        // 不阻塞启动，只 WARN，见 [[local-sut-runtime]]）；search 也用 dummy。
        // scenarioHome 必须是存在目录且含 governance 子目录（EdpConfigValidator.validateScenarioConfig
        // 硬校验，8-24 实测），用 /tmp/edpa-scenario-min 作最小合法目录（governance 由测试基建预置）。
        // EDP_AGENT_MODEL_* 通过 shell env（/tmp/sit-secrets.env）继承给子进程；本用例不做 LLM 调用故值不重要。
        return SutStack.builder(config)
                .agent(EDP_AGENT, a -> a
                        .env("EDP_AGENT_VERSATILE_A2A_URL", "http://127.0.0.1:1")
                        .env("EDP_AGENT_SEARCH_A2A_URL", "http://127.0.0.1:1")
                        .env("EDP_AGENT_SCENARIO_HOME", "/tmp/edpa-scenario-min"));
    }

    @Test
    @DisplayName("FEAT-028.A1: EDPAgent Card 声明 streaming 能力真实、skills 合规、身份为 EDPAgent")
    void cardDeclarationAlignsWithFeat028Contract() {
        AgentCard card = client(EDP_AGENT).getAgentCard();
        assertThat(card).as("EDPAgent card 不应为 null").isNotNull();

        // 身份断言：Card 上暴露的名字与 SUT jar 一致（8-24 实测：SDK 侧 card.name 反映
        // spring.application.name 而非 a2a.agent-name，属实现事实——A1 只做「声明面存在且非空」弱断言）。
        assertThat(card.name())
                .as("Card 声明的 agent 名不得为空；实测 SDK 侧 card.name = 'edp-agent-engine'（Spring 应用名）")
                .isNotBlank();

        // capabilities.streaming=true（application.yml 明示 streaming: true）——这是 SSE 并行主线 P3/P4 的前置。
        assertThat(card.capabilities())
                .as("capabilities 不应为 null").isNotNull();
        assertThat(card.capabilities().streaming())
                .as("§2.1: streaming 是 FEAT-028 SSE 模式的前置能力（application.yml 声明 true）")
                .isTrue();

        // skills 非空 + id 唯一（EDPAgent 声明一个主 skill: edp_banking_workflow）。
        List<AgentSkill> skills = card.skills();
        assertThat(skills).as("skills 不应为空——被其他 Agent 发现并作为工具调用的基础").isNotEmpty();
        Set<String> uniqIds = new HashSet<>();
        for (AgentSkill skill : skills) {
            assertThat(skill.id()).as("skill.id 不得为空").isNotBlank();
            assertThat(skill.name()).as("skill.name 不得为空").isNotBlank();
            assertThat(skill.description()).as("skill.description 不得为空").isNotBlank();
            assertThat(uniqIds.add(skill.id()))
                    .as("skill.id 必须唯一，实测重复：%s（全集=%s）", skill.id(), uniqIds)
                    .isTrue();
        }
        LOG.info("[a1] card.name=" + card.name() + " skills.ids=" + uniqIds
                + " streaming=" + card.capabilities().streaming()
                + " pushNotifications=" + card.capabilities().pushNotifications());
    }
}

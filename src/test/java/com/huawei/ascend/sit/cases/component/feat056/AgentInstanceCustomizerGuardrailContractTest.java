/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.feat056;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.openjiuwen.agents.toolguardrail.ToolGuardrail;
import com.openjiuwen.agents.toolguardrail.ToolGuardrailOptions;
import com.openjiuwen.agents.toolguardrail.ToolGuardrailPriorities;
import com.openjiuwen.agents.toolguardrail.config.ToolGuardrailConfig;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FEAT-056 业务 Rail 与 Tool Guardrail 的共存与优先级口径。
 *
 * <p>对应 PR 292 后的 L2 §2.3/§8.1：保留区检查只读 {@code DeepAgent.getRegisteredRails()}，
 * 经内层 BaseAgent 注册的 Rail 不在该列表；因此**不能**用"护栏安装成功"证明业务 Rail 优先级合规，
 * 必须显式检查业务 Rail 的 priority。本类用真实 DeepAgent + 真实 ToolGuardrail 验证，
 * 不再用 mock 的 DeepAgent。
 *
 * @since 0.1.2
 */
@Tag("component")
@Tag("feat-056")
@Feature("FEAT-056: 业务 Agent（Lib JAR）二次开发扩展点")
class AgentInstanceCustomizerGuardrailContractTest {
    @TempDir
    Path workspace;

    @Test
    @Story("FEAT-056-C21: 合规业务 Rail 共存且优先级显式检查，不能用安装成功代替")
    void compliantBusinessRailCoexistsAndItsPriorityIsCheckedExplicitly() {
        DeepAgent agent = deepAgent();
        AtomicInteger businessHits = new AtomicInteger();
        AgentRail businessRail = new AgentRail() {
            @Override
            public void beforeInvoke(AgentCallbackContext ctx) {
                businessHits.incrementAndGet();
            }
        };
        agent.getAgent().registerRail(businessRail);

        // 合规业务 Rail 低于保留下限：护栏必须照常安装，且不替换业务 Rail。
        ToolGuardrail.install(agent, options());

        assertThat(priorities(agent)).containsExactlyInAnyOrder(
                ToolGuardrailPriorities.ALLOWED_TOOLS_GATE,
                ToolGuardrailPriorities.PERMISSION_INTERRUPT,
                ToolGuardrailPriorities.DECISION_OBSERVER);
        // 安装成功本身不能作为优先级合规的证明，必须显式检查业务 Rail 的 priority。
        assertThat(businessRail.getPriority())
                .as("业务 Rail priority 必须低于 ToolGuardrailPriorities.RESERVED_FLOOR(%d)",
                        ToolGuardrailPriorities.RESERVED_FLOOR)
                .isLessThan(ToolGuardrailPriorities.RESERVED_FLOOR);
        assertThat(agent.getRegisteredRails())
                .as("内层注册的业务 Rail 不在 DeepAgent 外层登记列表，护栏的保留区检查看不到它；"
                        + "业务 Rail 是否真的共存由托管真实 A2A 用例验证")
                .doesNotContain(businessRail);
    }

    @Test
    @Story("FEAT-056-C21: 已知边界——内层注册的越界 Rail 不会被现有护栏拒绝（OUT/non-gating）")
    void innerRegisteredViolatingRailIsNotRejectedDocumentedBoundary() {
        DeepAgent agent = deepAgent();
        AgentRail violatingRail = new AgentRail() {
        };
        violatingRail.setPriority(ToolGuardrailPriorities.RESERVED_FLOOR);
        agent.getAgent().registerRail(violatingRail);

        // L2 §2.3 已声明：本特性不新增优先级检查机制，也不承诺自动拒绝所有直接注册的越界 Rail。
        // 这里只固定"当前不会拒绝"这一已声明边界，避免行为被静默改变；该断言不计入能力覆盖。
        assertThatCode(() -> ToolGuardrail.install(agent, options())).doesNotThrowAnyException();
        assertThat(agent.getRegisteredRails()).doesNotContain(violatingRail);
    }

    private DeepAgent deepAgent() {
        String id = "feat056-guardrail-" + System.nanoTime();
        return new DeepAgent(
                AgentCard.builder().id(id).name("CustomizerGuardrailTest").build(),
                DeepAgentConfig.builder().enableTaskLoop(false)
                        .backend(Map.of("provider", "OpenAI", "api_key", "test-placeholder",
                                "api_base", "https://example.invalid"))
                        .model(Map.of("model", "unused-local-model")).build(),
                Workspace.builder().rootPath(workspace.resolve(id).toString()).build());
    }

    private static java.util.List<Integer> priorities(DeepAgent agent) {
        return agent.getRegisteredRails().stream()
                .filter(AgentRail.class::isInstance)
                .map(AgentRail.class::cast)
                .map(AgentRail::getPriority)
                .toList();
    }

    private static ToolGuardrailOptions options() {
        return ToolGuardrailOptions.builder(ToolGuardrailConfig.builder().enabled(true).build()).build();
    }
}

/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.feat056;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openjiuwen.agents.toolguardrail.ToolGuardrail;
import com.openjiuwen.agents.toolguardrail.ToolGuardrailOptions;
import com.openjiuwen.agents.toolguardrail.ToolGuardrailPriorities;
import com.openjiuwen.agents.toolguardrail.config.ToolGuardrailConfig;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * FEAT-056 guardrail coexistence and priority boundary.
 */
@Tag("component")
@Tag("feat-056")
@Feature("FEAT-056: 业务 Agent（Lib JAR）二次开发扩展点")
class AgentInstanceCustomizerGuardrailContractTest {
    @Test
    @Story("FEAT-056-C21: 合规业务 Rail 与护栏共存")
    void guardrailAcceptsBusinessRailBelowReservedFloor() {
        ReActAgent inner = mock(ReActAgent.class);
        DeepAgent agent = mock(DeepAgent.class);
        List<Object> registered = new ArrayList<>(List.of(railWithPriority(ToolGuardrailPriorities.RESERVED_FLOOR - 1)));
        when(agent.getAgent()).thenReturn(inner);
        when(agent.getRegisteredRails()).thenReturn(registered);
        when(agent.getWorkspace()).thenReturn(null);

        ToolGuardrail.install(agent, options());

        assertThat(registered).hasSize(4);
    }

    @Test
    @Story("FEAT-056-C21: 保留区业务 Rail 在安装点失败")
    void guardrailRejectsBusinessRailAtReservedFloor() {
        ReActAgent inner = mock(ReActAgent.class);
        DeepAgent agent = mock(DeepAgent.class);
        when(agent.getAgent()).thenReturn(inner);
        when(agent.getRegisteredRails())
                .thenReturn(new ArrayList<>(List.of(railWithPriority(ToolGuardrailPriorities.RESERVED_FLOOR))));
        when(agent.getWorkspace()).thenReturn(null);

        assertThatThrownBy(() -> ToolGuardrail.install(agent, options()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reserved band");
    }

    private static ToolGuardrailOptions options() {
        return ToolGuardrailOptions.builder(ToolGuardrailConfig.builder().enabled(true).build()).build();
    }

    private static AgentRail railWithPriority(int priority) {
        AgentRail rail = new AgentRail() {
        };
        rail.setPriority(priority);
        return rail;
    }
}

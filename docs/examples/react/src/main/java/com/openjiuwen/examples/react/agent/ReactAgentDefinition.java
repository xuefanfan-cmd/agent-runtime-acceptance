package com.openjiuwen.examples.react.agent;

import java.util.List;
import java.util.Map;

import com.openjiuwen.core.singleagent.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;

/**
 * Core 语义层定义：只负责 AgentCard、prompt、模型参数与工具元数据，不依赖 Spring/runtime。
 */
public final class ReactAgentDefinition {

    public static final String AGENT_ID = "notes-react";

    private ReactAgentDefinition() {
    }

    public static DefinedReactAgent create(String apiKey, String apiBase, String modelName) {
        ReActAgentConfig config = ReActAgentConfig.builder()
                .promptTemplate(List.of(Map.of("role", "system", "content",
                        "你是文本分析助手。需要统计时调用 text_stats 工具，不要自己估算数字。")))
                .maxIterations(6)
                .build()
                .configureModelClient("OpenAI", apiKey, apiBase, modelName, true);
        config.getModelConfigObj().setTemperature(0.1);

        AgentCard card = AgentCard.builder()
                .id(AGENT_ID).name(AGENT_ID)
                .description("ReAct 推理循环 + 本地工具调用")
                .build();
        ReActAgent agent = new ReActAgent(card);
        agent.configure(config);

        TextStatsTool tool = new TextStatsTool();
        agent.getAbilityManager().add(tool.getCard());
        return new DefinedReactAgent(agent, tool, card.getId());
    }

    /** 语义层产物：runtime 层据此注册工具执行体并托管 Agent。 */
    public record DefinedReactAgent(ReActAgent agent, TextStatsTool tool, String agentId) {
    }
}

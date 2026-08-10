package com.openjiuwen.example.customrest;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;

/**
 * Agent 装配（custom-rest 的执行前提）：构造 ReActAgent、注册到 ResourceMgr，
 * 并用基础 handler 托管——custom-rest 经运行时 A2A 执行链驱动它。
 *
 * <p>与「配置驱动 Agent」示例的差别仅在于本例显式声明 Handler Bean；
 * 协议转换本身不依赖 handler 形态。
 */
@Configuration(proxyBeanMethods = false)
public class AgentConfiguration {

    /** 与 application.yml 的 openjiuwen.service.agent-id 保持一致。 */
    public static final String AGENT_ID = "assistant";

    @Value("${llm.api-key:}")
    private String apiKey;

    @Value("${llm.api-base:}")
    private String apiBase;

    @Value("${llm.model:gpt-4o-mini}")
    private String modelName;

    @Bean
    ReActAgent assistantAgent() {
        AgentCard card = AgentCard.builder()
                .id(AGENT_ID).name(AGENT_ID)
                .description("自定义 REST 协议下的问答助手")
                .build();
        ReActAgent agent = new ReActAgent(card);

        ReActAgentConfig config = ReActAgentConfig.builder()
                .promptTemplate(List.of(Map.of("role", "system", "content", "你是一个简洁的助手。")))
                .maxIterations(12)
                .build()
                .configureModelClient("OpenAI", apiKey, apiBase, modelName, true);
        agent.configure(config);

        var registration = Runner.resourceMgr().addAgent(card, () -> agent, null);
        if (registration.isError()) {
            throw new IllegalStateException("Agent registration failed", registration.getError());
        }
        return agent;
    }

    @Bean
    AgentHandler assistantHandler(ReActAgent assistantAgent) {
        return new JiuwenCoreAgentHandler(assistantAgent);
    }
}

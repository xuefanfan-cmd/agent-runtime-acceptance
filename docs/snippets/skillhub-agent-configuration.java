package com.openjiuwen.example.skillhub;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.openjiuwen.core.singleagent.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreAgentExtHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;

/**
 * SkillHub 示例的 Agent 装配：两个 Bean 缺一不可。
 *
 * <p>① Agent 实例——必须设置 {@code sysOperationId}（core 技能基础设施
 * 以前它初始化，未设置则技能注册不生效）；SkillHub 不需要 ResourceMgr
 * 注册，因为 handler 直接持有实例。
 *
 * <p>② ext handler——SkillHubManager 以 {@code @Autowired(required = false)}
 * 只注入 {@link JiuwenCoreAgentExtHandler}；基础 handler / agent-id 自动装配
 * 路径不会获得 SkillHub 能力。
 */
@Configuration(proxyBeanMethods = false)
public class AgentConfiguration {

    /** Agent 标识：同时用作 sysOperationId。 */
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
                .description("带 SkillHub 技能的问答助手")
                .build();
        ReActAgent agent = new ReActAgent(card);

        ReActAgentConfig config = ReActAgentConfig.builder()
                .promptTemplate(List.of(Map.of("role", "system",
                        "content", "你是一个简洁的助手。系统已为你注册了若干技能（skill），请优先使用它们。")))
                .maxIterations(12)
                // 前置条件：core 的 SkillUtil 以 sysOperationId 初始化，
                // 不设置则 registerSkill 不生效（SkillHub 下载成功但 agent 无技能）
                .sysOperationId(AGENT_ID)
                .build()
                .configureModelClient("openai", apiKey, apiBase, modelName, true);
        agent.configure(config);
        return agent;
    }

    @Bean
    AgentHandler assistantHandler(ReActAgent assistantAgent) {
        // SkillHub 注入点只在 ext handler 上；构造器只接受 Agent 实例，
        // 因此必须手动声明 Bean（agent-id 纯配置自动装配只覆盖基础 handler）
        return new JiuwenCoreAgentExtHandler(assistantAgent);
    }
}

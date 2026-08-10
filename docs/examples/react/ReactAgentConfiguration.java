package com.openjiuwen.examples.react;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;

/**
 * 最小完整闭环：ReAct 推理循环（LLM 自主决定调几次工具）+ 本地工具两步注册 + 托管。
 * 命名中性化，只演示框架能力，不含业务逻辑。
 *
 * <p>配套文件：application.yml（服务端点与 LLM 配置）、TextStatsTool.java（工具实现）。
 */
@Configuration(proxyBeanMethods = false)
public class ReactAgentConfiguration {

    @Bean
    AgentHandler reactHandler(
            @Value("${react.api-key:}") String apiKey,
            @Value("${react.api-base:}") String apiBase,
            @Value("${react.model-name:gpt-4o-mini}") String modelName) {
        // 1) 官方创建路径：new + configure（ReActAgent 无工厂类）
        ReActAgentConfig config = ReActAgentConfig.builder()
                .promptTemplate(List.of(Map.of("role", "system", "content",
                        "你是文本分析助手。需要统计时调用 text_stats 工具，不要自己估算数字。")))
                .maxIterations(6)
                .build()
                .configureModelClient("openai", apiKey, apiBase, modelName, true);
        config.getModelConfigObj().setTemperature(0.1);

        AgentCard card = AgentCard.builder()
                .id("notes-react").name("notes-react")
                .description("ReAct 推理循环 + 本地工具调用")
                .build();
        ReActAgent agent = new ReActAgent(card);
        agent.configure(config);

        // 2) 工具两步注册：元数据进 AbilityManager（LLM 可见），
        //    执行体进 ResourceMgr（运行时可调）；两步缺一不可
        TextStatsTool tool = new TextStatsTool();
        agent.getAbilityManager().add(tool.getCard());
        Runner.resourceMgr().addTool(tool, List.of(card.getId()), true);

        // 3) 库存 handler 直接托管，不子类化
        return new JiuwenCoreAgentHandler(agent);
    }
}

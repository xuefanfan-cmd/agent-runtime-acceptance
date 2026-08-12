package com.openjiuwen.examples.react.runtime;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.examples.react.agent.ReactAgentDefinition;
import com.openjiuwen.examples.react.agent.ReactAgentDefinition.DefinedReactAgent;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;

/** Runtime 程序级服务层：读取环境配置、注册执行资源并把 core Agent 托管为服务。 */
@Configuration(proxyBeanMethods = false)
public class ReactAgentRuntimeConfiguration {

    @Bean
    AgentHandler reactHandler(
            @Value("${react.api-key:}") String apiKey,
            @Value("${react.api-base:}") String apiBase,
            @Value("${react.model-name:gpt-4o-mini}") String modelName) {
        DefinedReactAgent defined = ReactAgentDefinition.create(apiKey, apiBase, modelName);

        // 执行体属于程序级运行资源；ToolCard 元数据已在语义层加入 AbilityManager。
        Runner.resourceMgr().addTool(defined.tool(), List.of(defined.agentId()), true);
        return new JiuwenCoreAgentHandler(defined.agent());
    }
}

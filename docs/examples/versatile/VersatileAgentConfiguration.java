package com.openjiuwen.examples.versatile;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.openjiuwen.service.adapters.versatile.agentfw.VersatileAgentHandler;
import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.spi.AgentHandler;

/**
 * 把远端 versatile（HTTP+SSE）工作流包成标准 Agent 的全部装配代码：
 * 一个 Bean；HttpClient / 请求抽取 / 响应抽取由 handler 内部组装。
 *
 * <p>配套文件：application.yml（url-template、result-node-name、中断映射等）。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VersatileProperties.class)
public class VersatileAgentConfiguration {

    /** A2A 路由标识；必须与 application.yml 的 openjiuwen.service.agent-id 一致。 */
    public static final String AGENT_ID = "versatile-agent";

    @Bean
    AgentHandler versatileAgentHandler(VersatileProperties properties) {
        return new VersatileAgentHandler(properties);
    }
}

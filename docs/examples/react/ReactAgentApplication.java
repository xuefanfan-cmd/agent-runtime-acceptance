package com.openjiuwen.examples.react;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动类：加载 ReactAgentConfiguration 中的 AgentHandler Bean 后，
 * agent-service-app 自动暴露 REST /v1/query 与 A2A 端点。
 */
@SpringBootApplication
public class ReactAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReactAgentApplication.class, args);
    }
}

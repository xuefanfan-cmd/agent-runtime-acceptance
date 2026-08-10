package com.openjiuwen.examples.versatile;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动类：加载 VersatileAgentConfiguration 中的 AgentHandler Bean 后，
 * agent-service-app 自动暴露 REST /v1/query 与 A2A 端点。
 */
@SpringBootApplication
public class VersatileAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(VersatileAgentApplication.class, args);
    }
}

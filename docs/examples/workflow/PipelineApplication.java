package com.openjiuwen.examples.workflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动类：加载 PipelineConfiguration 中的 AgentHandler Bean 后，
 * agent-service-app 自动暴露 REST /v1/query 与 A2A 端点。
 */
@SpringBootApplication
public class PipelineApplication {

    public static void main(String[] args) {
        SpringApplication.run(PipelineApplication.class, args);
    }
}

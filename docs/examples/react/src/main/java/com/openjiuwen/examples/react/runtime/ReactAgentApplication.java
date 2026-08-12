package com.openjiuwen.examples.react.runtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Runtime 服务入口；扫描共同根包下的 agent/runtime 分层。 */
@SpringBootApplication(scanBasePackages = "com.openjiuwen.examples.react")
public class ReactAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReactAgentApplication.class, args);
    }
}

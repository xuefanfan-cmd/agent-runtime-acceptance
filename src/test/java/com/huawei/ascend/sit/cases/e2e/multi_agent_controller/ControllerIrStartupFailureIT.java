/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.e2e.multi_agent_controller;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FEAT-031 §7 startup failure surface for an invalid controller-IR bundle. */
@Tag("e2e")
@Tag("multi-agent-controller")
@Tag("feat-031")
@Feature("FEAT-031: Studio DSL Java 承载")
class ControllerIrStartupFailureIT {
    private static final String SUT = "controller-ir-demo";

    @Test
    @Story("MA-031-05-20-E12: 非法 bundle 启动失败")
    @DisplayName("缺主 IR 的 bundle 启动 fail-fast，进程未暴露 A2A 端口")
    void invalidBundleFailsBeforeA2aBecomesReady() throws IOException {
        Path bundle = Path.of("target", "sit-logs", "controller-ir-invalid",
                UUID.randomUUID().toString());
        Files.createDirectories(bundle.resolve("ir"));
        Files.writeString(bundle.resolve("manifest.json"),
                "{\"irPathToLocal\":{\"agent/ir/missing/missing.json\":\"ir/missing.json\"},"
                        + "\"modelMapping\":\"model-mapping.json\"}");
        Files.writeString(bundle.resolve("model-mapping.json"),
                "{\"schemaVersion\":\"1\",\"models\":{\"deepseek-flash\":"
                        + "{\"endpoint\":\"\",\"apiKey\":\"\"}}}");

        TestConfig config = TestConfig.load();
        SutStack.Builder builder = SutStack.builder(config)
                .streaming(true)
                .agent(SUT, agent -> agent
                        .env("LLM_API_KEY", "sit-controlled-key")
                        .env("LLM_API_BASE", "http://127.0.0.1:1/v1")
                        .env("LLM_MODEL_NAME", "deepseek-flash")
                        .env("CONTROLLER_IR_BUNDLE_DIR", bundle.toString())
                        .env("CONTROLLER_IR_ROOT_AGENT_ID", "missing-root")
                        .env("REDIS_HOST", requiredEnv("REDIS_HOST"))
                        .env("REDIS_PORT", requiredEnv("REDIS_PORT"))
                        .property("openjiuwen.service.middleware.checkpointer.type", "redis")
                        .property("openjiuwen.service.middleware.checkpointer.redis-ref", "default")
                        .property("openjiuwen.service.middleware.redis.default.type", "standalone")
                        .property("openjiuwen.service.middleware.redis.default.host", requiredEnv("REDIS_HOST"))
                        .property("openjiuwen.service.middleware.redis.default.port", requiredEnv("REDIS_PORT"))
                        .property("openjiuwen.service.middleware.redis.default.database", "0")
                        .property("openjiuwen.service.middleware.redis.default.timeout-ms", "5000")
                        .property("openjiuwen.service.middleware.redis.default.encrypted-password", ""));

        assertThatThrownBy(builder::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("process exited before becoming ready")
                .hasMessageContaining("主 IR 未找到")
                .satisfies(exception -> assertThat(exception.getMessage())
                        .doesNotContain("Started ControllerIrDemo")
                        .doesNotContain("/.well-known/agent.json responded 200"));
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        org.junit.jupiter.api.Assumptions.assumeTrue(value != null && !value.isBlank(),
                () -> "missing required environment variable: " + name);
        return value;
    }
}

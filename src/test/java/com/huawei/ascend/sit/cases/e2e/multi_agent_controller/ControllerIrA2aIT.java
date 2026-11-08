/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.e2e.multi_agent_controller;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.transport.MessageProtocol;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** FEAT-031 §7 controller-IR demo driven through the standard A2A entrypoint. */
@Tag("e2e")
@Tag("multi-agent-controller")
@Tag("feat-031")
@Feature("FEAT-031: Studio DSL Java 承载")
class ControllerIrA2aIT extends BaseManagedStackTest {
    private static final String SUT = "controller-ir-demo";
    private static final long FLOW_TIMEOUT_MS = 120_000L;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        String apiKey = requiredEnv("LLM_API_KEY");
        String baseUrl = requiredAny("LLM_API_BASE", "LLM_BASE_URL");
        String model = requiredAny("LLM_MODEL", "LLM_MODEL_NAME");
        String redisHost = requiredEnv("REDIS_HOST");
        String redisPort = requiredEnv("REDIS_PORT");
        return SutStack.builder(config)
                .streaming(true)
                .agent(SUT, agent -> agent
                        .env("LLM_API_KEY", apiKey)
                        .env("LLM_API_BASE", baseUrl)
                        .env("LLM_MODEL_NAME", model)
                        .env("LLM_MODEL_TYPE", System.getenv().getOrDefault("LLM_MODEL_TYPE", "OpenAI"))
                        .env("REDIS_HOST", redisHost)
                        .env("REDIS_PORT", redisPort)
                        .property("openjiuwen.service.middleware.checkpointer.type", "redis")
                        .property("openjiuwen.service.middleware.checkpointer.redis-ref", "default")
                        .property("openjiuwen.service.middleware.redis.default.type", "standalone")
                        .property("openjiuwen.service.middleware.redis.default.host", redisHost)
                        .property("openjiuwen.service.middleware.redis.default.port", redisPort)
                        .property("openjiuwen.service.middleware.redis.default.database", "0")
                        .property("openjiuwen.service.middleware.redis.default.timeout-ms", "5000")
                        .property("openjiuwen.service.middleware.redis.default.encrypted-password", ""));
    }

    @Test
    @Story("MA-031-05-20-E01: default workflow A2A")
    @DisplayName("闲聊请求经标准 A2A 进入默认工作流并产生业务输出")
    void defaultWorkflowRunsThroughA2a() {
        InteractionFlow.FlowResult result = InteractionFlow.of(client(SUT))
                .withTimeoutMs(FLOW_TIMEOUT_MS)
                .withContextId(context("E01"))
                .send("你好")
                .awaitState(TaskState.TASK_STATE_INPUT_REQUIRED)
                .execute();
        assertThat(result.round(0).generatedText()).isNotBlank();
    }

    @Test
    @Story("MA-031-05-20-E02: intent workflow A2A")
    @DisplayName("理财咨询经意图识别进入理财工作流")
    void intentWorkflowRunsThroughA2a() {
        InteractionFlow.FlowResult result = InteractionFlow.of(client(SUT))
                .withTimeoutMs(FLOW_TIMEOUT_MS)
                .withContextId(context("E02"))
                .send("手机银行理财功能怎么用")
                .awaitState(TaskState.TASK_STATE_INPUT_REQUIRED)
                .execute();
        assertThat(result.round(0).generatedText()).contains("理财");
    }

    @Test
    @Story("MA-031-05-20-E03: multiple workflow routing")
    @DisplayName("贷款与理财请求进入不同工作流")
    void multipleWorkflowsRemainDistinct() {
        InteractionFlow.FlowResult wealth = InteractionFlow.of(client(SUT))
                .withTimeoutMs(FLOW_TIMEOUT_MS)
                .withContextId(context("E03-wealth"))
                .send("理财功能怎么用")
                .awaitState(TaskState.TASK_STATE_INPUT_REQUIRED)
                .execute();
        InteractionFlow.FlowResult loan = InteractionFlow.of(client(SUT))
                .withTimeoutMs(FLOW_TIMEOUT_MS)
                .withContextId(context("E03-loan"))
                .send("贷款功能怎么用")
                .awaitState(TaskState.TASK_STATE_INPUT_REQUIRED)
                .execute();
        assertThat(wealth.round(0).generatedText()).contains("理财");
        assertThat(loan.round(0).generatedText()).contains("贷款");
    }

    @Test
    @Story("MA-031-05-20-E05: loop and user-input resume")
    @DisplayName("交互式工作流在等待输入后可继续同一任务")
    void interactiveWorkflowCanResume() {
        InteractionFlow.FlowResult first = InteractionFlow.of(client(SUT))
                .withTimeoutMs(FLOW_TIMEOUT_MS)
                .withContextId(context("E05"))
                .send("理财功能怎么用")
                .awaitState(TaskState.TASK_STATE_INPUT_REQUIRED)
                .execute();
        assertThat(first.round(0).taskId()).isNotBlank();
        InteractionFlow.of(client(SUT))
                .withTimeoutMs(FLOW_TIMEOUT_MS)
                .withContextId(first.round(0).contextId())
                .withTaskId(first.round(0).taskId())
                .send("继续")
                .awaitState(TaskState.TASK_STATE_INPUT_REQUIRED)
                .execute();
    }

    @Test
    @Story("MA-031-05-20-E10: streaming and blocking A2A")
    @DisplayName("同一 controller-IR SUT 支持流式与阻塞 A2A")
    void streamingAndBlockingEntriesBothConsumeConfiguration() {
        InteractionFlow.FlowResult streamed = InteractionFlow.of(client(SUT))
                .protocol(MessageProtocol.A2A_STREAM)
                .withTimeoutMs(FLOW_TIMEOUT_MS)
                .withContextId(context("E10-stream"))
                .send("你好")
                .awaitState(TaskState.TASK_STATE_INPUT_REQUIRED)
                .execute();
        InteractionFlow.FlowResult blocking = InteractionFlow.of(client(SUT))
                .protocol(MessageProtocol.A2A_SYNC)
                .withTimeoutMs(FLOW_TIMEOUT_MS)
                .withContextId(context("E10-sync"))
                .send("你好")
                .awaitState(TaskState.TASK_STATE_COMPLETED)
                .execute();
        assertThat(streamed.round(0).generatedText()).isNotBlank();
        assertThat(blocking.round(0).generatedText()).isNotBlank();
    }

    private static String requiredAny(String... names) {
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        Assumptions.abort("missing required environment variable: " + String.join("/", names));
        return null;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        Assumptions.assumeTrue(value != null && !value.isBlank(),
                () -> "missing required environment variable: " + name);
        return value;
    }

    private static String context(String suffix) {
        return "feat031-s7-" + suffix + '-' + UUID.randomUUID().toString().substring(0, 8);
    }
}

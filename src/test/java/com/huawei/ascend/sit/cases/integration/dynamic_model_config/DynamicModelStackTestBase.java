/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.dynamic_model_config;

import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.fixtures.model.RealModelRecordingProxy;
import com.huawei.ascend.sit.lifecycle.SutStack;

import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

/**
 * Shared SUT topology for FEAT-050-CORE: the customer-side carrier plus two distinguishable model
 * endpoints, both of which are <strong>real</strong> models reached through a pass-through
 * recording proxy ({@link RealModelRecordingProxy}).
 *
 * <p>The proxy forwards every request to the real model and streams the real answer back; the only
 * test-side effect is counting received calls, which is the routing oracle. Answer text is never
 * fabricated (no Mock LLM). Endpoints are created before the stack starts because the carrier
 * registers them at startup. Subclasses vary the carrier wiring (instance default model, pool
 * on/off) through {@link #buildStack(TestConfig)}.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class DynamicModelStackTestBase {

    protected static final String SUT = "dynamic-model-config-sit";

    /** 第二份真实凭据的前缀；未配置 LLM2 时退回第一份（方案二：两个条目仍可区分）。 */
    protected static final String SECOND_MODEL_PREFIX =
            isPresent(System.getenv("LLM2_API_BASE")) ? "LLM2_" : "LLM_";

    protected final RealModelRecordingProxy endpointA = startEndpoint("LLM_");
    protected final RealModelRecordingProxy endpointB = startEndpoint(SECOND_MODEL_PREFIX);

    protected TestConfig config;
    protected SutStack stack;

    @BeforeAll
    void startDynamicModelStack() {
        config = TestConfig.load();
        stack = buildStack(config).start();
    }

    @AfterAll
    void stopDynamicModelStack() {
        if (stack != null) {
            stack.close();
        }
        closeQuietly(endpointA);
        closeQuietly(endpointB);
    }

    protected abstract SutStack.Builder buildStack(TestConfig config);

    /**
     * Baseline carrier wiring: model A → endpoint A, model B → endpoint B, process-wide default A.
     *
     * @param config test configuration
     * @param a endpoint backing model A
     * @param b endpoint backing model B
     * @return stack builder
     */
    protected static SutStack.Builder carrier(
            TestConfig config, RealModelRecordingProxy a, RealModelRecordingProxy b) {
        return carrier(config, a, b, java.util.Map.of());
    }

    /**
     * Carrier wiring with additional Spring property overrides for the fixture.
     *
     * @param config test configuration
     * @param a endpoint backing model A
     * @param b endpoint backing model B
     * @param extraProperties extra Spring properties for the fixture process
     * @return stack builder
     */
    protected static SutStack.Builder carrier(
            TestConfig config, RealModelRecordingProxy a, RealModelRecordingProxy b,
            java.util.Map<String, String> extraProperties) {
        return SutStack.builder(config).agent(SUT, agent -> agent
                .property("dmcsit.model-a-base", a.baseUrl())
                .property("dmcsit.model-a-name", a.modelName())
                .property("dmcsit.model-b-base", b.baseUrl())
                .property("dmcsit.model-b-name", b.modelName())
                .property("dmcsit.model-c-base", a.baseUrl())
                .property("dmcsit.model-c-name", a.modelName())
                .property("dmcsit.default-model-id", extraProperties.getOrDefault("dmcsit.default-model-id", "A"))
                .property("dmcsit.pool-enabled", extraProperties.getOrDefault("dmcsit.pool-enabled", "true"))
                .property("dmcsit.instance-enabled", extraProperties.getOrDefault("dmcsit.instance-enabled", "true"))
                .property("dmcsit.instance-model-id", extraProperties.getOrDefault("dmcsit.instance-model-id", "A"))
                // 其余用例级开关（如多步轮 dmcsit.tool-step-enabled）也必须透传，否则 SUT 收不到。
                .property("dmcsit.tool-step-enabled",
                        extraProperties.getOrDefault("dmcsit.tool-step-enabled", "false"))
                .env("DMCSIT_API_KEY", System.getenv("LLM_API_KEY")));
    }

    /**
     * 真实模型回答的最低要求：非空。
     *
     * <p>路由类断言的主 Oracle 是"哪一端点收到了调用"（见各用例的 requestsSince 断言）；
     * 真实模型输出内容不可预测，因此不对文本内容做匹配。</p>
     *
     * @param round 本轮结果
     * @param caseId 用例标识（仅用于失败信息）
     */
    protected static void assertRealModelAnswered(InteractionFlow.RoundResult round, String caseId) {
        assertThat(round.generatedText())
                .as("[%s] 真实模型应返回非空回答（内容不做匹配），实际=%s", caseId, round.generatedText())
                .isNotBlank();
    }

    protected InteractionFlow.RoundResult send(String caseId, String input, Map<String, Object> metadata) {
        return InteractionFlow.of(stack.client(SUT))
                .withContextId(caseId + "-" + System.nanoTime())
                .send(input)
                .withMetadata(metadata)
                .awaitState(TaskState.TASK_STATE_COMPLETED)
                .execute()
                .round(0);
    }

    protected InteractionFlow.RoundResult sendInSession(String contextId, String input, Map<String, Object> metadata) {
        return InteractionFlow.of(stack.client(SUT))
                .withContextId(contextId)
                .send(input)
                .withMetadata(metadata)
                .awaitState(TaskState.TASK_STATE_COMPLETED)
                .execute()
                .round(0);
    }

    /**
     * 预期任务以指定状态终结的请求（用于"无可用模型必须拒绝"这类负向用例）。
     *
     * <p>负向用例不得靠捕获断言异常判定通过，因此这里显式声明期望的终结状态，让框架把状态交回来。</p>
     *
     * @param caseId 用例标识（用于生成唯一 contextId）
     * @param input 用户输入
     * @param metadata 请求元数据
     * @param expected 期望的任务终结状态
     * @return 本轮结果
     */
    protected InteractionFlow.RoundResult sendExpecting(String caseId, String input,
            Map<String, Object> metadata, TaskState expected) {
        return InteractionFlow.of(stack.client(SUT))
                .withContextId(caseId + "-" + System.nanoTime())
                .send(input)
                .withMetadata(metadata)
                .awaitState(expected)
                .execute()
                .round(0);
    }

    protected DynamicModelControlClient control() {
        return new DynamicModelControlClient(stack.baseUrl(SUT));
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static RealModelRecordingProxy startEndpoint(String envPrefix) {
        try {
            return RealModelRecordingProxy.start(envPrefix);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("cannot start real-model recording proxy for " + envPrefix, exception);
        }
    }

    private static void closeQuietly(RealModelRecordingProxy endpoint) {
        try {
            endpoint.close();
        } catch (RuntimeException ignored) {
            // 清理失败不影响结论；端点资源随 JVM 退出释放。
        }
    }
}

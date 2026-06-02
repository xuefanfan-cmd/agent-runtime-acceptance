package com.huawei.ascend.sit.cases.component.agents.weather;

import com.huawei.ascend.sit.base.BaseComponentTest;
import com.huawei.ascend.sit.client.InteractionFlow;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Weather Agent multi-turn conversation tests using Fluent DSL.
 *
 * <p>Uses {@link InteractionFlow} to express multi-turn interactions
 * as readable Java code — inputs, expected states, and assertions
 * are all visible inline.</p>
 *
 * <p>These tests verify that the Weather Agent correctly handles
 * multi-turn dialogue including information gathering (INTERRUPT)
 * and response generation.</p>
 */
@Tag("component")
@Disabled("示例用例，待联调验证后逐个放开")
class WeatherConversationTest extends BaseComponentTest {

    @Test
    @DisplayName("天气查询：指定城市 → Agent 直接返回结果")
    void weatherQuery_withCity_shouldReturnResultDirectly() {
        InteractionFlow.of(a2aClient)
                .withTimeoutMs(config.getPollTimeoutSeconds() * 1000L)
                .send("北京今天天气怎么样")
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertTask(task -> {
                        assertThat(task.artifacts()).as("天气结果应包含 artifact").isNotEmpty();
                    })
                .execute();
    }

    @Test
    @DisplayName("天气查询：未指定城市 → Agent 中断要求补充 → 提供城市后返回结果")
    void weatherQuery_withoutCity_shouldInterruptThenComplete() {
        InteractionFlow.of(a2aClient)
                .withTimeoutMs(config.getPollTimeoutSeconds() * 1000L)
                // 第1轮：不指定城市，Agent 应中断请求补充信息
                .send("今天天气怎么样")
                    .awaitState(TaskState.TASK_STATE_INPUT_REQUIRED)
                    .expectEvents(count -> assertThat(count).isGreaterThan(0))
                // 第2轮：提供城市信息，Agent 应返回完整结果
                .send("北京")
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertTask(task -> {
                        assertThat(task.artifacts()).as("补全城市后应返回天气结果").isNotEmpty();
                    })
                .execute();
    }

    @Test
    @DisplayName("天气查询：多轮追问同一话题保持上下文")
    void weatherQuery_followUp_shouldMaintainContext() {
        InteractionFlow.of(a2aClient)
                .withTimeoutMs(config.getPollTimeoutSeconds() * 1000L)
                // 第1轮：查询天气
                .send("上海今天天气怎么样")
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                // 第2轮：追问明天天气（上下文延续）
                .send("那明天呢？")
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertTask(task -> {
                        assertThat(task.artifacts()).as("追问应返回天气结果").isNotEmpty();
                    })
                .execute();
    }

    @Test
    @DisplayName("天气查询：模糊输入后多次补全信息")
    void weatherQuery_ambiguousInput_shouldHandleGracefully() {
        InteractionFlow.of(a2aClient)
                .withTimeoutMs(config.getPollTimeoutSeconds() * 1000L)
                // 第1轮：极其模糊的输入
                .send("天气")
                    .awaitState(TaskState.TASK_STATE_INPUT_REQUIRED)
                // 第2轮：提供城市
                .send("杭州")
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertTask(task -> {
                        assertThat(task.artifacts()).isNotEmpty();
                    })
                .execute();
    }
}

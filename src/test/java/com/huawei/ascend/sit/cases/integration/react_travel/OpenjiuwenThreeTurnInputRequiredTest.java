package com.huawei.ascend.sit.cases.integration.react_travel;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OJ-05 — openjiuwen three-turn INPUT_REQUIRED collection (full chain, sync send).
 *
 * <p>Aligned with C-03 / {@link StreamingTravelPlanningTest} three-turn shape; SUT is the
 * openjiuwen travel chain via {@code -Dtest.env=openjiuwen}.</p>
 *
 * <p>See {@code docs/cases/reactagent/OJ-05-openjiuwen-three-turn-input-required.md}.</p>
 */
@Tag("integration")
@Tag("openjiuwen")
class OpenjiuwenThreeTurnInputRequiredTest extends BaseManagedStackTest {

    private static final String MAINPLAN = "mainplan";
    private static final String TRIP = "trip";
    private static final String HOTEL = "hotel";

    /**
     * Turn texts follow C-03 shape. Turn1/2 add anti-complete / anti-default-origin clauses so
     * openjiuwen does not skip {@code INPUT_REQUIRED} or assume Shenzhen before origin is collected.
     */
    private static final String TURN1 =
            "我要去北京出差。出发地和行程天数都还没定，请先追问缺失信息，不要直接做行程规划。";
    private static final String TURN2 =
            "出差3天，下周二出发。出发城市还没定，不是深圳，请先继续追问，不要调用行程规划。";
    private static final String TURN3 =
            "从上海出发。差标：每晚不超过 800 元、最低 4 星、协议品牌 全季/亚朵/希尔顿欢朋。"
                    + "偏好：国贸附近，需要会议室。请据此完成完整行程规划。";

    /** Full-chain Turn3 (mainplan→trip→hotel) often exceeds the default 120s poll window. */
    private static final long FLOW_TIMEOUT_MS = 300_000L;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .streaming(false)
                .agent(HOTEL)
                .agent(TRIP, a -> a.downstream(HOTEL))
                .agent(MAINPLAN, a -> a.downstream(TRIP));
    }

    @Test
    @DisplayName("OJ-05: 三轮信息收集 — INPUT_REQUIRED→INPUT_REQUIRED→COMPLETED")
    void oj05_threeTurnSync_followsInputRequiredThenCompleted() {
        long timeoutMs = Math.max(config.getPollTimeoutSeconds() * 1000L, FLOW_TIMEOUT_MS);
        InteractionFlow.FlowResult result = InteractionFlow.of(client(MAINPLAN))
                .withMetadata(Map.of(
                        "userId", "manual-user",
                        "agentId", "mainplan"))
                .withTimeoutMs(timeoutMs)
                .send(TURN1)
                    .awaitState(TaskState.TASK_STATE_INPUT_REQUIRED)
                    .assertGenerated(reply -> assertThat(reply)
                            .as("OJ-05.A turn1 clarifying reply")
                            .isNotBlank())
                .send(TURN2)
                    .awaitState(TaskState.TASK_STATE_INPUT_REQUIRED)
                    .assertGenerated(reply -> assertThat(reply)
                            .as("OJ-05.B turn2 clarifying reply")
                            .isNotBlank())
                .send(TURN3)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertAnswer(text -> {
                        assertThat(text).as("OJ-05.C turn3 text").isNotBlank();
                        assertThat(text.length())
                                .as("OJ-05.C turn3 substantive length")
                                .isGreaterThan(8);
                    })
                .execute();

        assertThat(result.roundCount()).as("OJ-05 round count").isEqualTo(3);
        String contextId = result.round(0).contextId();
        assertThat(contextId).as("OJ-05.D turn1 contextId").isNotBlank();
        assertThat(result.round(1).contextId())
                .as("OJ-05.D turn2 contextId matches turn1")
                .isEqualTo(contextId);
        assertThat(result.round(2).contextId())
                .as("OJ-05.D turn3 contextId matches turn1")
                .isEqualTo(contextId);
    }
}

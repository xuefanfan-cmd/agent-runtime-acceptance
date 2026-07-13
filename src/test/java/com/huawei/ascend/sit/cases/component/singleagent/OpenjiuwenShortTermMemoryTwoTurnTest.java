package com.huawei.ascend.sit.cases.component.singleagent;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.client.TaskTextExtractor;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OJ-03 — openjiuwen short-term memory across two turns (same contextId).
 *
 * <p>Driven by {@link InteractionFlow}: Turn1 incomplete request pauses at
 * {@code INPUT_REQUIRED}; Turn2 continues the same task and must reflect Turn1 intent.
 * On a single-mainplan stack Turn2 may stay {@code INPUT_REQUIRED} when {@code trip} is
 * unreachable — that is accepted; the memory signal is weak-semantic reply text.</p>
 *
 * <p>See {@code docs/cases/reactagent/OJ-03-openjiuwen-short-term-memory-two-turn.md}.</p>
 */
@Tag("component")
@Tag("openjiuwen")
@Tag("smoke")
class OpenjiuwenShortTermMemoryTwoTurnTest extends BaseManagedStackTest {

    private static final String MAINPLAN = "mainplan";

    /** Matches {@code testdata/component/singleagent/oj-03-two-turn-dialogue.json}. */
    private static final String TURN1_TEXT = "我要出差";
    private static final String TURN2_TEXT = "去北京，明天出发，3天";
    private static final List<String> TURN2_MUST_MATCH_ANY = List.of("北京", "出差");
    private static final List<String> TURN2_MUST_NOT_MATCH_ANY = List.of(
            "是否要出差", "你想出差吗", "请说明是否要出差");
    private static final List<TaskState> TURN2_ALLOWED_STATES = List.of(
            TaskState.TASK_STATE_COMPLETED,
            TaskState.TASK_STATE_INPUT_REQUIRED);

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .streaming(false)
                .agent(MAINPLAN);
    }

    @Test
    @DisplayName("OJ-03: 两轮同 contextId — Turn2 理解 Turn1 出差意图")
    void oj03_twoTurnSync_preservesShortTermContext() {
        InteractionFlow.FlowResult result = InteractionFlow.of(client(MAINPLAN))
                .withTimeoutMs(config.getPollTimeoutSeconds() * 1000L)
                .send(TURN1_TEXT)
                    .awaitState(TaskState.TASK_STATE_INPUT_REQUIRED)
                    .assertTask(task -> assertThat(TaskTextExtractor.textOf(task))
                            .as("OJ-03 Turn1 clarifying reply")
                            .isNotBlank())
                .send(TURN2_TEXT)
                    // Prefer INPUT_REQUIRED wait so unreachable trip (single mainplan) does not
                    // hang on awaitTerminalState; COMPLETED also accepted via mayReachState.
                    .mayReachState(TaskState.TASK_STATE_INPUT_REQUIRED)
                    .assertThat(ctx -> assertThat(ctx.taskState())
                            .as("OJ-03.A Turn2 state (COMPLETED or INPUT_REQUIRED on single mainplan)")
                            .isIn(TURN2_ALLOWED_STATES))
                    .assertTask(task -> {
                        String text = TaskTextExtractor.textOf(task);
                        assertThat(text).as("OJ-03.A Turn2 text").isNotBlank();
                        assertThat(TURN2_MUST_MATCH_ANY.stream().anyMatch(text::contains))
                                .as("OJ-03.B turn2MustMatchAny — text should reflect Turn1 travel intent")
                                .isTrue();
                        for (String forbidden : TURN2_MUST_NOT_MATCH_ANY) {
                            assertThat(text)
                                    .as("OJ-03.B must not reset session with: %s", forbidden)
                                    .doesNotContain(forbidden);
                        }
                    })
                .execute();

        assertThat(result.round(1).contextId())
                .as("OJ-03.C Turn2 contextId matches Turn1")
                .isEqualTo(result.round(0).contextId());
    }
}

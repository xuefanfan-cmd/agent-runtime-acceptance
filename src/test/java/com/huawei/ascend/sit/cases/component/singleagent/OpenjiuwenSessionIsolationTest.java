package com.huawei.ascend.sit.cases.component.singleagent;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OJ-04 — openjiuwen session isolation (different contextId, serial execution).
 *
 * <p>Two independent {@link InteractionFlow}s with pinned {@code contextId}s. Either turn may be
 * {@code COMPLETED} or {@code INPUT_REQUIRED} (Turn1 often completes when city+duration is enough;
 * Turn2 may stay {@code INPUT_REQUIRED} without trip). Isolation is asserted via contextId
 * stability and weak-semantic city heuristics.</p>
 *
 * <p>See {@code docs/cases/reactagent/OJ-04-openjiuwen-session-isolation.md}.</p>
 */
@Tag("component")
@Tag("openjiuwen")
class OpenjiuwenSessionIsolationTest extends BaseManagedStackTest {

    private static final String MAINPLAN = "mainplan";
    private static final String TURN2_TEXT = "住宿标准每晚 800 以内";
    private static final List<String> TURN2_MUST_MATCH_ANY = List.of("住宿", "800", "出差");
    /** Turn1 may COMPLETED when LLM treats city+duration as enough (no rail). */
    private static final List<TaskState> TURN_ALLOWED_STATES = List.of(
            TaskState.TASK_STATE_COMPLETED,
            TaskState.TASK_STATE_INPUT_REQUIRED);

    private static final String SESSION_A = "oj-04-session-a";
    private static final String TURN1_A = "我计划去上海出差 3 天";
    private static final String CITY_A = "上海";
    private static final String FORBIDDEN_A = "北京";

    private static final String SESSION_B = "oj-04-session-b";
    private static final String TURN1_B = "我计划去北京出差 3 天";
    private static final String CITY_B = "北京";
    private static final String FORBIDDEN_B = "上海";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .streaming(false)
                .agent(MAINPLAN);
    }

    @Test
    @DisplayName("OJ-04: 不同 contextId 串行两 session — 业务输出不串扰")
    void oj04_serialSessions_doNotCrossPollinate() {
        long timeoutMs = config.getPollTimeoutSeconds() * 1000L;

        InteractionFlow.FlowResult resultA = runSession(
                SESSION_A, TURN1_A, CITY_A, FORBIDDEN_A, timeoutMs);
        InteractionFlow.FlowResult resultB = runSession(
                SESSION_B, TURN1_B, CITY_B, FORBIDDEN_B, timeoutMs);

        assertThat(resultA.round(0).contextId())
                .as("OJ-04.A session-A contextId stable")
                .isEqualTo(SESSION_A)
                .isEqualTo(resultA.round(1).contextId());
        assertThat(resultB.round(0).contextId())
                .as("OJ-04.A session-B contextId stable")
                .isEqualTo(SESSION_B)
                .isEqualTo(resultB.round(1).contextId());
        assertThat(resultA.round(0).contextId())
                .as("OJ-04.A session contextIds must differ")
                .isNotEqualTo(resultB.round(0).contextId());
    }

    private InteractionFlow.FlowResult runSession(
            String contextId, String turn1, String expectedCity, String forbiddenCity, long timeoutMs) {
        AtomicReference<String> turn1Text = new AtomicReference<>("");

        return InteractionFlow.of(client(MAINPLAN))
                .withContextId(contextId)
                .withTimeoutMs(timeoutMs)
                .send(turn1)
                    // City+duration often yields COMPLETED (default origin); do not require INPUT_REQUIRED.
                    .mayReachState(TaskState.TASK_STATE_INPUT_REQUIRED)
                    .assertThat(ctx -> assertThat(ctx.taskState())
                            .as("%s turn1 state", contextId)
                            .isIn(TURN_ALLOWED_STATES))
                    .assertGenerated(text -> {
                        turn1Text.set(text);
                        assertThat(text).as("%s turn1 reply", contextId).isNotBlank();
                    })
                .send(TURN2_TEXT)
                    .mayReachState(TaskState.TASK_STATE_INPUT_REQUIRED)
                    .assertThat(ctx -> assertThat(ctx.taskState())
                            .as("%s turn2 state", contextId)
                            .isIn(TURN_ALLOWED_STATES))
                    .assertGenerated(turn2Text -> {
                        String dialogue = turn1Text.get() + "\n" + turn2Text;
                        assertCityIsolation(turn2Text, dialogue, expectedCity, forbiddenCity, contextId);
                    })
                .execute();
    }

    private static void assertCityIsolation(
            String turn2Text, String agentDialogue, String expectedCity, String forbiddenCity, String label) {
        assertThat(turn2Text).as("%s turn2 text", label).isNotBlank();
        assertThat(turn2Text)
                .as("%s must not treat forbidden city as destination", label)
                .doesNotContain("去" + forbiddenCity);

        int forbiddenIndex = turn2Text.indexOf(forbiddenCity);
        int expectedIndex = turn2Text.indexOf(expectedCity);
        if (forbiddenIndex >= 0 && expectedIndex >= 0) {
            assertThat(expectedIndex)
                    .as("%s expected city should appear before forbidden city when both present", label)
                    .isLessThan(forbiddenIndex);
        }

        boolean turn2Continuation = TURN2_MUST_MATCH_ANY.stream().anyMatch(turn2Text::contains);
        boolean agentRetainedCity = agentDialogue.contains(expectedCity);
        assertThat(turn2Continuation || agentRetainedCity)
                .as("%s turn2 continues session or dialogue retains %s", label, expectedCity)
                .isTrue();
    }
}

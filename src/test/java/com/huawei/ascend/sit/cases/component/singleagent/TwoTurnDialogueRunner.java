package com.huawei.ascend.sit.cases.component.singleagent;

import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.A2aStreamErrors;
import com.huawei.ascend.sit.client.TaskTextExtractor;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Shared two-turn streaming dialogue for B-03 / B-04 (Turn1 → Turn2 with same {@code contextId}).
 *
 * <p>Scenario constants live here (no main ScenarioData). Matches
 * {@code testdata/integration/react_travel/b03-redis-multi-turn.json}.</p>
 */
public final class TwoTurnDialogueRunner {

    private static final Logger LOG = Logger.getLogger(TwoTurnDialogueRunner.class.getName());

    static final String TURN1_TEXT = "我要出差";
    static final String TURN2_TEXT =
            "2026年6月19日从深圳去北京出差3天，给我推荐酒店，没有任何要求，随机选一个就好";
    static final long TURN1_TIMEOUT_MS = 90_000L;
    static final long TURN2_TIMEOUT_MS = 120_000L;
    static final List<TaskState> TURN1_ALLOWED_TERMINAL_STATES = List.of(
            TaskState.TASK_STATE_COMPLETED,
            TaskState.TASK_STATE_INPUT_REQUIRED);
    static final TaskState TURN2_EXPECTED_TERMINAL_STATE = TaskState.TASK_STATE_COMPLETED;
    static final List<String> TURN2_MUST_MATCH_ANY = List.of("北京", "出差");
    static final List<String> TURN2_MUST_NOT_MATCH_ANY = List.of(
            "您要去哪里出差",
            "请问您要去哪里",
            "请告诉我您的目的地",
            "您打算去哪里");

    private TwoTurnDialogueRunner() {
    }

    record Result(
            TaskState turn1State,
            String contextId,
            TaskState turn2State,
            String turn2Text
    ) {
    }

    public static Result run(A2aServiceClient a2a, String logPrefix) throws InterruptedException {
        A2aEventCollector turn1Collector = new A2aEventCollector();
        AtomicReference<Throwable> turn1Error = new AtomicReference<>();
        Thread turn1Thread = Thread.ofVirtual().name(logPrefix + "-turn1").start(() ->
                a2a.sendMessage(
                        A2A.toUserMessage(TURN1_TEXT),
                        List.of(turn1Collector.createConsumer()),
                        error -> turn1Error.set(error)));

        TaskState turn1State = TwoTurnDialogueAwait.awaitTurn1Outcome(turn1Collector);
        turn1Thread.join(TURN1_TIMEOUT_MS);
        assertStreamHealthy(turn1Error, logPrefix + " Turn1");
        assertThat(turn1Collector.eventCount())
                .as(logPrefix + " Turn1 stream events")
                .isGreaterThan(0);

        String contextId = turn1Collector.findFirstContextId();
        assertThat(contextId).as(logPrefix + " Turn1 contextId").isNotBlank();
        LOG.info(logPrefix + " turn1_terminal_state=" + turn1State);

        Message turn2Message = Message.builder(A2A.toUserMessage(TURN2_TEXT))
                .contextId(contextId)
                .build();
        A2aEventCollector turn2Collector = new A2aEventCollector();
        AtomicReference<Throwable> turn2Error = new AtomicReference<>();
        Thread turn2Thread = Thread.ofVirtual().name(logPrefix + "-turn2").start(() ->
                a2a.sendMessage(
                        turn2Message,
                        List.of(turn2Collector.createConsumer()),
                        error -> turn2Error.set(error)));

        TaskState turn2State = turn2Collector.awaitTerminalState(TURN2_TIMEOUT_MS);
        turn2Thread.join(TURN2_TIMEOUT_MS);
        assertStreamHealthy(turn2Error, logPrefix + " Turn2");
        assertThat(turn2State)
                .as(logPrefix + " Turn2 terminal state")
                .isEqualTo(TURN2_EXPECTED_TERMINAL_STATE);

        String taskId = turn2Collector.findFirstTaskId();
        assertThat(taskId).as(logPrefix + " Turn2 taskId").isNotBlank();
        Task turn2Task = a2a.getTask(taskId);
        String turn2Text = TaskTextExtractor.textOf(turn2Task);
        LOG.info(logPrefix + " Turn2 reply (truncated): "
                + (turn2Text.length() > 200 ? turn2Text.substring(0, 200) + "..." : turn2Text));

        TwoTurnDialogueAssertions.assertTurn2Understanding(turn2Text);
        return new Result(turn1State, contextId, turn2State, turn2Text);
    }

    private static void assertStreamHealthy(AtomicReference<Throwable> streamError, String label) {
        Throwable failure = streamError.get();
        if (failure != null && !A2aStreamErrors.isBenignShutdown(failure)) {
            fail(label + " message/stream failed", failure);
        }
    }
}

package com.huawei.ascend.sit.cases.integration.checkpointer;

import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.A2aStreamErrors;
import com.huawei.ascend.sit.client.TaskTextExtractor;
import com.huawei.ascend.sit.model.integration.checkpointer.RedisMultiTurnScenarioData;
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
 */
final class TwoTurnDialogueRunner {

    private static final Logger LOG = Logger.getLogger(TwoTurnDialogueRunner.class.getName());

    private TwoTurnDialogueRunner() {
    }

    record Result(
            TaskState turn1State,
            String contextId,
            TaskState turn2State,
            String turn2Text
    ) {
    }

    static Result run(A2aServiceClient a2a, RedisMultiTurnScenarioData scenario, String logPrefix)
            throws InterruptedException {
        A2aEventCollector turn1Collector = new A2aEventCollector();
        AtomicReference<Throwable> turn1Error = new AtomicReference<>();
        Thread turn1Thread = Thread.ofVirtual().name(logPrefix + "-turn1").start(() ->
                a2a.sendMessage(
                        A2A.toUserMessage(scenario.turn1Text()),
                        List.of(turn1Collector.createConsumer()),
                        error -> turn1Error.set(error)));

        TaskState turn1State = TwoTurnDialogueAwait.awaitTurn1Outcome(turn1Collector, scenario);
        turn1Thread.join(scenario.turn1TimeoutMs());
        assertStreamHealthy(turn1Error, logPrefix + " Turn1");
        assertThat(turn1Collector.eventCount())
                .as(logPrefix + " Turn1 stream events")
                .isGreaterThan(0);

        String contextId = turn1Collector.findFirstContextId();
        assertThat(contextId).as(logPrefix + " Turn1 contextId").isNotBlank();
        LOG.info(logPrefix + " turn1_terminal_state=" + turn1State);

        Message turn2Message = Message.builder(A2A.toUserMessage(scenario.turn2Text()))
                .contextId(contextId)
                .build();
        A2aEventCollector turn2Collector = new A2aEventCollector();
        AtomicReference<Throwable> turn2Error = new AtomicReference<>();
        Thread turn2Thread = Thread.ofVirtual().name(logPrefix + "-turn2").start(() ->
                a2a.sendMessage(
                        turn2Message,
                        List.of(turn2Collector.createConsumer()),
                        error -> turn2Error.set(error)));

        TaskState turn2State = turn2Collector.awaitTerminalState(scenario.turn2TimeoutMs());
        turn2Thread.join(scenario.turn2TimeoutMs());
        assertStreamHealthy(turn2Error, logPrefix + " Turn2");
        assertThat(turn2State)
                .as(logPrefix + " Turn2 terminal state")
                .isEqualTo(scenario.resolvedTurn2ExpectedState());

        String taskId = turn2Collector.findFirstTaskId();
        assertThat(taskId).as(logPrefix + " Turn2 taskId").isNotBlank();
        Task turn2Task = a2a.getTask(taskId);
        String turn2Text = TaskTextExtractor.textOf(turn2Task);
        LOG.info(logPrefix + " Turn2 reply (truncated): "
                + (turn2Text.length() > 200 ? turn2Text.substring(0, 200) + "..." : turn2Text));

        TwoTurnDialogueAssertions.assertTurn2Understanding(turn2Text, scenario);
        return new Result(turn1State, contextId, turn2State, turn2Text);
    }

    private static void assertStreamHealthy(AtomicReference<Throwable> streamError, String label) {
        Throwable failure = streamError.get();
        if (failure != null && !A2aStreamErrors.isBenignShutdown(failure)) {
            fail(label + " message/stream failed", failure);
        }
    }
}

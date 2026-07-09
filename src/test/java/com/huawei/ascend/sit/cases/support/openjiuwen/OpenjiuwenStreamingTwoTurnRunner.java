package com.huawei.ascend.sit.cases.support.openjiuwen;

import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.A2aStreamErrors;
import com.huawei.ascend.sit.client.TaskTextExtractor;
import com.huawei.ascend.sit.model.openjiuwen.OpenjiuwenTwoTurnScenarioData;
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
 * Streaming two-turn dialogue helper for openjiuwen OJ-06+ ({@code message/stream}).
 *
 * <p>Turn1 may end {@code INPUT_REQUIRED}; Turn2 resumes with {@code taskId} when needed.
 * Turn2 allowed states come from testdata (typically {@code COMPLETED} and optionally
 * {@code INPUT_REQUIRED}).</p>
 */
public final class OpenjiuwenStreamingTwoTurnRunner {

    private static final Logger LOG = Logger.getLogger(OpenjiuwenStreamingTwoTurnRunner.class.getName());

    private OpenjiuwenStreamingTwoTurnRunner() {
    }

    public record Result(TaskState turn1State, String contextId, TaskState turn2State, String turn2Text) {
    }

    public static Result run(A2aServiceClient a2a, OpenjiuwenTwoTurnScenarioData scenario, String logPrefix)
            throws InterruptedException {
        A2aEventCollector turn1Collector = new A2aEventCollector();
        AtomicReference<Throwable> turn1Error = new AtomicReference<>();
        Thread turn1Thread = Thread.ofVirtual().name(logPrefix + "-turn1").start(() ->
                a2a.sendMessage(
                        A2A.toUserMessage(scenario.turn1Text()),
                        List.of(turn1Collector.createConsumer()),
                        error -> turn1Error.set(error)));

        List<TaskState> turn1Allowed = scenario.resolvedTurn1AllowedStates();
        TaskState turn1State = OpenjiuwenRoundAwait.awaitAllowedOutcome(
                turn1Collector, a2a, scenario.turn1TimeoutMs(), turn1Allowed, logPrefix + " turn1");
        turn1Thread.join(scenario.turn1TimeoutMs());
        assertStreamHealthy(turn1Error, logPrefix + " Turn1");
        assertThat(turn1Allowed).as(logPrefix + " Turn1 allowed states").contains(turn1State);
        assertThat(turn1Collector.eventCount())
                .as(logPrefix + " Turn1 stream events")
                .isGreaterThan(0);

        String contextId = turn1Collector.findFirstContextId();
        assertThat(contextId).as(logPrefix + " Turn1 contextId").isNotBlank();
        LOG.info(logPrefix + " turn1_state=" + turn1State + " contextId=" + contextId);

        String turn1TaskId = turn1Collector.findFirstTaskId();
        Message turn2Message = OpenjiuwenRoundAwait.buildContinuationMessage(
                scenario.turn2Text(), contextId, turn1State, turn1TaskId);

        A2aEventCollector turn2Collector = new A2aEventCollector();
        AtomicReference<Throwable> turn2Error = new AtomicReference<>();
        Thread turn2Thread = Thread.ofVirtual().name(logPrefix + "-turn2").start(() ->
                a2a.sendMessage(
                        turn2Message,
                        List.of(turn2Collector.createConsumer()),
                        error -> turn2Error.set(error)));

        List<TaskState> turn2Allowed = scenario.resolvedTurn2AllowedStates();
        TaskState turn2State = OpenjiuwenRoundAwait.awaitAllowedOutcome(
                turn2Collector, a2a, turn1TaskId, scenario.turn2TimeoutMs(), turn2Allowed, logPrefix + " turn2");
        turn2Thread.join(scenario.turn2TimeoutMs());
        assertStreamHealthy(turn2Error, logPrefix + " Turn2");
        assertThat(turn2Allowed).as(logPrefix + " Turn2 allowed states").contains(turn2State);

        String taskId = turn2Collector.findFirstTaskId();
        assertThat(taskId).as(logPrefix + " Turn2 taskId").isNotBlank();

        Task turn2Task = a2a.getTask(taskId);
        String turn2Text = TaskTextExtractor.textOf(turn2Task);
        assertThat(turn2Text).as(logPrefix + " Turn2 text").isNotBlank();
        LOG.info(logPrefix + " turn2_state=" + turn2State + " reply (truncated): "
                + (turn2Text.length() > 200 ? turn2Text.substring(0, 200) + "..." : turn2Text));

        OpenjiuwenTextAssertions.assertTurn2Understanding(turn2Text, scenario, logPrefix);
        assertThat(turn2Collector.findFirstContextId())
                .as(logPrefix + " Turn2 contextId matches Turn1")
                .isEqualTo(contextId);

        return new Result(turn1State, contextId, turn2State, turn2Text);
    }

    private static void assertStreamHealthy(AtomicReference<Throwable> streamError, String label) {
        Throwable failure = streamError.get();
        if (failure != null && !A2aStreamErrors.isBenignShutdown(failure)) {
            fail(label + " message/stream failed", failure);
        }
    }
}

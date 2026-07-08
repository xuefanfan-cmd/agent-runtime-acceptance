package com.huawei.ascend.sit.cases.openjiuwen;

import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
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
 * Synchronous two-turn dialogue helper for OJ-03.
 *
 * <p>Turn1 often ends {@code INPUT_REQUIRED} ({@code request_user_input}); Turn2 must resume
 * the same task ({@code taskId} + {@code contextId}). Turn2 may end {@code COMPLETED} or
 * {@code INPUT_REQUIRED} (e.g. downstream trip/hotel still collecting).</p>
 */
public final class OpenjiuwenSyncTwoTurnRunner {

    private static final Logger LOG = Logger.getLogger(OpenjiuwenSyncTwoTurnRunner.class.getName());

    private OpenjiuwenSyncTwoTurnRunner() {
    }

    record Result(TaskState turn1State, String contextId, TaskState turn2State, String turn2Text) {
    }

    public static Result run(A2aServiceClient a2a, OpenjiuwenTwoTurnScenarioData scenario, String logPrefix) {
        A2aEventCollector turn1Collector = new A2aEventCollector();
        AtomicReference<Throwable> turn1Error = new AtomicReference<>();

        a2a.sendMessage(
                A2A.toUserMessage(scenario.turn1Text()),
                List.of(turn1Collector.createConsumer()),
                error -> turn1Error.set(error));

        if (turn1Error.get() != null) {
            fail(logPrefix + " Turn1 message/send failed", turn1Error.get());
        }

        List<TaskState> turn1Allowed = scenario.resolvedTurn1AllowedStates();
        TaskState turn1State = OpenjiuwenRoundAwait.awaitAllowedOutcome(
                turn1Collector, a2a, scenario.turn1TimeoutMs(), turn1Allowed, logPrefix + " turn1");
        assertThat(turn1Allowed).as(logPrefix + " Turn1 allowed states").contains(turn1State);

        String contextId = turn1Collector.findFirstContextId();
        assertThat(contextId).as(logPrefix + " Turn1 contextId").isNotBlank();
        LOG.info(logPrefix + " turn1_state=" + turn1State + " contextId=" + contextId);

        String turn1TaskId = turn1Collector.findFirstTaskId();
        Message turn2Message = OpenjiuwenRoundAwait.buildContinuationMessage(
                scenario.turn2Text(), contextId, turn1State, turn1TaskId);

        A2aEventCollector turn2Collector = new A2aEventCollector();
        AtomicReference<Throwable> turn2Error = new AtomicReference<>();

        a2a.sendMessage(
                turn2Message,
                List.of(turn2Collector.createConsumer()),
                error -> turn2Error.set(error));

        if (turn2Error.get() != null) {
            fail(logPrefix + " Turn2 message/send failed", turn2Error.get());
        }

        List<TaskState> turn2Allowed = scenario.resolvedTurn2AllowedStates();
        TaskState turn2State = OpenjiuwenRoundAwait.awaitAllowedOutcome(
                turn2Collector, a2a, turn1TaskId, scenario.turn2TimeoutMs(), turn2Allowed, logPrefix + " turn2");
        assertThat(turn2Allowed).as(logPrefix + " Turn2 allowed states").contains(turn2State);

        String taskId = turn2Collector.findFirstTaskId();
        assertThat(taskId).as(logPrefix + " Turn2 taskId").isNotBlank();

        Task turn2Task = a2a.getTask(taskId);
        String turn2Text = TaskTextExtractor.textOf(turn2Task);
        assertThat(turn2Text).as(logPrefix + " Turn2 text").isNotBlank();
        LOG.info(logPrefix + " turn2_state=" + turn2State + " reply (truncated): "
                + (turn2Text.length() > 200 ? turn2Text.substring(0, 200) + "..." : turn2Text));

        OpenjiuwenTextAssertions.assertTurn2Understanding(turn2Text, scenario);
        assertThat(turn2Collector.findFirstContextId())
                .as(logPrefix + " Turn2 contextId matches Turn1")
                .isEqualTo(contextId);

        return new Result(turn1State, contextId, turn2State, turn2Text);
    }
}

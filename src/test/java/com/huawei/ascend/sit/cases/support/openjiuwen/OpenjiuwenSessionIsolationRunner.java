package com.huawei.ascend.sit.cases.support.openjiuwen;

import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.TaskTextExtractor;
import com.huawei.ascend.sit.model.openjiuwen.OpenjiuwenSessionIsolationScenarioData;
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
 * Serial two-session isolation runner for OJ-04 (distinct {@code contextId} per session).
 */
public final class OpenjiuwenSessionIsolationRunner {

    private static final Logger LOG = Logger.getLogger(OpenjiuwenSessionIsolationRunner.class.getName());

    private OpenjiuwenSessionIsolationRunner() {
    }

    public static void run(A2aServiceClient a2a, OpenjiuwenSessionIsolationScenarioData scenario, String logPrefix) {
        long timeoutMs = scenario.timeoutMs();
        for (OpenjiuwenSessionIsolationScenarioData.SessionCase session : scenario.sessions()) {
            runSession(a2a, scenario, session, timeoutMs, logPrefix);
        }
    }

    private static void runSession(A2aServiceClient a2a, OpenjiuwenSessionIsolationScenarioData scenario,
                                   OpenjiuwenSessionIsolationScenarioData.SessionCase session,
                                   long timeoutMs, String logPrefix) {
        String sessionLabel = logPrefix + " [" + session.contextId() + "]";

        A2aEventCollector turn1Collector = send(
                a2a, messageWithContext(session.turn1(), session.contextId()), sessionLabel + " turn1");

        TaskState turn1State = OpenjiuwenRoundAwait.awaitAllowedOutcome(
                turn1Collector,
                a2a,
                timeoutMs,
                OpenjiuwenRoundAwait.COMPLETED_OR_INPUT_REQUIRED,
                sessionLabel + " turn1");
        assertThat(turn1State).as(sessionLabel + " turn1 state")
                .isIn(OpenjiuwenRoundAwait.COMPLETED_OR_INPUT_REQUIRED);

        String contextId = turn1Collector.findFirstContextId();
        assertThat(contextId).as(sessionLabel + " turn1 contextId").isEqualTo(session.contextId());
        LOG.info(sessionLabel + " turn1_state=" + turn1State + " contextId=" + contextId);

        String turn1TaskId = turn1Collector.findFirstTaskId();
        assertThat(turn1TaskId).as(sessionLabel + " turn1 taskId").isNotBlank();
        Task turn1Task = a2a.getTask(turn1TaskId);

        Message turn2Message = OpenjiuwenRoundAwait.buildContinuationMessage(
                scenario.turn2Text(), contextId, turn1State, turn1TaskId);

        A2aEventCollector turn2Collector = send(a2a, turn2Message, sessionLabel + " turn2");

        TaskState turn2State = OpenjiuwenRoundAwait.awaitAllowedOutcome(
                turn2Collector,
                a2a,
                turn1TaskId,
                timeoutMs,
                OpenjiuwenRoundAwait.COMPLETED_OR_INPUT_REQUIRED,
                sessionLabel + " turn2");
        assertThat(turn2State).as(sessionLabel + " turn2 state")
                .isIn(OpenjiuwenRoundAwait.COMPLETED_OR_INPUT_REQUIRED);

        String turn2TaskId = turn2Collector.findFirstTaskId();
        assertThat(turn2TaskId).as(sessionLabel + " turn2 taskId").isNotBlank();
        assertThat(turn2Collector.findFirstContextId())
                .as(sessionLabel + " turn2 contextId matches turn1")
                .isEqualTo(contextId);

        Task turn2Task = a2a.getTask(turn2TaskId);
        String turn2Text = TaskTextExtractor.textOf(turn2Task);
        assertThat(turn2Text).as(sessionLabel + " turn2 text").isNotBlank();
        LOG.info(sessionLabel + " turn2_state=" + turn2State + " reply (truncated): "
                + (turn2Text.length() > 200 ? turn2Text.substring(0, 200) + "..." : turn2Text));

        String agentDialogue = TaskTextExtractor.agentDialogueOf(turn1Task, turn2Task);
        OpenjiuwenTextAssertions.assertCityIsolation(turn2Text, agentDialogue, session, scenario);
    }

    private static Message messageWithContext(String text, String contextId) {
        return Message.builder(A2A.toUserMessage(text))
                .contextId(contextId)
                .build();
    }

    private static A2aEventCollector send(A2aServiceClient a2a, Message message, String label) {
        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> error = new AtomicReference<>();
        a2a.sendMessage(message, List.of(collector.createConsumer()), error::set);
        if (error.get() != null) {
            fail(label + " send failed", error.get());
        }
        return collector;
    }
}

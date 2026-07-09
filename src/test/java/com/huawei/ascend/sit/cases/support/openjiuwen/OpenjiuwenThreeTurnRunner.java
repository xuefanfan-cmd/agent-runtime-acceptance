package com.huawei.ascend.sit.cases.support.openjiuwen;

import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.TaskTextExtractor;
import com.huawei.ascend.sit.model.openjiuwen.OpenjiuwenThreeTurnScenarioData;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Three-turn INPUT_REQUIRED collection runner for OJ-05 (full chain, sync send).
 */
public final class OpenjiuwenThreeTurnRunner {

    private static final Logger LOG = Logger.getLogger(OpenjiuwenThreeTurnRunner.class.getName());

    private OpenjiuwenThreeTurnRunner() {
    }

    public record TurnResult(int index, TaskState state, String taskId, String contextId, String text) {
    }

    public record Result(List<TurnResult> turns) {
        public TurnResult turn(int index) {
            return turns.get(index);
        }
    }

    public static Result run(A2aServiceClient a2a, OpenjiuwenThreeTurnScenarioData scenario,
                             Map<String, Object> metadata, long defaultTimeoutMs, String logPrefix) {
        List<TurnResult> results = new ArrayList<>();
        String contextId = scenario.sessionId();
        String priorTaskId = null;
        TaskState priorState = null;

        List<OpenjiuwenThreeTurnScenarioData.TurnSpec> turns = scenario.turns();
        for (int i = 0; i < turns.size(); i++) {
            OpenjiuwenThreeTurnScenarioData.TurnSpec turn = turns.get(i);
            long timeoutMs = turn.timeoutMs() != null && turn.timeoutMs() > 0
                    ? turn.timeoutMs()
                    : (scenario.timeoutMs() > 0 ? scenario.timeoutMs() : defaultTimeoutMs);
            String label = logPrefix + " turn-" + (i + 1);

            Message message = buildMessage(turn.text(), contextId, priorState, priorTaskId);
            A2aEventCollector collector = send(a2a, message, metadata, label);

            List<TaskState> allowed = turn.resolvedAllowedStates();
            String knownTaskId = priorState != null && !priorState.isFinal() ? priorTaskId : null;
            TaskState state = OpenjiuwenRoundAwait.awaitAllowedOutcome(
                    collector, a2a, knownTaskId, timeoutMs, allowed, label);
            assertThat(allowed).as(label + " allowed states").contains(state);

            String taskId = collector.findFirstTaskId();
            assertThat(taskId).as(label + " taskId").isNotBlank();
            String observedContextId = collector.findFirstContextId();
            assertThat(observedContextId).as(label + " contextId").isEqualTo(contextId);

            Task task = a2a.getTask(taskId);
            String text = TaskTextExtractor.textOf(task);
            assertThat(text).as(label + " reply text").isNotBlank();
            if (turn.minResponseLength() != null && turn.minResponseLength() > 0) {
                assertThat(text.length())
                        .as(label + " substantive reply length")
                        .isGreaterThan(turn.minResponseLength());
            }

            LOG.info(label + " state=" + state + " contextId=" + contextId
                    + " reply (truncated): "
                    + (text.length() > 200 ? text.substring(0, 200) + "..." : text));

            results.add(new TurnResult(i, state, taskId, contextId, text));
            priorTaskId = taskId;
            priorState = state;
        }

        return new Result(List.copyOf(results));
    }

    private static Message buildMessage(String text, String contextId, TaskState priorState, String priorTaskId) {
        Message.Builder builder = Message.builder(A2A.toUserMessage(text))
                .contextId(contextId);
        if (priorState != null && !priorState.isFinal()) {
            builder.taskId(priorTaskId);
        }
        return builder.build();
    }

    private static A2aEventCollector send(A2aServiceClient a2a, Message message,
                                          Map<String, Object> metadata, String label) {
        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> error = new AtomicReference<>();
        if (metadata == null || metadata.isEmpty()) {
            a2a.sendMessage(message, List.of(collector.createConsumer()), error::set);
        } else {
            a2a.sendMessage(message, metadata, List.of(collector.createConsumer()), error::set);
        }
        if (error.get() != null) {
            fail(label + " send failed", error.get());
        }
        return collector;
    }
}

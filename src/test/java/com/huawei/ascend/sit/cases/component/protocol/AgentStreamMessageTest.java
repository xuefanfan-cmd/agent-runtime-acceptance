package com.huawei.ascend.sit.cases.component.protocol;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.TaskTextExtractor;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * A-04 — message/stream 流式调用 (特性 3-2 / 4-3).
 *
 * <p>Uses {@link SutStack.Builder#streaming(boolean)} + {@link A2aServiceClient#sendMessage}
 * + {@link A2aEventCollector} — the framework's standard streaming client path
 * ({@code message/stream} when {@code streaming=true}). Scenario constants live in this class
 * (no main ScenarioData); see {@code testdata/component/protocol/a04-stream-hello.json}.</p>
 *
 * <p>See {@code docs/cases/reactagent/A-04-message-stream.md}.</p>
 */
@Tag("component")
@Tag("smoke")
class AgentStreamMessageTest extends BaseManagedStackTest {

    private static final Logger LOG = Logger.getLogger(AgentStreamMessageTest.class.getName());

    /** Matches {@code testdata/component/protocol/a04-stream-hello.json}. */
    private static final String INPUT_TEXT = "你好";
    private static final long STREAM_TIMEOUT_MS = 60_000L;
    private static final TaskState EXPECTED_TERMINAL = TaskState.TASK_STATE_COMPLETED;
    private static final List<TaskState> REQUIRED_STATES = List.of(TaskState.TASK_STATE_SUBMITTED);

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .streaming(true)
                .agent("mainplan");
    }

    @Test
    @DisplayName("A-04: message/stream 流式调用 — SUBMITTED→COMPLETED 最小闭环")
    void a04_streamMessage_primaryScenario() {
        A2aServiceClient a2a = client("mainplan");

        AgentCard card = a2a.getAgentCard();
        assertThat(card.capabilities().streaming())
                .as("capabilities.streaming")
                .isTrue();

        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> streamError = new AtomicReference<>();

        a2a.sendMessage(
                A2A.toUserMessage(INPUT_TEXT),
                List.of(collector.createConsumer()),
                error -> streamError.set(error));

        // SendStreamingMessage is async: sendMessage returns before SSE events arrive.
        TaskState terminalState = collector.awaitTerminalState(STREAM_TIMEOUT_MS);

        Throwable streamFailure = streamError.get();
        if (streamFailure != null && !isBenignStreamShutdown(streamFailure)) {
            fail("message/stream failed", streamFailure);
        }
        if (streamFailure != null) {
            LOG.fine("Ignoring benign SSE stream shutdown: " + streamFailure.getMessage());
        }

        assertThat(collector.eventCount()).as("stream events").isGreaterThan(0);

        List<TaskState> states = extractStates(collector);

        for (TaskState required : REQUIRED_STATES) {
            assertThat(states)
                    .as("required state %s", required)
                    .contains(required);
        }

        assertThat(terminalState)
                .as("terminal state")
                .isEqualTo(EXPECTED_TERMINAL);
        assertThat(states.get(states.size() - 1))
                .as("last observed state")
                .isEqualTo(terminalState);

        String taskId = requireConsistentTaskId(collector);
        assertThat(taskId).as("taskId").isNotBlank();

        String responseText = extractResponseText(collector);
        assertThat(responseText).as("response text").isNotBlank();

        boolean workingObserved = states.contains(TaskState.TASK_STATE_WORKING);
        LOG.info("A-04.E working_observed=" + workingObserved);
    }

    private static boolean isBenignStreamShutdown(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof CancellationException) {
                return true;
            }
            if (current instanceof IOException io && io.getMessage() != null
                    && io.getMessage().toLowerCase().contains("cancel")) {
                return true;
            }
        }
        return false;
    }

    private static List<TaskState> extractStates(A2aEventCollector collector) {
        List<TaskState> states = new ArrayList<>();
        for (ClientEvent event : collector.snapshotAllEvents()) {
            taskFrom(event).ifPresent(task -> states.add(task.status().state()));
        }
        return states;
    }

    private static String requireConsistentTaskId(A2aEventCollector collector) {
        List<String> ids = collector.snapshotAllEvents().stream()
                .flatMap(event -> taskFrom(event).stream())
                .map(Task::id)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        assertThat(ids).as("taskId consistency").hasSize(1);
        return ids.get(0);
    }

    private static String extractResponseText(A2aEventCollector collector) {
        return collector.findTerminalEvent()
                .flatMap(AgentStreamMessageTest::taskFrom)
                .map(TaskTextExtractor::textOf)
                .orElse("");
    }

    private static Optional<Task> taskFrom(ClientEvent event) {
        if (event instanceof TaskEvent taskEvent) {
            return Optional.of(taskEvent.getTask());
        }
        if (event instanceof TaskUpdateEvent updateEvent) {
            return Optional.of(updateEvent.getTask());
        }
        return Optional.empty();
    }
}

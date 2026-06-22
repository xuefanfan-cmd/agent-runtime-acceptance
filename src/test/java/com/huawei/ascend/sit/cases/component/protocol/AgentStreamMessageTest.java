package com.huawei.ascend.sit.cases.component.protocol;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.config.TestEnvironment;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.model.protocol.A04ScenarioData;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

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
 * ({@code message/stream} when {@code streaming=true}).</p>
 *
 * <p>See {@code docs/cases/A-04-message-stream.md}.</p>
 */
@Tag("component")
@Tag("smoke")
@EnabledIf("com.huawei.ascend.sit.cases.component.protocol.AgentStreamMessageTest#isExecutable")
class AgentStreamMessageTest extends BaseManagedStackTest {

    private static final Logger LOG = Logger.getLogger(AgentStreamMessageTest.class.getName());
    static final String LLM_KEY_ENV = "SIT_LLM_API_KEY";

    /** LOCAL 默认不跑，避免无 LLM 时污染 {@code mvn test}。 */
    static boolean isExecutable() {
        TestEnvironment env = TestEnvironment.current();
        return env == TestEnvironment.SIT || env == TestEnvironment.UAT;
    }

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .streaming(true)
                .agent("mainplan", a -> {
                    String apiKey = System.getenv(LLM_KEY_ENV);
                    if (apiKey != null && !apiKey.isBlank()) {
                        a.property("main-plan-agent.api-key", apiKey);
                    }
                });
    }

    @Test
    @DisplayName("A-04: message/stream 流式调用 — SUBMITTED→COMPLETED 最小闭环")
    void a04_streamMessage_primaryScenario() {
        String apiKey = System.getenv(LLM_KEY_ENV);
        if (apiKey == null || apiKey.isBlank()) {
            fail("SIT_LLM_API_KEY must be set for A-04");
        }

        A04ScenarioData scenario = A04ScenarioData.loadDefault();
        A2aServiceClient a2a = client("mainplan");

        AgentCard card = a2a.getAgentCard();
        assertThat(card.capabilities().streaming())
                .as("capabilities.streaming")
                .isTrue();

        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> streamError = new AtomicReference<>();

        a2a.sendMessage(
                A2A.toUserMessage(scenario.inputText()),
                List.of(collector.createConsumer()),
                error -> streamError.set(error));

        // SendStreamingMessage is async: sendMessage returns before SSE events arrive.
        TaskState terminalState = collector.awaitTerminalState(scenario.streamTimeoutMs());

        Throwable streamFailure = streamError.get();
        if (streamFailure != null && !isBenignStreamShutdown(streamFailure)) {
            fail("message/stream failed", streamFailure);
        }
        if (streamFailure != null) {
            LOG.fine("Ignoring benign SSE stream shutdown: " + streamFailure.getMessage());
        }

        assertThat(collector.eventCount()).as("stream events").isGreaterThan(0);

        List<TaskState> states = extractStates(collector);

        for (String required : scenario.requiredStates()) {
            assertThat(states)
                    .as("required state %s", required)
                    .contains(parseStateName(required));
        }

        assertThat(terminalState)
                .as("terminal state")
                .isEqualTo(parseStateName(scenario.expectedTerminalState()));
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
        Optional<Task> terminalTask = collector.findTerminalEvent().flatMap(AgentStreamMessageTest::taskFrom);
        return terminalTask.map(AgentStreamMessageTest::textOf).orElse("");
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

    private static TaskState parseStateName(String name) {
        return TaskState.valueOf(name);
    }

    private static String textOf(Task task) {
        StringBuilder sb = new StringBuilder();
        if (task.artifacts() != null) {
            for (Artifact artifact : task.artifacts()) {
                appendText(sb, artifact.parts());
            }
        }
        if (sb.length() == 0 && task.status() != null && task.status().message() != null) {
            appendText(sb, task.status().message().parts());
        }
        if (sb.length() == 0 && task.history() != null && !task.history().isEmpty()) {
            appendText(sb, task.history().get(task.history().size() - 1).parts());
        }
        return sb.toString().trim();
    }

    private static void appendText(StringBuilder sb, List<Part<?>> parts) {
        if (parts == null) {
            return;
        }
        for (Part<?> part : parts) {
            if (part instanceof TextPart textPart && textPart.text() != null) {
                sb.append(textPart.text());
            }
        }
    }
}

package com.huawei.ascend.sit.cases.component.boundary;

import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.TaskTextExtractor;
import com.huawei.ascend.sit.model.component.boundary.C07ScenarioData;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;

import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-07.*.A～E long-message and health-probe assertions.
 */
final class C07LongMessageAssertions {

    private static final Logger LOG = Logger.getLogger(C07LongMessageAssertions.class.getName());
    private static final int FAILURE_LOG_MAX_CHARS = 200;

    private C07LongMessageAssertions() {
    }

    static void assertLongMessageReachedTerminal(A2aEventCollector collector, C07ScenarioData scenario, String label) {
        TaskState terminal = collector.awaitTerminalState(scenario.longMessageTimeoutMs());

        assertThat(collector.eventCount())
                .as(label + " C-07.A events")
                .isGreaterThan(0);

        assertThat(terminal.isFinal())
                .as(label + " C-07.B terminal is final")
                .isTrue();

        String taskId = collector.findFirstTaskId();
        assertThat(taskId).as(label + " C-07.C taskId").isNotBlank();

        LOG.info(label + " long_message_terminal_state=" + terminal);
        if (terminal == TaskState.TASK_STATE_FAILED) {
            taskFromCollector(collector).ifPresent(task -> {
                String failureText = TaskTextExtractor.textOf(task);
                if (failureText.length() > FAILURE_LOG_MAX_CHARS) {
                    failureText = failureText.substring(0, FAILURE_LOG_MAX_CHARS) + "...";
                }
                LOG.info(label + " failure_text=" + failureText);
            });
        }
    }

    static void assertHealthProbeCompleted(Task task, C07ScenarioData scenario, String label) {
        assertThat(task.status().state())
                .as(label + " C-07.E health probe state")
                .isEqualTo(scenario.resolvedHealthProbeTerminalState());
        String text = TaskTextExtractor.textOf(task);
        assertThat(text).as(label + " C-07.E health probe text").isNotBlank();
    }

    private static java.util.Optional<Task> taskFromCollector(A2aEventCollector collector) {
        return collector.findTerminalEvent().flatMap(C07LongMessageAssertions::taskFrom);
    }

    private static java.util.Optional<Task> taskFrom(ClientEvent event) {
        if (event instanceof TaskEvent taskEvent) {
            return java.util.Optional.of(taskEvent.getTask());
        }
        if (event instanceof TaskUpdateEvent updateEvent) {
            return java.util.Optional.of(updateEvent.getTask());
        }
        return java.util.Optional.empty();
    }
}

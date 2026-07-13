package com.huawei.ascend.sit.cases.component.boundary;

import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.TaskTextExtractor;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-06.*.B～D error surface and health-probe assertions.
 */
final class EmptyMessageAssertions {

    private EmptyMessageAssertions() {
    }

    static void assertEmptyMessageFailed(A2aEventCollector collector, String label) {
        TaskState terminal = collector.awaitTerminalState(EmptyMessageFlow.EMPTY_MESSAGE_TIMEOUT_MS);

        assertThat(collector.eventCount())
                .as(label + " C-06.A events")
                .isGreaterThan(0);

        assertThat(terminal)
                .as(label + " C-06.B terminal state")
                .isEqualTo(EmptyMessageFlow.EXPECTED_TERMINAL_STATE);

        String taskId = collector.findFirstTaskId();
        assertThat(taskId).as(label + " C-06.D taskId").isNotBlank();

        Task task = taskFromCollector(collector).orElseThrow();
        assertInvalidInputError(task, label);
    }

    static void assertHealthProbeCompleted(Task task, String label) {
        assertThat(task.status().state())
                .as(label + " C-06.E health probe state")
                .isEqualTo(EmptyMessageFlow.HEALTH_PROBE_EXPECTED_TERMINAL_STATE);
        String text = TaskTextExtractor.textOf(task);
        assertThat(text).as(label + " C-06.E health probe text").isNotBlank();
    }

    private static void assertInvalidInputError(Task task, String label) {
        String errorSurface = aggregateErrorText(task);
        assertThat(errorSurface)
                .as(label + " C-06.C error surface")
                .contains(EmptyMessageFlow.EXPECTED_ERROR_CODE);
        for (String fragment : EmptyMessageFlow.EXPECTED_ERROR_DETAIL_FRAGMENTS) {
            assertThat(errorSurface.toLowerCase())
                    .as(label + " C-06.C detail fragment")
                    .contains(fragment.toLowerCase());
        }
    }

    private static String aggregateErrorText(Task task) {
        StringBuilder sb = new StringBuilder();
        if (task.status() != null && task.status().message() != null) {
            appendParts(sb, task.status().message().parts());
            appendMetadata(sb, task.status().message().metadata());
        }
        if (task.artifacts() != null) {
            for (Artifact artifact : task.artifacts()) {
                appendParts(sb, artifact.parts());
            }
        }
        return sb.toString();
    }

    private static void appendParts(StringBuilder sb, List<Part<?>> parts) {
        if (parts == null) {
            return;
        }
        for (Part<?> part : parts) {
            if (part instanceof TextPart textPart && textPart.text() != null) {
                sb.append(textPart.text());
            }
        }
    }

    private static void appendMetadata(StringBuilder sb, Map<String, Object> metadata) {
        if (metadata == null) {
            return;
        }
        Object error = metadata.get("a2a.error");
        if (error != null) {
            sb.append(error);
        }
    }

    private static java.util.Optional<Task> taskFromCollector(A2aEventCollector collector) {
        return collector.findTerminalEvent().flatMap(EmptyMessageAssertions::taskFrom);
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

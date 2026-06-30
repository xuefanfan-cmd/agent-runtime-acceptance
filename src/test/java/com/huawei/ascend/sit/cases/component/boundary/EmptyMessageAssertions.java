package com.huawei.ascend.sit.cases.component.boundary;

import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.TaskTextExtractor;
import com.huawei.ascend.sit.model.component.boundary.EmptyMessageScenarioData;
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

    static void assertEmptyMessageFailed(A2aEventCollector collector, EmptyMessageScenarioData scenario, String label) {
        TaskState terminal = collector.awaitTerminalState(scenario.emptyMessageTimeoutMs());

        assertThat(collector.eventCount())
                .as(label + " C-06.A events")
                .isGreaterThan(0);

        assertThat(terminal)
                .as(label + " C-06.B terminal state")
                .isEqualTo(scenario.resolvedExpectedTerminalState());

        String taskId = collector.findFirstTaskId();
        assertThat(taskId).as(label + " C-06.D taskId").isNotBlank();

        Task task = taskFromCollector(collector).orElseThrow();
        assertInvalidInputError(task, scenario, label);
    }

    static void assertHealthProbeCompleted(Task task, EmptyMessageScenarioData scenario, String label) {
        assertThat(task.status().state())
                .as(label + " C-06.E health probe state")
                .isEqualTo(scenario.resolvedHealthProbeTerminalState());
        String text = TaskTextExtractor.textOf(task);
        assertThat(text).as(label + " C-06.E health probe text").isNotBlank();
    }

    private static void assertInvalidInputError(Task task, EmptyMessageScenarioData scenario, String label) {
        String errorSurface = aggregateErrorText(task);
        assertThat(errorSurface)
                .as(label + " C-06.C error surface")
                .contains(scenario.expectedErrorCode());
        for (String fragment : scenario.expectedErrorDetailFragments()) {
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

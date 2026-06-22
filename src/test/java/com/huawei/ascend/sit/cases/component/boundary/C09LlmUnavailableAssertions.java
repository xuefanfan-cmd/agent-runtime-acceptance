package com.huawei.ascend.sit.cases.component.boundary;

import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.TaskTextExtractor;
import com.huawei.ascend.sit.model.component.boundary.C09ScenarioData;
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
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-09.*.A～D LLM-unavailable assertions (F3 loose verdict).
 */
final class C09LlmUnavailableAssertions {

    private static final Logger LOG = Logger.getLogger(C09LlmUnavailableAssertions.class.getName());
    private static final int FAILURE_LOG_MAX_CHARS = 200;

    private C09LlmUnavailableAssertions() {
    }

    static void assertLlmUnavailableNonSuccessTerminal(
            A2aEventCollector collector, C09ScenarioData scenario, String label) {
        TaskState terminal = collector.awaitTerminalState(scenario.llmFailureTimeoutMs());

        assertThat(collector.eventCount())
                .as(label + " C-09.A events")
                .isGreaterThan(0);

        assertThat(terminal.isFinal())
                .as(label + " C-09.B terminal is final")
                .isTrue();

        assertThat(scenario.resolvedDisallowedTerminalStates())
                .as(label + " C-09.B disallowed terminal")
                .doesNotContain(terminal);

        String taskId = collector.findFirstTaskId();
        String errorSurface = taskFromCollector(collector)
                .map(C09LlmUnavailableAssertions::aggregateErrorText)
                .orElse("");

        boolean hasTaskId = taskId != null && !taskId.isBlank();
        boolean hasErrorText = errorSurface != null && !errorSurface.isBlank();
        assertThat(hasTaskId || hasErrorText)
                .as(label + " C-09.C error surface or taskId")
                .isTrue();

        LOG.info(label + " llm_unavailable_terminal_state=" + terminal);
        if (terminal == TaskState.TASK_STATE_FAILED) {
            String failureText = taskFromCollector(collector)
                    .map(TaskTextExtractor::textOf)
                    .orElse(errorSurface);
            if (failureText.length() > FAILURE_LOG_MAX_CHARS) {
                failureText = failureText.substring(0, FAILURE_LOG_MAX_CHARS) + "...";
            }
            LOG.info(label + " failure_text=" + failureText);
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
        String text = TaskTextExtractor.textOf(task);
        if (!text.isBlank()) {
            sb.append(text);
        }
        return sb.toString().trim();
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
        return collector.findTerminalEvent().flatMap(C09LlmUnavailableAssertions::taskFrom);
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

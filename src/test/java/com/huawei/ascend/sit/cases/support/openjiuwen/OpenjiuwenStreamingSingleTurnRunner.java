package com.huawei.ascend.sit.cases.support.openjiuwen;

import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.A2aStreamErrors;
import com.huawei.ascend.sit.client.TaskTextExtractor;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Single-turn {@code message/stream} helper for openjiuwen integration tests.
 */
public final class OpenjiuwenStreamingSingleTurnRunner {

    private OpenjiuwenStreamingSingleTurnRunner() {
    }

    public record Result(TaskState terminalState, String taskId, String responseText, int eventCount) {
    }

    public static Result run(A2aServiceClient a2a, String inputText, long timeoutMs, String label) {
        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> streamError = new AtomicReference<>();

        a2a.sendMessage(
                A2A.toUserMessage(inputText),
                List.of(collector.createConsumer()),
                error -> streamError.set(error));

        TaskState terminalState = collector.awaitTerminalState(timeoutMs);
        Throwable failure = streamError.get();
        if (failure != null && !A2aStreamErrors.isBenignShutdown(failure)) {
            fail(label + " message/stream failed", failure);
        }

        assertThat(collector.eventCount()).as(label + " stream events").isGreaterThan(0);

        String taskId = collector.findFirstTaskId();
        assertThat(taskId).as(label + " taskId").isNotBlank();

        Task task = a2a.getTask(taskId);
        String responseText = TaskTextExtractor.textOf(task);
        return new Result(terminalState, taskId, responseText, collector.eventCount());
    }
}

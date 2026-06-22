package com.huawei.ascend.sit.cases.component.protocol;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.TaskTextExtractor;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.config.TestEnvironment;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.model.protocol.A05ScenarioData;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * A-05 — tasks/get 查询已完成任务 (特性 4-4).
 *
 * <p>Sync {@code message/send} ({@code streaming(false)}) to {@code COMPLETED}, then
 * {@code getTask(taskId)} and assert id/state/text match the send-side terminal snapshot.</p>
 *
 * <p>See {@code docs/cases/A-05-task-get-completed.md}.</p>
 */
@Tag("component")
@Tag("smoke")
@EnabledIf("com.huawei.ascend.sit.cases.component.protocol.AgentTaskGetTest#isExecutable")
class AgentTaskGetTest extends BaseManagedStackTest {

    private static final Logger LOG = Logger.getLogger(AgentTaskGetTest.class.getName());
    static final String LLM_KEY_ENV = "SIT_LLM_API_KEY";

    /** LOCAL 默认不跑，避免无 LLM 时污染 {@code mvn test}。 */
    static boolean isExecutable() {
        TestEnvironment env = TestEnvironment.current();
        return env == TestEnvironment.SIT || env == TestEnvironment.UAT;
    }

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .streaming(false)
                .agent("mainplan", a -> {
                    String apiKey = System.getenv(LLM_KEY_ENV);
                    if (apiKey != null && !apiKey.isBlank()) {
                        a.property("main-plan-agent.api-key", apiKey);
                    }
                });
    }

    @Test
    @DisplayName("A-05: tasks/get 查询已完成任务 — send 快照与 get 一致")
    void a05_getTask_matchesSendSnapshotAfterCompleted() {
        String apiKey = System.getenv(LLM_KEY_ENV);
        if (apiKey == null || apiKey.isBlank()) {
            fail("SIT_LLM_API_KEY must be set for A-05");
        }

        A05ScenarioData scenario = A05ScenarioData.loadDefault();
        A2aServiceClient a2a = client("mainplan");

        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> sendError = new AtomicReference<>();

        a2a.sendMessage(
                A2A.toUserMessage(scenario.inputText()),
                List.of(collector.createConsumer()),
                error -> sendError.set(error));

        if (sendError.get() != null) {
            fail("message/send failed", sendError.get());
        }

        TaskState terminalState = collector.awaitTerminalState(scenario.sendTimeoutMs());
        assertThat(terminalState)
                .as("send terminal state")
                .isEqualTo(TaskState.valueOf(scenario.expectedTerminalState()));

        Task sendTask = collector.findTerminalEvent()
                .flatMap(AgentTaskGetTest::taskFrom)
                .orElseThrow(() -> new AssertionError("send did not produce a terminal task snapshot"));

        String taskId = sendTask.id();
        assertThat(taskId).as("send taskId").isNotBlank();

        String sendText = TaskTextExtractor.textOf(sendTask);
        assertThat(sendText).as("send response text").isNotBlank();

        LOG.info("A-05.E send_event_count=" + collector.eventCount());

        Task queried = a2a.getTask(taskId);
        assertThat(queried).as("getTask result").isNotNull();
        assertThat(queried.id()).as("get taskId").isEqualTo(taskId);
        assertThat(queried.status().state())
                .as("get task state")
                .isEqualTo(TaskState.TASK_STATE_COMPLETED);

        String getText = TaskTextExtractor.textOf(queried);
        assertThat(getText).as("get response text").isNotBlank();
        assertThat(getText).as("send vs get text").isEqualTo(sendText);
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

package com.huawei.ascend.sit.cases.openjiuwen.component;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.cases.openjiuwen.OpenjiuwenStackSupport;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OJ-02 — openjiuwen synchronous contextId assignment and resume.
 *
 * <p>See {@code docs/cases/OJ-02-openjiuwen-sync-context-id-resume.md}.</p>
 */
@Tag("component")
@Tag("openjiuwen")
class OpenjiuwenSyncContextIdTest extends BaseManagedStackTest {

    private static final String INPUT_TEXT = "hi";
    private static final long ROUND_TIMEOUT_MS = 120_000;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return OpenjiuwenStackSupport.mainplanSync(config);
    }

    @Test
    @DisplayName("OJ-02: Turn1 不带 contextId → 服务端分配；Turn2 带回 → 透传，taskId 互异")
    void oj02_syncContextIdAssignedAndPreservedAcrossTurns() {
        A2aServiceClient a2a = client(OpenjiuwenStackSupport.MAINPLAN);

        Message turn1 = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .parts(List.of(new TextPart(INPUT_TEXT)))
                .build();
        TurnObservation t1 = sendOneTurn(a2a, turn1);

        assertThat(t1.streamError()).as("Turn 1 stream error").isNull();
        assertThat(t1.finalState().isFinal()).as("Turn 1 final state").isTrue();
        assertThat(t1.taskId()).as("Turn 1 taskId").isNotBlank();
        assertThat(t1.contextId())
                .as("OJ-02.A Turn1 contextId assigned")
                .isNotBlank();

        Message turn2 = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .contextId(t1.contextId())
                .parts(List.of(new TextPart(INPUT_TEXT)))
                .build();
        TurnObservation t2 = sendOneTurn(a2a, turn2);

        assertThat(t2.streamError()).as("Turn 2 stream error").isNull();
        assertThat(t2.finalState().isFinal()).as("Turn 2 final state").isTrue();
        assertThat(t2.taskId()).as("Turn 2 taskId").isNotBlank();
        assertThat(t2.contextId())
                .as("OJ-02.B Turn2 contextId preserved")
                .isEqualTo(t1.contextId());
        assertThat(t2.taskId())
                .as("OJ-02.C taskId differs across turns")
                .isNotEqualTo(t1.taskId());
    }

    private TurnObservation sendOneTurn(A2aServiceClient a2a, Message message) {
        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> streamError = new AtomicReference<>();

        List<BiConsumer<ClientEvent, AgentCard>> consumers = List.of(collector.createConsumer());
        Consumer<Throwable> errorHandler = streamError::set;

        a2a.sendMessage(message, null, consumers, errorHandler);
        TaskState finalState = collector.awaitTerminalState(ROUND_TIMEOUT_MS);

        return new TurnObservation(
                collector.findFirstTaskId(),
                collector.findFirstContextId(),
                finalState,
                streamError.get());
    }

    private record TurnObservation(
            String taskId,
            String contextId,
            TaskState finalState,
            Throwable streamError) {
    }
}

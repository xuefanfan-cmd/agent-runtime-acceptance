package com.huawei.ascend.sit.cases.integration.agent_bus;

import com.openjiuwen.client.api.AgentClient;
import com.openjiuwen.client.api.ContinueInputRequest;
import com.openjiuwen.client.api.InvocationCall;
import com.openjiuwen.client.api.InvocationEvent;
import com.openjiuwen.client.api.InvocationMode;
import com.openjiuwen.client.api.InvocationNotResumableException;
import com.openjiuwen.client.api.InvocationRequest;
import com.openjiuwen.client.api.TaskState;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Feature("FEAT-006: 客户端发起标准化智能体调用")
@Tag("feat-006")
@Tag("integration")
class StandardAgentClientBlackboxTest {

    @Test
    @Tag("blackbox")
    @Story("FEAT-006.streaming.lifecycle: 标准流式调用生命周期")
    @Tag("story-feat-006-streaming-lifecycle")
    @DisplayName("Feat-006 标准流式调用回显 invocation 并保持 conversation")
    void feat006StreamingInvocationProjectsLifecycleAndKeepsConversation() throws Exception {
        String conversation = "conv-" + UUID.randomUUID();
        try (ClientSdkBlackboxFixture gateway = new ClientSdkBlackboxFixture()) {
            gateway.enqueueSse(
                    ClientSdkBlackboxFixture.status("task-1", conversation, "TASK_STATE_SUBMITTED", null),
                    ClientSdkBlackboxFixture.status("task-1", conversation, "TASK_STATE_WORKING", "planning"),
                    ClientSdkBlackboxFixture.status("task-1", conversation, "TASK_STATE_COMPLETED", "travel-ready"));
            gateway.enqueueSse(
                    ClientSdkBlackboxFixture.status("task-2", conversation, "TASK_STATE_SUBMITTED", null),
                    ClientSdkBlackboxFixture.status("task-2", conversation, "TASK_STATE_WORKING", "updating"),
                    ClientSdkBlackboxFixture.status("task-2", conversation, "TASK_STATE_COMPLETED", "updated"));

            try (AgentClient client = gateway.client()) {
                InvocationCall first = client.invoke(request(conversation, "inv-1", "plan trip"));
                var firstEvents = ClientSdkBlackboxFixture.subscribe(first).await();
                assertLifecycle(firstEvents, "inv-1", "travel-ready");
                assertThat(first.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state())
                        .isEqualTo(TaskState.COMPLETED);

                InvocationCall second = client.invoke(request(conversation, "inv-2", "change hotel"));
                var secondEvents = ClientSdkBlackboxFixture.subscribe(second).await();
                assertLifecycle(secondEvents, "inv-2", "updated");
                assertThat(second.conversationId()).isEqualTo(conversation);
                assertThat(second.invocationRef()).isNotEqualTo(first.invocationRef());

                var firstWire = gateway.takeRequest();
                var secondWire = gateway.takeRequest();
                assertThat(firstWire.at("/params/message/contextId").asText()).isEqualTo(conversation);
                assertThat(secondWire.at("/params/message/contextId").asText()).isEqualTo(conversation);
                assertThat(firstWire.at("/params/message").has("taskId")).isFalse();
                assertThat(firstWire.at("/params/metadata/agentId").asText()).isEqualTo("travel-mainplan");
            }
        }
    }

    @Test
    @Tag("blackbox")
    @Story("FEAT-006.streaming.continue-input: 等待输入续接")
    @Tag("story-feat-006-streaming-continue-input")
    @DisplayName("Feat-006 补充输入以新 invocation 续接指定等待状态")
    void feat006ContinueInputCreatesRelatedInvocationAndRejectsInvalidRelations() throws Exception {
        String conversation = "conv-" + UUID.randomUUID();
        try (ClientSdkBlackboxFixture gateway = new ClientSdkBlackboxFixture()) {
            gateway.enqueueSse(ClientSdkBlackboxFixture.userInputRequired(
                    "task-wait", conversation, "please provide origin"));
            gateway.enqueueJson(ClientSdkBlackboxFixture.task(
                    "task-wait", conversation, "TASK_STATE_COMPLETED", "continued"));

            try (AgentClient client = gateway.client()) {
                InvocationCall waiting = client.invoke(request(conversation, "inv-wait", "travel"));
                List<InvocationEvent> events = ClientSdkBlackboxFixture.subscribe(waiting)
                        .awaitEvent(InvocationEvent.InputRequired.class::isInstance);
                assertThat(events).anyMatch(InvocationEvent.InputRequired.class::isInstance);

                InvocationCall continued = client.continueInput(ContinueInputRequest.builder()
                        .conversationId(conversation)
                        .relatedInvocationRef(waiting.invocationRef())
                        .invocationId("inv-continued")
                        .input("from Shanghai")
                        .build());
                assertThat(continued.invocationRef()).isEqualTo("inv-continued");
                assertThat(continued.completion().toCompletableFuture().get(3, TimeUnit.SECONDS).state())
                        .isEqualTo(TaskState.COMPLETED);

                gateway.takeRequest();
                var resume = gateway.takeRequest();
                assertThat(resume.at("/params/message/taskId").asText()).isEqualTo("task-wait");
                assertThat(resume.at("/params/message/contextId").asText()).isEqualTo(conversation);
                assertThat(resume.at("/params/message/messageId").asText()).isEqualTo("inv-continued");

                assertThatThrownBy(() -> client.continueInput(ContinueInputRequest.builder()
                        .conversationId(conversation)
                        .relatedInvocationRef("missing")
                        .input("x")
                        .build())).isInstanceOf(InvocationNotResumableException.class);
            }
        }
    }

    @Test
    @Tag("blackbox")
    @Story("FEAT-006.streaming.failure-boundary: 调用失败边界")
    @Tag("story-feat-006-streaming-failure-boundary")
    @DisplayName("Feat-006 Gateway 拒绝保持稳定分类且不伪造完成")
    void feat006FailuresRemainClassifiedWithoutFalseCompletion() throws Exception {
        try (ClientSdkBlackboxFixture gateway = new ClientSdkBlackboxFixture()) {
            gateway.enqueueHttpError(503, "ROUTE_NO_CANDIDATES");
            try (AgentClient client = gateway.client()) {
                InvocationCall call = client.invoke(request("conv-fail", "inv-fail", "x"));
                List<InvocationEvent> events = ClientSdkBlackboxFixture.subscribe(call).await();
                assertThat(events).noneMatch(InvocationEvent.Completed.class::isInstance);
                assertThat(events).filteredOn(InvocationEvent.Failed.class::isInstance).singleElement()
                        .satisfies(event -> assertThat(((InvocationEvent.Failed) event).errorCode())
                                .isEqualTo("ROUTE_NO_CANDIDATES"));
                assertThat(call.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state())
                        .isEqualTo(TaskState.FAILED);
            }
        }
    }

    @Test
    @Tag("contract")
    @Story("FEAT-006.streaming.unknown-state-contract: 未知状态兼容")
    @Tag("story-feat-006-streaming-unknown-state-contract")
    @DisplayName("Feat-006 未识别状态映射 UNKNOWN 且不阻断后续终态")
    void feat006UnknownTaskStateMapsToReadonlyUnknown() throws Exception {
        try (ClientSdkBlackboxFixture gateway = new ClientSdkBlackboxFixture()) {
            gateway.enqueueSse(
                    ClientSdkBlackboxFixture.status("task-future", "conv-future", "TASK_STATE_PAUSED", null),
                    ClientSdkBlackboxFixture.status("task-future", "conv-future", "TASK_STATE_COMPLETED", "done"));
            try (AgentClient client = gateway.client()) {
                InvocationCall call = client.invoke(request("conv-future", "inv-future", "x"));
                List<InvocationEvent> events = ClientSdkBlackboxFixture.subscribe(call).await();
                assertThat(events).filteredOn(InvocationEvent.StatusChanged.class::isInstance)
                        .map(InvocationEvent.StatusChanged.class::cast)
                        .extracting(InvocationEvent.StatusChanged::state)
                        .contains(TaskState.UNKNOWN);
                assertThat(events).anyMatch(InvocationEvent.Completed.class::isInstance);
            }
        }
    }

    private static InvocationRequest request(String conversation, String invocation, String input) {
        return InvocationRequest.builder()
                .agentId("travel-mainplan")
                .conversationId(conversation)
                .invocationId(invocation)
                .mode(InvocationMode.STREAMING)
                .input(input)
                .build();
    }

    private static void assertLifecycle(List<InvocationEvent> events, String invocationRef, String output) {
        assertThat(events).allSatisfy(event -> assertThat(event.invocationRef()).isEqualTo(invocationRef));
        assertThat(events).anyMatch(InvocationEvent.Accepted.class::isInstance);
        assertThat(events).anyMatch(event -> event instanceof InvocationEvent.StatusChanged status
                && status.state() == TaskState.WORKING);
        assertThat(events).anyMatch(event -> event instanceof InvocationEvent.Completed completed
                && output.equals(completed.outputText()));
    }
}

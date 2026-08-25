package com.huawei.ascend.sit.cases.integration.agent_bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.openjiuwen.client.api.AgentClient;
import com.openjiuwen.client.api.AgentClients;
import com.openjiuwen.client.api.ClassifiedError;
import com.openjiuwen.client.api.EndpointType;
import com.openjiuwen.client.api.InvocationCall;
import com.openjiuwen.client.api.InvocationEvent;
import com.openjiuwen.client.api.InvocationMode;
import com.openjiuwen.client.api.InvocationRequest;
import com.openjiuwen.client.api.InvocationSnapshot;
import com.openjiuwen.client.api.RetryPolicy;
import com.openjiuwen.client.api.TaskState;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Feature("FEAT-006: 客户端发起标准化智能体调用")
@Tag("feat-006")
@Tag("integration")
@Tag("contract")
class ClientReconnectBlackboxTest {

    @Test
    @Story("F006-R01: 已知 Task 的流式自动恢复")
    @DisplayName("Feat-006 Runtime 断流后订阅原 Task 且不重发创建")
    void runtimeDisconnectSubscribesOriginalTaskWithoutRecreating() throws Exception {
        try (ClientSdkBlackboxFixture endpoint = new ClientSdkBlackboxFixture()) {
            endpoint.enqueueSse(ClientSdkBlackboxFixture.status(
                    "task-reconnect", "ctx-reconnect", "TASK_STATE_WORKING", "planning"));
            endpoint.enqueueSse(ClientSdkBlackboxFixture.status(
                    "task-reconnect", "ctx-reconnect", "TASK_STATE_COMPLETED", "done"));

            try (AgentClient client = endpoint.client(EndpointType.RUNTIME)) {
                InvocationSnapshot snapshot = invoke(client, "runtime-reconnect")
                        .completion().toCompletableFuture().get(8, TimeUnit.SECONDS);

                assertThat(snapshot.state()).isEqualTo(TaskState.COMPLETED);
                assertThat(snapshot.outputText()).isEqualTo("done");
                assertMethods(endpoint, false,
                        "SendStreamingMessage", "SubscribeToTask");
            }
        }
    }

    @Test
    @Story("F006-R02: SSE idle timeout 自动恢复")
    @DisplayName("Feat-006 Runtime 订阅 SSE 空窗超时后查询原 Task 且不取消")
    void runtimeIdleTimeoutRecoversOriginalTaskWithoutRecreatingOrCanceling() throws Exception {
        try (ClientSdkBlackboxFixture endpoint = new ClientSdkBlackboxFixture()) {
            endpoint.enqueueSse(ClientSdkBlackboxFixture.status(
                    "task-idle", "ctx-idle", "TASK_STATE_WORKING", "planning"));
            endpoint.enqueueDelayedSse(2_000, ClientSdkBlackboxFixture.status(
                    "task-idle", "ctx-idle", "TASK_STATE_COMPLETED", "late-frame"));
            endpoint.enqueueJson(ClientSdkBlackboxFixture.taskSnapshot(
                    "task-idle", "ctx-idle", "TASK_STATE_COMPLETED", "done"));

            try (AgentClient client = endpoint.client(EndpointType.RUNTIME, Duration.ofMillis(300))) {
                InvocationCall call = invoke(client, "idle-timeout");
                ClientSdkBlackboxFixture.EventProbe events = ClientSdkBlackboxFixture.subscribe(call);
                InvocationSnapshot snapshot = call.completion().toCompletableFuture()
                        .get(6, TimeUnit.SECONDS);

                assertThat(snapshot.state()).isEqualTo(TaskState.COMPLETED);
                assertThat(snapshot.outputText()).isEqualTo("done");
                assertThat(events.await()).noneMatch(InvocationEvent.Failed.class::isInstance)
                        .filteredOn(InvocationEvent.StatusChanged.class::isInstance)
                        .extracting(event -> ((InvocationEvent.StatusChanged) event).state())
                        .doesNotContain(TaskState.FAILED, TaskState.CANCELED);
                assertMethods(endpoint, false,
                        "SendStreamingMessage", "SubscribeToTask", "GetTask");
            }
        }
    }

    @Test
    @Story("F006-R04: 终态订阅回退 GetTask")
    @DisplayName("Feat-006 订阅遇到终态时 GetTask 收敛且只结算一次")
    void terminalSubscriptionFallsBackToGetTask() throws Exception {
        try (ClientSdkBlackboxFixture endpoint = new ClientSdkBlackboxFixture()) {
            endpoint.enqueueSse(ClientSdkBlackboxFixture.status(
                    "task-terminal", "ctx-terminal", "TASK_STATE_WORKING", "planning"));
            endpoint.enqueueJsonRpcError(-32004, "task is already terminal");
            endpoint.enqueueJson(ClientSdkBlackboxFixture.taskSnapshot(
                    "task-terminal", "ctx-terminal", "TASK_STATE_COMPLETED", "final-result"));

            try (AgentClient client = endpoint.client(EndpointType.RUNTIME)) {
                InvocationSnapshot snapshot = invoke(client, "terminal-race")
                        .completion().toCompletableFuture().get(8, TimeUnit.SECONDS);

                assertThat(snapshot.state()).isEqualTo(TaskState.COMPLETED);
                assertThat(snapshot.outputText()).isEqualTo("final-result");
                assertMethods(endpoint, false,
                        "SendStreamingMessage", "SubscribeToTask", "GetTask");
            }
        }
    }

    @Test
    @Story("F006-R03: invocationRef 即时查询原 Task 快照")
    @DisplayName("Feat-006 活动恢复期间 getInvocation 返回原 Task 的公开快照")
    void getInvocationProjectsCurrentTaskSnapshotWhileRecoveryIsActive() throws Exception {
        try (ClientSdkBlackboxFixture endpoint = new ClientSdkBlackboxFixture()) {
            endpoint.enqueueSse(ClientSdkBlackboxFixture.status(
                    "task-query", "ctx-query", "TASK_STATE_WORKING", "planning"));
            endpoint.enqueueDelayedSse(2_000, ClientSdkBlackboxFixture.status(
                    "task-query", "ctx-query", "TASK_STATE_COMPLETED", "done"));
            endpoint.enqueueJson(ClientSdkBlackboxFixture.taskSnapshot(
                    "task-query", "ctx-query", "TASK_STATE_WORKING", "current-snapshot"));

            try (AgentClient client = endpoint.client(EndpointType.RUNTIME)) {
                InvocationCall call = invoke(client, "query-active");
                assertThat(call.accepted().toCompletableFuture().get(5, TimeUnit.SECONDS)
                        .diagnosticTaskRef()).isEqualTo("task-query");

                List<JsonNode> initialRequests = takeRequests(endpoint, false, 2);
                assertThat(initialRequests).extracting(node -> node.path("method").asText())
                        .containsExactly("SendStreamingMessage", "SubscribeToTask");

                InvocationSnapshot current = client.getInvocation(call.invocationRef())
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertThat(current.invocationRef()).isEqualTo(call.invocationRef());
                assertThat(current.diagnosticTaskRef()).isEqualTo("task-query");
                assertThat(current.state()).isEqualTo(TaskState.WORKING);
                assertThat(current.terminal()).isFalse();
                assertThat(current.outputText()).isEqualTo("current-snapshot");
                assertThat(current.maybeRecovery()).isEmpty();

                JsonNode query = endpoint.takeRequest(false);
                assertThat(query.path("method").asText()).isEqualTo("GetTask");
                assertThat(query.at("/params/id").asText()).isEqualTo("task-query");

                InvocationSnapshot completed = call.completion().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
                assertThat(completed.invocationRef()).isEqualTo(call.invocationRef());
                assertThat(completed.diagnosticTaskRef()).isEqualTo("task-query");
                assertThat(completed.state()).isEqualTo(TaskState.COMPLETED);
            }
        }
    }

    @Test
    @Story("F006-B01/B02/B04: 有界重试、三次熔断且不取消服务端 Task")
    @DisplayName("Feat-006 恢复按有界间隔重试并在第三次失败后停止本地观察")
    void threeRecoveryFailuresOpenCircuitWithoutCancelingTask() throws Exception {
        try (ClientSdkBlackboxFixture endpoint = new ClientSdkBlackboxFixture()) {
            endpoint.enqueueSse(ClientSdkBlackboxFixture.status(
                    "task-circuit", "ctx-circuit", "TASK_STATE_WORKING", "planning"));
            endpoint.enqueueHttpError(503, "SERVICE_UNAVAILABLE");
            endpoint.enqueueHttpError(503, "SERVICE_UNAVAILABLE");
            endpoint.enqueueHttpError(503, "SERVICE_UNAVAILABLE");
            endpoint.enqueueJson(ClientSdkBlackboxFixture.taskSnapshot(
                    "task-circuit", "ctx-circuit", "TASK_STATE_COMPLETED", "after-recovery"));

            try (AgentClient client = endpoint.client(EndpointType.RUNTIME)) {
                InvocationCall call = invoke(client, "recovery-circuit");
                List<ClientSdkBlackboxFixture.TimedRequest> automaticRequests =
                        takeTimedRequests(endpoint, false, 4);
                assertThatThrownBy(() -> call.completion().toCompletableFuture()
                        .get(8, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .cause().isInstanceOf(ClassifiedError.class)
                        .satisfies(error -> assertThat(((ClassifiedError) error).code())
                                .isEqualTo("RECOVERY_RETRY_EXHAUSTED"));

                assertThat(automaticRequests).extracting(request -> request.body().path("method").asText())
                        .containsExactly("SendStreamingMessage", "SubscribeToTask", "GetTask",
                                "SubscribeToTask");
                Duration retryDelay = Duration.ofNanos(automaticRequests.get(3).receivedAtNanos()
                        - automaticRequests.get(2).receivedAtNanos());
                assertThat(retryDelay).as("fixed recovery backoff after the second failure")
                        .isBetween(Duration.ofMillis(250), Duration.ofSeconds(3));
                Duration stopObservation = Duration.ofMillis(1_200);
                assertThat(endpoint.hasRequestWithin(stopObservation))
                        .as("no automatic request after the third consecutive failure")
                        .isFalse();
                System.out.printf("F006-B01 observedRetryDelayMs=%d stopObservationMs=%d%n",
                        retryDelay.toMillis(), stopObservation.toMillis());
                assertRecoveryTaskIds(automaticRequests.stream()
                        .map(ClientSdkBlackboxFixture.TimedRequest::body).toList());

                InvocationSnapshot reconciled = client.getInvocation(call.invocationRef())
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertThat(reconciled.state()).isEqualTo(TaskState.COMPLETED);
                assertThat(reconciled.outputText()).isEqualTo("after-recovery");
                JsonNode explicitQuery = endpoint.takeRequest(false);
                assertThat(explicitQuery.path("method").asText()).isEqualTo("GetTask");
                assertThat(explicitQuery.at("/params/id").asText())
                        .isEqualTo(automaticRequests.get(1).body().at("/params/id").asText());
            }
        }
    }

    @Test
    @Story("F006-B01: 非默认重试间隔与停止阈值可配置")
    @DisplayName("Feat-006 公开 RetryPolicy 控制恢复间隔并在配置阈值停止")
    void configuredRetryPolicyControlsRecoveryIntervalAndFailureLimit() throws Exception {
        try (ClientSdkBlackboxFixture endpoint = new ClientSdkBlackboxFixture()) {
            endpoint.enqueueSse(ClientSdkBlackboxFixture.status(
                    "task-configured-policy", "ctx-configured-policy", "TASK_STATE_WORKING", "planning"));
            endpoint.enqueueHttpError(503, "SERVICE_UNAVAILABLE");
            endpoint.enqueueHttpError(503, "SERVICE_UNAVAILABLE");
            endpoint.enqueueHttpError(503, "SERVICE_UNAVAILABLE");
            endpoint.enqueueHttpError(503, "SERVICE_UNAVAILABLE");
            endpoint.enqueueJson(ClientSdkBlackboxFixture.taskSnapshot(
                    "task-configured-policy", "ctx-configured-policy",
                    "TASK_STATE_COMPLETED", "after-configured-policy"));

            RetryPolicy policy = RetryPolicy.builder()
                    .maxConsecutiveFailures(4)
                    .initialDelay(Duration.ofMillis(700))
                    .maxDelay(Duration.ofMillis(700))
                    .multiplier(1.0d)
                    .jitterFactor(0.0d)
                    .build();
            try (AgentClient client = AgentClients.builder()
                    .endpointType(EndpointType.RUNTIME)
                    .endpointUrl(endpoint.baseUrl())
                    .retryPolicy(policy)
                    .build()) {
                InvocationCall call = invoke(client, "configured-recovery-policy");
                List<ClientSdkBlackboxFixture.TimedRequest> automaticRequests =
                        takeTimedRequests(endpoint, false, 5);
                assertThatThrownBy(() -> call.completion().toCompletableFuture()
                        .get(8, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .cause().isInstanceOf(ClassifiedError.class)
                        .satisfies(error -> assertThat(((ClassifiedError) error).code())
                                .isEqualTo("RECOVERY_RETRY_EXHAUSTED"));

                assertThat(automaticRequests).extracting(request -> request.body().path("method").asText())
                        .containsExactly("SendStreamingMessage", "SubscribeToTask", "GetTask",
                                "SubscribeToTask", "GetTask");
                Duration configuredDelay = Duration.ofNanos(automaticRequests.get(3).receivedAtNanos()
                        - automaticRequests.get(2).receivedAtNanos());
                assertThat(configuredDelay).as("configured 700 ms recovery interval")
                        .isBetween(Duration.ofMillis(600), Duration.ofSeconds(2));
                Duration stopObservation = Duration.ofMillis(1_000);
                assertThat(endpoint.hasRequestWithin(stopObservation))
                        .as("no automatic request after the configured fourth consecutive failure")
                        .isFalse();
                System.out.printf("F006-B01 configuredRetryDelayMs=%d configuredFailureLimit=%d "
                                + "stopObservationMs=%d%n",
                        configuredDelay.toMillis(), policy.maxConsecutiveFailures(),
                        stopObservation.toMillis());
                assertRecoveryTaskIds(automaticRequests.stream()
                        .map(ClientSdkBlackboxFixture.TimedRequest::body).toList());

                InvocationSnapshot reconciled = client.getInvocation(call.invocationRef())
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertThat(reconciled.state()).isEqualTo(TaskState.COMPLETED);
                assertThat(reconciled.outputText()).isEqualTo("after-configured-policy");
                JsonNode explicitQuery = endpoint.takeRequest(false);
                assertThat(explicitQuery.path("method").asText()).isEqualTo("GetTask");
                assertThat(explicitQuery.at("/params/id").asText()).isEqualTo("task-configured-policy");
            }
        }
    }

    @Test
    @Story("F006-B03: 有效 Task 响应清零连续失败计数")
    @DisplayName("Feat-006 有效 GetTask 快照或 Subscribe 帧清零失败计数")
    void successfulTaskObservationResetsRecoveryFailureCounter() throws Exception {
        assertGetTaskSuccessResetsRecoveryFailureCounter();
        assertSubscriptionSuccessResetsRecoveryFailureCounter();
    }

    private static void assertGetTaskSuccessResetsRecoveryFailureCounter() throws Exception {
        try (ClientSdkBlackboxFixture endpoint = new ClientSdkBlackboxFixture()) {
            endpoint.enqueueSse(ClientSdkBlackboxFixture.status(
                    "task-reset", "ctx-reset", "TASK_STATE_WORKING", "planning"));
            endpoint.enqueueHttpError(503, "SERVICE_UNAVAILABLE");
            endpoint.enqueueJson(ClientSdkBlackboxFixture.taskSnapshot(
                    "task-reset", "ctx-reset", "TASK_STATE_WORKING", "still-working"));
            endpoint.enqueueHttpError(503, "SERVICE_UNAVAILABLE");
            endpoint.enqueueJson(ClientSdkBlackboxFixture.taskSnapshot(
                    "task-reset", "ctx-reset", "TASK_STATE_WORKING", "still-working"));
            endpoint.enqueueHttpError(503, "SERVICE_UNAVAILABLE");
            endpoint.enqueueJson(ClientSdkBlackboxFixture.taskSnapshot(
                    "task-reset", "ctx-reset", "TASK_STATE_COMPLETED", "done"));

            try (AgentClient client = endpoint.client(EndpointType.RUNTIME)) {
                InvocationSnapshot snapshot = invoke(client, "counter-reset")
                        .completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

                assertThat(snapshot.state()).isEqualTo(TaskState.COMPLETED);
                assertThat(snapshot.outputText()).isEqualTo("done");
                assertMethods(endpoint, false,
                        "SendStreamingMessage", "SubscribeToTask", "GetTask",
                        "SubscribeToTask", "GetTask", "SubscribeToTask", "GetTask");
            }
        }
    }

    private static void assertSubscriptionSuccessResetsRecoveryFailureCounter() throws Exception {
        try (ClientSdkBlackboxFixture endpoint = new ClientSdkBlackboxFixture()) {
            endpoint.enqueueSse(ClientSdkBlackboxFixture.status(
                    "task-subscribe-reset", "ctx-subscribe-reset", "TASK_STATE_WORKING", "planning"));
            endpoint.enqueueHttpError(503, "SERVICE_UNAVAILABLE");
            endpoint.enqueueSse(ClientSdkBlackboxFixture.status(
                    "task-subscribe-reset", "ctx-subscribe-reset", "TASK_STATE_WORKING", "reconnected"));
            endpoint.enqueueHttpError(503, "SERVICE_UNAVAILABLE");
            endpoint.enqueueHttpError(503, "SERVICE_UNAVAILABLE");
            endpoint.enqueueSse(ClientSdkBlackboxFixture.status(
                    "task-subscribe-reset", "ctx-subscribe-reset", "TASK_STATE_COMPLETED", "done"));

            try (AgentClient client = endpoint.client(EndpointType.GATEWAY)) {
                InvocationSnapshot snapshot = invoke(client, "subscription-counter-reset")
                        .completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

                assertThat(snapshot.state()).isEqualTo(TaskState.COMPLETED);
                assertThat(snapshot.outputText()).isEqualTo("done");
                assertMethods(endpoint, true,
                        "SendStreamingMessage", "SubscribeToTask", "SubscribeToTask",
                        "SubscribeToTask", "SubscribeToTask", "SubscribeToTask");
            }
        }
    }

    @Test
    @Story("F006-B06: invocation 级熔断隔离")
    @DisplayName("Feat-006 一个 invocation 熔断不影响另一个 invocation 恢复")
    void recoveryCircuitIsIsolatedPerInvocation() throws Exception {
        try (ClientSdkBlackboxFixture endpoint = new ClientSdkBlackboxFixture()) {
            endpoint.enqueueSse(ClientSdkBlackboxFixture.status(
                    "task-isolation-first", "ctx-isolation-first", "TASK_STATE_WORKING", "planning"));
            endpoint.enqueueHttpError(503, "SERVICE_UNAVAILABLE");
            endpoint.enqueueHttpError(503, "SERVICE_UNAVAILABLE");
            endpoint.enqueueHttpError(503, "SERVICE_UNAVAILABLE");
            endpoint.enqueueSse(ClientSdkBlackboxFixture.status(
                    "task-isolation-second", "ctx-isolation-second", "TASK_STATE_WORKING", "planning"));
            endpoint.enqueueSse(ClientSdkBlackboxFixture.status(
                    "task-isolation-second", "ctx-isolation-second", "TASK_STATE_COMPLETED", "done"));

            try (AgentClient client = endpoint.client(EndpointType.RUNTIME)) {
                InvocationCall first = invoke(client, "isolation-first");
                assertThatThrownBy(() -> first.completion().toCompletableFuture()
                        .get(8, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .cause().isInstanceOf(ClassifiedError.class)
                        .satisfies(error -> assertThat(((ClassifiedError) error).code())
                                .isEqualTo("RECOVERY_RETRY_EXHAUSTED"));

                InvocationCall second = invoke(client, "isolation-second");
                InvocationSnapshot completed = second.completion().toCompletableFuture()
                        .get(8, TimeUnit.SECONDS);

                assertThat(completed.invocationRef()).isEqualTo(second.invocationRef())
                        .isNotEqualTo(first.invocationRef());
                assertThat(completed.state()).isEqualTo(TaskState.COMPLETED);
                assertThat(completed.outputText()).isEqualTo("done");
            }
        }
    }

    @Test
    @Disabled("blocked: current Client does not settle deterministic SubscribeToTask JSON-RPC errors; completion times out")
    @Story("F006-B05: 确定性协议错误不进入基础设施重试")
    @DisplayName("Feat-006 终态订阅协议错误直接结束当前 invocation 且不继续重试")
    void deterministicSubscriptionProtocolErrorDoesNotTriggerInfrastructureRetry() throws Exception {
        try (ClientSdkBlackboxFixture endpoint = new ClientSdkBlackboxFixture()) {
            endpoint.enqueueSse(ClientSdkBlackboxFixture.status(
                    "task-protocol-error", "ctx-protocol-error", "TASK_STATE_WORKING", "planning"));
            endpoint.enqueueJsonRpcError(-32602, "invalid task state for subscription");

            try (AgentClient client = endpoint.client(EndpointType.RUNTIME)) {
                InvocationCall call = invoke(client, "protocol-error");
                assertThatThrownBy(() -> call.completion().toCompletableFuture()
                        .get(8, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class);

                List<JsonNode> requests = takeRequests(endpoint, false, 2);
                assertThat(requests).extracting(node -> node.path("method").asText())
                        .containsExactly("SendStreamingMessage", "SubscribeToTask");
                assertThat(requests.get(1).at("/params/id").asText())
                        .isEqualTo("task-protocol-error");
                assertThat(requests).noneMatch(node ->
                        "GetTask".equals(node.path("method").asText())
                                || "CancelTask".equals(node.path("method").asText()));
            }
        }
    }

    @Test
    @Story("F006-E01: 两种 Endpoint 的恢复请求语义一致")
    @DisplayName("Feat-006 Gateway 与 Runtime 都以原 taskId 订阅恢复")
    void gatewayAndRuntimeUseTheOriginalTaskForRecovery() throws Exception {
        for (EndpointType type : EndpointType.values()) {
            try (ClientSdkBlackboxFixture endpoint = new ClientSdkBlackboxFixture()) {
                String taskId = "task-" + type.name().toLowerCase();
                endpoint.enqueueSse(ClientSdkBlackboxFixture.status(
                        taskId, "ctx-" + type.name().toLowerCase(), "TASK_STATE_WORKING", "planning"));
                endpoint.enqueueSse(ClientSdkBlackboxFixture.status(
                        taskId, "ctx-" + type.name().toLowerCase(), "TASK_STATE_COMPLETED", "done"));

                try (AgentClient client = endpoint.client(type)) {
                    InvocationSnapshot snapshot = invoke(client, "endpoint-" + type.name().toLowerCase())
                            .completion().toCompletableFuture().get(8, TimeUnit.SECONDS);
                    assertThat(snapshot.state()).isEqualTo(TaskState.COMPLETED);

                    boolean authenticated = type == EndpointType.GATEWAY;
                    List<JsonNode> requests = takeRequests(endpoint, authenticated, 2);
                    assertThat(requests).extracting(node -> node.path("method").asText())
                            .containsExactly("SendStreamingMessage", "SubscribeToTask");
                    assertThat(requests.get(1).at("/params/id").asText()).isEqualTo(taskId);
                    assertThat(requests).noneMatch(node -> "CancelTask".equals(node.path("method").asText()));
                }
            }
        }
    }

    private static InvocationCall invoke(AgentClient client, String scenario) {
        return client.invoke(InvocationRequest.builder()
                .agentId("travel-mainplan")
                .conversationId("ctx-" + scenario)
                .invocationId("inv-" + UUID.randomUUID())
                .mode(InvocationMode.STREAMING)
                .input("reconnect acceptance " + scenario)
                .build());
    }

    private static void assertMethods(ClientSdkBlackboxFixture endpoint, boolean authenticated,
                                      String... expected) throws Exception {
        List<JsonNode> requests = takeRequests(endpoint, authenticated, expected.length);
        assertThat(requests).extracting(node -> node.path("method").asText())
                .containsExactly(expected);
        assertThat(requests).noneMatch(node -> "CancelTask".equals(node.path("method").asText()));
        String taskId = requests.get(1).at("/params/id").asText();
        assertThat(taskId).isNotBlank();
        assertThat(requests.stream().skip(1)
                .filter(node -> node.path("method").asText().matches("GetTask|SubscribeToTask"))
                .allMatch(node -> taskId.equals(node.at("/params/id").asText())))
                .isTrue();
    }

    private static List<JsonNode> takeRequests(ClientSdkBlackboxFixture endpoint,
                                               boolean authenticated, int count) throws Exception {
        List<JsonNode> requests = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            requests.add(endpoint.takeRequest(authenticated));
        }
        return requests;
    }

    private static List<ClientSdkBlackboxFixture.TimedRequest> takeTimedRequests(
            ClientSdkBlackboxFixture endpoint, boolean authenticated, int count) throws Exception {
        List<ClientSdkBlackboxFixture.TimedRequest> requests = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            requests.add(endpoint.takeTimedRequest(authenticated));
        }
        return requests;
    }

    private static void assertRecoveryTaskIds(List<JsonNode> requests) {
        assertThat(requests).noneMatch(node -> "CancelTask".equals(node.path("method").asText()));
        String taskId = requests.get(1).at("/params/id").asText();
        assertThat(taskId).isNotBlank();
        assertThat(requests.stream().skip(1)
                .allMatch(node -> taskId.equals(node.at("/params/id").asText())))
                .isTrue();
    }
}

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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
            endpoint.enqueueJsonRpcError(-32602, "task is already terminal",
                    "TASK_NOT_SUBSCRIBABLE_TERMINAL");
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
            enqueueInfrastructureFailures(endpoint, 6);
            endpoint.enqueueJson(ClientSdkBlackboxFixture.taskSnapshot(
                    "task-circuit", "ctx-circuit", "TASK_STATE_COMPLETED", "after-recovery"));

            try (AgentClient client = endpoint.client(EndpointType.RUNTIME)) {
                InvocationCall call = invoke(client, "recovery-circuit");
                List<ClientSdkBlackboxFixture.TimedRequest> automaticRequests =
                        takeTimedRequests(endpoint, false, 7);
                assertRecoveryExhausted(call);

                assertThat(automaticRequests).extracting(request -> request.body().path("method").asText())
                        .containsExactlyElementsOf(recoveryMethods(3));
                Duration firstRetryDelay = Duration.ofNanos(automaticRequests.get(3).receivedAtNanos()
                        - automaticRequests.get(2).receivedAtNanos());
                Duration secondRetryDelay = Duration.ofNanos(automaticRequests.get(5).receivedAtNanos()
                        - automaticRequests.get(4).receivedAtNanos());
                assertThat(firstRetryDelay).as("default backoff after the first failed cycle")
                        .isBetween(Duration.ofMillis(150), Duration.ofSeconds(2));
                assertThat(secondRetryDelay).as("default backoff after the second failed cycle")
                        .isBetween(Duration.ofMillis(300), Duration.ofSeconds(2));
                Duration stopObservation = Duration.ofMillis(1_000);
                assertThat(endpoint.hasRequestWithin(stopObservation))
                        .as("no fourth recovery cycle after three failed cycles")
                        .isFalse();
                System.out.printf("F006-B01 firstRetryDelayMs=%d secondRetryDelayMs=%d stopObservationMs=%d%n",
                        firstRetryDelay.toMillis(), secondRetryDelay.toMillis(), stopObservation.toMillis());
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
    @Story("F006-B02-custom: 非默认重试间隔与停止阈值可配置")
    @DisplayName("Feat-006 公开 RetryPolicy 控制恢复间隔并在配置阈值停止")
    void configuredRetryPolicyControlsRecoveryIntervalAndFailureLimit() throws Exception {
        try (ClientSdkBlackboxFixture endpoint = new ClientSdkBlackboxFixture()) {
            endpoint.enqueueSse(ClientSdkBlackboxFixture.status(
                    "task-configured-policy", "ctx-configured-policy", "TASK_STATE_WORKING", "planning"));
            enqueueInfrastructureFailures(endpoint, 8);
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
                        takeTimedRequests(endpoint, false, 9);
                assertRecoveryExhausted(call);

                assertThat(automaticRequests).extracting(request -> request.body().path("method").asText())
                        .containsExactlyElementsOf(recoveryMethods(4));
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
    @Story("F006-B03-gettask-reset: GetTask WORKING 清零连续失败计数")
    @DisplayName("Feat-006 GetTask WORKING 后重新累计三个完整失败周期")
    void getTaskWorkingResetsRecoveryFailureCounter() throws Exception {
        try (ClientSdkBlackboxFixture endpoint = new ClientSdkBlackboxFixture()) {
            endpoint.enqueueSse(ClientSdkBlackboxFixture.status(
                    "task-get-reset", "ctx-get-reset", "TASK_STATE_WORKING", "planning"));
            enqueueInfrastructureFailures(endpoint, 2);
            endpoint.enqueueHttpError(503, "SERVICE_UNAVAILABLE");
            endpoint.enqueueJson(ClientSdkBlackboxFixture.taskSnapshot(
                    "task-get-reset", "ctx-get-reset", "TASK_STATE_WORKING", "still-working"));
            enqueueInfrastructureFailures(endpoint, 6);

            try (AgentClient client = client(endpoint, EndpointType.RUNTIME, fastPolicy(3, 6))) {
                InvocationCall call = invoke(client, "gettask-counter-reset");
                List<JsonNode> requests = takeRequests(endpoint, false, 11);
                assertRecoveryExhausted(call);

                assertThat(requests).extracting(node -> node.path("method").asText())
                        .containsExactlyElementsOf(recoveryMethods(5));
                assertRecoveryTaskIds(requests);
                assertThat(endpoint.hasRequestWithin(Duration.ofMillis(300))).isFalse();
            }
        }
    }

    @Test
    @Story("F006-B03-subscribe-reset: Subscribe WORKING 帧清零连续失败计数")
    @DisplayName("Feat-006 Subscribe WORKING 帧后重新累计三个完整失败周期")
    void subscribeWorkingFrameResetsRecoveryFailureCounter() throws Exception {
        try (ClientSdkBlackboxFixture endpoint = new ClientSdkBlackboxFixture()) {
            endpoint.enqueueSse(ClientSdkBlackboxFixture.status(
                    "task-subscribe-reset", "ctx-subscribe-reset", "TASK_STATE_WORKING", "planning"));
            enqueueInfrastructureFailures(endpoint, 2);
            endpoint.enqueueSse(ClientSdkBlackboxFixture.status(
                    "task-subscribe-reset", "ctx-subscribe-reset", "TASK_STATE_WORKING", "reconnected"));
            enqueueInfrastructureFailures(endpoint, 5);

            try (AgentClient client = client(endpoint, EndpointType.GATEWAY, fastPolicy(3, 6))) {
                InvocationCall call = invoke(client, EndpointType.GATEWAY, "subscription-counter-reset");
                List<JsonNode> requests = takeRequests(endpoint, true, 9);
                InvocationSnapshot snapshot = call.completion().toCompletableFuture()
                        .get(8, TimeUnit.SECONDS);

                assertThat(snapshot.state()).isEqualTo(TaskState.WORKING);
                assertThat(snapshot.terminal()).isFalse();
                assertThat(snapshot.maybeRecovery()).isPresent();
                assertThat(requests).extracting(node -> node.path("method").asText())
                        .containsExactly(
                                "SendStreamingMessage", "SubscribeToTask", "GetTask",
                                "SubscribeToTask", "GetTask", "SubscribeToTask", "GetTask",
                                "SubscribeToTask", "GetTask");
                assertRecoveryTaskIds(requests);
                assertThat(endpoint.hasRequestWithin(Duration.ofMillis(300))).isFalse();
            }
        }
    }

    @ParameterizedTest(name = "known-task budget={0}, total requests={1}")
    @CsvSource({"2, 5", "6, 13"})
    @Story("F006-B05-budget: WORKING 不返还已知 Task 总恢复预算")
    @DisplayName("Feat-006 默认及自定义已知 Task 恢复预算均形成有限结束")
    void workingSnapshotsDoNotResetKnownTaskRecoveryBudget(int recoveryBudget,
                                                            int expectedRequestCount) throws Exception {
        try (ClientSdkBlackboxFixture endpoint = new ClientSdkBlackboxFixture()) {
            String taskId = "task-budget-" + recoveryBudget;
            String contextId = "ctx-budget-" + recoveryBudget;
            endpoint.enqueueSse(ClientSdkBlackboxFixture.status(
                    taskId, contextId, "TASK_STATE_WORKING", "planning"));
            for (int attempt = 0; attempt < recoveryBudget; attempt++) {
                endpoint.enqueueHttpError(503, "SERVICE_UNAVAILABLE");
                endpoint.enqueueJson(ClientSdkBlackboxFixture.taskSnapshot(
                        taskId, contextId, "TASK_STATE_WORKING", "still-working-" + attempt));
            }

            RetryPolicy policy = fastPolicy(3, recoveryBudget);
            try (AgentClient client = client(endpoint, EndpointType.GATEWAY, policy)) {
                InvocationSnapshot snapshot = invoke(client, EndpointType.GATEWAY,
                        "known-task-budget-" + recoveryBudget)
                        .completion().toCompletableFuture().get(8, TimeUnit.SECONDS);
                List<JsonNode> requests = takeRequests(endpoint, true, expectedRequestCount);

                assertThat(expectedRequestCount).isEqualTo(1 + 2 * recoveryBudget);
                assertThat(snapshot.state()).isEqualTo(TaskState.WORKING);
                assertThat(snapshot.terminal()).isFalse();
                assertThat(snapshot.maybeRecovery()).isPresent();
                assertThat(snapshot.recovery().suggestedAction())
                        .isEqualTo(InvocationSnapshot.Recovery.Action.QUERY_INVOCATION);
                assertThat(requests).extracting(node -> node.path("method").asText())
                        .containsExactlyElementsOf(recoveryMethods(recoveryBudget));
                assertRecoveryTaskIds(requests);
                assertThat(endpoint.hasRequestWithin(Duration.ofMillis(300)))
                        .as("no recovery cycle beyond the configured total budget")
                        .isFalse();
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
            enqueueInfrastructureFailures(endpoint, 6);
            endpoint.enqueueSse(ClientSdkBlackboxFixture.status(
                    "task-isolation-second", "ctx-isolation-second", "TASK_STATE_WORKING", "planning"));
            endpoint.enqueueSse(ClientSdkBlackboxFixture.status(
                    "task-isolation-second", "ctx-isolation-second", "TASK_STATE_COMPLETED", "done"));
            endpoint.enqueueJson(ClientSdkBlackboxFixture.taskSnapshot(
                    "task-isolation-first", "ctx-isolation-first", "TASK_STATE_COMPLETED", "first-done"));

            try (AgentClient client = client(endpoint, EndpointType.RUNTIME, fastPolicy(3, 6))) {
                InvocationCall first = invoke(client, "isolation-first");
                List<JsonNode> firstRequests = takeRequests(endpoint, false, 7);
                assertRecoveryExhausted(first);

                InvocationCall second = invoke(client, "isolation-second");
                InvocationSnapshot completed = second.completion().toCompletableFuture()
                        .get(8, TimeUnit.SECONDS);
                List<JsonNode> secondRequests = takeRequests(endpoint, false, 2);

                assertThat(completed.invocationRef()).isEqualTo(second.invocationRef())
                        .isNotEqualTo(first.invocationRef());
                assertThat(completed.state()).isEqualTo(TaskState.COMPLETED);
                assertThat(completed.outputText()).isEqualTo("done");
                assertThat(firstRequests).extracting(node -> node.path("method").asText())
                        .containsExactlyElementsOf(recoveryMethods(3));
                assertThat(secondRequests).extracting(node -> node.path("method").asText())
                        .containsExactly("SendStreamingMessage", "SubscribeToTask");
                assertRecoveryTaskIds(firstRequests);
                assertThat(secondRequests.get(1).at("/params/id").asText())
                        .isEqualTo("task-isolation-second");

                InvocationSnapshot reconciled = client.getInvocation(first.invocationRef())
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertThat(reconciled.state()).isEqualTo(TaskState.COMPLETED);
                assertThat(reconciled.outputText()).isEqualTo("first-done");
                JsonNode explicitQuery = endpoint.takeRequest(false);
                assertThat(explicitQuery.path("method").asText()).isEqualTo("GetTask");
                assertThat(explicitQuery.at("/params/id").asText()).isEqualTo("task-isolation-first");
            }
        }
    }

    @Test
    @Story("F006-R04-terminal-error: 结构化终态订阅错误经 GetTask 对账")
    @DisplayName("Feat-006 终态订阅错误不消耗失败次数并经 GetTask 收敛")
    void deterministicSubscriptionProtocolErrorDoesNotTriggerInfrastructureRetry() throws Exception {
        try (ClientSdkBlackboxFixture endpoint = new ClientSdkBlackboxFixture()) {
            endpoint.enqueueSse(ClientSdkBlackboxFixture.status(
                    "task-protocol-error", "ctx-protocol-error", "TASK_STATE_WORKING", "planning"));
            endpoint.enqueueJsonRpcError(-32602, "invalid task state for subscription",
                    "TASK_NOT_SUBSCRIBABLE_TERMINAL");
            endpoint.enqueueJson(ClientSdkBlackboxFixture.taskSnapshot(
                    "task-protocol-error", "ctx-protocol-error", "TASK_STATE_COMPLETED", "done"));

            RetryPolicy policy = RetryPolicy.builder()
                    .maxConsecutiveFailures(1)
                    .build();
            try (AgentClient client = AgentClients.builder()
                    .endpointType(EndpointType.RUNTIME)
                    .endpointUrl(endpoint.baseUrl())
                    .credentialProvider(conversationId -> "acceptance-token")
                    .retryPolicy(policy)
                    .build()) {
                InvocationSnapshot completed = invoke(client, "protocol-error")
                        .completion().toCompletableFuture().get(8, TimeUnit.SECONDS);

                assertThat(completed.state()).isEqualTo(TaskState.COMPLETED);
                assertThat(completed.outputText()).isEqualTo("done");
                List<JsonNode> requests = takeRequests(endpoint, false, 3);
                assertThat(requests).extracting(node -> node.path("method").asText())
                        .containsExactly("SendStreamingMessage", "SubscribeToTask", "GetTask");
                assertThat(requests.get(1).at("/params/id").asText())
                        .isEqualTo("task-protocol-error");
                assertThat(requests.get(2).at("/params/id").asText())
                        .isEqualTo("task-protocol-error");
                assertThat(requests).noneMatch(node -> "CancelTask".equals(node.path("method").asText()));
            }
        }
    }

    @Test
    @Story("F006-R04-invalid-params: 真正 INVALID_PARAMS 不进入基础设施重试")
    @DisplayName("Feat-006 非终态 INVALID_PARAMS 有限失败且不触发重试")
    void invalidParamsDoesNotTriggerInfrastructureRetry() throws Exception {
        try (ClientSdkBlackboxFixture endpoint = new ClientSdkBlackboxFixture()) {
            endpoint.enqueueSse(ClientSdkBlackboxFixture.status(
                    "task-invalid-params", "ctx-invalid-params", "TASK_STATE_WORKING", "planning"));
            endpoint.enqueueJsonRpcError(-32602, "request parameters are invalid", "INVALID_PARAMS");

            try (AgentClient client = endpoint.client(EndpointType.RUNTIME)) {
                InvocationCall call = invoke(client, "invalid-params");
                assertThatThrownBy(() -> call.completion().toCompletableFuture()
                        .get(8, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .cause().isInstanceOf(ClassifiedError.class)
                        .satisfies(error -> assertThat(((ClassifiedError) error).code())
                                .isEqualTo("INVALID_PARAMS"));

                List<JsonNode> requests = takeRequests(endpoint, false, 2);
                assertThat(requests).extracting(node -> node.path("method").asText())
                        .containsExactly("SendStreamingMessage", "SubscribeToTask");
                assertThat(requests.get(1).at("/params/id").asText())
                        .isEqualTo("task-invalid-params");
                assertThat(endpoint.hasRequestWithin(Duration.ofMillis(500)))
                        .as("INVALID_PARAMS must not start infrastructure reconciliation or retry")
                        .isFalse();
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
                    InvocationSnapshot snapshot = invoke(client, type, "endpoint-" + type.name().toLowerCase())
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

    private static AgentClient client(ClientSdkBlackboxFixture endpoint, EndpointType endpointType,
                                      RetryPolicy retryPolicy) {
        return AgentClients.builder()
                .endpointType(endpointType)
                .endpointUrl(endpoint.baseUrl())
                .credentialProvider(conversationId -> "acceptance-token")
                .retryPolicy(retryPolicy)
                .build();
    }

    private static RetryPolicy fastPolicy(int maxConsecutiveFailures, int maxKnownTaskRecoveryAttempts) {
        return RetryPolicy.builder()
                .maxConsecutiveFailures(maxConsecutiveFailures)
                .maxKnownTaskRecoveryAttempts(maxKnownTaskRecoveryAttempts)
                .initialDelay(Duration.ofMillis(25))
                .maxDelay(Duration.ofMillis(25))
                .multiplier(1.0d)
                .jitterFactor(0.0d)
                .build();
    }

    private static void enqueueInfrastructureFailures(ClientSdkBlackboxFixture endpoint, int count) {
        for (int i = 0; i < count; i++) {
            endpoint.enqueueHttpError(503, "SERVICE_UNAVAILABLE");
        }
    }

    private static List<String> recoveryMethods(int cycles) {
        List<String> methods = new ArrayList<>();
        methods.add("SendStreamingMessage");
        for (int i = 0; i < cycles; i++) {
            methods.add("SubscribeToTask");
            methods.add("GetTask");
        }
        return methods;
    }

    private static void assertRecoveryExhausted(InvocationCall call) {
        assertThatThrownBy(() -> call.completion().toCompletableFuture()
                .get(8, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(ClassifiedError.class)
                .satisfies(error -> assertThat(((ClassifiedError) error).code())
                        .isEqualTo("RECOVERY_RETRY_EXHAUSTED"));
    }

    private static InvocationCall invoke(AgentClient client, String scenario) {
        return invoke(client, EndpointType.RUNTIME, scenario);
    }

    private static InvocationCall invoke(AgentClient client, EndpointType endpointType, String scenario) {
        InvocationRequest.Builder request = endpointType == EndpointType.GATEWAY
                ? InvocationRequest.gatewayBuilder("travel-mainplan")
                : InvocationRequest.runtimeBuilder();
        return client.invoke(request
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

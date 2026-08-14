package com.huawei.ascend.sit.cases.integration.agent_bus;

import com.openjiuwen.client.api.AgentClient;
import com.openjiuwen.client.api.InvocationCall;
import com.openjiuwen.client.api.InvocationMode;
import com.openjiuwen.client.api.InvocationRequest;
import com.openjiuwen.client.api.TaskState;
import com.openjiuwen.client.spi.Governance;
import com.openjiuwen.client.tool.spi.LocalToolDescriptor;
import com.openjiuwen.client.tool.spi.ToolExecutionRecord;
import com.openjiuwen.client.tool.spi.ToolExposurePolicy;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Feature("FEAT-007: 客户端本地工具注册与执行")
@Tag("feat-007")
@Tag("integration")
@Tag("blackbox")
class LocalToolBlackboxTest {

    @Test
    @Story("FEAT-007.exposure-and-observation: 显式暴露与 Observation 闭环")
    @Tag("story-feat-007-exposure-and-observation")
    @DisplayName("Feat-007 仅显式暴露工具并自动回传 Observation")
    void feat007ExposurePoliciesProducePerInvocationToolViewAndExecuteObservation() throws Exception {
        String conversation = "tools-" + UUID.randomUUID();
        try (ClientSdkBlackboxFixture gateway = new ClientSdkBlackboxFixture()) {
            gateway.enqueueSse(ClientSdkBlackboxFixture.inputRequired(
                    "task-tool", conversation, "call-weather", "local-weather", "{\"city\":\"Shanghai\"}"));
            gateway.enqueueJson(ClientSdkBlackboxFixture.task(
                    "task-tool", conversation, "TASK_STATE_COMPLETED", "weather received"));

            try (AgentClient client = gateway.client()) {
                AtomicInteger executions = new AtomicInteger();
                client.tools().register(descriptor("local-weather", LocalToolDescriptor.SideEffect.OBSERVATION),
                        (invocation, context) -> {
                            executions.incrementAndGet();
                            return ToolExecutionRecord.ok(invocation.toolCallId(),
                                    Map.of("city", "Shanghai", "temperature", 26));
                        });
                client.exposeInConversation(conversation, ToolExposurePolicy.allow("local-weather"));

                InvocationCall call = client.invoke(request(conversation, "inv-tool", ToolExposurePolicy.all()));
                ClientSdkBlackboxFixture.subscribe(call).await();
                assertThat(call.completion().toCompletableFuture().get(3, TimeUnit.SECONDS).state())
                        .isEqualTo(TaskState.COMPLETED);
                assertThat(executions).hasValue(1);

                var create = gateway.takeRequest();
                assertThat(create.at("/params/metadata/clientTools")).hasSize(1);
                assertThat(create.at("/params/metadata/clientTools/0/name").asText()).isEqualTo("local-weather");
                var resume = gateway.takeRequest();
                assertThat(resume.at("/params/message/taskId").asText()).isEqualTo("task-tool");
                assertThat(resume.at("/params/message/parts/0/metadata/toolCallId").asText())
                        .isEqualTo("call-weather");
                assertThat(resume.at("/params/message/parts/0/text").asText())
                        .contains("Shanghai", "temperature");
            }
        }
    }

    @Test
    @Story("FEAT-007.action-governance: Action 本地治理与结构化失败")
    @Tag("story-feat-007-action-governance")
    @DisplayName("Feat-007 Action 审批拒绝不执行副作用并回传结构化结果")
    void feat007ActionToolAppliesLocalApprovalAndReturnsStructuredOutcome() throws Exception {
        String conversation = "action-" + UUID.randomUUID();
        try (ClientSdkBlackboxFixture gateway = new ClientSdkBlackboxFixture()) {
            gateway.enqueueSse(ClientSdkBlackboxFixture.inputRequired(
                    "task-action", conversation, "call-write", "local-write", "{\"value\":1}"));
            gateway.enqueueJson(ClientSdkBlackboxFixture.task(
                    "task-action", conversation, "TASK_STATE_COMPLETED", "denial observed"));

            AtomicInteger executions = new AtomicInteger();
            try (AgentClient client = com.openjiuwen.client.api.AgentClients.builder()
                     .transport(new com.openjiuwen.client.transport.a2a.A2aHttpTransportProvider(
                             gatewayUrl(gateway), ClientSdkBlackboxFixture.JSON, Duration.ofSeconds(3)))
                     .credentialProvider(conversationId -> "acceptance-token")
                     .approvalProvider((descriptor, invocation, context) -> CompletableFuture.supplyAsync(
                             () -> Governance.ApprovalDecision.denied("operator denied"),
                             CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS)))
                    .build()) {
                client.tools().register(descriptor("local-write", LocalToolDescriptor.SideEffect.ACTION),
                        (invocation, context) -> {
                            executions.incrementAndGet();
                            return ToolExecutionRecord.ok(invocation.toolCallId(), Map.of("written", true));
                        });
                client.exposeInConversation(conversation, ToolExposurePolicy.allow("local-write"));
                InvocationCall call = client.invoke(request(conversation, "inv-action", ToolExposurePolicy.all()));
                var events = ClientSdkBlackboxFixture.subscribe(call);
                gateway.takeRequest();
                var resume = gateway.takeRequest();
                assertThat(resume.at("/params/message/parts/0/text").asText())
                        .containsIgnoringCase("rejected").contains("operator denied");
                events.await();
                assertThat(call.completion().toCompletableFuture().get(3, TimeUnit.SECONDS).state())
                        .isEqualTo(TaskState.COMPLETED);
                assertThat(executions).hasValue(0);
            }
        }
    }

    @Test
    @Story("FEAT-007.idempotent-result: 重复工具投影与单次最终结果")
    @Tag("story-feat-007-idempotent-result")
    @DisplayName("Feat-007 重复工具请求只执行一次并提交一个最终结果")
    void feat007DuplicateToolProjectionExecutesOnceAndSubmitsOneFinalResult() throws Exception {
        String conversation = "dedup-" + UUID.randomUUID();
        String toolFrame = ClientSdkBlackboxFixture.inputRequired(
                "task-dedup", conversation, "same-call", "local-read", "{\"key\":\"a\"}");
        try (ClientSdkBlackboxFixture gateway = new ClientSdkBlackboxFixture()) {
            gateway.enqueueSse(toolFrame, toolFrame);
            gateway.enqueueJson(ClientSdkBlackboxFixture.task(
                    "task-dedup", conversation, "TASK_STATE_COMPLETED", "one result"));
            AtomicInteger executions = new AtomicInteger();
            try (AgentClient client = gateway.client()) {
                client.tools().register(descriptor("local-read", LocalToolDescriptor.SideEffect.OBSERVATION),
                        (invocation, context) -> {
                            executions.incrementAndGet();
                            return ToolExecutionRecord.ok(invocation.toolCallId(), Map.of("value", "one"));
                        });
                client.exposeInConversation(conversation, ToolExposurePolicy.allow("local-read"));
                InvocationCall call = client.invoke(request(conversation, "inv-dedup", ToolExposurePolicy.all()));
                ClientSdkBlackboxFixture.subscribe(call).await();
                assertThat(call.completion().toCompletableFuture().get(3, TimeUnit.SECONDS).state())
                        .isEqualTo(TaskState.COMPLETED);
                assertThat(executions).hasValue(1);
                gateway.takeRequest();
                gateway.takeRequest();
            }
        }
    }

    private static LocalToolDescriptor descriptor(String id, LocalToolDescriptor.SideEffect sideEffect) {
        return LocalToolDescriptor.builder(id)
                .description("acceptance " + id)
                .sideEffect(sideEffect)
                .inputSchema("{\"type\":\"object\"}")
                .build();
    }

    private static InvocationRequest request(String conversation, String invocation,
                                             ToolExposurePolicy policy) {
        return InvocationRequest.builder()
                .agentId("travel-mainplan")
                .conversationId(conversation)
                .invocationId(invocation)
                .mode(InvocationMode.STREAMING)
                .input("execute local tool")
                .exposure(policy)
                .build();
    }

    private static String gatewayUrl(ClientSdkBlackboxFixture gateway) {
        return gateway.baseUrl();
    }
}

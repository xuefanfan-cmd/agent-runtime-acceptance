package com.huawei.ascend.sit.cases.e2e.reconnect;

import com.huawei.ascend.sit.fault.FaultLink;
import com.huawei.ascend.sit.fixtures.reconnect.DeepAgentReconnectFixture;
import com.openjiuwen.client.api.AgentClient;
import com.openjiuwen.client.api.InvocationCall;
import com.openjiuwen.client.api.InvocationEvent;
import com.openjiuwen.client.api.InvocationMode;
import com.openjiuwen.client.api.InvocationRequest;
import com.openjiuwen.client.api.InvocationSnapshot;
import com.openjiuwen.client.api.TaskState;
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Feature("FEAT-011: 客户端调用路由转发")
@Tag("feat-001")
@Tag("feat-006")
@Tag("feat-011")
@Tag("deepagent")
@Tag("e2e")
class DeepAgentGatewayReconnectIT {
    private static final String COMPARISON_QUERY =
            "对比 DeepSeek V3、Qwen-Max、Doubao-pro-32k 三家的大模型 API 输入定价";
    private static final List<String> RESULT_MARKERS = List.of("qwen-max", "火山方舟", "$0.27");

    @Test
    @Stories({
            @Story("F011-E02: DeepAgent Gateway 长流 owner/SSE Bridge 恢复"),
            @Story("F006-E02: DeepAgent 断流后恢复原 Task"),
            @Story("RT-R07: 远程调用不重复 Oracle 维持 partial")
    })
    @DisplayName("Feat-001/006/011 Client 经 Gateway 恢复原 DeepAgent 长流 Task")
    void clientReconnectsThroughGatewayToOriginalDeepAgentTask() throws Exception {
        Assumptions.assumeTrue(DeepAgentReconnectFixture.hasLlmCredentials(),
                "blocked/not-run: DeepAgent Gateway E2E requires LLM_API_KEY");
        try (DeepAgentReconnectFixture environment = DeepAgentReconnectFixture.gateway();
             AgentClient client = environment.client()) {
            String conversationId = "deep-reconnect-" + UUID.randomUUID();
            InvocationCall call = client.invoke(InvocationRequest.builder()
                    .agentId(DeepAgentReconnectFixture.agentId())
                    .conversationId(conversationId)
                    .invocationId("inv-" + UUID.randomUUID())
                    .mode(InvocationMode.STREAMING)
                    .input(COMPARISON_QUERY)
                    .build());
            ReconnectEventProbe probe = new ReconnectEventProbe();
            call.events().subscribe(probe);

            String taskId = call.accepted().toCompletableFuture()
                    .get(45, TimeUnit.SECONDS).diagnosticTaskRef();
            assertThat(taskId).as("accepted diagnostic task id").isNotBlank();
            Assumptions.assumeTrue(probe.awaitWorking(45, TimeUnit.SECONDS),
                    "INCONCLUSIVE: DeepAgent reached no observable WORKING window");

            FaultLink link = environment.faultLink();
            link.resetPeer();
            try {
                Thread.sleep(250);
            } finally {
                link.restore();
            }

            InvocationSnapshot completed = call.completion().toCompletableFuture()
                    .get(300, TimeUnit.SECONDS);
            InvocationSnapshot queried = client.getInvocation(call.invocationRef())
                    .toCompletableFuture().get(30, TimeUnit.SECONDS);

            assertThat(completed.diagnosticTaskRef()).isEqualTo(taskId);
            assertThat(completed.state()).isEqualTo(TaskState.COMPLETED);
            assertThat(completed.terminal()).isTrue();
            assertThat(completed.maybeRecovery()).isEmpty();
            assertThat(completed.outputText()).isNotBlank();
            assertThat(queried.diagnosticTaskRef()).isEqualTo(taskId);
            assertThat(queried.state()).isEqualTo(TaskState.COMPLETED);
            assertThat(probe.events()).noneMatch(InvocationEvent.Failed.class::isInstance);
            assertThat(RESULT_MARKERS.stream().filter(completed.outputText()::contains).count())
                    .as("final report should contain at least two fixture-backed vendor markers")
                    .isGreaterThanOrEqualTo(2);
        }
    }
}

package com.huawei.ascend.sit.cases.integration.agent_client_ts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;

import static com.huawei.ascend.sit.cases.integration.agent_client_ts.Feat087MockA2aServer.JSON;
import static com.huawei.ascend.sit.cases.integration.agent_client_ts.Feat087TsSdkHarness.invokeOp;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-087 请求确认面：载荷容错、三种动作、多确认逐个提交、超时、
 * 重新开放、关闭清理与 result 结算。对应 F087-32 … F087-37。
 */
@Tag("integration")
@Tag("agent-client-ts")
@Tag("feat-087")
@Feature("FEAT-087: Agent-Client支持Node服务TS语言")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Feat087ConfirmationBlackboxTest {

    private Feat087TsSdkHarness harness;

    @BeforeAll
    void setUp() {
        harness = new Feat087TsSdkHarness();
    }

    @BeforeEach
    void assumeSdkEnvironmentReady() {
        // 依赖门禁：SUT（TS SDK）未交付时逐用例 SKIPPED，不计 FAIL
        Feat087TsSdkHarness.assumeEnvironmentReady(harness.sdkDir());
        // 用例间隔离：清空 mock 规则（防 onEvery 持久规则泄漏）与 wire 记录
        harness.server().resetRules();
        harness.server().takeRequests();
    }

    @AfterAll
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    private ObjectNode client(String conversationId) {
        return Feat087TsSdkHarness.clientSpec("GATEWAY", harness.server().url(), conversationId);
    }

    private void scriptCreateConfirm(String conv, String taskId, JsonNode interrupt) {
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText())
                        && !b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.sse(List.of(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.statusUpdate(taskId, conv, "TASK_STATE_INPUT_REQUIRED", "", interrupt))))));
    }

    private void scriptResumeCompleted(String conv, String taskId, String text) {
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText())
                        && b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.sse(List.of(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.statusUpdate(taskId, conv, "TASK_STATE_COMPLETED", text, null))))));
    }

    private ObjectNode answerOp(String confirmationRef, String action, String feedback) {
        ObjectNode op = Feat087TsSdkHarness.op("answer");
        op.put("confirmationRef", confirmationRef).put("round", 1).put("action", action);
        if (feedback != null) {
            op.put("feedback", feedback);
        }
        return op;
    }

    private JsonNode lastConfirmationWire() {
        JsonNode found = null;
        for (ObjectNode r : harness.server().takeRequests()) {
            JsonNode parts = r.path("params").path("message").path("parts");
            if (parts.isArray() && parts.size() > 0
                    && parts.get(0).has("data") || parts.size() > 1) {
                found = r;
            }
        }
        return found;
    }

    @Test
    @Tag("story-feat-087-32")
    @Story("FEAT-087.F087-32: 确认载荷校验容错")
    @DisplayName("F087-32 载荷非法只诊断不投递，未知风险显示 UNKNOWN，空列表合法")
    void f08732ConfirmationPayloadTolerance() {
        // 缺 command_summary → INVALID_CONFIRMATION，不开放
        ObjectNode broken = JSON.createObjectNode();
        broken.put("toolCallId", "call-32a").put("toolName", "server-tool");
        ObjectNode badAsk = broken.putObject("context");
        badAsk.put("_interrupt_kind", "tool_permission_ask");
        badAsk.putArray("matched_rules");
        badAsk.putArray("file_guard_hits");
        badAsk.put("decision", "ask_user");
        badAsk.put("risk_hint", "high");
        badAsk.set("actions", Feat087Interrupts.defaultActions());
        scriptCreateConfirm("conv-32a", "task-32a", Feat087Interrupts.items(broken));
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(client("conv-32a"),
                JSON.createArrayNode().add(invokeOp("STREAMING", "go", "inv-32a", "event:diagnostic"))), 30_000);
        assertThat(Feat087TsSdkHarness.events(result, "confirmation.required"))
                .as("非法载荷不投递可操作确认").isEmpty();
        assertThat(Feat087TsSdkHarness.events(result, "diagnostic").toString()).contains("INVALID_CONFIRMATION");

        // 未知风险 → risk=UNKNOWN + 诊断；空规则/文件列表合法开放
        ObjectNode unknownRisk = Feat087Interrupts.confirmation("call-32b", "server-tool", "extreme", null);
        scriptCreateConfirm("conv-32b", "task-32b", Feat087Interrupts.single(unknownRisk));
        result = harness.runDriver(Feat087TsSdkHarness.scenario(client("conv-32b"),
                JSON.createArrayNode().add(invokeOp("STREAMING", "go", "inv-32b", "event:confirmation.required"))), 30_000);
        List<JsonNode> confirmations = Feat087TsSdkHarness.events(result, "confirmation.required");
        assertThat(confirmations).hasSize(1);
        assertThat(confirmations.get(0).path("confirmation").path("risk").asText()).isEqualTo("UNKNOWN");

        // actions 为空数组 → 不开放
        ObjectNode noActions = Feat087Interrupts.confirmation("call-32c", "server-tool", "high", JSON.createArrayNode());
        scriptCreateConfirm("conv-32c", "task-32c", Feat087Interrupts.single(noActions));
        result = harness.runDriver(Feat087TsSdkHarness.scenario(client("conv-32c"),
                JSON.createArrayNode().add(invokeOp("STREAMING", "go", "inv-32c", "none"))), 15_000);
        assertThat(Feat087TsSdkHarness.events(result, "confirmation.required"))
                .as("无可识别动作不开放").isEmpty();

        // 未知动作忽略并诊断；仍有可识别动作时正常开放（A42）
        ArrayNode mixedActions = JSON.createArrayNode();
        mixedActions.add("allow").add("dance");
        ObjectNode unknownAction = Feat087Interrupts.confirmation("call-32d", "server-tool", "high", mixedActions);
        scriptCreateConfirm("conv-32d", "task-32d", Feat087Interrupts.single(unknownAction));
        result = harness.runDriver(Feat087TsSdkHarness.scenario(client("conv-32d"),
                JSON.createArrayNode().add(invokeOp("STREAMING", "go", "inv-32d", "event:confirmation.required"))), 30_000);
        List<JsonNode> opened = Feat087TsSdkHarness.events(result, "confirmation.required");
        assertThat(opened).as("未知动作被忽略后仍开放").hasSize(1);
        JsonNode allowedActions = opened.get(0).path("confirmation").path("allowedActions");
        assertThat(allowedActions.size()).as("只保留可识别动作").isEqualTo(1);
        assertThat(allowedActions.get(0).asText()).isEqualTo("allow");
        assertThat(Feat087TsSdkHarness.events(result, "diagnostic").toString())
                .contains("UNKNOWN_ACTION");
    }

    @Test
    @Tag("story-feat-087-33")
    @Tag("smoke-feat-087")
    @Story("FEAT-087.F087-33: 三种动作与 feedback")
    @DisplayName("F087-33 allow/reject/cancel 回答上 wire 且语义由服务端状态体现")
    void f08733ThreeActionsWithFeedback() {
        ObjectNode interrupt = Feat087Interrupts.single(
                Feat087Interrupts.confirmation("call-33", "server-tool", "high", null));
        for (String[] actionAndState : new String[][] {
                {"allow", "TASK_STATE_COMPLETED"}, {"reject", "TASK_STATE_COMPLETED"}, {"cancel", "TASK_STATE_CANCELED"}}) {
            String conv = "conv-33-" + actionAndState[0];
            scriptCreateConfirm(conv, "task-33", interrupt.deepCopy());
            scriptResumeCompleted(conv, "task-33", actionAndState[0]);
            ArrayNode ops = JSON.createArrayNode();
            ops.add(invokeOp("STREAMING", "go", "inv-33", "event:confirmation.required"));
            ops.add(answerOp("call-33", actionAndState[0], "宿主说明-" + actionAndState[0]));
            ops.add(Feat087TsSdkHarness.op("waitEvent").put("type", "terminal"));
            ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(client(conv), ops), 30_000);
            assertThat(result.has("fatal")).isFalse();
            JsonNode answerWire = lastConfirmationWire();
            assertThat(answerWire).as(actionAndState[0] + " 回答请求").isNotNull();
            JsonNode parts = answerWire.path("params").path("message").path("parts");
            assertThat(parts.get(0).path("text").asText()).isEqualTo(actionAndState[0]);
            JsonNode data = parts.get(1).path("data").path("guardrail_confirm");
            assertThat(data.path("action").asText()).isEqualTo(actionAndState[0]);
            assertThat(data.path("tool_call_id").asText()).isEqualTo("call-33");
            assertThat(data.path("feedback").asText()).isEqualTo("宿主说明-" + actionAndState[0]);
        }
    }

    @Test
    @Tag("story-feat-087-34")
    @Story("FEAT-087.F087-34: 一轮多个确认逐个提交")
    @DisplayName("F087-34 两个确认都投递且逐个回答")
    void f08734MultipleConfirmationsAnsweredSequentially() {
        String conv = "conv-34";
        JsonNode interrupt = Feat087Interrupts.items(
                Feat087Interrupts.confirmation("call-34a", "server-tool-a", "high", null),
                Feat087Interrupts.confirmation("call-34b", "server-tool-b", "high", null));
        scriptCreateConfirm(conv, "task-34", interrupt);
        scriptResumeCompleted(conv, "task-34", "ok");
        ArrayNode ops = JSON.createArrayNode();
        ops.add(invokeOp("STREAMING", "go", "inv-34", "event:confirmation.required"));
        ObjectNode waitSecond = Feat087TsSdkHarness.op("waitEvent");
        waitSecond.put("type", "confirmation.required");
        ops.add(waitSecond);
        ops.add(answerOp("call-34a", "allow", null));
        ObjectNode answerB = Feat087TsSdkHarness.op("answer");
        answerB.put("confirmationRef", "call-34b").put("round", 1).put("action", "reject");
        ops.add(answerB);
        ops.add(Feat087TsSdkHarness.op("waitEvent").put("type", "terminal"));
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(client(conv), ops), 30_000);
        assertThat(Feat087TsSdkHarness.events(result, "confirmation.required").size())
                .as("两个确认均投递").isEqualTo(2);
    }

    @Test
    @Tag("story-feat-087-35")
    @Story("FEAT-087.F087-35: 确认超时与自动回答边界")
    @DisplayName("F087-35 默认不自动回答；含 reject 到期自动拒；不含 reject 只通知")
    void f08735ConfirmationTimeoutBehavior() {
        ObjectNode interrupt = Feat087Interrupts.single(
                Feat087Interrupts.confirmation("call-35", "server-tool", "high", null));
        // 默认（未配置超时）：不自动回答
        scriptCreateConfirm("conv-35a", "task-35a", interrupt.deepCopy());
        ArrayNode ops = JSON.createArrayNode();
        ops.add(invokeOp("STREAMING", "go", "inv-35a", "event:confirmation.required"));
        ops.add(Feat087TsSdkHarness.op("sleep").put("ms", 500));
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(client("conv-35a"), ops), 15_000);
        assertThat(Feat087TsSdkHarness.events(result, "confirmation.timeout"))
                .as("默认关闭超时不自动回答").isEmpty();

        // 启用且可选动作含 reject：到期自动 reject
        ObjectNode cli = client("conv-35b");
        cli.putObject("confirmation").put("timeoutMs", 200);
        scriptCreateConfirm("conv-35b", "task-35b", interrupt.deepCopy());
        scriptResumeCompleted("conv-35b", "task-35b", "auto-rejected");
        ops = JSON.createArrayNode();
        ops.add(invokeOp("STREAMING", "go", "inv-35b", "event:confirmation.required"));
        ObjectNode waitTimeout = Feat087TsSdkHarness.op("waitEvent");
        waitTimeout.put("type", "confirmation.timeout");
        ops.add(waitTimeout);
        result = harness.runDriver(Feat087TsSdkHarness.scenario(cli, ops), 30_000);
        List<JsonNode> timeouts = Feat087TsSdkHarness.events(result, "confirmation.timeout");
        assertThat(timeouts).hasSize(1);
        assertThat(timeouts.get(0).path("autoRejected").asBoolean()).isTrue();
        harness.server().takeRequests(); // 清空 35b 场景的 wire 记录，避免自动拒答续接请求泄漏进 35c 断言

        // 可选动作不含 reject：只通知，保持 OPEN 可回答
        ObjectNode allowOnly = Feat087Interrupts.confirmation("call-35c", "server-tool", "high",
                JSON.createArrayNode().add("allow"));
        ObjectNode cli2 = client("conv-35c");
        cli2.putObject("confirmation").put("timeoutMs", 200);
        scriptCreateConfirm("conv-35c", "task-35c", Feat087Interrupts.single(allowOnly));
        ops = JSON.createArrayNode();
        ops.add(invokeOp("STREAMING", "go", "inv-35c", "event:confirmation.required"));
        ops.add(Feat087TsSdkHarness.op("sleep").put("ms", 500));
        result = harness.runDriver(Feat087TsSdkHarness.scenario(cli2, ops), 15_000);
        timeouts = Feat087TsSdkHarness.events(result, "confirmation.timeout");
        assertThat(timeouts).hasSize(1);
        assertThat(timeouts.get(0).path("autoRejected").asBoolean()).isFalse();
        assertThat(harness.server().takeRequests().stream()
                .filter(r -> r.path("params").path("message").has("taskId")).count())
                .as("不含 reject 时不提交自动回答").isZero();

        // 同轮两个确认各自计时（A45）：回答第一个、其提交在途期间第二个照常到期
        JsonNode twoConf = Feat087Interrupts.items(
                Feat087Interrupts.confirmation("call-35d1", "server-tool", "high", null),
                Feat087Interrupts.confirmation("call-35d2", "server-tool", "high", null));
        ObjectNode cli3 = client("conv-35d");
        cli3.putObject("confirmation").put("timeoutMs", 300);
        scriptCreateConfirm("conv-35d", "task-35d", twoConf);
        // 第一个回答的响应挂起 800ms：第二个确认计时器（300ms）必须在其间独立到期
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText())
                        && b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.hangThen(800, Feat087MockA2aServer.sse(List.of(
                        Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                                Feat087MockA2aServer.statusUpdate("task-35d", "conv-35d", "TASK_STATE_COMPLETED", "ok", null)))))));
        ops = JSON.createArrayNode();
        ops.add(invokeOp("STREAMING", "go", "inv-35d", "event:confirmation.required"));
        ops.add(Feat087TsSdkHarness.op("answer")
                .put("confirmationRef", "call-35d1").put("round", 1).put("action", "allow"));
        ops.add(Feat087TsSdkHarness.op("waitEvent").put("type", "confirmation.timeout"));
        ops.add(Feat087TsSdkHarness.op("waitEvent").put("type", "terminal"));
        result = harness.runDriver(Feat087TsSdkHarness.scenario(cli3, ops), 30_000);
        List<JsonNode> independentTimeouts = Feat087TsSdkHarness.events(result, "confirmation.timeout");
        assertThat(independentTimeouts).as("回答第一个不影响第二个计时").hasSize(1);
        assertThat(independentTimeouts.get(0).path("confirmationRef").asText()).isEqualTo("call-35d2");
        assertThat(independentTimeouts.get(0).path("autoRejected").asBoolean()).isTrue();
    }

    @Test
    @Tag("story-feat-087-36")
    @Story("FEAT-087.F087-36: 重新开放与关闭清理")
    @DisplayName("F087-36 提交失败后 round 递增重新开放，关闭后回答被拒")
    void f08736ReopenAfterFailedSubmissionAndCloseCleanup() {
        ObjectNode interrupt = Feat087Interrupts.single(
                Feat087Interrupts.confirmation("call-36", "server-tool", "high", null));
        String conv = "conv-36";
        // 回答提交失败（502）→ 核对仍待确认 → 重新开放 round=2
        scriptCreateConfirm(conv, "task-36", interrupt.deepCopy());
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText())
                        && b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.json(502, JSON.createObjectNode().put("code", "FORWARD_FAILED")));
        harness.server().onWhere(b -> "GetTask".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.json(200, Feat087MockA2aServer.rpcResult(Feat087MockA2aServer.task("task-36", conv,
                        "TASK_STATE_INPUT_REQUIRED", "", interrupt.deepCopy()))));
        ArrayNode ops = JSON.createArrayNode();
        ops.add(invokeOp("STREAMING", "go", "inv-36", "event:confirmation.required"));
        ops.add(answerOp("call-36", "allow", null));
        ObjectNode waitReopen = Feat087TsSdkHarness.op("waitEvent");
        waitReopen.put("type", "confirmation.required");
        ops.add(waitReopen);
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(client(conv), ops), 30_000);
        List<JsonNode> confirmations = Feat087TsSdkHarness.events(result, "confirmation.required");
        assertThat(confirmations.size()).as("失败后重新开放").isGreaterThanOrEqualTo(2);
        assertThat(confirmations.get(confirmations.size() - 1).path("confirmation").path("round").asInt())
                .as("重新开放 round 递增").isGreaterThan(1);

        // 关闭后回答本地拒绝
        String conv2 = "conv-36x";
        scriptCreateConfirm(conv2, "task-36x", interrupt.deepCopy());
        ops = JSON.createArrayNode();
        ops.add(invokeOp("STREAMING", "go", "inv-36x", "event:confirmation.required"));
        ops.add(Feat087TsSdkHarness.op("closeCall"));
        ops.add(answerOp("call-36", "allow", null));
        result = harness.runDriver(Feat087TsSdkHarness.scenario(client(conv2), ops), 15_000);
        assertThat(Feat087TsSdkHarness.opResult(result, 2).path("error").path("code").asText())
                .isEqualTo("CLOSED");
        // 关闭有未决确认的调用：RESIDUAL_PENDING 诊断，提示服务端 Task 仍在等待（A48 / FEAT §5.4）
        assertThat(Feat087TsSdkHarness.events(result, "diagnostic").toString())
                .contains("RESIDUAL_PENDING");
    }

    @Test
    @Tag("story-feat-087-37")
    @Story("FEAT-087.F087-37: 确认不结算 result")
    @DisplayName("F087-37 等待确认时 result 不结算，回答后兑现最终结果")
    void f08737ConfirmationDoesNotSettleResult() {
        ObjectNode interrupt = Feat087Interrupts.single(
                Feat087Interrupts.confirmation("call-37", "server-tool", "high", null));
        String conv = "conv-37";
        scriptCreateConfirm(conv, "task-37", interrupt.deepCopy());
        scriptResumeCompleted(conv, "task-37", "final");
        ArrayNode ops = JSON.createArrayNode();
        ops.add(invokeOp("STREAMING", "go", "inv-37", "event:confirmation.required"));
        ops.add(Feat087TsSdkHarness.op("sleep").put("ms", 400));
        ops.add(Feat087TsSdkHarness.op("callState"));
        ops.add(answerOp("call-37", "allow", null));
        ops.add(Feat087TsSdkHarness.op("waitEvent").put("type", "terminal"));
        ops.add(Feat087TsSdkHarness.op("callState"));
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(client(conv), ops), 30_000);
        assertThat(Feat087TsSdkHarness.opResult(result, 2).path("resultSettled").asBoolean())
                .as("等待确认期间 result 未结算").isFalse();
        assertThat(Feat087TsSdkHarness.opResult(result, 5).path("resultSettled").asBoolean())
                .as("回答后 result 兑现").isTrue();
    }
}

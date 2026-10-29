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
 * FEAT-087 本地工具面：注册与默认值、暴露策略、请求校验、治理回调、
 * 同步/异步与超时、批量回填、去重与续接核对、执行记录、缺种类待办。
 * 对应 F087-21 … F087-31。
 */
@Tag("integration")
@Tag("agent-client-ts")
@Tag("feat-087")
@Feature("FEAT-087: Agent-Client支持Node服务TS语言")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Feat087LocalToolBlackboxTest {

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

    private ObjectNode toolSpec(String toolId, String behavior) {
        ObjectNode spec = JSON.createObjectNode();
        ObjectNode descriptor = spec.putObject("descriptor");
        descriptor.put("toolId", toolId).put("kind", "OBSERVATION");
        spec.put("behavior", behavior);
        return spec;
    }

    private static ObjectNode interruptFor(String callId, String toolName, ObjectNode arguments) {
        return Feat087Interrupts.single(Feat087Interrupts.clientTool(callId, toolName, arguments, true));
    }

    private void scriptToolRound(String conv, String taskId, JsonNode interrupt, String finalText) {
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText())
                        && !b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.sse(List.of(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.statusUpdate(taskId, conv, "TASK_STATE_INPUT_REQUIRED", "", interrupt))))));
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText())
                        && b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.sse(List.of(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.statusUpdate(taskId, conv, "TASK_STATE_COMPLETED", finalText, null))))));
    }

    private ObjectNode invokeWithExposure(String invocationId, String await) {
        ObjectNode invoke = invokeOp("STREAMING", "go", invocationId, await);
        invoke.put("toolExposure", JSON.createObjectNode().put("type", "all"));
        return invoke;
    }

    private JsonNode lastResumeWire() {
        JsonNode resume = null;
        for (ObjectNode r : harness.server().takeRequests()) {
            if ("SendStreamingMessage".equals(r.path("method").asText())
                    && r.path("params").path("message").has("taskId")) {
                resume = r;
            }
        }
        return resume;
    }

    private String resumePartText(JsonNode resume) {
        return resume.path("params").path("message").path("parts").get(0).path("text").asText();
    }

    private boolean resumeMentions(JsonNode resume, String needle) {
        for (JsonNode part : resume.path("params").path("message").path("parts")) {
            if (part.path("text").asText().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @Tag("story-feat-087-40")
    @Story("FEAT-087.F087-40: 目录更新与新工具不进在途")
    @DisplayName("F087-40 创建后注册的新工具不进入在途调用，未声明请求按 tool_not_declared 拒绝")
    void f08740MidCallRegistrationDoesNotEnterInFlightRound() {
        // 创建前仅注册并暴露 t-40a；服务端一轮同时请求 t-40a 与 t-40b
        ArrayNode tools = JSON.createArrayNode();
        tools.add(toolSpec("t-40a", "ok"));
        ObjectNode cli = client("conv-40");
        cli.set("tools", tools);
        cli.putObject("exposure").put("type", "allow").putArray("toolIds").add("t-40a");
        JsonNode interrupt = Feat087Interrupts.items(
                Feat087Interrupts.clientTool("call-40a", "t-40a", JSON.createObjectNode(), true),
                Feat087Interrupts.clientTool("call-40b", "t-40b", JSON.createObjectNode(), true));
        scriptToolRound("conv-40", "task-40", interrupt, "done");
        ArrayNode ops = JSON.createArrayNode();
        ops.add(invokeOp("STREAMING", "go", "inv-40", "state:COMPLETED"));
        // 调用结束后在同一 client 上注册 t-40b：不得追溯进入上一轮（在途集合在请求时已冻结）
        ObjectNode lateReg = Feat087TsSdkHarness.op("register");
        lateReg.putObject("descriptor").put("toolId", "t-40b").put("kind", "OBSERVATION");
        lateReg.put("behavior", "ok");
        ops.add(lateReg);
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(cli, ops), 30_000);
        assertThat(result.has("fatal")).isFalse();
        assertThat(Feat087TsSdkHarness.opResult(result, 1).path("ok").asBoolean())
                .as("同 client 中途注册成功").isTrue();

        // 已上报清单不变：仅创建请求携带 clientTools 且只含 t-40a
        List<ObjectNode> wire = harness.server().takeRequests();
        JsonNode create = wire.get(0);
        JsonNode clientTools = create.path("params").path("metadata").path("clientTools");
        assertThat(clientTools.size()).as("创建清单只含创建时暴露的工具").isEqualTo(1);
        assertThat(clientTools.get(0).path("name").asText()).as("wire 工具名字段为 name（L2 §5.2）").isEqualTo("t-40a");

        // 在途分发时 t-40b 未声明：拒绝回填且 handler 不执行；t-40a 正常执行
        // （复用上方 takeRequests() 取到的 wire 列表；再次取队列会因已清空而得 null）
        JsonNode resume = null;
        for (ObjectNode r : wire) {
            if ("SendStreamingMessage".equals(r.path("method").asText())
                    && r.path("params").path("message").has("taskId")) {
                resume = r;
            }
        }
        assertThat(resume).isNotNull();
        assertThat(resumeMentions(resume, "tool_not_declared"))
                .as("t-40b 按 tool_not_declared 拒绝回填").isTrue();
        assertThat(resumeMentions(resume, "call-40b")).as("t-40b 仍按协议回填其 toolCallId").isTrue();
        int executedA = 0;
        int executedB = 0;
        for (JsonNode h : result.path("handlerLog")) {
            if ("call-40a".equals(h.path("toolCallId").asText())) {
                executedA++;
            }
            if ("call-40b".equals(h.path("toolCallId").asText())) {
                executedB++;
            }
        }
        assertThat(executedA).as("t-40a 执行一次").isEqualTo(1);
        assertThat(executedB).as("新工具不进入在途调用，handler 不执行").isEqualTo(0);
    }

    @Test
    @Tag("story-feat-087-21")
    @Story("FEAT-087.F087-21: 工具注册与默认值")
    @DisplayName("F087-21 缺 toolId/kind 注册失败、同 toolId 覆盖、默认 inputSchema")
    void f08721RegistrationDefaultsAndOverride() {
        // 缺 toolId / 缺 kind：注册边界同步失败（A28）
        ObjectNode missingId = JSON.createObjectNode().put("kind", "OBSERVATION");
        ArrayNode ops = JSON.createArrayNode();
        ObjectNode reg = Feat087TsSdkHarness.op("register");
        reg.set("descriptor", missingId);
        ops.add(reg);
        ObjectNode missingKind = JSON.createObjectNode().put("toolId", "t-21x");
        ObjectNode regNoKind = Feat087TsSdkHarness.op("register");
        regNoKind.set("descriptor", missingKind);
        ops.add(regNoKind);
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(client("conv-21z"), ops));
        assertThat(Feat087TsSdkHarness.opResult(result, 0).path("error").path("code").asText())
                .as("缺 toolId 注册失败").isEqualTo("INVALID_ARGUMENT");
        assertThat(Feat087TsSdkHarness.opResult(result, 1).path("error").path("code").asText())
                .as("缺 kind 注册失败").isEqualTo("INVALID_ARGUMENT");

        // 正常注册 + 同 toolId 覆盖（后注册生效）
        ArrayNode tools = JSON.createArrayNode();
        tools.add(toolSpec("t-21", "echo"));
        ObjectNode override = JSON.createObjectNode();
        override.putObject("descriptor").put("toolId", "t-21").put("kind", "OBSERVATION");
        override.put("behavior", "ok");
        tools.add(override);
        ObjectNode cli = client("conv-21");
        cli.set("tools", tools);
        cli.putObject("exposure").put("type", "all");
        scriptToolRound("conv-21", "task-21", interruptFor("call-21", "t-21",
                JSON.createObjectNode().put("q", 1)), "done");
        result = harness.runDriver(Feat087TsSdkHarness.scenario(cli,
                JSON.createArrayNode().add(invokeOp("STREAMING", "go", "inv-21", "state:COMPLETED"))), 30_000);
        JsonNode create = harness.server().takeRequests().get(0);
        JsonNode clientTools = create.path("params").path("metadata").path("clientTools");
        assertThat(clientTools.size()).as("同 toolId 只有一个条目").isEqualTo(1);
        assertThat(clientTools.get(0).path("name").asText()).isEqualTo("t-21");
        JsonNode handlerLog = result.path("handlerLog");
        assertThat(handlerLog.size()).as("覆盖后只有新 handler 执行一次").isEqualTo(1);
    }

    @Test
    @Tag("story-feat-087-22")
    @Story("FEAT-087.F087-22: 默认不暴露与空清单拒绝")
    @DisplayName("F087-22 未配置策略时清单为空且请求回填 tool_not_declared")
    void f08722NoExposureEmptyToolViewRejectsRequests() {
        ArrayNode tools = JSON.createArrayNode();
        tools.add(toolSpec("t-22", "ok"));
        ObjectNode cli = client("conv-22");
        cli.set("tools", tools);
        scriptToolRound("conv-22", "task-22", interruptFor("call-22", "t-22", null), "done");
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(cli,
                JSON.createArrayNode().add(invokeOp("STREAMING", "go", "inv-22", "state:COMPLETED"))), 30_000);
        // 复用同一次 takeRequests() 的结果（再次取队列会因已清空而得 null，见 f08740 同类整改）
        java.util.List<ObjectNode> wire22 = harness.server().takeRequests();
        JsonNode create = wire22.get(0);
        assertThat(create.path("params").path("metadata").has("clientTools"))
                .as("未配置策略时创建 wire 无 clientTools").isFalse();
        assertThat(result.path("handlerLog").size()).as("handler 不执行").isZero();
        JsonNode resume = null;
        for (ObjectNode r : wire22) {
            if ("SendStreamingMessage".equals(r.path("method").asText())
                    && r.path("params").path("message").has("taskId")) {
                resume = r;
            }
        }
        assertThat(resumePartText(resume)).contains("tool_not_declared");
    }

    @Test
    @Tag("story-feat-087-23")
    @Story("FEAT-087.F087-23: 两级暴露策略组合与过期")
    @DisplayName("F087-23 两级策略取交集且过期后 context_expired")
    void f08723TwoLevelExposurePolicyIntersectionAndExpiry() {
        // 仅会话级 allow [t-23a]：清单只含 t-23a
        ArrayNode tools = JSON.createArrayNode();
        tools.add(toolSpec("t-23a", "ok"));
        tools.add(toolSpec("t-23b", "ok"));
        ObjectNode cli = client("conv-23a");
        cli.set("tools", tools);
        cli.putObject("exposure").put("type", "allow")
                .putArray("toolIds").add("t-23a");
        scriptToolRound("conv-23a", "task-23a", interruptFor("call-23a", "t-23a", null), "done");
        harness.runDriver(Feat087TsSdkHarness.scenario(cli,
                JSON.createArrayNode().add(invokeOp("STREAMING", "go", "inv-23a", "state:COMPLETED"))), 30_000);
        JsonNode create = harness.server().takeRequests().get(0);
        assertThat(create.path("params").path("metadata").path("clientTools").size()).isEqualTo(1);
        assertThat(create.path("params").path("metadata").path("clientTools").get(0).path("name").asText())
                .isEqualTo("t-23a");

        // 两级都配置：会话级 all + 调用级 allow [t-23b] → 交集 [t-23b]
        ObjectNode cli2 = client("conv-23b");
        cli2.set("tools", tools.deepCopy());
        cli2.putObject("exposure").put("type", "all");
        scriptToolRound("conv-23b", "task-23b", interruptFor("call-23b", "t-23b", null), "done");
        ObjectNode invoke = invokeOp("STREAMING", "go", "inv-23b", "state:COMPLETED");
        ObjectNode callPolicy = JSON.createObjectNode().put("type", "allow");
        callPolicy.putArray("toolIds").add("t-23b");
        invoke.set("toolExposure", callPolicy);
        harness.runDriver(Feat087TsSdkHarness.scenario(cli2, JSON.createArrayNode().add(invoke)), 30_000);
        create = harness.server().takeRequests().get(0);
        assertThat(create.path("params").path("metadata").path("clientTools").size()).isEqualTo(1);
        assertThat(create.path("params").path("metadata").path("clientTools").get(0).path("name").asText())
                .isEqualTo("t-23b");

        // 仅调用级（A30 四组合之一）：无会话级策略，调用级 allow [t-23b] → 清单 [t-23b]
        ObjectNode cli4 = client("conv-23d");
        cli4.set("tools", tools.deepCopy());
        scriptToolRound("conv-23d", "task-23d", interruptFor("call-23d", "t-23b", null), "done");
        ObjectNode invokeD = invokeOp("STREAMING", "go", "inv-23d", "state:COMPLETED");
        ObjectNode callOnly = JSON.createObjectNode().put("type", "allow");
        callOnly.putArray("toolIds").add("t-23b");
        invokeD.set("toolExposure", callOnly);
        harness.runDriver(Feat087TsSdkHarness.scenario(cli4, JSON.createArrayNode().add(invokeD)), 30_000);
        create = harness.server().takeRequests().get(0);
        assertThat(create.path("params").path("metadata").path("clientTools").size())
                .as("仅调用级时清单取该级").isEqualTo(1);
        assertThat(create.path("params").path("metadata").path("clientTools").get(0).path("name").asText())
                .isEqualTo("t-23b");

        // 会话级 allExcept [t-23a]：清单含 t-23b、不含 t-23a（A30 四种策略之一）
        ObjectNode cli5 = client("conv-23e");
        cli5.set("tools", tools.deepCopy());
        ObjectNode except = cli5.putObject("exposure").put("type", "allExcept");
        except.putArray("toolIds").add("t-23a");
        scriptToolRound("conv-23e", "task-23e", interruptFor("call-23e", "t-23b", null), "done");
        harness.runDriver(Feat087TsSdkHarness.scenario(cli5,
                JSON.createArrayNode().add(invokeOp("STREAMING", "go", "inv-23e", "state:COMPLETED"))), 30_000);
        create = harness.server().takeRequests().get(0);
        JsonNode names = create.path("params").path("metadata").path("clientTools");
        assertThat(names.size()).as("allExcept 排除后仅剩 t-23b").isEqualTo(1);
        assertThat(names.get(0).path("name").asText()).isEqualTo("t-23b");

        // 过期策略：请求回填 context_expired
        ObjectNode cli3 = client("conv-23c");
        cli3.set("tools", tools.deepCopy());
        ObjectNode expired = cli3.putObject("exposure").put("type", "allow");
        expired.putArray("toolIds").add("t-23a");
        expired.put("expiresAt", System.currentTimeMillis() - 1000);
        scriptToolRound("conv-23c", "task-23c", interruptFor("call-23c", "t-23a", null), "done");
        harness.runDriver(Feat087TsSdkHarness.scenario(cli3,
                JSON.createArrayNode().add(invokeOp("STREAMING", "go", "inv-23c", "state:COMPLETED"))), 30_000);
        JsonNode resume = lastResumeWire();
        assertThat(resumePartText(resume)).contains("context_expired");
    }

    @Test
    @Tag("story-feat-087-24")
    @Tag("smoke-feat-087")
    @Story("FEAT-087.F087-24: 创建上报与续接不重报")
    @DisplayName("F087-24 仅创建携带 clientTools，回填/补充输入/确认回答均不携带")
    void f08724ToolViewReportedOnCreateOnly() {
        ArrayNode tools = JSON.createArrayNode();
        tools.add(toolSpec("t-24", "ok"));
        ObjectNode cli = client("conv-24");
        cli.set("tools", tools);
        cli.putObject("exposure").put("type", "all");
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText())
                        && !b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.sse(List.of(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.statusUpdate("task-24", "conv-24", "TASK_STATE_INPUT_REQUIRED", "",
                                interruptFor("call-24", "t-24", null)))))));
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText())
                        && b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.sse(List.of(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.statusUpdate("task-24", "conv-24", "TASK_STATE_INPUT_REQUIRED", "",
                                Feat087Interrupts.items(Feat087Interrupts.userInput("ask-24", "继续"))))))));
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText())
                        && b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.sse(List.of(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.statusUpdate("task-24", "conv-24", "TASK_STATE_INPUT_REQUIRED", "",
                                Feat087Interrupts.items(Feat087Interrupts.confirmation("call-24c", "server-tool", "high", null))))))));
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText())
                        && b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.sse(List.of(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.statusUpdate("task-24", "conv-24", "TASK_STATE_COMPLETED", "ok", null))))));
        ArrayNode ops = JSON.createArrayNode();
        ObjectNode invoke = invokeOp("STREAMING", "go", "inv-24", "pending:user_input");
        ops.add(invoke);
        ops.add(Feat087TsSdkHarness.op("continueInput").put("input", "yes").put("await", "none"));
        ops.add(Feat087TsSdkHarness.op("waitEvent").put("type", "confirmation.required"));
        ops.add(Feat087TsSdkHarness.op("answer")
                .put("confirmationRef", "call-24c").put("round", 1).put("action", "allow"));
        ops.add(Feat087TsSdkHarness.op("waitEvent").put("type", "terminal"));
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(cli, ops), 45_000);
        assertThat(result.has("fatal")).isFalse();

        List<ObjectNode> wire = harness.server().takeRequests();
        assertThat(wire.size()).as("创建/回填/补充输入/确认回答四类请求").isGreaterThanOrEqualTo(4);
        JsonNode create = wire.get(0);
        assertThat(create.path("params").path("metadata").path("clientTools").size()).isEqualTo(1);
        for (int i = 1; i < wire.size(); i++) {
            assertThat(wire.get(i).path("params").path("metadata").has("clientTools"))
                    .as("续接请求不重报 clientTools（第 " + i + " 个）").isFalse();
        }
        // 确认回答 wire：占位 TextPart（动作字符串）+ guardrail_confirm DataPart（A31）
        JsonNode answerWire = null;
        for (ObjectNode r : wire) {
            JsonNode parts = r.path("params").path("message").path("parts");
            if (parts.size() >= 2 && parts.get(1).has("data")) {
                answerWire = r;
            }
        }
        assertThat(answerWire).as("确认回答请求存在").isNotNull();
        assertThat(answerWire.path("params").path("message").path("parts").get(0).path("text").asText())
                .isEqualTo("allow");
        assertThat(answerWire.path("params").path("message").path("parts").get(1)
                .path("data").path("guardrail_confirm").path("action").asText()).isEqualTo("allow");
    }

    @Test
    @Tag("story-feat-087-25")
    @Story("FEAT-087.F087-25: 请求校验与参数检查")
    @DisplayName("F087-25 未声明/未注册/缺必填拒绝，空参数按无必填通过")
    void f08725RequestValidationAndArgumentNormalization() {
        // (a) 请求未声明工具 → tool_not_declared
        ArrayNode tools = JSON.createArrayNode();
        tools.add(toolSpec("t-25", "ok"));
        ObjectNode cli = client("conv-25a");
        cli.set("tools", tools);
        cli.putObject("exposure").put("type", "allow").putArray("toolIds").add("t-25");
        scriptToolRound("conv-25a", "task-25a", interruptFor("call-25a", "other-tool", null), "done");
        harness.runDriver(Feat087TsSdkHarness.scenario(cli,
                JSON.createArrayNode().add(invokeOp("STREAMING", "go", "inv-25a", "state:COMPLETED"))), 30_000);
        assertThat(resumePartText(lastResumeWire())).contains("tool_not_declared");

        // (b) 已声明未注册 → tool_not_found：创建时 t-25 已注册并进入清单，
        // 请求到达前注销，工具轮经查询驱动（ASYNC），回填 wire 为 SendMessage
        ObjectNode cli2 = client("conv-25b");
        cli2.set("tools", JSON.createArrayNode().add(toolSpec("t-25", "ok")));
        cli2.putObject("exposure").put("type", "allow").putArray("toolIds").add("t-25");
        harness.server().onWhere(b -> "SendMessage".equals(b.path("method").asText())
                        && !b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.json(200, Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.task("task-25b", "conv-25b", "TASK_STATE_WORKING", "accepted", null))));
        harness.server().onWhere(b -> "GetTask".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.json(200, Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.task("task-25b", "conv-25b", "TASK_STATE_INPUT_REQUIRED", "",
                                interruptFor("call-25b", "t-25", null)))));
        harness.server().onWhere(b -> "SendMessage".equals(b.path("method").asText())
                        && b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.json(200, Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.task("task-25b", "conv-25b", "TASK_STATE_COMPLETED", "done", null))));
        ArrayNode ops25b = JSON.createArrayNode();
        ops25b.add(invokeOp("ASYNC", "go", "inv-25b", "accepted"));
        ObjectNode unreg = Feat087TsSdkHarness.op("unregister");
        unreg.put("toolId", "t-25");
        ops25b.add(unreg);
        ops25b.add(Feat087TsSdkHarness.op("getInvocation"));
        ObjectNode waitTerm25b = Feat087TsSdkHarness.op("waitEvent");
        waitTerm25b.put("type", "terminal");
        ops25b.add(waitTerm25b);
        harness.runDriver(Feat087TsSdkHarness.scenario(cli2, ops25b), 30_000);
        JsonNode backfill25b = null;
        for (ObjectNode r : harness.server().takeRequests()) {
            if ("SendMessage".equals(r.path("method").asText())
                    && r.path("params").path("message").has("taskId")) {
                backfill25b = r;
            }
        }
        assertThat(backfill25b).as("未注册请求按 tool_not_found 回填").isNotNull();
        assertThat(resumePartText(backfill25b)).contains("tool_not_found");

        // (c) 必填缺失 → invalid_tool_arguments 且 handler 不执行
        ObjectNode cli3 = client("conv-25c");
        ObjectNode spec = JSON.createObjectNode();
        ObjectNode descriptor = spec.putObject("descriptor");
        descriptor.put("toolId", "t-25").put("kind", "OBSERVATION");
        descriptor.putArray("required").add("city");
        spec.put("behavior", "ok");
        cli3.set("tools", JSON.createArrayNode().add(spec));
        cli3.putObject("exposure").put("type", "all");
        scriptToolRound("conv-25c", "task-25c", interruptFor("call-25c", "t-25",
                JSON.createObjectNode().put("q", 1)), "done");
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(cli3,
                JSON.createArrayNode().add(invokeOp("STREAMING", "go", "inv-25c", "state:COMPLETED"))), 30_000);
        assertThat(resumePartText(lastResumeWire())).contains("invalid_tool_arguments");
        assertThat(result.path("handlerLog").size()).isZero();

        // (d) 参数缺失 / 数组参数归一化 {} → 无必填参数可通过检查并执行
        ObjectNode cli4 = client("conv-25d");
        cli4.set("tools", JSON.createArrayNode().add(toolSpec("t-25", "echo")));
        cli4.putObject("exposure").put("type", "all");
        ObjectNode arrayArgs = JSON.createObjectNode();
        ObjectNode context = arrayArgs.putObject("context");
        context.put("_interrupt_kind", "client_tool");
        context.putArray("arguments");
        ObjectNode noArgs = JSON.createObjectNode();
        noArgs.put("toolCallId", "call-25d").put("toolName", "t-25");
        noArgs.set("context", context);
        scriptToolRound("conv-25d", "task-25d", Feat087Interrupts.single(noArgs), "done");
        harness.runDriver(Feat087TsSdkHarness.scenario(cli4,
                JSON.createArrayNode().add(invokeOp("STREAMING", "go", "inv-25d", "state:COMPLETED"))), 30_000);
        JsonNode resume = lastResumeWire();
        assertThat(resumePartText(resume)).as("数组参数归一化空对象后执行").contains("\"status\":\"ok\"");
    }

    @Test
    @Tag("story-feat-087-26")
    @Story("FEAT-087.F087-26: 治理回调与审批")
    @DisplayName("F087-26 无回调自动执行、审批拒绝不执行、权限拒绝带自定义码")
    void f08726GovernanceCallbacksAndApproval() {
        ArrayNode tools = JSON.createArrayNode();
        ObjectNode obs = toolSpec("t-26-obs", "ok");
        ObjectNode act = JSON.createObjectNode();
        act.putObject("descriptor").put("toolId", "t-26-act").put("kind", "ACTION");
        act.put("behavior", "ok");
        tools.add(obs);
        tools.add(act);
        JsonNode interrupt = Feat087Interrupts.items(
                Feat087Interrupts.clientTool("call-26o", "t-26-obs", null, true),
                Feat087Interrupts.clientTool("call-26a", "t-26-act", null, true));

        // 无回调：两者都执行
        ObjectNode cli = client("conv-26a");
        cli.set("tools", tools.deepCopy());
        cli.putObject("exposure").put("type", "all");
        scriptToolRound("conv-26a", "task-26a", interrupt.deepCopy(), "done");
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(cli,
                JSON.createArrayNode().add(invokeOp("STREAMING", "go", "inv-26a", "state:COMPLETED"))), 30_000);
        assertThat(result.path("handlerLog").size()).as("未配置治理回调时全部执行").isEqualTo(2);

        // 审批拒绝：Action 不执行，回填 rejected
        ObjectNode cli2 = client("conv-26b");
        cli2.set("tools", tools.deepCopy());
        cli2.putObject("exposure").put("type", "all");
        cli2.putObject("governance").put("approvalProvider", "deny");
        scriptToolRound("conv-26b", "task-26b", interrupt.deepCopy(), "done");
        result = harness.runDriver(Feat087TsSdkHarness.scenario(cli2,
                JSON.createArrayNode().add(invokeOp("STREAMING", "go", "inv-26b", "state:COMPLETED"))), 30_000);
        assertThat(result.path("handlerLog").size()).as("Action 被审批拒绝不执行").isEqualTo(1);
        JsonNode resume = lastResumeWire();
        assertThat(resumeMentions(resume, "rejected")).as("Action 审批拒绝回填 rejected").isTrue();

        // 权限拒绝（自定义码）：不执行
        ObjectNode cli3 = client("conv-26c");
        cli3.set("tools", tools.deepCopy());
        cli3.putObject("exposure").put("type", "all");
        cli3.putObject("governance").put("policyGuard", "deny");
        scriptToolRound("conv-26c", "task-26c", interrupt.deepCopy(), "done");
        result = harness.runDriver(Feat087TsSdkHarness.scenario(cli3,
                JSON.createArrayNode().add(invokeOp("STREAMING", "go", "inv-26c", "state:COMPLETED"))), 30_000);
        assertThat(result.path("handlerLog").size()).isZero();
        assertThat(resumePartText(lastResumeWire())).as("权限拒绝使用自定义 errorCode").contains("custom_denied");
    }

    @Test
    @Tag("story-feat-087-27")
    @Story("FEAT-087.F087-27: 同步/异步 handler 与执行超时")
    @DisplayName("F087-27 异步到期即判、同步返回后判、迟到结果被忽略")
    void f08727HandlerTimeoutAsyncImmediateAndSyncAfterReturn() {
        ArrayNode tools = JSON.createArrayNode();
        tools.add(timeoutTool("t-27-hang", "hang-async", 1000));
        tools.add(timeoutTool("t-27-slow", "sync-slow", 1000));
        tools.add(toolSpec("t-27-ok", "ok"));
        ObjectNode throwing = toolSpec("t-27-err", "throw");
        tools.add(throwing);
        JsonNode interrupt = Feat087Interrupts.items(
                Feat087Interrupts.clientTool("call-h", "t-27-hang", null, true),
                Feat087Interrupts.clientTool("call-ok", "t-27-ok", null, true),
                Feat087Interrupts.clientTool("call-err", "t-27-err", null, true),
                Feat087Interrupts.clientTool("call-slow", "t-27-slow", null, true));
        ObjectNode cli = client("conv-27");
        cli.set("tools", tools);
        cli.putObject("exposure").put("type", "all");
        scriptToolRound("conv-27", "task-27", interrupt, "done");
        ArrayNode ops = JSON.createArrayNode();
        ops.add(invokeOp("STREAMING", "go", "inv-27", "state:COMPLETED"));
        ops.add(Feat087TsSdkHarness.op("toolRecords"));
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(cli, ops), 60_000);
        JsonNode records = Feat087TsSdkHarness.opResult(result, 1).path("records");
        assertThat(records.isArray()).isTrue();
        for (JsonNode rec : records) {
            switch (rec.path("toolCallId").asText()) {
                case "call-h" -> {
                    assertThat(rec.path("status").asText()).isEqualTo("TIMEOUT");
                    assertThat(rec.path("errorCode").asText()).isEqualTo("timeout");
                    // 挂起 handler 被 abort 后拒绝：迟到完成记为 ERROR，不改写超时结果（L2 §3.5）
                    assertThat(rec.path("lateCompletion").asText()).isEqualTo("ERROR");
                }
                case "call-slow" -> {
                    // 同步 handler 越过期限后返回：按实际经过时间记 TIMEOUT（FEAT §5.3）
                    assertThat(rec.path("status").asText()).isEqualTo("TIMEOUT");
                    assertThat(rec.path("errorCode").asText()).isEqualTo("timeout");
                }
                case "call-ok" -> assertThat(rec.path("status").asText()).isEqualTo("OK");
                case "call-err" -> {
                    assertThat(rec.path("status").asText()).isEqualTo("ERROR");
                    assertThat(rec.path("errorCode").asText()).isEqualTo("tool_execution_error");
                }
                default -> { }
            }
        }
    }

    private ObjectNode timeoutTool(String toolId, String behavior, int timeoutMs) {
        ObjectNode spec = JSON.createObjectNode();
        ObjectNode descriptor = spec.putObject("descriptor");
        descriptor.put("toolId", toolId).put("kind", "OBSERVATION").put("timeoutMs", timeoutMs);
        spec.put("behavior", behavior);
        return spec;
    }

    @Test
    @Tag("story-feat-087-28")
    @Tag("smoke-feat-087")
    @Story("FEAT-087.F087-28: 一轮串行执行一次批量回填")
    @DisplayName("F087-28 串行执行且全部就绪后一次提交两个结果")
    void f08728SerialExecutionAndSingleBatchedResume() {
        ArrayNode tools = JSON.createArrayNode();
        tools.add(toolSpec("t-28", "ok"));
        ObjectNode throwing = toolSpec("t-28-err", "throw");
        tools.add(throwing);
        JsonNode interrupt = Feat087Interrupts.items(
                Feat087Interrupts.clientTool("call-28a", "t-28", null, true),
                Feat087Interrupts.clientTool("call-28b", "t-28-err", null, true));
        ObjectNode cli = client("conv-28");
        cli.set("tools", tools);
        cli.putObject("exposure").put("type", "all");
        scriptToolRound("conv-28", "task-28", interrupt, "done");
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(cli,
                JSON.createArrayNode().add(invokeOp("STREAMING", "go", "inv-28", "state:COMPLETED"))), 30_000);
        JsonNode handlerLog = result.path("handlerLog");
        assertThat(handlerLog.size()).isEqualTo(2);
        long end0 = handlerLog.get(0).path("end").asLong();
        long start1 = handlerLog.get(1).path("start").asLong();
        assertThat(start1).as("串行：第二项在第一项结束后开始").isGreaterThanOrEqualTo(end0);
        JsonNode resume = lastResumeWire();
        assertThat(resume.path("params").path("message").path("parts").size())
                .as("一次批量回填两个结果").isEqualTo(2);
        assertThat(resumeMentions(resume, "call-28a")).isTrue();
        assertThat(resumeMentions(resume, "call-28b")).as("失败项也随批提交").isTrue();
    }

    @Test
    @Tag("story-feat-087-30")
    @Story("FEAT-087.F087-30: 终态停止回填与执行记录")
    @DisplayName("F087-30 服务端拒绝回填映射 CONTINUATION_REJECTED 且记录可读")
    void f08730TerminalStopAndToolRecordsReadable() {
        ArrayNode tools = JSON.createArrayNode();
        tools.add(toolSpec("t-30", "ok"));
        ObjectNode cli = client("conv-30");
        cli.set("tools", tools);
        cli.putObject("exposure").put("type", "all");
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText())
                        && !b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.sse(List.of(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.statusUpdate("task-30", "conv-30", "TASK_STATE_INPUT_REQUIRED", "",
                                interruptFor("call-30", "t-30", null)))))));
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText())
                        && b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.json(200, Feat087MockA2aServer.jsonRpcError(-32051, "continuation failed", "CONTINUATION_FAILED")));
        ArrayNode ops = JSON.createArrayNode();
        // await diagnostic：等待回填被拒诊断落地后再读执行记录（await none 会在轮次结算前读取）
        ops.add(invokeOp("STREAMING", "go", "inv-30", "event:diagnostic"));
        ops.add(Feat087TsSdkHarness.op("toolRecords"));
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(cli, ops), 30_000);
        assertThat(Feat087TsSdkHarness.events(result, "diagnostic").toString()).contains("CONTINUATION_REJECTED");
        JsonNode records = Feat087TsSdkHarness.opResult(result, 1).path("records");
        assertThat(records.size()).isGreaterThanOrEqualTo(1);
        JsonNode record = records.get(0);
        assertThat(record.path("status").asText()).isEqualTo("OK");
        assertThat(record.path("resultSummary").asText().length()).isLessThanOrEqualTo(256);
    }

    @Test
    @Tag("story-feat-087-31")
    @Story("FEAT-087.F087-31: 缺种类待办处理")
    @DisplayName("F087-31 带工具名无种类按本地工具，缺 toolName 按用户输入等待")
    void f08731MissingKindPendingItems() {
        ArrayNode tools = JSON.createArrayNode();
        tools.add(toolSpec("t-31", "ok"));
        ObjectNode noKind = JSON.createObjectNode();
        noKind.put("toolCallId", "call-31a").put("toolName", "t-31");
        ObjectNode noName = JSON.createObjectNode();
        noName.put("toolCallId", "call-31b");
        noName.put("message", "需要补充信息");
        ObjectNode cli = client("conv-31");
        cli.set("tools", tools);
        cli.putObject("exposure").put("type", "all");

        // 变体一（独立轮次）：带工具名、无种类 → 按 client_tool 处理并执行（A52，与 Java 一致）。
        // 不与变体二同轮注入：无种类项归类 client_tool、缺 toolName 项按用户输入开放，
        // 混在一轮会命中同轮不混合种类前提（L2 D2 MIXED_PENDING_KINDS）而非 A52 目标路径。
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText())
                        && !b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.sse(List.of(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.statusUpdate("task-31a", "conv-31a", "TASK_STATE_INPUT_REQUIRED", "",
                                Feat087Interrupts.single(noKind)))))));
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText())
                        && b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.sse(List.of(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.statusUpdate("task-31a", "conv-31a", "TASK_STATE_COMPLETED", "done", null))))));
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(cli,
                JSON.createArrayNode().add(invokeOp("STREAMING", "go", "inv-31a", "state:COMPLETED"))), 30_000);
        assertThat(result.path("handlerLog").size()).as("缺种类带工具名按本地工具执行").isEqualTo(1);

        // 变体二（独立轮次）：缺 toolName → 不执行，按用户输入等待开放并诊断
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText())
                        && !b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.sse(List.of(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.statusUpdate("task-31b", "conv-31b", "TASK_STATE_INPUT_REQUIRED", "",
                                Feat087Interrupts.single(noName)))))));
        result = harness.runDriver(Feat087TsSdkHarness.scenario(client("conv-31b"),
                JSON.createArrayNode().add(invokeOp("STREAMING", "go", "inv-31b", "event:input.required"))), 30_000);
        assertThat(result.path("handlerLog").size()).as("缺 toolName 不执行").isZero();
        assertThat(Feat087TsSdkHarness.events(result, "input.required").size())
                .as("缺 toolName 项按用户输入等待开放").isEqualTo(1);
        assertThat(Feat087TsSdkHarness.events(result, "diagnostic").toString())
                .contains("INPUT_RESUME_TARGET_MISSING");
    }

    @Test
    @Tag("story-feat-087-29")
    @Story("FEAT-087.F087-29: 不重复执行与回填结果不明")
    @DisplayName("F087-29 回填超时后核对重发一次且 handler 不重复执行")
    void f08729NoRepeatExecutionAndSubmissionUnknownResend() {
        ArrayNode tools = JSON.createArrayNode();
        tools.add(toolSpec("t-29", "ok"));
        ObjectNode cli = client("conv-29");
        cli.set("tools", tools);
        cli.putObject("exposure").put("type", "all");
        cli.putObject("timeouts").put("unaryMs", 600);
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText())
                        && !b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.sse(List.of(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.statusUpdate("task-29", "conv-29", "TASK_STATE_INPUT_REQUIRED", "",
                                interruptFor("call-29", "t-29", null)))))));
        // 第一次回填挂起（结果不明）
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText())
                        && b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.hangThen(5000, Feat087MockA2aServer.sse(List.of())));
        // 核对：Task 仍停在原待办
        harness.server().onWhere(b -> "GetTask".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.json(200, Feat087MockA2aServer.rpcResult(Feat087MockA2aServer.task("task-29", "conv-29",
                        "TASK_STATE_INPUT_REQUIRED", "", interruptFor("call-29", "t-29", null)))));
        // 重发（原报文）后完成
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText())
                        && b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.sse(List.of(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.statusUpdate("task-29", "conv-29", "TASK_STATE_COMPLETED", "ok", null))))));
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(cli,
                JSON.createArrayNode().add(invokeOp("STREAMING", "go", "inv-29", "state:COMPLETED"))), 30_000);
        assertThat(result.path("handlerLog").size()).as("handler 只执行一次").isEqualTo(1);
        java.util.List<ObjectNode> wire = harness.server().takeRequests();
        java.util.Set<String> resumeIds = new java.util.HashSet<>();
        int resumes = 0;
        for (ObjectNode r : wire) {
            if ("SendStreamingMessage".equals(r.path("method").asText())
                    && r.path("params").path("message").has("taskId")) {
                resumes++;
                resumeIds.add(r.path("params").path("message").path("messageId").asText());
            }
        }
        assertThat(resumes).as("回填最多自动重发一次（共两次提交）").isEqualTo(2);
        assertThat(resumeIds).as("重发沿用原 messageId").hasSize(1);
    }
}

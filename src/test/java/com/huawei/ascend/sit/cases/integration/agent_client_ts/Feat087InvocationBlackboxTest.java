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
 * FEAT-087 标准调用面：两类入口 × 三种模式、实例约束、上下文透传、
 * 查询忙碌、用户补充输入与续接校验。对应 F087-03 … F087-12。
 */
@Tag("integration")
@Tag("agent-client-ts")
@Tag("feat-087")
@Feature("FEAT-087: Agent-Client支持Node服务TS语言")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Feat087InvocationBlackboxTest {

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

    private ObjectNode client(String kind, String conversationId) {
        return Feat087TsSdkHarness.clientSpec(kind, harness.server().url(), conversationId);
    }

    private void sseCompleted(String method, String taskId, String convId) {
        harness.server().onWhere(b -> method.equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.sse(List.of(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.statusUpdate(taskId, convId, "TASK_STATE_COMPLETED", "done", null))))));
    }

    @Test
    @Tag("story-feat-087-03")
    @Tag("smoke-feat-087")
    @Story("FEAT-087.F087-03: 两类入口 × 三种模式创建与查询")
    @DisplayName("F087-03 Gateway 上报 agentId、Runtime 不上报且 ASYNC 查询回显")
    void f08703BothEntryKindsAllThreeModes() {
        for (String kind : new String[] {"GATEWAY", "RUNTIME"}) {
            String conv = "conv-03-" + kind;
            // STREAMING
            sseCompleted("SendStreamingMessage", "task-" + kind + "-s", conv);
            ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(client(kind, conv),
                    JSON.createArrayNode().add(invokeOp("STREAMING", "hi", "inv-s", "state:COMPLETED"))));
            JsonNode create = takeFirstRequest();
            assertThat(create.path("method").asText()).isEqualTo("SendStreamingMessage");
            assertThat(create.path("params").path("metadata").has("agentId"))
                    .as(kind + " 创建 wire agentId 存在性").isEqualTo("GATEWAY".equals(kind));

            // BLOCKING
            harness.server().onWhere(b -> "SendMessage".equals(b.path("method").asText()), r ->
                    Feat087MockA2aServer.json(200, Feat087MockA2aServer.rpcResult(
                            Feat087MockA2aServer.task("task-" + kind + "-b", conv, "TASK_STATE_COMPLETED", "done", null))));
            result = harness.runDriver(Feat087TsSdkHarness.scenario(client(kind, conv),
                    JSON.createArrayNode().add(invokeOp("BLOCKING", "hi", "inv-b", "state:COMPLETED"))));
            // 快照 taskState 为归一化枚举（L2 §2.5），wire 才是 TASK_STATE_*
            assertThat(Feat087TsSdkHarness.opResult(result, 0).path("snapshot").path("taskState").asText())
                    .isEqualTo("COMPLETED");

            // ASYNC：受理后查询
            harness.server().onWhere(b -> "SendMessage".equals(b.path("method").asText()), r ->
                    Feat087MockA2aServer.json(200, Feat087MockA2aServer.rpcResult(
                            Feat087MockA2aServer.task("task-" + kind + "-a", conv, "TASK_STATE_WORKING", "", null))));
            harness.server().onWhere(b -> "GetTask".equals(b.path("method").asText()), r ->
                    Feat087MockA2aServer.json(200, Feat087MockA2aServer.rpcResult(
                            Feat087MockA2aServer.task("task-" + kind + "-a", conv, "TASK_STATE_WORKING", "", null))));
            ArrayNode ops = JSON.createArrayNode();
            ops.add(invokeOp("ASYNC", "hi", "inv-a", "result"));
            ops.add(Feat087TsSdkHarness.op("getInvocation"));
            result = harness.runDriver(Feat087TsSdkHarness.scenario(client(kind, conv), ops));
            JsonNode snapshot = Feat087TsSdkHarness.opResult(result, 1).path("snapshot");
            assertThat(snapshot.path("taskState").asText()).isEqualTo("WORKING");
            assertThat(snapshot.path("invocationRef").asText()).isEqualTo("inv-a");
            assertThat(snapshot.path("observation").asText()).isEqualTo("IDLE");
            int getTasks = countMethod("GetTask");
            assertThat(getTasks).as("ASYNC 仅显式查询一次，无后台轮询").isEqualTo(1);
        }
    }

    private JsonNode takeFirstRequest() {
        List<ObjectNode> all = harness.server().takeRequests();
        assertThat(all).isNotEmpty();
        return all.get(all.size() - 1);
    }

    private int countMethod(String method) {
        int n = 0;
        for (ObjectNode r : harness.server().takeRequests()) {
            if (method.equals(r.path("method").asText())) {
                n++;
            }
        }
        return n;
    }

    @Test
    @Tag("story-feat-087-04")
    @Story("FEAT-087.F087-04: 实例约束（单会话 / 单活动调用 / 单请求在途）")
    @DisplayName("F087-04 缺 conversationId 失败、第二调用 CLIENT_BUSY、在途不超过 1")
    void f08704InstanceConstraintsSingleCallSingleInflight() {
        // 缺 conversationId：createAgentClient 同步抛 INVALID_CONFIG（L2 §6.3 配置校验）
        ObjectNode options = JSON.createObjectNode();
        options.put("kind", "GATEWAY").put("url", harness.server().url());
        ObjectNode createOp = Feat087TsSdkHarness.op("createClient");
        createOp.set("options", options);
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario((ObjectNode) null,
                JSON.createArrayNode().add(createOp)));
        assertThat(Feat087TsSdkHarness.opResult(result, 0).path("error").path("code").asText())
                .as("缺 conversationId 属创建配置错误")
                .isEqualTo("INVALID_CONFIG");

        // 活动调用未终态时第二次 invoke：CLIENT_BUSY
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.hangThen(8000, Feat087MockA2aServer.sse(List.of())));
        ArrayNode ops = JSON.createArrayNode();
        ops.add(invokeOp("STREAMING", "first", "inv-04b", "none"));
        ObjectNode second = invokeOp("STREAMING", "second", "inv-04b2", "none");
        ops.add(second);
        result = harness.runDriver(Feat087TsSdkHarness.scenario(client("GATEWAY", "conv-04b"), ops));
        assertThat(Feat087TsSdkHarness.opResult(result, 1).path("error").path("code").asText())
                .isEqualTo("CLIENT_BUSY");
        // 挂起窗口内只有一个请求在途
        assertThat(harness.server().maxInflight()).isEqualTo(1);
    }

    @Test
    @Tag("story-feat-087-05")
    @Story("FEAT-087.F087-05: 上下文透传与结果回显")
    @DisplayName("F087-05 traceId/correlationId/自定义属性上 wire 且每请求取当前凭证")
    void f08705ContextForwardedAndSnapshotEchoesIdentity() {
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.sse(List.of(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.statusUpdate("task-05", "conv-05", "TASK_STATE_COMPLETED", "ok", null))))));
        ObjectNode cli = client("GATEWAY", "conv-05");
        cli.put("token", "tok-current");
        ObjectNode invoke = invokeOp("STREAMING", "hi", "inv-05", "state:COMPLETED");
        invoke.put("traceId", "t-087");
        invoke.put("correlationId", "c-087");
        ObjectNode attrs = invoke.putObject("attributes");
        attrs.put("bizKey", "bizValue");
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(cli,
                JSON.createArrayNode().add(invoke)));
        JsonNode wire = harness.server().takeRequests().get(0);
        JsonNode attributes = wire.path("params").path("metadata").path("attributes");
        assertThat(attributes.path("traceId").asText()).isEqualTo("t-087");
        assertThat(attributes.path("correlationId").asText()).isEqualTo("c-087");
        assertThat(attributes.path("bizKey").asText()).isEqualTo("bizValue");
        assertThat(wire.path("params").path("message").path("contextId").asText()).isEqualTo("conv-05");
        assertThat(wire.path("__authorization").asText()).isEqualTo("Bearer tok-current");
        JsonNode snapshot = Feat087TsSdkHarness.opResult(result, 0).path("snapshot");
        assertThat(snapshot.path("conversationId").asText()).isEqualTo("conv-05");
        assertThat(snapshot.path("invocationRef").asText()).isEqualTo("inv-05");
        assertThat(snapshot.path("mode").asText()).isEqualTo("STREAMING");
    }

    @Test
    @Tag("story-feat-087-08")
    @Story("FEAT-087.F087-08: 入口不支持流式不静默降级")
    @DisplayName("F087-08 STREAM_NOT_AVAILABLE 报 STREAMING_NOT_SUPPORTED 且无非流式重发")
    void f08708StreamingUnsupportedDoesNotSilentlyDowngrade() {
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.json(200, Feat087MockA2aServer.jsonRpcError(-32052, "stream not available", "STREAM_NOT_AVAILABLE")));
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(client("GATEWAY", "conv-08"),
                JSON.createArrayNode().add(invokeOp("STREAMING", "hi", "inv-08", "result"))));
        JsonNode snapshot = Feat087TsSdkHarness.opResult(result, 0).path("snapshot");
        assertThat(snapshot.path("error").path("code").asText()).isEqualTo("STREAMING_NOT_SUPPORTED");
        assertThat(snapshot.path("observation").asText()).isEqualTo("INTERRUPTED");
        int sendMessage = 0;
        for (ObjectNode r : harness.server().takeRequests()) {
            if ("SendMessage".equals(r.path("method").asText())) {
                sendMessage++;
            }
        }
        assertThat(sendMessage).as("无非流式重发").isZero();
    }

    @Test
    @Tag("story-feat-087-06")
    @Story("FEAT-087.F087-06: BLOCKING 有界查询与观察窗口")
    @DisplayName("F087-06 观察窗口耗尽返回 BLOCKING_WINDOW_ENDED 且 Task 不被改失败")
    void f08706BlockingBoundedQueryWindowEnds() {
        harness.server().onWhere(b -> "SendMessage".equals(b.path("method").asText())
                        && !b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.json(200, Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.task("task-06", "conv-06", "TASK_STATE_WORKING", "", null))));
        harness.server().onEvery(b -> "GetTask".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.json(200, Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.task("task-06", "conv-06", "TASK_STATE_WORKING", "", null))));
        ObjectNode cli = client("GATEWAY", "conv-06");
        ObjectNode timeouts = cli.putObject("timeouts");
        timeouts.put("blockingObservationMs", 900);
        ObjectNode retry = cli.putObject("retry");
        retry.put("initialDelayMs", 100).put("maxDelayMs", 100);
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(cli,
                JSON.createArrayNode().add(invokeOp("BLOCKING", "hi", "inv-06", "result"))), 30_000);
        JsonNode snapshot = Feat087TsSdkHarness.opResult(result, 0).path("snapshot");
        assertThat(snapshot.path("observation").asText()).isEqualTo("ENDED");
        assertThat(snapshot.path("error").path("code").asText()).isEqualTo("BLOCKING_WINDOW_ENDED");
        assertThat(snapshot.path("taskState").asText()).isEqualTo("WORKING");
        assertThat(countMethod("GetTask")).as("窗口内多次 GetTask 轮询").isGreaterThanOrEqualTo(2);
    }

    @Test
    @Tag("story-feat-087-07")
    @Story("FEAT-087.F087-07: STREAMING 事件投递与轮末待办处理")
    @DisplayName("F087-07 output/status/input.required 事件按序投递且待办在 EOF 后处理")
    void f08707StreamingEventsDeliveredAndTodoAfterEof() {
        ObjectNode interrupt = Feat087Interrupts.items(
                Feat087Interrupts.userInput("ask-07", "继续吗"));
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.sse(List.of(
                        Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                                Feat087MockA2aServer.statusUpdate("task-07", "conv-07", "TASK_STATE_WORKING", "partial", null))),
                        Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                                Feat087MockA2aServer.statusUpdate("task-07", "conv-07", "TASK_STATE_INPUT_REQUIRED", "", interrupt))))));
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText())
                        && b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.sse(List.of(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.statusUpdate("task-07", "conv-07", "TASK_STATE_COMPLETED", "final", null))))));
        ArrayNode ops = JSON.createArrayNode();
        ObjectNode invoke = invokeOp("STREAMING", "go", "inv-07", "event:input.required");
        ops.add(invoke);
        ObjectNode cont = Feat087TsSdkHarness.op("continueInput");
        cont.put("input", "yes");
        ops.add(cont);
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(client("GATEWAY", "conv-07"), ops));
        // 事件顺序：status(output 文本) → input.required，均在 EOF 前投递；回填在事件之后
        java.util.List<String> types = new java.util.ArrayList<>();
        for (JsonNode ev : result.path("events")) {
            String t = ev.path("type").asText();
            if (java.util.List.of("status", "output", "input.required", "terminal").contains(t)) {
                types.add(t);
            }
        }
        assertThat(types).contains("input.required");
        assertThat(types.indexOf("input.required")).isGreaterThan(types.indexOf("status"));
        List<ObjectNode> wire = harness.server().takeRequests();
        JsonNode resume = null;
        for (ObjectNode r : wire) {
            if (r.path("params").path("message").has("taskId")) {
                resume = r;
            }
        }
        assertThat(resume).as("存在续接请求").isNotNull();
        assertThat(resume.path("params").path("message").path("taskId").asText()).isEqualTo("task-07");
    }

    @Test
    @Tag("story-feat-087-09")
    @Story("FEAT-087.F087-09: ASYNC 受理即结算与查询驱动回填")
    @DisplayName("F087-09 ASYNC result 立即结算且查询发现工具待办后自动回填")
    void f08709AsyncResultSettlesOnAcceptanceAndQueryDrivesTools() {
        harness.server().onWhere(b -> "SendMessage".equals(b.path("method").asText())
                        && !b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.json(200, Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.task("task-09", "conv-09", "TASK_STATE_SUBMITTED", "", null))));
        harness.server().onWhere(b -> "GetTask".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.json(200, Feat087MockA2aServer.rpcResult(Feat087MockA2aServer.task("task-09", "conv-09",
                        "TASK_STATE_INPUT_REQUIRED", "", Feat087Interrupts.single(
                                Feat087Interrupts.clientTool("call-09", "tool-09",
                                        JSON.createObjectNode().put("city", "SZ"), true))))));
        harness.server().onWhere(b -> "SendMessage".equals(b.path("method").asText())
                        && b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.json(200, Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.task("task-09", "conv-09", "TASK_STATE_COMPLETED", "ok", null))));
        ObjectNode cli = client("GATEWAY", "conv-09");
        ObjectNode invoke = invokeOp("ASYNC", "hi", "inv-09", "result");
        invoke.put("toolExposure", JSON.createObjectNode().put("type", "all"));
        ArrayNode ops = JSON.createArrayNode();
        ops.add(invoke);
        ObjectNode reg = Feat087TsSdkHarness.op("register");
        ObjectNode descriptor = reg.putObject("descriptor");
        descriptor.put("toolId", "tool-09").put("kind", "OBSERVATION");
        reg.put("behavior", "ok");
        ops.add(reg);
        ops.add(Feat087TsSdkHarness.op("getInvocation"));
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(cli, ops));
        // ASYNC 受理即结算：result 已兑现且 observation=IDLE
        assertThat(Feat087TsSdkHarness.opResult(result, 0).path("snapshot").path("observation").asText())
                .isEqualTo("IDLE");
        java.util.List<ObjectNode> wire = harness.server().takeRequests();
        JsonNode resume = null;
        for (ObjectNode r : wire) {
            if (r.path("params").path("message").has("taskId")
                    && r.path("params").path("message").path("parts").size() > 0) {
                resume = r;
            }
        }
        assertThat(resume).as("查询后自动回填工具结果").isNotNull();
        assertThat(resume.path("params").path("message").path("parts").get(0)
                .path("metadata").path("toolCallId").asText()).isEqualTo("call-09");
        assertThat(resume.path("params").path("metadata").has("clientTools")).isFalse();
    }

    @Test
    @Tag("story-feat-087-10")
    @Story("FEAT-087.F087-10: 查询忙碌与无 Task 关联")
    @DisplayName("F087-10 读流与审批等待中查询立即 CLIENT_BUSY，无关联返回 NO_TASK_ASSOCIATION")
    void f08710BusyQueryAndNoTaskAssociation() {
        // 读流期间 getInvocation：立即 CLIENT_BUSY，不发 GetTask
        // （create 受理后 SSE 保持打开、Task 已知，SDK 处于观察中 → 查询本地拒绝）
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.sse(List.of(Feat087MockA2aServer.sseOf(
                                Feat087MockA2aServer.statusUpdate("task-10a", "conv-10a", "TASK_STATE_WORKING", "", null))))
                        .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.KEEP_OPEN));
        ArrayNode ops = JSON.createArrayNode();
        // await accepted：确保查询发生在受理之后（读流期间），否则 taskId 未知属查询前置竞态
        ops.add(invokeOp("STREAMING", "hi", "inv-10a", "accepted"));
        ops.add(Feat087TsSdkHarness.op("getInvocation"));
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(client("GATEWAY", "conv-10a"), ops));
        assertThat(Feat087TsSdkHarness.opResult(result, 1).path("error").path("code").asText())
                .isEqualTo("CLIENT_BUSY");
        int getTasks = 0;
        for (ObjectNode r : harness.server().takeRequests()) {
            if ("GetTask".equals(r.path("method").asText())) {
                getTasks++;
            }
        }
        assertThat(getTasks).as("忙碌时不发查询请求").isZero();

        // 无 Task 关联（创建即失败）后查询
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.json(400, JSON.createObjectNode().put("code", "VALIDATION_FAILED")));
        ops = JSON.createArrayNode();
        ops.add(invokeOp("STREAMING", "hi", "inv-10c", "result"));
        ops.add(Feat087TsSdkHarness.op("getInvocation"));
        result = harness.runDriver(Feat087TsSdkHarness.scenario(client("GATEWAY", "conv-10c"), ops));
        assertThat(Feat087TsSdkHarness.opResult(result, 1).path("error").path("code").asText())
                .isEqualTo("NO_TASK_ASSOCIATION");
    }

    @Test
    @Tag("story-feat-087-11")
    @Story("FEAT-087.F087-11: 用户补充输入单点与多等待点")
    @DisplayName("F087-11 多等待点 answers 定向且集合完全相等，缺项本地拒绝")
    void f08711UserInputSingleAndMultiplePendingPoints() {
        // 多等待点：全量回答 → 每个续接 TextPart 带 toolCallId
        ObjectNode interrupt = Feat087Interrupts.items(
                Feat087Interrupts.userInput("ask-date", "日期"), Feat087Interrupts.userInput("ask-people", "人数"));
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText())
                        && !b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.sse(List.of(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.statusUpdate("task-11", "conv-11", "TASK_STATE_INPUT_REQUIRED", "", interrupt))))));
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText())
                        && b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.sse(List.of(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.statusUpdate("task-11", "conv-11", "TASK_STATE_COMPLETED", "ok", null))))));
        ArrayNode ops = JSON.createArrayNode();
        ops.add(invokeOp("STREAMING", "go", "inv-11", "pending:user_input"));
        ObjectNode cont = Feat087TsSdkHarness.op("continueInput");
        ObjectNode answers = cont.putObject("answers");
        answers.put("ask-date", "明天");
        answers.put("ask-people", "两人");
        ops.add(cont);
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(client("GATEWAY", "conv-11"), ops));
        assertThat(result.has("fatal")).isFalse();
        JsonNode resume = null;
        for (ObjectNode r : harness.server().takeRequests()) {
            if (r.path("params").path("message").has("taskId")) {
                resume = r;
            }
        }
        assertThat(resume).isNotNull();
        assertThat(resume.path("params").path("message").path("taskId").asText())
                .as("补充输入续接同一 Task，不新建").isEqualTo("task-11");
        assertThat(resume.path("params").path("message").path("parts").size()).isEqualTo(2);
        assertThat(resume.path("params").path("message").path("parts").get(0)
                .path("metadata").path("toolCallId").asText()).isEqualTo("ask-date");
        assertThat(resume.path("params").path("message").path("parts").get(1)
                .path("metadata").path("toolCallId").asText()).isEqualTo("ask-people");

        // 缺项：本地拒绝，wire 无续接请求
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText())
                        && !b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.sse(List.of(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.statusUpdate("task-11x", "conv-11x", "TASK_STATE_INPUT_REQUIRED", "", interrupt))))));
        ops = JSON.createArrayNode();
        ops.add(invokeOp("STREAMING", "go", "inv-11x", "pending:user_input"));
        cont = Feat087TsSdkHarness.op("continueInput");
        cont.putObject("answers").put("ask-date", "明天");
        ops.add(cont);
        result = harness.runDriver(Feat087TsSdkHarness.scenario(client("GATEWAY", "conv-11x"), ops));
        assertThat(Feat087TsSdkHarness.opResult(result, 1).path("error").path("code").asText())
                .isEqualTo("INVALID_ARGUMENT");
        for (ObjectNode r : harness.server().takeRequests()) {
            assertThat(r.path("params").path("message").has("taskId"))
                    .as("缺项不产生续接请求（仅创建请求）").isFalse();
        }
    }

    @Test
    @Tag("story-feat-087-12")
    @Story("FEAT-087.F087-12: 续接校验非法迁移")
    @DisplayName("F087-12 已终态/非等待输入/已关闭/不存在旧调用续接被拒且不新建 Task")
    void f08712ContinuationValidationRejectsIllegalTransitions() {
        // 已终态调用
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.sse(List.of(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.statusUpdate("task-12", "conv-12", "TASK_STATE_COMPLETED", "done", null))))));
        ArrayNode ops = JSON.createArrayNode();
        ops.add(invokeOp("STREAMING", "go", "inv-12", "state:COMPLETED"));
        ObjectNode cont = Feat087TsSdkHarness.op("continueInput");
        cont.put("relatedInvocationRef", "inv-12");
        cont.put("input", "again");
        ops.add(cont);
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(client("GATEWAY", "conv-12"), ops));
        assertThat(Feat087TsSdkHarness.opResult(result, 1).path("error").path("code").asText())
                .isEqualTo("INVALID_CONTINUATION");

        // 不存在的旧调用
        result = harness.runDriver(Feat087TsSdkHarness.scenario(client("GATEWAY", "conv-12b"),
                JSON.createArrayNode().add(cont)));
        assertThat(Feat087TsSdkHarness.opResult(result, 0).path("error").path("code").asText())
                .isEqualTo("INVOCATION_NOT_FOUND");

        // 非等待输入：活动调用停在本地工具待办（A13：不处于用户输入等待）
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText())
                        && !b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.sse(List.of(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.statusUpdate("task-12c", "conv-12c", "TASK_STATE_INPUT_REQUIRED", "",
                                Feat087Interrupts.single(Feat087Interrupts.clientTool("call-12c", "t-12", null, true))))))));
        ops = JSON.createArrayNode();
        ops.add(invokeOp("STREAMING", "go", "inv-12c", "pending:client_tool"));
        ObjectNode contC = Feat087TsSdkHarness.op("continueInput");
        contC.put("input", "x");
        ops.add(contC);
        result = harness.runDriver(Feat087TsSdkHarness.scenario(client("GATEWAY", "conv-12c"), ops));
        assertThat(Feat087TsSdkHarness.opResult(result, 1).path("error").path("code").asText())
                .isEqualTo("INVALID_CONTINUATION");

        // 已关闭：等待用户输入时关闭调用（A13：已关闭）
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText())
                        && !b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.sse(List.of(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.statusUpdate("task-12d", "conv-12d", "TASK_STATE_INPUT_REQUIRED", "",
                                Feat087Interrupts.items(Feat087Interrupts.userInput("ask-12d", "继续"))))))));
        ops = JSON.createArrayNode();
        ops.add(invokeOp("STREAMING", "go", "inv-12d", "pending:user_input"));
        ops.add(Feat087TsSdkHarness.op("closeCall"));
        ObjectNode contD = Feat087TsSdkHarness.op("continueInput");
        contD.put("input", "x");
        ops.add(contD);
        result = harness.runDriver(Feat087TsSdkHarness.scenario(client("GATEWAY", "conv-12d"), ops));
        assertThat(Feat087TsSdkHarness.opResult(result, 2).path("error").path("code").asText())
                .isEqualTo("INVALID_CONTINUATION");
    }
}

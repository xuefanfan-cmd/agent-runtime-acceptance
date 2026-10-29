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
 * FEAT-087 断线恢复面：流式重订阅优先、有界恢复、Gateway/Runtime 创建结果
 * 不明、SSE 注释帧心跳与空闲超时。对应 F087-13 … F087-17。
 */
@Tag("integration")
@Tag("agent-client-ts")
@Tag("feat-087")
@Feature("FEAT-087: Agent-Client支持Node服务TS语言")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Feat087RecoveryBlackboxTest {

    private Feat087TsSdkHarness harness;

    @BeforeAll
    void setUp() {
        harness = new Feat087TsSdkHarness();
    }

    @BeforeEach
    void assumeSdkEnvironmentReady() {
        // 依赖门禁：SUT（TS SDK）未交付时逐用例 SKIPPED，不计 FAIL
        Feat087TsSdkHarness.assumeEnvironmentReady(harness.sdkDir());
        // 规则与 wire 隔离：onEvery 持久规则不得跨用例泄漏
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
    @Tag("story-feat-087-13")
    @Tag("smoke-feat-087")
    @Story("FEAT-087.F087-13: 流式断线恢复重订阅优先")
    @DisplayName("F087-13 断流后先 SubscribeToTask 且不重发创建")
    void f08713StreamRecoveryResubscribesWithoutRecreate() {
        harness.server().takeRequests();
        // 流在无终态帧的情况下结束（服务端关闭流）：SDK 应进入恢复而不是重发创建；
        // A14 要求 Gateway 与直连 Runtime 分别验证，创建与重订阅规则均须持久
        harness.server().onEvery(b -> "SendStreamingMessage".equals(b.path("method").asText())
                        && !b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.sse(List.of(
                        Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                                Feat087MockA2aServer.statusUpdate("task-13", "conv-13", "TASK_STATE_WORKING", "half", null))))));
        harness.server().onEvery(b -> "SubscribeToTask".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.sse(List.of(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.task("task-13", "conv-13", "TASK_STATE_COMPLETED", "resumed", null))))));
        for (String kind : new String[] {"GATEWAY", "RUNTIME"}) {
            String conv = "conv-13-" + kind.toLowerCase();
            ObjectNode cli = Feat087TsSdkHarness.clientSpec(kind, harness.server().url(), conv);
            ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(cli,
                    JSON.createArrayNode().add(invokeOp("STREAMING", "hi", "inv-13-" + kind, "state:COMPLETED"))), 30_000);
            assertThat(result.has("fatal")).as(kind + " 无 fatal").isFalse();
            java.util.List<ObjectNode> wire = harness.server().takeRequests();
            int creates = 0;
            int subscriptions = 0;
            for (ObjectNode r : wire) {
                if ("SendStreamingMessage".equals(r.path("method").asText())
                        && !r.path("params").path("message").has("taskId")) {
                    creates++;
                }
                if ("SubscribeToTask".equals(r.path("method").asText())) {
                    subscriptions++;
                }
            }
            assertThat(creates).as(kind + " 断流后不重发创建").isEqualTo(1);
            assertThat(subscriptions).as(kind + " 恢复走重订阅").isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    @Tag("story-feat-087-39")
    @Story("FEAT-087.F087-39: STREAMING 恢复成功但 Task 持续运行时有界停止")
    @DisplayName("F087-39 恢复订阅成功但 Task 始终 RUNNING，达流式恢复上限 6 后停止并报观察中断")
    void f08739StreamRecoveryStopsWhileTaskKeepsRunning() {
        harness.server().takeRequests();
        // 创建流无终态结束（Task 已受理），随后每次恢复订阅均"成功"但 Task 持续 RUNNING
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText())
                        && !b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.sse(List.of(
                        Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                                Feat087MockA2aServer.statusUpdate("task-39", "conv-39", "TASK_STATE_WORKING", "half", null))))));
        // 每次恢复订阅“成功”：返回 RUNNING 状态帧后流正常结束，Task 始终不进入终态（持久规则）
        harness.server().onEvery(b -> "SubscribeToTask".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.sse(List.of(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.task("task-39", "conv-39", "TASK_STATE_WORKING", "still-running", null))))));
        harness.server().onEvery(b -> "GetTask".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.json(200, Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.task("task-39", "conv-39", "TASK_STATE_WORKING", "still-running", null))));
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(client("conv-39"),
                JSON.createArrayNode().add(invokeOp("STREAMING", "hi", "inv-39", "result"))), 90_000);
        assertThat(result.has("fatal")).isFalse();
        JsonNode snapshot = Feat087TsSdkHarness.opResult(result, 0).path("snapshot");
        assertThat(snapshot.path("observation").asText()).as("Task 持续运行时仍有界停止").isEqualTo("ENDED");
        assertThat(snapshot.path("error").path("code").asText()).isEqualTo("RECOVERY_EXHAUSTED");
        assertThat(snapshot.path("stale").asBoolean()).isTrue();
        java.util.List<ObjectNode> wire = harness.server().takeRequests();
        int creates = 0;
        int subscriptions = 0;
        for (ObjectNode r : wire) {
            if ("SendStreamingMessage".equals(r.path("method").asText())
                    && !r.path("params").path("message").has("taskId")) {
                creates++;
            }
            if ("SubscribeToTask".equals(r.path("method").asText())) {
                subscriptions++;
            }
        }
        assertThat(creates).as("不重发创建").isEqualTo(1);
        assertThat(subscriptions).as("恢复订阅次数不超过流式恢复上限 6").isBetween(1, 6);
    }

    @Test
    @Tag("story-feat-087-14")
    @Story("FEAT-087.F087-14: 有界恢复与预算用尽")
    @DisplayName("F087-14 连续失败达上限停止并报 RECOVERY_EXHAUSTED，上限可配置")
    void f08714BoundedRecoveryStopsAtLimit() {
        harness.server().takeRequests();
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText())
                        && !b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.sse(List.of(
                        Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                                Feat087MockA2aServer.statusUpdate("task-14", "conv-14", "TASK_STATE_WORKING", "half", null))))));
        // 恢复请求全部失败：响应头发送后连接即断（持久规则：每次恢复尝试均失败；
        // 网络错误可重试，预算耗尽应报 RECOVERY_EXHAUSTED / observation=ENDED）
        harness.server().onEvery(b ->
                        "SubscribeToTask".equals(b.path("method").asText())
                                || "GetTask".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.sseThenDisconnect(List.of()));
        ObjectNode cli = client("conv-14");
        cli.putObject("retry").put("maxConsecutiveFailures", 3)
                .put("initialDelayMs", 50).put("maxDelayMs", 100);
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(cli,
                JSON.createArrayNode().add(invokeOp("STREAMING", "hi", "inv-14", "result"))), 60_000);
        JsonNode snapshot = Feat087TsSdkHarness.opResult(result, 0).path("snapshot");
        assertThat(snapshot.path("observation").asText()).isEqualTo("ENDED");
        assertThat(snapshot.path("error").path("code").asText()).isEqualTo("RECOVERY_EXHAUSTED");
        assertThat(snapshot.path("stale").asBoolean()).isTrue();

        // 上限可配置：maxConsecutiveFailures=2 时恢复请求数少于默认 3 次的运行
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText())
                        && !b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.sse(List.of(
                        Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                                Feat087MockA2aServer.statusUpdate("task-14x", "conv-14x", "TASK_STATE_WORKING", "half", null))))));
        harness.server().onEvery(b ->
                        "SubscribeToTask".equals(b.path("method").asText())
                                || "GetTask".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.sseThenDisconnect(List.of()));
        ObjectNode cli2 = client("conv-14x");
        cli2.putObject("retry").put("maxConsecutiveFailures", 2)
                .put("initialDelayMs", 50).put("maxDelayMs", 100);
        int before = countMethod("SubscribeToTask");
        harness.runDriver(Feat087TsSdkHarness.scenario(cli2,
                JSON.createArrayNode().add(invokeOp("STREAMING", "hi", "inv-14x", "result"))), 60_000);
        int after = countMethod("SubscribeToTask");
        assertThat(after).as("上限=2 时恢复请求少于上限=3 的运行").isLessThan(before);
    }

    @Test
    @Tag("story-feat-087-15")
    @Story("FEAT-087.F087-15: Gateway 创建结果不明同键重发")
    @DisplayName("F087-15 创建响应丢失后同键重发且只创建一个 Task")
    void f08715GatewayCreateUnknownResultResendsSameKey() {
        harness.server().takeRequests();
        // 第 1 次创建连接即断（结果未知）；重发同键返回缓存 JSON
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.disconnectAtStart());
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.sse(List.of(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.statusUpdate("task-15", "conv-15", "TASK_STATE_COMPLETED", "cached", null))))));
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(client("conv-15"),
                JSON.createArrayNode().add(invokeOp("STREAMING", "hi", "inv-15", "state:COMPLETED"))), 30_000);
        assertThat(result.has("fatal")).isFalse();
        java.util.List<ObjectNode> wire = harness.server().takeRequests();
        java.util.Set<String> messageIds = new java.util.HashSet<>();
        int creates = 0;
        for (ObjectNode r : wire) {
            if ("SendStreamingMessage".equals(r.path("method").asText())) {
                creates++;
                messageIds.add(r.path("params").path("message").path("messageId").asText());
            }
        }
        assertThat(creates).as("创建至少重发一次").isGreaterThanOrEqualTo(2);
        assertThat(messageIds).as("同键重发：messageId 不变").hasSize(1);

        // (b) 并发同键 409 IDEMPOTENCY_IN_FLIGHT：可重试，同键重试直至成功（A16）
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.json(409, JSON.createObjectNode().put("code", "IDEMPOTENCY_IN_FLIGHT")));
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.sse(List.of(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.statusUpdate("task-15b", "conv-15b", "TASK_STATE_COMPLETED", "ok", null))))));
        result = harness.runDriver(Feat087TsSdkHarness.scenario(client("conv-15b"),
                JSON.createArrayNode().add(invokeOp("STREAMING", "hi", "inv-15b", "state:COMPLETED"))), 30_000);
        assertThat(result.has("fatal")).isFalse();
        java.util.Set<String> messageIdsB = new java.util.HashSet<>();
        int createsB = 0;
        for (ObjectNode r : harness.server().takeRequests()) {
            if ("SendStreamingMessage".equals(r.path("method").asText())) {
                createsB++;
                messageIdsB.add(r.path("params").path("message").path("messageId").asText());
            }
        }
        assertThat(createsB).as("409 后同键重试").isGreaterThanOrEqualTo(2);
        assertThat(messageIdsB).as("409 重试仍同键").hasSize(1);

        // (c) Gateway 创建结果持续不明：有界停止（A15：原始请求后最多 3 次重发，共 ≤4 次）
        harness.server().onEvery(b -> "SendStreamingMessage".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.disconnectAtStart());
        ObjectNode cli = client("conv-15c");
        cli.putObject("retry").put("initialDelayMs", 50).put("maxDelayMs", 100);
        result = harness.runDriver(Feat087TsSdkHarness.scenario(cli,
                JSON.createArrayNode().add(invokeOp("STREAMING", "hi", "inv-15c", "result"))), 30_000);
        JsonNode snapshotC = Feat087TsSdkHarness.opResult(result, 0).path("snapshot");
        assertThat(snapshotC.path("acceptance").asText()).isEqualTo("UNKNOWN");
        assertThat(snapshotC.path("error").path("code").asText())
                .as("持续不明时有界停止并报告受理不确定/观察中断")
                .isIn("ACCEPTANCE_UNKNOWN", "OBSERVATION_INTERRUPTED");
        int createsC = 0;
        for (ObjectNode r : harness.server().takeRequests()) {
            if ("SendStreamingMessage".equals(r.path("method").asText())) {
                createsC++;
            }
        }
        assertThat(createsC).as("原始请求 + 至多 3 次同键重发").isBetween(2, 4);
    }

    @Test
    @Tag("story-feat-087-16")
    @Story("FEAT-087.F087-16: Runtime 创建结果不确定")
    @DisplayName("F087-16 直连 Runtime 创建结果不明不重发并报 ACCEPTANCE_UNKNOWN")
    void f08716RuntimeCreateUnknownDoesNotResend() {
        harness.server().takeRequests();
        ObjectNode cli = Feat087TsSdkHarness.clientSpec("RUNTIME", harness.server().url(), "conv-16");
        harness.server().onWhere(b -> "SendMessage".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.disconnectAtStart());
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(cli,
                JSON.createArrayNode().add(invokeOp("BLOCKING", "hi", "inv-16", "result"))), 30_000);
        JsonNode snapshot = Feat087TsSdkHarness.opResult(result, 0).path("snapshot");
        assertThat(snapshot.path("acceptance").asText()).isEqualTo("UNKNOWN");
        assertThat(snapshot.path("error").path("code").asText()).isEqualTo("ACCEPTANCE_UNKNOWN");
        assertThat(countMethod("SendMessage")).as("Runtime 创建不重发").isEqualTo(1);
    }

    @Test
    @Tag("story-feat-087-17")
    @Story("FEAT-087.F087-17: SSE 注释帧心跳与空闲超时")
    @DisplayName("F087-17 注释帧不产生业务事件且空闲超时后重订阅")
    void f08717CommentFramesAreHeartbeatsNotBusinessEvents() {
        String comment = Feat087MockA2aServer.sseComment("keepalive");
        // 流发出注释帧与一帧状态后保持打开并静默：静默超过 sseIdleMs 应触发重订阅
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.sse(List.of(
                        comment,
                        Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                                Feat087MockA2aServer.statusUpdate("task-17", "conv-17", "TASK_STATE_WORKING", "half", null)))))
                        .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.KEEP_OPEN));
        harness.server().onWhere(b -> "SubscribeToTask".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.sse(List.of(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.task("task-17", "conv-17", "TASK_STATE_COMPLETED", "resumed", null))))));
        ObjectNode cli = client("conv-17");
        cli.putObject("timeouts").put("sseIdleMs", 800);
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(cli,
                JSON.createArrayNode().add(invokeOp("STREAMING", "hi", "inv-17", "state:COMPLETED"))), 30_000);
        assertThat(result.has("fatal")).isFalse();
        // 注释帧不作为业务事件投递
        for (JsonNode ev : result.path("events")) {
            String type = ev.path("type").asText();
            org.assertj.core.api.Assertions.assertThat(type)
                    .as("注释帧不产生 output/status 业务事件").isNotIn("output");
        }
        java.util.List<ObjectNode> wire = harness.server().takeRequests();
        int subscriptions = 0;
        int creates = 0;
        for (ObjectNode r : wire) {
            if ("SubscribeToTask".equals(r.path("method").asText())) {
                subscriptions++;
            }
            if ("SendStreamingMessage".equals(r.path("method").asText())) {
                creates++;
            }
        }
        assertThat(subscriptions).as("空闲超时触发重订阅").isGreaterThanOrEqualTo(1);
        assertThat(creates).as("空闲恢复不重发创建").isEqualTo(1);
    }
}

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

import java.util.ArrayList;
import java.util.List;

import static com.huawei.ascend.sit.cases.integration.agent_client_ts.Feat087MockA2aServer.JSON;
import static com.huawei.ascend.sit.cases.integration.agent_client_ts.Feat087TsSdkHarness.invokeOp;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-087 输出面：双事件体系归集、交织容错、多跳生产者与调用树。
 * 对应 F087-18 … F087-20。
 */
@Tag("integration")
@Tag("agent-client-ts")
@Tag("feat-087")
@Feature("FEAT-087: Agent-Client支持Node服务TS语言")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Feat087OutputCallTreeBlackboxTest {

    private Feat087TsSdkHarness harness;

    @BeforeAll
    void setUp() {
        harness = new Feat087TsSdkHarness();
    }

    @BeforeEach
    void assumeSdkEnvironmentReady() {
        // 依赖门禁：SUT（TS SDK）未交付时逐用例 SKIPPED，不计 FAIL
        Feat087TsSdkHarness.assumeEnvironmentReady(harness.sdkDir());
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

    private static final java.util.concurrent.atomic.AtomicInteger FRAME_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * OpenAI 风格工具事件帧：产物 DataPart 携带 {type:'TOOL_CALL_'+phase} 事件，
     * producer 由 artifact.metadata.agentEvent.source（type=output）声明。
     */
    private static String toolFrame(String phase, String taskId, String conv, String agentId,
                                    String toolCallId, String toolName, String argsDelta, String result) {
        ObjectNode data = JSON.createObjectNode();
        switch (phase) {
            case "START" -> {
                data.put("type", "TOOL_CALL_START").put("toolCallId", toolCallId);
                if (toolName != null) {
                    data.put("toolName", toolName);
                }
            }
            case "ARGS" -> data.put("type", "TOOL_CALL_ARGS").put("toolCallId", toolCallId).put("delta", argsDelta);
            case "END" -> data.put("type", "TOOL_CALL_END").put("toolCallId", toolCallId);
            case "RESULT" -> {
                data.put("type", "TOOL_CALL_RESULT").put("toolCallId", toolCallId);
                data.set("result", jsonOrText(result));
            }
            case "ERROR" -> data.put("type", "TOOL_CALL_RESULT").put("toolCallId", toolCallId)
                    .put("isError", true).put("content", result);
            default -> throw new IllegalArgumentException("unsupported phase: " + phase);
        }
        ObjectNode frame = JSON.createObjectNode();
        ObjectNode payload = frame.putObject("artifactUpdate");
        payload.put("taskId", taskId).put("contextId", conv).put("lastChunk", true);
        ObjectNode artifact = payload.putObject("artifact");
        artifact.put("artifactId", "art-tool-" + FRAME_SEQ.incrementAndGet());
        artifact.putArray("parts").addObject().set("data", data);
        ObjectNode event = artifact.putObject("metadata").putObject("agentEvent");
        event.put("type", "output");
        event.putObject("source").put("agentId", agentId).put("taskId", taskId);
        return Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(frame));
    }

    private List<String> terminalFrame(String taskId, String conv) {
        return List.of(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                Feat087MockA2aServer.statusUpdate(taskId, conv, "TASK_STATE_COMPLETED", "done", null))));
    }

    /**
     * A2A OutputSchema 产物帧（L2 §3.9）：DataPart 携带 {type:'tool_result', payload:{toolCallId,
     * toolName, result}}，producer 从 artifact.metadata.agentEvent（type=output）.source 读取。
     */
    private static String a2aOutputSchemaFrame(String taskId, String conv, String agentId,
                                               String artifactId, String toolCallId, String toolName, String text) {
        ObjectNode data = JSON.createObjectNode();
        data.put("type", "tool_result");
        data.putObject("payload").put("toolCallId", toolCallId).put("toolName", toolName)
                .set("result", jsonOrText(text));
        ObjectNode frame = JSON.createObjectNode();
        ObjectNode payload = frame.putObject("artifactUpdate");
        payload.put("taskId", taskId).put("contextId", conv).put("lastChunk", true);
        ObjectNode artifact = payload.putObject("artifact");
        artifact.put("artifactId", artifactId);
        artifact.putArray("parts").addObject().set("data", data);
        ObjectNode event = artifact.putObject("metadata").putObject("agentEvent");
        event.put("type", "output");
        event.putObject("source").put("agentId", agentId).put("taskId", taskId);
        return Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(frame));
    }

    private static JsonNode jsonOrText(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.textNode(text);
        }
    }

    @Test
    @Tag("story-feat-087-18")
    @Story("FEAT-087.F087-18: 双事件体系归集与错误隔离")
    @DisplayName("F087-18 TOOL_CALL 四类事件归集且缺 START 容错")
    void f08718DualEventSystemsAggregatedWithIsolation() {
        List<String> frames = new ArrayList<>();
        // A2A OutputSchema 体系（A20 双事件体系之一）
        frames.add(a2aOutputSchemaFrame("task-18", "conv-18", "root-agent",
                "art-a2a-1", "call-a2a", "tool-a2a", "{\"temp\":25}"));
        frames.add(toolFrame("START", "task-18", "conv-18", "root-agent", "call-a", "tool-a", null, null));
        frames.add(toolFrame("ARGS", "task-18", "conv-18", "root-agent", "call-a", null, "{\"city\":\"SZ\"}", null));
        frames.add(toolFrame("END", "task-18", "conv-18", "root-agent", "call-a", null, null, null));
        frames.add(toolFrame("RESULT", "task-18", "conv-18", "root-agent", "call-a", null, null, "{\"temp\":26}"));
        frames.add(toolFrame("ARGS", "task-18", "conv-18", "root-agent", "call-b", "tool-b", "{\"x\":1}", null));
        frames.add(toolFrame("ERROR", "task-18", "conv-18", "root-agent", "call-b", null, null, "boom"));
        frames.addAll(terminalFrame("task-18", "conv-18"));
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.sse(frames));
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(client("conv-18"),
                JSON.createArrayNode().add(invokeOp("STREAMING", "hi", "inv-18", "state:COMPLETED"))), 30_000);
        assertThat(result.has("fatal")).isFalse();
        JsonNode viewA = null;
        JsonNode viewB = null;
        for (JsonNode ev : Feat087TsSdkHarness.events(result, "tool.output")) {
            String id = ev.path("view").path("toolCallId").asText();
            if ("call-a".equals(id)) {
                viewA = ev.path("view");
            }
            if ("call-b".equals(id)) {
                viewB = ev.path("view");
            }
        }
        assertThat(viewA).as("call-a 归集出最终结果").isNotNull();
        assertThat(viewA.path("toolName").asText()).isEqualTo("tool-a");
        assertThat(viewA.path("result").toString()).contains("temp");
        assertThat(viewB).as("缺 START 的 call-b 仍归集").isNotNull();
        // A2A OutputSchema 分支：按 producer + toolCallId 归集
        JsonNode viewA2a = null;
        for (JsonNode ev : Feat087TsSdkHarness.events(result, "tool.output")) {
            if ("call-a2a".equals(ev.path("view").path("toolCallId").asText())) {
                viewA2a = ev.path("view");
            }
        }
        assertThat(viewA2a).as("A2A OutputSchema 产物归集出 tool.output").isNotNull();
        assertThat(viewA2a.path("toolName").asText()).isEqualTo("tool-a2a");
        assertThat(viewA2a.path("producer").path("agentId").asText()).isEqualTo("root-agent");
        assertThat(Feat087TsSdkHarness.events(result, "status").size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Tag("story-feat-087-19")
    @Story("FEAT-087.F087-19: 多跳生产者复用 toolCallId 分开归集")
    @DisplayName("F087-19 两生产者复用同一 toolCallId 时不合并")
    void f08719ProducersWithSameToolCallIdStaySeparate() {
        List<String> frames = new ArrayList<>();
        frames.add(toolFrame("RESULT", "task-19", "conv-19", "producer-a", "call-shared", "tool-x", null, "{\"from\":\"a\"}"));
        frames.add(toolFrame("RESULT", "task-19", "conv-19", "producer-b", "call-shared", "tool-y", null, "{\"from\":\"b\"}"));
        frames.addAll(terminalFrame("task-19", "conv-19"));
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.sse(frames));
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(client("conv-19"),
                JSON.createArrayNode().add(invokeOp("STREAMING", "hi", "inv-19", "state:COMPLETED"))), 30_000);
        List<String> producers = new ArrayList<>();
        for (JsonNode ev : Feat087TsSdkHarness.events(result, "tool.output")) {
            if ("call-shared".equals(ev.path("view").path("toolCallId").asText())) {
                producers.add(ev.path("view").path("producer").path("agentId").asText());
            }
        }
        assertThat(producers).as("同一 toolCallId 按生产者分开归集")
                .contains("producer-a", "producer-b");
    }

    @Test
    @Tag("story-feat-087-20")
    @Story("FEAT-087.F087-20: 调用树构建与模式不可用")
    @DisplayName("F087-20 STREAMING 构建调用树，BLOCKING 返回 UNAVAILABLE_FOR_MODE")
    void f08720CallTreeBuiltInStreamingOnly() {
        List<String> frames = new ArrayList<>();
        // 五层委派链：root → level-1 → … → level-5
        frames.add(delegationFrame("task-20", "conv-20", "root", "level-1"));
        frames.add(delegationFrame("task-20", "conv-20", "level-1", "level-2"));
        frames.add(delegationFrame("task-20", "conv-20", "level-2", "level-3"));
        frames.add(delegationFrame("task-20", "conv-20", "level-3", "level-4"));
        frames.add(delegationFrame("task-20", "conv-20", "level-4", "level-5"));
        // 兄弟分叉：root 下两个并行子分支
        frames.add(delegationFrame("task-20", "conv-20", "root", "sib-1"));
        frames.add(delegationFrame("task-20", "conv-20", "root", "sib-2"));
        // 容错注入：环边（level-1 → root）应诊断并拒绝；重复边（root → level-1）应忽略
        frames.add(delegationFrame("task-20", "conv-20", "level-1", "root"));
        frames.add(delegationFrame("task-20", "conv-20", "root", "level-1"));
        frames.addAll(terminalFrame("task-20", "conv-20"));
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.sse(frames));
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(client("conv-20"),
                JSON.createArrayNode().add(invokeOp("STREAMING", "hi", "inv-20", "state:COMPLETED"))), 30_000);
        assertThat(result.has("fatal")).isFalse();
        JsonNode snapshot = Feat087TsSdkHarness.opResult(result, 0).path("snapshot");
        assertThat(snapshot.path("callTreeAvailability").asText())
                .as("STREAMING 下调用树可用").isEqualTo("AVAILABLE");
        List<JsonNode> treeEvents = Feat087TsSdkHarness.treeEvents(result);
        assertThat(treeEvents.size()).as("订阅 callTree 收到快照").isGreaterThanOrEqualTo(1);

        // 结构断言：环边注入前应存在完整度 LIVE 且结构完整的树快照（L2 §3.10）
        JsonNode live = null;
        for (JsonNode ev : treeEvents) {
            JsonNode t = ev.path("tree");
            if ("LIVE".equals(t.path("completeness").asText()) && t.path("root").path("children").size() == 3) {
                live = t;
            }
        }
        assertThat(live).as("环边注入前存在 LIVE 且三子节点的树快照").isNotNull();
        JsonNode chain = live.path("root").path("children").get(0);
        int depth = 1;
        while (chain.has("children") && chain.path("children").size() > 0) {
            chain = chain.path("children").get(0);
            depth++;
        }
        assertThat(depth).as("五层委派链深度（root 下 5 层）").isEqualTo(5);

        // 末次快照：环/重复边仅产生诊断（CYCLE_DETECTED），已知结构不受污染
        JsonNode tree = treeEvents.get(treeEvents.size() - 1).path("tree");
        assertThat(tree.path("diagnostics").toString()).as("环边被诊断且拒绝").contains("CYCLE_DETECTED");
        JsonNode root = tree.path("root");
        assertThat(root.path("key").path("agentId").asText()).as("根节点为 root").isEqualTo("root");
        assertThat(root.path("children").size())
                .as("根节点挂 level-1 与两个兄弟分叉，环/重复边不改结构").isEqualTo(3);

        harness.server().onWhere(b -> "SendMessage".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.json(200, Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.task("task-20b", "conv-20b", "TASK_STATE_COMPLETED", "ok", null))));
        result = harness.runDriver(Feat087TsSdkHarness.scenario(client("conv-20b"),
                JSON.createArrayNode().add(invokeOp("BLOCKING", "hi", "inv-20b", "state:COMPLETED"))), 30_000);
        assertThat(Feat087TsSdkHarness.opResult(result, 0).path("snapshot").path("callTreeAvailability").asText())
                .isEqualTo("UNAVAILABLE_FOR_MODE");
    }

    @Test
    @Tag("story-feat-087-41")
    @Story("FEAT-087.F087-41: 同 Task 续接共享树，新 Task 新树")
    @DisplayName("F087-41 补充输入后调用树修订号延续，新调用构建独立新树")
    void f08741SameTaskContinuationSharesTreeNewTaskNewTree() {
        // 第一轮：委派关系到达后等待用户补充输入（树处于 LIVE）
        List<String> frames = new ArrayList<>();
        frames.add(delegationFrame("task-41", "conv-41", "root", "child-a"));
        frames.add(delegationFrame("task-41", "conv-41", "child-a", "child-b"));
        frames.add(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                Feat087MockA2aServer.statusUpdate("task-41", "conv-41", "TASK_STATE_INPUT_REQUIRED", "",
                        Feat087Interrupts.single(Feat087Interrupts.userInput("ask-41", "请补充说明"))))));
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText())
                        && !b.path("params").path("message").has("taskId"), r -> Feat087MockA2aServer.sse(frames));
        // 续接完成，并带来新的委派关系：树修订号应继续增长而非重建
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText())
                        && b.path("params").path("message").has("taskId"), r -> {
            List<String> resume = new ArrayList<>();
            resume.add(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                    Feat087MockA2aServer.statusUpdate("task-41", "conv-41", "TASK_STATE_WORKING", "resumed", null))));
            resume.add(delegationFrame("task-41", "conv-41", "child-b", "child-c"));
            resume.add(Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                    Feat087MockA2aServer.statusUpdate("task-41", "conv-41", "TASK_STATE_COMPLETED", "done", null))));
            return Feat087MockA2aServer.sse(resume);
        });
        ArrayNode ops = JSON.createArrayNode();
        ops.add(invokeOp("STREAMING", "hi", "inv-41", "event:input.required"));
        ObjectNode cont = Feat087TsSdkHarness.op("continueInput");
        cont.put("input", "补充说明");
        cont.put("await", "state:COMPLETED");
        ops.add(cont);
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(client("conv-41"), ops), 30_000);
        assertThat(result.has("fatal")).isFalse();
        List<JsonNode> treeEvents = Feat087TsSdkHarness.treeEvents(result);
        assertThat(treeEvents.size()).as("续接前后均产生树快照").isGreaterThanOrEqualTo(2);
        JsonNode before = treeEvents.get(0).path("tree");
        JsonNode after = treeEvents.get(treeEvents.size() - 1).path("tree");
        assertThat(after.path("revision").asLong()).as("续接后树修订号继续增长（未重建）")
                .isGreaterThan(before.path("revision").asLong());
        assertThat(after.path("root").path("key").path("agentId").asText())
                .as("续接共享同一棵树（根不变）").isEqualTo("root");

        // 新 Task 新树：新调用的树从修订号小值重新开始
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText())
                        && !b.path("params").path("message").has("taskId"), r ->
                Feat087MockA2aServer.sse(List.of(
                        delegationFrame("task-41b", "conv-41b", "root", "child-x"),
                        Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                                Feat087MockA2aServer.statusUpdate("task-41b", "conv-41b", "TASK_STATE_COMPLETED", "ok", null))))));
        result = harness.runDriver(Feat087TsSdkHarness.scenario(client("conv-41b"),
                JSON.createArrayNode().add(invokeOp("STREAMING", "hi", "inv-41b", "state:COMPLETED"))), 30_000);
        List<JsonNode> newTreeEvents = Feat087TsSdkHarness.treeEvents(result);
        assertThat(newTreeEvents.size()).as("新调用产生树快照").isGreaterThanOrEqualTo(1);
        assertThat(newTreeEvents.get(0).path("call").asText())
                .as("新 Task 新树（不同调用句柄）").isNotEqualTo(treeEvents.get(0).path("call").asText());
        assertThat(newTreeEvents.get(0).path("tree").path("revision").asLong())
                .as("新树修订号从头开始").isLessThanOrEqualTo(before.path("revision").asLong());
    }

    /**
     * 委派关系产物帧：artifact.metadata.agentEvent={type:'delegation', source, target}；
     * 根代理（"root"）引用当前 Task（与 bindRoot 的根 Task 一致），其余代理用 <agent>-task 派生 Task。
     */
    private static String delegationFrame(String taskId, String conv, String parentAgent, String childAgent) {
        ObjectNode frame = JSON.createObjectNode();
        ObjectNode payload = frame.putObject("artifactUpdate");
        payload.put("taskId", taskId).put("contextId", conv);
        ObjectNode artifact = payload.putObject("artifact");
        artifact.put("artifactId", "art-del-" + FRAME_SEQ.incrementAndGet());
        ObjectNode event = artifact.putObject("metadata").putObject("agentEvent");
        event.put("type", "delegation");
        event.putObject("source").put("agentId", parentAgent)
                .put("taskId", "root".equals(parentAgent) ? taskId : parentAgent + "-task");
        event.putObject("target").put("agentId", childAgent)
                .put("taskId", "root".equals(childAgent) ? taskId : childAgent + "-task");
        artifact.putArray("parts").addObject().put("text", "delegate to " + childAgent);
        return Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(frame));
    }
}

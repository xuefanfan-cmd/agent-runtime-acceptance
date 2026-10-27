package com.huawei.ascend.sit.cases.integration.react_travel;

import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.controller.a2a.client.*;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.*;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.huawei.ascend.sit.cases.integration.react_travel.Feat086A2aFixture.*;
import static org.assertj.core.api.Assertions.*;

/** Public Caller SPI over real SDK HTTP. Agent end-to-end checks remain in the Demo test class. */
@Tag("integration") @Tag("openjiuwen") @Tag("feat-086")
@Feature("FEAT-086 remote A2A response hooks")
class Feat086RemoteCallerContractTest {
    final Path evidence = Path.of("target/feature-acceptance-testing/feat086/caller", UUID.randomUUID().toString());
    final List<String> events = new CopyOnWriteArrayList<>();
    final RemoteAgentCaller.EventObserver observer = new RemoteAgentCaller.EventObserver() {
        public void onStatus(TaskStatusUpdateEvent event) { events.add("status:" + event.status().state()); }
        public void onArtifact(TaskArtifactUpdateEvent event) { events.add("artifact:" + event.artifact().artifactId()); }
    };
    RemoteCallOutcome call(String key, List<Map<String,Object>> wire, boolean streaming, RemoteTaskResultHook hook) throws Exception {
        try (Feat086A2aFixture peer = new Feat086A2aFixture(evidence.resolve(key), wire)) {
            A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
            registry.register("peer", peer.card(), 10, streaming);
            A2ARemoteAgentClient caller = new A2ARemoteAgentClient(registry, 4, null, hook);
            try {
                RemoteCallOutcome outcome = caller.callOutcome(new RemoteCall("peer", "input", "context", null,
                        Map.of(), Map.of(), streaming), observer).get(15, TimeUnit.SECONDS);
                assertThat(peer.errors).isEmpty();
                assertThat(peer.requests).hasSize(1);
                Files.writeString(evidence.resolve(key).resolve("outcome.json"), JSON.writeValueAsString(outcome));
                return outcome;
            } finally { caller.shutdown(); }
        }
    }
    String result(String key, List<Map<String,Object>> artifacts, List<Map<String,Object>> status) throws Exception {
        return call(key, List.of(Map.of("task", task("COMPLETED", artifacts, status))), false, null).result();
    }
    Map<String,Object> a(String id, String value) { return artifact(id, List.of(text(value)), Map.of()); }

    @Test @Tag("f086-30") @Story("F086-30 complete snapshot") @DisplayName("F086-30 完整 Task 只交付 Status")
    void f086_30() throws Exception {
        assertThat(call("30", List.of(Map.of("task", task("COMPLETED", List.of(a("old", "history")), List.of(text("done"))))), true, null).result()).isEqualTo("history");
        assertThat(events).containsExactly("status:TASK_STATE_COMPLETED");
    }
    @Test @Tag("f086-31") @Story("F086-31 Message only") @DisplayName("F086-31 Message 不进入 Task Hook 或事件投影")
    void f086_31() throws Exception {
        AtomicInteger count = new AtomicInteger();
        assertThat(call("31", List.of(Map.of("message", message(List.of(text("message-only"))))), true,
                task -> { count.incrementAndGet(); return "wrong"; }).result()).isEqualTo("message-only");
        assertThat(count.get()).isZero(); assertThat(events).isEmpty();
    }
    @Test @Tag("f086-40") @Story("F086-40 completed") @DisplayName("F086-40 结果 Hook 一次读取原始聚合 Task")
    void f086_40() throws Exception {
        AtomicInteger count = new AtomicInteger();
        var wire = List.of(status("WORKING", "start"), update("a", "first", false), update("a", "second", true), status("COMPLETED", "done"));
        assertThat(call("40", wire, true, task -> {
            count.incrementAndGet(); assertThat(task.status().state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
            assertThat(task.artifacts()).hasSize(1); assertThat(task.artifacts().getFirst().parts()).hasSize(2);
            return "hook-value";
        }).result()).isEqualTo("hook-value");
        assertThat(count.get()).isEqualTo(1);
    }
    @Test @Tag("f086-44") @Story("F086-44 failure") @DisplayName("F086-44 FAILED 与 REJECTED 不触发成功 Hook")
    void f086_44() throws Exception {
        for (String state : List.of("FAILED", "REJECTED")) {
            AtomicInteger count = new AtomicInteger();
            var out = call("44-" + state, List.of(Map.of("task", task(state, List.of(a("partial", "partial")), List.of(text("failure-reason"))))), false,
                    task -> { count.incrementAndGet(); return "wrong"; });
            assertThat(out.remoteState().name()).isEqualTo("TASK_STATE_" + state);
            assertThat(out.result()).isEqualTo("failure-reason"); assertThat(count.get()).isZero();
        }
    }
    @Test @Tag("f086-45") @Story("F086-45 canceled") @DisplayName("F086-45 CANCELED 不触发成功 Hook")
    void f086_45() throws Exception {
        AtomicInteger count = new AtomicInteger();
        var out = call("45", List.of(Map.of("task", task("CANCELED", List.of(a("partial", "partial")), List.of(text("cancel"))))), false,
                task -> { count.incrementAndGet(); return "wrong"; });
        assertThat(out.remoteState()).isEqualTo(TaskState.TASK_STATE_CANCELED); assertThat(count.get()).isZero();
    }
    @Test @Tag("f086-46") @Story("F086-46 interrupted") @DisplayName("F086-46 INPUT_REQUIRED 与 AUTH_REQUIRED 不提取成功结果")
    void f086_46() throws Exception {
        for (String state : List.of("INPUT_REQUIRED", "AUTH_REQUIRED")) {
            AtomicInteger count = new AtomicInteger();
            var out = call("46-" + state, List.of(Map.of("task", task(state, List.of(a("partial", "partial")), List.of(text("provide-input"))))), false,
                    task -> { count.incrementAndGet(); return "wrong"; });
            assertThat(out.result()).isNull(); assertThat(out.inputPrompt()).isEqualTo("provide-input");
            assertThat(out.remoteState().name()).isEqualTo("TASK_STATE_" + state); assertThat(count.get()).isZero();
        }
    }
    @Test @Tag("f086-48") @Story("F086-48 incomplete stream") @DisplayName("F086-48 部分结果后断流不算成功")
    void f086_48() throws Exception {
        AtomicInteger count = new AtomicInteger();
        assertThatThrownBy(() -> call("48", List.of(status("WORKING", "start"), update("partial", "partial", false)), true,
                task -> { count.incrementAndGet(); return "wrong"; })).isInstanceOf(ExecutionException.class)
                .hasMessageContaining("closed the stream before a terminal event");
        assertThat(count.get()).isZero();
    }
    void hookError(String key, RemoteTaskResultHook hook, String expected) throws Exception {
        try (Feat086A2aFixture peer = new Feat086A2aFixture(evidence.resolve(key),
                List.of(Map.of("task", task("COMPLETED", List.of(a("answer", "fallback-forbidden")), List.of(text("status-fallback-forbidden"))))))) {
            A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry(); registry.register("peer", peer.card(), 10, false);
            A2ARemoteAgentClient caller = new A2ARemoteAgentClient(registry, 4, null, hook);
            try {
                assertThatThrownBy(() -> caller.callOutcome(new RemoteCall("peer", "input", "context", null, Map.of()), observer)
                        .get(15, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasStackTraceContaining(expected);
                assertThat(peer.requests).hasSize(1); assertThat(peer.errors).isEmpty();
            } finally { caller.shutdown(); }
        }
    }
    @Test @Tag("f086-50") @Story("F086-50 null result") @DisplayName("F086-50 独立 Caller null 结果异常且不重发")
    void f086_50() throws Exception { hookError("50", task -> null, "null"); }
    @Test @Tag("f086-51") @Story("F086-51 throwing result") @DisplayName("F086-51 独立 Caller 异常保留原因且不重发")
    void f086_51() throws Exception { hookError("51", task -> { throw new IllegalStateException("F086-result-canary"); }, "F086-result-canary"); }

    @Test @Tag("f086-62") @Story("F086-62 agentEvent filtering") @DisplayName("F086-62 agentEvent 键含 null 也过滤")
    void f086_62() throws Exception {
        for (Object marker : List.of(Map.of("type", "output"))) {
            Map<String,Object> metadata = new LinkedHashMap<>(); metadata.put("agentEvent", marker); metadata.put("_agentcore_terminal", true);
            assertThat(result("62-" + (marker == null ? "null" : "object"), List.of(artifact("hidden", List.of(text("hidden")), metadata), a("business", "visible")), List.of())).isEqualTo("visible");
        }
        Map<String,Object> nullMarker = new LinkedHashMap<>(); nullMarker.put("agentEvent", null); nullMarker.put("_agentcore_terminal", true);
        Task original = Task.builder().id("remote-task").contextId("remote-context").status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
                .artifacts(List.of(Artifact.builder().artifactId("hidden").parts(new TextPart("hidden")).metadata(nullMarker).build(),
                        Artifact.builder().artifactId("visible").parts(new TextPart("visible")).build())).build();
        assertThat(Feat086ProjectionBatchContractTest.callbackResult(original, null).get("call-0")).isEqualTo("visible");
    }
    @Test @Tag("f086-63") @Story("F086-63 last terminal") @DisplayName("F086-63 最后终态候选优先包括空串")
    void f086_63() throws Exception {
        for (String last : List.of("new", "")) assertThat(result("63-" + last,
                List.of(a("plain", "plain"), artifact("old", List.of(text("old")), Map.of("_agentcore_terminal", true)),
                        artifact("new", List.of(text(last)), Map.of("_agentcore_terminal", true))), List.of())).isEqualTo(last);
    }
    @Test @Tag("f086-64") @Story("F086-64 concatenation") @DisplayName("F086-64 普通 Parts 按顺序无分隔拼接")
    void f086_64() throws Exception {
        assertThat(result("64", List.of(artifact("a", List.of(text(" A "), text("B\n")), Map.of()), a("b", "C")), List.of())).isEqualTo(" A B\nC");
    }
    @Test @Tag("f086-65") @Story("F086-65 part conversion") @DisplayName("F086-65 Text Data File 默认转换")
    void f086_65() throws Exception {
        assertThat(result("65", List.of(artifact("a", List.of(text("raw "), data(Map.of("x", 1)),
                Map.of("url", "https://example.invalid/file.txt", "mediaType", "text/plain")), Map.of())), List.of())).isEqualTo("raw {\"x\":1.0}");
    }
    @Test @Tag("f086-66") @Story("F086-66 envelope") @DisplayName("F086-66 终态 envelope 字段优先级与标量集合保留")
    void f086_66() throws Exception {
        int index = 0;
        for (String type : List.of("answer", "workflow_final")) {
            for (String field : List.of("content", "delta", "output", "response")) {
                assertThat(result("66-" + index++, List.of(artifact("a", List.of(data(Map.of("type", type, "content", "outer", "payload", Map.of(field, "inner")))), Map.of())), List.of())).isEqualTo("inner");
            }
            for (Object value : List.of(false, 0.0, List.of(), Map.of())) {
                assertThat(result("66-" + index++, List.of(artifact("a", List.of(data(Map.of("type", type, "payload", Map.of("content", " ", "output", value)))), Map.of())), List.of())).isEqualTo(JSON.writeValueAsString(value));
            }
        }
    }
    @Test @Tag("f086-67") @Story("F086-67 intermediate envelope") @DisplayName("F086-67 中间 envelope 过滤而普通数据保留")
    void f086_67() throws Exception {
        assertThat(result("67", List.of(artifact("a", List.of(data(Map.of("type", "llm_output", "index", 0, "payload", Map.of("content", "skip"))), data(Map.of("business", "keep"))), Map.of())), List.of())).isEqualTo("{\"business\":\"keep\"}");
    }
    @Test @Tag("f086-68") @Story("F086-68 status fallback") @DisplayName("F086-68 状态回退只读 Text 不合成 completed 或读 history")
    void f086_68() throws Exception {
        assertThat(result("68-text", List.of(a("blank", " \n")), List.of(text("first"), data(Map.of("ignored", true)), text("second")))).isEqualTo("firstsecond");
        Map<String,Object> task = task("COMPLETED", List.of(), List.of()); task.put("history", List.of(message(List.of(text("history-ignored")))));
        assertThat(call("68-history", List.of(Map.of("task", task)), false, null).result()).isEmpty();
    }
    @Test @Tag("f086-69") @Story("F086-69 SDK aggregation") @DisplayName("F086-69 同 ID 替换追加保持 SDK 列表位置")
    void f086_69() throws Exception {
        assertThat(call("69", List.of(status("WORKING", "start"), update("a", "old", false), update("b", "B", false),
                update("a", "A", false), update("a", "+", true), update("missing", "ignored", true), status("COMPLETED", "done")), true, null).result()).isEqualTo("A+B");
    }
    @Test @Tag("f086-70") @Story("F086-70 response metadata") @DisplayName("F086-70 完成响应 metadata 独立导出")
    void f086_70() throws Exception {
        for (Object value : List.of(Map.of("nested", List.of(Map.of("x", 1.0))), "invalid")) {
            Map<String,Object> task = task("COMPLETED", List.of(a("a", "answer")), List.of());
            task.put("metadata", Map.of("_agentcore_response_metadata", value));
            var out = call("70-" + (value instanceof Map ? "map" : "invalid"), List.of(Map.of("task", task)), false, null);
            assertThat(out.result()).isEqualTo("answer");
            assertThat(JSON.<com.fasterxml.jackson.databind.JsonNode>valueToTree(out.responseMetadata())).isEqualTo(JSON.valueToTree(value instanceof Map ? value : Map.of()));
        }
    }
}

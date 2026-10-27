package com.huawei.ascend.sit.cases.integration.react_travel;

import com.openjiuwen.service.app.controller.a2a.client.*;
import com.openjiuwen.service.app.controller.a2a.A2aPushNotificationCallback;
import com.openjiuwen.service.app.orchestrator.A2AEnabledServeOrchestrator;
import com.openjiuwen.service.app.lifecycle.ActiveStreamRegistry;
import com.openjiuwen.service.spec.dto.*;
import com.openjiuwen.service.spec.spi.*;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.spec.*;
import org.junit.jupiter.api.*;
import io.qameta.allure.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Exercises documented manual construction and AgentHandler/Caller SPIs; no internal coordinator access. */
@Tag("integration") @Tag("openjiuwen") @Tag("feat-086")
@Feature("FEAT-086 remote A2A response hooks")
class Feat086ProjectionBatchContractTest {
    static TaskStatusUpdateEvent state(String state, String content) {
        Message m = content == null ? null : Message.builder().role(Message.Role.ROLE_AGENT).parts(new TextPart(content)).build();
        return new TaskStatusUpdateEvent("remote-task", new TaskStatus(TaskState.valueOf("TASK_STATE_" + state), m, null), "remote-context", Map.of());
    }
    static TaskArtifactUpdateEvent artifact(String id, String text, Map<String,Object> metadata) {
        return new TaskArtifactUpdateEvent("remote-task", Artifact.builder().artifactId(id).parts(new TextPart(text)).metadata(metadata).build(), "remote-context", true, false, Map.of());
    }
    static RemoteCallOutcome done(String result) { return new RemoteCallOutcome("remote-task", TaskState.TASK_STATE_COMPLETED, "COMPLETED", result, null); }
    static boolean delegation(StreamingEventKind event) {
        return event instanceof TaskArtifactUpdateEvent a && a.artifact().metadata() != null
                && a.artifact().metadata().get("agentEvent") instanceof Map<?,?> m && "delegation".equals(m.get("type"));
    }
    static final class Probe implements AgentHandler {
        final List<String> names; final boolean resume;
        final List<ServeRequest> received = new CopyOnWriteArrayList<>();
        Probe(List<String> names, boolean resume) { this.names = names; this.resume = resume; }
        public QueryResponse query(ServeRequest r) {
            received.add(r);
            if (r.getMetadata().containsKey("runtime.remoteToolResults")) return new QueryResponse(Map.of("content", "parent-complete"), r.getConversationId());
            List<Map<String,Object>> items = new ArrayList<>();
            for (int i=0; i<names.size(); i++) items.add(Map.of("type", "__interaction__", "toolCallId", "call-" + i,
                    "toolName", "a2a_delegate", "message", r.lastUserQuery(), "context",
                    Map.of("_interrupt_kind", "a2a_delegate", "agentName", names.get(i), "resume", resume)));
            return new QueryResponse(Map.of("_interrupt", Map.of("items", items)), r.getConversationId());
        }
        public void streamQuery(ServeRequest r, QueryStreamObserver o) {
            QueryResponse response = query(r);
            Map<?,?> value = (Map<?,?>) response.getResult();
            if(value.containsKey("_interrupt")) o.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, value.get("_interrupt")));
            else o.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, value.get("content")));
            o.onComplete(response);
        }
        Map<?,?> results() {
            return received.stream().filter(r -> r.getMetadata().containsKey("runtime.remoteToolResults"))
                    .map(r -> (Map<?,?>)r.getMetadata().get("runtime.remoteToolResults")).findFirst().orElseThrow();
        }
    }
    static final class Output implements QueryStreamObserver {
        final List<QueryChunk> chunks = new CopyOnWriteArrayList<>();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        public void onNext(QueryChunk chunk) { chunks.add(chunk); }
        public void onError(Throwable error) { errors.add(error); }
        public void onComplete() { }
        List<TaskArtifactUpdateEvent> artifacts() { return chunks.stream().map(QueryChunk::getData).filter(TaskArtifactUpdateEvent.class::isInstance).map(TaskArtifactUpdateEvent.class::cast).toList(); }
    }
    static final class Session implements AutoCloseable {
        final Probe probe; final Output output = new Output(); final ServeRequest request = new ServeRequest();
        final A2AEnabledServeOrchestrator runtime;
        Session(RemoteAgentCaller caller, RemoteProjectionHook projection, RemoteTaskResultHook result, List<String> names, boolean resume, boolean legacy) {
            probe = new Probe(names, resume);
            var store = new InMemoryTaskStore();
            runtime = legacy ? new A2AEnabledServeOrchestrator(probe, store, caller, new ActiveStreamRegistry(), "parent-agent", 8, 32, 5)
                    : new A2AEnabledServeOrchestrator(probe, store, caller, new ActiveStreamRegistry(), "parent-agent", 8, 32, 5, null, projection, result);
            request.setConversationId("F086-"+UUID.randomUUID()); request.setStream(true);
            request.setMetadata(Map.of("runtime.parentTaskId", "parent-task"));
            request.setMessages(List.of(Map.of("role", "user", "content", "delegation-input")));
        }
        void run() { runtime.streamQuery(request, output); assertThat(output.errors).isEmpty(); }
        public void close() { runtime.stopDispatching(); }
    }
    Session session(RemoteAgentCaller caller, RemoteProjectionHook hook) { return new Session(caller, hook, null, List.of("peer"), true, false); }
    static Session waiting(RemoteTaskResultHook hook) {
        return new Session((c,o)->CompletableFuture.completedFuture(new RemoteCallOutcome("remote-task",TaskState.TASK_STATE_INPUT_REQUIRED,"INPUT_REQUIRED",null,"Remote callback pending")),null,hook,List.of("peer"),true,false);
    }
    static Map<?,?> callbackResult(Task task, RemoteTaskResultHook hook) {
        try(var s=waiting(hook)) {
            s.run(); assertThat(s.probe.received).hasSize(1);
            assertThat(s.runtime.onAccepted(new A2aPushNotificationCallback(UUID.randomUUID().toString(),task))).isTrue();
            s.run(); assertThat(s.probe.received).hasSize(2); return s.probe.results();
        }
    }
    static Task callbackTask(TaskState state, String text) {
        return Task.builder().id("remote-task").contextId("remote-context").status(state==null?null:new TaskStatus(state))
                .artifacts(List.of(Artifact.builder().artifactId("answer").parts(new TextPart(text)).build()))
                .metadata(Map.of("_agentcore_response_metadata",Map.of("response","meta"))).build();
    }
    @Test @Tag("f086-42") @Story("F086-42 callback promotion") @DisplayName("F086-42 非终态回调成功提升保留原状态")
    void f086_42() {
        // SDK rejects null status/state before the callback entry; retain this as a reported coverage limit.
        assertThatThrownBy(()->callbackTask(null,"business")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new TaskStatus(null)).isInstanceOf(IllegalArgumentException.class);
        for(TaskState state:List.of(TaskState.UNRECOGNIZED,TaskState.TASK_STATE_SUBMITTED,TaskState.TASK_STATE_WORKING)) {
            AtomicInteger count=new AtomicInteger(); Task task=callbackTask(state,"business");
            try(var s=waiting(t->{count.incrementAndGet();assertThat(t).isSameAs(task);return "hook";})) {
                s.run(); assertThat(s.runtime.onAccepted(new A2aPushNotificationCallback(UUID.randomUUID().toString(),task))).isTrue(); s.run();
                assertThat(s.probe.results().get("call-0")).isEqualTo("hook"); assertThat(count.get()).isEqualTo(1);
                assertThat(s.probe.received.getLast().getRemoteResponseMetadata()).isEmpty();
            }
        }
    }
    @Test @Tag("f086-43") @Story("F086-43 no promotion") @DisplayName("F086-43 非终态空文本不试调用结果 Hook")
    void f086_43() {
        AtomicInteger calls=new AtomicInteger();
        try(var s=waiting(t->{calls.incrementAndGet();return "must-not-promote";})) {
            s.run(); assertThat(s.runtime.onAccepted(new A2aPushNotificationCallback(UUID.randomUUID().toString(),callbackTask(TaskState.TASK_STATE_WORKING,"")))).isTrue();
            assertThat(calls.get()).isZero(); assertThat(s.probe.received).hasSize(1);
        }
    }
    @Test @Tag("f086-54") @Story("F086-54 callback null") @DisplayName("F086-54 回调 null 映射本地结果错误")
    void f086_54() { assertCallbackError(t->null); }
    @Test @Tag("f086-55") @Story("F086-55 callback exception") @DisplayName("F086-55 回调异常映射本地结果错误")
    void f086_55() { assertCallbackError(t->{throw new IllegalStateException("result-error");}); }
    void assertCallbackError(RemoteTaskResultHook hook) {
        var task=callbackTask(TaskState.TASK_STATE_COMPLETED,"fallback-forbidden");
        AtomicInteger requests=new AtomicInteger();
        try(var s=new Session((c,o)->{requests.incrementAndGet();return CompletableFuture.completedFuture(new RemoteCallOutcome(c.agentName().equals("a")?"remote-task":"other-task",TaskState.TASK_STATE_INPUT_REQUIRED,"INPUT_REQUIRED",null,"Remote callback pending"));},null,
                t->t.id().equals("remote-task")?hook.extract(t):"other-success",List.of("a","b"),true,false)) {
            s.run(); assertThat(s.probe.received).hasSize(1);
            assertThat(s.runtime.onAccepted(new A2aPushNotificationCallback("first-"+UUID.randomUUID(),task))).isTrue();
            assertThat(s.probe.received).hasSize(1);
            var other=Task.builder().id("other-task").contextId("remote-context").status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).build();
            assertThat(s.runtime.onAccepted(new A2aPushNotificationCallback("second-"+UUID.randomUUID(),other))).isTrue();
            s.run(); var result=s.probe.results();
            assertThat(result.get("call-0").toString()).contains("REMOTE_RESULT_HOOK_ERROR","ok=false").doesNotContain("remoteError=","responseMetadata=");
            assertThat(result.get("call-1")).isEqualTo("other-success"); assertThat(requests.get()).isEqualTo(2); assertThat(s.probe.received).hasSize(2);
        }
        assertThat(task.status().state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
    }
    @Test @Tag("f086-61") @Story("F086-61 replaced caller callback") @DisplayName("F086-61 替换 Caller 回调仍应用结果 Hook")
    void f086_61() {
        AtomicInteger count=new AtomicInteger();
        assertThat(callbackResult(callbackTask(TaskState.TASK_STATE_COMPLETED,"original"),t->{count.incrementAndGet();return "callback-hook";}).get("call-0")).isEqualTo("callback-hook");
        assertThat(count.get()).isEqualTo(1);
    }
    RemoteAgentCaller events(List<? extends StreamingEventKind> events) {
        return (call, observer) -> {
            try { for (var event : events) {
                if(event instanceof TaskArtifactUpdateEvent a) observer.onArtifact(a);
                if(event instanceof TaskStatusUpdateEvent s) observer.onStatus(s);
            } return CompletableFuture.completedFuture(done("private-result")); }
            catch (RuntimeException error) { return CompletableFuture.failedFuture(error); }
        };
    }
    static void normalBatchError(boolean throwing) throws Exception {
        var root=java.nio.file.Path.of("target/feature-acceptance-testing/feat086/batch-errors",UUID.randomUUID().toString());
        try(var bad=new Feat086A2aFixture(root.resolve("bad"),List.of(Map.of("task",Feat086A2aFixture.task("COMPLETED",List.of(),List.of(Feat086A2aFixture.text("bad"))))));
            var good=new Feat086A2aFixture(root.resolve("good"),List.of(Map.of("task",Feat086A2aFixture.task("COMPLETED",List.of(),List.of(Feat086A2aFixture.text("good"))))))) {
            var registry=new com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry(); registry.register("bad",bad.card(),10,false);registry.register("good",good.card(),10,false);
            List<Task> originals=new CopyOnWriteArrayList<>();
            var caller=new A2ARemoteAgentClient(registry,4,null,t->{originals.add(t);if(((TextPart)t.status().message().parts().getFirst()).text().equals("good"))return "other-success";if(throwing)throw new IllegalStateException("batch-canary");return null;});
            try(var s=new Session(caller,null,null,List.of("bad","good"),true,false)) {
                s.run();assertThat(s.probe.received).hasSize(2);
                assertThat(s.probe.results().get("call-0").toString()).contains("REMOTE_RESULT_HOOK_ERROR","ok=false").doesNotContain("remoteError=","responseMetadata=");
                assertThat(s.probe.results().get("call-1")).isEqualTo("other-success");
                assertThat(bad.requests).hasSize(1);assertThat(good.requests).hasSize(1);
                assertThat(originals).hasSize(2).allSatisfy(t->assertThat(t.status().state()).isEqualTo(TaskState.TASK_STATE_COMPLETED));
            } finally {caller.shutdown();}
        }
    }
    @Test @Tag("f086-13") @Story("F086-13 legacy constructor") @DisplayName("F086-13 旧构造默认展示与结果兼容")
    void f086_13() throws Exception {
        try (var s = new Session(events(List.of(artifact("a", "body", Map.of()))), null, null, List.of("peer"), true, true)) {
            s.run(); assertThat(s.output.artifacts()).hasSize(2); assertThat(s.probe.results().get("call-0")).isEqualTo("private-result");
        }
        try(var peer=new Feat086A2aFixture(java.nio.file.Path.of("target/feature-acceptance-testing/feat086/legacy",UUID.randomUUID().toString()),List.of(Map.of("task",Feat086A2aFixture.task("COMPLETED",List.of(Feat086A2aFixture.artifact("answer",List.of(Feat086A2aFixture.text("legacy-result")),Map.of())),List.of()))))) {
            var registry=new com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry(); registry.register("peer",peer.card(),10,false);
            var caller=new A2ARemoteAgentClient(registry);
            try { assertThat(caller.callOutcome(new RemoteCall("peer","input","context",null,Map.of()),null).get(15,TimeUnit.SECONDS).result()).isEqualTo("legacy-result"); }
            finally {caller.shutdown();}
        }
    }
    @Test @Tag("f086-17") @Story("F086-17 correlation") @DisplayName("F086-17 并发上下文关联隔离")
    void f086_17() throws Exception {
        List<RemoteProjectionContext> seen = new CopyOnWriteArrayList<>();
        RemoteProjectionHook hook = (e,c) -> { seen.add(c); return null; };
        try(var a = new Session(events(List.of(state("WORKING", "a"))), hook, null, List.of("peer-a"), true, false);
            var b = new Session(events(List.of(state("WORKING", "b"))), hook, null, List.of("peer-b"), true, false)) {
            b.request.setMetadata(Map.of("runtime.parentTaskId", "parent-b"));
            CompletableFuture.allOf(CompletableFuture.runAsync(a::run), CompletableFuture.runAsync(b::run)).get(15, TimeUnit.SECONDS);
            assertThat(seen).hasSize(4).allSatisfy(c -> { assertThat(c.parentAgentId()).isEqualTo("parent-agent"); assertThat(c.toolCallId()).isEqualTo("call-0");
                assertThat(c.parentTaskId()).isEqualTo(c.remoteAgentName().equals("peer-a") ? "parent-task" : "parent-b"); });
        }
    }
    @Test @Tag("f086-18") @Story("F086-18 custom identity") @DisplayName("F086-18 自定义 Artifact 不二次装饰")
    void f086_18() {
        Artifact custom = Artifact.builder().artifactId("custom-id").parts(new TextPart("custom-body")).metadata(Map.of("business", "own", "_agentcore_terminal", true)).build();
        try(var s=session(events(List.of(artifact("a","raw",Map.of()))), (e,c)-> delegation(e) ? null : custom)) {
            s.run(); assertThat(s.output.artifacts()).hasSize(1);
            assertThat(s.output.artifacts().getFirst().artifact()).isEqualTo(custom);
        }
    }
    @Test @Tag("f086-20") @Story("F086-20 projection error") @DisplayName("F086-20 投影异常传播不展示不默认回退")
    void f086_20() {
        try(var s=session(events(List.of(artifact("a","raw",Map.of()))), (e,c)-> { throw new IllegalStateException("projection-canary"); })) {
            s.run(); assertThat(s.output.artifacts()).isEmpty();
            assertThat(s.probe.results().get("call-0").toString()).contains("projection-canary", "ok=false");
        }
    }
    @Test @Tag("f086-21") @Story("F086-21 delegation consumption") @DisplayName("F086-21 null 委派仅消费一次")
    void f086_21() {
        AtomicInteger calls=new AtomicInteger();
        try(var s=session(events(List.of(state("WORKING","first"),artifact("a","raw",Map.of()),state("COMPLETED","done"))), (e,c)-> { if(delegation(e))calls.incrementAndGet(); return null; })) {
            s.run(); assertThat(calls.get()).isEqualTo(1); assertThat(s.probe.results().get("call-0")).isEqualTo("private-result");
        }
    }
    @Test @Tag("f086-22") @Story("F086-22 status dedup") @DisplayName("F086-22 隐藏状态仍参与去重")
    void f086_22() {
        AtomicInteger calls=new AtomicInteger();
        var second = new TaskStatusUpdateEvent("remote-task", new TaskStatus(TaskState.TASK_STATE_WORKING,
                Message.builder().role(Message.Role.ROLE_AGENT).parts(new TextPart("same")).build(), java.time.OffsetDateTime.now()),"remote-context",Map.of("different",true));
        try(var s=session(events(List.of(state("WORKING","same"),second)), (e,c)-> { if(e instanceof TaskStatusUpdateEvent)calls.incrementAndGet(); return null; })) {
            s.run(); assertThat(calls.get()).isEqualTo(1);
        }
    }
    @Test @Tag("f086-23") @Story("F086-23 changed status") @DisplayName("F086-23 同状态新内容均投影")
    void f086_23() {
        List<String> values=new CopyOnWriteArrayList<>();
        try(var s=session(events(List.of(state("WORKING","one"),state("WORKING","two"))), (e,c)-> { if(e instanceof TaskStatusUpdateEvent v)values.add(((TextPart)v.status().message().parts().getFirst()).text()); return null; })) {
            s.run(); assertThat(values).containsExactly("one","two");
        }
    }
    @Test @Tag("f086-24") @Story("F086-24 terminal suppression") @DisplayName("F086-24 隐藏终态后抑制所有后续事件")
    void f086_24() {
        List<StreamingEventKind> seen=new CopyOnWriteArrayList<>();
        try(var s=session(events(List.of(state("COMPLETED","done"),artifact("late","late",Map.of()),state("WORKING","late"))), (e,c)-> { seen.add(e); return null; })) {
            s.run(); assertThat(seen).hasSize(2); assertThat(seen.get(1)).isInstanceOf(TaskStatusUpdateEvent.class);
        }
    }
    @Test @Tag("f086-25") @Story("F086-25 default delegation") @DisplayName("F086-25 默认委派字段与空白文案回退")
    void f086_25() {
        for(String input:List.of("delegation-input"," "))try(var s=session(events(List.of(state("WORKING","progress"))),null)) {
            s.request.setMessages(List.of(Map.of("role","user","content",input))); s.run();
            var d=s.output.artifacts().getFirst(); var m=(Map<?,?>)d.artifact().metadata().get("agentEvent");
            assertThat(m.get("type")).isEqualTo("delegation"); assertThat(m.get("toolCallId")).isEqualTo("call-0");
            assertThat(m.get("source")).isEqualTo(Map.of("agentId","parent-agent","taskId","parent-task"));
            assertThat(m.get("target")).isEqualTo(Map.of("agentId","peer","taskId","remote-task"));
            assertThat(d.append()).isFalse(); assertThat(d.lastChunk()).isTrue();
            assertThat(d.artifact().artifactId()).isEqualTo("delegation:parent-task:remote-task");
            assertThat(((TextPart)d.artifact().parts().getFirst()).text()).isNotBlank();
            if(!input.isBlank())assertThat(((TextPart)d.artifact().parts().getFirst()).text()).isEqualTo(input);
        }
    }
    @Test @Tag("f086-26") @Story("F086-26 default artifact") @DisplayName("F086-26 默认 Artifact 副本字段与原对象隔离")
    void f086_26() {
        var original=new TaskArtifactUpdateEvent("remote-task",Artifact.builder().artifactId("id").name("name").description("description").parts(new TextPart("raw")).metadata(Map.of("_agentcore_terminal",true,"business","keep")).build(),"remote-context",true,false,Map.of("event-business","event-keep"));
        try(var s=session(events(List.of(original)),null)) {
            s.run(); var p=s.output.artifacts().get(1);
            assertThat(p.artifact().artifactId()).isEqualTo("remote:peer:remote-task:id");
            assertThat(p.artifact().metadata()).containsEntry("business","keep").doesNotContainKey("_agentcore_terminal");
            assertThat(p.artifact().parts()).isEqualTo(original.artifact().parts()); assertThat(p.append()).isTrue();
            assertThat(p.lastChunk()).isFalse();assertThat(p.metadata()).isEqualTo(original.metadata());
            assertThat(p.artifact().name()).isEqualTo("name");assertThat(p.artifact().description()).isEqualTo("description");
            assertThat(p.artifact().metadata().get("agentEvent")).isEqualTo(Map.of("type","output","source",Map.of("agentId","peer","taskId","remote-task")));
            assertThat(original.artifact().metadata()).containsEntry("_agentcore_terminal",true);
        }
    }
    @Test @Tag("f086-27") @Story("F086-27 existing source") @DisplayName("F086-27 来源标签与 ID 原值保留")
    void f086_27() {
        var original=artifact("original-id","raw",Map.of("agentEvent",Map.of("type","output","source",Map.of("agentId","leaf","taskId","leaf-task"))));
        try(var s=session(events(List.of(original)),null)) { s.run(); assertThat(s.output.artifacts().get(1).artifact()).isEqualTo(original.artifact()); }
    }
    @Test @Tag("f086-28") @Story("F086-28 status parts") @DisplayName("F086-28 Status 缺失消息回退而空文本不回退")
    void f086_28() {
        assertThatThrownBy(()->Message.builder().role(Message.Role.ROLE_AGENT).parts(List.of()).build()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Parts cannot be empty");
        for(String text:Arrays.asList(null,"","normal"))try(var s=session(events(List.of(state("WORKING",text))),null)) {
            s.run(); var status=s.output.artifacts().get(1);assertThat(((TextPart)status.artifact().parts().getFirst()).text()).isEqualTo(text==null?"working":text);
            assertThat(status.artifact().artifactId()).startsWith("status:peer:remote-task:");assertThat(status.append()).isFalse();assertThat(status.lastChunk()).isTrue();
            assertThat(status.artifact().metadata().get("agentEvent")).isEqualTo(Map.of("type","status","state","working","source",Map.of("agentId","peer","taskId","remote-task")));
        }
    }
    @Test @Tag("f086-56") @Story("F086-56 batch correlation") @DisplayName("F086-56 交错完成批次全部结算后按 ID 恢复一次")
    void f086_56() throws Exception {
        Map<String,CompletableFuture<RemoteCallOutcome>> futures=new ConcurrentHashMap<>();
        try(var s=new Session((c,o)->futures.computeIfAbsent(c.agentName(),k->new CompletableFuture<>()),null,null,List.of("a","b"),true,false)) {
            var running=CompletableFuture.runAsync(s::run);
            await().atMost(java.time.Duration.ofSeconds(5)).until(()->futures.size()==2);
            futures.get("b").complete(done("B")); assertThat(s.probe.received).hasSize(1);
            futures.get("a").complete(done("A")); running.get(10,TimeUnit.SECONDS);
            assertThat(s.probe.results()).isEqualTo(Map.of("call-0","A","call-1","B")); assertThat(s.probe.received).hasSize(2);
        }
    }
    @Test @Tag("f086-58") @Story("F086-58 waiting member") @DisplayName("F086-58 批次含等待成员不提前回灌成功项")
    void f086_58() {
        try(var s=new Session((c,o)->CompletableFuture.completedFuture(c.agentName().equals("a")?done("A"):
                new RemoteCallOutcome("waiting-task",TaskState.TASK_STATE_INPUT_REQUIRED,"INPUT_REQUIRED",null,"need-input")),null,null,List.of("a","b"),true,false)) {
            s.run(); assertThat(s.probe.received).hasSize(1); assertThat(s.output.chunks).anyMatch(c->c.getType().equals(QueryChunk.TYPE_INTERRUPT));
        }
    }
    @Test @Tag("f086-59") @Story("F086-59 replaced caller projection") @DisplayName("F086-59 替换 Caller 事件仍经 Hook")
    void f086_59() {
        AtomicInteger calls=new AtomicInteger();
        try(var s=session(events(List.of(artifact("a","raw",Map.of()))),(e,c)->{ calls.incrementAndGet(); return Artifact.builder().artifactId("custom").parts(new TextPart("edited")).build(); })) {
            s.run(); assertThat(calls.get()).isEqualTo(2); assertThat(s.output.artifacts()).allSatisfy(e->assertThat(e.artifact().artifactId()).isEqualTo("custom"));
        }
    }
    @Test @Tag("f086-60") @Story("F086-60 private result") @DisplayName("F086-60 替换 Caller 私有结果不自动进结果 Hook")
    void f086_60() {
        AtomicInteger calls=new AtomicInteger();
        try(var s=new Session(events(List.of()),null,t->{calls.incrementAndGet();return "wrong";},List.of("peer"),true,false)) {
            s.run(); assertThat(s.probe.results().get("call-0")).isEqualTo("private-result"); assertThat(calls.get()).isZero();
        }
    }
    @Test @Tag("f086-71") @Story("F086-71 direct reply") @DisplayName("F086-71 直接回复按批次顺序换行保留空白")
    void f086_71() {
        try(var s=new Session((c,o)->CompletableFuture.completedFuture(switch(c.agentName()) {
            case "a"->done("A"); case "empty"->done(""); case "null"->done(null);case "blank"->done(" "); case "failed"->new RemoteCallOutcome("fail",TaskState.TASK_STATE_FAILED,"REMOTE_BUSINESS_FAILURE","failed",null); default->done("B");
        }),null,null,List.of("a","failed","null","empty","blank","b"),false,false)) {
            s.request.setStream(false); var result=s.runtime.query(s.request); assertThat(((Map<?,?>)result.getResult()).get("content")).isEqualTo("A\n \nB"); assertThat(s.probe.received).hasSize(1);
        }
    }
}

package com.huawei.ascend.sit.cases.integration.react_travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.lifecycle.ProcessLauncher;
import com.huawei.ascend.sit.lifecycle.SutInstance;
import com.sun.net.httpserver.HttpServer;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.*;
import javax.net.ssl.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Public HTTP checks; the recorder forwards unchanged requests to the real configured LLM. */
@Tag("integration") @Tag("openjiuwen") @Tag("feat-086")
@Feature("FEAT-086 remote A2A response hooks")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class Feat086RemoteA2aResponseHookReactAgentTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HOOK = "openjiuwen.demo.remote-response.";
    private static final String REMOTE = "openjiuwen.service.a2a.remote-agents";
    private final String run = "feat086-" + UUID.randomUUID();
    private final String prefix = "F086_" + UUID.randomUUID() + ":";
    private final Path evidence = Path.of("target", "feature-acceptance-testing", "feat086", "observations", run);
    private final Map<String, Observation> observations = new LinkedHashMap<>();
    private LiveModelRecorder recorder;
    private SutStack stack;
    private TestConfig config;
    private final List<SutInstance> startedInstances = new ArrayList<>();
    private Feat086A2aFixture controlledPeer;
    private SutStack controlledParent;

    @BeforeAll void start() throws Exception {
        assertThat(System.getenv("LLM_API_KEY")).as("live LLM credential supplied through environment").isNotBlank();
        Files.createDirectories(evidence);
        recorder = new LiveModelRecorder(evidence);
        try {
            Map<String, Object> agents = new LinkedHashMap<>();
            for (String name : List.of("hotel", "trip", "mainplan")) {
                agents.put(name, Map.of("group", "com.openjiuwen.example", "artifact", "travel-demo-" + name,
                        "version", "0.1.2", "remote-agents-prefix", REMOTE));
            }
            agents.put("hosted-react", Map.of("group", "com.openjiuwen.example", "artifact",
                    "multi-react-travel-hosted-demo", "version", "0.1.1", "ready-mode", "tcp"));
            config = TestConfig.fromMap(Map.of("sut", Map.of("agents", agents,
                    "timeout", Map.of("startup-seconds", 120), "wire-log", Map.of("enabled", false))));
            stack = SutStack.builder(config)
                    .launcher((agent, options) -> {
                        SutInstance instance = new ProcessLauncher(config).start(agent, options);
                        startedInstances.add(instance);
                        return instance;
                    })
                    .agent("hotel", a -> model(a, "hotel"))
                    .agent("trip", a -> {
                        model(a, "trip");
                        a.downstream("hotel").property(REMOTE + "[0].name", "travel-hotel")
                                .property(REMOTE + "[0].streaming", "true");
                    })
                    .agent("mainplan", a -> {
                        model(a, "mainplan"); hooks(a);
                        a.downstream("trip").property(REMOTE + "[0].name", "travel-trip")
                                .property(REMOTE + "[0].streaming", "true");
                    })
                    .agent("hosted-react", a -> {
                        model(a, "hosted-react"); hooks(a);
                        String local = "openjiuwen.service.a2a.agents.agent-a.remote-agents[0]";
                        a.downstream("hotel", local + ".url").property(local + ".name", "travel-hotel")
                                .property(local + ".streaming", "true")
                                .property("openjiuwen.demo.hosted.max-iterations", "8");
                    }).start();
        } catch (Exception e) { stop(); throw e; }
    }
    private void model(SutStack.AgentBuilder a, String name) {
        String property = name.equals("hosted-react") ? "openjiuwen.demo.hosted.api-base"
                : "openjiuwen.travel." + name + ".llm.api-base";
        a.property(property, recorder.baseUrl(name));
        // The credential stays in inherited environment, never program arguments or fixtures.
    }
    private void hooks(SutStack.AgentBuilder a) {
        a.property(HOOK + "projection-enabled", "true").property(HOOK + "text-prefix", prefix)
                .property(HOOK + "show-delegation", "false").property(HOOK + "result-enabled", "true")
                .property(HOOK + "result-source", "artifacts").property(HOOK + "result-artifact-id", run + "-missing");
    }
    @AfterAll void stop() {
        if (controlledParent != null) controlledParent.close();
        if (controlledPeer != null) controlledPeer.close();
        if (stack != null) stack.close();
        for (SutInstance instance : startedInstances) instance.close();
        if (recorder != null) recorder.close();
    }
    @Test @Tag("f086-01") @Story("F086-01 Artifact edit") @DisplayName("F086-01 普通装配 Artifact 编辑")
    void f086_01() throws Exception { assertEdited(observe("mainplan"), "output"); }
    @Test @Tag("f086-02") @Story("F086-02 Status edit") @DisplayName("F086-02 普通装配 Status 编辑")
    void f086_02() throws Exception { assertEdited(observe("mainplan"), "status"); }
    @Test @Tag("f086-03") @Story("F086-03 Hide delegation") @DisplayName("F086-03 父层 delegation 隐藏后继续真实委派")
    void f086_03() throws Exception {
        Observation o = observe("mainplan");
        assertThat(o.remote()).isNotEmpty();
        assertThat(o.remote().stream().filter(n -> n.at("/metadata/agentEvent/type").asText().equals("delegation"))
                .filter(n -> n.at("/metadata/agentEvent/source/agentId").asText().equals("travel-mainplan")))
                .as("only parent delegation is filtered; downstream delegation may be forwarded").isEmpty();
        assertEmptyTool("mainplan", "travel-trip");
    }
    @Test @Tag("f086-04") @Story("F086-04 Exact empty ToolMessage") @DisplayName("F086-04 普通装配空 ToolMessage 精确回灌")
    void f086_04() throws Exception { observe("mainplan"); assertEmptyTool("mainplan", "travel-trip"); }
    @Test @Tag("f086-05") @Story("F086-05 Hosted Artifact edit") @DisplayName("F086-05 Hosted 真实远端 Artifact 编辑")
    void f086_05() throws Exception { assertEdited(observe("hosted-react"), "output"); }
    @Test @Tag("f086-06") @Story("F086-06 Hosted exact empty ToolMessage") @DisplayName("F086-06 Hosted 空 ToolMessage 精确回灌")
    void f086_06() throws Exception { observe("hosted-react"); assertEmptyTool("hosted-react", "travel-hotel"); }
    @Test @Tag("f086-07") @Story("F086-07 Query metadata clipping") @DisplayName("F086-07 Query SSE 只暴露投影内容与 agentEvent")
    void f086_07() throws Exception {
        List<JsonNode> frames = observe("mainplan").remote();
        assertThat(frames).isNotEmpty();
        for (JsonNode frame : frames) {
            Set<String> fields = new HashSet<>(); frame.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrder("content", "metadata");
            Set<String> metadata = new HashSet<>(); frame.path("metadata").fieldNames().forEachRemaining(metadata::add);
            assertThat(metadata).containsExactly("agentEvent");
        }
    }
    @Test @Tag("f086-08") @Story("F086-08 Default result compatibility") @DisplayName("F086-08 未配置结果 Hook 的 trip 保持非空工具结果")
    void f086_08() throws Exception {
        observe("mainplan");
        List<JsonNode> tools = toolMessages("trip", "travel-hotel");
        assertThat(tools).as("real hotel result reaches trip next model request").isNotEmpty();
        assertThat(tools).allSatisfy(t -> assertThat(t.path("content").asText()).isNotBlank());
    }
    @Test @Tag("f086-09") @Story("F086-09 Projection only") @DisplayName("F086-09 仅投影 Hook 保持默认结果")
    void f086_09() throws Exception {
        Observation o = variant("case09", Map.of("result-enabled", "false"));
        assertEdited(o, "output");
        assertNonemptyTool("case09");
    }
    @Test @Tag("f086-10") @Story("F086-10 Result only") @DisplayName("F086-10 仅结果 Hook 保持默认展示")
    void f086_10() throws Exception {
        Observation o = variant("case10", Map.of("projection-enabled", "false"));
        assertThat(o.remote()).isNotEmpty();
        assertThat(o.remote()).noneMatch(n -> hasPrefix(n.path("content")));
        assertThat(o.remote()).anyMatch(n -> n.at("/metadata/agentEvent/type").asText().equals("delegation"));
        assertEmptyTool("case10", "travel-trip");
    }
    @Test @Tag("f086-14") @Story("F086-14 Hide Artifact") @DisplayName("F086-14 隐藏 Artifact 仍完成原任务并回灌结果")
    void f086_14() throws Exception {
        Observation o = variant("case14", Map.of("show-artifacts", "false", "result-enabled", "false"));
        assertThat(o.remote()).isNotEmpty().noneMatch(n -> n.at("/metadata/agentEvent/type").asText().equals("output"));
        assertNonemptyTool("case14");
        assertRemoteCompleted(o);
    }
    @Test @Tag("f086-15") @Story("F086-15 Hide Status") @DisplayName("F086-15 隐藏 Status 仍按完成结算")
    void f086_15() throws Exception {
        Observation o = variant("case15", Map.of("show-status", "false", "result-enabled", "false"));
        assertThat(o.remote()).isNotEmpty();
        assertThat(o.remote().stream().filter(n -> n.at("/metadata/agentEvent/type").asText().equals("status")
                && n.at("/metadata/agentEvent/source/agentId").asText().equals("travel-trip")).count())
                .as("direct trip Status events are hidden; cascaded hotel status arrives as an Artifact").isZero();
        assertNonemptyTool("case15");
        assertRemoteCompleted(o);
    }
    @Test @Tag("f086-16") @Story("F086-16 Edit delegation") @DisplayName("F086-16 自定义 delegation 按调用仅一次并先于远端事件")
    void f086_16() throws Exception {
        Observation o = variant("case16", Map.of("show-delegation", "true", "result-enabled", "false"));
        List<JsonNode> delegation = o.remote().stream().filter(n ->
                n.at("/metadata/agentEvent/type").asText().equals("delegation")
                && n.at("/metadata/agentEvent/source/agentId").asText().equals("travel-mainplan")).toList();
        assertThat(delegation).isNotEmpty();
        Set<String> toolIds = new HashSet<>();
        for (JsonNode event : delegation) {
            assertThat(hasPrefix(event.path("content"))).isTrue();
            String toolId = event.at("/metadata/agentEvent/toolCallId").asText();
            assertThat(toolId).isNotBlank();
            assertThat(toolIds.add(toolId)).as("one delegation per tool call").isTrue();
            String taskId = event.at("/metadata/agentEvent/target/taskId").asText();
            Optional<JsonNode> firstRemote = o.remote().stream().filter(n ->
                    n.at("/metadata/agentEvent/source/taskId").asText().equals(taskId)).findFirst();
            assertThat(firstRemote).isPresent();
            assertThat(o.remote().indexOf(event)).isLessThan(o.remote().indexOf(firstRemote.orElseThrow()));
        }
    }
    private Observation variant(String key, Map<String, String> overrides) throws Exception {
        try (SutStack parent = SutStack.builder(config).remoteAgent("trip", stack.baseUrl("trip"))
                .agent("mainplan", a -> {
                    hooks(a);
                    a.property("openjiuwen.travel.mainplan.llm.api-base", recorder.baseUrl(key));
                    a.downstream("trip").property(REMOTE + "[0].name", "travel-trip")
                            .property(REMOTE + "[0].streaming", "true");
                    overrides.forEach((k, v) -> a.property(HOOK + k, v));
                }).start()) {
            return query(parent, "mainplan", key);
        }
    }
    @Test @Tag("f086-11") @Story("F086-11 duplicate projection") @DisplayName("F086-11 普通和 Hosted 重复投影 Bean 启动失败")
    void f086_11() throws Exception { duplicateBean("projection"); }
    @Test @Tag("f086-12") @Story("F086-12 duplicate result") @DisplayName("F086-12 普通和 Hosted 重复结果 Bean 启动失败")
    void f086_12() throws Exception { duplicateBean("result"); }
    private void duplicateBean(String kind) throws Exception {
        for(String agent:List.of("mainplan","hosted-react")) {
            Path log=Path.of("target/sit-logs",agent,"stdout.log"); long before=Files.exists(log)?Files.size(log):0;
            try {
            org.assertj.core.api.Assertions.assertThatThrownBy(()-> {
                try(SutStack duplicate=SutStack.builder(config).agent(agent,a->{model(a,agent);a.profile("feat086-acceptance").property(HOOK+kind+"-enabled","true")
                        .readyMode(com.huawei.ascend.sit.lifecycle.AgentConfig.ReadyMode.AGENT_CARD);}).start()) {
                    throw new AssertionError("Duplicate hooks unexpectedly started");
                }
            }).isInstanceOf(IllegalStateException.class);
            } finally {
            byte[] data=Files.readAllBytes(log); String current=new String(Arrays.copyOfRange(data,(int)Math.min(before,data.length),data.length),java.nio.charset.StandardCharsets.UTF_8);
            Files.writeString(evidence.resolve("duplicate-"+kind+"-"+agent+".log"),current);
            }
            String current=Files.readString(evidence.resolve("duplicate-"+kind+"-"+agent+".log"));
            assertThat(current).contains("NoUniqueBeanDefinitionException");
            assertThat(current).contains(kind.equals("projection")?"RemoteProjectionHook":"RemoteTaskResultHook");
        }
    }
    private void controlled() throws Exception {
        if (controlledParent != null) return;
        controlledPeer = new Feat086A2aFixture(evidence.resolve("controlled-peer"), List.of());
        assertThat(org.springframework.web.client.RestClient.create().get().uri(controlledPeer.baseUrl()+"/.well-known/agent-card.json")
                .retrieve().body(org.a2aproject.sdk.spec.AgentCard.class)).isNotNull();
        controlledParent = SutStack.builder(config).remoteAgent("controlled", controlledPeer.baseUrl())
                .agent("mainplan", a -> a.profile("feat086-acceptance")
                        .property("openjiuwen.travel.mainplan.llm.api-base", recorder.baseUrl("controlled"))
                        .downstream("controlled").property(REMOTE + "[0].name", "travel-trip")
                        .property(REMOTE + "[0].streaming", "true").property(REMOTE + "[0].timeout-seconds", "30"))
                .start();
    }
    private JsonNode probe(Map<String,String> mode) throws Exception {
        controlled();
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create(controlledParent.baseUrl("mainplan") + "/feat086/probe"));
        if(mode != null) r.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(mode)));
        var response=HttpClient.newHttpClient().send(r.build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200); return JSON.readTree(response.body());
    }
    private Observation controlledQuery(String key, Map<String,String> mode, List<Map<String,Object>> frames) throws Exception {
        probe(mode); recorder.requests.remove("controlled"); controlledPeer.requests.clear(); controlledPeer.replies=r->frames;
        Observation result=query(controlledParent,"mainplan",key);
        Files.writeString(evidence.resolve(key+"-probe.json"),probe(null).toString());
        Files.writeString(evidence.resolve(key+"-model-requests.json"),JSON.writeValueAsString(recorder.requests.getOrDefault("controlled",List.of())));
        Files.writeString(evidence.resolve(key+"-remote-requests.json"),JSON.writeValueAsString(controlledPeer.requests));
        assertThat(controlledPeer.errors).isEmpty(); assertThat(controlledPeer.requests).isNotEmpty();
        return result;
    }
    private List<Map<String,Object>> completed(String text) {
        return List.of(Feat086A2aFixture.status("WORKING","progress"), Feat086A2aFixture.update("answer",text,false),Feat086A2aFixture.status("COMPLETED","status-result"));
    }
    private void exactControlledTool(String expected) {
        assertThat(toolMessages("controlled","travel-trip")).isNotEmpty().allSatisfy(t->assertThat(t.path("content").asText()).isEqualTo(expected));
    }
    @Test @Tag("f086-19") @Story("F086-19 null metadata") @DisplayName("F086-19 自定义 null metadata 的 Query SSE 出口")
    void f086_19() throws Exception {
        var o=controlledQuery("case19",Map.of("projection","null-metadata","result","literal","literal","result"),completed("original"));
        assertThat(o.remote()).isNotEmpty().allSatisfy(n->{assertThat(n.at("/metadata/agentEvent").isNull()).isTrue(); assertThat(n.path("content").toString()).contains("F086-custom-body");});
        exactControlledTool("result");
    }
    @Test @Tag("f086-29") @Story("F086-29 parent wire context") @DisplayName("F086-29 A2A 外层父上下文与远端 source")
    void f086_29() throws Exception {
        probe(Map.of("result","literal","literal","answer")); controlledPeer.replies=r->completed("remote-body");
        String body=JSON.writeValueAsString(Map.of("jsonrpc","2.0","id","case29","method","SendStreamingMessage","params",Map.of("message",Map.of("messageId",UUID.randomUUID().toString(),"role","ROLE_USER","contextId",run+"-case29","parts",List.of(Map.of("text","请实际调用 travel-trip。张三深圳到北京出差，2026-11-10至12日，两晚每晚800元，四星，查询酒店，不预订。"))))));
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(controlledParent.baseUrl("mainplan")+"/a2a")).timeout(Duration.ofSeconds(180)).header("Content-Type","application/json").header("Accept","text/event-stream").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        Files.writeString(evidence.resolve("case29-a2a.sse"),response.body()); assertThat(response.statusCode()).isEqualTo(200);
        List<JsonNode> updates=new ArrayList<>(); Set<String> parentIds=new HashSet<>();
        for(String line:response.body().split("\\R"))if(line.startsWith("data:")){var frame=JSON.readTree(line.substring(5).trim()).path("result");
            if(frame.has("statusUpdate"))parentIds.add(frame.at("/statusUpdate/taskId").asText());
            if(frame.at("/artifactUpdate/artifact/metadata/agentEvent/source/taskId").asText().equals("remote-task"))updates.add(frame.path("artifactUpdate"));}
        assertThat(parentIds).hasSize(1).doesNotContain("remote-task"); assertThat(updates).isNotEmpty().allSatisfy(u->{assertThat(u.path("taskId").asText()).isIn(parentIds); assertThat(u.path("contextId").asText()).isEqualTo(run+"-case29");});
    }
    @Test @Tag("f086-32") @Story("F086-32 artifact source") @DisplayName("F086-32 结果 Hook 读取带展示标签的原始 Artifact")
    void f086_32() throws Exception {
        var task=Feat086A2aFixture.task("COMPLETED",List.of(Feat086A2aFixture.artifact("business",List.of(Feat086A2aFixture.text("tagged-business")),Map.of("agentEvent",Map.of("type","output")))),List.of(Feat086A2aFixture.text("status")));
        controlledQuery("case32",Map.of("result","artifacts"),List.of(Map.of("task",task))); exactControlledTool("tagged-business");
    }
    @Test @Tag("f086-33") @Story("F086-33 status source") @DisplayName("F086-33 Status 文本精确回灌")
    void f086_33() throws Exception { controlledQuery("case33",Map.of("result","status"),completed("artifact")); exactControlledTool("status-result"); }
    @Test @Tag("f086-34") @Story("F086-34 history source") @DisplayName("F086-34 History 消息精确回灌")
    void f086_34() throws Exception {
        var task=Feat086A2aFixture.task("COMPLETED",List.of(),List.of(Feat086A2aFixture.text("status")));
        task.put("history",List.of(Feat086A2aFixture.message(List.of(Feat086A2aFixture.text("history-A"))),Feat086A2aFixture.message(List.of(Feat086A2aFixture.text("history-B")))));
        controlledQuery("case34",Map.of("result","history"),List.of(Map.of("task",task))); exactControlledTool("history-Ahistory-B");
    }
    @Test @Tag("f086-35") @Story("F086-35 literal preservation") @DisplayName("F086-35 空白换行字符串原值回灌")
    void f086_35() throws Exception { controlledQuery("case35",Map.of("result","literal","literal","  exact\nvalue\t "),completed("fallback")); exactControlledTool("  exact\nvalue\t "); }
    @Test @Tag("f086-52") @Story("F086-52 batch null") @DisplayName("F086-52 普通调用 null 结果可识别本地错误")
    void f086_52() throws Exception { controlledError("case52","null"); }
    @Test @Tag("f086-53") @Story("F086-53 batch exception") @DisplayName("F086-53 普通调用结果异常无回退不重发")
    void f086_53() throws Exception { controlledError("case53","throw"); }
    private void controlledError(String key,String mode) throws Exception {
        Feat086ProjectionBatchContractTest.normalBatchError(mode.equals("throw"));
        controlledQuery(key,Map.of("result",mode),completed("fallback-forbidden"));
        assertThat(toolMessages("controlled","travel-trip")).isNotEmpty().allSatisfy(t->assertThat(t.path("content").asText()).contains("REMOTE_RESULT_HOOK_ERROR").doesNotContain("remoteError","responseMetadata"));
        JsonNode p=probe(null); assertThat(p.path("tasks").size()).isEqualTo(controlledPeer.requests.size());
        for(JsonNode task:p.path("tasks"))assertThat(task.asText()).contains("COMPLETED");
    }
    @Test @Tag("f086-57") @Story("F086-57 chunk aggregation") @DisplayName("F086-57 多片段聚合成单次工具结果")
    void f086_57() throws Exception {
        controlledQuery("case57",Map.of("result","artifacts"),List.of(Feat086A2aFixture.status("WORKING","start"),Feat086A2aFixture.update("a","A",false),Feat086A2aFixture.update("a","B",true),Feat086A2aFixture.status("COMPLETED","done")));
        exactControlledTool("AB"); assertThat(probe(null).path("tasks").size()).isEqualTo(controlledPeer.requests.size());
    }
    @Test @Tag("f086-72") @Story("F086-72 Hosted isolation") @DisplayName("F086-72 Hosted A B 并发远端结果隔离")
    void f086_72() throws Exception {
        Path hostedLog=Path.of("target/sit-logs/hosted-react/stdout.log");long logStart=Files.exists(hostedLog)?Files.size(hostedLog):0;
        try(var aPeer=new Feat086A2aFixture(evidence.resolve("hosted-a-peer"),List.of(Map.of("task",Feat086A2aFixture.task("COMPLETED",List.of(Feat086A2aFixture.artifact("a",List.of(Feat086A2aFixture.text("ONLY_AGENT_A")),Map.of())),List.of(Feat086A2aFixture.text("done"))))));
            var bPeer=new Feat086A2aFixture(evidence.resolve("hosted-b-peer"),List.of(Map.of("task",Feat086A2aFixture.task("COMPLETED",List.of(Feat086A2aFixture.artifact("b",List.of(Feat086A2aFixture.text("ONLY_AGENT_B")),Map.of())),List.of(Feat086A2aFixture.text("done"))))));
            var hosted=SutStack.builder(config).remoteAgent("aPeer",aPeer.baseUrl()).remoteAgent("bPeer",bPeer.baseUrl()).agent("hosted-react",a->{
                a.profile("feat086-acceptance").property("openjiuwen.demo.hosted.api-base",recorder.baseUrl("isolation")).property("openjiuwen.demo.hosted.max-iterations","8");
                for(String id:List.of("a","b")) {String local="openjiuwen.service.a2a.agents.agent-"+id+".remote-agents[0]";a.downstream(id+"Peer",local+".url").property(local+".name","travel-hotel").property(local+".streaming","true");}
            }).start()) {
            // TCP readiness precedes asynchronous per-agent discovery; complete the fixture Given first.
            long hostedPid=((com.huawei.ascend.sit.lifecycle.ManagedSutInstance)hosted.managedInstance("hosted-react")).pid();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(200)).until(()->{
                byte[] bytes=Files.readAllBytes(hostedLog);
                String current=new String(Arrays.copyOfRange(bytes,(int)logStart,bytes.length),java.nio.charset.StandardCharsets.UTF_8);
                return current.lines().filter(line->line.contains(" "+hostedPid+" ---")&&line.contains("Discovered remote agent 'travel-hotel'")).count()==2;
            });
            Map<String,CompletableFuture<String>> replies=new LinkedHashMap<>();
            for(String id:List.of("a","b"))replies.put(id,CompletableFuture.supplyAsync(()->{
                try{return hostedQuery(hosted,"agent-"+id,"isolation-"+id);}catch(Exception e){throw new CompletionException(e);}
            }));
            for(var entry:replies.entrySet()) {String response=entry.getValue().get(180,TimeUnit.SECONDS);Files.writeString(evidence.resolve("isolation-"+entry.getKey()+".sse"),response);}
            List<JsonNode> tools=toolMessages("isolation","travel-hotel"); assertThat(tools).hasSize(2);
            assertThat(tools.stream().map(t->t.path("content").asText()).toList()).containsExactlyInAnyOrder("ONLY_AGENT_A","ONLY_AGENT_B");
            assertThat(tools.stream().map(t->t.path("tool_call_id").asText()).distinct().count()).isEqualTo(2);
            for(String id:List.of("a","b")) {
                List<JsonNode> ownTools=new ArrayList<>();
                for(JsonNode request:recorder.requests.getOrDefault("isolation",List.of())) {
                    boolean own=false;for(JsonNode message:request.path("messages"))if(message.path("role").asText().equals("user")&&message.path("content").asText().contains("F086_SESSION_isolation-"+id))own=true;
                    if(own)for(JsonNode message:request.path("messages"))if(message.path("role").asText().equals("tool"))ownTools.add(message);
                }
                assertThat(ownTools).isNotEmpty().allSatisfy(t->assertThat(t.path("content").asText()).isEqualTo("ONLY_AGENT_"+id.toUpperCase(java.util.Locale.ROOT)));
            }
            var p=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(hosted.baseUrl("hosted-react")+"/feat086/probe")).build(),HttpResponse.BodyHandlers.ofString());
            Files.writeString(evidence.resolve("isolation-probe.json"),p.body());
            Set<String> parentAgents=new HashSet<>(); for(JsonNode event:JSON.readTree(p.body()).path("events"))parentAgents.add(event.path("parentAgentId").asText());
            assertThat(parentAgents).hasSize(2); assertThat(aPeer.requests).hasSize(1);assertThat(bPeer.requests).hasSize(1);
        }
    }
    private String hostedQuery(SutStack target,String id,String key) throws Exception {
        var body=Map.of("agent_id",id,"conversation_id",run+key,"stream",true,"messages",List.of(Map.of("role","user","content","F086_SESSION_"+key+" 必须实际调用 travel-hotel 远程工具查询：张三深圳到北京出差，2026年11月10日至12日，两晚每晚800元，四星国贸附近；拿到结果后直接总结，不要重复调用。")));
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(target.baseUrl("hosted-react")+"/v1/query")).timeout(Duration.ofSeconds(180)).header("Content-Type","application/json").header("Accept","text/event-stream").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);return response.body();
    }
    private record CallbackObservation(JsonNode pending,JsonNode before,List<JsonNode> tools,int requests) { }
    private CallbackObservation callbackObservation;
    private CallbackObservation callbackRun() throws Exception {
        if(callbackObservation!=null)return callbackObservation;
        try(var peer=new Feat086A2aFixture(evidence.resolve("callback-peer"),List.of(Map.of("task",Feat086A2aFixture.task("WORKING",List.of(Feat086A2aFixture.artifact("partial",List.of(Feat086A2aFixture.text("partial-must-wait")),Map.of())),List.of(Feat086A2aFixture.text("working"))))));
            var parent=SutStack.builder(config).remoteAgent("callback-peer",peer.baseUrl()).agent("mainplan",a->a.profile("feat086-acceptance")
                    .property("openjiuwen.travel.mainplan.llm.api-base",recorder.baseUrl("callback"))
                    .property("openjiuwen.service.a2a.push-notifications","true")
                    .property(HOOK+"callback-enabled","true").property(HOOK+"callback-url",peer.baseUrl()+"/callback")
                    .downstream("callback-peer").property(REMOTE+"[0].name","travel-trip").property(REMOTE+"[0].streaming","false")).start()) {
            JsonNode pending=sendA2a(parent,"callback",null);
            assertThat(pending.at("/status/state").asText()).isEqualTo("TASK_STATE_INPUT_REQUIRED");
            assertThat(toolMessages("callback","travel-trip")).isEmpty();
            JsonNode before=readProbe(parent); assertThat(before.path("tasks").size()).isZero();
            assertThat(peer.requests).hasSize(1); assertThat(peer.requests.getFirst().at("/params/configuration/taskPushNotificationConfig/url").asText()).isEqualTo(peer.baseUrl()+"/callback");
            var task=Feat086A2aFixture.task("COMPLETED",List.of(Feat086A2aFixture.artifact("answer",List.of(Feat086A2aFixture.text("callback-exact-result")),Map.of())),List.of(Feat086A2aFixture.text("done")));
            var body=Map.of("jsonrpc","2.0","id","callback","notificationId",run+"-notify","result",Map.of("task",task));
            var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(parent.baseUrl("mainplan")+"/a2a/push-notifications/callback")).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
            Files.writeString(evidence.resolve("callback-accepted.json"),response.body());assertThat(response.statusCode()).isEqualTo(200);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofMillis(250)).untilAsserted(()->assertThat(toolMessages("callback","travel-trip")).isNotEmpty());
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(120)).untilAsserted(()->assertThat(parent.client("mainplan").getTask(pending.path("id").asText()).status().state()).isEqualTo(org.a2aproject.sdk.spec.TaskState.TASK_STATE_COMPLETED));
            var after=readProbe(parent);Files.writeString(evidence.resolve("callback-probe.json"),after.toString());assertThat(after.path("tasks").size()).isEqualTo(1);
            callbackObservation=new CallbackObservation(pending,before,List.copyOf(toolMessages("callback","travel-trip")),peer.requests.size());
            return callbackObservation;
        }
    }
    private JsonNode readProbe(SutStack parent) throws Exception {
        var r=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(parent.baseUrl("mainplan")+"/feat086/probe")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200); return JSON.readTree(r.body());
    }
    private JsonNode sendA2a(SutStack parent,String key,JsonNode previous) throws Exception {
        Map<String,Object> message=new LinkedHashMap<>();message.put("messageId",UUID.randomUUID().toString());message.put("role","ROLE_USER");
        message.put("contextId",previous==null?run+"-"+key:previous.path("contextId").asText());
        if(previous!=null)message.put("taskId",previous.path("id").asText());
        message.put("parts",List.of(Map.of("text",previous==null?"请实际调用 travel-trip 查询张三深圳到北京出差方案，2026年11月10日至12日，两晚每晚800元，四星国贸，所有信息齐全，不预订。":"已确认，可以继续查询。")));
        var body=Map.of("jsonrpc","2.0","id",key,"method","SendMessage","params",Map.of("message",message));
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(parent.baseUrl("mainplan")+"/a2a")).timeout(Duration.ofSeconds(180)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        Files.writeString(evidence.resolve(key+"-"+(previous==null?"initial":"resumed")+".json"),response.body());assertThat(response.statusCode()).isEqualTo(200);
        JsonNode task=JSON.readTree(response.body()).at("/result/task");assertThat(task.path("id").asText()).isNotBlank();return task;
    }
    @Test @Tag("f086-41") @Story("F086-41 callback completed") @DisplayName("F086-41 完成回调自动恢复真实父 Agent 一次")
    void f086_41() throws Exception { var o=callbackRun();assertThat(o.tools()).hasSize(1);assertThat(o.tools().getFirst().path("content").asText()).isEqualTo("callback-exact-result");assertThat(o.requests()).isEqualTo(1); }
    @Test @Tag("f086-47") @Story("F086-47 callback pending") @DisplayName("F086-47 等待回调无部分成功结果回灌")
    void f086_47() throws Exception {var o=callbackRun();assertThat(o.pending().toString()).contains("Remote callback pending");assertThat(o.before().path("tasks").size()).isZero();}
    @Test @Tag("f086-49") @Story("F086-49 interruption recovery") @DisplayName("F086-49 恢复后原 Task 的旧 Artifact 仍可提取")
    void f086_49() throws Exception {
        controlled();probe(Map.of("result","artifacts"));recorder.requests.remove("controlled");controlledPeer.requests.clear();
        controlledPeer.replies=r->{boolean resumed=!r.at("/params/message/taskId").asText().isBlank();return List.of(Map.of("task",Feat086A2aFixture.task(resumed?"COMPLETED":"INPUT_REQUIRED",resumed?List.of(Feat086A2aFixture.artifact("old",List.of(Feat086A2aFixture.text("OLD")),Map.of()),Feat086A2aFixture.artifact("new",List.of(Feat086A2aFixture.text("NEW")),Map.of())):List.of(Feat086A2aFixture.artifact("old",List.of(Feat086A2aFixture.text("OLD")),Map.of())),List.of(Feat086A2aFixture.text(resumed?"done":"请确认继续")))));};
        JsonNode first=sendA2a(controlledParent,"case49",null);assertThat(first.at("/status/state").asText()).isEqualTo("TASK_STATE_INPUT_REQUIRED");assertThat(probe(null).path("tasks").size()).isZero();assertThat(toolMessages("controlled","travel-trip")).isEmpty();
        JsonNode last=sendA2a(controlledParent,"case49",first);assertThat(last.at("/status/state").asText()).isEqualTo("TASK_STATE_COMPLETED");exactControlledTool("OLDNEW");assertThat(controlledPeer.requests).hasSize(2);assertThat(controlledPeer.requests.getLast().at("/params/message/taskId").asText()).isEqualTo("remote-task");
    }
    @Test @Tag("f086-36") @Story("F086-36 Streaming both enabled") @DisplayName("F086-36 双流式实际流式调用与成功结果")
    void f086_36() throws Exception { streamingVariant("case36", true, true); }
    @Test @Tag("f086-37") @Story("F086-37 Inbound nonstreaming") @DisplayName("F086-37 入口非流式不补造远端投影")
    void f086_37() throws Exception { streamingVariant("case37", false, true); }
    @Test @Tag("f086-38") @Story("F086-38 Remote nonstreaming") @DisplayName("F086-38 远端非流式不补造远端投影")
    void f086_38() throws Exception { streamingVariant("case38", true, false); }
    @Test @Tag("f086-39") @Story("F086-39 Both nonstreaming") @DisplayName("F086-39 双非流式保持成功结果 Hook")
    void f086_39() throws Exception { streamingVariant("case39", false, false); }
    private void streamingVariant(String key, boolean inbound, boolean outbound) throws Exception {
        try (RemoteRecorder remote = new RemoteRecorder(stack.baseUrl("trip"), evidence.resolve(key + "-a2a-methods.txt"));
             SutStack parent = SutStack.builder(config).remoteAgent("trip", remote.baseUrl())
                     .agent("mainplan", a -> {
                         hooks(a);
                         a.property("openjiuwen.travel.mainplan.llm.api-base", recorder.baseUrl(key))
                                 .property(HOOK + "show-delegation", "true")
                                 .downstream("trip").property(REMOTE + "[0].name", "travel-trip")
                                 .property(REMOTE + "[0].streaming", Boolean.toString(outbound));
                     }).start()) {
            Observation o = query(parent, "mainplan", key, inbound);
            assertEmptyTool(key, "travel-trip");
            boolean streaming = inbound && outbound;
            assertThat(remote.methods).as("actual remote HTTP method must match the switch conjunction")
                    .anyMatch(m -> streaming ? isStreamingMethod(m) : isSyncMethod(m));
            assertThat(remote.methods).noneMatch(m -> streaming ? isSyncMethod(m) : isStreamingMethod(m));
            if (streaming) {
                assertEdited(o, "output");
                assertEdited(o, "delegation");
            } else {
                assertThat(o.remote()).as("no synthetic remote projection for a complete Task").isEmpty();
                assertThat(o.body()).doesNotContain(prefix);
            }
        }
    }
    private boolean isStreamingMethod(String method) {
        return method.equalsIgnoreCase("SendStreamingMessage") || method.equals("message/stream");
    }
    private boolean isSyncMethod(String method) {
        return method.equalsIgnoreCase("SendMessage") || method.equals("message/send");
    }
    private void assertNonemptyTool(String key) {
        List<JsonNode> tools = toolMessages(key, "travel-trip");
        assertThat(tools).isNotEmpty();
        assertThat(tools).allSatisfy(t -> assertThat(t.path("content").asText()).isNotBlank());
    }
    private void assertRemoteCompleted(Observation o) throws Exception {
        String id = o.remote().stream().filter(n -> n.at("/metadata/agentEvent/source/agentId").asText().equals("travel-trip"))
                .map(n -> n.at("/metadata/agentEvent/source/taskId").asText()).filter(s -> !s.isBlank()).findFirst().orElseThrow();
        org.a2aproject.sdk.spec.Task task = stack.client("trip").getTask(id);
        assertThat(task.id()).isEqualTo(id);
        assertThat(task.status().state()).isEqualTo(org.a2aproject.sdk.spec.TaskState.TASK_STATE_COMPLETED);
        assertThat(task.artifacts()).isNotEmpty();
        Files.writeString(evidence.resolve("trip-task-summary-" + id + ".json"), JSON.writeValueAsString(Map.of(
                "taskId", task.id(), "state", task.status().state().name(), "artifactCount", task.artifacts().size(),
                "artifactIds", task.artifacts().stream().map(org.a2aproject.sdk.spec.Artifact::artifactId).toList())));
    }
    private void assertEdited(Observation o, String kind) {
        List<JsonNode> frames = o.remote().stream()
                .filter(n -> n.at("/metadata/agentEvent/type").asText().equals(kind)).toList();
        assertThat(frames).as("real remote %s event required", kind).isNotEmpty();
        assertThat(frames.stream().anyMatch(n -> hasPrefix(n.path("content"))))
                .as("textual %s event contains projection prefix", kind).isTrue();
    }
    private boolean hasPrefix(JsonNode content) {
        if (content.isTextual()) return content.textValue().startsWith(prefix);
        if (content.isArray()) {
            for (JsonNode part : content) if (part.path("text").asText().startsWith(prefix)) return true;
        }
        return false;
    }
    private void assertEmptyTool(String agent, String remote) {
        List<JsonNode> tools = toolMessages(agent, remote);
        assertThat(tools).as("next real model request retains remote tool ID").isNotEmpty();
        for (JsonNode tool : tools) {
            assertThat(tool.path("tool_call_id").asText()).isNotBlank();
            assertThat(tool.has("content")).isTrue();
            assertThat(tool.get("content").isTextual()).isTrue();
            assertThat(tool.get("content").textValue()).isEmpty();
        }
    }
    private List<JsonNode> toolMessages(String agent, String remote) {
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode request : recorder.requests.getOrDefault(agent, List.of())) {
            Set<String> ids = new HashSet<>();
            for (JsonNode msg : request.path("messages")) for (JsonNode call : msg.path("tool_calls")) {
                if (call.at("/function/name").asText().equals(remote)) ids.add(call.path("id").asText());
            }
            for (JsonNode msg : request.path("messages")) {
                if (msg.path("role").asText().equals("tool") && ids.contains(msg.path("tool_call_id").asText())) found.add(msg);
            }
        }
        return found;
    }
    private Observation observe(String name) throws Exception {
        if (observations.containsKey(name)) return observations.get(name);
        return query(stack, name, name);
    }
    private Observation query(SutStack target, String name, String key) throws Exception {
        return query(target, name, key, true);
    }
    private Observation query(SutStack target, String name, String key, boolean streaming) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversation_id", run + "-" + key); body.put("stream", streaming);
        String prompt = (name.equals("mainplan") ? "请实际调用远程 travel-trip 工具规划出差，并让它实际调用 travel-hotel 查询酒店。"
                : "必须实际调用 travel-hotel 远程工具查询酒店，不能凭知识直接回答。")
                + "出差人张三，从深圳到北京，2026-11-10入住、2026-11-12退房，两晚每晚800元人民币，"
                + "最低四星，国贸附近，偏好全季或亚朵。商务出差，所有必要信息已齐全，查询即可不预订不付款。";
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        if (name.equals("hosted-react")) body.put("agent_id", "agent-a");
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create(target.baseUrl(name) + "/v1/query")).timeout(Duration.ofSeconds(360))
                .header("Content-Type", "application/json").header("Accept", streaming ? "text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        Files.writeString(evidence.resolve(key + "-query.sse"), response.body());
        List<JsonNode> remote = new ArrayList<>();
        for (String line : response.body().split("\\R")) {
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.equals("[DONE]") || data.isEmpty()) continue;
            JsonNode frame = JSON.readTree(data);
            // QuerySseSupport emits remote payloads directly, without an event: line or type wrapper.
            if (frame.has("content") && frame.path("metadata").has("agentEvent")) remote.add(frame);
        }
        Observation observation = new Observation(response.statusCode(), response.body(), List.copyOf(remote));
        observations.put(key, observation);
        assertThat(observation.http()).as("query saved in %s", evidence).isEqualTo(200);
        return observation;
    }
    private record Observation(int http, String body, List<JsonNode> remote) { }

    /** A routing-only recorder. Real trip receives every request; no Task or event is synthesized. */
    private static final class RemoteRecorder implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final List<String> methods = new CopyOnWriteArrayList<>();
        RemoteRecorder(String target, Path evidence) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
            server.createContext("/", exchange -> {
                try {
                    byte[] request = exchange.getRequestBody().readAllBytes();
                    if (request.length > 0) {
                        JsonNode body = JSON.readTree(request);
                        String method = body.path("method").asText();
                        methods.add(method);
                        Files.writeString(evidence, method + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    }
                    HttpRequest.Builder forward = HttpRequest.newBuilder(URI.create(target + exchange.getRequestURI()))
                            .timeout(Duration.ofSeconds(330)).header("Content-Type", "application/json");
                    forward.header("Accept", Optional.ofNullable(exchange.getRequestHeaders().getFirst("Accept")).orElse("*/*"));
                    forward.method(exchange.getRequestMethod(), request.length == 0
                            ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(request));
                    HttpResponse<java.io.InputStream> reply = http.send(forward.build(), HttpResponse.BodyHandlers.ofInputStream());
                    exchange.getResponseHeaders().set("Content-Type", reply.headers().firstValue("Content-Type").orElse("application/json"));
                    if (exchange.getRequestMethod().equals("GET")) {
                        // The advertised endpoint must route subsequent requests through this observer.
                        byte[] data;
                        try (var input = reply.body()) {
                            JsonNode card = JSON.readTree(input.readAllBytes());
                            routeCard(card, URI.create(target).getPort());
                            data = JSON.writeValueAsBytes(card);
                        }
                        exchange.sendResponseHeaders(reply.statusCode(), data.length);
                        exchange.getResponseBody().write(data);
                    } else {
                        exchange.sendResponseHeaders(reply.statusCode(), 0);
                        try (var input = reply.body(); var output = exchange.getResponseBody()) {
                            byte[] buffer = new byte[8192]; int count;
                            while ((count = input.read(buffer)) != -1) { output.write(buffer, 0, count); output.flush(); }
                        }
                    }
                } catch (Exception error) {
                    Files.writeString(evidence, "proxy-error=" + error.getClass().getName() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    try { exchange.sendResponseHeaders(502, -1); } catch (Exception ignored) { /* closed client */ }
                } finally { exchange.close(); }
            });
            server.start();
        }
        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        private void routeCard(JsonNode node, int targetPort) {
            if (node.isObject() && node.path("url").isTextual()) {
                URI advertised = URI.create(node.path("url").asText());
                if (advertised.getPort() == targetPort) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("url", baseUrl()
                            + Optional.ofNullable(advertised.getRawPath()).orElse("")
                            + (advertised.getRawQuery() == null ? "" : "?" + advertised.getRawQuery()));
                }
            }
            if (node.isContainerNode()) node.forEach(child -> routeCard(child, targetPort));
        }
        public void close() { server.stop(0); executor.shutdownNow(); }
    }

    /** Only bodies and status are persisted. No incoming or outgoing authentication headers. */
    private static final class LiveModelRecorder implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final Map<String, List<JsonNode>> requests = new ConcurrentHashMap<>();
        private final java.util.concurrent.atomic.AtomicInteger evidenceSequence = new java.util.concurrent.atomic.AtomicInteger();
        private final HttpClient upstream;
        LiveModelRecorder(Path evidence) throws Exception {
            HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).version(HttpClient.Version.HTTP_1_1);
            if ("false".equalsIgnoreCase(System.getenv("LLM_SSL_VERIFY"))) {
                SSLContext ssl = SSLContext.getInstance("TLS");
                ssl.init(null, new TrustManager[]{new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] c, String a) { }
                    public void checkServerTrusted(X509Certificate[] c, String a) { }
                }}, null);
                builder.sslContext(ssl);
            }
            upstream = builder.build();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try {
                    String[] path = exchange.getRequestURI().getPath().split("/", 3);
                    String agent = path[1];
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    List<JsonNode> list = requests.computeIfAbsent(agent, k -> new CopyOnWriteArrayList<>());
                    list.add(JSON.readTree(body)); int index = evidenceSequence.incrementAndGet();
                    Files.write(evidence.resolve(agent + "-model-" + index + ".json"), body);
                    String base = System.getenv("LLM_API_BASE").replaceAll("/+$", "");
                    String suffix = path[2].replaceFirst("^v1", "");
                    HttpRequest forwarded = HttpRequest.newBuilder(URI.create(base + suffix)).timeout(Duration.ofSeconds(300))
                            .header("Content-Type", "application/json").header("Authorization", "Bearer " + System.getenv("LLM_API_KEY"))
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
                    HttpResponse<java.io.InputStream> reply = upstream.send(forwarded, HttpResponse.BodyHandlers.ofInputStream());
                    Files.writeString(evidence.resolve(agent + "-http-" + index + ".txt"), Integer.toString(reply.statusCode()));
                    exchange.getResponseHeaders().set("Content-Type", reply.headers().firstValue("Content-Type").orElse("application/json"));
                    exchange.sendResponseHeaders(reply.statusCode(), 0);
                    try (var input = reply.body(); var output = exchange.getResponseBody()) { input.transferTo(output); }
                } catch (Exception error) {
                    Files.writeString(evidence.resolve("proxy-error.txt"), error.getClass().getName(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    try { exchange.sendResponseHeaders(502, -1); } catch (Exception ignored) { /* closed client */ }
                } finally { exchange.close(); }
            });
            server.start();
        }
        String baseUrl(String agent) { return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + agent + "/v1"; }
        public void close() { server.stop(0); executor.shutdownNow(); }
    }
}

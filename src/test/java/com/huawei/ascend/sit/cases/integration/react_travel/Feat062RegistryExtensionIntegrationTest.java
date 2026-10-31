package com.huawei.ascend.sit.cases.integration.react_travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static com.huawei.ascend.sit.cases.integration.react_travel.Feat062Fixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Tag("integration") @Tag("openjiuwen") @Tag("feat-062")
@Feature("FEAT-062: 直连模式支持 Agent 动态注册和发现")
class Feat062RegistryExtensionIntegrationTest {
    @Test @Tag("story-feat-062-extension-listener-recovery")
    @Story("FEAT-062.extension.listener-recovery: 目录事件消费者异常不阻断后续刷新") @DisplayName("Feat-062 目录事件消费者异常不阻断后续刷新")
    void feat062ExtensionListenerRecovery() throws Exception {
        try(var f=new Feat062Fixture("static")) {
            f.contractHost=true;f.model.live=false;f.start("hotel",Map.of());f.start("trip",Map.of());
            Path input=f.evidence.resolve("listener-source.json");
            var trip=entry(f,"trip","travel-trip","/a2a");var hotel=entry(f,"hotel","travel-hotel","/a2a/agents/travel-hotel");
            source(input,1,List.of(trip,hotel),false);
            f.start("mainplan",Map.of("feat062.source-file",input.toString(),"feat062.throw-listener","true"));
            awaitEntry(f,"mainplan","travel-trip",true);
            var changed=new LinkedHashMap<String,Object>(trip);var card=((JsonNode)trip.get("card")).deepCopy();
            ((ObjectNode)card).put("description","LISTENER_SECOND_VERSION");changed.put("card",card);
            changed.put("instances", List.of(Map.of("instanceId", "listener-recovery-v2",
                    "url", f.wires.get("trip").url()+"/a2a", "weight", 100)));
            source(input,2,List.of(changed,hotel),false);
            await().atMost(Duration.ofSeconds(12)).pollInterval(Duration.ofMillis(100)).untilAsserted(()->
                    assertThat(catalog(f,"mainplan").toString()).contains("LISTENER_SECOND_VERSION"));
            Feat062RegistrationScenarios.complete(f,"LISTENER_RECOVERED_BUSINESS");
            assertThat(Files.readString(f.evidence.resolve("mainplan.log"))).contains("FEAT062 single event-consumer failure");
        }
    }
    @Test @Tag("story-feat-062-extension-empty-registration")
    @Story("FEAT-062.extension.empty-registration: 注册开启但实例清单为空快速失败") @DisplayName("Feat-062 注册开启但实例清单为空快速失败")
    void feat062ExtensionEmptyRegistration() throws Exception {
        try(var f=new Feat062Fixture("dynamic")) {
            f.contractHost=true;
            var result=f.probeStartup("mainplan",Map.of("feat062.empty-instances","true",A2A+"registry.registration.enabled","true"));
            assertThat(result.exited()).as("empty InstanceCatalog must fail startup; %s",result.log()).isTrue();
            assertThat(result.diagnostic()).containsIgnoringCase("instance");
            assertThat(f.registry.requests).noneMatch(n->n.path("httpMethod").asText().equals("POST")&&n.path("path").asText().endsWith("/instances"));
        }
    }
    @Test @Tag("story-feat-062-auth-encrypted-token")
    @Story("FEAT-062.auth.encrypted-token: token 密文配置接入") @DisplayName("Feat-062 token 密文配置接入")
    void feat062AuthEncryptedToken() throws Exception {
        try(var f=new Feat062Fixture("dynamic")) {
            f.contractHost=true;
            var key=javax.crypto.KeyGenerator.getInstance("AES").generateKey();byte[] nonce=new byte[12];new java.security.SecureRandom().nextBytes(nonce);
            var cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,key,new javax.crypto.spec.GCMParameterSpec(128,nonce));
            byte[] encrypted=cipher.doFinal(f.token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] packed=new byte[nonce.length+encrypted.length];System.arraycopy(nonce,0,packed,0,nonce.length);System.arraycopy(encrypted,0,packed,nonce.length,encrypted.length);
            f.encryptedToken="ENC("+Base64.getEncoder().encodeToString(packed)+")";f.cipherKey=Base64.getEncoder().encodeToString(key.getEncoded());
            try { f.startTravel(false); }
            catch (org.awaitility.core.ConditionTimeoutException | IllegalStateException startupFailure) {
                var unauthorized = f.registry.requests.stream().filter(n ->
                        n.path("httpStatus").asInt() == 401 && n.has("authorizationMatches")).toList();
                if (unauthorized.isEmpty()) throw startupFailure;
                f.save("encrypted-token-authorization-observations.json", unauthorized);
                assertThat(unauthorized).as("registry client must decrypt configured ciphertext before authorization")
                        .allSatisfy(n -> assertThat(n.path("authorizationMatches").asBoolean()).isTrue());
                throw startupFailure;
            }
            Feat062RegistrationScenarios.complete(f,"ENCRYPTED_TOKEN");
            for(String role:List.of("mainplan","trip","hotel")) {
                var count=request("GET",f.wires.get(role).url()+"/feat062/decrypt-count",null,Map.of());
                assertThat(Integer.parseInt(count.body())).as("registry client used public decryptor: %s",role).isPositive();
            }
            assertThat(f.registry.requests).allSatisfy(n->assertThat(n.path("authorizationMatches").asBoolean()).isTrue());
            try(var files=Files.walk(f.evidence)) {
                for(Path path:files.filter(p->p.toString().endsWith(".log")).toList()) assertThat(Files.readString(path)).doesNotContain(f.token,f.encryptedToken);
            }
        }
    }
    static Map<String,Object> entry(Feat062Fixture f, String role, String id, String endpoint) throws Exception {
        String base=role.equals("trip")?"":endpoint;
        JsonNode card=JSON.readTree(request("GET",f.wires.get(role).url()+base+"/.well-known/agent-card.json",null,Map.of()).body());
        return Map.of("agentId",id,"card",card,"timeoutSeconds",10,"isStreaming",true,
                "instances",List.of(Map.of("instanceId",id+"-"+role,"url",f.wires.get(role).url()+endpoint,"weight",100)));
    }
    static void source(Path path,long version,List<Map<String,Object>> entries,boolean fail) throws Exception {
        Path temp=path.resolveSibling("next-"+UUID.randomUUID()+".json");
        Files.writeString(temp,JSON.writeValueAsString(Map.of("version",version,"entries",entries,"fail",fail)));
        Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    static JsonNode catalog(Feat062Fixture f,String role) throws Exception {
        var r=request("GET",f.wires.get(role).url()+"/feat062/catalog",null,Map.of());
        assertThat(r.statusCode()).isEqualTo(200); return JSON.readTree(r.body());
    }
    static void awaitEntry(Feat062Fixture f,String role,String id,boolean present) {
        await().atMost(Duration.ofSeconds(8)).pollInterval(Duration.ofMillis(100)).ignoreExceptions().untilAsserted(()->
                assertThat(catalog(f,role).path("entries").toString().contains("\"name\":\""+id+"\"")).isEqualTo(present));
    }
    static void awaitCallable(Feat062Fixture f,String role,String id) {
        await().atMost(Duration.ofSeconds(8)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            List<JsonNode> found = new ArrayList<>();
            catalog(f,role).path("entries").forEach(entry -> {
                if (entry.path("name").asText().equals(id)) found.add(entry);
            });
            assertThat(found).hasSize(1);
            assertThat(found.getFirst().path("instances").size()).isGreaterThan(0);
        });
    }
    @Test @Tag("story-feat-062-extension-refresh-exception")
    @Story("FEAT-062.extension.refresh-exception: 目录源意外异常保留旧目录") @DisplayName("Feat-062 目录源意外异常保留旧目录")
    void feat062ExtensionRefreshException() throws Exception {
        try(var f=new Feat062Fixture("static")) {
            f.contractHost=true; f.model.live=false; f.start("hotel",Map.of());f.start("trip",Map.of());
            Path input=f.evidence.resolve("source.json");
            var trip=entry(f,"trip","travel-trip","/a2a");var hotel=entry(f,"hotel","travel-hotel","/a2a/agents/travel-hotel");
            source(input,1,List.of(trip,hotel),false);
            f.start("mainplan",Map.of("feat062.source-file",input.toString()));
            awaitEntry(f,"mainplan","travel-trip",true);Feat062RegistrationScenarios.complete(f,"SOURCE_INITIAL");
            source(input,2,List.of(),true);
            await().atMost(Duration.ofSeconds(14)).pollInterval(Duration.ofMillis(200)).until(()->
                    JSON.readTree(request("GET",f.wires.get("mainplan").url()+"/feat062/source-observations",null,Map.of()).body())
                            .path("failures").asInt()>0);
            f.saveText("source-failure-observed.json", request("GET",f.wires.get("mainplan").url()+"/feat062/source-observations",null,Map.of()).body());
            Feat062RegistrationScenarios.complete(f,"SOURCE_FAILURE_RETAINED");
            var changed=new LinkedHashMap<String,Object>(trip);var card=((JsonNode)trip.get("card")).deepCopy();
            ((ObjectNode)card).put("description","FEAT062 source recovered new card");changed.put("card",card);
            changed.put("instances", List.of(Map.of("instanceId", "source-recovered-v3",
                    "url", f.wires.get("trip").url()+"/a2a", "weight", 100)));
            source(input,3,List.of(changed,hotel),false);
            await().atMost(Duration.ofSeconds(8)).pollInterval(Duration.ofMillis(100)).untilAsserted(()->
                    assertThat(catalog(f,"mainplan").toString()).contains("FEAT062 source recovered new card"));
            Feat062RegistrationScenarios.complete(f,"SOURCE_RECOVERED");
        }
    }
    void hosted(String operation) throws Exception {
        try(var f=new Feat062Fixture("static")) {
            f.contractHost=true;f.model.live=false;f.start("trip",Map.of());f.startReplica("trip","trip-local");
            Path input=f.evidence.resolve("hosted-source.json"); var global=entry(f,"trip","travel-trip","/a2a");
            source(input,1,operation.equals("add")?List.of():List.of(global),false);
            Map<String,String> settings=new LinkedHashMap<>();settings.put("feat062.hosted-caller","true");settings.put("feat062.source-file",input.toString());
            // Only this SPI host uses explicit local caller configuration. The normal hotel remains a leaf.
            if(operation.equals("local")) {
                String prefix=A2A+"agents.travel-hotel.remote-agents[0].";
                settings.put(prefix+"name","travel-trip");settings.put(prefix+"url",f.wires.get("trip-local").url());settings.put(prefix+"streaming","true");
            }
            f.start("hotel",settings);
            if(operation.equals("add")) source(input,2,List.of(global),false);
            if(operation.equals("update")) {
                awaitEntry(f,"hotel","travel-trip",true);
                for(String id:List.of("travel-hotel","travel-hotel-alt")) hostedCall(f,id,true);
                var changed=new LinkedHashMap<String,Object>(global);var card=((JsonNode)global.get("card")).deepCopy();
                ((ObjectNode)card).put("description","HOSTED_UPDATED_F062");
                for (JsonNode skill : card.path("skills")) ((ObjectNode)skill).put("description","HOSTED_UPDATED_F062");
                changed.put("card",card);
                // Change the candidate identity as well so the independent card-only
                // registry defect cannot mask tool reconciliation in this hosted case.
                changed.put("instances",List.of(Map.of("instanceId","hosted-updated-v2",
                        "url",f.wires.get("trip").url()+"/a2a","weight",100)));
                source(input,2,List.of(changed),false);
                await().atMost(Duration.ofSeconds(8)).pollInterval(Duration.ofMillis(100)).untilAsserted(()->
                        assertThat(catalog(f,"hotel").toString()).contains("HOSTED_UPDATED_F062"));
            }
            awaitEntry(f,"hotel","travel-trip",true);
            for(String id:List.of("travel-hotel","travel-hotel-alt")) {
                hostedCall(f,id,true);
                if(operation.equals("update")) assertThat(f.models.get("hotel").getLast().path("tools").toString()).contains("HOSTED_UPDATED_F062");
            }
            if(operation.equals("delete")) {
                int before=f.wires.get("trip").requests.size();source(input,3,List.of(),false);awaitEntry(f,"hotel","travel-trip",false);
                for(String id:List.of("travel-hotel","travel-hotel-alt")) hostedCall(f,id,false);
                assertThat(f.wires.get("trip").requests.size()).isEqualTo(before);
            }
            if(operation.equals("local")) {
                assertThat(f.wires.get("trip-local").requests).anyMatch(n->n.at("/body/method").asText().startsWith("Send"));
                assertThat(f.wires.get("trip").requests).anyMatch(n->n.at("/body/method").asText().startsWith("Send"));
                var next=new LinkedHashMap<String,Object>(global);next.put("instances",List.of(Map.of("instanceId","global-new","url",f.wires.get("trip").url()+"/a2a","weight",100)));
                source(input,3,List.of(next),false);
                for(String id:List.of("travel-hotel","travel-hotel-alt")) {
                    int localBefore=f.wires.get("trip-local").requests.size(),globalBefore=f.wires.get("trip").requests.size();
                    hostedCall(f,id,true);
                    assertThat(f.wires.get("trip-local").requests.size()-localBefore).isEqualTo(id.equals("travel-hotel")?1:0);
                    assertThat(f.wires.get("trip").requests.size()-globalBefore).isEqualTo(id.equals("travel-hotel")?0:1);
                }
            }
        }
    }
    static void hostedCall(Feat062Fixture f,String id,boolean available) throws Exception {
        String marker="HOSTED_CALL_TRIP_"+id+"_"+UUID.randomUUID();String endpoint="/a2a/agents/"+id;
        var call=f.send("hotel",endpoint,f.prompt(marker),false,marker,null);
        var task=Feat062DirectRegistryReactAgentTest.completed(f,call,"hotel",endpoint);
        var model=f.modelRequests("hotel",marker);assertThat(model).isNotEmpty();
        boolean exposed=model.stream().anyMatch(n->n.path("tools").toString().contains("travel-trip"));
        assertThat(exposed).as("hosted instance tool view follows global catalog").isEqualTo(available);
        if(available) assertThat(f.toolResults("hotel",marker,"travel-trip")).isNotEmpty();
        assertThat(task.path("artifacts").toString()).contains(f.inventoryCanary);
    }
    @Test @Tag("story-feat-062-hosted-catalog-inherit-add")
    @Story("FEAT-062.hosted-catalog.inherit-add: 全局新增传播到 hosted 继承目录") @DisplayName("Feat-062 全局新增传播到 hosted 继承目录")
    void feat062HostedCatalogInheritAdd() throws Exception { hosted("add"); }
    @Test @Tag("story-feat-062-hosted-catalog-inherit-update")
    @Story("FEAT-062.hosted-catalog.inherit-update: 全局更新传播到 hosted 继承目录") @DisplayName("Feat-062 全局更新传播到 hosted 继承目录")
    void feat062HostedCatalogInheritUpdate() throws Exception { hosted("update"); }
    @Test @Tag("story-feat-062-hosted-catalog-inherit-delete")
    @Story("FEAT-062.hosted-catalog.inherit-delete: 全局删除传播到 hosted 继承目录") @DisplayName("Feat-062 全局删除传播到 hosted 继承目录")
    void feat062HostedCatalogInheritDelete() throws Exception { hosted("delete"); }
    @Test @Tag("story-feat-062-hosted-catalog-local-priority")
    @Story("FEAT-062.hosted-catalog.local-priority: 实例局部静态优先于全局动态") @DisplayName("Feat-062 实例局部静态优先于全局动态")
    void feat062HostedCatalogLocalPriority() throws Exception { hosted("local"); }
}

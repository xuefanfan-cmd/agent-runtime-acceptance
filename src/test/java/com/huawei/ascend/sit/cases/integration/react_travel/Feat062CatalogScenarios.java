package com.huawei.ascend.sit.cases.integration.react_travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static com.huawei.ascend.sit.cases.integration.react_travel.Feat062Fixture.*;
import static com.huawei.ascend.sit.cases.integration.react_travel.Feat062RegistrationScenarios.complete;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

final class Feat062CatalogScenarios {
    static Map<String,String> statics(Feat062Fixture f) {
        return Map.of(A2A+"remote-agents[0].name","travel-trip",A2A+"remote-agents[0].url",f.wires.get("trip").url(),
                A2A+"remote-agents[0].streaming","true",A2A+"remote-agents[1].name","travel-hotel",
                A2A+"remote-agents[1].url",f.wires.get("hotel").url()+"/a2a/agents/travel-hotel",A2A+"remote-agents[1].streaming","true");
    }
    static void registrationOnly() throws Exception {
        try(var f=new Feat062Fixture("dynamic")) {
            f.model.live=false; f.start("hotel",Map.of(A2A+"registry.registration.enabled","false"));
            f.start("trip",Map.of(A2A+"registry.registration.enabled","false"));
            var settings=new LinkedHashMap<>(statics(f));settings.put(A2A+"registry.discovery.enabled","false");settings.put(A2A+"registry.registration.enabled","true");
            f.start("mainplan",settings);complete(f,"REGISTER_ONLY");
            var directory=f.registryJson("GET","/agents",null);assertThat(directory.path("agents").size()).isEqualTo(1);
            assertThat(directory.path("agents").get(0).path("agentId").asText()).isEqualTo("travel-mainplan");
            assertThat(f.registryLinks.get("mainplan").requests).noneMatch(n->n.path("httpMethod").asText().equals("GET")
                    ||n.path("path").asText().contains("route-handle"));
        }
    }
    static void bothEnabled() throws Exception {
        try(var f=new Feat062Fixture("dynamic")) {
            f.model.live=false;f.start("hotel",Map.of());f.start("trip",Map.of());f.awaitRegistry();
            f.start("mainplan",Map.of(A2A+"registry.registration.enabled","true"));
            complete(f,"BOTH_SWITCHES");
            var directory=f.registryJson("GET","/agents",null);Set<String> ids=new HashSet<>();directory.path("agents").forEach(n->ids.add(n.path("agentId").asText()));
            assertThat(ids).containsExactlyInAnyOrder("travel-mainplan","travel-trip","travel-hotel","travel-hotel-alt");
            assertThat(f.registryLinks.get("mainplan").requests).anyMatch(n->n.path("path").asText().equals("/v2/agents/travel-mainplan/instances")
                    &&n.path("httpMethod").asText().equals("POST"));
            assertThat(f.registryLinks.get("mainplan").requests).anyMatch(n->n.path("path").asText().equals("/v2/route-handle:resolve"));
        }
    }
    static void cachedOutage() throws Exception {
        try(var f=new Feat062Fixture("dynamic")) {
            f.startTravel(false);complete(f,"DYNAMIC_WARM");
            f.registryLinks.get("mainplan").unavailable=true;
            long start=System.nanoTime();
            for(int round=0;round<3;round++) {
                complete(f,"CACHED_OUTAGE_"+round);
                long t=System.nanoTime();await().atMost(Duration.ofSeconds(6)).pollInterval(Duration.ofMillis(100))
                        .until(()->System.nanoTime()-t>Duration.ofSeconds(5).toNanos());
            }
            assertThat(System.nanoTime()-start).isGreaterThan(Duration.ofSeconds(15).toNanos());
            assertThat(f.registryLinks.get("trip").requests).anyMatch(n->n.path("path").asText().endsWith("/heartbeat"));
            f.registryLinks.get("mainplan").unavailable=false;complete(f,"AFTER_CACHED_OUTAGE");
        }
    }
    static void uncachedStaticOutage() throws Exception {
        try(var f=new Feat062Fixture("dynamic")) {
            f.model.live=false; f.start("hotel",Map.of());f.start("trip",Map.of());f.awaitRegistry();
            f.registry.unavailable=true;f.start("mainplan",statics(f));
            complete(f,"UNCACHED_STATIC_OUTAGE");f.registry.unavailable=false;
        }
    }
    static void precedence(boolean empty) throws Exception {
        try(var f=new Feat062Fixture("dynamic")) {
            f.model.live=false;f.start("hotel",Map.of());f.start("trip",Map.of());f.awaitRegistry();
            try(var decoy=f.new Wire("http://127.0.0.1:"+f.ports.get("trip"),"static-decoy",null)) {
                var settings=new LinkedHashMap<>(statics(f));settings.put(A2A+"remote-agents[0].url",decoy.url());
                f.start("mainplan",settings);complete(f,"DYNAMIC_PRECEDENCE");
                assertThat(decoy.requests).noneMatch(n->n.path("httpMethod").asText().equals("POST"));
                if(empty) {
                    f.stacks.get("trip").stop("trip");
                    await().atMost(Duration.ofSeconds(8)).pollInterval(Duration.ofMillis(100)).untilAsserted(()->
                            assertThat(f.registryJson("GET","/agents/travel-trip/instances",null).path("instances").size()).isZero());
                    assertThat(f.registryRequest("GET","/agents/travel-trip/card",null).statusCode()).isEqualTo(200);
                    // Wait for an externally observed post-removal discovery, then one full TTL for publication.
                    int before=f.registryLinks.get("mainplan").requests.size();
                    await().atMost(Duration.ofSeconds(7)).pollInterval(Duration.ofMillis(100)).until(()->
                            f.registryLinks.get("mainplan").requests.stream().skip(before)
                                    .anyMatch(n->n.path("path").asText().startsWith("/v2/agents/travel-trip/instances")));
                    String marker="EMPTY_CANDIDATES_"+UUID.randomUUID();
                    var result=f.send("mainplan","/a2a",f.prompt(marker),false,marker,null);
                    var task=Feat062DirectRegistryReactAgentTest.current(f,result,"mainplan","/a2a");
                    assertThat(Feat062DirectRegistryReactAgentTest.state(task)).isIn("COMPLETED","FAILED","REJECTED");
                    assertThat(f.toolResults("mainplan",marker,"travel-trip").toString()).contains("REMOTE_UNAVAILABLE");
                    assertThat(decoy.requests).noneMatch(n->n.path("httpMethod").asText().equals("POST"));
                }
            }
        }
    }
    static void resolveAddress() throws Exception {
        for(boolean explicit:List.of(false,true)) try(var f=new Feat062Fixture("dynamic")) {
            f.model.live=false;f.start("hotel",Map.of());f.start("trip",Map.of());f.awaitRegistry();
            try(var decoy=f.new Wire("http://127.0.0.1:"+f.ports.get("trip"),"declared-decoy",null)) {
                var original=JSON.readTree(f.registryJson("GET","/agents/travel-trip/card",null).path("rawJson").asText());
                Feat062StaticScenarios.replaceUrls(original,f.wires.get("trip").url(),decoy.url());
                var registration=f.registry.requests.stream().filter(n->n.path("httpMethod").asText().equals("POST")
                        &&n.path("path").asText().equals("/v2/agents/travel-trip/instances")).findFirst().orElseThrow().path("body").deepCopy();
                ((ObjectNode)registration).put("agentCardJson",original.toString());
                f.registryJson("POST","/agents/travel-trip/instances",registration);
                f.start("mainplan",explicit?Map.of(A2A+"registry.discovery.call-address-policy","resolve"):Map.of());
                complete(f,"RESOLVE_ADDRESS");
                assertThat(decoy.requests).isEmpty();
                assertThat(f.wires.get("trip").requests).anyMatch(n->n.at("/body/method").asText().equals("SendMessage"));
                Feat062RegistrationScenarios.compareCard(f,"hotel","travel-hotel","/a2a/agents/travel-hotel");
            }
        }
    }
}

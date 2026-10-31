package com.huawei.ascend.sit.cases.integration.react_travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import static com.huawei.ascend.sit.cases.integration.react_travel.Feat062Fixture.*;
import static com.huawei.ascend.sit.cases.integration.react_travel.Feat062RegistrationScenarios.complete;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

final class Feat062StaticScenarios {
    static void retry() throws Exception {
        try (var f = new Feat062Fixture("static")) {
            f.contractHost = true; f.model.live = false; f.start("hotel", Map.of()); f.start("trip", Map.of());
            var trip = f.wires.get("trip"); trip.unavailable = true;
            f.start("mainplan", Map.of());
            List<JsonNode> first = cards(trip);
            assertThat(first).hasSize(1); long t0 = first.getFirst().path("nanoTime").asLong(); trip.unavailable = false;
            await().atMost(Duration.ofSeconds(35)).pollInterval(Duration.ofMillis(100)).until(() -> cards(trip).size() > 1);
            long delta = cards(trip).get(1).path("nanoTime").asLong() - t0;
            f.save("static-retry-timing.json", Map.of("nanos", delta, "requests", cards(trip)));
            assertThat(Duration.ofNanos(delta).toMillis()).isBetween(29000L, 33000L);
            Feat062RegistryExtensionIntegrationTest.awaitEntry(f,"mainplan","travel-trip",true);
            complete(f, "STATIC_RETRY");
        }
    }
    static List<JsonNode> cards(Feat062Fixture.Wire wire) {
        return wire.requests.stream().filter(n -> n.path("httpMethod").asText().equals("GET")
                && n.path("path").asText().endsWith("/.well-known/agent-card.json")).toList();
    }
    static void permanentCache() throws Exception {
        try (var f = new Feat062Fixture("mixed-trip-static")) {
            f.startTravel(false); complete(f, "CACHE_WARM");
            var trip = f.wires.get("trip"); int count = cards(trip).size();
            trip.transform = n -> ((ObjectNode)n).put("description", "changed-card-not-to-be-reloaded");
            long start = System.nanoTime();
            await().atMost(Duration.ofSeconds(68)).pollInterval(Duration.ofSeconds(1)).until(() -> {
                assertThat(cards(trip).size()).as("successful static card stays cached independently of dynamic TTL").isEqualTo(count);
                return System.nanoTime() - start > Duration.ofSeconds(62).toNanos();
            });
            complete(f, "CACHE_STILL_VALID"); assertThat(cards(trip)).hasSize(count);
        }
    }
    static void normalization() throws Exception {
        for (String suffix : List.of("", "/", "///")) try (var f = new Feat062Fixture("static")) {
            f.model.live=false; f.start("hotel", Map.of()); f.start("trip", Map.of());
            f.start("mainplan", Map.of(A2A + "remote-agents[0].name", "travel-trip",
                    A2A + "remote-agents[0].url", f.wires.get("trip").url() + suffix,
                    A2A + "remote-agents[0].streaming", "true",
                    A2A + "remote-agents[1].name", "travel-hotel",
                    A2A + "remote-agents[1].url", f.wires.get("hotel").url() + "/a2a/agents/travel-hotel" + suffix,
                    A2A + "remote-agents[1].streaming", "true"));
            complete(f, "NORMALIZE");
            assertThat(cards(f.wires.get("trip"))).isNotEmpty().allSatisfy(n -> assertThat(n.path("path").asText()).isEqualTo("/.well-known/agent-card.json"));
            assertThat(cards(f.wires.get("hotel"))).isNotEmpty().allSatisfy(n -> assertThat(n.path("path").asText()).isEqualTo("/a2a/agents/travel-hotel/.well-known/agent-card.json"));
        }
    }
    static void invalidEntry() throws Exception {
        for (String field : List.of("name", "url")) for (String value : List.of("", " ")) try (var f = new Feat062Fixture("static")) {
            f.model.live=false;
            var overrides=new LinkedHashMap<String,String>();
            overrides.put(A2A+"remote-agents[0].name", "travel-trip"); overrides.put(A2A+"remote-agents[0].url", "http://127.0.0.1:1");
            overrides.put(A2A+"remote-agents[1].name", "travel-hotel"); overrides.put(A2A+"remote-agents[1].url", "http://127.0.0.1:1");
            overrides.put(A2A+"remote-agents[1]."+field, value);
            var result=f.probeStartup("mainplan", overrides);
            assertThat(result.exited()).as("invalid indexed static entry must fail startup; %s",result.log()).isTrue();
            assertThat(result.diagnostic()).contains("remote-agents[1]",field);
        }
    }
    static void streamingDisabled() throws Exception {
        try(var f=new Feat062Fixture("static")) {
            f.model.live=false; f.start("hotel",Map.of());f.start("trip",Map.of());
            f.start("mainplan",Map.of(A2A+"remote-agents[0].name","travel-trip",A2A+"remote-agents[0].url",f.wires.get("trip").url(),
                    A2A+"remote-agents[0].streaming","false",A2A+"remote-agents[1].name","travel-hotel",
                    A2A+"remote-agents[1].url",f.wires.get("hotel").url()+"/a2a/agents/travel-hotel",A2A+"remote-agents[1].streaming","true"));
            String marker="GATE_"+UUID.randomUUID();var call=f.send("mainplan","/a2a",f.prompt(marker),true,marker,null);
            var task=Feat062DirectRegistryReactAgentTest.completed(f,call,"mainplan","/a2a");
            Feat062DirectRegistryReactAgentTest.assertChain(f,marker,task);
            assertThat(call.contentType()).contains("text/event-stream");
            assertThat(f.wires.get("trip").calls(marker)).anyMatch(n->n.at("/body/method").asText().equals("SendMessage"))
                    .noneMatch(n->n.at("/body/method").asText().equals("SendStreamingMessage"));
            assertThat(f.wires.get("hotel").calls(marker)).anyMatch(n->n.at("/body/method").asText().equals("SendStreamingMessage"));
        }
    }
    static void replaceUrls(JsonNode node,String old,String next) {
        if(node.isObject()&&node.path("url").isTextual()&&node.path("url").asText().startsWith(old))
            ((ObjectNode)node).put("url",next+node.path("url").asText().substring(old.length()));
        if(node.isContainerNode()) node.forEach(n->replaceUrls(n,old,next));
    }
    static void addressSource() throws Exception {
        try(var f=new Feat062Fixture("static")) {
            f.model.live=false;f.start("hotel",Map.of());f.start("trip",Map.of());
            var original=f.wires.get("trip");
            try(var decoy=f.new Wire("http://127.0.0.1:"+f.ports.get("trip"),"decoy",null)) {
                original.transform=n->replaceUrls(n,original.url(),decoy.url());
                f.start("mainplan",Map.of());complete(f,"STATIC_ADDRESS");
                assertThat(decoy.requests.stream().filter(n->n.path("httpMethod").asText().equals("POST")).count())
                        .as("Feature §6 requires configured static URL; card-declared decoy gets zero RPC").isZero();
                assertThat(original.requests).anyMatch(n->n.at("/body/method").asText().equals("SendMessage"));
            }
        }
    }
}
